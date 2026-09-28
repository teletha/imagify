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
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import imagify.ImageFormat.Jpeg.Subsampling;
import imagify.jpeg.jna.JpegliCodec;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link ImageFormat#JPEG} carries the two encoder settings it offers, through
 * {@link ImageWriter} and {@link ImageReader} rather than through the JDK writer directly.
 *
 * <p>Both are worth having separately from the writer's own tests, for different reasons. The
 * colour-difference resolution is not an {@link javax.imageio.ImageWriteParam} at all but image
 * metadata, so a write that answers {@code null} for its metadata cannot carry it. The entropy
 * coder tables are the opposite: they are on the write param, but only on the JDK's own JPEG
 * subclass of it, so they are unreachable unless a caller is handed the concrete class.
 * </p>
 */
class ImageFormatJpegTest {

    /** The quality {@link ImageFormat#JPEG} asks for when the caller does not say. */
    private static final double DEFAULT_QUALITY = 0.85;

    @Test
    @DisplayName("the subsampling the format asks for reaches the frame header")
    void theSubsamplingReachesTheFrameHeader() throws IOException {
        BufferedImage source = gradient(96, 96);

        for (Subsampling subsampling : Subsampling.values()) {
            byte[] jpeg = ImageWriter.toBytes(source,
                    ImageFormat.JPEG.subsampling(subsampling), DEFAULT_QUALITY);

            int[][] factors = frameHeaderSamplingFactors(jpeg);
            assertEquals(3, factors.length,
                    subsampling + ": a colour JPEG has three components in its frame header");
            assertArrayEquals(new int[] {subsampling.horizontalFactor, subsampling.verticalFactor},
                    factors[0], subsampling + ": the luma channel should carry the asked-for factors");
            // Only the luma channel may be subsampled; the two colour-difference channels follow it.
            for (int component = 1; component < factors.length; component++) {
                assertArrayEquals(new int[] {1, 1}, factors[component],
                        subsampling + ": component " + component + " should not be subsampled");
            }
        }
    }

    @Test
    @DisplayName("more colour detail is a bigger file, and it is the colour detail that is kept")
    void moreSubsamplingKeepsMoreOfThePicture() throws IOException {
        BufferedImage source = gradient(96, 96);

        byte[] smallest = ImageWriter.toBytes(source, ImageFormat.JPEG.subsampling(Subsampling.S420),
                DEFAULT_QUALITY);
        byte[] middle = ImageWriter.toBytes(source, ImageFormat.JPEG.subsampling(Subsampling.S422),
                DEFAULT_QUALITY);
        byte[] largest = ImageWriter.toBytes(source, ImageFormat.JPEG.subsampling(Subsampling.S444),
                DEFAULT_QUALITY);

        assertTrue(smallest.length < middle.length && middle.length < largest.length,
                "the three should be three sizes, got " + smallest.length + ", " + middle.length
                        + " and " + largest.length);

        // Fidelity is measured on a picture whose detail is entirely in the two colour difference
        // channels, which is the only place a subsampling axis can show. On the diagonal gradient
        // above it cannot: luma carries most of the energy there, so discarding the chroma barely
        // moves the PSNR and the three files come out within a few hundredths of a dB of each other.
        //
        // What is deliberately not asserted is that the PSNR rises at every step from 4:2:0 to 4:4:4.
        // That is a libjpeg property, and jpegli is not libjpeg: it maps the quality argument to a
        // bit budget with its own rate control, so a 4:4:4 file spends the same budget over three
        // full resolution channels and can land fractionally below a 4:2:2 file. That the colour is
        // what is lost is the claim worth holding, and it holds by a wide margin.
        BufferedImage chroma = chromaDetail(96, 96);
        int[] chromaPixels = pixels(chroma);
        double by420 = ImageMetrics.psnr(chromaPixels, decodedPixels(ImageWriter.toBytes(chroma,
                ImageFormat.JPEG.subsampling(Subsampling.S420), DEFAULT_QUALITY)));
        double by444 = ImageMetrics.psnr(chromaPixels, decodedPixels(ImageWriter.toBytes(chroma,
                ImageFormat.JPEG.subsampling(Subsampling.S444), DEFAULT_QUALITY)));
        assertTrue(by444 - by420 > 3, String.format(
                "4:4:4 should keep colour detail that 4:2:0 averages away: %.2f dB at 4:2:0 against %.2f dB at 4:4:4",
                by420, by444));

        // And the other half of it, which is what would catch a luma factor being dropped along the
        // way: subsampling the chroma is not supposed to cost the luma anything at all. All three
        // land within a fraction of a dB of each other here, so the margin is a fraction of a dB too.
        BufferedImage luma = lumaDetail(96, 96);
        int[] lumaPixels = pixels(luma);
        double luma420 = ImageMetrics.psnr(lumaPixels, decodedPixels(ImageWriter.toBytes(luma,
                ImageFormat.JPEG.subsampling(Subsampling.S420), DEFAULT_QUALITY)));
        double luma444 = ImageMetrics.psnr(lumaPixels, decodedPixels(ImageWriter.toBytes(luma,
                ImageFormat.JPEG.subsampling(Subsampling.S444), DEFAULT_QUALITY)));
        assertTrue(Math.abs(luma420 - luma444) < 1, String.format(
                "luma detail is the same picture at either subsampling: %.2f dB at 4:2:0 against %.2f dB at 4:4:4",
                luma420, luma444));
    }

