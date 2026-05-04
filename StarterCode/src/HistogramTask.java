import java.awt.Color;
import java.util.concurrent.RecursiveTask;

// Task for Fork/Join histogram computation
public class HistogramTask extends RecursiveTask<int[]> {
    private static final long serialVersionUID = 1L;
    private final Filters filters;
    private final Color[][] img;
    private final int startRow;
    private final int endRow; // exclusive
    private final int width;
    // Threshold chosen to avoid too fine-grained tasks
    private static final int ROW_THRESHOLD = 16;

    HistogramTask(Filters filters, Color[][] img, int startRow, int endRow, int width) {
        this.filters = filters;
        this.img = img;
        this.startRow = startRow;
        this.endRow = endRow;
        this.width = width;
    }

    @Override
    protected int[] compute() {
        int rows = endRow - startRow;
        if (rows <= ROW_THRESHOLD) {
            int[] local = new int[256];
            for (int i = startRow; i < endRow; i++) {
                for (int j = 0; j < width; j++) {
                    Color pixel = img[i][j];
                    int r = pixel.getRed();
                    int g = pixel.getGreen();
                    int b = pixel.getBlue();
                    int lum = filters.computeLuminosity(r, g, b);
                    local[lum]++;
                }
            }
            return local;
        } else {
            int mid = (startRow + endRow) >>> 1;
            HistogramTask left = new HistogramTask(filters, img, startRow, mid, width);
            HistogramTask right = new HistogramTask(filters, img, mid, endRow, width);
            left.fork();
            int[] rightRes = right.compute();
            int[] leftRes = left.join();
            // Merge
            for (int i = 0; i < 256; i++) leftRes[i] += rightRes[i];
            return leftRes;
        }
    }
}

