import java.awt.image.BufferedImage;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.RecursiveTask;

public class HistogramTask extends RecursiveTask<int[]> {
    private static final long serialVersionUID = 1L;
    private static final int ROW_THRESHOLD = 128;

    private final Filters filters;
    private final BufferedImage img;
    private final int startRow;
    private final int endRow;
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
        }

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

class EqualizationTask extends RecursiveAction {
    private static final long serialVersionUID = 1L;
    private static final int ROW_THRESHOLD = 128;

    private final Filters filters;
    private final BufferedImage src;
    private final BufferedImage out;
    private final int startRow;
    private final int endRow;
    private final int width;
    private final int[] cumulative;
    private final int cdfMin;

    EqualizationTask(Filters filters, BufferedImage src, BufferedImage out,
                     int startRow, int endRow, int width,
                     int[] cumulative, int cdfMin) {
        this.filters = filters;
        this.src = src;
        this.out = out;
        this.startRow = startRow;
        this.endRow = endRow;
        this.width = width;
        this.cumulative = cumulative;
        this.cdfMin = cdfMin;
    }

    @Override
    protected void compute() {
        int rows = endRow - startRow;

        if (rows <= ROW_THRESHOLD) {
            int totalPixels = src.getWidth() * src.getHeight();
            int denom = totalPixels - cdfMin;

            for (int y = startRow; y < endRow; y++) {
                for (int x = 0; x < width; x++) {
                    int rgb = src.getRGB(x, y);

                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;

                    int lum = filters.computeLuminosity(r, g, b);

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
            return;
        }

        int mid = (startRow + endRow) >>> 1;
        invokeAll(
                new EqualizationTask(filters, src, out, startRow, mid, width, cumulative, cdfMin),
                new EqualizationTask(filters, src, out, mid, endRow, width, cumulative, cdfMin)
        );
    }
}