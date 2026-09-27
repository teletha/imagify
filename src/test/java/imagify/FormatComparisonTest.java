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

import imagify.avif.AvifException;
import imagify.avif.jna.AvifCodec;
import imagify.avif.jna.AvifLibrary;
import imagify.webp.WebpCodec;
import imagify.webp.WebpException;
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
 * Converts every PNG under {@code src/test/resources/png} to AVIF and to WebP at a range
 * of quality settings, measures what each setting costs in bytes and what it gives up in
 * fidelity, and writes the comparison to an HTML report.
 *
 * <p>The report lands in {@code target/test-output/report/index.html} with the encoded
 * files and their decoded results next to it, so the numbers and the pixels can be looked
 * at together.
 */
class FormatComparisonTest {

    private static final String PNG_DIR = "src/test/resources/png";

    private static final String REPORT_DIR = "target/test-output/report";

    /** Quality settings to compare. Both {@code libavif} and {@code libwebp} take 0-100. */
    private static final int[] QUALITIES = {10, 30, 50, 70, 90};

    /** The library default: slow enough to be fair, fast enough to keep the test short. */
    private static final int AVIF_SPEED = AvifLibrary.DEFAULT_SPEED;

    private static final String AVIF = "avif";

    private static final String WEBP = "webp";

    private static final String WEBP_LOSSLESS = "webp-lossless";

    /** One source image together with every encoding made from it. */
    private record Sample(String id, String name, int width, int height, int sourceBytes,
                          boolean opaque, List<Variant> variants) {}

    /** One encoding of one image, with the measurements that describe it. */
    private record Variant(String format, int quality, int bytes, double psnr, double ssim,
                           long encodeNanos, long decodeNanos, Path encoded, Path decoded) {

        String label() {
            return WEBP_LOSSLESS.equals(format) ? "webp lossless" : format + " q" + quality;
        }
    }

