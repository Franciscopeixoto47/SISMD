import java.io.IOException;
import java.util.Scanner;

public class ApplyFilters {
 
    public static void main(String[] args) throws IOException {
        Scanner scanner = new Scanner(System.in);
        String filePath = "C:\\Users\\franc\\Faculdade\\Mestrado\\2_Semestre\\SISMD\\projetoSISMD\\StarterCode\\src.jpg";
        Filters filters = new Filters(filePath);
        System.out.println("Select implementation:");
        System.out.println("1 - Sequential");
        System.out.println("2 - Multithreaded (manual threads)");
        System.out.println("3 - Multithreaded (thread pools)");
        System.out.println("4 - Fork/Join framework");
        System.out.println("5 - CompletableFuture-based");

        int choice = scanner.nextInt();
        int threads = 1;
        if (choice > 1 && choice <= 5) {
            System.out.print("Enter number of threads: ");
            threads = scanner.nextInt();
        }
        long startTime = System.nanoTime();
        boolean validChoice = true;
        switch (choice) {
            case 1:
                filters.sequentialHistogramFilter("output1.jpg");
                break;
            case 2:
                filters.multithreadedHistogramFilter("output2.jpg", threads);
                break;
            case 3:
                filters.threadPoolHistogramFilter("output3.jpg", threads);
                break;
            case 4:
                filters.forkJoinHistogramFilter("output4.jpg", threads);
                break;
            case 5:
                filters.completableFutureHistogramFilter("output5.jpg", threads);
                break;
            default:
                validChoice = false;
                System.out.println("Invalid choice.");
        }
        if (validChoice) {
            long elapsedNanos = System.nanoTime() - startTime;
            double elapsedMillis = elapsedNanos / 1_000_000.0;
            System.out.printf("Implementation %d took %.3f ms.%n", choice, elapsedMillis);
        }
        scanner.close();
    }

}
