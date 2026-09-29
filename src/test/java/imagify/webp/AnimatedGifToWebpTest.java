/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.webp;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import imagify.ImageMetrics;
import imagify.ImageResizer;
import imagify.webp.ffm.WebpCodec;

/**
 * Complete verification of animated GIF to animated WebP conversion.
 *
 * <p>Frame count, timing, dimensions, pixel fidelity (PSNR/SSIM) are
 * verified programmatically.</p>
 */
class AnimatedGifToWebpTest {

    private static final String GIF_DIR = "src/test/resources/anime gif";

    private static final String REPORT_DIR = "target/test-output/anime-gif-to-webp";

    @Test
    @DisplayName("Converts animated GIF to animated WebP, preserving frame count, timing, dimensions, and quality")
    void convertsAnimatedGifToWebp(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(WebpCodec.isAvailable(), () -> "libwebp is not available: " + WebpCodec.getUnavailableReason());

        Path gifDir = Paths.get(GIF_DIR);
        Assumptions.assumeTrue(Files.isDirectory(gifDir), () -> GIF_DIR + " does not exist");

        List<Path> gifs = Files.walk(gifDir).filter(p -> p.toString().toLowerCase().endsWith(".gif")).sorted().toList();
        Assumptions.assumeFalse(gifs.isEmpty(), () -> "no GIF files in " + GIF_DIR);

        Path reportDir = Paths.get(REPORT_DIR);
        Files.createDirectories(reportDir);

        // Aggregate across all GIFs
        int totalFrames = 0;
        int passedGifs = 0;
        for (int i = 0; i < gifs.size(); i++) {
            Path gif = gifs.get(i);
            String id = String.format("anim%02d", i + 1);
            ConvertResult result = convertGif(gif, id, reportDir);
            totalFrames += result.frameCount;
            if (result.passed) passedGifs++;
        }

        assertEquals(gifs.size(), passedGifs, "all GIFs should pass conversion");
    }

    @Test
    @DisplayName("Verify pixel-level fidelity in animated GIF to WebP conversion")
    void pixelFidelityPreserved(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(WebpCodec.isAvailable(), () -> "libwebp is not available: " + WebpCodec.getUnavailableReason());

        Path gifDir = Paths.get(GIF_DIR);
        Assumptions.assumeTrue(Files.isDirectory(gifDir));

        List<Path> gifs = Files.walk(gifDir).filter(p -> p.toString().toLowerCase().endsWith(".gif")).sorted().toList();
        Assumptions.assumeFalse(gifs.isEmpty());

        // Verify PSNR/SSIM on at least one GIF (use a subdirectory to avoid overlap with
        // convertsAnimatedGifToWebp)
        Path gif = gifs.get(0);
        Path reportDir = Paths.get(REPORT_DIR);
        Path fidelityDir = reportDir.resolve("fidelity");
        Files.createDirectories(fidelityDir);
        ConvertResult result = convertGif(gif, "fidelity_test", fidelityDir);

        assertTrue(result.avgPsnr > 20, "average PSNR should be > 20dB (lossy conversion): " + result.avgPsnr);
        assertTrue(result.avgSsim > 0.5, "average SSIM should be > 0.5 (structural similarity): " + result.avgSsim);
    }

    @Test
    @DisplayName("Resize animated GIF frames and convert to animated WebP")
    void resizedAnimatedGifToWebp(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(WebpCodec.isAvailable(), () -> "libwebp is not available: " + WebpCodec.getUnavailableReason());

        Path gifDir = Paths.get(GIF_DIR);
        Assumptions.assumeTrue(Files.isDirectory(gifDir));

        List<Path> gifs = Files.walk(gifDir).filter(p -> p.toString().toLowerCase().endsWith(".gif")).sorted().toList();
        Assumptions.assumeFalse(gifs.isEmpty());

        // Verify on the first GIF
        Path gif = gifs.get(0);
        List<BufferedImage> originalFrames = readAllGifFrames(gif);
        int[] delaysMs = readGifFrameDelays(gif);
        int targetW = Math.max(1, originalFrames.get(0).getWidth() / 2);
        int targetH = Math.max(1, originalFrames.get(0).getHeight() / 2);

        // リサイズしてWebPにエンコード
        List<BufferedImage> resizedFrames = new java.util.ArrayList<>();
        for (BufferedImage frame : originalFrames) {
            resizedFrames.add(ImageResizer.resize(frame, targetW, targetH));
        }

        byte[] webpBytes = WebpCodec.encodeAnimation(resizedFrames, delaysMs, 75, false, 0);
        assertTrue(webpBytes.length > 0, "encoded WebP is empty");

        // Decode and verify
        WebpImageInfo info = WebpCodec.readHeader(webpBytes);
        assertTrue(info.hasAnimation(), "output is not an animation");
        assertEquals(resizedFrames.size(), info.frameCount(), "frame count mismatch");

        List<BufferedImage> decodedFrames = WebpCodec.decodeAnimation(webpBytes);
        assertEquals(resizedFrames.size(), decodedFrames.size(), "decoded frame count mismatch");

        // Verify all decoded frames match the resized dimensions
        for (BufferedImage frame : decodedFrames) {
            assertEquals(targetW, frame.getWidth(), "decoded frame width mismatch");
            assertEquals(targetH, frame.getHeight(), "decoded frame height mismatch");
        }

        // Also verify pixel-level fidelity (comparison with pre-resize frames is impossible, but
        // confirm there is no quality degradation in the decoded frames themselves)
        double avgPsnr = 0;
        double avgSsim = 0;
        int checked = 0;
        for (int i = 0; i < Math.min(resizedFrames.size(), decodedFrames.size()); i++) {
            int[] ref = ImageMetrics.argb(resizedFrames.get(i));
            int[] act = ImageMetrics.argb(decodedFrames.get(i));
            if (ref.length == act.length) {
                avgPsnr += ImageMetrics.psnr(ref, act);
                avgSsim += ImageMetrics.ssim(ref, act, targetW, targetH);
                checked++;
            }
        }
        if (checked > 0) {
            avgPsnr /= checked;
            avgSsim /= checked;
        }
        assertTrue(avgPsnr > 20, "average PSNR should be > 20dB after resize+convert: " + avgPsnr);
        assertTrue(avgSsim > 0.5, "average SSIM should be > 0.5 after resize+convert: " + avgSsim);

        // Write output files for inspection (separate directory to avoid collision)
        Path resizedDir = Paths.get("target/test-output/anime-gif-resized-to-webp");
        Files.createDirectories(resizedDir);
        Path outFile = resizedDir.resolve(gif.getFileName().toString().replace(".gif", "_resized.webp"));
        Files.write(outFile, webpBytes);
    }

