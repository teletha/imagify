/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.ico;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;

class IcoImageIOTest {

    private static BufferedImage sample(int w, int h) {
        // Asymmetric on purpose: a flipped or shifted row is immediately visible.
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = (x == 0) ? 0x00FFFFFF        // fully transparent column
                        : (y == 0) ? 0xFFFF0000         // opaque red top row
                        : (y == 1) ? 0x8000FF00         // half transparent green
                        : 0xFF0000FF;                  // opaque blue bottom row
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    private static byte[] write(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter writer = new IcoImageWriter(new IcoImageWriterSpi());
        writer.setOutput(ImageIO.createImageOutputStream(out));
        writer.write(null, new IIOImage(image, null, null), writer.getDefaultWriteParam());
        writer.dispose();
        return out.toByteArray();
    }

    private static BufferedImage read(byte[] ico) throws IOException {
        ImageReader reader = new IcoImageReader(new IcoImageReaderSpi());
        reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(ico)));
        BufferedImage decoded = reader.read(0);
        reader.dispose();
        return decoded;
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("an image survives a write and read round trip")
    void roundTrip() throws IOException {
        for (int[] size : new int[][] { { 4, 4 }, { 5, 3 }, { 32, 32 }, { 16, 16 } }) {
            BufferedImage image = sample(size[0], size[1]);
            BufferedImage decoded = read(write(image));
            assertEquals(size[0], decoded.getWidth(), "width of " + size[0] + "x" + size[1]);
            assertEquals(size[1], decoded.getHeight(), "height of " + size[0] + "x" + size[1]);
            for (int y = 0; y < size[1]; y++) {
                for (int x = 0; x < size[0]; x++) {
                    assertEquals(
                            String.format("%08x", image.getRGB(x, y)),
                            String.format("%08x", decoded.getRGB(x, y)),
                            "pixel " + x + "," + y + " of " + size[0] + "x" + size[1]);
                }
            }
        }
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("the container declares the size the format expects")
    void containerLayout() throws IOException {
        byte[] ico = write(sample(32, 32));
        assertEquals(6 + 16 + 40 + 32 * 32 * 4 + 4 * 32, ico.length, "total file size");

        assertEquals(0, ico[0], "reserved");
        assertEquals(0, ico[1], "reserved");
        assertEquals(1, ico[2], "type is ICO");
        assertEquals(0, ico[3], "type is ICO");
        assertEquals(1, ico[4], "one image");
        assertEquals(0, ico[5], "one image");

        assertEquals(32, ico[6] & 0xFF, "directory width");
        assertEquals(32, ico[7] & 0xFF, "directory height");
        assertEquals(32, ico[12] & 0xFF, "directory bit count");

        int dib = 22;
        assertEquals(40, le32(ico, dib), "biSize");
        assertEquals(32, le32(ico, dib + 4), "biWidth");
        assertEquals(64, le32(ico, dib + 8), "biHeight is twice the image height");
        assertEquals(1, ico[dib + 12] & 0xFF, "biPlanes");
        assertEquals(32, ico[dib + 14] & 0xFF, "biBitCount");
        assertEquals(0, le32(ico, dib + 16), "biCompression is BI_RGB");
        assertEquals(32 * 32 * 4 + 4 * 32, le32(ico, dib + 20), "biSizeImage");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("the colour rows start right after the 40 byte header")
    void colourDataFollowsTheHeaderImmediately() throws IOException {
        int w = 32, h = 32;
        byte[] ico = write(sample(w, h));
        int first = 22 + 40;
        int rowBytes = w * 4;
        // A DIB is bottom-up, so the last row in the file is the top row of the
        // image, which is the opaque red one in the sample.
        int last = first + (h - 1) * rowBytes;
        for (int x = 0; x < w; x++) {
            int p = last + x * 4;
            int argb = ((ico[p + 3] & 0xFF) << 24)
                    | ((ico[p + 2] & 0xFF) << 16)
                    | ((ico[p + 1] & 0xFF) << 8)
                    | (ico[p] & 0xFF);
            // The colour data keeps the original alpha, so the column stays
            // 0x00ffffff; it is the AND mask that makes it transparent.
            int want = (x == 0) ? 0x00FFFFFF : 0xFFFF0000;
            assertEquals(String.format("%08x", want), String.format("%08x", argb),
                    "top row pixel " + x);
        }
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("the AND mask marks the transparent pixels")
    void andMask() throws IOException {
        byte[] ico = write(sample(32, 32));
        int maskOffset = 22 + 40 + 32 * 32 * 4;
        // The first column is fully transparent, so bit 7 of each mask row is set.
        for (int row = 0; row < 32; row++) {
            int value = ico[maskOffset + row * 4] & 0xFF;
            assertEquals(0x80, value, "AND mask row " + row);
        }
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a 256 pixel side is stored as the reserved 0 byte")
    void largestSide() throws IOException {
        byte[] ico = write(sample(256, 256));
        assertEquals(0, ico[6] & 0xFF, "width of 256 is stored as 0");
        assertEquals(0, ico[7] & 0xFF, "height of 256 is stored as 0");
        BufferedImage decoded = read(ico);
        assertEquals(256, decoded.getWidth());
        assertEquals(256, decoded.getHeight());
        assertEquals(String.format("%08x", 0xFFFF0000),
                String.format("%08x", decoded.getRGB(1, 0)), "top right of the red row");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("an image larger than 256 pixels is rejected")
    void tooLarge() throws IOException {
        ImageWriter writer = new IcoImageWriter(new IcoImageWriterSpi());
        writer.setOutput(ImageIO.createImageOutputStream(new ByteArrayOutputStream()));
        IIOException failure = assertThrows(IIOException.class, () -> writer.write(null,
                new IIOImage(sample(257, 16), null, null), null));
        assertTrue(failure.getMessage().contains("256x256"), failure.getMessage());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("the writer is registered with ImageIO")
    void registered() {
        assertTrue(ImageIO.getImageWritersByFormatName("ico").hasNext());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("the reader recognises an ICO file")
    void canDecode() {
        byte[] ico = new byte[]{0, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        assertTrue(new IcoImageReaderSpi().canDecodeInput(ico));
    }

    private static int le32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }
}
