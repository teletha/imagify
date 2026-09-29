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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Assumptions;

import imagify.avif.AvifException;
import imagify.avif.jna.AvifCodec;
import imagify.avif.jna.AvifLibrary;
import imagify.webp.WebpCodec;
import imagify.webp.WebpException;

/**
 * Converts every PNG under {@code src/test/resources/png} to AVIF and WebP at a range
 * of quality settings, measures what each setting costs in bytes and what it gives up in
 * fidelity, and writes the comparison to an HTML report.
 *
 * <p>The report lands in {@code target/test-output/report/index.html} with the encoded
 * files and their decoded results next to it, so the numbers and the pixels can be looked
 * at together.
 */
class FormatComparison {

    private static final String PNG_DIR = "src/test/resources/png";

    private static final String REPORT_DIR = "target/test-output/report";

    /** Quality settings to compare. AVIF and WebP take 0-100. */
    private static final int[] QUALITIES = {10, 20, 30, 40, 50, 60, 70, 80, 90};

    /** The library default: slow enough to be fair, fast enough to keep the test short. */
    private static final int AVIF_SPEED = AvifLibrary.DEFAULT_SPEED;

    private static final String AVIF = "avif";

    private static final String WEBP = "webp";

    private static final String WEBP_LOSSLESS = "webp-lossless";

    /** One source image together with every encoding made from it. */
    private record Sample(String id, String name, int width, int height, int sourceBytes, boolean opaque, List<Variant> variants,
            Map<WebpWay, List<Numbers>> webp) {
    }

    /** One encoding of one image, with the measurements that describe it. */
    private record Variant(String format, int quality, int bytes, double psnr, double ssim, long encodeNanos, long decodeNanos, Path encoded, Path decoded) {

        String label() {
            return WEBP_LOSSLESS.equals(format) ? "webp lossless" : format + " q" + quality;
        }
    }

    /** What one WebP encoding cost and gave up, on whichever backend produced it. */
    private record Numbers(int bytes, double psnr, double ssim, long encodeNanos, long decodeNanos) {
    }

    /** One WebP encoding: the variant the report keeps, and what every available backend made of it. */
    private record Webp(Variant variant, Map<WebpWay, Numbers> numbers) {
    }

    /**
     * The two ways this library reaches {@code libwebp}.
     *
     * <p>The report and its assertions follow the backend the library settled on, so the numbers in
     * the report stay the ones {@code ImageIO} produces. Every other available one is run alongside
     * and only logged, to say what choosing it would have cost on the same image at the same setting.
     */
    private enum WebpWay {

        FFM(WebpCodec.Backend.FFM), WEBP4J(WebpCodec.Backend.WEBP4J);

        private final WebpCodec.Backend backend;

        WebpWay(WebpCodec.Backend backend) {
            this.backend = backend;
        }

        boolean isAvailable() {
            return backend.isAvailable();
        }

        String title() {
            return backend.title();
        }

        String reason() {
            return backend.getUnavailableReason();
        }

        byte[] encode(BufferedImage image, int quality, boolean lossless, int method) throws WebpException {
            return switch (this) {
            case FFM -> imagify.webp.ffm.WebpCodec.encode(image, quality, lossless, method);
            case WEBP4J -> imagify.webp.webp4j.WebpCodec.encode(image, quality, lossless, method);
            };
        }

        BufferedImage decode(byte[] encoded) throws WebpException {
            return switch (this) {
            case FFM -> imagify.webp.ffm.WebpCodec.decode(encoded);
            case WEBP4J -> imagify.webp.webp4j.WebpCodec.decode(encoded);
            };
        }
    }

    public static void main(String[] args) throws Exception {
        Assumptions.assumeTrue(AvifCodec.isAvailable(), () -> "libavif is not available: " + AvifCodec.getUnavailableReason());
        Assumptions.assumeTrue(WebpCodec.isAvailable(), () -> "libwebp is not available: " + WebpCodec.getUnavailableReason());

        Path pngDir = Paths.get(PNG_DIR);
        Assumptions.assumeTrue(Files.isDirectory(pngDir), () -> PNG_DIR + " does not exist");

        List<Path> pngs = new ArrayList<>();
        Files.walk(pngDir).filter(p -> p.toString().endsWith(".png")).sorted().forEach(pngs::add);
        Assumptions.assumeFalse(pngs.isEmpty(), () -> "no PNG files in " + PNG_DIR);

        Path reportDir = Paths.get(REPORT_DIR);
        Files.createDirectories(reportDir);

        WebpWay selected = selected();
        System.out.println("webp backends: " + describeWays());
        System.out.println("  the report follows " + selected.title() + ", marked * below; every other available one runs alongside and is only logged");
        System.out.printf("  %-1s %-8s %10s %9s %9s %9s %8s%n", "", "backend", "bytes", "encode", "decode", "PSNR", "SSIM");

        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < pngs.size(); i++) {
            samples.add(convert(pngs.get(i), String.format("img%02d", i + 1), reportDir, selected));
        }

