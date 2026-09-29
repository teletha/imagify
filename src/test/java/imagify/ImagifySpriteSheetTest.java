package imagify;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImagifySpriteSheetTest {

    // ── helpers ──────────────────────────────────────────────────────

    private static BufferedImage makeImage(int w, int h, int rgb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                img.setRGB(x, y, rgb);
        return img;
    }

    private static BufferedImage red()   { return makeImage(32, 32, 0xFFFF0000); }
    private static BufferedImage green() { return makeImage(32, 32, 0xFF00FF00); }
    private static BufferedImage blue()  { return makeImage(32, 32, 0xFF0000FF); }

    private static byte[] toBytes(BufferedImage image, ImageFormat format) throws IOException {
        return ImageWriter.toBytes(image, format, ImageFormat.PNG.getDefaultQuality());
    }

    /** Writes frames as PNG files and returns their paths. */
    private static List<Path> writeFrames(Path dir, BufferedImage... frames) throws IOException {
        List<Path> paths = new java.util.ArrayList<>();
        for (int i = 0; i < frames.length; i++) {
            Path file = dir.resolve("frame" + i + ".png");
            Files.write(file, toBytes(frames[i], ImageFormat.PNG));
            paths.add(file);
        }
        return paths;
    }

    // ══════════════════════════════════════════════════════════════════
    //  readPaths(List<Path>).asSpriteSheet(int)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("spriteSheet from paths creates an image")
    void spriteSheetFromPaths(@TempDir Path dir) throws Exception {
        BufferedImage[] frames = { red(), green(), blue() };
        List<Path> paths = writeFrames(dir, frames);

        Imagify pipe = Imagify.readPaths(paths).asSpriteSheet(2);
        BufferedImage sheet = pipe.toBufferedImage();

        assertNotNull(sheet);
        // 3 frames, 2 cols → 2 rows: width=64, height=64
        assertEquals(64, sheet.getWidth());
        assertEquals(64, sheet.getHeight());
    }

    @Test
    @DisplayName("spriteSheet from paths places frames in correct grid positions")
    void spriteSheetFromPathsPlacesCorrectly(@TempDir Path dir) throws Exception {
        BufferedImage[] frames = { red(), green(), blue() };
        List<Path> paths = writeFrames(dir, frames);

        Imagify pipe = Imagify.readPaths(paths).asSpriteSheet(2);
        BufferedImage sheet = pipe.toBufferedImage();

        // 3 frames in 2 cols: Red(0,0), Green(32,0), Blue(0,32). Sheet is 64x64
        assertEquals(0xFFFF0000, sheet.getRGB(5, 5));
        assertEquals(0xFF00FF00, sheet.getRGB(37, 5));
        assertEquals(0xFF0000FF, sheet.getRGB(5, 37));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  readPaths(List<Path>).asSpriteSheet(Consumer<SpriteSheet>)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("spriteSheet with layout consumer creates an image")
    void spriteSheetWithLayout(@TempDir Path dir) throws Exception {
        BufferedImage[] frames = { red(), green(), blue() };
        List<Path> paths = writeFrames(dir, frames);

        Imagify pipe = Imagify.readPaths(paths).asSpriteSheet(sheet -> sheet.columns(2).padding(5).spacing(2));
        BufferedImage sheet = pipe.toBufferedImage();

        assertNotNull(sheet);
        // 3 frames, 2 cols, padding=5, spacing=2
        // width = 2*5 + 2*32 + 1*2 = 76
        // height = 2*5 + 2*32 + 1*2 = 76
        assertEquals(76, sheet.getWidth());
        assertEquals(76, sheet.getHeight());
    }

    @Test
    @DisplayName("spriteSheet with layout consumer respects custom cell size")
    void spriteSheetWithCustomCellSize(@TempDir Path dir) throws Exception {
        BufferedImage[] frames = { red(), green(), blue() };
        List<Path> paths = writeFrames(dir, frames);

        Imagify pipe = Imagify.readPaths(paths).asSpriteSheet(sheet -> sheet.columns(2).cell(64, 64).padding(8));
        BufferedImage sheet = pipe.toBufferedImage();

        // 3 frames, 2 cols, 64x64 cells, padding=8
        // width = 2*8 + 2*64 = 144
        // height = 2*8 + 2*64 = 144
        assertEquals(144, sheet.getWidth());
        assertEquals(144, sheet.getHeight());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  asSpriteSheet(int)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("asSpriteSheet turns a sequence into a single image")
    void asSpriteSheetTurnsSequenceIntoImage(@TempDir Path dir) throws Exception {
        BufferedImage src = makeImage(32, 32, 0xFFFF0000);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        Imagify pipe = Imagify.read(file).asSpriteSheet(1);
        BufferedImage sheet = pipe.toBufferedImage();

        assertEquals(32, sheet.getWidth());
        assertEquals(32, sheet.getHeight());
    }

    @Test
    @DisplayName("asSpriteSheet drops animation timing")
    void asSpriteSheetDropsAnimationTiming(@TempDir Path dir) throws Exception {
        BufferedImage r = red(), g = green(), b = blue();
        List<Path> paths = writeFrames(dir, r, g, b);

        // asSpriteSheet converts it to a single image
        Imagify pipe = Imagify.readPaths(paths).asSpriteSheet(2).asSpriteSheet(1);
        FrameSequence seq = pipe.get();
        assertEquals(1, seq.frameCount(), "asSpriteSheet should collapse to a single frame");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  asSpriteSheet(Consumer<SpriteSheet>)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("asSpriteSheet with layout consumer applies custom configuration")
    void asSpriteSheetWithLayoutConsumer(@TempDir Path dir) throws Exception {
        BufferedImage src = red();
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        Imagify pipe = Imagify.read(file)
                .asSpriteSheet(sheet -> sheet.cell(64, 64).padding(10).background(Color.WHITE));
        BufferedImage sheet = pipe.toBufferedImage();

        // 1 frame with 64x64 cell, padding=10
        // width = 2*10 + 1*64 = 84
        // height = 2*10 + 1*64 = 84
        assertEquals(84, sheet.getWidth());
        assertEquals(84, sheet.getHeight());
        // Background should be white (corner is padding)
        assertEquals(0xFFFFFFFF, sheet.getRGB(0, 0), "corner should be white with padding and background");
    }

    

    // ══════════════════════════════════════════════════════════════════════
    //  Edge cases and validation
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("spriteSheet from paths produces non-empty image")
    void spriteSheetProducesNonEmptyImage(@TempDir Path dir) throws Exception {
        BufferedImage src = makeImage(10, 10, 0xFFAAAAAA);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("test.png");
        Files.write(file, png);

        Imagify pipe = Imagify.readPaths(List.of(file)).asSpriteSheet(1);
        BufferedImage sheet = pipe.toBufferedImage();

        assertTrue(sheet.getWidth() > 0);
        assertTrue(sheet.getHeight() > 0);
    }

    @Test
    @DisplayName("asSpriteSheet returns an Imagify that can be written")
    void asSpriteSheetCanBeWritten(@TempDir Path dir) throws Exception {
        BufferedImage src = red();
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        Path out = dir.resolve("output.png");
        Imagify.read(file).asSpriteSheet(1).writeTo(out);

        assertTrue(Files.exists(out));
        assertTrue(Files.size(out) > 0);
    }

    @Test
    @DisplayName("asSpriteSheet with layout returns a working pipeline for chaining")
    void asSpriteSheetChains(@TempDir Path dir) throws Exception {
        BufferedImage src = red();
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        Imagify pipe = Imagify.read(file);
        Imagify result = pipe.asSpriteSheet(sheet -> sheet.columns(1));
        // Verify the result is usable for further chaining
        BufferedImage sheet = result.asSpriteSheet(sheet2 -> sheet2.columns(1)).toBufferedImage();
        assertEquals(32, sheet.getWidth());
        assertEquals(32, sheet.getHeight());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Full pipeline: readPaths → asSpriteSheet → resize → write
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("full pipeline: spriteSheet → resize → writeTo")
    void spriteSheetToResizeToWrite(@TempDir Path dir) throws Exception {
        BufferedImage[] frames = { red(), green(), blue() };
        List<Path> paths = writeFrames(dir, frames);

        Path out = dir.resolve("output.png");
        Imagify.readPaths(paths).asSpriteSheet(2)
                .resize(16, 16)
                .writeTo(out);

        assertTrue(Files.exists(out));
        BufferedImage result = ImageReader.read(out).toBufferedImage();
        assertEquals(16, result.getWidth());
        assertEquals(16, result.getHeight());
    }

    @Test
    @DisplayName("full pipeline: read → asSpriteSheet → writeToBytes")
    void spriteSheetToBytes(@TempDir Path dir) throws Exception {
        BufferedImage src = red();
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        byte[] outBytes = Imagify.read(file).asSpriteSheet(1).writeToBytes(ImageFormat.PNG);
        assertTrue(outBytes.length > 0);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Read from paths into spriteSheet
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("spriteSheet from byte arrays creates valid image")
    void spriteSheetFromByteArrays(@TempDir Path dir) throws Exception {
        BufferedImage r = red(), g = green();
        byte[] pngR = toBytes(r, ImageFormat.PNG);
        byte[] pngG = toBytes(g, ImageFormat.PNG);

        Path fileR = dir.resolve("red.png");
        Path fileG = dir.resolve("green.png");
        Files.write(fileR, pngR);
        Files.write(fileG, pngG);

        Imagify pipe = Imagify.readPaths(List.of(fileR, fileG)).asSpriteSheet(2);
        BufferedImage sheet = pipe.toBufferedImage();
        assertEquals(64, sheet.getWidth());
        assertEquals(32, sheet.getHeight());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Split/unsplit round-trip via SpriteSheet
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("asSpriteSheet output is readable as a still image")
    void asSpriteSheetOutputReadable(@TempDir Path dir) throws Exception {
        BufferedImage src = red();
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        Imagify pipe = Imagify.read(file).asSpriteSheet(1);
        // Verify the result can be read back as a single frame
        FrameSequence seq = pipe.get();
        assertEquals(1, seq.frameCount());
        assertEquals(32, seq.toBufferedImage().getWidth());
    }

    @Test
    @DisplayName("asSpriteSheet with layout produces ARGB image")
    void asSpriteSheetProducesArgb(@TempDir Path dir) throws Exception {
        BufferedImage src = makeImage(32, 32, BufferedImage.TYPE_INT_ARGB, 0xFFFF0000);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        BufferedImage sheet = Imagify.read(file).asSpriteSheet(1).toBufferedImage();
        assertEquals(BufferedImage.TYPE_INT_ARGB, sheet.getType());
    }

    // ── helper ──────────────────────────────────────────────────────

    private static BufferedImage makeImage(int w, int h, int type, int rgb) {
        BufferedImage img = new BufferedImage(w, h, type);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                img.setRGB(x, y, rgb);
        return img;
    }
}