    @Test
    @DisplayName("PNGをAVIFとWebPに変換し、画質とサイズの比較レポートをHTMLで出力する")
    void writesComparisonReport() throws Exception {
        Assumptions.assumeTrue(AvifCodec.isAvailable(),
                () -> "libavif is not available: " + AvifCodec.getUnavailableReason());
        Assumptions.assumeTrue(WebpCodec.isAvailable(),
                () -> "libwebp is not available: " + WebpCodec.getUnavailableReason());

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
            assertEquals(QUALITIES.length * 2 + 1, sample.variants().size(),
                    sample.name() + " should carry one variant per format and quality");
            for (Variant variant : sample.variants()) {
                assertTrue(variant.bytes() > 0, "empty " + variant.label());
                assertTrue(variant.psnr() > 0, "PSNR out of range for " + variant.label());
                assertTrue(variant.ssim() >= -1 && variant.ssim() <= 1,
                        "SSIM out of range for " + variant.label() + ": " + variant.ssim());
                assertEquals(variant.bytes(), Files.size(variant.encoded()),
                        "the report disagrees with the file it measured: " + variant.label());
            }
        }
        System.out.println("report → " + html.toAbsolutePath());
        System.out.printf("  %-16s %-8s %10s %8s %9s %8s %10s%n",
                "format", "quality", "bytes", "vs PNG", "PSNR", "SSIM", "encode");
        for (String[] row : summaryRows(samples)) {
            System.out.printf("  %-16s %-8s %10s %8s %9s %8s %10s%n",
                    row[0], row[1], row[2], row[3], row[4], row[5], row[6]);
        }
    }

    // ------------------------------------------------------------------ conversion

    private static Sample convert(Path png, String id, Path reportDir)
            throws IOException, AvifException, WebpException {
        BufferedImage image = ImageIO.read(png.toFile());
        assertNotNull(image, "cannot read " + png);

        int width = image.getWidth();
        int height = image.getHeight();
        int[] reference = argb(image);
        int sourceBytes = (int) Files.size(png);
        boolean opaque = isOpaque(reference);

        Path originalDir = reportDir.resolve("original");
        Files.createDirectories(originalDir);
        Path original = originalDir.resolve(id + ".png");
        ImageIO.write(image, "png", original.toFile());

        Path avifDir = reportDir.resolve(AVIF);
        Path webpDir = reportDir.resolve(WEBP);
        Files.createDirectories(avifDir);
        Files.createDirectories(webpDir);

        List<Variant> variants = new ArrayList<>();
        for (int quality : QUALITIES) {
            long start = System.nanoTime();
            byte[] encoded = AvifCodec.encode(image, quality, AVIF_SPEED);
            long encodeNanos = System.nanoTime() - start;

            start = System.nanoTime();
            BufferedImage decoded = AvifCodec.decode(encoded).image();
            long decodeNanos = System.nanoTime() - start;

            Path file = avifDir.resolve(id + ".q" + quality + ".avif");
            Files.write(file, encoded);
            variants.add(measure(AVIF, quality, encoded, decoded, reference, width, height,
                    encodeNanos, decodeNanos, file));
        }

        for (int quality : QUALITIES) {
            long start = System.nanoTime();
            byte[] encoded = WebpCodec.encode(image, quality, false);
            long encodeNanos = System.nanoTime() - start;

            start = System.nanoTime();
            BufferedImage decoded = WebpCodec.decode(encoded);
            long decodeNanos = System.nanoTime() - start;

            Path file = webpDir.resolve(id + ".q" + quality + ".webp");
            Files.write(file, encoded);
            variants.add(measure(WEBP, quality, encoded, decoded, reference, width, height,
                    encodeNanos, decodeNanos, file));
        }

        // WebP can be lossless, so it gives a useful upper bound on what fidelity costs.
        long start = System.nanoTime();
        byte[] lossless = WebpCodec.encode(image, 0, true);
        long encodeNanos = System.nanoTime() - start;
        start = System.nanoTime();
        BufferedImage decoded = WebpCodec.decode(lossless);
        long decodeNanos = System.nanoTime() - start;

        Path file = webpDir.resolve(id + ".lossless.webp");
        Files.write(file, lossless);
        variants.add(measure(WEBP_LOSSLESS, 0, lossless, decoded, reference, width, height,
                encodeNanos, decodeNanos, file));

        return new Sample(id, png.getFileName().toString(), width, height, sourceBytes, opaque, variants);
    }

    private static Variant measure(String format, int quality, byte[] encoded, BufferedImage decoded,
                                   int[] reference, int width, int height, long encodeNanos,
                                   long decodeNanos, Path file) throws IOException {
        // The decoded pixels are written out as PNG so the browser shows exactly what the
        // codec produced, no matter how it feels about rendering AVIF and WebP itself.
        Path decodedPng = file.resolveSibling(file.getFileName() + ".decoded.png");
        ImageIO.write(decoded, "png", decodedPng.toFile());

        int[] actual = argb(decoded);
        assertEquals(reference.length, actual.length, "the round trip changed the image size");
        return new Variant(format, quality, encoded.length, psnr(reference, actual),
                ssim(reference, actual, width, height), encodeNanos, decodeNanos, file, decodedPng);
    }

    // ------------------------------------------------------------------ measurement

    /** Reads an image into one packed ARGB int per pixel, which is layout independent. */
    private static int[] argb(BufferedImage image) {
        int[] pixels = new int[image.getWidth() * image.getHeight()];
        int i = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                pixels[i++] = image.getRGB(x, y);
            }
        }
        return pixels;
    }

    private static boolean isOpaque(int[] pixels) {
        for (int pixel : pixels) {
            if ((pixel >>> 24) != 0xFF) {
                return false;
            }
        }
        return true;
    }

    /** Peak signal to noise ratio over the colour channels, in decibels. */
    private static double psnr(int[] reference, int[] actual) {
        double squared = 0;
        for (int i = 0; i < reference.length; i++) {
            int a = reference[i];
            int b = actual[i];
            int dr = ((a >>> 16) & 0xFF) - ((b >>> 16) & 0xFF);
            int dg = ((a >>> 8) & 0xFF) - ((b >>> 8) & 0xFF);
            int db = (a & 0xFF) - (b & 0xFF);
            squared += (double) dr * dr + (double) dg * dg + (double) db * db;
        }
        double mse = squared / (reference.length * 3);
        return mse == 0 ? 99 : 10 * Math.log10(255.0 * 255.0 / mse);
    }

    /** Mean structural similarity over luma, averaged over the usual 8x8 windows. */
    private static double ssim(int[] reference, int[] actual, int width, int height) {
        final int window = 8;
        if (width < window || height < window) {
            return 1;
        }
        double[] referenceLuma = luma(reference);
        double[] actualLuma = luma(actual);
        final double c1 = square(0.01 * 255);
        final double c2 = square(0.03 * 255);
        final int n = window * window;

        double total = 0;
        int windows = 0;
        for (int y = 0; y + window <= height; y++) {
            for (int x = 0; x + window <= width; x++) {
                double sumA = 0;
                double sumB = 0;
                for (int j = 0; j < window; j++) {
                    int row = (y + j) * width + x;
                    for (int i = 0; i < window; i++) {
                        sumA += referenceLuma[row + i];
                        sumB += actualLuma[row + i];
                    }
                }
                double meanA = sumA / n;
                double meanB = sumB / n;

                double varianceA = 0;
                double varianceB = 0;
                double covariance = 0;
                for (int j = 0; j < window; j++) {
                    int row = (y + j) * width + x;
                    for (int i = 0; i < window; i++) {
                        double da = referenceLuma[row + i] - meanA;
                        double db = actualLuma[row + i] - meanB;
                        varianceA += da * da;
                        varianceB += db * db;
                        covariance += da * db;
                    }
                }
                varianceA /= n - 1;
                varianceB /= n - 1;
                covariance /= n - 1;

                total += ((2 * meanA * meanB + c1) * (2 * covariance + c2))
                        / ((meanA * meanA + meanB * meanB + c1) * (varianceA + varianceB + c2));
                windows++;
            }
        }
        return total / windows;
    }

    private static double[] luma(int[] pixels) {
        double[] out = new double[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            out[i] = 0.299 * ((pixel >>> 16) & 0xFF)
                    + 0.587 * ((pixel >>> 8) & 0xFF)
                    + 0.114 * (pixel & 0xFF);
        }
        return out;
    }

    private static double square(double value) {
        return value * value;
    }

    // ------------------------------------------------------------------ aggregation

    /** One row per format and quality, aggregated over every source image. */
    private static List<String[]> summaryRows(List<Sample> samples) {
        long source = 0;
        for (Sample sample : samples) {
            source += sample.sourceBytes();
        }
        List<String[]> rows = new ArrayList<>();
        for (String format : new String[] {AVIF, WEBP}) {
            for (int quality : QUALITIES) {
                rows.add(aggregate(samples, format, quality, source));
            }
        }
        rows.add(aggregate(samples, WEBP_LOSSLESS, 0, source));
        return rows;
    }

    private static String[] aggregate(List<Sample> samples, String format, int quality, long source) {
        long bytes = 0;
        long encodeNanos = 0;
        double psnrSum = 0;
        double ssimSum = 0;
        int count = 0;
        for (Sample sample : samples) {
            for (Variant variant : sample.variants()) {
                if (!variant.format().equals(format) || variant.quality() != quality) {
                    continue;
                }
                bytes += variant.bytes();
                encodeNanos += variant.encodeNanos();
                psnrSum += variant.psnr();
                ssimSum += variant.ssim();
                count++;
            }
        }
        return new String[] {
                WEBP_LOSSLESS.equals(format) ? "webp lossless" : format,
                WEBP_LOSSLESS.equals(format) ? "-" : String.valueOf(quality),
                group(bytes),
                String.format("%.1f%%", 100.0 * bytes / source),
                String.format("%.1f", psnrSum / count),
                String.format("%.4f", ssimSum / count),
                String.format("%.0f ms", encodeNanos / 1_000_000.0),
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

    // ------------------------------------------------------------------ report

    private static String report(List<Sample> samples) {
        long source = 0;
        for (Sample sample : samples) {
            source += sample.sourceBytes();
        }
        StringBuilder html = new StringBuilder(64 * 1024);

        html.append("<!DOCTYPE html>\n<html lang=\"ja\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>AVIF / WebP 画質とサイズの比較</title>\n")
                .append("<style>\n").append(css()).append("\n</style>\n</head>\n<body>\n");

        html.append("<h1>AVIF / WebP 画質とサイズの比較</h1>\n")
                .append("<p class=\"lead\">").append(samples.size()).append(" 枚の PNG（合計 ")
                .append(group(source)).append("）を libavif ").append(AvifCodec.getVersion())
                .append(" と WebP に変換し、品質ごとのサイズと画質指標を比べたもの。"
                + "画質指標は元画像にデコードし直した結果に対して算出している。</p>\n");

        appendSummary(html, samples);
        appendDetail(html, samples);

        html.append("<h2>3. 画像比較</h2>\n")
                .append("<p class=\"note\">各画像をドラッグすると、原画像（左）と変換後（右）の境目が動く。"
                        + "1× は元画像のピクセル寸そのまま（等倍）で表示する。</p>\n")
                .append("<div class=\"zoom\">")
                .append("<button data-zoom=\"1\">1×</button>")
                .append("<button data-zoom=\"2\" class=\"on\">2×</button>")
                .append("<button data-zoom=\"3\">3×</button>")
                .append("<button data-zoom=\"4\">4×</button>")
                .append("</div>\n");

        for (Sample sample : samples) {
            String ratio = sample.width() + "/" + sample.height();
            // 1× is the image's own pixel size, so the grid is sized from the source.
            String size = "--w:" + sample.width() + "px;--ar:" + ratio;
            String originalPath = relative("original/" + sample.id() + ".png");
            html.append("<div class=\"sample\" id=\"").append(sample.id()).append("\">\n<h3>")
                    .append(escape(sample.name())).append("</h3>\n")
                    .append("<div class=\"grid\">\n")
                    .append("<figure class=\"cmp\" style=\"").append(size).append("\">")
                    .append("<div class=\"stack\" style=\"").append(size).append("\">")
                    .append("<img class=\"after\" src=\"").append(escape(originalPath))
                    .append("\" alt=\"").append(escape(sample.name())).append("\" loading=\"lazy\">")
                    .append("</div>")
                    .append("<figcaption>原画像 · ").append(group(sample.sourceBytes()))
                    .append(" · ").append(sample.width()).append("×").append(sample.height())
                    .append("</figcaption></figure>\n");

            for (Variant variant : sample.variants()) {
                html.append("<figure class=\"cmp\" style=\"").append(size).append("\">")
                        .append("<div class=\"stack\" style=\"").append(size).append("\">")
                        .append("<img class=\"after\" src=\"").append(escape(originalPath))
                        .append("\" alt=\"\" loading=\"lazy\">")
                        .append("<div class=\"clip\"><img class=\"before\" src=\"")
                        .append(escape(relative(variant.decoded().toString())))
                        .append("\" alt=\"").append(escape(variant.label()))
                        .append("\" loading=\"lazy\"></div></div>")
                        .append("<figcaption>").append(escape(variant.label()))
                        .append(" · ").append(group(variant.bytes()))
                        .append(" · SSIM ").append(String.format("%.4f", variant.ssim()))
                        .append("</figcaption></figure>\n");
            }
            html.append("</div>\n</div>\n");
        }

        return html.append("<script>\n").append(js()).append("\n</script>\n</body>\n</html>\n").toString();
    }

    private static void appendSummary(StringBuilder html, List<Sample> samples) {
        html.append("<h2>1. 集計</h2>\n")
                .append("<p class=\"note\">全画像の合計。SSIM が 1 に近いほど原画像に似ている。</p>\n")
                .append("<table>\n<thead><tr><th>形式</th><th>品質</th><th>合計サイズ</th>")
                .append("<th>PNG 比</th><th>PSNR (dB)</th><th>SSIM</th><th>エンコード</th>")
                .append("</tr></thead>\n<tbody>\n");
        for (String[] row : summaryRows(samples)) {
            html.append("<tr><td>").append(escape(row[0])).append("</td><td>").append(escape(row[1]))
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
        html.append("<h2>2. 一覧</h2>\n")
                .append("<p class=\"note\">見出しをクリックすると並び替えられる。"
                        + "括弧内は PNG に対する圧縮率と SSIM。</p>\n")
                .append("<table>\n<thead><tr><th>画像</th><th>サイズ</th><th>PNG</th>");
        for (String format : new String[] {AVIF, WEBP}) {
            for (int quality : QUALITIES) {
                html.append("<th>").append(format).append(" q").append(quality).append("</th>");
            }
        }
        html.append("<th>webp lossless</th></tr></thead>\n<tbody>\n");

        for (Sample sample : samples) {
            html.append("<tr><td class=\"name\"><a href=\"#").append(sample.id()).append("\">")
                    .append(escape(sample.name())).append("</a>")
                    .append(sample.opaque() ? "" : " <span class=\"tag\">透過あり</span>")
                    .append("</td><td class=\"num\">").append(sample.width()).append("×")
                    .append(sample.height()).append("</td><td class=\"num\">")
                    .append(group(sample.sourceBytes())).append("</td>");
            for (String format : new String[] {AVIF, WEBP}) {
                for (int quality : QUALITIES) {
                    html.append(cell(sample, format, quality));
                }
            }
            html.append(cell(sample, WEBP_LOSSLESS, 0)).append("</tr>\n");
        }
        html.append("</tbody>\n</table>\n");
    }

    private static String cell(Sample sample, String format, int quality) {
        for (Variant variant : sample.variants()) {
            if (variant.format().equals(format) && variant.quality() == quality) {
                return "<td class=\"num\">" + group(variant.bytes())
                        + "<small>" + String.format("%.0f%%", 100.0 * variant.bytes() / sample.sourceBytes())
                        + " (" + String.format("%.3f", variant.ssim()) + ")</small></td>";
            }
        }
        return "<td class=\"num\">-</td>";
    }

    /** Turns a path under the report directory into one relative to the report. */
    private static String relative(String file) {
        String path = file.replace('\\', '/');
        return path.startsWith(REPORT_DIR + "/") ? path.substring(REPORT_DIR.length() + 1) : path;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String css() {
        return ""
            + ":root{--zoom:2}"
            + "*{box-sizing:border-box}"
            + "body{margin:0;padding:32px;font:14px/1.6 -apple-system,'Segoe UI','Noto Sans JP',sans-serif;"
            + "color:#1b1f23;background:#fff}"
            + "h1{font-size:24px;margin:0 0 8px}"
            + "h2{font-size:19px;margin:40px 0 4px;padding-bottom:6px;border-bottom:2px solid #1b1f23}"
            + "h3{font-size:15px;margin:28px 0 8px;font-weight:600}"
            + ".lead{margin:0 0 8px;color:#57606a;max-width:70ch}"
            + ".note{margin:0 0 12px;color:#57606a}"
            + "table{border-collapse:collapse;font-size:13px;margin-bottom:8px}"
            + "th,td{border:1px solid #d0d7de;padding:5px 9px;white-space:nowrap}"
            + "th{background:#f6f8fa;cursor:pointer;user-select:none}"
            + "th:hover{background:#eaeef2}"
            + "td.num{text-align:right;font-variant-numeric:tabular-nums}"
            + "td small{display:block;color:#6e7781;font-size:11px}"
            + "td.name{font-weight:600}"
            + "td.name a{color:#0969da;text-decoration:none}"
            + "td.name a:hover{text-decoration:underline}"
            + ".tag{font-size:11px;font-weight:400;color:#9a6700;background:#fff8c5;padding:0 4px;"
            + "border-radius:3px}"
            + ".zoom{margin:0 0 20px}"
            + ".zoom button{font:inherit;padding:4px 12px;border:1px solid #d0d7de;background:#f6f8fa;"
            + "cursor:pointer}"
            + ".zoom button:first-child{border-radius:6px 0 0 6px}"
            + ".zoom button:last-child{border-radius:0 6px 6px 0}"
            + ".zoom button+button{border-left:none}"
            + ".zoom button.on{background:#1b1f23;color:#fff;border-color:#1b1f23}"
            + ".sample{border-top:1px solid #d0d7de;padding-top:4px}"
            + ".grid{display:flex;flex-wrap:wrap;gap:16px}"
            + ".cmp{margin:0;width:calc(var(--w) * var(--zoom));flex:0 0 auto}"
            + ".stack{position:relative;width:100%;aspect-ratio:var(--ar);overflow:hidden;line-height:0;"
            + "border:1px solid #d0d7de;border-radius:6px;cursor:ew-resize;"
            + "background:repeating-conic-gradient(#e9edf1 0 25%,#fff 0 50%) 0 0/16px 16px}"
            + ".stack img{position:absolute;top:0;left:0;display:block;image-rendering:pixelated}"
            + ".stack .after{width:100%;height:100%}"
            + ".stack .clip{position:absolute;top:0;left:0;height:100%;width:50%;overflow:hidden}"
            + ".stack .before{height:100%;width:auto;max-width:none}"
            + "figcaption{font-size:11px;color:#57606a;padding-top:4px;line-height:1.4;word-break:break-word}"
            + "@media(prefers-color-scheme:dark){"
            + "body{background:#0d1117;color:#e6edf3}"
            + "h2{border-color:#e6edf3}"
            + ".lead,.note,figcaption{color:#8b949e}"
            + "th{background:#161b22}th:hover{background:#21262d}"
            + "th,td{border-color:#30363d}.stack{border-color:#30363d}"
            + "td small{color:#8b949e}"
            + "td.name a{color:#4493f8}}";
    }

    private static String js() {
        return ""
            + "document.querySelectorAll('.zoom button').forEach(function(b){"
            + "  b.addEventListener('click',function(){"
            + "    document.documentElement.style.setProperty('--zoom',b.dataset.zoom);"
            + "    document.querySelectorAll('.zoom button').forEach(function(o){"
            + "      o.classList.toggle('on',o===b);});});});"
            + "document.querySelectorAll('.stack .clip').forEach(function(clip){"
            + "  var stack=clip.parentNode;"
            + "  function move(e){"
            + "    var r=stack.getBoundingClientRect();"
            + "    var ratio=(e.clientX-r.left)/r.width;"
            + "    clip.style.width=(Math.min(1,Math.max(0,ratio))*100)+'%';}"
            + "  stack.addEventListener('pointerdown',function(e){"
            + "    stack.setPointerCapture(e.pointerId);move(e);});"
            + "  stack.addEventListener('pointermove',function(e){"
            + "    if(e.buttons)move(e);});"
            + "});"
            + "function sortBy(table,index,asc){"
            + "  var body=table.tBodies[0];"
            + "  var rows=Array.prototype.slice.call(body.rows);"
            + "  var key=function(row){"
            + "    var text=row.cells[index]?row.cells[index].textContent.trim():'';"
            + "    var num=parseFloat(text.replace('%',''));"
            + "    return isNaN(num)?text.toLowerCase():num;};"
            + "  rows.sort(function(a,b){var x=key(a),y=key(b);"
            + "    return x<y?(asc?-1:1):x>y?(asc?1:-1):0;});"
            + "  rows.forEach(function(r){body.appendChild(r);});}"
            + "document.querySelectorAll('table').forEach(function(table){"
            + "  table.querySelectorAll('th').forEach(function(th,index){"
            + "    var asc=true;"
            + "    th.addEventListener('click',function(){asc=!asc;sortBy(table,index,asc);});});});";
    }
}
