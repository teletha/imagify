/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.avif;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import imagify.avif.ffm.AvifCodec;
import imagify.webp.ffm.WebpCodec;

/**
 * Test for mutual conversion between animated WebP and animated AVIF.
 *
 * <p>Uses animated GIF as an intermediate source,
 * and verifies conversion from WebP→AVIF and AVIF→WebP.
 */
class AnimatedWebpAvifConversionTest {

    private static final String GIF_DIR = "src/test/resources/anime gif";
    private static final String REPORT_DIR = "target/test-output/webp-avif-conversion";

    @Test
    @DisplayName("Converts animated WebP to animated AVIF, preserving frames and dimensions")
    void webpToAvif(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(WebpCodec.isAvailable(),
                () -> "libwebp is not available: " + WebpCodec.getUnavailableReason());
        Assumptions.assumeTrue(AvifCodec.isAvailable(),
                () -> "libavif is not available");

        Path gifDir = Paths.get(GIF_DIR);
        Assumptions.assumeTrue(Files.isDirectory(gifDir));

        List<Path> gifs = Files.walk(gifDir)
                .filter(p -> p.toString().toLowerCase().endsWith(".gif"))
                .sorted()
                .toList();
        Assumptions.assumeFalse(gifs.isEmpty());

        Path gif = gifs.get(0);
        List<BufferedImage> frames = readAllGifFrames(gif);
        int[] delaysMs = readGifFrameDelays(gif);

        // Animate GIF → animated WebP
        byte[] webpBytes = WebpCodec.encodeAnimation(frames, delaysMs, 75, false, 0);
        assertTrue(webpBytes.length > 0, "encoded WebP is empty");

        // WebP → decode → AVIF
        List<BufferedImage> webpFrames = WebpCodec.decodeAnimation(webpBytes);
        assertFalse(webpFrames.isEmpty(), "decoded WebP frames should not be empty");

        byte[] avifBytes = AvifCodec.encodeAnimation(webpFrames, delaysMs, 60, 0);
        assertTrue(avifBytes.length > 0, "encoded AVIF is empty");

        // Decode AVIF and verify
        List<BufferedImage> avifFrames = AvifCodec.decodeAnimation(avifBytes);
        assertFalse(avifFrames.isEmpty(), "decoded AVIF frames should not be empty");

        // Verify frame count and dimensions
        assertEquals(webpFrames.size(), avifFrames.size(), "frame count mismatch");
        for (BufferedImage frame : avifFrames) {
            assertEquals(frames.get(0).getWidth(), frame.getWidth(), "frame width mismatch");
            assertEquals(frames.get(0).getHeight(), frame.getHeight(), "frame height mismatch");
        }

        // Output
        Path reportDir = Paths.get(REPORT_DIR).resolve("webp-to-avif");
        Files.createDirectories(reportDir);
        Path outFile = reportDir.resolve(gif.getFileName().toString().replace(".gif", ".webp-to-avif.avif"));
        Files.write(outFile, avifBytes);
    }

