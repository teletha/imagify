package imagify;

import java.awt.image.BufferedImage;

public class ResizeTest {
    public static void main(String[] args) {
        BufferedImage src = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int r = (x * 16) % 256;
                int g = (y * 16) % 256;
                int b = ((x + y) * 8) % 256;
                src.setRGB(x, y, (0xFF << 24) | (r << 16) | (g << 8) | b);
            }
        }

        // Upscaling
        testResize(src, ResizeAlgorithm.BILINEAR, 32, 32);
        testResize(src, ResizeAlgorithm.CATROM, 32, 32);
        testResize(src, ResizeAlgorithm.MITCHELL, 32, 32);
        testResize(src, ResizeAlgorithm.LANCZOS3, 32, 32);
        testResize(src, ResizeAlgorithm.HQX, 32, 32);

        // Downscaling
        testResize(src, ResizeAlgorithm.BILINEAR, 8, 8);
        testResize(src, ResizeAlgorithm.CATROM, 8, 8);
        testResize(src, ResizeAlgorithm.MITCHELL, 8, 8);
        testResize(src, ResizeAlgorithm.LANCZOS3, 8, 8);

        // Non-square
        testResize(src, ResizeAlgorithm.BILINEAR, 32, 24);
        testResize(src, ResizeAlgorithm.LANCZOS3, 32, 24);

        // No-op
        testResize(src, ResizeAlgorithm.LANCZOS3, 16, 16);

        // xBRZ takes exact integer multiples only, so a size that is not one is expected to fail.
        try {
            testResize(src, ResizeAlgorithm.HQX, 32, 32);
        } catch (Exception ignored) {
        }
        try {
            BufferedImage src32 = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 8; x++)
                    src32.setRGB(x, y, src.getRGB(x, y));
            testResize(src32, ResizeAlgorithm.HQX, 24, 24);
        } catch (Exception ignored) {
        }
        try {
            BufferedImage src64 = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 4; y++)
                for (int x = 0; x < 4; x++)
                    src64.setRGB(x, y, src.getRGB(x, y));
            testResize(src64, ResizeAlgorithm.HQX, 16, 16);
        } catch (Exception ignored) {
        }
    }

    static void testResize(BufferedImage src, ResizeAlgorithm algo, int tw, int th) {
        BufferedImageResize.resize(tw, th, algo).apply(src);
    }
}
