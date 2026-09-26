package imagify;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Arrays;

public class SpriteSheetTest {
    public static void main(String[] args) throws Exception {
        // Create test frames
        BufferedImage f1 = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        BufferedImage f2 = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        BufferedImage f3 = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        BufferedImage f4 = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        f1.setRGB(0, 0, 32, 32, new int[32 * 32], 0, 32); // transparent
        f2.setRGB(0, 0, 32, 32, new int[32 * 32], 0, 32);
        f3.setRGB(0, 0, 32, 32, new int[32 * 32], 0, 32);
        f4.setRGB(0, 0, 32, 32, new int[32 * 32], 0, 32);

        // Test 1: Grid layout
        BufferedImage sheet = SpriteSheet.of()
            .addFrame(f1).addFrame(f2).addFrame(f3).addFrame(f4)
            .columns(2)
            .spacing(2)
            .padding(4)
            .toImage();
        System.out.println("Grid 2x2: " + sheet.getWidth() + "x" + sheet.getHeight());
        assert sheet.getWidth() == 4 + 2*32 + 2 + 32 : "Width mismatch";
        assert sheet.getHeight() == 4 + 2*32 + 2 + 32 : "Height mismatch";
        System.out.println("PASS");

        // Test 2: Uniform size resize
        BufferedImage big = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        BufferedImage sheet2 = SpriteSheet.of()
            .addFrame(big).addFrame(big)
            .uniformSize(32, 32)
            .columns(2)
            .toImage();
        System.out.println("Uniform resize: " + sheet2.getWidth() + "x" + sheet2.getHeight());
        assert sheet2.getWidth() == 64 : "Uniform width mismatch";
        System.out.println("PASS");

        // Test 3: Single image grid
        BufferedImage sheet3 = SpriteSheet.of()
            .addFrame(big)
            .grid(3, 4)
            .spacing(1)
            .toImage();
        System.out.println("Single image 3x4 grid: " + sheet3.getWidth() + "x" + sheet3.getHeight());
        assert sheet3.getWidth() == 3 * 64 + 2 * 1 : "Grid width mismatch";
        assert sheet3.getHeight() == 4 * 64 + 3 * 1 : "Grid height mismatch";
        System.out.println("PASS");

        System.out.println("\nAll sprite sheet tests passed!");
    }
}