        Path html = reportDir.resolve("index.html");
        Files.write(html, report(samples).getBytes("UTF-8"));

        assertTrue(Files.size(html) > 2048, "the report looks truncated");
        for (Sample sample : samples) {
            assertEquals(QUALITIES.length * 2 + 1, sample.variants()
                    .size(), sample.name() + " should carry one variant per format and quality");
            for (Variant variant : sample.variants()) {
                assertTrue(variant.bytes() > 0, "empty " + variant.label());
                assertTrue(variant.psnr() > 0, "PSNR out of range for " + variant.label());
                assertTrue(variant.ssim() >= -1 && variant.ssim() <= 1, "SSIM out of range for " + variant.label() + ": " + variant.ssim());
                assertEquals(variant.bytes(), Files
                        .size(variant.encoded()), "the report disagrees with the file it measured: " + variant.label());
            }
        }
        System.out.println("report → " + html.toAbsolutePath());
        System.out.printf("  %-16s %-8s %10s %8s %9s %8s %10s%n", "format", "quality", "bytes", "vs PNG", "PSNR", "SSIM", "encode");
        for (String[] row : summaryRows(samples)) {
            System.out.printf("  %-16s %-8s %10s %8s %9s %8s %10s%n", row[0], row[1], row[2], row[3], row[4], row[5], row[6]);
        }
        appendWebpTotals(samples, selected);
    }

    // ------------------------------------------------------------------ conversion

    private static Sample convert(Path png, String id, Path reportDir, WebpWay selected) throws IOException, AvifException, WebpException {
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
            variants.add(measure(AVIF, quality, encoded, decoded, reference, width, height, encodeNanos, decodeNanos, file));
        }

        // Every WebP encoding goes through every available backend, so the two can be read side by
        // side on the same image at the same setting. Only the selected one reaches the report.
        Map<WebpWay, List<Numbers>> log = new EnumMap<>(WebpWay.class);
        for (int quality : QUALITIES) {
            Webp webp = webp(id, image, reference, width, height, webpDir.resolve(id + ".q" + quality + ".webp"), WEBP, quality, false, selected);
            collect(webp, log);
            variants.add(webp.variant());
        }

        // WebP can be lossless, so it gives a useful upper bound on what fidelity costs.
        Webp webp = webp(id, image, reference, width, height, webpDir.resolve(id + ".lossless.webp"), WEBP_LOSSLESS, 0, true, selected);
        collect(webp, log);
        variants.add(webp.variant());

        return new Sample(id, png.getFileName().toString(), width, height, sourceBytes, opaque, variants, log);
    }

    /**
     * Encodes and decodes one WebP setting through every available backend and logs what each one
     * made of it. Both backends receive the same quality / lossless / method arguments so the
     * comparison is fair; webp4j is skipped for lossless because its lossless path does not
     * produce a true lossless result on all images (probe: round-trip pixel mismatches on the
     * test PNGs), so only FFM is asked for that setting.
     */
    private static Webp webp(String id, BufferedImage image, int[] reference, int width, int height, Path file, String format, int quality, boolean lossless, WebpWay selected)
            throws IOException, WebpException {
        Map<WebpWay, Numbers> numbers = new EnumMap<>(WebpWay.class);
        Variant variant = null;

        for (WebpWay way : WebpWay.values()) {
            if (!way.isAvailable()) {
                continue;
            }
            if (lossless && way == WebpWay.WEBP4J) {
                System.out.printf("  %-1s %-8s skipped (lossless: webp4j does not produce a true lossless result on all images)%n", way == selected ? "*" : "", way.title());
                continue;
            }

            long start = System.nanoTime();
            byte[] encoded = way.encode(image, quality, lossless, WebpCodec.DEFAULT_COMPRESSION_METHOD);
            long encodeNanos = System.nanoTime() - start;

            start = System.nanoTime();
            BufferedImage decoded = way.decode(encoded);
            long decodeNanos = System.nanoTime() - start;

            numbers.put(way, measure(encoded, decoded, reference, width, height, encodeNanos, decodeNanos));
            if (way == selected) {
                Files.write(file, encoded);
                variant = measure(format, quality, encoded, decoded, reference, width, height, encodeNanos, decodeNanos, file);
            }
        }

        assertNotNull(variant, "no available backend produced " + format + " for " + id);
        System.out.printf("  %s %s q%s%n", format, id, lossless ? "lossless" : quality);
        for (WebpWay way : WebpWay.values()) {
            Numbers each = numbers.get(way);
            if (each != null) {
                System.out.printf("  %-1s %-8s %10s %9s %9s %9s %8s%n", way == selected ? "*" : "", way.title(), group(each.bytes()),
                        millis(each.encodeNanos()), millis(each.decodeNanos()), String.format("%.1f", each.psnr()), String.format("%.4f", each.ssim()));
            }
        }
        return new Webp(variant, numbers);
    }

    /** Files one encoding's numbers under the backend that produced them. */
    private static void collect(Webp webp, Map<WebpWay, List<Numbers>> log) {
        for (Map.Entry<WebpWay, Numbers> entry : webp.numbers().entrySet()) {
            log.computeIfAbsent(entry.getKey(), way -> new ArrayList<>()).add(entry.getValue());
        }
    }

    /** @return the backend the report and its assertions follow */
    private static WebpWay selected() {
        return WebpCodec.backend() == WebpCodec.Backend.WEBP4J ? WebpWay.WEBP4J : WebpWay.FFM;
    }

    /** @return every backend and whether it can run here, for the line above the conversions */
    private static String describeWays() {
        StringBuilder text = new StringBuilder();
        for (WebpWay way : WebpWay.values()) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(way.title());
            if (!way.isAvailable()) {
                text.append(" (unavailable: ").append(way.reason()).append(")");
            }
        }
        return text.toString();
    }

    private static Variant measure(String format, int quality, byte[] encoded, BufferedImage decoded, int[] reference, int width, int height, long encodeNanos, long decodeNanos, Path file)
            throws IOException {
        // The decoded pixels are written out as PNG so the browser shows exactly what the
        // codec produced, no matter how it feels about rendering AVIF and WebP itself.
        Path decodedPng = file.resolveSibling(file.getFileName() + ".decoded.png");
        ImageIO.write(decoded, "png", decodedPng.toFile());

        Numbers numbers = measure(encoded, decoded, reference, width, height, encodeNanos, decodeNanos);
        return new Variant(format, quality, numbers.bytes(), numbers.psnr(), numbers.ssim(), numbers.encodeNanos(), numbers.decodeNanos(), file, decodedPng);
    }

    private static Numbers measure(byte[] encoded, BufferedImage decoded, int[] reference, int width, int height, long encodeNanos, long decodeNanos) {
        int[] actual = argb(decoded);
        assertEquals(reference.length, actual.length, "the round trip changed the image size");
        return new Numbers(encoded.length, psnr(reference, actual), ssim(reference, actual, width, height), encodeNanos, decodeNanos);
    }

    // ------------------------------------------------------------------ measurement

    /** Flattens a potentially transparent image to opaque by compositing on white. */
    private static BufferedImage flattenToOpaque(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage opaque = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        var g = opaque.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return opaque;
    }

    private static int[] argb(BufferedImage image) {
        return ImageMetrics.argb(image);
    }

    private static boolean isOpaque(int[] pixels) {
        return ImageMetrics.isOpaque(pixels);
    }

    private static double psnr(int[] reference, int[] actual) {
        return ImageMetrics.psnr(reference, actual);
    }

    private static double ssim(int[] reference, int[] actual, int width, int height) {
        return ImageMetrics.ssim(reference, actual, width, height);
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
        return new String[] {WEBP_LOSSLESS.equals(format) ? "webp lossless" : format,
                WEBP_LOSSLESS.equals(format) ? "-" : String.valueOf(quality), group(bytes), String.format("%.1f%%", 100.0 * bytes / source),
                String.format("%.1f", psnrSum / count), String.format("%.4f", ssimSum / count),
                String.format("%.0f ms", encodeNanos / 1_000_000.0),};
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

    private static String millis(long nanos) {
        return String.format("%.0f ms", nanos / 1_000_000.0);
    }

    /**
     * Totals per backend over every WebP encoding of the run, so the two ways can be compared
     * without reading one line per image and setting.
     */
    private static void appendWebpTotals(List<Sample> samples, WebpWay selected) {
        System.out.println("webp, both ways, over every image and setting:");
        System.out.printf("  %-1s %-8s %10s %8s %9s %8s %10s%n", "", "backend", "bytes", "vs PNG", "PSNR", "SSIM", "encode");
        for (WebpWay way : WebpWay.values()) {
            long bytes = 0;
            long source = 0;
            long encodeNanos = 0;
            double psnrSum = 0;
            double ssimSum = 0;
            int count = 0;
            for (Sample sample : samples) {
                source += sample.sourceBytes();
                for (Numbers numbers : sample.webp().getOrDefault(way, List.of())) {
                    bytes += numbers.bytes();
                    encodeNanos += numbers.encodeNanos();
                    psnrSum += numbers.psnr();
                    ssimSum += numbers.ssim();
                    count++;
                }
            }
            if (count > 0) {
                System.out.printf("  %-1s %-8s %10s %8s %9s %8s %10s%n", way == selected ? "*" : "", way.title(), group(bytes),
                        String.format("%.1f%%", 100.0 * bytes / source), String.format("%.1f", psnrSum / count), String.format("%.4f", ssimSum / count),
                        millis(encodeNanos));
            }
        }
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
                .append("<title>AVIF / WebP Quality and Size Comparison</title>\n")
                .append("<style>\n")
                .append(ReportAssets.css())
                .append(extraCss())
                .append("\n</style>\n</head>\n<body>\n");

        html.append("<h1>AVIF / WebP Quality and Size Comparison</h1>\n")
                .append("<p class=\"lead\">")
                .append(samples.size())
                .append(" images (total ")
                .append(group(source))
                .append(") converted to libavif ")
                .append(AvifCodec.getVersion())
                .append(", and WebP, comparing the size and quality metrics per quality setting." + "Quality metrics are calculated against the result of re-decoding the original image.</p>\n");

        appendSummary(html, samples);
        appendDetail(html, samples);

        html.append("<h2>3. Comparison</h2>\n").append("<p class=\"note\">Show conversion results per image. Images are displayed with a max width of 480px.</p>\n");

        for (Sample sample : samples) {
            String ratio = sample.width() + "/" + sample.height();
            String size = "--w:" + sample.width() + "px;--ar:" + ratio;
            String originalPath = relative("original/" + sample.id() + ".png");
            html.append("<div class=\"sample\" id=\"")
                    .append(sample.id())
                    .append("\">\n<h3>")
                    .append(escape(sample.name()))
                    .append("</h3>\n");

            // Original image
            html.append("<div class=\"grid\">\n")
                    .append("<figure class=\"cmp\" style=\"")
                    .append(size)
                    .append("\">")
                    .append("<img src=\"")
                    .append(escape(originalPath))
                    .append("\" alt=\"")
                    .append(escape(sample.name()))
                    .append("\" loading=\"lazy\">")
                    .append("<figcaption>original · ")
                    .append(group(sample.sourceBytes()))
                    .append(" · ")
                    .append(sample.width())
                    .append("×")
                    .append(sample.height())
                    .append("</figcaption></figure>\n")
                    .append("</div>\n");

            // Per-format comparison grids
            for (String format : new String[] {AVIF, WEBP}) {
                html.append("<h4>").append(format.toUpperCase()).append("</h4>\n<div class=\"grid\">\n");
                for (Variant variant : sample.variants()) {
                    // Include WebP lossless in the WebP section
                    if (WEBP.equals(format) && WEBP_LOSSLESS.equals(variant.format())) {
                        // include
                    } else if (!variant.format().equals(format)) {
                        continue;
                    }
                    html.append("<figure class=\"cmp\" style=\"")
                            .append(size)
                            .append("\">")
                            .append("<img src=\"")
                            .append(escape(relative(variant.decoded().toString())))
                            .append("\" alt=\"")
                            .append(escape(variant.label()))
                            .append("\" loading=\"lazy\">")
                            .append("<figcaption>")
                            .append(escape(variant.label()))
                            .append(" · ")
                            .append(group(variant.bytes()))
                            .append(" · SSIM ")
                            .append(String.format("%.4f", variant.ssim()))
                            .append("</figcaption></figure>\n");
                }
                html.append("</div>\n");
            }

            html.append("</div>\n");
        }

        return html.append("<script>\n").append(ReportAssets.js()).append("\n</script>\n</body>\n</html>\n").toString();
    }

    /** Extra CSS for this report. */
    private static String extraCss() {
        return "" + ":root{--zoom:1}" + ".stack{user-select:none;-webkit-user-select:none}" + ".cmp img{max-width:480px;height:auto;display:block}" + "@media(prefers-color-scheme:dark){" + ".cmp img{background:repeating-conic-gradient(#161b22 0 25%,#0d1117 0 50%) 0 0/16px 16px}}";
    }

    private static void appendSummary(StringBuilder html, List<Sample> samples) {
        html.append("<h2>1. Summary</h2>\n")
                .append("<p class=\"note\">Total across all images. The closer SSIM is to 1, the more similar to the original.</p>\n")
                .append("<table>\n<thead><tr><th>format</th><th>quality</th><th>total size</th>")
                .append("<th>vs PNG</th><th>PSNR (dB)</th><th>SSIM</th><th>encode</th>")
                .append("</tr></thead>\n<tbody>\n");
        for (String[] row : summaryRows(samples)) {
            html.append("<tr><td>")
                    .append(escape(row[0]))
                    .append("</td><td>")
                    .append(escape(row[1]))
                    .append("</td><td class=\"num\">")
                    .append(escape(row[2]))
                    .append("</td><td class=\"num\">")
                    .append(escape(row[3]))
                    .append("</td><td class=\"num\">")
                    .append(escape(row[4]))
                    .append("</td><td class=\"num\">")
                    .append(escape(row[5]))
                    .append("</td><td class=\"num\">")
                    .append(escape(row[6]))
                    .append("</td></tr>\n");
        }
        html.append("</tbody>\n</table>\n");
    }

    private static void appendDetail(StringBuilder html, List<Sample> samples) {
        html.append("<h2>2. Detail</h2>\n").append("<p class=\"note\">Click a heading to sort." + "(brackets show compression ratio and SSIM vs PNG).</p>\n");

        // Separate table per format
        for (String format : new String[] {AVIF, WEBP}) {
            String displayName = format.toUpperCase();
            html.append("<h3>").append(displayName).append("</h3>\n").append("<table>\n<thead><tr><th>image</th><th>size</th><th>PNG</th>");
            for (int quality : QUALITIES) {
                html.append("<th>q").append(quality).append("</th>");
            }
            html.append("</tr></thead>\n<tbody>\n");

            for (Sample sample : samples) {
                html.append("<tr><td class=\"name\"><a href=\"#")
                        .append(sample.id())
                        .append("\">")
                        .append(escape(sample.name()))
                        .append("</a>")
                        .append(sample.opaque() ? "" : " <span class=\"tag\">alpha</span>")
                        .append("</td><td class=\"num\">")
                        .append(sample.width())
                        .append("×")
                        .append(sample.height())
                        .append("</td><td class=\"num\">")
                        .append(group(sample.sourceBytes()))
                        .append("</td>");
                for (int quality : QUALITIES) {
                    html.append(cell(sample, format, quality));
                }
                html.append("</tr>\n");
            }
            html.append("</tbody>\n</table>\n");
        }

        // WebP lossless as a separate section
        html.append("<h3>WebP Lossless</h3>\n")
                .append("<table>\n<thead><tr><th>image</th><th>size</th><th>PNG</th><th>webp lossless</th></tr></thead>\n<tbody>\n");
        for (Sample sample : samples) {
            html.append("<tr><td class=\"name\"><a href=\"#")
                    .append(sample.id())
                    .append("\">")
                    .append(escape(sample.name()))
                    .append("</a>")
                    .append(sample.opaque() ? "" : " <span class=\"tag\">alpha</span>")
                    .append("</td><td class=\"num\">")
                    .append(sample.width())
                    .append("×")
                    .append(sample.height())
                    .append("</td><td class=\"num\">")
                    .append(group(sample.sourceBytes()))
                    .append("</td>")
                    .append(cell(sample, WEBP_LOSSLESS, 0))
                    .append("</tr>\n");
        }
        html.append("</tbody>\n</table>\n");
    }

    private static String cell(Sample sample, String format, int quality) {
        for (Variant variant : sample.variants()) {
            if (variant.format().equals(format) && variant.quality() == quality) {
                return "<td class=\"num\">" + group(variant.bytes()) + "<small>" + String
                        .format("%.0f%%", 100.0 * variant.bytes() / sample.sourceBytes()) + " (" + String
                                .format("%.3f", variant.ssim()) + ")</small></td>";
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
}
