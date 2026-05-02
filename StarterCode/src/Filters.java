import java.awt.Color;
import java.io.IOException;
import java.util.concurrent.*;

/**
 * @author Jorge Coelho
 * @contact jmn@isep.ipp.pt
 * @version 1.0
 */
public class Filters {
    String file;
    Color image[][];

    //Constructor with filename for source image
    Filters(String filename) {
        this.file = filename;
        image = Utils.loadImage(filename);
    }
    
    public void HistogramFilter(String outputFile, int value) throws IOException {
        Color[][] tmp = Utils.copyImage(image);
        int[] hist = new int[256];
        int total_pixels = tmp.length*tmp[0].length;
        System.out.println("Total pixels: "+total_pixels);
        // Runs through entire matrix and computes luminosity
        for (int i = 0; i < tmp.length; i++) {
            for (int j = 0; j < tmp[i].length; j++) {

                Color pixel = tmp[i][j];
                int r = pixel.getRed();
                int g = pixel.getGreen();
                int b = pixel.getBlue();
                int lum = computeLuminosity(r, g, b);
                hist[lum]++;
            }
        }

        CumulativeResult cumRes = computeCumulative(hist);
        int[] cumulative = cumRes.cumulative;
        int cdfMin = cumRes.cdfMin;

        //Change each pixel of the output image
        for (int i = 0; i < tmp.length; i++) {
            for (int j = 0; j < tmp[i].length; j++) {

            Color pixel = tmp[i][j];
            int r = pixel.getRed();
            int g = pixel.getGreen();
            int b = pixel.getBlue();
            int lum = computeLuminosity(r, g, b);
            //int newLum = 255*(cumulative[lum]/total_pixels);

            double cdf = (double) cumulative[lum] / (double) (total_pixels - cdfMin);
            int newLum = (int) Math.round(255.0 * cdf);

            //if (newLum < 0) newLum = 0;
            //if (newLum > 255) newLum = 255;
            tmp[i][j] = new Color(newLum, newLum, newLum);
            }
        }
        //Utils.writeImage(tmp, outputFile);
        applyEqualizationAndWrite(tmp, cumulative, cdfMin, total_pixels, outputFile);
    }

    public int computeLuminosity(int r, int g, int b) {
        return (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
    }

    //Helper to compute cumulative histogram and cdfMin
    private static class CumulativeResult {
        int[] cumulative;
        int cdfMin;
        CumulativeResult(int[] cumulative, int cdfMin) {
            this.cumulative = cumulative;
            this.cdfMin = cdfMin;
        }
    }

    //Helper to apply equalization and write output
    private void applyEqualizationAndWrite(Color[][] tmp, int[] cumulative, int cdfMin, int total_pixels, String outputFile) {
        int height = tmp.length;
        int width = tmp[0].length;
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                Color pixel = tmp[i][j];
                int r = pixel.getRed();
                int g = pixel.getGreen();
                int b = pixel.getBlue();
                int lum = computeLuminosity(r, g, b);
                double cdf = (double) cumulative[lum] / (double) (total_pixels - cdfMin);
                int newLum = (int) Math.round(255.0 * cdf);
                tmp[i][j] = new Color(newLum, newLum, newLum);
            }
        }
        Utils.writeImage(tmp, outputFile);
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

    //Sequential baseline implementation for histogram equalization
    //Processes the image pixel-by-pixel in a (sequentially)
    public void sequentialHistogramFilter(String outputFile, int value) throws IOException {
        HistogramFilter(outputFile, value);
    }

