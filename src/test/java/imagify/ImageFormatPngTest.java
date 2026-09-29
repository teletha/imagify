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

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link ImageFormat#PNG} carries the one setting it has, and that the {@code quality}
 * argument of {@link ImageWriter} leaves it alone.
 *
 * <p>The second half is the reason this file exists. The ImageIO plug-in reads a PNG's compression
 * quality as a deflate level, so the argument that every other format spends on deciding how much
 * of the picture to keep was being spent here on how hard the compressor works instead. Asking for
 * the best quality on a lossless format was therefore answered with a file 207 times larger,
 * holding exactly the same pixels. The effort is a setting of the format now, and the test holds
 * the argument to being ignored.
 * </p>
 */
class ImageFormatPngTest {

    @Test
    @DisplayName("the compression level the format asks for reaches the compressor")
    void everyLevelReachesTheCompressor() throws IOException {
        BufferedImage source = flat(400, 400);

        for (int level = 0; level <= ImageFormat.Png.MAX_COMPRESSION_LEVEL; level++) {
            byte[] png = ImageWriter.toBytes(source, ImageFormat.PNG.compressionLevel(level));

            // The plug-in counts deflate levels down from 0 and its quality up from 0.0, so the
            // level a format names is the one asked for as the quality that lands on it. Comparing
            // against the plug-in is what makes this a claim about the level rather than about
            // "a different file".
            assertArrayEquals(throughThePlugin(source, 1.0 - level / 9.0), png, "level " + level + " did not reach the compressor as level " + level);
        }
    }

    @Test
    @DisplayName("the default level is the one the plug-in would have picked")
    void theDefaultLevelIsTheOneThePlugInWouldHavePicked() throws IOException {
        BufferedImage source = flat(400, 400);

        assertEquals(4, ImageFormat.Png.DEFAULT_COMPRESSION_LEVEL, "the plug-in settles on 4, which is not the 6 java.util.zip calls its default");
        assertEquals(ImageFormat.Png.DEFAULT_COMPRESSION_LEVEL, ImageFormat.PNG.compressionLevel);
        assertArrayEquals(throughImageIo(source), ImageWriter
                .toBytes(source, ImageFormat.PNG), "a plain PNG should be encoded exactly as ImageIO encodes it");
    }

    @Test
    @DisplayName("more effort is a smaller file holding the same pixels")
    void moreEffortIsASmallerFileHoldingTheSamePixels() throws IOException {
        BufferedImage source = flat(400, 400);
        int[] expected = ImageMetrics.argb(source);

        byte[] quickest = ImageWriter.toBytes(source, ImageFormat.PNG.compressionLevel(0));
        byte[] thorough = ImageWriter.toBytes(source, ImageFormat.PNG.compressionLevel(9));

        assertTrue(thorough.length * 10 < quickest.length, String
                .format("two deflate levels apart are worth a great deal, but %d bytes against %d is not", thorough.length, quickest.length));

        // Which is the whole reason the argument is no longer spent on this: the two files are the
        // same picture, so neither of them is a better PNG than the other.
        assertArrayEquals(expected, decodedPixels(quickest), "level 0 should keep every pixel");
        assertArrayEquals(expected, decodedPixels(thorough), "level 9 should keep every pixel");
    }

    @Test
    @DisplayName("the quality argument is ignored, as it already was for GIF and BMP")
    void theQualityArgumentIsIgnored() throws IOException {
        BufferedImage source = flat(64, 64);

        for (double quality : new double[] {0.0, 0.5, 1.0}) {
            assertArrayEquals(ImageWriter.toBytes(source, ImageFormat.PNG, 0.0), ImageWriter
                    .toBytes(source, ImageFormat.PNG, quality), "a PNG cannot lose anything, so " + quality + " should have made no difference");
        }
    }

    @Test
    @DisplayName("a setting hands back a new value that says what it asks for")
    void theSettingsAreValues() {
        ImageFormat.Png thorough = ImageFormat.PNG.compressionLevel(9);
        assertEquals(9, thorough.compressionLevel);
        assertEquals(ImageFormat.Png.DEFAULT_COMPRESSION_LEVEL, ImageFormat.PNG.compressionLevel);

        assertNotEquals(ImageFormat.PNG, thorough, "asking for more effort is a different request");
        assertNotSame(ImageFormat.PNG, thorough, "a value the caller owns cannot change the constant");
        assertEquals(thorough, ImageFormat.PNG.compressionLevel(9), "two values asking for the same thing are equal");
        assertEquals(thorough.hashCode(), ImageFormat.PNG.compressionLevel(9).hashCode());

        assertThrows(IllegalArgumentException.class, () -> ImageFormat.PNG.compressionLevel(10));
        assertThrows(IllegalArgumentException.class, () -> ImageFormat.PNG.compressionLevel(-1));
    }

    // ------------------------------------------------------------------------------ helpers

    private static byte[] throughThePlugin(BufferedImage source, double quality) throws IOException {
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
                writer.setOutput(output);
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality((float) quality);
                writer.write(null, new IIOImage(source, null, null), param);
                output.flush();
            }
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static byte[] throughImageIo(BufferedImage source) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(source, "png", bytes);
        return bytes.toByteArray();
    }

    private static int[] decodedPixels(byte[] png) throws IOException {
        return ImageMetrics.argb(ImageReader.read(png, ImageFormat.PNG).toBufferedImage());
    }

    /**
     * Large flat areas, which is the content a deflate level shows itself on: random pixels are the
     * one thing every level compresses equally badly.
     */
    private static BufferedImage flat(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int v = ((x / 40) + (y / 40)) % 2 == 0 ? 0x30 : 0xC0;
                image.setRGB(x, y, 0xFF000000 | (v << 16) | (v << 8) | v);
            }
        }
        return image;
    }
}
