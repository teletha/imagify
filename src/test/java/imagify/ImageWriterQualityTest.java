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
import imagify.webp.jna.WebpCodec;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that the {@code quality} argument of {@link ImageWriter} reaches the encoder, and that
 * every format including AVIF is served by its registered ImageIO plug-in rather than by a
 * hand written code path.
 *
 * <p>Both used to be untrue. {@code quality} was accepted and then dropped on the floor, so every
 * call produced the very same bytes, and AVIF went through reflection into a class that has no
 * no-argument constructor, which made {@code IOException: Failed to read AVIF} and
 * {@code IOException: Failed to write AVIF} the only two outcomes an AVIF user could reach.</p>
 */
class ImageWriterQualityTest {

    /** The formats whose encoders spend real effort on the quality they are given. */
    private static final ImageFormat[] LOSSY = {ImageFormat.JPEG, ImageFormat.WEBP, ImageFormat.AVIF};

    /**
     * The formats that cannot lose anything: GIF and BMP advertise compression types yet refuse an
     * explicit mode, and PNG's writer reads the quality as a deflate level, so for all three the
     * argument has nothing to spend itself on. What a PNG spends its effort on instead is a setting
     * of the format, {@link ImageFormat.Png#compressionLevel(int)}, tested in
     * {@link ImageFormatPngTest}.
     */
    private static final ImageFormat[] NO_AXIS = {ImageFormat.GIF, ImageFormat.BMP, ImageFormat.PNG};

    @Test
    @DisplayName("a lossy format gets smaller as the quality drops")
    void lossyFollowsQuality() throws IOException {
        assumeLibs();
        BufferedImage image = sample(120, 90);

        for (ImageFormat format : LOSSY) {
            byte[] low = ImageWriter.toBytes(image, format, 0.01);
            byte[] mid = ImageWriter.toBytes(image, format, 0.50);
            byte[] high = ImageWriter.toBytes(image, format, 0.99);

            assertTrue(low.length < mid.length,
                    format + ": 0.01 (" + low.length + ") should be smaller than 0.50 (" + mid.length + ")");
            assertTrue(mid.length < high.length,
                    format + ": 0.50 (" + mid.length + ") should be smaller than 0.99 (" + high.length + ")");
            assertTrue(low.length * 2 < high.length,
                    format + ": quality should matter, but 0.01 gave " + low.length
                            + " against " + high.length + " at 0.99");
        }
    }

    @Test
    @DisplayName("the default quality lands between the extremes")
    void defaultSitsBetweenTheExtremes() throws IOException {
        assumeLibs();
        BufferedImage image = sample(120, 90);

        for (ImageFormat format : LOSSY) {
            int low = ImageWriter.toBytes(image, format, 0.01).length;
            int high = ImageWriter.toBytes(image, format, 0.99).length;
            int byDefault = ImageWriter.toBytes(image, format).length;

            assertTrue(byDefault > low && byDefault < high,
                    format + ": the default of " + format.getDefaultQuality() + " gave " + byDefault
                            + ", which is not between " + low + " and " + high);
        }
    }

    @Test
    @DisplayName("a format without a quality axis still writes, and ignores the value")
    void noQualityAxisIsNotAFailure() throws IOException {
        BufferedImage image = sample(120, 90);

        for (ImageFormat format : NO_AXIS) {
            byte[] low = ImageWriter.toBytes(image, format, 0.01);
            byte[] high = ImageWriter.toBytes(image, format, 0.99);
            assertTrue(low.length > 0, format + ": nothing was written at 0.01");
            assertArrayEquals(low, high, format + " has no quality axis, so both should be identical");
        }
    }

