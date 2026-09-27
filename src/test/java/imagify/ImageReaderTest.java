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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the byte array and {@link InputStream} entry points of {@link ImageReader}.
 *
 * <p>These used to fail for every format: {@code ImageIO} has no {@code ImageInputStream}
 * provider for a {@code byte[]}, so passing the array straight through produced a {@code null}
 * stream and then a {@link NullPointerException} on close. The array has to be wrapped in a
 * stream, and the wrapping is the whole point of these tests.
 */
class ImageReaderTest {

    @Test
    @DisplayName("a byte array is read back through the explicit format overload")
    void readsFromByteArray() throws IOException {
        byte[] png = write(sample(), ImageFormat.PNG);
        BufferedImage back = ImageReader.read(png, ImageFormat.PNG).toBufferedImage();
        assertEquals(20, back.getWidth());
        assertEquals(10, back.getHeight());
    }

    @Test
    @DisplayName("a byte array is read back with the format detected from the header")
    void detectsFromByteArray() throws IOException {
        byte[] png = write(sample(), ImageFormat.PNG);
        assertEquals(20, ImageReader.read(png).toBufferedImage().getWidth());
    }

    @Test
    @DisplayName("an InputStream reaches the same result as the equivalent byte array")
    void readsFromInputStream() throws IOException {
        byte[] png = write(sample(), ImageFormat.PNG);
        assertEquals(20, ImageReader.read(new ByteArrayInputStream(png), ImageFormat.PNG).toBufferedImage().getWidth());
        assertEquals(20, ImageReader.read(new ByteArrayInputStream(png)).toBufferedImage().getWidth());
    }

    @Test
    @DisplayName("an empty array is reported rather than crashing")
    void rejectsEmptyData() {
        assertThrows(IOException.class, () -> ImageReader.read(new byte[0], ImageFormat.PNG));
    }

    private static BufferedImage sample() {
        BufferedImage image = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
        image.setRGB(3, 4, 0x336699);
        return image;
    }

    private static byte[] write(BufferedImage image, ImageFormat format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter.toStream(image, format, out);
        return out.toByteArray();
    }
}
