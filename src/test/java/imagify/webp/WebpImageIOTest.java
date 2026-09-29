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
import static org.junit.jupiter.api.Assumptions.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import imagify.webp.ffm.WebpCodec;

/**
 * Tests the WebP {@code ImageIO} integration: service registration, format recognition, the reader,
 * the writer, the animation path and the metadata.
 *
 * <p>Everything that needs the native {@code libwebp} is skipped when it is missing, so the tests
 * that do not need it still run everywhere.
 */
class WebpImageIOTest {

    private static final String JAVAX_IMAGEIO_1_0 = "javax_imageio_1.0";

    // ------------------------------------------------------------------- service registration

    @Test
    @DisplayName("ImageIO discovers the WebP reader and writer through the service files")
    void registration() {
        assertTrue(ImageIO.getImageReadersByFormatName("webp").hasNext(), "the reader is not registered");
        assertTrue(ImageIO.getImageWritersByFormatName("webp").hasNext(), "the writer is not registered");
        assertTrue(ImageIO.getImageReadersBySuffix("webp").hasNext());
        assertTrue(ImageIO.getImageWritersBySuffix("webp").hasNext());
        assertTrue(ImageIO.getImageReadersByMIMEType("image/webp").hasNext());
        assertTrue(ImageIO.getImageWritersByMIMEType("image/webp").hasNext());

        // Registering WebP must not have displaced the built in formats.
        assertTrue(ImageIO.getImageReadersByFormatName("png").hasNext());
        assertTrue(ImageIO.getImageWritersByFormatName("JPEG").hasNext());
    }

    @Test
    @DisplayName("the reader provider describes itself consistently")
    void readerSpiContract() {
        WebpImageReaderSpi spi = new WebpImageReaderSpi();
        assertEquals(WebpImageReader.class, spi.getReaderClass());
        assertArrayEquals(new String[] {"webp", "WEBP", "WebP"}, spi.getFormatNames());
        assertArrayEquals(new String[] {"webp"}, spi.getFileSuffixes());
        assertArrayEquals(new String[] {"image/webp"}, spi.getMIMETypes());
        assertEquals("imagify.webp.WebpImageReader", spi.getPluginClassName());
        assertEquals(WebpImageReader.class.getName(), spi.createReaderInstance(null).getClass().getName());
        // setInput() reads this list, so an empty one would break every source.
        assertArrayEquals(new Class<?>[] {ImageInputStream.class}, spi.getInputTypes());
        assertTrue(spi.isStandardImageMetadataFormatSupported());
        assertFalse(spi.isStandardStreamMetadataFormatSupported());
        assertEquals(WebpMetadata.NATIVE_FORMAT, spi.getNativeImageMetadataFormatName());
        assertNull(spi.getNativeStreamMetadataFormatName());
        assertNotNull(spi.getVersion());
        assertNotNull(spi.getVendorName());
        assertNotNull(spi.getDescription(null));
        assertNotNull(spi.toString());

        // The arrays must be copies, so a caller cannot corrupt the constants.
        spi.getFormatNames()[0] = "tampered";
        assertEquals("webp", spi.getFormatNames()[0]);
    }

    @Test
    @DisplayName("the writer provider describes itself consistently")
    void writerSpiContract() {
        WebpImageWriterSpi spi = new WebpImageWriterSpi();
        assertEquals(WebpImageWriter.class, spi.getWriterClass());
        assertArrayEquals(new String[] {"webp", "WEBP", "WebP"}, spi.getFormatNames());
        assertArrayEquals(new String[] {"webp"}, spi.getFileSuffixes());
        assertArrayEquals(new String[] {"image/webp"}, spi.getMIMETypes());
        assertEquals("imagify.webp.WebpImageWriter", spi.getPluginClassName());
        assertEquals(WebpImageWriter.class.getName(), spi.createWriterInstance(null).getClass().getName());
        // setOutput() reads this list, so an empty one would break every destination.
        assertArrayEquals(javax.imageio.spi.ImageWriterSpi.STANDARD_OUTPUT_TYPE, spi.getOutputTypes());
        assertFalse(spi.isStandardImageMetadataFormatSupported());
        assertFalse(spi.isStandardStreamMetadataFormatSupported());
        assertNull(spi.getNativeImageMetadataFormatName());
        assertNull(spi.getNativeStreamMetadataFormatName());
        assertNotNull(spi.getVersion());
        assertNotNull(spi.getVendorName());
        assertNotNull(spi.getDescription(null));
        assertNotNull(spi.toString());

        spi.getFormatNames()[0] = "tampered";
        assertEquals("webp", spi.getFormatNames()[0]);
    }

