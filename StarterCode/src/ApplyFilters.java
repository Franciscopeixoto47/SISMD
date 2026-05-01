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

        int choice = scanner.nextInt();
        int threads = 1;
        if (choice > 1 && choice <= 2) {
            System.out.print("Enter number of threads/parallelism: ");
            threads = scanner.nextInt();
        }
        switch (choice) {
            case 1:
                filters.sequentialHistogramFilter("output.jpg", 128);
                break;
            case 2:
                filters.multithreadedHistogramFilter("output.jpg", threads);
                break;
            default:
                System.out.println("Invalid choice.");
        }
        scanner.close();
    }

}
