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
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import imagify.ImageMetrics;
import imagify.avif.ffm.AvifCodec;
import imagify.avif.ffm.AvifConstants;

/**
 * Test for animated GIF to animated AVIF conversion.
 *
 * <p>Verifies that resized and normal versions have separate output directories.
 */
class AnimatedGifToAvifTest {

    private static final String GIF_DIR = "src/test/resources/anime gif";
    private static final String REPORT_DIR = "target/test-output/anime-gif-to-avif";

    @Test
    @DisplayName("Converts animated GIF to animated AVIF, preserving frame count, dimensions, and quality")
    void convertsAnimatedGifToAvif(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(AvifCodec.isAvailable(),
                () -> "libavif is not available");

        Path gifDir = Paths.get(GIF_DIR);
        Assumptions.assumeTrue(Files.isDirectory(gifDir), () -> GIF_DIR + " does not exist");

        List<Path> gifs = Files.walk(gifDir)
                .filter(p -> p.toString().toLowerCase().endsWith(".gif"))
                .sorted()
                .toList();
        Assumptions.assumeFalse(gifs.isEmpty(), () -> "no GIF files in " + GIF_DIR);

        Path reportDir = Paths.get(REPORT_DIR);
        Files.createDirectories(reportDir);

        // Verify on at least one GIF
        Path gif = gifs.get(0);
        List<BufferedImage> frames = readAllGifFrames(gif);
        int[] delaysMs = readGifFrameDelays(gif);

        // Convert to AVIF without resizing
        byte[] avifBytes = AvifCodec.encodeAnimation(frames, delaysMs, 60, 0);
        assertTrue(avifBytes.length > 0, "encoded AVIF is empty");

        // Decode and verify
        List<BufferedImage> decodedFrames = AvifCodec.decodeAnimation(avifBytes);
        assertFalse(decodedFrames.isEmpty(), "decoded frames should not be empty");

        // Confirm frame count is not zero
        assertTrue(decodedFrames.size() > 0, "should have decoded frames");

        // Verify all decoded frames match original dimensions
        for (BufferedImage frame : decodedFrames) {
            assertEquals(frames.get(0).getWidth(), frame.getWidth(), "frame width mismatch");
            assertEquals(frames.get(0).getHeight(), frame.getHeight(), "frame height mismatch");
        }

        // Write output file
        Path outFile = reportDir.resolve(gif.getFileName().toString().replace(".gif", ".avif"));
        Files.createDirectories(reportDir);
        Files.write(outFile, avifBytes);

        // Verify AVIF header info
        AvifImageInfo info = AvifCodec.readHeader(avifBytes);
        assertEquals(frames.get(0).getWidth(), info.width(), "AVIF width mismatch");
        assertEquals(frames.get(0).getHeight(), info.height(), "AVIF height mismatch");

        System.out.printf("  Converted %d frames from GIF to AVIF: %d bytes, dimensions=%dx%d%n",
                frames.size(), avifBytes.length,
                frames.get(0).getWidth(), frames.get(0).getHeight());
    }

    @Test
    @DisplayName("Resize animated GIF frames and convert to animated AVIF")
    void resizedAnimatedGifToAvif(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(AvifCodec.isAvailable(),
                () -> "libavif is not available");

        Path gifDir = Paths.get(GIF_DIR);
        Assumptions.assumeTrue(Files.isDirectory(gifDir), () -> GIF_DIR + " does not exist");

        List<Path> gifs = Files.walk(gifDir)
                .filter(p -> p.toString().toLowerCase().endsWith(".gif"))
                .sorted()
                .toList();
        Assumptions.assumeFalse(gifs.isEmpty(), () -> "no GIF files in " + GIF_DIR);

        Path gif = gifs.get(0);
        List<BufferedImage> originalFrames = readAllGifFrames(gif);
        int[] delaysMs = readGifFrameDelays(gif);

        int targetW = Math.max(1, originalFrames.get(0).getWidth() / 2);
        int targetH = Math.max(1, originalFrames.get(0).getHeight() / 2);

        // Resize and encode to AVIF
        List<BufferedImage> resizedFrames = new java.util.ArrayList<>();
        for (BufferedImage frame : originalFrames) {
            resizedFrames.add(resize(frame, targetW, targetH));
        }

        byte[] avifBytes = AvifCodec.encodeAnimation(resizedFrames, delaysMs, 60, 0);
        assertTrue(avifBytes.length > 0, "encoded AVIF is empty");

        // Decode and verify
        List<BufferedImage> decodedFrames = AvifCodec.decodeAnimation(avifBytes);
        assertFalse(decodedFrames.isEmpty(), "decoded frames should not be empty");

        // Verify all decoded frames match resized dimensions
        for (BufferedImage frame : decodedFrames) {
            assertEquals(targetW, frame.getWidth(), "decoded frame width mismatch");
            assertEquals(targetH, frame.getHeight(), "decoded frame height mismatch");
        }

        // Write output file to a separate directory
        Path resizedDir = Paths.get("target/test-output/anime-gif-resized-to-avif");
        Files.createDirectories(resizedDir);
        Path outFile = resizedDir.resolve(gif.getFileName().toString().replace(".gif", "_resized.avif"));
        Files.write(outFile, avifBytes);

        System.out.printf("  Resized %d frames from %dx%d to %dx%d: %d bytes%n",
                resizedFrames.size(),
                originalFrames.get(0).getWidth(), originalFrames.get(0).getHeight(),
                targetW, targetH, avifBytes.length);
    }

