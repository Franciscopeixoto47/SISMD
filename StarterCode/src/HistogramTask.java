import java.awt.image.BufferedImage;
import java.util.concurrent.RecursiveTask;

public class HistogramTask extends RecursiveTask<int[]> {
    private static final long serialVersionUID = 1L;
    private static final int ROW_THRESHOLD = 256;

    private final Filters filters;
    private final BufferedImage img;
    private final int startRow;
    private final int endRow; // exclusive
    private final int width;

    HistogramTask(Filters filters, BufferedImage img, int startRow, int endRow, int width) {
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

            for (int y = startRow; y < endRow; y++) {
                for (int x = 0; x < width; x++) {
                    int rgb = img.getRGB(x, y);

                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;

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

            for (int i = 0; i < 256; i++) {
                leftRes[i] += rightRes[i];
            }

            return leftRes;
        }
    }
}