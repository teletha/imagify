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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Resizes every PNG under {@code src/test/resources/png} with every {@link ResizeAlgorithm}
 * to a set of target widths, measures what each one costs and what it gives up, and writes
 * the comparison to an HTML report.
 *
 * <p>The report lands in {@code target/test-output/resize-report/index.html} with the
 * resized images next to it, so the numbers and the pixels can be looked at together.
 * It is the sibling of {@link FormatComparisonTest} and shares its stylesheet, its script
 * and its quality measures, so the two read the same way.</p>
 *
 * <h2>What the numbers mean</h2>
 *
 * <p>Resampling has no ground truth: there is no such thing as the correctly resampled
 * version of a photograph, so nothing here is an absolute error. Every figure is built
 * to be read <em>across</em> a row.</p>
 *
 * <ul>
 *   <li><b>PSNR</b> and <b>SSIM</b> come from a round trip. The image is resized to the
 *       target and then put back to the source size with a fixed Lanczos3 pass, and the
 *       result is compared with the source. The same fixed pass is used for every
 *       algorithm, so the ranking is fair, but the absolute value depends on that choice
 *       and means little alone. A blur and a ringing overshoot both lose information and
 *       both score alike here.</li>
 *   <li><b>Sharpness</b> is the mean gradient magnitude of the result over that of the
 *       source, divided by what a linear resize of the same factor would predict. It
 *       needs no reference at all, so it is the figure that separates a faithful result
 *       from a faithful and sharp one. 1.000 means as much edge as the scale factor
 *       accounts for; below is blur, above is ringing or an edge that has been
 *       invented.</li>
 *   <li><b>Time</b> is the wall clock of one {@code BufferedImageResize.resize} call.
 *       The first call for an algorithm pays for its classes being loaded, so it reads
 *       high.</li>
 * </ul>
 *
 * <p>The corpus is small pixel art, which is the content where resampling is most
 * visible. Every algorithm in {@link ResizeAlgorithm} is a smoothing filter, and a
 * sprite wants its pixels left alone, so the absence of a nearest neighbour among them
 * is itself the headline.</p>
 */
class ResizeComparisonTest {

    private static final String PNG_DIR = "src/test/resources/png";

    private static final String REPORT_DIR = "target/test-output/resize-report";

    /**
     * Target scale factors as percentages of original dimensions.
     *
     * <p>xBRZ (HQX) only accepts exact integer multiples, so it will only work for
     * scales that happen to produce integer multiples (unlikely for percentage scales).</p>
     */
    private static final double[] TARGET_SCALES = {0.8, 0.7, 0.6, 0.5, 0.4};

    /** The pass that puts a result back to the source size, for every algorithm alike. */
    private static final ResizeAlgorithm REFERENCE = ResizeAlgorithm.LANCZOS3;

    /** A resize of one image by one algorithm, with the measurements that describe it. */
    private record Variant(ResizeAlgorithm algorithm, double targetScale, int width, int height,
                            long nanos, double psnr, double ssim, double sharpness,
                            Path resized, long size, String failure) {

        String label() {
            return algorithm.name().toLowerCase();
        }

        boolean ok() {
            return failure == null;
        }
    }

    /** One source image together with every resize made from it. */
    private record Sample(String id, String name, int width, int height, boolean opaque,
                           Path original, List<Variant> variants) {

        Variant variant(ResizeAlgorithm algorithm, double targetScale) {
            for (Variant variant : variants) {
                if (variant.algorithm() == algorithm && variant.targetScale() == targetScale) {
                    return variant;
                }
            }
            return null;
        }

        /** @return the variant that kept the most information, or null if none of them ran */
        Variant bestAt(double targetScale) {
            Variant best = null;
            for (Variant variant : variants) {
                if (variant.targetScale() == targetScale && variant.ok()
                        && (best == null || variant.psnr() > best.psnr())) {
                    best = variant;
                }
            }
            return best;
        }
    }