    @Test
    @DisplayName("Verify pixel-level fidelity in animated GIF to AVIF conversion")
    void pixelFidelityPreserved(@TempDir Path dir) throws Exception {
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

        // Convert to AVIF without resizing
        byte[] avifBytes = AvifCodec.encodeAnimation(originalFrames, delaysMs, 60, 0);

        // Decode
        List<BufferedImage> decodedFrames = AvifCodec.decodeAnimation(avifBytes);

        // At least one frame should be decoded
        assertFalse(decodedFrames.isEmpty(), "should have decoded at least one frame");

        // Verify pixel-level fidelity
        int checked = Math.min(originalFrames.size(), decodedFrames.size());
        double avgPsnr = 0;
        double avgSsim = 0;
        int validFrames = 0;
        for (int i = 0; i < checked; i++) {
            int[] ref = ImageMetrics.argb(originalFrames.get(i));
            int[] act = ImageMetrics.argb(decodedFrames.get(i));
            if (ref.length == act.length) {
                avgPsnr += ImageMetrics.psnr(ref, act);
                avgSsim += ImageMetrics.ssim(ref, act, originalFrames.get(i).getWidth(), originalFrames.get(i).getHeight());
                validFrames++;
            }
        }
        if (validFrames > 0) {
            avgPsnr /= validFrames;
            avgSsim /= validFrames;
        }

        // AVIF is lossy but should be structurally similar
        assertTrue(avgSsim > 0.3,
                "average SSIM should be > 0.3 (lossy AVIF): " + avgSsim);

        // Write output file (to a subdirectory to avoid collision with other tests)
        Path fidelityDir = Paths.get(REPORT_DIR).resolve("fidelity");
        Files.createDirectories(fidelityDir);
        Path outFile = fidelityDir.resolve(gif.getFileName().toString().replace(".gif", ".avif"));
        Files.write(outFile, avifBytes);

        System.out.printf("  Fidelity check: %d frames checked, PSNR=%.1f, SSIM=%.4f%n",
                validFrames, avgPsnr, avgSsim);
    }

    @Test
    @DisplayName("Animated AVIF subsamples chroma to 4:2:0")
    void animationSubsamplesChroma() throws Exception {
        Assumptions.assumeTrue(AvifCodec.isAvailable(), () -> "libavif is not available");

        Path gifDir = Paths.get(GIF_DIR);
        Assumptions.assumeTrue(Files.isDirectory(gifDir), () -> GIF_DIR + " does not exist");
        Path gif = Files.list(gifDir)
                .filter(p -> p.toString().toLowerCase().endsWith(".gif"))
                .sorted()
                .findFirst()
                .orElse(null);
        Assumptions.assumeTrue(gif != null, () -> "no GIF files in " + GIF_DIR);

        byte[] avifBytes = AvifCodec.encodeAnimation(
                readAllGifFrames(gif), readGifFrameDelays(gif), 60, 0);

        // The animation encoder deliberately writes 4:2:0, where the still encoder writes 4:4:4.
        // 4:2:0 is what makes the encode affordable: it hands AV1 one full plane and two quarter
        // planes per frame instead of three full ones, worth about 1.7x on the encode and 2.2x on
        // the file at the same quality. This test pins the choice down so it cannot regress
        // silently; AvifImageIOTest covers the still side staying at 4:4:4.
        assertEquals(AvifConstants.PIXEL_FORMAT_YUV420,
                AvifCodec.readHeader(avifBytes).yuvFormat(),
                "the animation encoder should subsample chroma to 4:2:0");
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

    private static int readGifDelay(IIOMetadata metadata) {
        if (metadata == null) return 1000;
        for (String name : metadata.getMetadataFormatNames()) {
            var node = metadata.getAsTree(name);
            int delay = extractDelay(node);
            if (delay > 0) return delay * 10;  // GIF delayTime is centiseconds → milliseconds
        }
        return 1000;
    }

    private static int extractDelay(org.w3c.dom.Node node) {
        if (node == null) return 0;
        if ("GraphicControlExtension".equals(node.getNodeName())) {
            var attr = node.getAttributes().getNamedItem("delayTime");
            if (attr != null) {
                return Integer.parseInt(attr.getNodeValue());  // centiseconds
            }
        }
        for (int i = 0; i < node.getChildNodes().getLength(); i++) {
            int delay = extractDelay(node.getChildNodes().item(i));
            if (delay > 0) return delay;
        }
        return 0;
    }

    private static BufferedImage resize(BufferedImage source, int targetW, int targetH) {
        BufferedImage result = new BufferedImage(targetW, targetH, BufferedImage.TYPE_4BYTE_ABGR);
        java.awt.Graphics2D g = result.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, targetW, targetH, null);
        g.dispose();
        return result;
    }
}
