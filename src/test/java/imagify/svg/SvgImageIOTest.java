/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.svg;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;

class SvgImageIOTest {

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a simple SVG is read and rasterized")
    void roundTrip() throws IOException {
        String svg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="4" height="4">
              <rect width="4" height="4" fill="red"/>
            </svg>""";
        byte[] data = svg.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        ImageReader reader = new SvgImageReader(new SvgImageReaderSpi());
        reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(data)));
        assertTrue(new SvgImageReaderSpi().canDecodeInput(new ByteArrayInputStream(data)));
        BufferedImage image = reader.read(0);
        assertNotNull(image);
        assertEquals(4, image.getWidth());
        assertEquals(4, image.getHeight());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("SVG files from src/test/resources/svg are read correctly")
    void readSvgFiles() throws IOException, URISyntaxException {
        String[] names = {"icon-red.svg", "icon-green.svg", "icon-blue.svg"};
        for (String name : names) {
            URL url = getClass().getResource("/svg/" + name);
            assertNotNull(url, "missing /svg/" + name);
            File file = new File(url.toURI());
            assertTrue(file.exists(), "file not found: " + file);

            BufferedImage image = ImageIO.read(file);
            assertNotNull(image, "failed to read " + name);
            assertTrue(image.getWidth() > 0, "width must be > 0 for " + name);
            assertTrue(image.getHeight() > 0, "height must be > 0 for " + name);
        }
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("the reader is registered with ImageIO")
    void registered() {
        assertTrue(ImageIO.getImageReadersByFormatName("svg").hasNext());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("the reader recognises an SVG file")
    void canDecode() {
        byte[] svg = "<svg></svg>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(new SvgImageReaderSpi().canDecodeInput(svg));
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("the reader refuses non-SVG input")
    void cantDecode() {
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47};
        assertFalse(new SvgImageReaderSpi().canDecodeInput(png));
    }
}