    /**
     * A picture with a flat luma channel and all of its detail in the two colour difference ones.
     *
     * <p>Red and blue move together and green does not move at all, so the luma stays put while the
     * chroma varies on an eight pixel period in both directions. The period is chosen to be long
     * enough that it survives the 8x8 block the coefficients are quantised in, and short enough that
     * a 2x2 average of the chroma flattens it.
     */
    private static BufferedImage chromaDetail(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double wave = Math.sin(2 * Math.PI * x / 8) * Math.sin(2 * Math.PI * y / 8);
                int red = (int) Math.round(128 + 100 * wave);
                int blue = (int) Math.round(128 - 100 * wave);
                image.setRGB(x, y, red << 16 | 128 << 8 | blue);
            }
        }
        return image;
    }

    /** The same eight pixel wave with all three channels moving together, so the luma carries it. */
    private static BufferedImage lumaDetail(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int grey = (int) Math.round(128 + 100 * Math.sin(2 * Math.PI * x / 8) * Math.sin(2 * Math.PI * y / 8));
                image.setRGB(x, y, grey << 16 | grey << 8 | grey);
            }
        }
        return image;
    }

    @Test
    @DisplayName("a plain JPEG format is the file the writer would have written on its own")
    void aPlainFormatIsTheOneTheWriterWouldHaveWritten() throws IOException {
        BufferedImage source = gradient(96, 96);

        // Asking for the settings by name is the same request as not asking for them at all, and
        // that holds whichever encoder ends up answering it.
        assertArrayEquals(ImageWriter.toBytes(source, ImageFormat.JPEG, DEFAULT_QUALITY),
                ImageWriter.toBytes(source,
                        ImageFormat.JPEG.subsampling(ImageFormat.Jpeg.DEFAULT_SUBSAMPLING)
                                .optimizeHuffmanTables(ImageFormat.Jpeg.DEFAULT_OPTIMIZE_HUFFMAN_TABLES),
                        DEFAULT_QUALITY),
                "asking for the defaults should be the same as not asking at all");

        // The default subsampling travels as no metadata at all, so the write is byte for byte the
        // one that was produced before there was anything to ask for. That is a claim about the JDK's
        // writer though, and this jar encodes JPEG itself out of jpegli whenever the native library
        // is there — at which point there is no JDK write left to be identical to, which is rather
        // the point of shipping it.
        assumeFalse(JpegliCodec.isAvailable(),
                "skipped: jpegli encodes JPEG directly, so there is no JDK write to be byte identical to");
        assertArrayEquals(throughImageIo(source, DEFAULT_QUALITY),
                ImageWriter.toBytes(source, ImageFormat.JPEG, DEFAULT_QUALITY),
                "a plain JPEG format should be encoded exactly as ImageIO encodes it");
    }

    @Test
    @DisplayName("the entropy coder tables the format asks for reach the encoder")
    void theOptimizedTablesReachTheEncoder() throws IOException {
        BufferedImage source = gradient(96, 96);

        byte[] standard = ImageWriter.toBytes(source, ImageFormat.JPEG, DEFAULT_QUALITY);
        byte[] optimised = ImageWriter.toBytes(source,
                ImageFormat.JPEG.optimizeHuffmanTables(true), DEFAULT_QUALITY);

        assertFalse(Arrays.equals(standard, optimised),
                "the same image at the same quality should not have produced the same file");
        assertTrue(optimised.length < standard.length, "the tables were computed from the image, so the file should be smaller: "
                + optimised.length + " bytes against " + standard.length);

        // Computing the tables changes how the coefficients are coded and nothing else, so the
        // picture has to come back the same. That is what makes this a free win rather than a
        // quality setting, and it is what the test holds the encoder to.
        assertArrayEquals(decodedPixels(standard), decodedPixels(optimised),
                "the same pixels should come back either way, only the file should differ");
    }

    @Test
    @DisplayName("a setting hands back a new value that says what it asks for")
    void theSettingsAreValues() {
        assertEquals(Subsampling.S420, ImageFormat.JPEG.subsampling,
                "4:2:0 is the resolution an encoder writes unless told otherwise");
        assertFalse(ImageFormat.JPEG.optimizeHuffmanTables,
                "the standard tables are the ones used unless the caller asks for the work");

        ImageFormat.Jpeg careful = ImageFormat.JPEG.subsampling(Subsampling.S444)
                .optimizeHuffmanTables(true);
        assertEquals(Subsampling.S444, careful.subsampling);
        assertTrue(careful.optimizeHuffmanTables);

        assertNotEquals(ImageFormat.JPEG, careful, "asking for something is a different request");
        assertNotSame(ImageFormat.JPEG, careful, "a value the caller owns cannot change the constant");
        assertEquals(careful, ImageFormat.JPEG.subsampling(Subsampling.S444).optimizeHuffmanTables(true),
                "two values asking for the same thing are equal");
        assertEquals(careful.hashCode(),
                ImageFormat.JPEG.subsampling(Subsampling.S444).optimizeHuffmanTables(true).hashCode());

        // A setting keeps the ones that came before it, whichever order they arrive in.
        assertEquals(careful, ImageFormat.JPEG.optimizeHuffmanTables(true).subsampling(Subsampling.S444));
        assertEquals(Subsampling.S420, ImageFormat.JPEG.optimizeHuffmanTables(true).subsampling,
                "and the setting that was not asked for is left where it was");

        assertThrows(IllegalArgumentException.class, () -> ImageFormat.JPEG.subsampling(null));
    }

    // ------------------------------------------------------------------------------ helpers

    /**
     * The sampling factors of each component in a JPEG frame header, luma first.
     *
     * <p>Read out of the file rather than out of the metadata that was written, because the
     * frame header is the thing the setting is supposed to reach and it is also the thing a
     * decoder reads.
     * </p>
     */
    private static int[][] frameHeaderSamplingFactors(byte[] jpeg) {
        for (int i = 2; i < jpeg.length - 1; i++) {
            if ((jpeg[i] & 0xFF) != 0xFF) {
                continue;
            }
            int marker = jpeg[i + 1] & 0xFF;
            boolean isAFrameHeader = marker >= 0xC0 && marker <= 0xCF
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (!isAFrameHeader) {
                continue;
            }
            int components = jpeg[i + 9] & 0xFF;
            int[][] factors = new int[components][2];
            for (int c = 0; c < components; c++) {
                // Each component is an identifier, a byte holding both sampling factors, and the
                // index of the quantisation table it uses.
                int sampling = jpeg[i + 11 + c * 3] & 0xFF;
                factors[c][0] = sampling >> 4;
                factors[c][1] = sampling & 0x0F;
            }
            return factors;
        }
        throw new AssertionError("the file has no frame header, so it is not a JPEG");
    }

    private static int[] decodedPixels(byte[] jpeg) throws IOException {
        return pixels(ImageReader.read(jpeg, ImageFormat.JPEG).toBufferedImage());
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    private static byte[] throughImageIo(BufferedImage source, double quality) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            // Ask ImageTypeSpecifier rather than the name alone so that the provider is one that has
            // already said it can encode this image. The name alone answers with every provider
            // registered under it, and this jar registers a JPEG provider of its own that declines
            // whenever its native library is missing, which is precisely not what is being compared
            // here: this asks what the JDK's own writer produces.
            javax.imageio.ImageWriter writer = ImageIO.getImageWriters(
                    ImageTypeSpecifier.createFromRenderedImage(source), "jpeg").next();
            try {
                writer.setOutput(output);
                javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality((float) quality);
                writer.write(null, new javax.imageio.IIOImage(source, null, null), param);
            } finally {
                writer.dispose();
            }
            output.flush();
        }
        return bytes.toByteArray();
    }

    /**
     * A gradient in all three colour channels, because that is what a chroma subsampling axis is
     * measured on: flat colour is the one thing subsampling cannot show.
     */
    private static BufferedImage gradient(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xFF000000
                        | (x * 255 / (width - 1)) << 16
                        | (y * 255 / (height - 1)) << 8
                        | ((x + y) * 255 / (width + height - 2)));
            }
        }
        return image;
    }
}