    @Test
    @DisplayName("ImageIO returns this library's reader and writer for the webp suffix")
    void bySuffix() {
        List<String> readers = new ArrayList<>();
        for (Iterator<ImageReader> it = ImageIO.getImageReadersBySuffix("webp"); it.hasNext();) {
            readers.add(it.next().getClass().getName());
        }
        assertTrue(readers.contains(WebpImageReader.class.getName()), "got " + readers);

        List<String> writers = new ArrayList<>();
        for (Iterator<ImageWriter> it = ImageIO.getImageWritersBySuffix("webp"); it.hasNext();) {
            writers.add(it.next().getClass().getName());
        }
        assertTrue(writers.contains(WebpImageWriter.class.getName()), "got " + writers);
    }

    // -------------------------------------------------------------------------- format sniffing

    @Test
    @DisplayName("the reader provider rejects data that is not WebP")
    void rejectsForeignData() {
        WebpImageReaderSpi spi = new WebpImageReaderSpi();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        byte[] riffButNotWebp = "RIFF\0\0\0\0WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(spi.canDecodeInput(null));
        assertFalse(spi.canDecodeInput(new byte[0]));
        assertFalse(spi.canDecodeInput("RI".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
        assertFalse(spi.canDecodeInput(png));
        assertFalse(spi.canDecodeInput(new ByteArrayInputStream(png)));
        assertFalse(spi.canDecodeInput(riffButNotWebp), "a RIFF file of another form type");
        assertFalse(spi.canDecodeInput("not an image"), "an unsupported input type");
    }

    @Test
    @DisplayName("the reader provider accepts WebP data in every supported input type")
    void acceptsWebPData() throws Exception {
        requireLibwebp();
        WebpImageReaderSpi spi = new WebpImageReaderSpi();
        byte[] webp = WebpCodec.encode(sample(16, 12, false), 80, false);
        assertTrue(spi.canDecodeInput(webp), "as a byte array");
        assertTrue(spi.canDecodeInput(new ByteArrayInputStream(webp)), "as an InputStream");
        assertTrue(spi.canDecodeInput(stream(webp)), "as an ImageInputStream");
    }

    @Test
    @DisplayName("sniffing an ImageInputStream leaves the stream position untouched")
    void sniffingDoesNotMoveTheStream() throws Exception {
        requireLibwebp();
        byte[] data = WebpCodec.encode(sample(16, 12, false), 80, false);
        try (ImageInputStream input = stream(data)) {
            input.seek(0);
            new WebpImageReaderSpi().canDecodeInput(input);
            assertEquals(0, input.getStreamPosition());
        }
    }

    @Test
    @DisplayName("sniffing a File and a URL recognises a WebP file")
    void sniffingAFileAndUrl(@TempDir Path directory) throws Exception {
        requireLibwebp();
        byte[] data = WebpCodec.encode(sample(16, 12, false), 80, false);
        File file = directory.resolve("sniff.webp").toFile();
        Files.write(file.toPath(), data);
        WebpImageReaderSpi spi = new WebpImageReaderSpi();
        assertTrue(spi.canDecodeInput(file), "as a File");
        assertTrue(spi.canDecodeInput(file.toURI().toURL()), "as a URL");
    }

    // --------------------------------------------------------------------------------- round trip

    @Test
    @DisplayName("an opaque image survives a lossy round trip within the lossy tolerance")
    void lossyRoundTrip() throws Exception {
        requireLibwebp();
        BufferedImage source = sample(64, 48, false);
        byte[] webp = WebpCodec.encode(source, 100, false);
        BufferedImage back = WebpCodec.decode(webp);

        assertEquals(64, back.getWidth());
        assertEquals(48, back.getHeight());
        assertFalse(back.getColorModel().hasAlpha(), "a lossy opaque encode should not add alpha");
        // A flat, synthetic image compresses almost exactly, so anything larger than this means
        // the pixels were reordered rather than quantised.
        assertTrue(meanAbsoluteError(source, back) < 12,
                "mean absolute error was " + meanAbsoluteError(source, back));
    }

    @Test
    @DisplayName("a lossless round trip is pixel exact")
    void losslessRoundTrip() throws Exception {
        requireLibwebp();
        BufferedImage source = sample(64, 48, true);
        byte[] webp = WebpCodec.encode(source, 0, true);
        BufferedImage back = WebpCodec.decode(webp);

        assertEquals(64, back.getWidth());
        assertEquals(48, back.getHeight());
        assertTrue(back.getColorModel().hasAlpha());
        assertPixelsEqual(source, back);
    }

    @Test
    @DisplayName("an unassociated alpha channel survives, it is never premultiplied")
    void alphaStaysUnassociated() throws Exception {
        requireLibwebp();
        // Half transparent red: premultiplying would turn it into a dark, fully transparent red.
        BufferedImage source = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = source.createGraphics();
        g.setComposite(java.awt.AlphaComposite.Src);
        g.setColor(new Color(255, 0, 0, 128));
        g.fillRect(0, 0, 4, 4);
        g.dispose();

        byte[] webp = WebpCodec.encode(source, 0, true);
        BufferedImage back = WebpCodec.decode(webp);
        int pixel = back.getRGB(1, 1);
        assertEquals(0x80, pixel >>> 24 & 0xFF, "alpha should still be 128");
        assertEquals(0xFF, pixel >>> 16 & 0xFF, "red should still be 255");
    }

    @Test
    @DisplayName("a premultiplied source is un-premultiplied before it is encoded")
    void premultipliedSource() throws Exception {
        requireLibwebp();
        BufferedImage source = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = source.createGraphics();
        g.setComposite(java.awt.AlphaComposite.Src);
        g.setColor(new Color(255, 0, 0, 128));
        g.fillRect(0, 0, 4, 4);
        g.dispose();

        byte[] webp = WebpCodec.encode(source, 0, true);
        BufferedImage back = WebpCodec.decode(webp);
        int pixel = back.getRGB(1, 1);
        assertEquals(0x80, pixel >>> 24 & 0xFF, "alpha should survive as 128");
        assertEquals(0xFF, pixel >>> 16 & 0xFF, "red should be restored, not premultiplied to 128");
    }

    @Test
    @DisplayName("the lossless bitstream is reported as VP8L and the lossy one as VP8")
    void reportsTheFormat() throws Exception {
        requireLibwebp();
        WebpImageInfo lossy = WebpCodec.readHeader(WebpCodec.encode(sample(16, 16, false), 80, false));
        assertEquals(WebpCodec.FORMAT_VP8, lossy.format());
        assertEquals("VP8", WebpCodec.formatName(lossy.format()));
        assertFalse(lossy.hasAnimation());
        assertEquals(1, lossy.frameCount());

        WebpImageInfo lossless = WebpCodec.readHeader(WebpCodec.encode(sample(16, 16, false), 0, true));
        assertEquals(WebpCodec.FORMAT_VP8L, lossless.format());
        assertEquals("VP8L", WebpCodec.formatName(lossless.format()));
    }

    // ----------------------------------------------------------------- which bitstream a file has

    @Test
    @DisplayName("a still says which bitstream it holds, with or without an alpha channel")
    void losslessStill() throws Exception {
        requireLibwebp();
        for (boolean alpha : new boolean[] {false, true}) {
            String kind = alpha ? "with alpha" : "without alpha";
            assertFalse(WebpCodec.isLossless(WebpCodec.encode(sample(16, 16, alpha), 80, false)),
                    "a VP8 file is not lossless, " + kind);
            assertTrue(WebpCodec.isLossless(WebpCodec.encode(sample(16, 16, alpha), 0, true)),
                    "a VP8L file is lossless, " + kind);
        }
    }

    @Test
    @DisplayName("an animation is lossless when the bitstream of its frames is")
    void losslessAnimation() throws Exception {
        requireLibwebp();
        List<BufferedImage> frames = List.of(sample(16, 16, false), sample(16, 16, true));
        assertFalse(WebpCodec.isLossless(WebpCodec.encodeAnimation(frames, new int[] {40, 40}, 80, false, 0)),
                "a lossy animation stores its frames as VP8");
        assertTrue(WebpCodec.isLossless(WebpCodec.encodeAnimation(frames, new int[] {40, 40}, 0, true, 0)),
                "a lossless animation stores its frames as VP8L");
    }

    @Test
    @DisplayName("a header too short to hold the bitstream answers the lossy default")
    void losslessFromAShortHeader() throws Exception {
        requireLibwebp();
        byte[] lossless = WebpCodec.encode(sample(16, 16, true), 0, true);
        assertTrue(WebpCodec.losslessHeaderLength() > 16,
                "the header has to reach past the container to be worth anything");
        assertTrue(WebpCodec.isLossless(Arrays.copyOf(lossless, WebpCodec.losslessHeaderLength())),
                WebpCodec.losslessHeaderLength() + " bytes should be enough to see the bitstream");
        assertFalse(WebpCodec.isLossless(Arrays.copyOf(lossless, 16)),
                "sixteen bytes stop at the container header, so there is nothing to go on");
    }

    @Test
    @DisplayName("data that is not a WebP file is not a lossless one either")
    void losslessOfOtherData() throws Exception {
        assertFalse(WebpCodec.isLossless(null));
        assertFalse(WebpCodec.isLossless(new byte[0]));
        assertFalse(WebpCodec.isLossless("RIFF".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(WebpCodec.isLossless("not a webp file at all".getBytes(StandardCharsets.US_ASCII)));
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(sample(4, 4, false), "png", png));
        assertFalse(WebpCodec.isLossless(png.toByteArray()));
    }

    @Test
    @DisplayName("a container that claims a size it does not have ends the walk instead of looping")
    void losslessOfATruncatedContainer() {
        // A container whose first chunk claims a size that runs past the end of the data, one whose
        // size does not fit in a signed int, and one that is empty. None of them may walk off the
        // end of the array, and all three answer the lossy default.
        assertFalse(WebpCodec.isLossless(container("VP8X", 0x7F, 0xFF, 0xFF, 0xFF)));
        assertFalse(WebpCodec.isLossless(container("ABCD", 0xFF, 0xFF, 0xFF, 0xFF)));
        assertFalse(WebpCodec.isLossless(container("ABCD", 0, 0, 0, 0)), "an empty chunk steps over itself");
    }

    /**
     * A {@code RIFF} container holding one chunk header and no payload, whose size is given as four
     * bytes, most significant first.
     */
    private static byte[] container(String firstChunk, int... chunkSize) {
        byte[] data = new byte[24];
        System.arraycopy("RIFF".getBytes(StandardCharsets.ISO_8859_1), 0, data, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.ISO_8859_1), 0, data, 8, 4);
        System.arraycopy(firstChunk.getBytes(StandardCharsets.ISO_8859_1), 0, data, 12, 4);
        for (int i = 0; i < 4; i++) {
            data[16 + i] = (byte) (chunkSize[i] >> 8 * (3 - i));
        }
        return data;
    }

    @Test
    @DisplayName("the header is read without decoding the pixels")
    void headerOnlyRead() throws Exception {
        requireLibwebp();
        BufferedImage source = sample(120, 90, true);
        WebpImageInfo info = WebpCodec.readHeader(WebpCodec.encode(source, 80, false));
        assertEquals(120, info.width());
        assertEquals(90, info.height());
        assertTrue(info.hasAlpha());
        assertFalse(info.hasAnimation());
    }

    // ---------------------------------------------------------------------------------- animation

    @Test
    @DisplayName("an animation is decoded frame by frame, every frame composited onto the canvas")
    void animation() throws Exception {
        requireLibwebp();
        List<BufferedImage> frames = List.of(
                solid(32, 32, Color.RED),
                solid(32, 32, new Color(0, 255, 0, 128)));
        byte[] animated = WebpCodec.encodeAnimation(frames, new int[] {100, 250}, 80, false, 0);

        WebpImageInfo info = WebpCodec.readHeader(animated);
        assertTrue(info.hasAnimation(), "the container should be an animation");
        assertEquals(WebpCodec.FORMAT_VP8X, info.format());
        assertEquals(2, info.frameCount());
        assertEquals(32, info.width());
        assertEquals(32, info.height());

        List<BufferedImage> decoded = WebpCodec.decodeAnimation(animated);
        assertEquals(2, decoded.size());
        for (BufferedImage frame : decoded) {
            assertEquals(32, frame.getWidth(), "every frame is canvas sized");
            assertEquals(32, frame.getHeight(), "every frame is canvas sized");
        }
        assertTrue(isReddish(decoded.get(0)), "the first frame should be red");
        assertTrue(isGreenish(decoded.get(1)), "the second frame should be green");
    }

    @Test
    @DisplayName("the reader exposes an animation as one image per frame")
    void readerReadsAnimationFrames() throws Exception {
        requireLibwebp();
        List<BufferedImage> frames = List.of(
                solid(24, 24, Color.RED),
                solid(24, 24, Color.BLUE),
                solid(24, 24, Color.GREEN));
        byte[] animated = WebpCodec.encodeAnimation(frames, new int[] {40, 40, 40}, 80, false, 2);

        try (ImageInputStream input = stream(animated)) {
            WebpImageReader reader = new WebpImageReader(new WebpImageReaderSpi());
            reader.setInput(input);
            assertEquals(3, reader.getNumImages(true), "one image per frame");
            assertEquals(24, reader.getWidth(0));
            assertEquals(24, reader.getHeight(2));

            assertTrue(isReddish(reader.read(0)), "frame 0 should be red");
            assertTrue(isBlueish(reader.read(1)), "frame 1 should be blue");
            assertTrue(isGreenish(reader.read(2)), "frame 2 should be green");

            assertArrayEquals(new int[] {40, 40, 40}, reader.getFrameDelays());

            assertThrows(IndexOutOfBoundsException.class, () -> reader.getWidth(3));
        }
    }

    @Test
    @DisplayName("a still image reports no frame delays")
    void stillImageHasNoDelays() throws Exception {
        requireLibwebp();
        byte[] webp = WebpCodec.encode(sample(16, 16, false), 80, false);
        try (ImageInputStream input = stream(webp)) {
            WebpImageReader reader = new WebpImageReader(new WebpImageReaderSpi());
            reader.setInput(input);
            assertEquals(1, reader.getNumImages(true));
            assertNull(reader.getFrameDelays(), "a still image has no timing");
        }
    }

    @Test
    @DisplayName("a still WebP file is not mistaken for an animation")
    void stillIsNotAnimated() throws Exception {
        requireLibwebp();
        byte[] webp = WebpCodec.encode(sample(16, 16, true), 0, true);
        assertFalse(WebpCodec.readHeader(webp).hasAnimation());
        // The still decoder refuses an animation outright, so the still path has to stay on it
        // rather than reach for the animation decoder.
        assertNotNull(WebpCodec.decode(webp));
    }

    // -------------------------------------------------------------------------------- the reader

    @Test
    @DisplayName("the reader honours a source region")
    void sourceRegion() throws Exception {
        requireLibwebp();
        BufferedImage source = sample(40, 30, false);
        try (ImageInputStream input = stream(WebpCodec.encode(source, 0, true))) {
            WebpImageReader reader = new WebpImageReader(new WebpImageReaderSpi());
            reader.setInput(input);

            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceRegion(new Rectangle(10, 5, 8, 6));
            BufferedImage region = reader.read(0, param);
            assertEquals(8, region.getWidth());
            assertEquals(6, region.getHeight());
        }
    }

    @Test
    @DisplayName("the reader honours sub sampling")
    void subSampling() throws Exception {
        requireLibwebp();
        try (ImageInputStream input = stream(WebpCodec.encode(sample(40, 30, false), 0, true))) {
            WebpImageReader reader = new WebpImageReader(new WebpImageReaderSpi());
            reader.setInput(input);

            ImageReadParam param = reader.getDefaultReadParam();
            // This build of ImageReadParam only has the four argument form.
            param.setSourceSubsampling(2, 2, 0, 0);
            BufferedImage sampled = reader.read(0, param);
            assertEquals(20, sampled.getWidth());
            assertEquals(15, sampled.getHeight());
        }
    }

    @Test
    @DisplayName("the reader honours a destination and its offset")
    void destination() throws Exception {
        requireLibwebp();
        // A flat blue source, so "was written" is told apart from "the gradient happens to be black
        // at this particular pixel".
        try (ImageInputStream input = stream(WebpCodec.encode(solid(20, 20, Color.BLUE), 0, true))) {
            WebpImageReader reader = new WebpImageReader(new WebpImageReaderSpi());
            reader.setInput(input);

            BufferedImage destination = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
            ImageReadParam param = reader.getDefaultReadParam();
            param.setDestination(destination);
            param.setDestinationOffset(new java.awt.Point(5, 7));
            BufferedImage read = reader.read(0, param);

            assertSame(destination, read, "the caller supplied image should be filled in");
            assertEquals(0, destination.getRGB(0, 0), "outside the offset the destination is untouched");
            assertEquals(0, destination.getRGB(4, 6), "outside the offset the destination is untouched");
            assertEquals(Color.BLUE.getRGB(), destination.getRGB(5, 7), "inside the offset the image was written");
            assertEquals(Color.BLUE.getRGB(), destination.getRGB(24, 26), "the last written pixel");
        }
    }

    @Test
    @DisplayName("the reader honours a requested destination type")
    void destinationType() throws Exception {
        requireLibwebp();
        try (ImageInputStream input = stream(WebpCodec.encode(sample(20, 20, true), 0, true))) {
            WebpImageReader reader = new WebpImageReader(new WebpImageReaderSpi());
            reader.setInput(input);

            ImageReadParam param = reader.getDefaultReadParam();
            param.setDestinationType(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB));
            BufferedImage read = reader.read(0, param);
            assertEquals(BufferedImage.TYPE_INT_RGB, read.getType());
        }
    }

    @Test
    @DisplayName("the reader refuses a region that does not fit and an unsupported band selection")
    void rejectsBadParameters() throws Exception {
        requireLibwebp();
        try (ImageInputStream input = stream(WebpCodec.encode(sample(20, 20, false), 0, true))) {
            WebpImageReader reader = new WebpImageReader(new WebpImageReaderSpi());
            reader.setInput(input);

            ImageReadParam tooBig = reader.getDefaultReadParam();
            tooBig.setSourceRegion(new Rectangle(0, 0, 100, 100));
            assertThrows(IIOException.class, () -> reader.read(0, tooBig));

            ImageReadParam badBands = reader.getDefaultReadParam();
            badBands.setSourceBands(new int[] {0, 1});
            assertThrows(IIOException.class, () -> reader.read(0, badBands));
        }
    }

    @Test
    @DisplayName("the reader refuses to work without an input and on a file that is not WebP")
    void readerRejectsBadInput() throws Exception {
        requireLibwebp();
        WebpImageReader reader = new WebpImageReader(new WebpImageReaderSpi());
        assertThrows(IIOException.class, () -> reader.getNumImages(true));

        try (ImageInputStream input = stream("not a webp file at all".getBytes("US-ASCII"))) {
            reader.setInput(input);
            assertThrows(IIOException.class, () -> reader.getWidth(0));
        }
    }

    // -------------------------------------------------------------------------------- the writer

    @Test
    @DisplayName("the writer maps the ImageIO quality onto the libwebp quality")
    void writerMapsQuality() throws Exception {
        requireLibwebp();
        BufferedImage source = sample(80, 60, false);

        int small = write(source, 0.05f, WebpImageWriterSpi.COMPRESSION_TYPE).length;
        int large = write(source, 0.95f, WebpImageWriterSpi.COMPRESSION_TYPE).length;
        assertTrue(large > small,
                "a higher quality should produce a bigger file: " + small + " then " + large);
    }

    @Test
    @DisplayName("the writer stores VP8L when the lossless compression type is chosen")
    void writerHonoursLosslessType() throws Exception {
        requireLibwebp();
        BufferedImage source = sample(64, 64, true);
        byte[] lossy = write(source, 0.5f, WebpImageWriterSpi.COMPRESSION_TYPE);
        byte[] lossless = write(source, 0.5f, WebpImageWriterSpi.COMPRESSION_TYPE_LOSSLESS);

        assertEquals(WebpCodec.FORMAT_VP8, WebpCodec.readHeader(lossy).format());
        assertEquals(WebpCodec.FORMAT_VP8L, WebpCodec.readHeader(lossless).format());
    }

    @Test
    @DisplayName("the default write parameter is lossy at the libwebp default quality")
    void defaultWriteParam() throws Exception {
        requireLibwebp();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(new java.io.ByteArrayOutputStream())) {
            WebpImageWriter writer = new WebpImageWriter(new WebpImageWriterSpi());
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            assertTrue(param.canWriteCompressed());
            assertArrayEquals(new String[] {
                    WebpImageWriterSpi.COMPRESSION_TYPE,
                    WebpImageWriterSpi.COMPRESSION_TYPE_LOSSLESS}, param.getCompressionTypes());
            assertEquals(WebpImageWriterSpi.COMPRESSION_TYPE, param.getCompressionType());
            assertEquals(0.75f, param.getCompressionQuality(), 0.001f);
        }
    }