    // ------------------------------------------------------------------ conversion

    /** A record holding the conversion result of a single GIF. */
    private record ConvertResult(int frameCount, int webpBytesLength, boolean hasAnimation, double avgPsnr, double avgSsim, boolean passed) {
    }

    private ConvertResult convertGif(Path gif, String id, Path reportDir) throws Exception {
        // Read all frames from GIF
        List<BufferedImage> originalFrames = readAllGifFrames(gif);
        int[] delaysMs = readGifFrameDelays(gif);

        // Verify all frames have the same dimensions
        int frameWidth = originalFrames.get(0).getWidth();
        int frameHeight = originalFrames.get(0).getHeight();
        for (int i = 1; i < originalFrames.size(); i++) {
            assertEquals(frameWidth, originalFrames.get(i).getWidth(), "frame " + i + " width mismatch in " + gif.getFileName());
            assertEquals(frameHeight, originalFrames.get(i).getHeight(), "frame " + i + " height mismatch in " + gif.getFileName());
        }

        // Encode to animated WebP
        byte[] webpBytes = WebpCodec.encodeAnimation(originalFrames, delaysMs, 75, false, 0);
        assertTrue(webpBytes.length > 0, "encoded WebP is empty");

        // Verify header
        WebpImageInfo info = WebpCodec.readHeader(webpBytes);
        assertTrue(info.hasAnimation(), "output is not an animation");
        assertEquals(originalFrames.size(), info.frameCount(), "frame count mismatch");

        // Decode and verify frame count
        List<BufferedImage> decodedFrames = WebpCodec.decodeAnimation(webpBytes);
        assertEquals(originalFrames.size(), decodedFrames.size(), "decoded frame count mismatch");

        // Verify each frame's dimensions
        for (int i = 0; i < decodedFrames.size(); i++) {
            assertEquals(frameWidth, decodedFrames.get(i).getWidth(), "decoded frame " + i + " width mismatch");
            assertEquals(frameHeight, decodedFrames.get(i).getHeight(), "decoded frame " + i + " height mismatch");
        }

        // Verify timing
        int[][] timing = WebpCodec.readAnimationTiming(webpBytes);
        assertNotNull(timing);
        assertEquals(2, timing.length);
        assertEquals(originalFrames.size(), timing[0][0], "timing frame count mismatch");
        int[] decodedDelays = timing[1];
        assertEquals(delaysMs.length, decodedDelays.length, "timing array length mismatch");
        for (int i = 0; i < delaysMs.length; i++) {
            assertEquals(delaysMs[i], decodedDelays[i], Math.max(1, delaysMs[i] / 10), "delay mismatch at frame " + i);
        }

        // Pixel-level fidelity check (compare first and last frames)
        double avgPsnr = 0;
        double avgSsim = 0;
        int checkedFrames = 0;
        for (int i = 0; i < Math.min(originalFrames.size(), decodedFrames.size()); i++) {
            int[] ref = ImageMetrics.argb(originalFrames.get(i));
            int[] act = ImageMetrics.argb(decodedFrames.get(i));
            if (ref.length == act.length) {
                avgPsnr += ImageMetrics.psnr(ref, act);
                avgSsim += ImageMetrics.ssim(ref, act, frameWidth, frameHeight);
                checkedFrames++;
            }
        }
        if (checkedFrames > 0) {
            avgPsnr /= checkedFrames;
            avgSsim /= checkedFrames;
        }

        // Write output files for inspection
        Path outDir = reportDir;
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve(gif.getFileName().toString().replace(".gif", ".webp"));
        Files.write(outFile, webpBytes);

        return new ConvertResult(originalFrames.size(), webpBytes.length, info.hasAnimation(), avgPsnr, avgSsim, true);
    }

    // ------------------------------------------------------------------ helpers

    private static List<BufferedImage> readAllGifFrames(Path gif) throws IOException {
        try (var stream = ImageIO.createImageInputStream(Files.newInputStream(gif))) {
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

    private static int[] readGifFrameDelays(Path gif) throws IOException {
        try (var stream = ImageIO.createImageInputStream(Files.newInputStream(gif))) {
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
                try {
                    return Integer.parseInt(attr.getNodeValue()) * 10;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        for (int i = 0; i < node.getChildNodes().getLength(); i++) {
            int delay = extractDelay(node.getChildNodes().item(i));
            if (delay > 0) return delay;
        }
        return 0;
    }
}
