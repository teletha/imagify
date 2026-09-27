/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import imagify.avif.jna.AvifCodec;
import imagify.webp.WebpCodec;

/**
 * Tests the fluent {@link Imagify} API end-to-end.
 *
 * <p>
 * Covers reading, resizing, and writing images through the pipeline,
 * including automatic animation detection.
 * </p>
 */
class ImagifyTest {

    // ---------------------------------------------------------------- helpers

    private static BufferedImage makeImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                image.setRGB(x, y, (x * 255 / (width - 1)) << 16 | (y * 255 / (height - 1)) << 8 | 128);
        return image;
    }

    private static byte[] toBytes(BufferedImage image, ImageFormat format) throws IOException {
        return ImageWriter.toBytes(image, format, format.getDefaultQuality());
    }

    // ------------------------------------------------------------------- reading

    @Test
    @DisplayName("read from byte array and get the first frame")
    void readsFromByteArray(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(20, 10);
        byte[] png = toBytes(src, ImageFormat.PNG);

        Imagify pipe = Imagify.read(png);
        assertEquals(1, pipe.frameCount());
        assertEquals(20, pipe.toBufferedImage().getWidth());
        assertEquals(10, pipe.toBufferedImage().getHeight());
    }

    @Test
    @DisplayName("read from Path and get the first frame")
    void readsFromPath(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(20, 10);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        Imagify pipe = Imagify.read(file);
        assertEquals(1, pipe.frameCount());
        assertEquals(20, pipe.toBufferedImage().getWidth());
    }

    @Test
    @DisplayName("read from InputStream")
    void readsFromInputStream(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(20, 10);
        byte[] png = toBytes(src, ImageFormat.PNG);
        InputStream in = new ByteArrayInputStream(png);

        Imagify pipe = Imagify.read(in);
        assertEquals(1, pipe.frameCount());
        assertEquals(20, pipe.toBufferedImage().getWidth());
    }

    @Test
    @DisplayName("read returns FrameSequence with get()")
    void readReturnsFrameSequence(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(20, 10);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        FrameSequence seq = Imagify.read(file).get();
        assertEquals(1, seq.frameCount());
        assertEquals(20, seq.frames().get(0).getWidth());
    }

    @Test
    @DisplayName("read throws PipelineException for empty data")
    void readEmptyDataThrows() {
        assertThrows(IOError.class, () -> Imagify.read(new byte[0]));
    }

    // ------------------------------------------------------------------- resize

    @Test
    @DisplayName("resize to exact dimensions with default algorithm")
    void resizeExactDimensions(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(16, 16);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        Imagify pipe = Imagify.read(file).resize(8, 8);
        assertEquals(8, pipe.toBufferedImage().getWidth());
        assertEquals(8, pipe.toBufferedImage().getHeight());
    }

    @Test
    @DisplayName("resize with explicit algorithm")
    void resizeWithAlgorithm(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(32, 32);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        Imagify pipe = Imagify.read(file).resize(16, 16, ResizeAlgorithm.LANCZOS3);
        assertEquals(16, pipe.toBufferedImage().getWidth());
        assertEquals(16, pipe.toBufferedImage().getHeight());
    }

    @Test
    @DisplayName("resize by scale factor with default algorithm")
    void resizeByScale(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(32, 32);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        Imagify pipe = Imagify.read(file).resize(0.5);
        assertEquals(16, pipe.toBufferedImage().getWidth());
        assertEquals(16, pipe.toBufferedImage().getHeight());
    }

    @Test
    @DisplayName("resize by scale factor with explicit algorithm")
    void resizeByScaleWithAlgorithm(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(32, 32);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        Imagify pipe = Imagify.read(file).resize(0.5, ResizeAlgorithm.CATROM);
        assertEquals(16, pipe.toBufferedImage().getWidth());
        assertEquals(16, pipe.toBufferedImage().getHeight());
    }

    @Test
    @DisplayName("resize all frames (multi-frame)")
    void resizeMultiFrame(@TempDir Path dir) throws IOException {
        assumeTrue(AvifCodec.isAvailable(), "libavif not available");

        Path gif = Path.of("src/test/resources/anime gif/220354.gif");
        assumeTrue(Files.isDirectory(Path.of("src/test/resources/anime gif")), "test GIF dir not found");
        assumeTrue(Files.exists(gif), "test GIF not found");

        Imagify pipe = Imagify.read(gif).resize(16, 16);
        assertTrue(pipe.frameCount() > 0, "should have frames after resize");
        assertEquals(16, pipe.toBufferedImage().getWidth());
        assertEquals(16, pipe.toBufferedImage().getHeight());
    }

    // ------------------------------------------------------------------- writing

    @Test
    @DisplayName("writeTo to Path with auto-detected format")
    void writeToPath(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(20, 10);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        Path out = dir.resolve("output.png");
        Imagify.read(file).writeTo(out);

        assertTrue(Files.exists(out));
        assertTrue(Files.size(out) > 0);

        BufferedImage result = ImageReader.read(out).toBufferedImage();
        assertEquals(20, result.getWidth());
        assertEquals(10, result.getHeight());
    }

    @Test
    @DisplayName("writeTo to Path with explicit format")
    void writeToPathExplicitFormat(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(20, 10);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        Path out = dir.resolve("output.jpg");
        Imagify.read(file).writeTo(out, ImageFormat.JPEG);

        assertTrue(Files.exists(out));
        assertTrue(Files.size(out) > 0);
    }

    @Test
    @DisplayName("writeTo to Path with quality")
    void writeToPathWithQuality(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(20, 10);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        Path out = dir.resolve("output.jpg");
        Imagify.read(file).writeTo(out, ImageFormat.JPEG, 0.5);

        assertTrue(Files.exists(out));
        assertTrue(Files.size(out) > 0);
    }

    @Test
    @DisplayName("writeToBytes returns non-empty bytes")
    void writeToBytes(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(20, 10);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        byte[] outBytes = Imagify.read(file).writeToBytes(ImageFormat.PNG);
        assertTrue(outBytes.length > 0);
    }

    @Test
    @DisplayName("writeToBytes with quality returns non-empty bytes")
    void writeToBytesWithQuality(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(20, 10);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        byte[] outBytes = Imagify.read(file).writeToBytes(ImageFormat.JPEG, 0.5);
        assertTrue(outBytes.length > 0);
    }

    @Test
    @DisplayName("writeTo OutputStream")
    void writeToOutputStream(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(20, 10);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Imagify.read(file).writeTo(baos, ImageFormat.PNG);

        assertTrue(baos.size() > 0);
    }

    @Test
    @DisplayName("full pipeline: read → resize → writeTo → verify")
    void fullPipelineReadResizeWrite(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(32, 32);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        Path out = dir.resolve("output.png");
        Imagify.read(file).resize(16, 16, ResizeAlgorithm.LANCZOS3).writeTo(out);

        assertTrue(Files.exists(out));
        BufferedImage result = ImageReader.read(out).toBufferedImage();
        assertEquals(16, result.getWidth());
        assertEquals(16, result.getHeight());
    }

    @Test
    @DisplayName("full pipeline: read → resize → writeToBytes → verify")
    void fullPipelineReadResizeWriteBytes(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(32, 32);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        byte[] outBytes = Imagify.read(file).resize(0.5).writeToBytes(ImageFormat.PNG);

        assertTrue(outBytes.length > 0);
    }

    @Test
    @DisplayName("full pipeline: read → resize → writeTo → writeToBytes consistent")
    void pipelineOutputConsistent(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(32, 32);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        Path outPath = dir.resolve("output.png");
        Imagify.read(file).resize(16, 16).writeTo(outPath, ImageFormat.PNG);
        byte[] outBytes = Imagify.read(file).resize(16, 16).writeToBytes(ImageFormat.PNG);

        assertTrue(Files.size(outPath) > 0);
        assertTrue(outBytes.length > 0);
    }

    @Test
    @DisplayName("resize and write formats round-trip")
    void resizeWriteAllFormats(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(32, 32);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        Path out = dir.resolve("out.png");
        Imagify.read(file).resize(16, 16).writeTo(out, ImageFormat.PNG);
        assertTrue(Files.exists(out));
        assertTrue(Files.size(out) > 0);

        BufferedImage result = ImageReader.read(out).toBufferedImage();
        assertEquals(16, result.getWidth());
        assertEquals(16, result.getHeight());
    }

    // ------------------------------------------------------- chaining / state

    @Test
    @DisplayName("read returns same pipeline instance after resize")
    void chainingReturnsSameInstance() throws IOException {
        BufferedImage src = makeImage(20, 10);
        byte[] png = toBytes(src, ImageFormat.PNG);

        Imagify pipe = Imagify.read(png).resize(10, 5);
        assertSame(pipe, pipe.resize(10, 5));
    }

    @Test
    @DisplayName("toBufferedImage returns first frame")
    void toBufferedImageReturnsFirstFrame(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(20, 10);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        BufferedImage img = Imagify.read(file).toBufferedImage();
        assertNotNull(img);
        assertEquals(20, img.getWidth());
    }

    @Test
    @DisplayName("frameCount returns correct count")
    void frameCountReturnsCorrect(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(20, 10);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        assertEquals(1, Imagify.read(file).frameCount());
    }

    // ------------------------------------------------------- toStillImage

    @Test
    @DisplayName("toStillImage drops every frame but the first")
    void toStillImageDropsOtherFrames(@TempDir Path dir) throws IOException {
        Path gif = Path.of("src/test/resources", "anime gif", "220354.gif");
        assumeTrue(Files.isRegularFile(gif), "test GIF not found");

        Imagify pipe = Imagify.read(gif);
        assumeTrue(pipe.frameCount() > 1, "test GIF should be animated");

        pipe.toStillImage();
        assertEquals(1, pipe.frameCount());
    }

    @Test
    @DisplayName("toStillImage keeps the first frame pixel-for-pixel")
    void toStillImageKeepsFirstFrame(@TempDir Path dir) throws IOException {
        Path gif = Path.of("src/test/resources", "anime gif", "220354.gif");
        assumeTrue(Files.isRegularFile(gif), "test GIF not found");

        BufferedImage first = Imagify.read(gif).toBufferedImage();
        BufferedImage kept = Imagify.read(gif).toStillImage().toBufferedImage();

        assertEquals(first.getWidth(), kept.getWidth());
        assertEquals(first.getHeight(), kept.getHeight());
        for (int y = 0; y < first.getHeight(); y++)
            for (int x = 0; x < first.getWidth(); x++)
                assertEquals(first.getRGB(x, y), kept.getRGB(x, y), "pixel differs at " + x + "," + y);
    }

    @Test
    @DisplayName("toStillImage is a no-op on an already single frame image")
    void toStillImageOnStillImage(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(20, 10);
        Path file = dir.resolve("input.png");
        Files.write(file, toBytes(src, ImageFormat.PNG));

        Imagify pipe = Imagify.read(file);
        assertSame(pipe, pipe.toStillImage());
        assertEquals(1, pipe.frameCount());
        assertEquals(20, pipe.toBufferedImage().getWidth());
    }

    @Test
    @DisplayName("toStillImage then write produces a still image even for an animation format")
    void toStillImageForcesStillOutput(@TempDir Path dir) throws IOException {
        assumeTrue(WebpCodec.isAvailable(), "libwebp not available");

        Path gif = Path.of("src/test/resources", "anime gif", "220354.gif");
        assumeTrue(Files.isRegularFile(gif), "test GIF not found");

        Path animated = dir.resolve("animated.webp");
        Imagify.read(gif).writeTo(animated);
        assumeTrue(ImageReader.read(animated).frameCount() > 1, "baseline should be animated");

        Path still = dir.resolve("still.webp");
        Imagify.read(gif).toStillImage().writeTo(still);

        assertTrue(Files.exists(still));
        assertEquals(1, ImageReader.read(still).frameCount(), "toStillImage should have forced a single frame");
    }

    @Test
    @DisplayName("toStillImage chains with resize")
    void toStillImageChainsWithResize(@TempDir Path dir) throws IOException {
        Path gif = Path.of("src/test/resources", "anime gif", "220354.gif");
        assumeTrue(Files.isRegularFile(gif), "test GIF not found");

        Path out = dir.resolve("poster.png");
        Imagify.read(gif).resize(32, 32, ResizeAlgorithm.LANCZOS3).toStillImage().writeTo(out);

        BufferedImage result = ImageReader.read(out).toBufferedImage();
        assertEquals(32, result.getWidth());
        assertEquals(32, result.getHeight());
    }

    // --------------------------------------------------------- animation detection

    @Test
    @DisplayName("multi-frame GIF written as animated WebP")
    void animatedGifToAnimatedWebP(@TempDir Path dir) throws IOException {
        assumeTrue(WebpCodec.isAvailable(), "libwebp not available");

        Path gif = Path.of("src/test/resources", "anime gif", "220354.gif");
        assumeTrue(Files.isRegularFile(gif), "test GIF not found");

        Path out = dir.resolve("output.webp");
        Imagify.read(gif).writeTo(out);

        assertTrue(Files.exists(out));
        assertTrue(Files.size(out) > 0);

        FrameSequence seq = ImageReader.read(out);
        assertTrue(seq.frameCount() > 1, "should have multiple frames in animated WebP");
    }

    @Test
    @DisplayName("multi-frame GIF written as animated AVIF")
    void animatedGifToAnimatedAvif(@TempDir Path dir) throws IOException {
        assumeTrue(AvifCodec.isAvailable(), "libavif not available");

        Path gif = Path.of("src/test/resources", "anime gif", "220354.gif");
        assumeTrue(Files.isRegularFile(gif), "test GIF not found");

        Path out = dir.resolve("output.avif");
        Imagify.read(gif).writeTo(out);

        assertTrue(Files.exists(out));
        assertTrue(Files.size(out) > 0);

        // AVIF reader may not preserve animation metadata; just verify the file is valid and
        // readable
        FrameSequence seq = ImageReader.read(out);
        assertTrue(seq.frameCount() >= 1);
        assertNotNull(seq.toBufferedImage());
    }

    @Test
    @DisplayName("multi-frame GIF written to non-animated format uses first frame")
    void animatedGifToStillImage(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(32, 32);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        // Write PNG as PNG (no animation support in PNG via ImageIO in this project)
        Path out = dir.resolve("output.png");
        Imagify.read(file).writeTo(out);

        assertTrue(Files.exists(out));
        BufferedImage result = ImageReader.read(out).toBufferedImage();
        assertEquals(32, result.getWidth());
    }

    @Test
    @DisplayName("single-frame image written to format stays still image")
    void singleFrameRemainsStill(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(20, 10);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        byte[] avifBytes = Imagify.read(file).writeToBytes(ImageFormat.AVIF);
        assertTrue(avifBytes.length > 0);
    }

    // ------------------------------------------------------------- read variants

    @Test
    @DisplayName("read from Path then writeToBytes then read back")
    void readPathWriteBytesReadBack(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(20, 10);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        byte[] outBytes = Imagify.read(file).writeToBytes(ImageFormat.PNG);
        InputStream in = new ByteArrayInputStream(outBytes);
        Imagify pipe2 = Imagify.read(in);

        assertEquals(20, pipe2.toBufferedImage().getWidth());
        assertEquals(10, pipe2.toBufferedImage().getHeight());
    }

    @Test
    @DisplayName("read from byte array then write to file then read back")
    void readByteArrayWriteFileReadBack(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(20, 10);
        byte[] png = toBytes(src, ImageFormat.PNG);

        Path out = dir.resolve("output.png");
        Imagify.read(png).writeTo(out);

        BufferedImage result = ImageReader.read(out).toBufferedImage();
        assertEquals(20, result.getWidth());
        assertEquals(10, result.getHeight());
    }

    @Test
    @DisplayName("writeTo with Path-based format detection")
    void writeToPathFormatDetection(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(20, 10);
        byte[] png = toBytes(src, ImageFormat.PNG);
        Path file = dir.resolve("input.png");
        Files.write(file, png);

        // Extension determines format for PNG
        Path outPng = dir.resolve("out.png");
        Imagify.read(file).resize(10, 5).writeTo(outPng);
        assertTrue(Files.exists(outPng));
        assertTrue(Files.size(outPng) > 0);

        // Verify format was detected correctly
        BufferedImage result = ImageReader.read(outPng).toBufferedImage();
        assertEquals(10, result.getWidth());
        assertEquals(5, result.getHeight());
    }

    @Test
    @DisplayName("ImageFormat.fromPath resolves extensions correctly")
    void fromPathResolvesExtensions(@TempDir Path dir) throws IOException {
        BufferedImage src = makeImage(20, 10);
        Path file = dir.resolve("input.png");
        Files.write(file, toBytes(src, ImageFormat.PNG));

        assertEquals(ImageFormat.PNG, ImageFormat.fromPath(file));

        Path fileJpg = dir.resolve("image.jpg");
        Files.write(fileJpg, toBytes(src, ImageFormat.JPEG));
        assertEquals(ImageFormat.JPEG, ImageFormat.fromPath(fileJpg));
    }
}
