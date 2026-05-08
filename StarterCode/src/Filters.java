import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import java.io.File;

public class Filters {
    String file;
    BufferedImage image;

    Filters(String filename) throws IOException {
        this.file = filename;
        this.image = ImageIO.read(new File(filename));
        if (this.image == null) {
            throw new IOException("Could not load image: " + filename);
        }
    }

    public int computeLuminosity(int r, int g, int b) {
        return (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
    }

    private static class CumulativeResult {
        int[] cumulative;
        int cdfMin;

        CumulativeResult(int[] cumulative, int cdfMin) {
            this.cumulative = cumulative;
            this.cdfMin = cdfMin;
        }
    }

    private CumulativeResult computeCumulative(int[] hist) {
        int[] cumulative = new int[256];
        cumulative[0] = hist[0];
        for (int i = 1; i < 256; i++) {
            cumulative[i] = cumulative[i - 1] + hist[i];
        }

        int cdfMin = 0;
        for (int i = 0; i < 256; i++) {
            if (cumulative[i] != 0) {
                cdfMin = cumulative[i];
                break;
            }
        }

        return new CumulativeResult(cumulative, cdfMin);
    }

    private BufferedImage applyEqualization(BufferedImage src, int[] cumulative, int cdfMin) {
        int width = src.getWidth();
        int height = src.getHeight();
        int totalPixels = width * height;
        int denom = totalPixels - cdfMin;

        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = src.getRGB(x, y);

                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                int lum = computeLuminosity(r, g, b);

                int newLum;
                if (denom <= 0) {
                    newLum = lum;
                } else {
                    double cdf = (double) (cumulative[lum] - cdfMin) / denom;
                    newLum = (int) Math.round(255.0 * cdf);
                }

                if (newLum < 0) newLum = 0;
                if (newLum > 255) newLum = 255;

                int grayRgb = (0xFF << 24) | (newLum << 16) | (newLum << 8) | newLum;
                out.setRGB(x, y, grayRgb);
            }
        }

        return out;
    }

    private void writeImage(BufferedImage img, String outputFile) throws IOException {
        String format = "jpg";
        int dot = outputFile.lastIndexOf('.');
        if (dot > 0 && dot < outputFile.length() - 1) {
            format = outputFile.substring(dot + 1);
        }
        ImageIO.write(img, format, new File(outputFile));
    }

    public void HistogramFilter(String outputFile) throws IOException {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] hist = new int[256];

        int totalPixels = width * height;
        System.out.println();
        System.out.println("Total pixels: " + totalPixels);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int lum = computeLuminosity(r, g, b);
                hist[lum]++;
            }
        }

        CumulativeResult cumRes = computeCumulative(hist);
        BufferedImage out = applyEqualization(image, cumRes.cumulative, cumRes.cdfMin);
        writeImage(out, outputFile);
    }

    public void sequentialHistogramFilter(String outputFile) throws IOException {
        HistogramFilter(outputFile);
    }

    public void multithreadedHistogramFilter(String outputFile, int value) throws IOException {
        int numThreads = value > 0 ? value : 2;
        int width = image.getWidth();
        int height = image.getHeight();

        int[][] localHists = new int[numThreads][256];
        Thread[] threads = new Thread[numThreads];

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                int startRow = threadId * height / numThreads;
                int endRow = (threadId + 1) * height / numThreads;

                for (int y = startRow; y < endRow; y++) {
                    for (int x = 0; x < width; x++) {
                        int rgb = image.getRGB(x, y);
                        int r = (rgb >> 16) & 0xFF;
                        int g = (rgb >> 8) & 0xFF;
                        int b = rgb & 0xFF;
                        int lum = computeLuminosity(r, g, b);
                        localHists[threadId][lum]++;
                    }
                }
            });
            threads[t].start();
        }

        for (int t = 0; t < numThreads; t++) {
            try {
                threads[t].join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        int[] hist = new int[256];
        for (int t = 0; t < numThreads; t++) {
            for (int i = 0; i < 256; i++) {
                hist[i] += localHists[t][i];
            }
        }

        CumulativeResult cumRes = computeCumulative(hist);
        BufferedImage out = applyEqualization(image, cumRes.cumulative, cumRes.cdfMin);
        writeImage(out, outputFile);
    }

    public void threadPoolHistogramFilter(String outputFile, int value) throws IOException {
        int numThreads = value > 0 ? value : 2;
        int width = image.getWidth();
        int height = image.getHeight();

        int[][] localHists = new int[numThreads][256];
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    int startRow = threadId * height / numThreads;
                    int endRow = (threadId + 1) * height / numThreads;

                    for (int y = startRow; y < endRow; y++) {
                        for (int x = 0; x < width; x++) {
                            int rgb = image.getRGB(x, y);
                            int r = (rgb >> 16) & 0xFF;
                            int g = (rgb >> 8) & 0xFF;
                            int b = rgb & 0xFF;
                            int lum = computeLuminosity(r, g, b);
                            localHists[threadId][lum]++;
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdown();
        }

        int[] hist = new int[256];
        for (int t = 0; t < numThreads; t++) {
            for (int i = 0; i < 256; i++) {
                hist[i] += localHists[t][i];
            }
        }

        CumulativeResult cumRes = computeCumulative(hist);
        BufferedImage out = applyEqualization(image, cumRes.cumulative, cumRes.cdfMin);
        writeImage(out, outputFile);
    }

    public void forkJoinHistogramFilter(String outputFile, int value) throws IOException {
        int parallelism = value > 0 ? value : Runtime.getRuntime().availableProcessors();
        int width = image.getWidth();
        int height = image.getHeight();

        ForkJoinPool pool = new ForkJoinPool(parallelism);
        try {
            HistogramTask root = new HistogramTask(this, image, 0, height, width);
            int[] hist = pool.invoke(root);

            CumulativeResult cumRes = computeCumulative(hist);
            BufferedImage out = applyEqualization(image, cumRes.cumulative, cumRes.cdfMin);
            writeImage(out, outputFile);
        } finally {
            pool.shutdown();
        }
    }

    public void completableFutureHistogramFilter(String outputFile, int value) throws IOException {
        int numTasks = value > 0 ? value : Runtime.getRuntime().availableProcessors();
        int width = image.getWidth();
        int height = image.getHeight();

        @SuppressWarnings("unchecked")
        CompletableFuture<int[]>[] futures = new CompletableFuture[numTasks];

        for (int t = 0; t < numTasks; t++) {
            final int startRow = t * height / numTasks;
            final int endRow = (t + 1) * height / numTasks;

            futures[t] = CompletableFuture.supplyAsync(() -> {
                int[] hist = new int[256];
                for (int y = startRow; y < endRow; y++) {
                    for (int x = 0; x < width; x++) {
                        int rgb = image.getRGB(x, y);
                        int r = (rgb >> 16) & 0xFF;
                        int g = (rgb >> 8) & 0xFF;
                        int b = rgb & 0xFF;
                        int lum = computeLuminosity(r, g, b);
                        hist[lum]++;
                    }
                }
                return hist;
            });
        }

        CompletableFuture.allOf(futures).join();

        int[] hist = new int[256];
        for (int t = 0; t < numTasks; t++) {
            int[] local = futures[t].join();
            for (int i = 0; i < 256; i++) {
                hist[i] += local[i];
            }
        }

        CumulativeResult cumRes = computeCumulative(hist);
        BufferedImage out = applyEqualization(image, cumRes.cumulative, cumRes.cdfMin);
        writeImage(out, outputFile);
    }
}