    @Test
    @DisplayName("the writer writes several images in a row")
    void writeSequence() throws Exception {
        requireLibwebp();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(new java.io.ByteArrayOutputStream())) {
            WebpImageWriter writer = new WebpImageWriter(new WebpImageWriterSpi());
            writer.setOutput(output);
            assertTrue(writer.canWriteSequence());
            writer.prepareWriteSequence(null);
            writer.writeToSequence(new IIOImage(sample(16, 16, false), null, null), null);
            writer.writeToSequence(new IIOImage(sample(24, 24, true), null, null), null);
            writer.endWriteSequence();
        }
    }

    @Test
    @DisplayName("the writer refuses to work without an output and rejects stream metadata")
    void writerRejectsBadState() throws Exception {
        requireLibwebp();
        WebpImageWriter writer = new WebpImageWriter(new WebpImageWriterSpi());
        assertThrows(IIOException.class, () -> writer.write(null, new IIOImage(sample(8, 8, false), null, null), null));
        assertThrows(IIOException.class, () -> writer.prepareWriteSequence(null));

        try (ImageOutputStream output = ImageIO.createImageOutputStream(new java.io.ByteArrayOutputStream())) {
            writer.setOutput(output);
            assertThrows(IIOException.class, () -> writer.prepareWriteSequence(new javax.imageio.metadata.IIOMetadata() {
                @Override
                public boolean isReadOnly() {
                    return true;
                }

                @Override
                public org.w3c.dom.Node getAsTree(String format) {
                    return null;
                }

                @Override
                public void mergeTree(String format, org.w3c.dom.Node node) {
                }

                @Override
                public void reset() {
                }
            }));
        }
    }

    // ------------------------------------------------------------------------------- ImageIO path

    @Test
    @DisplayName("ImageIO reads and writes WebP through the format name")
    void imageIoPath(@TempDir Path directory) throws Exception {
        requireLibwebp();
        BufferedImage source = sample(40, 30, true);
        File file = directory.resolve("out.webp").toFile();

        assertTrue(ImageIO.write(source, "webp", file), "ImageIO.write returned false");
        assertTrue(file.length() > 0);

        BufferedImage back = ImageIO.read(file);
        assertNotNull(back, "ImageIO.read returned null");
        assertEquals(40, back.getWidth());
        assertEquals(30, back.getHeight());
        assertTrue(back.getColorModel().hasAlpha(), "the alpha channel should have been written");
    }

    @Test
    @DisplayName("a higher ImageIO quality produces a bigger WebP file")
    void imageIoWithQuality(@TempDir Path directory) throws Exception {
        requireLibwebp();
        BufferedImage source = sample(100, 100, false);
        File small = directory.resolve("small.webp").toFile();
        File large = directory.resolve("large.webp").toFile();

        writeTo(source, small.toPath(), 0.05f);
        writeTo(source, large.toPath(), 0.95f);
        assertTrue(large.length() > small.length(),
                "a higher quality should produce a bigger file: " + small.length() + " then " + large.length());
    }

    // ---------------------------------------------------------------------------------- metadata

    @Test
    @DisplayName("the metadata reports the WebP properties natively and in the standard format")
    void metadata() throws Exception {
        requireLibwebp();
        byte[] webp = WebpCodec.encode(sample(30, 20, true), 0, true);
        try (ImageInputStream input = stream(webp)) {
            WebpImageReader reader = new WebpImageReader(new WebpImageReaderSpi());
            reader.setInput(input);

            var metadata = reader.getImageMetadata(0);
            assertTrue(metadata.isReadOnly());

            var native0 = (org.w3c.dom.Element) metadata.getAsTree(WebpMetadata.NATIVE_FORMAT);
            assertEquals("30", native0.getAttribute("width"));
            assertEquals("20", native0.getAttribute("height"));
            assertEquals("true", native0.getAttribute("hasAlpha"));
            assertEquals("false", native0.getAttribute("hasAnimation"));
            assertEquals("VP8L", native0.getAttribute("format"));
            assertEquals("1", native0.getAttribute("frameCount"));

            var standard = (org.w3c.dom.Element) metadata.getAsTree(JAVAX_IMAGEIO_1_0);
            assertEquals("30", standard.getElementsByTagName("Dimension")
                    .item(0).getAttributes().getNamedItem("pixelWidth").getNodeValue());

            assertThrows(IllegalArgumentException.class, () -> metadata.getAsTree("nonsense"));
            assertThrows(UnsupportedOperationException.class, () -> metadata.reset());
        }
    }

    @Test
    @DisplayName("the metadata of an animation reports the frame and loop counts")
    void animationMetadata() throws Exception {
        requireLibwebp();
        byte[] animated = WebpCodec.encodeAnimation(
                List.of(solid(16, 16, Color.RED), solid(16, 16, Color.BLUE)),
                new int[] {10, 20}, 80, false, 3);
        try (ImageInputStream input = stream(animated)) {
            WebpImageReader reader = new WebpImageReader(new WebpImageReaderSpi());
            reader.setInput(input);
            var native0 = (org.w3c.dom.Element) reader.getImageMetadata(0).getAsTree(WebpMetadata.NATIVE_FORMAT);
            assertEquals("true", native0.getAttribute("hasAnimation"));
            assertEquals("2", native0.getAttribute("frameCount"));
            assertEquals("3", native0.getAttribute("loopCount"));
        }
    }

    // ----------------------------------------------------------------------------------- helpers

    private static void requireLibwebp() {
        assumeTrue(WebpCodec.isAvailable(), () -> "libwebp is not available: " + WebpCodec.getUnavailableReason());
    }

    private static BufferedImage sample(int width, int height, boolean alpha) {
        // A gradient plus a few hard edges, so lossy compression has something to work on.
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage image = new BufferedImage(width, height, type);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = x * 255 / Math.max(1, width - 1);
                int g = y * 255 / Math.max(1, height - 1);
                int b = (x + y) % 256;
                int a = alpha ? (x % 3 == 0 ? 96 : 255) : 255;
                image.setRGB(x, y, a << 24 | r << 16 | g << 8 | b);
            }
        }
        return image;
    }

    private static BufferedImage solid(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setComposite(java.awt.AlphaComposite.Src);
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    private static boolean isReddish(BufferedImage image) {
        int pixel = image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
        return (pixel >>> 16 & 0xFF) > 200 && (pixel >>> 8 & 0xFF) < 60;
    }

    private static boolean isGreenish(BufferedImage image) {
        int pixel = image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
        return (pixel >>> 8 & 0xFF) > 150 && (pixel >>> 16 & 0xFF) < 100;
    }

    private static boolean isBlueish(BufferedImage image) {
        int pixel = image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
        return (pixel & 0xFF) > 150 && (pixel >>> 16 & 0xFF) < 100;
    }

    private static void assertPixelsEqual(BufferedImage expected, BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth(), "width");
        assertEquals(expected.getHeight(), actual.getHeight(), "height");
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                assertEquals(expected.getRGB(x, y), actual.getRGB(x, y),
                        "pixel " + x + "," + y + " differs");
            }
        }
    }

    private static double meanAbsoluteError(BufferedImage a, BufferedImage b) {
        double total = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                total += Math.abs((p >>> 16 & 0xFF) - (q >>> 16 & 0xFF));
                total += Math.abs((p >>> 8 & 0xFF) - (q >>> 8 & 0xFF));
                total += Math.abs((p & 0xFF) - (q & 0xFF));
            }
        }
        return total / (a.getWidth() * (long) a.getHeight() * 3);
    }

    private static byte[] write(BufferedImage image, float quality, String compressionType)
            throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            WebpImageWriter writer = new WebpImageWriter(new WebpImageWriterSpi());
            writer.setOutput(output);

            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            param.setCompressionType(compressionType);
            writer.write(null, new IIOImage(image, null, null), param);
        }
        return bytes.toByteArray();
    }

    private static void writeTo(BufferedImage image, Path file, float quality) throws IOException {
        Files.write(file, write(image, quality, WebpImageWriterSpi.COMPRESSION_TYPE));
    }

    private static ImageInputStream stream(byte[] data) throws IOException {
        return ImageIO.createImageInputStream(new ByteArrayInputStream(data));
    }
}
