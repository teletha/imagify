package imagify;

import java.awt.image.BufferedImage;

import imagify.resize.BufferedImageResize;
import imagify.resize.ResizeAlgorithm;

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

        // Upscaling tests
        System.out.println("=== Upscaling (16x16 -> 32x32) ===");
        testResize(src, ResizeAlgorithm.BILINEAR, 32, 32);
        testResize(src, ResizeAlgorithm.CATROM, 32, 32);
        testResize(src, ResizeAlgorithm.MITCHELL, 32, 32);
        testResize(src, ResizeAlgorithm.LANCZOS3, 32, 32);
        testResize(src, ResizeAlgorithm.HQX, 32, 32);

        // Downscaling tests
        System.out.println("\n=== Downscaling (16x16 -> 8x8) ===");
        testResize(src, ResizeAlgorithm.BILINEAR, 8, 8);
        testResize(src, ResizeAlgorithm.CATROM, 8, 8);
        testResize(src, ResizeAlgorithm.MITCHELL, 8, 8);
        testResize(src, ResizeAlgorithm.LANCZOS3, 8, 8);

        // Non-square resize
        System.out.println("\n=== Non-square (16x16 -> 32x24) ===");
        testResize(src, ResizeAlgorithm.BILINEAR, 32, 24);
        testResize(src, ResizeAlgorithm.LANCZOS3, 32, 24);

        // No-op
        System.out.println("\n=== No-op (16x16 -> 16x16) ===");
        testResize(src, ResizeAlgorithm.LANCZOS3, 16, 16);

        // xBRZ scale tests
        System.out.println("\n=== xBRZ upscaling tests ===");
        try { testResize(src, ResizeAlgorithm.HQX, 32, 32); } catch (Exception e) { System.out.println("HQX 2x failed: " + e.getMessage()); }
        try {
            BufferedImage src32 = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 8; x++)
                    src32.setRGB(x, y, src.getRGB(x, y));
            testResize(src32, ResizeAlgorithm.HQX, 24, 24);
        } catch (Exception e) { System.out.println("HQX 3x failed: " + e.getMessage()); }
        try {
            BufferedImage src64 = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 4; y++)
                for (int x = 0; x < 4; x++)
                    src64.setRGB(x, y, src.getRGB(x, y));
            testResize(src64, ResizeAlgorithm.HQX, 16, 16);
        } catch (Exception e) { System.out.println("HQX 4x failed: " + e.getMessage()); }

        System.out.println("\nAll tests completed!");
    }

    static void testResize(BufferedImage src, ResizeAlgorithm algo, int tw, int th) {
        BufferedImage result = BufferedImageResize.resize(src, tw, th, algo);
        System.out.printf("%-12s: %dx%d -> %dx%d OK%n", algo.name(), src.getWidth(), src.getHeight(), result.getWidth(), result.getHeight());
    }
}
