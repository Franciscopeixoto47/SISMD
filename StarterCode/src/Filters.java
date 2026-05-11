import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

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
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);

        int totalPixels = width * height;
        int denom = totalPixels - cumRes.cdfMin;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);

                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                int lum = computeLuminosity(r, g, b);

                int newLum;
                if (denom <= 0) {
                    newLum = lum;
                } else {
                    double cdf = (double) (cumRes.cumulative[lum] - cumRes.cdfMin) / denom;
                    newLum = (int) Math.round(255.0 * cdf);
                }

                if (newLum < 0) newLum = 0;
                if (newLum > 255) newLum = 255;

                int grayRgb = (0xFF << 24) | (newLum << 16) | (newLum << 8) | newLum;
                out.setRGB(x, y, grayRgb);
            }
        }

        writeImage(out, outputFile);
    }

    public void sequentialHistogramFilter(String outputFile) throws IOException {
        HistogramFilter(outputFile);
    }

    public void multithreadedHistogramFilter(String outputFile, int value) throws IOException {
        int numConsumers = value > 0 ? value : 2;
        int width = image.getWidth();
        int height = image.getHeight();

        final int blockSize = Math.max(1, height / (numConsumers * 2));
        final java.util.LinkedList<int[]> taskQueue = new java.util.LinkedList<>();
        final int[] hist = new int[256];
        final Object queueLock = new Object();
        final boolean[] producerDone = { false };

        Thread producer = new Thread(() -> {
            for (int startRow = 0; startRow < height; startRow += blockSize) {
                int endRow = Math.min(startRow + blockSize, height);
                synchronized (queueLock) {
                    taskQueue.add(new int[] { startRow, endRow });
                    queueLock.notifyAll();
                }
            }

            synchronized (queueLock) {
                producerDone[0] = true;
                queueLock.notifyAll();
            }
        });

        Thread[] consumers = new Thread[numConsumers];

        for (int t = 0; t < numConsumers; t++) {
            consumers[t] = new Thread(() -> {
                int[] localHist = new int[256];

                while (true) {
                    int[] task;

                    synchronized (queueLock) {
                        while (taskQueue.isEmpty() && !producerDone[0]) {
                            try {
                                queueLock.wait();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }

                        if (taskQueue.isEmpty() && producerDone[0]) {
                            break;
                        }

                        task = taskQueue.removeFirst();
                    }

                    int startRow = task[0];
                    int endRow = task[1];

                    for (int y = startRow; y < endRow; y++) {
                        for (int x = 0; x < width; x++) {
                            int rgb = image.getRGB(x, y);
                            int r = (rgb >> 16) & 0xFF;
                            int g = (rgb >> 8) & 0xFF;
                            int b = rgb & 0xFF;
                            int lum = computeLuminosity(r, g, b);
                            localHist[lum]++;
                        }
                    }
                }

                synchronized (hist) {
                    for (int i = 0; i < 256; i++) {
                        hist[i] += localHist[i];
                    }
                }
            });
        }

        producer.start();
        for (Thread consumer : consumers) consumer.start();

        try {
            producer.join();
            for (Thread consumer : consumers) consumer.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        CumulativeResult cumRes = computeCumulative(hist);
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        int totalPixels = width * height;
        int denom = totalPixels - cumRes.cdfMin;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int lum = computeLuminosity(r, g, b);

                int newLum;
                if (denom <= 0) {
                    newLum = lum;
                } else {
                    double cdf = (double) (cumRes.cumulative[lum] - cumRes.cdfMin) / denom;
                    newLum = (int) Math.round(255.0 * cdf);
                }

                if (newLum < 0) newLum = 0;
                if (newLum > 255) newLum = 255;

                int grayRgb = (0xFF << 24) | (newLum << 16) | (newLum << 8) | newLum;
                out.setRGB(x, y, grayRgb);
            }
        }

        writeImage(out, outputFile);
    }

    public void threadPoolHistogramFilter(String outputFile, int value) throws IOException {
        int numThreads = value > 0 ? value : 2;
        int width = image.getWidth();
        int height = image.getHeight();

        final int[][] localHists = new int[numThreads][256];
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;

            executor.submit(() -> {
                try {
                    int startRow = threadId * height / numThreads;
                    int endRow = (threadId + 1) * height / numThreads;
                    int[] local = localHists[threadId];

                    for (int y = startRow; y < endRow; y++) {
                        for (int x = 0; x < width; x++) {
                            int rgb = image.getRGB(x, y);
                            int r = (rgb >> 16) & 0xFF;
                            int g = (rgb >> 8) & 0xFF;
                            int b = rgb & 0xFF;
                            int lum = computeLuminosity(r, g, b);
                            local[lum]++;
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
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        int totalPixels = width * height;
        int denom = totalPixels - cumRes.cdfMin;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int lum = computeLuminosity(r, g, b);

                int newLum;
                if (denom <= 0) {
                    newLum = lum;
                } else {
                    double cdf = (double) (cumRes.cumulative[lum] - cumRes.cdfMin) / denom;
                    newLum = (int) Math.round(255.0 * cdf);
                }

                if (newLum < 0) newLum = 0;
                if (newLum > 255) newLum = 255;

                int grayRgb = (0xFF << 24) | (newLum << 16) | (newLum << 8) | newLum;
                out.setRGB(x, y, grayRgb);
            }
        }

        writeImage(out, outputFile);
    }

    public void forkJoinHistogramFilter(String outputFile, int value) throws IOException {
        int parallelism = value > 0 ? value : Runtime.getRuntime().availableProcessors();
        int width = image.getWidth();
        int height = image.getHeight();

        ForkJoinPool pool = new ForkJoinPool(parallelism);
        try {
            HistogramTask histogramTask = new HistogramTask(this, image, 0, height, width);
            int[] hist = pool.invoke(histogramTask);

            CumulativeResult cumRes = computeCumulative(hist);

            BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            EqualizationTask eqTask = new EqualizationTask(
                    this, image, out, 0, height, width, cumRes.cumulative, cumRes.cdfMin
            );
            pool.invoke(eqTask);

            writeImage(out, outputFile);
        } finally {
            pool.shutdown();
        }
    }

    public void completableFutureHistogramFilter(String outputFile, int value) throws IOException {
        int numTasks = value > 0 ? value : Runtime.getRuntime().availableProcessors();
        int width = image.getWidth();
        int height = image.getHeight();

        ExecutorService executor = Executors.newWorkStealingPool(numTasks);

        try {
            @SuppressWarnings("unchecked")
            CompletableFuture<int[]>[] histFutures = new CompletableFuture[numTasks];

            for (int t = 0; t < numTasks; t++) {
                final int startRow = t * height / numTasks;
                final int endRow = (t + 1) * height / numTasks;

                histFutures[t] = CompletableFuture.supplyAsync(() -> {
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
                }, executor);
            }

            CompletableFuture<int[]> mergedHistFuture =
                    CompletableFuture.allOf(histFutures)
                            .thenApply(v -> {
                                int[] merged = new int[256];
                                for (CompletableFuture<int[]> future : histFutures) {
                                    int[] local = future.join();
                                    for (int i = 0; i < 256; i++) {
                                        merged[i] += local[i];
                                    }
                                }
                                return merged;
                            });

            CompletableFuture<CumulativeResult> cumulativeFuture =
                    mergedHistFuture.thenApply(this::computeCumulative);

            CompletableFuture<BufferedImage> imageFuture =
                    cumulativeFuture.thenCompose(cumRes -> {
                        int totalPixels = width * height;
                        int denom = totalPixels - cumRes.cdfMin;

                        @SuppressWarnings("unchecked")
                        CompletableFuture<Void>[] eqFutures = new CompletableFuture[numTasks];
                        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);

                        for (int t = 0; t < numTasks; t++) {
                            final int startRow = t * height / numTasks;
                            final int endRow = (t + 1) * height / numTasks;

                            eqFutures[t] = CompletableFuture.runAsync(() -> {
                                for (int y = startRow; y < endRow; y++) {
                                    for (int x = 0; x < width; x++) {
                                        int rgb = image.getRGB(x, y);
                                        int r = (rgb >> 16) & 0xFF;
                                        int g = (rgb >> 8) & 0xFF;
                                        int b = rgb & 0xFF;
                                        int lum = computeLuminosity(r, g, b);

                                        int newLum;
                                        if (denom <= 0) {
                                            newLum = lum;
                                        } else {
                                            double cdf = (double) (cumRes.cumulative[lum] - cumRes.cdfMin) / denom;
                                            newLum = (int) Math.round(255.0 * cdf);
                                        }

                                        if (newLum < 0) newLum = 0;
                                        if (newLum > 255) newLum = 255;

                                        int grayRgb = (0xFF << 24) | (newLum << 16) | (newLum << 8) | newLum;
                                        out.setRGB(x, y, grayRgb);
                                    }
                                }
                            }, executor);
                        }

                        return CompletableFuture.allOf(eqFutures).thenApply(v -> out);
                    });

            imageFuture
                    .thenAccept(out -> {
                        try {
                            writeImage(out, outputFile);
                        } catch (IOException e) {
                            throw new CompletionException(e);
                        }
                    })
                    .join();

        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("Error applying CompletableFuture histogram filter", cause);
        } finally {
            executor.shutdown();
        }
    }
}