    @Test
    @DisplayName("a quality outside 0.0 to 1.0 is refused rather than silently clamped")
    void rejectsOutOfRangeQuality() {
        BufferedImage image = sample(16, 16);
        for (double bad : new double[] {-0.01, 1.01, 50.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            for (ImageFormat format : new ImageFormat[] {ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.BMP}) {
                assertThrows(IllegalArgumentException.class,
                        () -> ImageWriter.toBytes(image, format, bad),
                        format + " accepted the quality " + bad);
            }
        }
    }

    @Test
    @DisplayName("the two ends of the range are accepted")
    void acceptsTheEndsOfTheRange() throws IOException {
        BufferedImage image = sample(48, 32);
        for (double edge : new double[] {0.0, 1.0}) {
            assertTrue(ImageWriter.toBytes(image, ImageFormat.JPEG, edge).length > 0);
        }
    }

    @Test
    @DisplayName("toStream carries the quality through to the encoder")
    void streamFollowsQuality() throws IOException {
        assumeLibs();
        BufferedImage image = sample(120, 90);

        for (ImageFormat format : LOSSY) {
            assertTrue(encoded(image, format, 0.01).length
                            < encoded(image, format, 0.99).length,
                    format + ": the stream overload dropped the quality");
        }
    }

    @Test
    @DisplayName("toFile carries the quality through to the encoder, format given or inferred")
    void fileFollowsQuality(@TempDir Path directory) throws IOException {
        assumeLibs();
        BufferedImage image = sample(120, 90);

        for (ImageFormat format : LOSSY) {
            Path low = directory.resolve("low." + format.getExtension());
            Path high = directory.resolve("high." + format.getExtension());

            ImageWriter.toFile(image, format, 0.01, low);
            ImageWriter.toFile(image, format, 0.99, high);
            assertTrue(Files.size(low) < Files.size(high),
                    format + ": toFile ignored the quality, " + Files.size(low) + " against " + Files.size(high));

            // The extension is enough, so the caller does not have to repeat the format.
            Path inferred = directory.resolve("inferred." + format.getExtension());
            ImageWriter.toFile(image, inferred, 0.01);
            assertEquals(Files.size(low), Files.size(inferred),
                    format + ": the path overload encoded differently from the format overload");
        }
    }

    @Test
    @DisplayName("AVIF round trips through the facade, which used to be impossible")
    void avifRoundTripsThroughTheFacade() throws IOException {
        assumeTrue(AvifCodec.isAvailable(),
                () -> "libavif is not available: " + AvifCodec.getUnavailableReason());
        BufferedImage image = sample(64, 48);

        byte[] avif = ImageWriter.toBytes(image, ImageFormat.AVIF, 0.8);
        assertTrue(AvifCodec.isAvif(avif), "the output is not an AVIF file");

        BufferedImage back = ImageReader.read(avif, ImageFormat.AVIF).toBufferedImage();
        assertEquals(64, back.getWidth());
        assertEquals(48, back.getHeight());
    }

    @Test
    @DisplayName("AVIF is recognised from its ftyp box when the format is not stated")
    void detectsAvifFromItsHeader() throws IOException {
        assumeTrue(AvifCodec.isAvailable(),
                () -> "libavif is not available: " + AvifCodec.getUnavailableReason());
        byte[] avif = ImageWriter.toBytes(sample(40, 30), ImageFormat.AVIF, 0.8);

        assertEquals(ImageFormat.AVIF, ImageFormat.detect(avif));
        assertEquals(40, ImageReader.read(avif).toBufferedImage().getWidth());
    }

    @Test
    @DisplayName("both AVIF brands are recognised from a bare ftyp box, and no other ftyp box is")
    void detectsAvifBrandsFromTheHeaderAlone() {
        assertEquals(ImageFormat.AVIF, ImageFormat.detect(ftypBox("avif", "mif1", "miaf")));
        assertEquals(ImageFormat.AVIF, ImageFormat.detect(ftypBox("avis", "avif", "avis")));

        // Heic, and the lookalike brands that are not AVIF, must not be mistaken for it.
        for (String brand : new String[] {"heic", "mif1", "mavi", "avii", "avfi"}) {
            assertNull(ImageFormat.detect(ftypBox(brand, "mif1", "miaf")),
                    "the brand \"" + brand + "\" was taken for AVIF");
        }
    }

    @Test
    @DisplayName("a .avif path is read and written without being told the format")
    void avifThroughAPath(@TempDir Path directory) throws IOException {
        assumeTrue(AvifCodec.isAvailable(),
                () -> "libavif is not available: " + AvifCodec.getUnavailableReason());
        Path file = directory.resolve("out.avif");

        ImageWriter.toFile(sample(52, 26), file);
        assertTrue(Files.size(file) > 0, "nothing was written to the file");

        BufferedImage back = ImageReader.read(file).toBufferedImage();
        assertEquals(52, back.getWidth());
        assertEquals(26, back.getHeight());
    }

    @Test
    @DisplayName("every format survives a write and a read through the public API")
    void everyFormatRoundTrips() throws IOException {
        assumeLibs();
        BufferedImage image = sample(64, 48);

        for (ImageFormat format : all()) {
            byte[] encoded = ImageWriter.toBytes(image, format, 0.8);
            assertTrue(encoded.length > 0, format + ": nothing was written");

            BufferedImage explicit = ImageReader.read(encoded, format).toBufferedImage();
            assertEquals(64, explicit.getWidth(), format + ": wrong width with the format stated");
            assertEquals(48, explicit.getHeight(), format + ": wrong height with the format stated");

            BufferedImage detected = ImageReader.read(encoded).toBufferedImage();
            assertEquals(64, detected.getWidth(), format + ": wrong width with the format detected");
            assertEquals(48, detected.getHeight(), format + ": wrong height with the format detected");
        }
    }

    @Test
    @DisplayName("every format reports a name the ImageIO registry knows a writer under")
    void formatNamesAreTheRegistryNames() {
        for (ImageFormat format : all()) {
            assertTrue(javax.imageio.ImageIO.getImageWritersByFormatName(format.getFormatName()).hasNext(),
                    "ImageIO has no writer registered as \"" + format.getFormatName()
                            + "\", the name ImageFormat." + format + " reports");
        }
    }

    @Test
    @DisplayName("GIF is written as a GIF file, and either header revision is read back as one format")
    void gifIsWrittenAndReadBackAsGif() throws IOException {
        BufferedImage image = sample(48, 32);
        byte[] written = ImageWriter.toBytes(image, ImageFormat.GIF, 0.8);

        // The JDK writer emits the 89a revision, because it always needs the graphic control
        // extension to say a frame is transparent, and that extension is the whole of the
        // difference between the two revisions. So a GIF is written as an 89a whichever is asked
        // for, and either revision in a header is this one format.
        assertEquals("GIF89a", new String(written, 0, 6, StandardCharsets.US_ASCII), "not a GIF file");
        assertEquals(ImageFormat.GIF, ImageFormat.fromHeader("GIF87a".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(ImageFormat.GIF, ImageFormat.fromHeader("GIF89a".getBytes(StandardCharsets.US_ASCII)));
    }

    // ------------------------------------------------------------------------------ helpers

    /** @return the first twelve bytes of a minimal ISO base media file with the given brands */
    private static byte[] ftypBox(String majorBrand, String... compatibleBrands) {
        byte[] box = new byte[12 + 8 * compatibleBrands.length];
        box[3] = (byte) box.length;
        System.arraycopy("ftyp".getBytes(StandardCharsets.US_ASCII), 0, box, 4, 4);
        System.arraycopy(majorBrand.getBytes(StandardCharsets.US_ASCII), 0, box, 8, 4);
        for (int i = 0; i < compatibleBrands.length; i++) {
            System.arraycopy(compatibleBrands[i].getBytes(StandardCharsets.US_ASCII), 0,
                    box, 12 + 8 * i, 4);
        }
        return box;
    }

    private static ImageFormat[] all() {
        return new ImageFormat[] {ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.GIF,
                ImageFormat.BMP, ImageFormat.WEBP, ImageFormat.AVIF};
    }

    private static void assumeLibs() {
        assumeTrue(WebpCodec.isAvailable(),
                () -> "libwebp is not available: " + WebpCodec.getUnavailableReason());
        assumeTrue(AvifCodec.isAvailable(),
                () -> "libavif is not available: " + AvifCodec.getUnavailableReason());
    }

    private static byte[] encoded(BufferedImage image, ImageFormat format, double quality) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageWriter.toStream(image, format, quality, bytes);
        return bytes.toByteArray();
    }

    /**
     * A gradient with a checkerboard laid over it, so that a lossy encoder has something to
     * actually throw away and the encoded size responds to the quality it is given.
     */
    private static BufferedImage sample(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xFF000000
                        | (x * 255 / (width - 1)) << 16
                        | (y * 255 / (height - 1)) << 8
                        | ((x + y) % 2 == 0 ? 200 : 40));
            }
        }
        return image;
    }
}
