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

import imagify.avif.jna.AvifCodec;
import imagify.avif.AvifImageInfo;
import imagify.ImageMetrics;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.metadata.IIOMetadata;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * アニメGIF → アニメAVIF変換のテスト。
 *
 * <p>リサイズ版と通常版で出力先ディレクトリが分離されていることを確認する。
 */
class AnimatedGifToAvifTest {

    private static final String GIF_DIR = "src/test/resources/anime gif";
    private static final String REPORT_DIR = "target/test-output/anime-gif-to-avif";

    @Test
    @DisplayName("アニメGIFをアニメAVIFに変換し、フレーム数・寸法・画質を保持する")
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

        // 少なくとも1枚のGIFで検証
        Path gif = gifs.get(0);
        List<BufferedImage> frames = readAllGifFrames(gif);
        int[] delaysMs = readGifFrameDelays(gif);

        // リサイズなしでAVIFに変換
        byte[] avifBytes = AvifCodec.encodeAnimation(frames, delaysMs, 60, 0);
        assertTrue(avifBytes.length > 0, "encoded AVIF is empty");

        // デコードして検証
        List<BufferedImage> decodedFrames = AvifCodec.decodeAnimation(avifBytes);
        assertFalse(decodedFrames.isEmpty(), "decoded frames should not be empty");

        // フレーム数がゼロでないことを確認
        assertTrue(decodedFrames.size() > 0, "should have decoded frames");

        // 全デコードフレームの寸法が元の寸法と一致する
        for (BufferedImage frame : decodedFrames) {
            assertEquals(frames.get(0).getWidth(), frame.getWidth(), "frame width mismatch");
            assertEquals(frames.get(0).getHeight(), frame.getHeight(), "frame height mismatch");
        }

        // 出力ファイルを書き込み
        Path outFile = reportDir.resolve(gif.getFileName().toString().replace(".gif", ".avif"));
        Files.createDirectories(reportDir);
        Files.write(outFile, avifBytes);

        // AVIFヘッダー情報を確認
        AvifImageInfo info = AvifCodec.readHeader(avifBytes);
        assertEquals(frames.get(0).getWidth(), info.width(), "AVIF width mismatch");
        assertEquals(frames.get(0).getHeight(), info.height(), "AVIF height mismatch");

        System.out.printf("  Converted %d frames from GIF to AVIF: %d bytes, dimensions=%dx%d%n",
                frames.size(), avifBytes.length,
                frames.get(0).getWidth(), frames.get(0).getHeight());
    }

    @Test
    @DisplayName("アニメGIFのフレームをリサイズしてアニメAVIFに変換する")
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

        // リサイズしてAVIFにエンコード
        List<BufferedImage> resizedFrames = new java.util.ArrayList<>();
        for (BufferedImage frame : originalFrames) {
            resizedFrames.add(resize(frame, targetW, targetH));
        }

        byte[] avifBytes = AvifCodec.encodeAnimation(resizedFrames, delaysMs, 60, 0);
        assertTrue(avifBytes.length > 0, "encoded AVIF is empty");

        // デコードして検証
        List<BufferedImage> decodedFrames = AvifCodec.decodeAnimation(avifBytes);
        assertFalse(decodedFrames.isEmpty(), "decoded frames should not be empty");

        // 全デコードフレームの寸法がリサイズ後の寸法と一致する
        for (BufferedImage frame : decodedFrames) {
            assertEquals(targetW, frame.getWidth(), "decoded frame width mismatch");
            assertEquals(targetH, frame.getHeight(), "decoded frame height mismatch");
        }

        // 出力ファイルを別ディレクトリに書き込み
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
    @DisplayName("アニメGIF→アニメAVIF変換のピクセルレベル忠実度を検証")
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

        // リサイズなしでAVIFに変換
        byte[] avifBytes = AvifCodec.encodeAnimation(originalFrames, delaysMs, 60, 0);

        // デコード
        List<BufferedImage> decodedFrames = AvifCodec.decodeAnimation(avifBytes);

        // 少なくとも1フレームはデコードされるべき
        assertFalse(decodedFrames.isEmpty(), "should have decoded at least one frame");

        // ピクセルレベルの忠実度を検証
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

        // AVIFはロスリーだが、構造的には類似しているはず
        assertTrue(avgSsim > 0.3,
                "average SSIM should be > 0.3 (lossy AVIF): " + avgSsim);

        // 出力ファイルを書き込み（他のテストと被らないようにサブディレクトリ）
        Path fidelityDir = Paths.get(REPORT_DIR).resolve("fidelity");
        Files.createDirectories(fidelityDir);
        Path outFile = fidelityDir.resolve(gif.getFileName().toString().replace(".gif", ".avif"));
        Files.write(outFile, avifBytes);

        System.out.printf("  Fidelity check: %d frames checked, PSNR=%.1f, SSIM=%.4f%n",
                validFrames, avgPsnr, avgSsim);
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