    @Test
    @DisplayName("Converts animated AVIF to animated WebP, preserving frames and dimensions")
    void avifToWebp(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(WebpCodec.isAvailable(),
                () -> "libwebp is not available: " + WebpCodec.getUnavailableReason());
        Assumptions.assumeTrue(AvifCodec.isAvailable(),
                () -> "libavif is not available");

        Path gifDir = Paths.get(GIF_DIR);
        Assumptions.assumeTrue(Files.isDirectory(gifDir));

        List<Path> gifs = Files.walk(gifDir)
                .filter(p -> p.toString().toLowerCase().endsWith(".gif"))
                .sorted()
                .toList();
        Assumptions.assumeFalse(gifs.isEmpty());

        Path gif = gifs.get(0);
        List<BufferedImage> frames = readAllGifFrames(gif);
        int[] delaysMs = readGifFrameDelays(gif);

        // Animate GIF → animated AVIF
        byte[] avifBytes = AvifCodec.encodeAnimation(frames, delaysMs, 60, 0);
        assertTrue(avifBytes.length > 0, "encoded AVIF is empty");

        // AVIF → decode → WebP
        List<BufferedImage> avifFrames = AvifCodec.decodeAnimation(avifBytes);
        assertFalse(avifFrames.isEmpty(), "decoded AVIF frames should not be empty");

        byte[] webpBytes = WebpCodec.encodeAnimation(avifFrames, delaysMs, 75, false, 0);
        assertTrue(webpBytes.length > 0, "encoded WebP is empty");

        // Decode WebP and verify
        List<BufferedImage> webpFrames = WebpCodec.decodeAnimation(webpBytes);
        assertFalse(webpFrames.isEmpty(), "decoded WebP frames should not be empty");

        // Verify frame count and dimensions
        assertEquals(frames.size(), webpFrames.size(), "frame count mismatch");
        for (BufferedImage frame : webpFrames) {
            assertEquals(frames.get(0).getWidth(), frame.getWidth(), "frame width mismatch");
            assertEquals(frames.get(0).getHeight(), frame.getHeight(), "frame height mismatch");
        }

        // Output
        Path reportDir = Paths.get(REPORT_DIR).resolve("avif-to-webp");
        Files.createDirectories(reportDir);
        Path outFile = reportDir.resolve(gif.getFileName().toString().replace(".gif", ".avif-to-webp.webp"));
        Files.write(outFile, webpBytes);
    }

    @Test
    @DisplayName("Animated WebP→AVIF→WebP round-trip preserves frames")
    void roundTripWebpAvifWebp(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(WebpCodec.isAvailable(),
                () -> "libwebp is not available: " + WebpCodec.getUnavailableReason());
        Assumptions.assumeTrue(AvifCodec.isAvailable(),
                () -> "libavif is not available");

        Path gifDir = Paths.get(GIF_DIR);
        Assumptions.assumeTrue(Files.isDirectory(gifDir));

        List<Path> gifs = Files.walk(gifDir)
                .filter(p -> p.toString().toLowerCase().endsWith(".gif"))
                .sorted()
                .toList();
        Assumptions.assumeFalse(gifs.isEmpty());

        Path gif = gifs.get(0);
        List<BufferedImage> originalFrames = readAllGifFrames(gif);
        int[] delaysMs = readGifFrameDelays(gif);

        // Original GIF frames → WebP → AVIF → WebP
        byte[] webp1 = WebpCodec.encodeAnimation(originalFrames, delaysMs, 75, false, 0);
        List<BufferedImage> webpFrames = WebpCodec.decodeAnimation(webp1);
        byte[] avifBytes = AvifCodec.encodeAnimation(webpFrames, delaysMs, 60, 0);
        List<BufferedImage> avifFrames = AvifCodec.decodeAnimation(avifBytes);
        byte[] webp2 = WebpCodec.encodeAnimation(avifFrames, delaysMs, 75, false, 0);
        List<BufferedImage> webpResult = WebpCodec.decodeAnimation(webp2);

        // Final WebP frame count matches original
        assertEquals(originalFrames.size(), webpResult.size(),
                "round-trip frame count should match original");

        // All frames match dimensions
        for (BufferedImage frame : webpResult) {
            assertEquals(originalFrames.get(0).getWidth(), frame.getWidth(), "frame width mismatch");
            assertEquals(originalFrames.get(0).getHeight(), frame.getHeight(), "frame height mismatch");
        }

        // Output
        Path reportDir = Paths.get(REPORT_DIR).resolve("roundtrip");
        Files.createDirectories(reportDir);
        Path outFile = reportDir.resolve(gif.getFileName().toString().replace(".gif", ".roundtrip.webp"));
        Files.write(outFile, webp2);
    }

