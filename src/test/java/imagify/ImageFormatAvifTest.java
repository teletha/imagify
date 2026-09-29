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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import imagify.avif.ffm.AvifCodec;

/**
 * Tests that {@link ImageFormat#AVIF} behaves like every other format in the public API, that is
 * through {@link ImageWriter} and {@link ImageReader} rather than through the AVIF package
 * directly.
 *
 * <p>The settings the format carries are the reason this is worth having separately from the
 * plug-in's own tests: an {@link javax.imageio.ImageWriteParam} has room for a quality and a
 * compression type and for nothing else, so the encoder speed and the alpha quality can only be
 * asked for through the format.
 */
class ImageFormatAvifTest {

    @BeforeEach
    void requireLibavif() {
        assumeTrue(AvifCodec.isAvailable(), () -> "libavif is not available: " + AvifCodec.getUnavailableReason());
    }

    @Test
    @DisplayName("an image is written to AVIF bytes and read back through the public API")
    void roundTripThroughBytes() throws IOException {
        BufferedImage source = gradient(48, 32, true);

        byte[] avif = ImageWriter.toBytes(source, ImageFormat.AVIF);
        assertTrue(avif.length > 0, "no bytes were written");
        assertEquals("ftyp", new String(avif, 4, 4, "US-ASCII"), "an AVIF file opens with an ftyp box");

        BufferedImage back = ImageReader.read(avif, ImageFormat.AVIF).toBufferedImage();
        assertEquals(48, back.getWidth());
        assertEquals(32, back.getHeight());
        assertTrue(back.getColorModel().hasAlpha(), "the alpha channel should survive");
    }

    @Test
    @DisplayName("the encoder speed the format asks for reaches the encoder")
    void theSpeedReachesTheEncoder() throws Exception {
        BufferedImage source = gradient(64, 48, false);
        int[] expected = pixels(source);

        byte[] thorough = ImageWriter.toBytes(source, ImageFormat.AVIF.speed(0));
        byte[] quickest = ImageWriter.toBytes(source, ImageFormat.AVIF.speed(10));

        assertFalse(Arrays.equals(thorough, quickest), "the same image at two speeds should not have produced the same file");
        double thoroughFidelity = ImageMetrics.psnr(expected, pixels(ImageReader.read(thorough, ImageFormat.AVIF).toBufferedImage()));
        double quickestFidelity = ImageMetrics.psnr(expected, pixels(ImageReader.read(quickest, ImageFormat.AVIF).toBufferedImage()));
        assertTrue(thoroughFidelity >= quickestFidelity, String
                .format("the thorough encoder should not be the further from the source: %.2f dB against %.2f dB", thoroughFidelity, quickestFidelity));
    }

    @Test
    @DisplayName("the alpha quality the format asks for reaches the encoder")
    void theAlphaQualityReachesTheEncoder() throws Exception {
        BufferedImage source = gradient(64, 48, true);
        int[] expected = alphas(pixels(source));

        byte[] byDefault = ImageWriter.toBytes(source, ImageFormat.AVIF);
        byte[] flattened = ImageWriter.toBytes(source, ImageFormat.AVIF.alphaQuality(0));

        assertArrayEquals(expected, alphas(pixels(ImageReader.read(byDefault, ImageFormat.AVIF)
                .toBufferedImage())), "an AVIF keeps every alpha value unless the format says otherwise");
        assertFalse(Arrays.equals(expected, alphas(pixels(ImageReader.read(flattened, ImageFormat.AVIF)
                .toBufferedImage()))), "asking for the worst alpha quality should be visible in the alpha channel");
    }

    @Test
    @DisplayName("a still image is the one its ImageIO plug-in would have written")
    void aStillImageIsTheOneThePluginWrites() throws Exception {
        BufferedImage source = gradient(64, 48, true);

        // The settings have no home in an ImageWriteParam, so this write goes through the codec
        // rather than through the plug-in. What that must not change is the file it produces.
        assertArrayEquals(ImageWriter
                .toBytes(source, ImageFormat.AVIF, 0.6), throughThePlugin(source, 0.6f), "a plain AVIF format should be encoded exactly as the plug-in encodes it");
    }

    @Test
    @DisplayName("the settings reach the animation encoder as well")
    void theSettingsReachAnAnimation() throws Exception {
        FrameSequence sequence = new FrameSequence(List.of(gradient(32, 24, false), gradient(32, 24, true)), new int[] {40, 60}, 0);

        byte[] byDefault = ImageWriter.toBytes(sequence, ImageFormat.AVIF);
        byte[] fastest = ImageWriter.toBytes(sequence, ImageFormat.AVIF.speed(10).alphaQuality(0));

        assertFalse(Arrays.equals(byDefault, fastest), "the settings should have changed the encoding");
        FrameSequence back = ImageReader.read(fastest);
        assertEquals(2, back.frameCount(), "both frames should survive");
        assertEquals(32, back.frames().get(1).getWidth());
    }

    @Test
    @DisplayName("a setting hands back a new value that says what it asks for")
    void theSettingsAreValues() {
        assertNull(ImageFormat.AVIF.speed, "an unset speed is the encoder's own choice, not a number");
        assertNull(ImageFormat.AVIF.alphaQuality, "an unset alpha quality is the encoder's own choice");

        ImageFormat.Avif fast = ImageFormat.AVIF.speed(10);
        assertEquals(10, fast.speed.intValue());
        assertNull(fast.alphaQuality, "the settings that were not asked for stay unset");
        assertNotEquals(ImageFormat.AVIF, fast, "asking for a speed is a different request");
        assertNotSame(ImageFormat.AVIF, fast, "a value the caller owns cannot change the constant");
        assertEquals(fast, ImageFormat.AVIF.speed(10), "two values asking for the same thing are equal");
        assertEquals(fast.hashCode(), ImageFormat.AVIF.speed(10).hashCode());

        // A setting keeps the ones that came before it, whichever order they arrive in.
        assertEquals(ImageFormat.AVIF.speed(10).alphaQuality(0), ImageFormat.AVIF.alphaQuality(0).speed(10));
        assertEquals(0, ImageFormat.AVIF.speed(10).alphaQuality(0).alphaQuality.intValue());
        assertEquals(10, ImageFormat.AVIF.alphaQuality(0).speed(10).speed.intValue());

        assertThrows(IllegalArgumentException.class, () -> ImageFormat.AVIF.speed(11));
        assertThrows(IllegalArgumentException.class, () -> ImageFormat.AVIF.speed(-1));
        assertThrows(IllegalArgumentException.class, () -> ImageFormat.AVIF.alphaQuality(101));
        assertThrows(IllegalArgumentException.class, () -> ImageFormat.AVIF.alphaQuality(-1));
    }

    private static byte[] throughThePlugin(BufferedImage source, float quality) throws IOException {
        java.util.Iterator<javax.imageio.ImageWriter> writers = javax.imageio.ImageIO.getImageWritersByFormatName("avif");
        assumeTrue(writers.hasNext(), "the AVIF plug-in is not registered");
        javax.imageio.ImageWriter writer = writers.next();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (javax.imageio.stream.ImageOutputStream output = javax.imageio.ImageIO.createImageOutputStream(bytes)) {
                writer.setOutput(output);
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
                writer.write(null, new IIOImage(source, null, null), param);
            }
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static int[] alphas(int[] pixels) {
        int[] alpha = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            alpha[i] = pixels[i] >>> 24;
        }
        return alpha;
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
                image.setRGB(x, y, a << 24 | (x * 255 / (width - 1)) << 16 | (y * 255 / (height - 1)) << 8 | 64);
            }
        }
        return image;
    }
}
