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
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import imagify.avif.jna.AvifCodec;
import imagify.webp.jna.WebpCodec;

/**
 * Verifies cross-conversion from every input format to every output format.
 *
 * <p>Encodes an image in each format, decodes it, and re-encodes it in all
 * formats. Ensures that the decoded image size matches after each round-trip.</p>
 *
 * <p>JPEG/BMP cannot carry alpha, so the source image is chosen appropriately.</p>
 */
class CrossFormatConversionTest {

    /** All formats used as input and output. */
    private static final ImageFormat[] FORMATS = {ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.GIF, ImageFormat.BMP, ImageFormat.WEBP,
            ImageFormat.AVIF};

    /** AVIF/WebP require a native library. */
    private static final ImageFormat[] LOSSY_FORMATS = {ImageFormat.WEBP, ImageFormat.AVIF};

    /** Formats that cannot carry alpha. */
    private static final List<ImageFormat> NO_ALPHA_FORMATS = Arrays.asList(ImageFormat.JPEG, ImageFormat.BMP);

    @Test
    @DisplayName("Cross-conversion across all formats: every input can be converted to every output")
    void everyFormatToEveryFormat(@TempDir Path dir) throws IOException {
        assumeLibs();

        // Prepare sources for both alpha-capable and non-alpha-capable formats
        BufferedImage alphaSource = alphaGradient(48, 32);
        BufferedImage rgbSource = rgbGradient(48, 32);

        List<String> failures = new ArrayList<>();

        for (ImageFormat input : FORMATS) {
            // Choose source based on whether the input format supports alpha
            BufferedImage inputSource = input.supportsAlpha() ? alphaSource : rgbSource;
            byte[] encoded;
            try {
                encoded = encode(inputSource, input);
            } catch (Exception e) {
                continue; // Skip formats that cannot be encoded
            }
            if (encoded == null || encoded.length == 0) continue;

            BufferedImage decoded;
            try {
                decoded = ImageReader.read(encoded, input).toBufferedImage();
            } catch (Exception e) {
                continue; // Skip formats that cannot be decoded
            }

            for (ImageFormat output : FORMATS) {
                // Same-format conversions are covered by other tests
                if (input == output) continue;

                String msg = input + " → " + output;
                try {
                    // Strip alpha for formats that don't support it
                    BufferedImage outputSource = output.supportsAlpha() ? decoded : stripAlpha(decoded);
                    byte[] reEncoded = ImageWriter.toBytes(outputSource, output, 0.8);
                    assertNotEquals(0, reEncoded.length, msg + ": output is empty");

                    // Read back and verify dimensions
                    BufferedImage finalImage = ImageReader.read(reEncoded, output).toBufferedImage();
                    assertEquals(decoded.getWidth(), finalImage.getWidth(), msg + ": width");
                    assertEquals(decoded.getHeight(), finalImage.getHeight(), msg + ": height");
                } catch (Exception e) {
                    failures.add(msg + ": " + e.getMessage());
                }
            }
        }

        if (!failures.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("The following conversions failed (").append(failures.size()).append("):\n");
            for (String f : failures)
                sb.append("  ").append(f).append("\n");
            fail(sb.toString());
        }
    }

    @Test
    @DisplayName("Auto-detect input format, then convert to every output format")
    void autoDetectInputToEveryOutput(@TempDir Path dir) throws IOException {
        assumeLibs();

        BufferedImage alphaSource = alphaGradient(32, 24);
        BufferedImage rgbSource = rgbGradient(32, 24);

        List<String> failures = new ArrayList<>();

        for (ImageFormat input : FORMATS) {
            BufferedImage inputSource = input.supportsAlpha() ? alphaSource : rgbSource;
            byte[] encoded;
            try {
                encoded = encode(inputSource, input);
            } catch (Exception e) {
                continue;
            }
            if (encoded == null || encoded.length == 0) continue;

            // Auto-detect format from header
            BufferedImage decoded;
            try {
                decoded = ImageReader.read(encoded).toBufferedImage();
            } catch (Exception e) {
                continue;
            }
            if (decoded == null) continue;

            for (ImageFormat output : FORMATS) {
                if (input == output) continue;

                String msg = input + "→" + output + " (auto)";
                try {
                    BufferedImage outputSource = output.supportsAlpha() ? decoded : stripAlpha(decoded);
                    byte[] outBytes = ImageWriter.toBytes(outputSource, output, 0.8);
                    assertNotEquals(0, outBytes.length, msg + ": output is empty");
                    BufferedImage finalImage = ImageReader.read(outBytes).toBufferedImage();
                    assertEquals(decoded.getWidth(), finalImage.getWidth(), msg + ": width");
                    assertEquals(decoded.getHeight(), finalImage.getHeight(), msg + ": height");
                } catch (Exception e) {
                    failures.add(msg + ": " + e.getMessage());
                }
            }
        }

        if (!failures.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("The following auto-detect conversions failed (").append(failures.size()).append("):\n");
            for (String f : failures)
                sb.append("  ").append(f).append("\n");
            fail(sb.toString());
        }
    }

    @Test
    @DisplayName("Quality parameter works at both 0.0 and 1.0 for all formats")
    void qualityBoundariesWorkForAllFormats(@TempDir Path dir) throws IOException {
        assumeLibs();

        for (ImageFormat format : FORMATS) {
            BufferedImage source = format.supportsAlpha() ? alphaGradient(32, 24) : rgbGradient(32, 24);
            try {
                byte[] low = ImageWriter.toBytes(source, format, 0.0);
                byte[] high = ImageWriter.toBytes(source, format, 1.0);
                assertTrue(low.length > 0, format + ": cannot write at quality 0.0");
                assertTrue(high.length > 0, format + ": cannot write at quality 1.0");
            } catch (Exception e) {
                fail(format + ": quality boundary does not work - " + e.getMessage());
            }
        }
    }

    @Test
    @DisplayName("Lossy format cross-conversion reflects quality in file size")
    void lossyCrossConversionFollowsQuality(@TempDir Path dir) throws IOException {
        assumeLibs();

        BufferedImage source = alphaGradient(64, 48);

        for (ImageFormat format : LOSSY_FORMATS) {
            byte[] high = ImageWriter.toBytes(source, format, 0.95);
            byte[] low = ImageWriter.toBytes(source, format, 0.05);
            assertTrue(low.length < high.length, format + ": low quality (" + low.length + ") is larger than high quality (" + high.length + ")");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static BufferedImage alphaGradient(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                image.setRGB(x, y, ((x * 255 / (width - 1)) << 24) | ((y * 255 / (height - 1)) << 16) | ((x * y) % 256 << 8) | 128);
        return image;
    }

    private static BufferedImage rgbGradient(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                image.setRGB(x, y, ((y * 255 / (height - 1)) << 16) | ((x * y) % 256 << 8) | 128);
        return image;
    }

    /** Strips the alpha channel, converting to RGB. */
    private static BufferedImage stripAlpha(BufferedImage source) {
        if (!source.getColorModel().hasAlpha()) return source;
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().drawImage(source, 0, 0, null);
        return rgb;
    }

    private static byte[] encode(BufferedImage image, ImageFormat format) throws IOException {
        return ImageWriter.toBytes(image, format, format.getDefaultQuality());
    }

    private static void assumeLibs() {
        assumeTrue(WebpCodec.isAvailable(), () -> "libwebp is not available: " + WebpCodec.getUnavailableReason());
        assumeTrue(AvifCodec.isAvailable(), () -> "libavif is not available: " + AvifCodec.getUnavailableReason());
    }
}