    @Test
    @DisplayName("Animated AVIF→WebP→AVIF round-trip preserves frames")
    void roundTripAvifWebpAvif(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(WebpCodec.isAvailable(),
                () -> "libwebp is not available: " + WebpCodec.getUnavailableReason());
        Assumptions.assumeTrue(AvifCodec.isAvailable(),
                () -> "libavif is not available");

        Path gifDir = Paths.get(GIF_DIR);
        Assumptions.assumeTrue(Files.isDirectory(gifDir));

        List<Path> gifs = Files.walk(gifDir)
                .filter(p -> p.toString().toLowerCase().endsWith(".gif"))
                .sorted()
                .toList();
        Assumptions.assumeFalse(gifs.isEmpty());

        Path gif = gifs.get(0);
        List<BufferedImage> originalFrames = readAllGifFrames(gif);
        int[] delaysMs = readGifFrameDelays(gif);

        // Original GIF frames → AVIF → WebP → AVIF
        byte[] avif1 = AvifCodec.encodeAnimation(originalFrames, delaysMs, 60, 0);
        List<BufferedImage> avifFrames = AvifCodec.decodeAnimation(avif1);
        byte[] webpBytes = WebpCodec.encodeAnimation(avifFrames, delaysMs, 75, false, 0);
        List<BufferedImage> webpFrames = WebpCodec.decodeAnimation(webpBytes);
        byte[] avif2 = AvifCodec.encodeAnimation(webpFrames, delaysMs, 60, 0);
        List<BufferedImage> avifResult = AvifCodec.decodeAnimation(avif2);

        // Final AVIF frame count matches original
        assertEquals(originalFrames.size(), avifResult.size(),
                "round-trip frame count should match original");

        // All frames match dimensions
        for (BufferedImage frame : avifResult) {
            assertEquals(originalFrames.get(0).getWidth(), frame.getWidth(), "frame width mismatch");
            assertEquals(originalFrames.get(0).getHeight(), frame.getHeight(), "frame height mismatch");
        }

        // Output
        Path reportDir = Paths.get(REPORT_DIR).resolve("roundtrip");
        Files.createDirectories(reportDir);
        Path outFile = reportDir.resolve(gif.getFileName().toString().replace(".gif", ".roundtrip.avif"));
        Files.write(outFile, avif2);
    }

    // ------------------------------------------------------------------ helpers

    private static List<BufferedImage> readAllGifFrames(Path gif) throws Exception {
        try (ImageInputStream stream = ImageIO.createImageInputStream(Files.newInputStream(gif))) {
            var reader = ImageIO.getImageReadersByFormatName("gif").next();
            reader.setInput(stream, false, true);
            int numFrames = reader.getNumImages(true);
            var frames = new java.util.ArrayList<BufferedImage>(numFrames);
            for (int i = 0; i < numFrames; i++) {
                frames.add(reader.read(i));
            }
            return frames;
        }
    }

    private static int[] readGifFrameDelays(Path gif) throws Exception {
        try (ImageInputStream stream = ImageIO.createImageInputStream(Files.newInputStream(gif))) {
            var reader = ImageIO.getImageReadersByFormatName("gif").next();
            reader.setInput(stream, false, true);
            int numFrames = reader.getNumImages(true);
            int[] delays = new int[numFrames];
            for (int i = 0; i < numFrames; i++) {
                delays[i] = readGifDelay(reader.getImageMetadata(i));
            }
            return delays;
        }
    }

    private static int readGifDelay(javax.imageio.metadata.IIOMetadata metadata) {
        if (metadata == null) return 100;
        for (String name : metadata.getMetadataFormatNames()) {
            var node = metadata.getAsTree(name);
            int delay = extractDelay(node);
            if (delay > 0) return delay;
        }
        return 100;
    }

    private static int extractDelay(org.w3c.dom.Node node) {
        if (node == null) return 0;
        if ("GraphicControlExtension".equals(node.getNodeName())) {
            var attr = node.getAttributes().getNamedItem("delayTime");
            if (attr != null) {
                return Integer.parseInt(attr.getNodeValue());
            }
        }
        for (int i = 0; i < node.getChildNodes().getLength(); i++) {
            int delay = extractDelay(node.getChildNodes().item(i));
            if (delay > 0) return delay;
        }
        return 0;
    }
}
