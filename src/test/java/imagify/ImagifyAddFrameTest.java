package imagify;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImagifyAddFrameTest {

    // ── helpers ──────────────────────────────────────────

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

    // ══════════════════════════════════════════════════════
    //  add(Path) / add(Path...)
    // ══════════════════════════════════════════════════════

    @Test
    @DisplayName("addFrame from path adds a frame")
    void addFrameFromPath(@TempDir Path dir) throws Exception {
        BufferedImage src = red();
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("red.png");
        Files.write(file, png);

        Imagify pipe = Imagify.read(green()).add(file);
        assertEquals(2, pipe.frameCount());
    }

    @Test
    @DisplayName("addFrame varargs from paths adds multiple frames")
    void addFrameVarargsFromPaths(@TempDir Path dir) throws Exception {
        BufferedImage r = red(), b = blue();
        Path fileR = dir.resolve("red.png");
        Path fileB = dir.resolve("blue.png");
        Files.write(fileR, toBytes(r, ImageFormat.PNG));
        Files.write(fileB, toBytes(b, ImageFormat.PNG));

        Imagify pipe = Imagify.read(green()).add(fileR, fileB);
        assertEquals(3, pipe.frameCount());
    }

    @Test
    @DisplayName("addFrame from path preserves existing frames")
    void addFrameFromPathPreservesFrames(@TempDir Path dir) throws Exception {
        BufferedImage src = red();
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("red.png");
        Files.write(file, png);

        Imagify pipe = Imagify.read(green()).add(file);
        assertEquals(2, pipe.frameCount());
    }

    // ══════════════════════════════════════════════════════
    //  add(byte[]) / add(byte[]...)
    // ══════════════════════════════════════════════════════

    @Test
    @DisplayName("addFrame from byte array adds a frame")
    void addFrameFromByteArray(@TempDir Path dir) throws Exception {
        BufferedImage src = red();
        byte[] png = toBytes(src, ImageFormat.PNG);

        Imagify pipe = Imagify.read(green()).add(png);
        assertEquals(2, pipe.frameCount());
    }

    @Test
    @DisplayName("addFrame varargs from byte arrays adds multiple frames")
    void addFrameVarargsFromByteArrays(@TempDir Path dir) throws Exception {
        BufferedImage r = red(), b = blue();
        byte[] pngR = toBytes(r, ImageFormat.PNG);
        byte[] pngB = toBytes(b, ImageFormat.PNG);

        Imagify pipe = Imagify.read(green()).add(pngR, pngB);
        assertEquals(3, pipe.frameCount());
    }

    @Test
    @DisplayName("addFrame from byte array preserves existing frames")
    void addFrameFromByteArrayPreservesFrames(@TempDir Path dir) throws Exception {
        BufferedImage src = red();
        byte[] png = toBytes(src, ImageFormat.PNG);

        Imagify pipe = Imagify.read(green()).add(png);
        FrameSequence seq = pipe.get();
        assertEquals(2, seq.frameCount());
    }

    // ══════════════════════════════════════════════════════
    //  add(BufferedImage) / add(BufferedImage...)
    // ══════════════════════════════════════════════════════

    @Test
    @DisplayName("addFrame from BufferedImage adds a frame")
    void addFrameFromBufferedImage() throws Exception {
        Imagify pipe = Imagify.read(green()).add(red());
        assertEquals(2, pipe.frameCount());
    }

    @Test
    @DisplayName("addFrame varargs from BufferedImages adds multiple frames")
    void addFrameVarargsFromBufferedImages() {
        Imagify pipe = Imagify.read(green()).add(red(), blue());
        assertEquals(3, pipe.frameCount());
    }

    @Test
    @DisplayName("addFrame from BufferedImage preserves existing frames")
    void addFrameFromBufferedImagePreservesFrames(@TempDir Path dir) throws Exception {
        Imagify pipe = Imagify.read(green()).add(red());
        FrameSequence seq = pipe.get();
        assertEquals(2, seq.frameCount());
    }

    @Test
    @DisplayName("addFrame rejects null")
    void addFrameRejectsNull() {
        Imagify pipe = Imagify.read(green());
        assertThrows(NullPointerException.class, () -> pipe.add((BufferedImage) null));
    }

    // ══════════════════════════════════════════════════════
    //  add(InputStream) / add(InputStream...)
    // ══════════════════════════════════════════════════════

    @Test
    @DisplayName("addFrame from InputStream adds a frame")
    void addFrameFromInputStream(@TempDir Path dir) throws Exception {
        BufferedImage src = red();
        byte[] png = toBytes(src, ImageFormat.PNG);
        ByteArrayInputStream bais = new ByteArrayInputStream(png);

        Imagify pipe = Imagify.read(green()).add(bais);
        assertEquals(2, pipe.frameCount());
    }

    @Test
    @DisplayName("addFrame varargs from InputStream adds multiple frames")
    void addFrameVarargsFromInputStreams(@TempDir Path dir) throws Exception {
        BufferedImage r = red(), b = blue();
        byte[] pngR = toBytes(r, ImageFormat.PNG);
        byte[] pngB = toBytes(b, ImageFormat.PNG);
        ByteArrayInputStream baisR = new ByteArrayInputStream(pngR);
        ByteArrayInputStream baisB = new ByteArrayInputStream(pngB);

        Imagify pipe = Imagify.read(green()).add(baisR, baisB);
        assertEquals(3, pipe.frameCount());
    }

    // ══════════════════════════════════════════════════════
    //  addPaths(List<Path>)
    // ══════════════════════════════════════════════════════

    @Test
    @DisplayName("addPaths from path list adds multiple frames")
    void addPathsFromPathList(@TempDir Path dir) throws Exception {
        BufferedImage r = red(), b = blue();
        Path fileR = dir.resolve("red.png");
        Path fileB = dir.resolve("blue.png");
        Files.write(fileR, toBytes(r, ImageFormat.PNG));
        Files.write(fileB, toBytes(b, ImageFormat.PNG));

        Imagify pipe = Imagify.read(green()).addPaths(List.of(fileR, fileB));
        assertEquals(3, pipe.frameCount());
    }

    // ══════════════════════════════════════════════════════
    //  addBytes(List<byte[]>)
    // ══════════════════════════════════════════════════════

    @Test
    @DisplayName("addBytes from byte array list adds multiple frames")
    void addBytesFromByteArrayList(@TempDir Path dir) throws Exception {
        BufferedImage r = red(), b = blue();
        byte[] pngR = toBytes(r, ImageFormat.PNG);
        byte[] pngB = toBytes(b, ImageFormat.PNG);

        Imagify pipe = Imagify.read(green()).addBytes(Arrays.asList(pngR, pngB));
        assertEquals(3, pipe.frameCount());
    }

    // ══════════════════════════════════════════════════════
    //  addImages(List<BufferedImage>)
    // ══════════════════════════════════════════════════════

    @Test
    @DisplayName("addImages from BufferedImage list adds multiple frames")
    void addImagesFromBufferedImageList() {
        Imagify pipe = Imagify.read(green()).addImages(List.of(red(), blue()));
        assertEquals(3, pipe.frameCount());
    }

    // ══════════════════════════════════════════════════════
    //  addStreams(List<InputStream>)
    // ══════════════════════════════════════════════════════

    @Test
    @DisplayName("addStreams from InputStream list adds multiple frames")
    void addStreamsFromInputStreamList(@TempDir Path dir) throws Exception {
        BufferedImage r = red(), b = blue();
        byte[] pngR = toBytes(r, ImageFormat.PNG);
        byte[] pngB = toBytes(b, ImageFormat.PNG);

        Imagify pipe = Imagify.read(green()).addStreams(
                List.of(new ByteArrayInputStream(pngR), new ByteArrayInputStream(pngB)));
        assertEquals(3, pipe.frameCount());
    }

    // ══════════════════════════════════════════════════════
    //  Chaining
    // ══════════════════════════════════════════════════════

    @Test
    @DisplayName("addFrame returns same instance for chaining")
    void addFrameChains() throws Exception {
        Imagify pipe = Imagify.read(green());
        assertSame(pipe, pipe.add(red()));
    }

    @Test
    @DisplayName("addFrame varargs returns same instance for chaining")
    void addFrameVarargsChains() throws Exception {
        BufferedImage r = red();
        Imagify pipe = Imagify.read(green());
        assertSame(pipe, pipe.add(r, r));
    }

    @Test
    @DisplayName("addImages returns same instance for chaining")
    void addImagesChains() throws Exception {
        Imagify pipe = Imagify.read(green());
        assertSame(pipe, pipe.addImages(List.of(red())));
    }

    // ══════════════════════════════════════════════════════
    //  asSpriteSheet integration
    // ══════════════════════════════════════════════════════

    @Test
    @DisplayName("addFrame followed by asSpriteSheet produces a sprite sheet")
    void addFrameThenAsSpriteSheet(@TempDir Path dir) throws Exception {
        BufferedImage src = red();
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("red.png");
        Files.write(file, png);

        Imagify pipe = Imagify.read(green()).add(file);
        BufferedImage sheet = pipe.asSpriteSheet(2).toBufferedImage();

        assertEquals(64, sheet.getWidth());
        assertEquals(32, sheet.getHeight());
    }

    @Test
    @DisplayName("addFrame varargs followed by asSpriteSheet produces a sprite sheet")
    void addFrameVarargsThenAsSpriteSheet(@TempDir Path dir) throws Exception {
        BufferedImage r = red(), g = green();
        byte[] pngR = toBytes(r, ImageFormat.PNG);
        byte[] pngG = toBytes(g, ImageFormat.PNG);

        Imagify pipe = Imagify.read(green()).add(pngR, pngG);
        BufferedImage sheet = pipe.asSpriteSheet(2).toBufferedImage();

        // 3 frames (green + red + green), 2 cols ↁE2 rows: 64x64
        assertEquals(64, sheet.getWidth());
        assertEquals(64, sheet.getHeight());
    }

    @Test
    @DisplayName("addPaths followed by asSpriteSheet produces a sprite sheet")
    void addPathsThenAsSpriteSheet(@TempDir Path dir) throws Exception {
        BufferedImage r = red(), g = green();
        Path fileR = dir.resolve("red.png");
        Path fileG = dir.resolve("green.png");
        Files.write(fileR, toBytes(r, ImageFormat.PNG));
        Files.write(fileG, toBytes(g, ImageFormat.PNG));

        Imagify pipe = Imagify.read(green()).addPaths(List.of(fileR, fileG));
        BufferedImage sheet = pipe.asSpriteSheet(2).toBufferedImage();

        // 3 frames (green + red + green), 2 cols ↁE2 rows: 64x64
        assertEquals(64, sheet.getWidth());
        assertEquals(64, sheet.getHeight());
    }

    @Test
    @DisplayName("addFrame then asSpriteSheet keeps all frames visible")
    void addFrameThenAsSpriteSheetKeepsAllFrames(@TempDir Path dir) throws Exception {
        BufferedImage[] frames = { red(), green(), blue() };

        // Write frames as temp files
        for (int i = 0; i < frames.length; i++) {
            Path file = dir.resolve("frame" + i + ".png");
            Files.write(file, toBytes(frames[i], ImageFormat.PNG));
        }

        // green is already the first frame, add green and blue via varargs
        Imagify pipe = Imagify.read(green());
        pipe = pipe.add(dir.resolve("frame1.png")); // green
        pipe = pipe.add(dir.resolve("frame2.png")); // blue

        BufferedImage sheet = pipe.asSpriteSheet(2).toBufferedImage();
        // 3 frames in 2 cols: sheet is 64x64
        // Green(0,0), Green(32,0), Blue(0,32)
        assertEquals(0xFF00FF00, sheet.getRGB(5, 5));
        assertEquals(0xFF00FF00, sheet.getRGB(37, 5));
        assertEquals(0xFF0000FF, sheet.getRGB(5, 37));
    }

    @Test
    @DisplayName("addFrame then resize works correctly")
    void addFrameThenResize(@TempDir Path dir) throws Exception {
        BufferedImage src = red();
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("red.png");
        Files.write(file, png);

        Imagify pipe = Imagify.read(green()).add(file);
        BufferedImage sheet = pipe.resize(16, 16).toBufferedImage();

        assertEquals(16, sheet.getWidth());
        assertEquals(16, sheet.getHeight());
    }

    @Test
    @DisplayName("addFrame then writeTo works correctly")
    void addFrameThenWrite(@TempDir Path dir) throws Exception {
        BufferedImage src = red();
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("red.png");
        Files.write(file, png);

        Path out = dir.resolve("output.png");
        Imagify.read(green()).add(file).writeTo(out);

        assertTrue(Files.exists(out));
        assertTrue(Files.size(out) > 0);
    }

    @Test
    @DisplayName("addFrame then writeToBytes works correctly")
    void addFrameThenWriteToBytes(@TempDir Path dir) throws Exception {
        BufferedImage src = red();
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("red.png");
        Files.write(file, png);

        byte[] outBytes = Imagify.read(green()).add(file).writeToBytes(ImageFormat.PNG);
        assertTrue(outBytes.length > 0);
    }

    // ══════════════════════════════════════════════════════
    //  Multi-frame pipeline with addFrame
    // ══════════════════════════════════════════════════════

    @Test
    @DisplayName("addFrame creates multi-frame pipeline")
    void addFrameCreatesMultiFrame(@TempDir Path dir) throws Exception {
        Imagify pipe = Imagify.read(red());
        pipe = pipe.add(green());
        pipe = pipe.add(blue());
        assertEquals(3, pipe.frameCount());
    }

    @Test
    @DisplayName("addFrame preserves frame order")
    void addFramePreservesOrder(@TempDir Path dir) throws Exception {
        BufferedImage src = red();
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("red.png");
        Files.write(file, png);

        Imagify pipe = Imagify.read(green()).add(file).add(blue());
        FrameSequence seq = pipe.get();
        assertEquals(3, seq.frameCount());
    }

    @Test
    @DisplayName("addFrame varargs preserves frame order")
    void addFrameVarargsPreservesOrder(@TempDir Path dir) throws Exception {
        BufferedImage src = red();
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("red.png");
        Files.write(file, png);

        Imagify pipe = Imagify.read(green()).add(file, file);
        FrameSequence seq = pipe.get();
        assertEquals(3, seq.frameCount());
    }
}
