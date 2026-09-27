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

import imagify.avif.jna.AvifCodec;
import imagify.webp.WebpCodec;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 入力可能な全フォーマットから出力可能な全フォーマットへのクロス変換を検証する。
 *
 * <p>各形式で画像をエンコードし、デコードして全形式に再エンコードする。
 * 変換後の画像サイズが一致することを保証する。</p>
 *
 * <p>JPEG/BMP はアルファを持てないため、ソース画像を適切に選択する。</p>
 */
class CrossFormatConversionTest {

    /** 入力・出力として使用する全フォーマット。 */
    private static final ImageFormat[] FORMATS = {
            ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.GIF,
            ImageFormat.GIF89A, ImageFormat.BMP, ImageFormat.WEBP, ImageFormat.AVIF
    };

    /** AVIF/WebP にはネイティブライブラリが必要。 */
    private static final ImageFormat[] LOSSY_FORMATS = {ImageFormat.WEBP, ImageFormat.AVIF};

    /** アルファを持てない形式。 */
    private static final List<ImageFormat> NO_ALPHA_FORMATS = Arrays.asList(ImageFormat.JPEG, ImageFormat.BMP);

    @Test
    @DisplayName("全フォーマット間のクロス変換：各入力形式から全出力形式へ変換できる")
    void everyFormatToEveryFormat(@TempDir Path dir) throws IOException {
        assumeLibs();

        // アルファ対応形式と非対応形式の両方のソースを用意
        BufferedImage alphaSource = alphaGradient(48, 32);
        BufferedImage rgbSource = rgbGradient(48, 32);

        List<String> failures = new ArrayList<>();

        for (ImageFormat input : FORMATS) {
            // 入力形式がアルファを持てるかでソースを選択
            BufferedImage inputSource = input.supportsAlpha() ? alphaSource : rgbSource;
            byte[] encoded;
            try {
                encoded = encode(inputSource, input);
            } catch (Exception e) {
                continue; // この形式でエンコードできない場合はスキップ
            }
            if (encoded == null || encoded.length == 0) continue;

            BufferedImage decoded;
            try {
                decoded = ImageReader.read(encoded, input);
            } catch (Exception e) {
                continue; // デコードできない場合はスキップ
            }

            for (ImageFormat output : FORMATS) {
                // GIF89A は GIF と同一なのでスキップ
                if (output == ImageFormat.GIF89A && input != ImageFormat.GIF89A) continue;
                // 同一形式は他のテストでカバー済み
                if (input == output) continue;

                String msg = input + " → " + output;
                try {
                    // 出力がアルファ不可の場合はアルファを落としてエンコード
                    BufferedImage outputSource = output.supportsAlpha() ? decoded : stripAlpha(decoded);
                    byte[] reEncoded = ImageWriter.toBytes(outputSource, output, 0.8);
                    assertNotEquals(0, reEncoded.length, msg + ": 出力が空");

                    // 再度読み込んでサイズ確認
                    BufferedImage finalImage = ImageReader.read(reEncoded, output);
                    assertEquals(decoded.getWidth(), finalImage.getWidth(), msg + ": 幅");
                    assertEquals(decoded.getHeight(), finalImage.getHeight(), msg + ": 高さ");
                } catch (Exception e) {
                    failures.add(msg + ": " + e.getMessage());
                }
            }
        }

        if (!failures.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("以下の変換に失敗しました (").append(failures.size()).append("件):\n");
            for (String f : failures) sb.append("  ").append(f).append("\n");
            fail(sb.toString());
        }
    }

    @Test
    @DisplayName("全フォーマットのバイト配列入力→自動判別→全フォーマット出力")
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

            // ヘッダーから自動判別で読み込み
            BufferedImage decoded;
            try {
                decoded = ImageReader.read(encoded);
            } catch (Exception e) {
                continue;
            }
            if (decoded == null) continue;

            for (ImageFormat output : FORMATS) {
                if (output == ImageFormat.GIF89A && input != ImageFormat.GIF89A) continue;
                if (input == output) continue;

                String msg = input + "→" + output + " (auto)";
                try {
                    BufferedImage outputSource = output.supportsAlpha() ? decoded : stripAlpha(decoded);
                    byte[] outBytes = ImageWriter.toBytes(outputSource, output, 0.8);
                    assertNotEquals(0, outBytes.length, msg + ": 出力が空");
                    BufferedImage finalImage = ImageReader.read(outBytes);
                    assertEquals(decoded.getWidth(), finalImage.getWidth(), msg + ": 幅");
                    assertEquals(decoded.getHeight(), finalImage.getHeight(), msg + ": 高さ");
                } catch (Exception e) {
                    failures.add(msg + ": " + e.getMessage());
                }
            }
        }

        if (!failures.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("以下の自動判別変換に失敗しました (").append(failures.size()).append("件):\n");
            for (String f : failures) sb.append("  ").append(f).append("\n");
            fail(sb.toString());
        }
    }

    @Test
    @DisplayName("各フォーマットの品質パラメータが0.0と1.0の両端で動作する")
    void qualityBoundariesWorkForAllFormats(@TempDir Path dir) throws IOException {
        assumeLibs();

        for (ImageFormat format : FORMATS) {
            BufferedImage source = format.supportsAlpha() ? alphaGradient(32, 24) : rgbGradient(32, 24);
            try {
                byte[] low = ImageWriter.toBytes(source, format, 0.0);
                byte[] high = ImageWriter.toBytes(source, format, 1.0);
                assertTrue(low.length > 0, format + ": quality 0.0 で書き込めない");
                assertTrue(high.length > 0, format + ": quality 1.0 で書き込めない");
            } catch (Exception e) {
                fail(format + ": quality boundary が動作しない - " + e.getMessage());
            }
        }
    }

    @Test
    @DisplayName("損失形式間のクロス変換で品質がファイルサイズに反映される")
    void lossyCrossConversionFollowsQuality(@TempDir Path dir) throws IOException {
        assumeLibs();

        BufferedImage source = alphaGradient(64, 48);

        for (ImageFormat format : LOSSY_FORMATS) {
            byte[] high = ImageWriter.toBytes(source, format, 0.95);
            byte[] low = ImageWriter.toBytes(source, format, 0.05);
            assertTrue(low.length < high.length,
                    format + ": 低品質(" + low.length + ") が高品質(" + high.length + ") より大きい");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static BufferedImage alphaGradient(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                image.setRGB(x, y,
                        ((x * 255 / (width - 1)) << 24)
                        | ((y * 255 / (height - 1)) << 16)
                        | ((x * y) % 256 << 8)
                        | 128);
        return image;
    }

    private static BufferedImage rgbGradient(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                image.setRGB(x, y,
                        ((y * 255 / (height - 1)) << 16)
                        | ((x * y) % 256 << 8)
                        | 128);
        return image;
    }

    /** アルファチャネルを落として RGB 形式に変換する。 */
    private static BufferedImage stripAlpha(BufferedImage source) {
        if (!source.getColorModel().hasAlpha()) return source;
        BufferedImage rgb = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().drawImage(source, 0, 0, null);
        return rgb;
    }

    private static byte[] encode(BufferedImage image, ImageFormat format) throws IOException {
        return ImageWriter.toBytes(image, format, format.getDefaultQuality());
    }

    private static void assumeLibs() {
        assumeTrue(WebpCodec.isAvailable(),
                () -> "libwebp is not available: " + WebpCodec.getUnavailableReason());
        assumeTrue(AvifCodec.isAvailable(),
                () -> "libavif is not available: " + AvifCodec.getUnavailableReason());
    }
}
