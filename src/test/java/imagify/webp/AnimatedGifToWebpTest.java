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

/**
 * Tests for animated GIF to animated WebP conversion.
 *
 * <p>Reads animated GIF files from {@code src/test/resources/anime gif}, converts them to
 * animated WebP using {@link WebpCodec#encodeAnimation}, and verifies the output can be
 * decoded back correctly with frame count and timing preserved.
 */
class AnimatedGifToWebpTest {

    private static final String GIF_DIR = "src/test/resources/anime gif";

    private static final String REPORT_DIR = "target/test-output/anime-gif-to-webp";

    @Test
    @DisplayName("アニメGIFをアニメWebPに変換し、フレーム数とタイミングを保持する")
    void convertsAnimatedGifToWebp() throws Exception {
        Assumptions.assumeTrue(WebpCodec.isAvailable(),
                () -> "libwebp is not available: " + WebpCodec.getUnavailableReason());

        Path gifDir = Paths.get(GIF_DIR);
        Assumptions.assumeTrue(Files.isDirectory(gifDir), () -> GIF_DIR + " does not exist");

        List<Path> gifs = Files.walk(gifDir)
                .filter(p -> p.toString().toLowerCase().endsWith(".gif"))
                .sorted()
                .toList();
        Assumptions.assumeFalse(gifs.isEmpty(), () -> "no GIF files in " + GIF_DIR);

        Path reportDir = Paths.get(REPORT_DIR);
        Files.createDirectories(reportDir);

        for (int i = 0; i < gifs.size(); i++) {
            Path gif = gifs.get(i);
            String id = String.format("anim%02d", i + 1);
            convertGif(gif, id, reportDir);
        }

        System.out.println("Animated GIF → WebP conversion test completed");
    }

    private static void convertGif(Path gif, String id, Path reportDir) throws IOException, WebpException {
        // Read the GIF using ImageIO
        BufferedImage firstFrame = ImageIO.read(gif.toFile());
        assertNotNull(firstFrame, "cannot read " + gif);

        // Read all frames from GIF
        List<BufferedImage> frames = readAllGifFrames(gif);
        int[] delaysMs = readGifFrameDelays(gif);

        assertFalse(frames.isEmpty(), "no frames in " + gif);
        assertEquals(frames.size(), delaysMs.length, "delay count mismatch for " + gif);

        // Encode to animated WebP (lossy, quality 75, loop forever)
        int quality = 75;
        boolean lossless = false;
        int loopCount = 0; // 0 = forever

        long start = System.nanoTime();
        byte[] webpBytes = WebpCodec.encodeAnimation(frames, delaysMs, quality, lossless, 0);
        long encodeNanos = System.nanoTime() - start;

        assertTrue(webpBytes.length > 0, "encoded WebP is empty for " + gif.getFileName());

        // Verify the WebP file
        WebpImageInfo info = WebpCodec.readHeader(webpBytes);
        assertTrue(info.hasAnimation(), "output is not an animation");
        assertEquals(frames.size(), info.frameCount(), "frame count mismatch for " + gif.getFileName());

        // Decode animation and verify frame count and dimensions
        start = System.nanoTime();
        List<BufferedImage> decodedFrames = WebpCodec.decodeAnimation(webpBytes);
        long decodeNanos = System.nanoTime() - start;

        assertEquals(frames.size(), decodedFrames.size(), "decoded frame count mismatch");

        // Verify timing
        int[][] timing = WebpCodec.readAnimationTiming(webpBytes);
        assertNotNull(timing);
        assertEquals(2, timing.length);
        assertEquals(frames.size(), timing[0][0]);
        int[] decodedDelays = timing[1];
        assertEquals(delaysMs.length, decodedDelays.length);
        for (int i = 0; i < delaysMs.length; i++) {
            // Allow small timing differences due to WebP encoding
            assertEquals(delaysMs[i], decodedDelays[i], Math.max(1, delaysMs[i] / 10),
                    "delay mismatch at frame " + i);
        }

        // Write the WebP file for inspection
        Path outDir = Paths.get("target/test-output/anime-gif-to-webp");
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve(gif.getFileName().toString().replace(".gif", ".webp"));
        Files.write(outFile, webpBytes);

        // Also write first frame for visual comparison
        Path firstFrameOut = outDir.resolve("frame0_" + gif.getFileName().toString().replace(".gif", ".png"));
        ImageIO.write(frames.get(0), "png", firstFrameOut.toFile());

        System.out.printf("  %s: %d frames, %d bytes, encode %.2f ms, decode %.2f ms%n",
                gif.getFileName(), frames.size(), webpBytes.length,
                encodeNanos / 1_000_000.0, decodeNanos / 1_000_000.0);
    }

    /**
     * Reads all frames from an animated GIF using ImageIO.
     */
    private static List<BufferedImage> readAllGifFrames(Path gif) throws IOException {
        try (var stream = ImageIO.createImageInputStream(Files.newInputStream(gif))) {
            var reader = ImageIO.getImageReadersByFormatName("gif").next();
            reader.setInput(stream, false, true);

            int numFrames = reader.getNumImages(true);
            var frames = new java.util.ArrayList<BufferedImage>(numFrames);

            for (int i = 0; i < numFrames; i++) {
                BufferedImage frame = reader.read(i);
                frames.add(frame);
            }
            return frames;
        }
    }

    /**
     * Reads frame delays from an animated GIF.
     * Returns delays in milliseconds.
     */
    private static int[] readGifFrameDelays(Path gif) throws IOException {
        try (var stream = ImageIO.createImageInputStream(Files.newInputStream(gif))) {
            var reader = ImageIO.getImageReadersByFormatName("gif").next();
            reader.setInput(stream, false, true);

            int numFrames = reader.getNumImages(true);
            int[] delays = new int[numFrames];

            for (int i = 0; i < numFrames; i++) {
                var param = reader.getDefaultReadParam();
                var metadata = reader.getImageMetadata(i);
                delays[i] = readGifDelay(metadata);
            }
            return delays;
        }
    }

    /**
     * Extracts frame delay from GIF metadata.
     * Returns delay in milliseconds (default 100ms if not found).
     */
    private static int readGifDelay(javax.imageio.metadata.IIOMetadata metadata) {
        if (metadata == null) return 100;
        String[] names = metadata.getMetadataFormatNames();
        for (String name : names) {
            var node = metadata.getAsTree(name);
            int delay = extractDelayFromNode(node);
            if (delay > 0) return delay;
        }
        return 100; // default 100ms (10 centiseconds)
    }

    private static int extractDelayFromNode(org.w3c.dom.Node node) {
        if (node == null) return 0;
        if ("GraphicControlExtension".equals(node.getNodeName())) {
            var attr = node.getAttributes().getNamedItem("delayTime");
            if (attr != null) {
                try {
                    // delayTime is in hundredths of a second (centiseconds)
                    return Integer.parseInt(attr.getNodeValue()) * 10;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        // Recurse into children
        var children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            int delay = extractDelayFromNode(children.item(i));
            if (delay > 0) return delay;
        }
        return 0;
    }
}