    @Test
    @DisplayName("PNGを全リサイズアルゴリズムで拡大縮小し、速度と画質の比較レポートをHTMLで出力する")
    void writesComparisonReport() throws Exception {
        Path pngDir = Paths.get(PNG_DIR);
        Assumptions.assumeTrue(Files.isDirectory(pngDir), () -> PNG_DIR + " does not exist");

        List<Path> pngs = new ArrayList<>();
        Files.walk(pngDir).filter(p -> p.toString().endsWith(".png")).sorted().forEach(pngs::add);
        Assumptions.assumeFalse(pngs.isEmpty(), () -> "no PNG files in " + PNG_DIR);

        Path reportDir = Paths.get(REPORT_DIR);
        Files.createDirectories(reportDir);

        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < pngs.size(); i++) {
            samples.add(convert(pngs.get(i), String.format("img%02d", i + 1), reportDir));
        }

        Path html = reportDir.resolve("index.html");
        Files.write(html, report(samples).getBytes("UTF-8"));

        assertTrue(Files.size(html) > 2048, "the report looks truncated");
        for (Sample sample : samples) {
            assertEquals(ResizeAlgorithm.values().length * TARGET_SCALES.length,
                    sample.variants().size(),
                    sample.name() + " should carry one variant per algorithm and target scale");
            for (Variant variant : sample.variants()) {
                if (!variant.ok()) {
                    continue;
                }
                assertTrue(variant.nanos() > 0, "no timing for " + variant.label());
                assertTrue(variant.psnr() > 0, "PSNR out of range for " + variant.label());
                assertTrue(variant.ssim() >= -1 && variant.ssim() <= 1,
                        "SSIM out of range for " + variant.label() + ": " + variant.ssim());
                assertTrue(variant.sharpness() > 0, "no sharpness for " + variant.label());
                assertTrue(Files.size(variant.resized()) > 0, "empty " + variant.label());
            }
        }

        // xBRZ takes exact integer multiples only, and none of 40-80 are multiples of
        // the source dimensions (105 / 107 / 106), so it should fail for every size.
        Sample first = samples.get(0);
        for (double targetScale : TARGET_SCALES) {
            Variant v = first.variant(ResizeAlgorithm.HQX, targetScale);
            assertNotNull(v, "should have an HQX entry for " + sizeLabel(targetScale));
            assertFalse(v.ok(),
                    "xBRZ cannot produce " + sizeLabel(targetScale) + " from " + first.width()
                            + "px, so it should report failure");
        }