    //Multithreaded implementation for histogram equalization (manual thread management, no thread pools).
    //Distributes work among threads and synchronizes histogram merging.
    public void multithreadedHistogramFilter(String outputFile, int value) throws IOException {
        int numThreads = value > 0 ? value : 2; // Default to 2 threads if value is invalid
        Color[][] tmp = Utils.copyImage(image);
        int height = tmp.length;
        int width = tmp[0].length;
        int[][] localHists = new int[numThreads][256];
        Thread[] threads = new Thread[numThreads];

        //Each thread computes a local histogram for its assigned rows
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                int startRow = threadId * height / numThreads;
                int endRow = (threadId + 1) * height / numThreads;
                for (int i = startRow; i < endRow; i++) {
                    for (int j = 0; j < width; j++) {
                        Color pixel = tmp[i][j];
                        int r = pixel.getRed();
                        int g = pixel.getGreen();
                        int b = pixel.getBlue();
                        int lum = computeLuminosity(r, g, b);
                        localHists[threadId][lum]++;
                    }
                }
            });
            threads[t].start();
        }
        //Wait for all threads to finish
        for (int t = 0; t < numThreads; t++) {
            try {
                threads[t].join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        //Merge local histograms into a global histogram
        int[] hist = new int[256];
        for (int t = 0; t < numThreads; t++) {
            for (int i = 0; i < 256; i++) {
                hist[i] += localHists[t][i];
            }
        }
        int total_pixels = height * width;

        CumulativeResult cumRes = computeCumulative(hist);
        int[] cumulative = cumRes.cumulative;
        int cdfMin = cumRes.cdfMin;

        applyEqualizationAndWrite(tmp, cumulative, cdfMin, total_pixels, outputFile);
    }


    //Multithreaded implementation for histogram equalization using thread pools.
    public void threadPoolHistogramFilter(String outputFile, int value) throws IOException {
        int numThreads = value > 0 ? value : 2; // Default to 2 threads if value is invalid
        Color[][] tmp = Utils.copyImage(image);
        int height = tmp.length;
        int width = tmp[0].length;
        int[][] localHists = new int[numThreads][256];
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);

        //Each task computes a local histogram for its assigned rows
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                int startRow = threadId * height / numThreads;
                int endRow = (threadId + 1) * height / numThreads;
                for (int i = startRow; i < endRow; i++) {
                    for (int j = 0; j < width; j++) {
                        Color pixel = tmp[i][j];
                        int r = pixel.getRed();
                        int g = pixel.getGreen();
                        int b = pixel.getBlue();
                        int lum = computeLuminosity(r, g, b);
                        localHists[threadId][lum]++;
                    }
                }
                latch.countDown();
            });
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor.shutdown();
        //Merge local histograms into a global histogram
        int[] hist = new int[256];
        for (int t = 0; t < numThreads; t++) {
            for (int i = 0; i < 256; i++) {
                hist[i] += localHists[t][i];
            }
        }
        int total_pixels = height * width;

        CumulativeResult cumRes = computeCumulative(hist);
        int[] cumulative = cumRes.cumulative;
        int cdfMin = cumRes.cdfMin;

        applyEqualizationAndWrite(tmp, cumulative, cdfMin, total_pixels, outputFile);
    }

    // Splits the image into tasks executed asynchronously and composes results without explicit thread management.
    public void completableFutureHistogramFilter(String outputFile, int value) throws IOException {
        int numTasks = value > 0 ? value : Runtime.getRuntime().availableProcessors();
        Color[][] tmp = Utils.copyImage(image);
        int height = tmp.length;
        int width = tmp[0].length;

        @SuppressWarnings("unchecked")
        CompletableFuture<int[]>[] futures = new CompletableFuture[numTasks];

        for (int t = 0; t < numTasks; t++) {
            final int startRow = t * height / numTasks;
            final int endRow = (t + 1) * height / numTasks;
            futures[t] = CompletableFuture.supplyAsync(() -> {
                int[] hist = new int[256];
                for (int i = startRow; i < endRow; i++) {
                    for (int j = 0; j < width; j++) {
                        Color pixel = tmp[i][j];
                        int r = pixel.getRed();
                        int g = pixel.getGreen();
                        int b = pixel.getBlue();
                        int lum = computeLuminosity(r, g, b);
                        hist[lum]++;
                    }
                }
                return hist;
            });
        }

        // Wait for all tasks to complete
        CompletableFuture<Void> all = CompletableFuture.allOf(futures);
        all.join();

        // Merge local histograms into a global histogram
        int[] hist = new int[256];
        for (int t = 0; t < numTasks; t++) {
            int[] local = futures[t].join();
            for (int i = 0; i < 256; i++) {
                hist[i] += local[i];
            }
        }

        int total_pixels = height * width;

        CumulativeResult cumRes = computeCumulative(hist);
        int[] cumulative = cumRes.cumulative;
        int cdfMin = cumRes.cdfMin;

        applyEqualizationAndWrite(tmp, cumulative, cdfMin, total_pixels, outputFile);
    }
}