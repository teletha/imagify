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
import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;

class SvgToIcoTest {

    private static final String SVG_DIR = "src/test/resources/svg";

    private static final String ICO_OUT_DIR = "target/test-output/ico";

    private static final String PNG_OUT_DIR = "target/test-output/png";

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("Convert all SVGs under src/test/resources/svg to ICO")
    void allSvgsToIco() throws Exception {
        Path svgPath = Paths.get(SVG_DIR);
        if (!Files.isDirectory(svgPath)) {
            fail("svg directory not found: " + SVG_DIR);
        }

        Path outDir = Paths.get(ICO_OUT_DIR);
        Files.createDirectories(outDir);
        Path pngDir = Paths.get(PNG_OUT_DIR);
        Files.createDirectories(pngDir);

        List<File> svgFiles = svgFiles(svgPath);

        int count = 0;
        for (File svgFile : svgFiles) {
            BufferedImage image = ImageIO.read(svgFile);
            if (image == null) {
                fail("ImageIO.read returned null for: " + svgFile.getName());
            }
            assertTrue(image.getWidth() > 0, "width must be > 0 for " + svgFile.getName());
            assertTrue(image.getHeight() > 0, "height must be > 0 for " + svgFile.getName());

            String baseName = svgFile.getName().replace(".svg", "");
            File icoFile = outDir.resolve(baseName + ".ico").toFile();
            assertTrue(ImageIO.write(image, "ico", icoFile), "failed to write " + svgFile.getName() + " as ICO");
            assertTrue(icoFile.length() > 0, "ICO file is empty: " + baseName + ".ico");

            // The rasterised SVG next to the ICO, so the two can be compared by eye.
            File pngFile = pngDir.resolve(baseName + ".png").toFile();
            assertTrue(ImageIO.write(image, "png", pngFile), "failed to write " + baseName + ".png");
            count++;
        }
        assertTrue(count > 0, "no SVG files were found in " + SVG_DIR);
        System.out.println("ICO files written: " + count + " → " + outDir.toAbsolutePath());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("SVG→ICO→read back: pixels match exactly")
    void allSvgsSurviveTheRoundTrip() throws Exception {
        Path svgPath = Paths.get(SVG_DIR);
        if (!Files.isDirectory(svgPath)) {
            fail("svg directory not found: " + SVG_DIR);
        }

        for (File svgFile : svgFiles(svgPath)) {
            BufferedImage rendered = ImageIO.read(svgFile);
            assertNotNull(rendered, "ImageIO.read returned null for: " + svgFile.getName());

            BufferedImage decoded = readBack(write(rendered));

            assertEquals(rendered.getWidth(), decoded.getWidth(), "width of " + svgFile.getName());
            assertEquals(rendered.getHeight(), decoded.getHeight(), "height of " + svgFile.getName());
            for (int y = 0; y < rendered.getHeight(); y++) {
                for (int x = 0; x < rendered.getWidth(); x++) {
                    // The AND mask of an ICO is one bit wide, so a pixel whose alpha
                    // is below 128 is reported as fully transparent on the way back.
                    // Everything else must come out untouched.
                    int want = rendered.getRGB(x, y);
                    if (((want >>> 24) & 0xFF) < 128) {
                        want &= 0x00FFFFFF;
                    }
                    assertEquals(
                            String.format("%08x", want),
                            String.format("%08x", decoded.getRGB(x, y)),
                            svgFile.getName() + " pixel " + x + "," + y);
                }
            }
        }
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("Readable from SVG via ImageIO")
    void readViaImageIO() throws Exception {
        URL url = getClass().getResource("/svg/default_file.svg");
        assertNotNull(url, "missing /svg/default_file.svg");
        File svgFile = new File(url.toURI());
        BufferedImage image = ImageIO.read(svgFile);
        assertNotNull(image, "failed to read default_file.svg");
        assertTrue(image.getWidth() > 0);
        assertTrue(image.getHeight() > 0);
        System.out.println("default_file.svg → " + image.getWidth() + "x" + image.getHeight());
    }

    private static List<File> svgFiles(Path svgPath) throws Exception {
        List<File> svgFiles = new ArrayList<>();
        Files.walk(svgPath)
                .filter(p -> p.toString().endsWith(".svg"))
                .sorted()
                .map(Path::toFile)
                .forEach(svgFiles::add);
        return svgFiles;
    }

    private static byte[] write(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("ico").next();
        writer.setOutput(ImageIO.createImageOutputStream(out));
        writer.write(null, new IIOImage(image, null, null), null);
        writer.dispose();
        return out.toByteArray();
    }

    private static BufferedImage readBack(byte[] ico) throws Exception {
        ImageReader reader = ImageIO.getImageReadersByFormatName("ico").next();
        reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(ico)));
        BufferedImage decoded = reader.read(0);
        reader.dispose();
        return decoded;
    }
}