        System.out.println("report → " + html.toAbsolutePath());
        System.out.printf("  %-6s %-12s %10s %8s %9s %10s%n",
                "size", "algorithm", "avg time", "PSNR", "SSIM", "sharpness");
        for (String[] row : summaryRows(samples)) {
            System.out.printf("  %-6s %-12s %10s %8s %9s %10s%n",
                    row[0], row[1], row[2], row[3], row[4], row[5]);
        }
    }

    // ------------------------------------------------------------------ conversion

    private static Sample convert(Path png, String id, Path reportDir) throws IOException {
        BufferedImage source = ImageIO.read(png.toFile());
        assertNotNull(source, "cannot read " + png);

        int width = source.getWidth();
        int height = source.getHeight();
        int[] reference = ImageMetrics.argb(source);
        double sourceGradient = ImageMetrics.gradient(source);

        Path dir = reportDir.resolve(id);
        Files.createDirectories(dir);
        Path original = dir.resolve("original.png");
        ImageIO.write(source, "png", original.toFile());

        List<Variant> variants = new ArrayList<>();
        for (double targetScale : TARGET_SCALES) {
            for (ResizeAlgorithm algorithm : ResizeAlgorithm.values()) {
                variants.add(resize(source, dir, algorithm, targetScale,
                        reference, sourceGradient));
            }
        }

        return new Sample(id, png.getFileName().toString(), width, height,
                ImageMetrics.isOpaque(reference), original, variants);
    }

    private static Variant resize(BufferedImage source, Path dir, ResizeAlgorithm algorithm,
                                    double targetScale, int[] reference, double sourceGradient)
                                    throws IOException {
        int width = source.getWidth();
        int height = source.getHeight();
        int targetW = Math.max(1, (int) Math.round(width * targetScale));
        int targetH = Math.max(1, (int) Math.round(height * targetScale));
        String stem = algorithm.name().toLowerCase() + "." + sizeLabel(targetScale);

        long start = System.nanoTime();
        BufferedImage result;
        try {
            result = BufferedImageResize.resize(source, targetW, targetH, algorithm);
        } catch (RuntimeException e) {
            // xBRZ refuses anything that is not an exact integer multiple, and says why.
            // That is an answer worth showing rather than an error worth hiding.
            return new Variant(algorithm, targetScale, targetW, targetH, 0, 0, 0, 0,
                    null, 0, e.getMessage());
        }
        long nanos = System.nanoTime() - start;

        Path resized = dir.resolve(stem + ".png");
        ImageIO.write(result, "png", resized.toFile());

        // Put the result back to the source size and see how much of the original survived
        // the trip. Both directions go back to the source size, and the same pass is used
        // for every algorithm, so the numbers rank fairly.
        BufferedImage restored = BufferedImageResize.resize(result, width, height, REFERENCE);
        int[] actual = ImageMetrics.argb(restored);

        // A linear resize of the same factor would leave this much edge behind. Anything
        // else is the algorithm's own doing: blur takes it away, ringing adds edges that
        // were not there.
        double expected = targetScale < 1 ? targetScale : 1.0;
        double sharpness = (ImageMetrics.gradient(result) / sourceGradient) / expected;

        long size = Files.size(resized);
        return new Variant(algorithm, targetScale, result.getWidth(), result.getHeight(), nanos,
                ImageMetrics.psnr(reference, actual),
                ImageMetrics.ssim(reference, actual, width, height),
                sharpness, resized, size, null);
    }

    // ------------------------------------------------------------------ aggregation

    /** One row per target scale and algorithm, averaged over every source image. */
    private static List<String[]> summaryRows(List<Sample> samples) {
        List<String[]> rows = new ArrayList<>();
        for (double targetScale : TARGET_SCALES) {
            for (ResizeAlgorithm algorithm : ResizeAlgorithm.values()) {
                rows.add(aggregate(samples, algorithm, targetScale));
            }
        }
        return rows;
    }

    private static String[] aggregate(List<Sample> samples, ResizeAlgorithm algorithm,
                                            double targetScale) {
        long nanos = 0;
        double psnrSum = 0;
        double ssimSum = 0;
        double sharpnessSum = 0;
        long sizeSum = 0;
        int count = 0;
        for (Sample sample : samples) {
            Variant variant = sample.variant(algorithm, targetScale);
            if (variant == null || !variant.ok()) {
                continue;
            }
            nanos += variant.nanos();
            psnrSum += variant.psnr();
            ssimSum += variant.ssim();
            sharpnessSum += variant.sharpness();
            sizeSum += variant.size();
            count++;
        }
        if (count == 0) {
            return new String[] {sizeLabel(targetScale), algorithm.name().toLowerCase(),
                    "-", "-", "-", "-", "-"};
        }
        return new String[] {
                sizeLabel(targetScale),
                algorithm.name().toLowerCase(),
                String.format("%.2f ms", nanos / count / 1_000_000.0),
                String.format("%.1f", psnrSum / count),
                String.format("%.4f", ssimSum / count),
                String.format("%.3f", sharpnessSum / count),
                group(sizeSum / count),
        };
    }

    private static String group(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        }
        if (bytes >= 1024) {
            return String.format("%.1f kB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    /** @return the label a reader sees, such as {@code 80%} */
    static String sizeLabel(double targetScale) {
        return (int)(targetScale * 100) + "%";
    }

    // ------------------------------------------------------------------ report

    private static String report(List<Sample> samples) {
        StringBuilder html = new StringBuilder(128 * 1024);

        html.append("<!DOCTYPE html>\n<html lang=\"ja\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>リサイズアルゴリズムの比較</title>\n")
                .append("<style>\n").append(ReportAssets.css()).append(extraCss())
                .append("\n</style>\n</head>\n<body>\n");

        html.append("<h1>リサイズアルゴリズムの比較</h1>\n")
                .append("<p class=\"lead\">").append(samples.size())
                .append(" 枚の PNG を ").append(ResizeAlgorithm.values().length)
                .append(" 種類のアルゴリズムで ").append(TARGET_SCALES.length)
                .append(" 段階のサイズにリサイズし、所要時間と画質指標を比べたもの。</p>\n");

        appendNotes(html);
        appendSummary(html, samples);
        appendDetail(html, samples);
        appendComparisons(html, samples);

        return html.append("<script>\n").append(ReportAssets.js())
                .append("\n</script>\n</body>\n</html>\n").toString();
    }

    private static void appendNotes(StringBuilder html) {
        String reference = REFERENCE.name().toLowerCase();
        html.append("<h2>1. 見方</h2>\n")
                .append("<p class=\"note\">リサンプリングには正解の画像が存在しない。そのため以下の数値は絶対的な誤差ではなく、横の並びで読むためのものである。</p>\n")
                .append("<table>\n<thead><tr><th>指標</th><th>算出方法</th><th>読み方</th>")
                .append("</tr></thead>\n<tbody>\n")
                .append("<tr><td>PSNR / SSIM</td><td>目標サイズまでリサイズし、")
                .append(reference).append(" で元サイズへ戻してから元画像と比較</td>")
                .append("<td>往復して情報をどれだけ保ったか。復元に使う ")
                .append(reference)
                .append(" は全アルゴリズム共通なので横の比較は公平だが、絶対値そのものは意味を持たない</td></tr>\n")
                .append("<tr><td>鮮鋭度</td><td>結果の平均勾配を元画像の平均勾配で割り、")
                .append("さらに線形スケールなら残るはずの値で割る</td>")
                .append("<td>基準画像が要らない指標。1.000 が適正で、下回ればボケ、")
                .append("上回ればリンギングや存在しないエッジの生成</td></tr>\n")
                .append("<tr><td>時間</td><td><code>BufferedImageResize.resize</code> 1 回の実測</td>")
                .append("<td>同じアルゴリズムの初回呼び出しはクラス読み込みの分を含むため大きめに出る</td></tr>\n")
                .append("</tbody>\n</table>\n")
                .append("<p class=\"note\">比較対象はドット絵で、<code>ResizeAlgorithm</code> の 5 種は")
                .append("いずれも平滑化フィルタである。ドット絵はピクセルを 1 個も動かしたくないので、")
                .append("ニアレストネイバーが選択肢に含まれていないこと自体がこの比較の結論である。</p>\n")
                .append("<p class=\"note\"><code>HQX</code> は xBRZ の実装で、整数倍以外に拡大できない。"
                        + "80 / 70 / 60 / 50 / 40 px は元画像 (105 / 107 px) の整数倍ではないので、")
                .append("表と画像では n/a として示している。</p>\n");
    }

    private static void appendSummary(StringBuilder html, List<Sample> samples) {
        List<String[]> rows = summaryRows(samples);
        html.append("<h2>2. 集計</h2>\n")
                .append("<p class=\"note\">全画像の平均。見出しをクリックすると並び替えられる。</p>\n")
                .append("<table>\n<thead><tr><th>サイズ</th><th>アルゴリズム</th><th>平均時間</th>")
                .append("<th>PSNR (dB)</th><th>SSIM</th><th>鮮鋭度</th><th>平均サイズ</th>")
                .append("</tr></thead>\n<tbody>\n");
        for (String[] row : rows) {
            html.append("<tr><td>").append(escape(row[0])).append("</td><td>")
                    .append(escape(row[1]))
                    .append("</td><td class=\"num\">").append(escape(row[2]))
                    .append("</td><td class=\"num\">").append(escape(row[3]))
                    .append("</td><td class=\"num\">").append(escape(row[4]))
                    .append("</td><td class=\"num\">").append(escape(row[5]))
                    .append("</td><td class=\"num\">").append(escape(row[6]))
                    .append("</td></tr>\n");
        }
        html.append("</tbody>\n</table>\n");
    }

    private static void appendDetail(StringBuilder html, List<Sample> samples) {
        html.append("<h2>3. 一覧</h2>\n")
                .append("<p class=\"note\">画像ごとの値。括弧内は処理時間と鮮鋭度。"
                        + "サイズごとに PSNR 最高のセルを強調している。</p>\n")
                .append("<table>\n<thead><tr><th>画像</th><th>サイズ</th>");
        for (double targetScale : TARGET_SCALES) {
            for (ResizeAlgorithm algorithm : ResizeAlgorithm.values()) {
                html.append("<th>").append(sizeLabel(targetScale)).append(' ')
                        .append(algorithm.name().toLowerCase()).append("</th>");
            }
        }
        html.append("</tr></thead>\n<tbody>\n");

        for (Sample sample : samples) {
            html.append("<tr><td class=\"name\"><a href=\"#").append(sample.id()).append("\">")
                    .append(escape(sample.name())).append("</a>")
                    .append(sample.opaque() ? "" : " <span class=\"tag\">透過あり</span>")
                    .append("</td><td class=\"num\">").append(sample.width()).append("×")
                    .append(sample.height()).append("</td>");
            for (double targetScale : TARGET_SCALES) {
                for (ResizeAlgorithm algorithm : ResizeAlgorithm.values()) {
                    html.append(cell(sample, algorithm, targetScale));
                }
            }
            html.append("</tr>\n");
        }
        html.append("</tbody>\n</table>\n");
    }

    private static String cell(Sample sample, ResizeAlgorithm algorithm, double targetScale) {
        Variant variant = sample.variant(algorithm, targetScale);
        if (variant == null) {
            return "<td class=\"num\">-</td>";
        }
        if (!variant.ok()) {
            return "<td class=\"num na\" title=\"" + escape(variant.failure()) + "\">n/a</td>";
        }
        boolean best = sample.bestAt(targetScale) == variant;
        return "<td class=\"num" + (best ? " best" : "") + "\">"
                + String.format("%.1f", variant.psnr())
                + "<small>" + String.format("%.2f ms", variant.nanos() / 1_000_000.0)
                + " · " + String.format("%.3f", variant.sharpness())
                + " · " + group(variant.size()) + "</small></td>";
    }

    private static void appendComparisons(StringBuilder html, List<Sample> samples) {
        html.append("<h2>4. 画像比較</h2>\n")
                .append("<p class=\"note\">各画像と各スケールのリサイズ結果を示す。</p>\n");

        for (Sample sample : samples) {
            html.append("<div class=\"sample\" id=\"").append(sample.id()).append("\">\n<h3>")
                    .append(escape(sample.name())).append(" · ").append(sample.width()).append("×")
                    .append(sample.height()).append("</h3>\n");

            // Original at native size.
            String origSize = "--w:" + sample.width() + "px;--ar:"
                    + sample.width() + "/" + sample.height();
            html.append("<h4>元画像</h4>\n<div class=\"grid\">\n")
                    .append(figure(relative(sample.original().toString()), origSize,
                            "元画像 · " + sample.width() + "×" + sample.height()))
                    .append("</div>\n");

            // Per target scale, one figure per algorithm.
            for (double targetScale : TARGET_SCALES) {
                html.append("<h4>").append(sizeLabel(targetScale)).append("</h4>\n")
                        .append("<div class=\"grid\">\n");
                for (ResizeAlgorithm algorithm : ResizeAlgorithm.values()) {
                    Variant variant = sample.variant(algorithm, targetScale);
                    if (variant == null || !variant.ok()) {
                        html.append(unsupported(sample, algorithm, targetScale, variant));
                        continue;
                    }
                    String caption = variant.label() + " · " + variant.width() + "×"
                            + variant.height()
                            + "<small>PSNR " + String.format("%.1f", variant.psnr())
                            + " · SSIM " + String.format("%.4f", variant.ssim())
                            + " · 鮮鋭度 " + String.format("%.3f", variant.sharpness())
                            + " · " + String.format("%.2f ms", variant.nanos() / 1_000_000.0)
                            + " · " + group(variant.size()) + "</small>";
                    String size = "--w:" + variant.width() + "px;--ar:"
                            + variant.width() + "/" + variant.height();
                    html.append(figure(relative(variant.resized().toString()), size, caption));
                }
                html.append("</div>\n");
            }
            html.append("</div>\n");
        }
    }

    /** One simple figure with an image and a caption, no wipe slider. */
    private static String figure(String src, String size, String caption) {
        return "<figure class=\"cmp\" style=\"" + size + "\">"
                + "<img src=\"" + escape(src) + "\" alt=\"\" loading=\"lazy\" draggable=\"false\">"
                + "<figcaption>" + caption + "</figcaption></figure>\n";
    }

    private static String unsupported(Sample sample, ResizeAlgorithm algorithm,
                                             double targetScale, Variant variant) {
        String reason = variant == null ? "" : variant.failure();
        int targetW = Math.max(1, (int) Math.round(sample.width() * targetScale));
        int targetH = Math.max(1, (int) Math.round(sample.height() * targetScale));
        String size = "--w:" + targetW + "px;--ar:" + targetW + "/" + targetH;
        return "<figure class=\"cmp\" style=\"" + size + "\">"
                + "<div class=\"na-box\">" + escape(algorithm.name().toLowerCase())
                + "<br>非対応</div>"
                + "<figcaption>" + sizeLabel(targetScale) + ' '
                + escape(algorithm.name().toLowerCase()) + " · 非対応"
                + (reason.isEmpty() ? "" : "<small>" + escape(reason) + "</small>")
                + "</figcaption></figure>\n";
    }

    /** Turns a path under the report directory into one relative to the report. */
    private static String relative(String file) {
        String path = file.replace('\\', '/');
        return path.startsWith(REPORT_DIR + "/") ? path.substring(REPORT_DIR.length() + 1) : path;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** The rules this report needs on top of the shared ones. */
    private static String extraCss() {
        return ""
            + ":root{--zoom:1}"
            + "h4{font-size:13px;margin:22px 0 6px;font-weight:600;color:#57606a}"
            + ".note code{background:#f6f8fa;padding:0 4px;border-radius:3px;"
            + "font:12px/1.4 ui-monospace,SFMono-Regular,Consolas,monospace}"
            + "td.best{background:#e7f6ec;font-weight:600}"
            + "td.best small{color:#1a7f37}"
            + "td.na{color:#8b949e}"
            + ".cmp{position:relative}"
            + ".cmp img{width:100%;height:auto;display:block;image-rendering:pixelated}"
            + ".na-box{position:absolute;inset:0;display:flex;align-items:center;"
            + "justify-content:center;text-align:center;font-size:12px;color:#8b949e;"
            + "background:repeating-conic-gradient(#e9edf1 0 25%,#fff 0 50%) 0 0/16px 16px}"
            + "@media(prefers-color-scheme:dark){"
            + "h4{color:#8b949e}"
            + ".note code{background:#161b22}"
            + "td.best{background:#12261a;color:#7ee2a8}"
            + "td.best small{color:#56d38a}"
            + "td.na{color:#6e7681}"
            + ".na-box{color:#6e7681;"
            + "background:repeating-conic-gradient(#161b22 0 25%,#0d1117 0 50%) 0 0/16px 16px}}";
    }
}
