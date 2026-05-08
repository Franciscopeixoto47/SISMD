import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import com.sun.management.OperatingSystemMXBean;

public class ApplyFilters {

    private static final String METRICS_CSV = "metrics.csv";
    private static final long SAMPLE_INTERVAL_MS = 100;

    @FunctionalInterface
    private interface FilterAction {
        void run() throws IOException;
    }

    private static class MetricsResult {
        private final double wallMs;
        private final double cpuMs;
        private final double cpuPercent;
        private final double peakHeapMb;

        private MetricsResult(double wallMs, double cpuMs, double cpuPercent, double peakHeapMb) {
            this.wallMs = wallMs;
            this.cpuMs = cpuMs;
            this.cpuPercent = cpuPercent;
            this.peakHeapMb = peakHeapMb;
        }
    }

    private static class MetricsCollector {
        private final OperatingSystemMXBean osBean;
        private final MemoryMXBean memoryBean;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicLong peakHeapBytes = new AtomicLong(0L);
        private Thread samplerThread;
        private long startWallNanos;
        private long startCpuNanos;

        private MetricsCollector() {
            this.osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            this.memoryBean = ManagementFactory.getMemoryMXBean();
        }

        private void start() {
            startWallNanos = System.nanoTime();
            startCpuNanos = osBean.getProcessCpuTime();
            peakHeapBytes.set(memoryBean.getHeapMemoryUsage().getUsed());
            running.set(true);
            samplerThread = new Thread(() -> {
                while (running.get()) {
                    try {
                        long used = memoryBean.getHeapMemoryUsage().getUsed();
                        peakHeapBytes.updateAndGet(prev -> Math.max(prev, used));
                        Thread.sleep(SAMPLE_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (OutOfMemoryError e) {
                        break;
                    }
                }
            }, "metrics-sampler");
            samplerThread.setDaemon(true);
            samplerThread.start();
        }

        private MetricsResult stop() {
            running.set(false);
            if (samplerThread != null) {
                try {
                    samplerThread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            long endWallNanos = System.nanoTime();
            long endCpuNanos = osBean.getProcessCpuTime();
            long wallNanos = endWallNanos - startWallNanos;
            long cpuNanos = endCpuNanos - startCpuNanos;
            double wallMs = wallNanos / 1_000_000.0;
            double cpuMs = cpuNanos / 1_000_000.0;
            int processors = osBean.getAvailableProcessors();
            double cpuPercent = wallNanos > 0
                    ? (cpuNanos / (double) wallNanos) * (100.0 / processors)
                    : 0.0;
            double peakHeapMb = peakHeapBytes.get() / (1024.0 * 1024.0);
            return new MetricsResult(wallMs, cpuMs, cpuPercent, peakHeapMb);
        }
    }

    private static void appendMetricsCsv(MetricsResult result, int choice, int threads, String imageFileName) throws IOException {
        Path path = Paths.get(METRICS_CSV);
        if (!Files.exists(path)) {
            String header = "date,time,implementation,threads,image_file,execution_ms,cpu_ms,avg_cpu_percent" + System.lineSeparator();
            Files.writeString(path, header, StandardOpenOption.CREATE);
        }
        Instant now = Instant.now();
        String[] parts = now.toString().replace("Z", "").split("T");
        String date = parts[0];
        String time = parts.length > 1 ? parts[1] : "";
        String row = String.format("%s,%s,%d,%d,%s,%.3f,%.3f,%.2f%n",
                date, time, choice, threads, imageFileName, result.wallMs, result.cpuMs, result.cpuPercent);
        Files.writeString(path, row, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public static void main(String[] args) throws IOException {
        Scanner scanner = new Scanner(System.in);
        String filePath;
        System.out.print("Enter image file path: ");
        filePath = scanner.nextLine();

        Filters filters = new Filters(filePath);
        System.out.println("Select implementation:");
        System.out.println("1 - Sequential");
        System.out.println("2 - Multithreaded (manual threads)");
        System.out.println("3 - Multithreaded (thread pools)");
        System.out.println("4 - Fork/Join framework");
        System.out.println("5 - CompletableFuture-based");

        System.out.print("Enter choice (1-5): ");
        int choice = scanner.nextInt();
        int threads = 1;
        if (choice > 1 && choice <= 5) {
            System.out.print("Enter number of threads: ");
            threads = scanner.nextInt();
        }
        int threadsFinal = threads;
        boolean validChoice = true;
        FilterAction action;
        switch (choice) {
            case 1:
                action = () -> filters.sequentialHistogramFilter("output1.jpg");
                break;
            case 2:
                action = () -> filters.multithreadedHistogramFilter("output2.jpg", threadsFinal);
                break;
            case 3:
                action = () -> filters.threadPoolHistogramFilter("output3.jpg", threadsFinal);
                break;
            case 4:
                action = () -> filters.forkJoinHistogramFilter("output4.jpg", threadsFinal);
                break;
            case 5:
                action = () -> filters.completableFutureHistogramFilter("output5.jpg", threadsFinal);
                break;
            default:
                validChoice = false;
                action = null;
                System.out.println("Invalid choice.");
        }
        if (validChoice) {
            MetricsCollector collector = new MetricsCollector();
            collector.start();
            action.run();
            MetricsResult result = collector.stop();
            System.out.println();
            System.out.printf("Execution time: %.3f ms.%n", result.wallMs);
            System.out.printf("CPU time: %.3f ms (avg %.2f%%).%n", result.cpuMs, result.cpuPercent);
            System.out.printf("Peak heap usage: %.2f MB.%n", result.peakHeapMb);
            String imageFileName = java.nio.file.Paths.get(filePath).getFileName().toString();
            appendMetricsCsv(result, choice, threads, imageFileName);
            System.out.printf("Metrics appended to %s.%n", METRICS_CSV);
        }
        scanner.close();
    }
}
