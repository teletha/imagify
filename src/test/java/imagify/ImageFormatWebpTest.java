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

import imagify.webp.ffm.WebpCodec;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that {@link ImageFormat#WEBP} behaves like every other format in the public API, that is
 * through {@link ImageWriter} and {@link ImageReader} rather than through the WebP package directly.
 *
 * <p>These are the calls an ordinary user makes, and they used to fail because no WebP service
 * provider was registered, so they are worth pinning down separately from the plug-in's own tests.
 */
class ImageFormatWebpTest {

    @BeforeEach
    void requireLibwebp() {
        assumeTrue(WebpCodec.isAvailable(),
                () -> "libwebp is not available: " + WebpCodec.getUnavailableReason());
    }

    @Test
    @DisplayName("an image is written to WebP bytes and read back through the public API")
    void roundTripThroughBytes() throws IOException {
        BufferedImage source = gradient(48, 32, true);

        byte[] webp = ImageWriter.toBytes(source, ImageFormat.WEBP);
        assertTrue(webp.length > 0, "no bytes were written");
        assertEquals("RIFF", new String(webp, 0, 4, "US-ASCII"), "a WebP file starts with RIFF");
        assertEquals("WEBP", new String(webp, 8, 4, "US-ASCII"), "the form type is WEBP");

        BufferedImage back = ImageReader.read(webp, ImageFormat.WEBP).toBufferedImage();
        assertEquals(48, back.getWidth());
        assertEquals(32, back.getHeight());
        assertTrue(back.getColorModel().hasAlpha(), "the alpha channel should survive");
    }

    @Test
    @DisplayName("the format is detected from the RIFF header when it is not stated")
    void detectedFromHeader() throws IOException {
        byte[] webp = ImageWriter.toBytes(gradient(24, 24, false), ImageFormat.WEBP);
        assertEquals(ImageFormat.WEBP, ImageFormat.detect(webp));

        BufferedImage back = ImageReader.read(webp).toBufferedImage();
        assertEquals(24, back.getWidth());
        assertEquals(24, back.getHeight());
    }

    @Test
    @DisplayName("a .webp path selects WebP without being told the format")
    void inferredFromPath(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("out.webp");
        ImageWriter.toFile(gradient(30, 20, false), file);
        assertTrue(Files.size(file) > 0, "nothing was written to the file");

        BufferedImage back = ImageReader.read(file).toBufferedImage();
        assertEquals(30, back.getWidth());
        assertEquals(20, back.getHeight());
    }

    @Test
    @DisplayName("WebP is offered by ImageIO exactly like the built in formats")
    void offeredByImageIo() {
        assertTrue(javax.imageio.ImageIO.getImageReadersByFormatName("webp").hasNext(),
                "the reader is not registered");
        assertTrue(javax.imageio.ImageIO.getImageWritersByFormatName("webp").hasNext(),
                "the writer is not registered");
    }

    @Test
    @DisplayName("a half transparent pixel keeps its colour and its alpha")
    void alphaIsNotPremultiplied() throws IOException {
        BufferedImage source = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = source.createGraphics();
        g.setComposite(java.awt.AlphaComposite.Src);
        g.setColor(new Color(255, 0, 0, 128));
        g.fillRect(0, 0, 8, 8);
        g.dispose();

        BufferedImage back = ImageReader.read(ImageWriter.toBytes(source, ImageFormat.WEBP),
                ImageFormat.WEBP).toBufferedImage();
        int pixel = back.getRGB(4, 4);
        assertEquals(0x80, pixel >>> 24 & 0xFF, "alpha should still be 128");
        assertEquals(0xFF, pixel >>> 16 & 0xFF, "red should still be 255");
    }

    @Test
    @DisplayName("the flavour the format asks for is the one that reaches the encoder")
    void theFormatChoosesTheBitstream() throws Exception {
        BufferedImage source = gradient(24, 16, true);

        assertEquals(WebpCodec.FORMAT_VP8, WebpCodec.readHeader(ImageWriter.toBytes(source, ImageFormat.WEBP)).format(),
                "the default WebP is the lossy one");

        byte[] lossless = ImageWriter.toBytes(source, ImageFormat.WEBP.lossless());
        assertEquals(WebpCodec.FORMAT_VP8L, WebpCodec.readHeader(lossless).format(),
                "lossless() should have asked for the VP8L bitstream");
        assertArrayEquals(pixels(source), pixels(ImageReader.read(lossless, ImageFormat.WEBP).toBufferedImage()),
                "a lossless round trip should be pixel exact");
    }

    @Test
    @DisplayName("the flavour reaches the encoder for an animation as well")
    void theFormatChoosesTheBitstreamOfAnAnimation() throws Exception {
        FrameSequence sequence = new FrameSequence(
                List.of(gradient(24, 16, false), gradient(24, 16, true)), new int[] {40, 60}, 0);

        byte[] lossy = ImageWriter.toBytes(sequence, ImageFormat.WEBP);
        byte[] lossless = ImageWriter.toBytes(sequence, ImageFormat.WEBP.lossless());
        assertEquals(2, ImageReader.read(lossy).frameCount(), "both frames should survive");
        assertEquals(2, ImageReader.read(lossless).frameCount(), "both frames should survive");

        BufferedImage expected = sequence.frames().get(1);
        assertArrayEquals(pixels(expected), pixels(ImageReader.read(lossless).frames().get(1)),
                "a lossless animation should keep its pixels");
        assertFalse(Arrays.equals(pixels(expected), pixels(ImageReader.read(lossy).frames().get(1))),
                "a lossy animation should not be pixel exact, so the assertion above is worth something");
    }

    @Test
    @DisplayName("a file read back says which flavour it is, so writing it again keeps its pixels")
    void theFlavourIsReadFromTheBytes() throws Exception {
        BufferedImage source = gradient(24, 16, true);

        byte[] lossy = ImageWriter.toBytes(source, ImageFormat.WEBP);
        byte[] lossless = ImageWriter.toBytes(source, ImageFormat.WEBP.lossless());
        assertEquals(ImageFormat.WEBP, ImageFormat.detect(lossy), "a lossy file is the plain format");
        assertEquals(ImageFormat.WEBP.lossless(), ImageFormat.detect(lossless),
                "a lossless file should be detected as the lossless format");
        assertNotEquals(ImageFormat.WEBP, ImageFormat.WEBP.lossless(),
                "the two flavours ask the encoder for different things");
        assertNotSame(ImageFormat.WEBP.lossless(), ImageFormat.WEBP.lossless(),
                "a flavour is a value the caller owns, so adding a setting to it cannot reach the others");
        assertEquals(ImageFormat.WEBP.lossless(), ImageFormat.WEBP.lossless(),
                "two values asking for the same thing are equal, which is what a format has to do");

        // The point of detecting it: read a file and write it out again without saying which
        // flavour to use, and the flavour it was read in is the one it is written in.
        for (byte[] encoded : List.of(lossy, lossless)) {
            byte[] again = ImageWriter.toBytes(ImageReader.read(encoded).toBufferedImage(), ImageFormat.detect(encoded));
            assertEquals(WebpCodec.readHeader(encoded).format(), WebpCodec.readHeader(again).format(),
                    "the flavour of the file was not carried over to the file written from it");
        }

        // A lossless file survives that trip with every pixel, which is the whole reason for it.
        byte[] again = ImageWriter.toBytes(ImageReader.read(lossless).toBufferedImage(), ImageFormat.detect(lossless));
        assertArrayEquals(pixels(source), pixels(ImageReader.read(again).toBufferedImage()),
                "the pixels should have survived the trip through the file twice");
    }

    @Test
    @DisplayName("an animation says which flavour it is as well, frames and all")
    void theFlavourOfAnAnimationIsReadFromTheBytes() throws Exception {
        FrameSequence sequence = new FrameSequence(
                List.of(gradient(24, 16, false), gradient(24, 16, true)), new int[] {40, 60}, 0);

        byte[] lossy = ImageWriter.toBytes(sequence, ImageFormat.WEBP);
        byte[] lossless = ImageWriter.toBytes(sequence, ImageFormat.WEBP.lossless());
        assertEquals(ImageFormat.WEBP, ImageFormat.detect(lossy));
        assertEquals(ImageFormat.WEBP.lossless(), ImageFormat.detect(lossless),
                "the flavour of an animation lives in the bitstream of its first frame");

        byte[] again = ImageWriter.toBytes(ImageReader.read(lossless), ImageFormat.detect(lossless));
        assertEquals(2, ImageReader.read(again).frameCount(), "both frames should survive");
        assertArrayEquals(pixels(sequence.frames().get(1)), pixels(ImageReader.read(again).frames().get(1)),
                "a lossless animation should still be lossless after a read and a write");
    }

    @Test
    @DisplayName("a header too short to hold the bitstream is read as the lossy flavour")
    void aShortHeaderIsTheLossyFlavour() throws Exception {
        byte[] lossless = ImageWriter.toBytes(gradient(24, 16, true), ImageFormat.WEBP.lossless());
        assertEquals(ImageFormat.WEBP, ImageFormat.detect(Arrays.copyOf(lossless, 16)),
                "twelve bytes name the format and nothing more, and the lossy one is the safe answer");
        assertEquals(ImageFormat.WEBP, ImageFormat.detect(Arrays.copyOf(lossless, 12)),
                "twelve bytes name the format and nothing more");
    }

    @Test
    @DisplayName("the effort the format asks for reaches the animation encoder")
    void theCompressionMethodReachesTheEncoder() throws Exception {
        FrameSequence sequence = new FrameSequence(
                List.of(gradient(48, 32, false), gradient(48, 32, true)), new int[] {40, 60}, 0);

        byte[] quickest = ImageWriter.toBytes(sequence, ImageFormat.WEBP.compressionMethod(0));
        byte[] thorough = ImageWriter.toBytes(sequence, ImageFormat.WEBP.compressionMethod(6));

        // Which of the two is the smaller file depends on the content, so what is pinned here is
        // that the setting arrives and changes the encoding.
        assertFalse(Arrays.equals(quickest, thorough),
                "the same frames at two efforts should not have produced the same file");
        assertEquals(2, ImageReader.read(quickest).frameCount(), "both frames should survive");
        assertEquals(2, ImageReader.read(thorough).frameCount(), "both frames should survive");
    }

    @Test
    @DisplayName("a setting hands back a new value that says what it asks for")
    void theSettingsAreValues() {
        ImageFormat.Webp thorough = ImageFormat.WEBP.compressionMethod(6);
        assertEquals(6, thorough.compressionMethod);
        assertEquals(ImageFormat.Webp.DEFAULT_COMPRESSION_METHOD, ImageFormat.WEBP.compressionMethod,
                "the default effort is the one libwebp uses");

        assertNotEquals(ImageFormat.WEBP, thorough, "asking for more effort is a different request");
        assertNotSame(ImageFormat.WEBP, thorough, "a value the caller owns cannot change the constant");
        assertEquals(thorough, ImageFormat.WEBP.compressionMethod(6),
                "two values asking for the same thing are equal");
        assertEquals(thorough.hashCode(), ImageFormat.WEBP.compressionMethod(6).hashCode());

        // A setting keeps the ones that came before it, whichever order they arrive in.
        assertEquals(ImageFormat.WEBP.lossless().compressionMethod(6),
                ImageFormat.WEBP.compressionMethod(6).lossless(),
                "the flavour survives the effort, whichever order the two arrive in");
        assertTrue(ImageFormat.WEBP.lossless().compressionMethod(6).lossless,
                "the lossless flavour survived the effort");

        assertThrows(IllegalArgumentException.class, () -> ImageFormat.WEBP.compressionMethod(7));
        assertThrows(IllegalArgumentException.class, () -> ImageFormat.WEBP.compressionMethod(-1));
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    private static BufferedImage gradient(int width, int height, boolean alpha) {
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage image = new BufferedImage(width, height, type);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int a = alpha ? (x % 2 == 0 ? 128 : 255) : 255;
                image.setRGB(x, y, a << 24
                        | (x * 255 / (width - 1)) << 16
                        | (y * 255 / (height - 1)) << 8
                        | 64);
            }
        }
        return image;
    }

}
