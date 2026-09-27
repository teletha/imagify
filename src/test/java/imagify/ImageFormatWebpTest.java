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

import imagify.webp.WebpCodec;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
