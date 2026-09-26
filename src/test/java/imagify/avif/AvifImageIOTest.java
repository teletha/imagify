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

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
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
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import imagify.avif.jna.AvifCodec;

/**
 * Tests the {@code ImageIO} integration: service registration, format recognition, the reader, the
 * writer and the metadata.
 *
 * <p>
 * Everything that needs {@code libavif} is skipped when it is missing. The tests that do not
 * need it still run everywhere, and double as a check that {@code ImageIO} stays healthy on a
 * machine without the native library.
 */
class AvifImageIOTest {

    /** The one standard metadata format name, spelled out because the constant is protected. */
    private static final String JAVAX_IMAGEIO_1_0 = "javax_imageio_1.0";

    // ------------------------------------------------------------------- service registration

    @Test
    @DisplayName("ImageIO discovers the AVIF reader and writer through the service files")
    void registration() {
        assertTrue(ImageIO.getImageReadersByFormatName("AVIF").hasNext(), "the reader is not registered");
        assertTrue(ImageIO.getImageWritersByFormatName("AVIF").hasNext(), "the writer is not registered");
        assertTrue(ImageIO.getImageReadersBySuffix("avif").hasNext());
        assertTrue(ImageIO.getImageWritersBySuffix("avif").hasNext());
        assertTrue(ImageIO.getImageReadersByMIMEType("image/avif").hasNext());
        assertTrue(ImageIO.getImageWritersByMIMEType("image/avif").hasNext());

        // Registering AVIF must not have displaced the built in formats.
        assertTrue(ImageIO.getImageReadersByFormatName("png").hasNext());
        assertTrue(ImageIO.getImageWritersByFormatName("JPEG").hasNext());
    }

    @Test
    @DisplayName("the reader provider describes itself consistently")
    void readerSpiContract() {
        AvifImageReaderSpi spi = new AvifImageReaderSpi();
        assertEquals(AvifImageReader.class, spi.getReaderClass());
        assertArrayEquals(new String[] {"AVIF", "avif"}, spi.getFormatNames());
        assertArrayEquals(new String[] {"avif"}, spi.getFileSuffixes());
        assertArrayEquals(new String[] {"image/avif"}, spi.getMIMETypes());
        assertEquals("imagify.avif.AvifImageReader", spi.getPluginClassName());
        assertEquals(AvifImageReader.class.getName(), spi.createReaderInstance(null).getClass().getName());
        // setInput() reads this list, so an empty one would break every source.
        assertArrayEquals(new Class<?>[] {ImageInputStream.class}, spi.getInputTypes());
        assertTrue(spi.isStandardImageMetadataFormatSupported());
        assertFalse(spi.isStandardStreamMetadataFormatSupported());
        assertEquals(AvifMetadata.NATIVE_FORMAT, spi.getNativeImageMetadataFormatName());
        assertNull(spi.getNativeStreamMetadataFormatName());
        assertNotNull(spi.getVersion());
        assertNotNull(spi.getVendorName());
        assertNotNull(spi.getDescription(null));
        assertNotNull(spi.toString());

        // The arrays must be copies, so a caller cannot corrupt the constants.
        spi.getFormatNames()[0] = "tampered";
        assertEquals("AVIF", spi.getFormatNames()[0]);
    }

    @Test
    @DisplayName("the writer provider describes itself consistently")
    void writerSpiContract() {
        AvifImageWriterSpi spi = new AvifImageWriterSpi();
        assertEquals(AvifImageWriter.class, spi.getWriterClass());
        assertArrayEquals(new String[] {"AVIF", "avif"}, spi.getFormatNames());
        assertArrayEquals(new String[] {"avif"}, spi.getFileSuffixes());
        assertArrayEquals(new String[] {"image/avif"}, spi.getMIMETypes());
        assertEquals("imagify.avif.AvifImageWriter", spi.getPluginClassName());
        assertEquals(AvifImageWriter.class.getName(), spi.createWriterInstance(null).getClass().getName());
        // setOutput() reads this list, so an empty one would break every destination.
        assertArrayEquals(ImageWriterSpi.STANDARD_OUTPUT_TYPE, spi.getOutputTypes());
        assertFalse(spi.isStandardImageMetadataFormatSupported());
        assertFalse(spi.isStandardStreamMetadataFormatSupported());
        assertNull(spi.getNativeImageMetadataFormatName());
        assertNull(spi.getNativeStreamMetadataFormatName());
        assertNotNull(spi.getVersion());
        assertNotNull(spi.getVendorName());
        assertNotNull(spi.getDescription(null));
        assertNotNull(spi.toString());

        spi.getFormatNames()[0] = "tampered";
        assertEquals("AVIF", spi.getFormatNames()[0]);
    }

    @Test
    @DisplayName("ImageIO returns this library's reader for the avif suffix")
    void readersBySuffix() {
        Iterator<ImageReader> readers = ImageIO.getImageReadersBySuffix("avif");
        List<String> names = new ArrayList<>();
        while (readers.hasNext()) {
            names.add(readers.next().getClass().getName());
        }
        assertTrue(names.contains(AvifImageReader.class.getName()), "got " + names);
    }

    @Test
    @DisplayName("ImageIO returns this library's writer for the avif suffix")
    void writersBySuffix() {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersBySuffix("avif");
        List<String> names = new ArrayList<>();
        while (writers.hasNext()) {
            names.add(writers.next().getClass().getName());
        }
        assertTrue(names.contains(AvifImageWriter.class.getName()), "got " + names);
    }

    // -------------------------------------------------------------------------- format sniffing

    @Test
    @DisplayName("the reader provider rejects data that is not AVIF")
    void rejectsForeignData() throws IOException {
        AvifImageReaderSpi spi = new AvifImageReaderSpi();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        assertFalse(spi.canDecodeInput(null));
        assertFalse(spi.canDecodeInput(new byte[0]));
        assertFalse(spi.canDecodeInput(png));
        assertFalse(spi.canDecodeInput(new ByteArrayInputStream(png)));
        assertFalse(spi.canDecodeInput(stream(png)));
        assertFalse(spi.canDecodeInput("not an image"), "an unsupported input type");
    }

    @Test
    @DisplayName("the reader provider accepts AVIF data in every supported input type")
    void acceptsAvifData() throws IOException {
        requireLibavif();
        AvifImageReaderSpi spi = new AvifImageReaderSpi();
        byte[] avif = ftyp("avif");
        assertTrue(spi.canDecodeInput(avif), "as a byte array");
        assertTrue(spi.canDecodeInput(new ByteArrayInputStream(avif)), "as an InputStream");
        assertTrue(spi.canDecodeInput(stream(avif)), "as an ImageInputStream");
    }

    @Test
    @DisplayName("sniffing an ImageInputStream leaves the stream position untouched")
    void sniffingDoesNotMoveTheStream() throws IOException {
        byte[] data = ftyp("avif");
        try (ImageInputStream stream = stream(data)) {
            stream.seek(0);
            new AvifImageReaderSpi().canDecodeInput(stream);
            assertEquals(0, stream.getStreamPosition());
        }
    }

    @Test
    @DisplayName("sniffing a File reads only the head of the file")
    void sniffingAFile(@TempDir Path directory) throws IOException {
        requireLibavif();
        File avif = directory.resolve("sniff.avif").toFile();
        Files.write(avif.toPath(), ftyp("avif"));
        assertTrue(new AvifImageReaderSpi().canDecodeInput(avif));

        File png = directory.resolve("sniff.png").toFile();
        Files.write(png.toPath(), new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        assertFalse(new AvifImageReaderSpi().canDecodeInput(png));
    }

    @Test
    @DisplayName("sniffing a missing File is rejected rather than throwing")
    void sniffingAMissingFile(@TempDir Path directory) {
        assertFalse(new AvifImageReaderSpi().canDecodeInput(directory.resolve("absent.avif").toFile()));
    }

    // ----------------------------------------------------------------------- reader preconditions

    @Test
    @DisplayName("a header only file is rejected by the reader with a clear message")
    void headerOnlyInput() throws IOException {
        AvifImageReader reader = reader();
        reader.setInput(stream(ftyp("avif")));
        // The ftyp box is complete, so the parse fails inside libavif rather than in the sniffer.
        // Without libavif it fails on the missing library, with the same prefix.
        IIOException e = assertThrows(IIOException.class, () -> reader.getWidth(0));
        assertTrue(e.getMessage().startsWith("cannot read the AVIF header"), e.getMessage());
    }

    @Test
    @DisplayName("the reader refuses to work before an input is set")
    void noInput() {
        AvifImageReader reader = reader();
        assertThrows(IIOException.class, () -> reader.getNumImages(true));
        assertThrows(IIOException.class, () -> reader.getWidth(0));
        assertThrows(IIOException.class, () -> reader.getImageTypes(0));
        assertThrows(IIOException.class, () -> reader.getImageMetadata(0));
        assertThrows(IIOException.class, () -> reader.read(0));
    }

    @Test
    @DisplayName("the reader rejects an image index other than zero")
    void badIndex() throws IOException {
        AvifImageReader reader = reader();
        reader.setInput(stream(ftyp("avif")));
        assertThrows(IndexOutOfBoundsException.class, () -> reader.getWidth(1));
        assertThrows(IndexOutOfBoundsException.class, () -> reader.getWidth(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> reader.read(1));
        // An AVIF file always holds exactly one image.
        assertEquals(1, reader.getNumImages(true));
        assertEquals(1, reader.getNumImages(false));
    }

    @Test
    @DisplayName("the reader offers the image types it can produce")
    void imageTypes() throws IOException {
        requireLibavif();
        AvifImageReader reader = reader();
        reader.setInput(stream(encode(gradient(4, 4), null)));
        // getImageTypes() hands back an Iterator, not a collection.
        List<Integer> types = new ArrayList<>();
        for (Iterator<ImageTypeSpecifier> iterator = reader.getImageTypes(0); iterator.hasNext();) {
            types.add(iterator.next().getBufferedImageType());
        }
        assertEquals(List
                .of(BufferedImage.TYPE_4BYTE_ABGR, BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE, BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_3BYTE_BGR), types);
    }

    @Test
    @DisplayName("the reader has no stream metadata, because AVIF has none")
    void streamMetadata() throws IOException {
        requireLibavif();
        AvifImageReader reader = reader();
        reader.setInput(stream(encode(gradient(4, 4), null)));
        assertNull(reader.getStreamMetadata());
    }

    // ------------------------------------------------------------------ encode and decode, gated

    @Test
    @DisplayName("an image survives a write and read round trip")
    void roundTrip() throws IOException {
        requireLibavif();
        BufferedImage source = gradient(17, 13);
        byte[] encoded = encode(source, null);
        assertTrue(AvifCodec.isAvif(encoded), "the output is not an AVIF file");

        AvifImageReader reader = reader();
        reader.setInput(stream(encoded));
        assertEquals(17, reader.getWidth(0));
        assertEquals(13, reader.getHeight(0));

        BufferedImage decoded = reader.read(0);
        assertEquals(17, decoded.getWidth());
        assertEquals(13, decoded.getHeight());
        assertClose(source, decoded, 24, "the pixels changed more than the lossy encoder allows");
    }

    @Test
    @DisplayName("ImageIO.read and ImageIO.write work through the registered providers")
    void throughImageIO() throws IOException {
        requireLibavif();
        BufferedImage source = gradient(8, 8);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "AVIF", buffer), "ImageIO.write found no writer");
        assertTrue(AvifCodec.isAvif(buffer.toByteArray()));

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(buffer.toByteArray()));
        assertNotNull(decoded, "ImageIO.read found no reader");
        assertEquals(8, decoded.getWidth());
        assertClose(source, decoded, 24, "the pixels changed more than the lossy encoder allows");
    }

    @Test
    @DisplayName("an alpha channel survives the round trip")
    void alpha() throws IOException {
        requireLibavif();
        BufferedImage source = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                source.setRGB(x, y, x * 0x10 << 24 | 0x00ff0000);
            }
        }
        BufferedImage decoded = decode(encode(source, null));
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                assertEquals(source.getRGB(x, y) >>> 24, decoded.getRGB(x, y) >>> 24, "alpha at " + x + "," + y);
            }
        }
    }

    @Test
    @DisplayName("a lower quality setting produces a smaller file")
    void quality() throws IOException {
        requireLibavif();
        BufferedImage source = noise(64, 64);
        byte[] best = encode(source, 1.0f);
        byte[] worst = encode(source, 0.1f);
        assertTrue(worst.length < best.length, "quality 0.1 produced " + worst.length + " bytes, quality 1.0 produced " + best.length);
    }

    // ----------------------------------------------------------------- reading with parameters

    @Test
    @DisplayName("a source region limits the size of the result")
    void sourceRegion() throws IOException {
        requireLibavif();
        AvifImageReader reader = reader();
        reader.setInput(stream(encode(gradient(20, 20), null)));

        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceRegion(new Rectangle(4, 6, 8, 10));
        BufferedImage cropped = reader.read(0, param);
        assertEquals(8, cropped.getWidth());
        assertEquals(10, cropped.getHeight());
    }

    @Test
    @DisplayName("the destination type of the read parameters is honoured")
    void destinationType() throws IOException {
        requireLibavif();
        for (int type : new int[] {BufferedImage.TYPE_4BYTE_ABGR, BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE,
                BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_3BYTE_BGR}) {
            AvifImageReader reader = reader();
            reader.setInput(stream(encode(gradient(8, 8), null)));
            ImageReadParam param = reader.getDefaultReadParam();
            param.setDestinationType(ImageTypeSpecifier.createFromBufferedImageType(type));
            assertEquals(type, reader.read(0, param).getType(), "type " + type);
        }
    }

    @Test
    @DisplayName("a destination type this reader cannot produce is refused")
    void badDestinationType() throws IOException {
        requireLibavif();
        AvifImageReader reader = reader();
        reader.setInput(stream(encode(gradient(8, 8), null)));

        ImageReadParam param = reader.getDefaultReadParam();
        param.setDestinationType(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_BYTE_GRAY));
        assertThrows(IIOException.class, () -> reader.read(0, param));
    }

    @Test
    @DisplayName("a destination image that is too small is refused")
    void smallDestination() throws IOException {
        requireLibavif();
        BufferedImage destination = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        AvifImageReader reader = reader();
        reader.setInput(stream(encode(gradient(8, 8), null)));
        ImageReadParam param = reader.getDefaultReadParam();
        param.setDestination(destination);
        IIOException e = assertThrows(IIOException.class, () -> reader.read(0, param));
        assertTrue(e.getMessage().contains("destination image is 4x4"), e.getMessage());
    }

    @Test
    @DisplayName("a large enough destination image receives the pixels")
    void destinationImage() throws IOException {
        requireLibavif();
        BufferedImage source = gradient(8, 8);
        BufferedImage destination = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        AvifImageReader reader = reader();
        reader.setInput(stream(encode(source, null)));
        ImageReadParam param = reader.getDefaultReadParam();
        param.setDestination(destination);
        assertSame(destination, reader.read(0, param), "the destination image itself must be returned");
        assertClose(source, destination, 24, "the destination image holds the wrong pixels");
    }

    @Test
    @DisplayName("reading twice from the same reader gives the same result")
    void readTwice() throws IOException {
        requireLibavif();
        AvifImageReader reader = reader();
        reader.setInput(stream(encode(gradient(8, 8), null)));
        assertClose(reader.read(0), reader.read(0), 0, "a second read returned different pixels");
    }

    @Test
    @DisplayName("reset clears the state of a reader")
    void reset() throws IOException {
        requireLibavif();
        AvifImageReader reader = reader();
        reader.setInput(stream(encode(gradient(8, 8), null)));
        assertEquals(8, reader.getWidth(0));
        reader.reset();
        assertThrows(IIOException.class, () -> reader.getWidth(0));
    }

    // -------------------------------------------------------------------------------- metadata

    @Test
    @DisplayName("the native metadata format reports the container properties")
    void nativeMetadata() throws IOException {
        requireLibavif();
        AvifImageReader reader = reader();
        reader.setInput(stream(encode(gradient(12, 9), null)));
        IIOMetadata metadata = reader.getImageMetadata(0);
        assertEquals(AvifMetadata.NATIVE_FORMAT, metadata.getNativeMetadataFormatName());
        assertTrue(metadata.isReadOnly());
        assertTrue(metadata.isStandardMetadataFormatSupported());

        Element root = (Element) metadata.getAsTree(AvifMetadata.NATIVE_FORMAT);
        assertEquals(AvifMetadata.NATIVE_FORMAT, root.getTagName());
        assertEquals("12", root.getAttribute("width"));
        assertEquals("9", root.getAttribute("height"));
        assertEquals("8", root.getAttribute("depth"));
        assertEquals("YUV444", root.getAttribute("yuvFormat"));
        assertEquals("0", root.getAttribute("rotation"));
        assertEquals("false", root.getAttribute("mirrored"));
        assertEquals("0", root.getAttribute("exifSize"));
    }

    @Test
    @DisplayName("the standard metadata format describes the decoded image")
    void standardMetadata() throws IOException {
        requireLibavif();
        AvifImageReader reader = reader();
        reader.setInput(stream(encode(gradient(12, 9), null)));
        // The standard format takes an explicit list of extra formats, which AVIF has none of.
        IIOMetadata metadata = reader.getImageMetadata(0, JAVAX_IMAGEIO_1_0, null);
        Element root = (Element) metadata.getAsTree(JAVAX_IMAGEIO_1_0);
        assertEquals(JAVAX_IMAGEIO_1_0, root.getTagName());

        Element dimension = first(root, "Dimension");
        assertEquals("12", dimension.getAttribute("pixelWidth"));
        assertEquals("9", dimension.getAttribute("pixelHeight"));
        assertEquals("1.0", dimension.getAttribute("pixelAspectRatio"));

        // The standard format has no notion of an alpha channel per se, so its shape follows
        // whatever the container says. libavif is free to drop a fully opaque alpha plane, which is
        // why the expectation is derived rather than hard coded.
        Element nativeRoot = (Element) reader.getImageMetadata(0).getAsTree(AvifMetadata.NATIVE_FORMAT);
        boolean hasAlpha = Boolean.parseBoolean(nativeRoot.getAttribute("hasAlpha"));
        int channels = hasAlpha ? 4 : 3;

        Element data = first(root, "Data");
        assertEquals("12", data.getAttribute("width"));
        assertEquals("9", data.getAttribute("height"));
        assertEquals(Integer.toString(channels), data.getAttribute("numberOfChannels"));
        assertEquals(hasAlpha ? "ARGB" : "RGB", data.getAttribute("type"));
        assertEquals("unsignedIntegral", data.getAttribute("sampleFormat"));
        assertEquals("8 8 8 8".substring(0, channels * 2 - 1), data.getAttribute("bitsPerSample"));
        // Each channel is a nested attribute list, which is how the standard format spells it.
        String[] names = {"R", "G", "B", "A"};
        for (int channel = 0; channel < channels; channel++) {
            String attribute = data.getAttribute("channel" + channel);
            assertTrue(attribute.contains("name=\"" + names[channel] + "\""), attribute);
            assertTrue(attribute.contains("bitsPerSample=\"8\""), attribute);
        }

        assertEquals(hasAlpha ? "nonpremultiplied" : "none", first(root, "Transparency").getAttribute("alpha"));
    }

    @Test
    @DisplayName("metadata is read only, as AVIF carries none of it in the ImageIO sense")
    void metadataIsReadOnly() throws IOException {
        requireLibavif();
        AvifImageReader reader = reader();
        reader.setInput(stream(encode(gradient(4, 4), null)));
        IIOMetadata metadata = reader.getImageMetadata(0);
        assertThrows(UnsupportedOperationException.class, () -> metadata.mergeTree(null, null));
    }

    @Test
    @DisplayName("an unknown metadata format is refused with a clear message")
    void unknownMetadataFormat() throws IOException {
        requireLibavif();
        AvifImageReader reader = reader();
        reader.setInput(stream(encode(gradient(4, 4), null)));
        assertThrows(IllegalArgumentException.class, () -> reader.getImageMetadata(0, "no_such_format", null));
    }

    // ------------------------------------------------------------------------------- the writer

    @Test
    @DisplayName("the writer refuses to work before an output is set")
    void writerNoOutput() {
        AvifImageWriter writer = writer();
        assertThrows(IIOException.class, () -> writer.write(null, iioImage(gradient(4, 4)), null));
        assertThrows(IIOException.class, () -> writer.prepareWriteSequence(null));
    }

    @Test
    @DisplayName("the writer refuses an image it cannot encode")
    void writerBadImage() throws IOException {
        requireLibavif();
        try (ImageOutputStream out = output()) {
            AvifImageWriter writer = writer();
            writer.setOutput(out);
            assertThrows(IIOException.class, () -> writer.write(null, (IIOImage) null, null));
        }
    }

    @Test
    @DisplayName("the writer refuses stream metadata, because AVIF has none")
    void writerStreamMetadata() throws IOException {
        requireLibavif();
        IIOMetadata metadata = metadataOf(encode(gradient(4, 4), null));
        try (ImageOutputStream out = output()) {
            AvifImageWriter writer = writer();
            writer.setOutput(out);
            assertThrows(IIOException.class, () -> writer.prepareWriteSequence(metadata));
            assertThrows(IIOException.class, () -> writer.write(metadata, iioImage(gradient(4, 4)), null));
        }
    }

    @Test
    @DisplayName("the default write parameters allow an explicit quality")
    void writerWriteParam() throws IOException {
        requireLibavif();
        try (ImageOutputStream out = output()) {
            ImageWriteParam param = writer().getDefaultWriteParam();
            assertEquals(ImageWriteParam.MODE_DEFAULT, param.getCompressionMode());
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.5f);
            assertEquals(0.5f, param.getCompressionQuality(), 0.0f);
        }
    }

    @Test
    @DisplayName("a write sequence is flushed at the end")
    void writerSequence() throws IOException {
        requireLibavif();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(buffer)) {
            AvifImageWriter writer = writer();
            writer.setOutput(out);
            assertTrue(writer.canWriteSequence());
            writer.prepareWriteSequence(null);
            writer.write(null, iioImage(gradient(4, 4)), null);
            writer.endWriteSequence();
        }
        assertTrue(AvifCodec.isAvif(buffer.toByteArray()), "the sequence was not flushed");
    }

    @Test
    @DisplayName("endWriteSequence without a write is refused")
    void writerEndWithoutWrite() throws IOException {
        requireLibavif();
        try (ImageOutputStream out = output()) {
            AvifImageWriter writer = writer();
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            assertThrows(IIOException.class, writer::endWriteSequence);
        }
    }

    @Test
    @DisplayName("the writer reports that it can encode any non empty image")
    void writerCanEncode() {
        AvifImageWriterSpi spi = new AvifImageWriterSpi();
        assertEquals(AvifCodec.isAvailable(), spi.canEncodeImage(gradient(4, 4)));
        // Both overloads accept null, so the argument has to be typed to pick the RenderedImage
        // one.
        assertFalse(spi.canEncodeImage((RenderedImage) null));
        // An empty image cannot be built on this JDK at all, so only the type overload is left to
        // reject, and it has to agree with the image one about the library.
        assertEquals(AvifCodec.isAvailable(), spi
                .canEncodeImage(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB)));
        assertFalse(spi.canEncodeImage((ImageTypeSpecifier) null));
    }

    @Test
    @DisplayName("the writer reports no metadata, because AVIF has none")
    void writerMetadata() {
        AvifImageWriter writer = writer();
        assertNull(writer.getDefaultStreamMetadata(null));
        assertNull(writer.getDefaultImageMetadata(null, null));
        assertNull(writer.convertStreamMetadata(null, null));
    }

    // ------------------------------------------------------------------------------- helpers

    private static AvifImageReader reader() {
        return new AvifImageReader(new AvifImageReaderSpi());
    }

    private static AvifImageWriter writer() {
        return new AvifImageWriter(new AvifImageWriterSpi());
    }

    private static ImageOutputStream output() throws IOException {
        return ImageIO.createImageOutputStream(new ByteArrayOutputStream());
    }

    private static void requireLibavif() {
        assumeTrue(AvifCodec.isAvailable(), () -> "skipped: libavif is not available (" + AvifCodec.getUnavailableReason() + ")");
    }

    /**
     * Encodes an image through the writer, at the given quality, or at the default when
     * {@code null}.
     */
    private static byte[] encode(BufferedImage source, Float quality) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(buffer)) {
            AvifImageWriter writer = writer();
            writer.setOutput(out);
            ImageWriteParam param = null;
            if (quality != null) {
                param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, iioImage(source), param);
        }
        return buffer.toByteArray();
    }

    private static BufferedImage decode(byte[] encoded) throws IOException {
        AvifImageReader reader = reader();
        reader.setInput(stream(encoded));
        return reader.read(0);
    }

    private static IIOMetadata metadataOf(byte[] encoded) throws IOException {
        AvifImageReader reader = reader();
        reader.setInput(stream(encoded));
        return reader.getImageMetadata(0);
    }

    private static void assertClose(BufferedImage expected, BufferedImage actual, int tolerance, String message) {
        assertEquals(expected.getWidth(), actual.getWidth(), message + ": width");
        assertEquals(expected.getHeight(), actual.getHeight(), message + ": height");
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                int[] a = channels(expected.getRGB(x, y));
                int[] b = channels(actual.getRGB(x, y));
                for (int c = 0; c < 4; c++) {
                    assertTrue(Math
                            .abs(a[c] - b[c]) <= tolerance, message + ": " + describe(a) + " vs " + describe(b) + " at " + x + "," + y);
                }
            }
        }
    }

    /** @return the four channels of a non premultiplied ARGB value, in A, R, G, B order */
    private static int[] channels(int argb) {
        return new int[] {argb >>> 24, (argb >> 16) & 0xff, (argb >> 8) & 0xff, argb & 0xff};
    }

    private static String describe(int[] channels) {
        return "ARGB" + Arrays.toString(channels);
    }

    private static Element first(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element && tag.equals(element.getTagName())) {
                return element;
            }
        }
        throw new AssertionError("no " + tag + " element in " + parent.getTagName());
    }

    /** A smooth RGB gradient, which an AVIF encoder reproduces almost exactly. */
    private static BufferedImage gradient(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = width < 2 ? 0 : x * 255 / (width - 1);
                int g = height < 2 ? 0 : y * 255 / (height - 1);
                image.setRGB(x, y, 0xff000000 | r << 16 | g << 8 | (r + g) / 2);
            }
        }
        return image;
    }

    private static BufferedImage flat(int argb) {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    /** A half transparent red, so that libavif cannot drop the alpha plane. */
    private static BufferedImage translucent(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, 0x80ff0000);
            }
        }
        return image;
    }

    /** Random noise, which defeats the transform predictors so file size really tracks quality. */
    private static BufferedImage noise(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int seed = 0x2545f491;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                seed ^= seed << 13;
                seed ^= seed >>> 17;
                seed ^= seed << 5;
                image.setRGB(x, y, 0xff000000 | seed);
            }
        }
        return image;
    }

    /** @return a well formed 28 byte {@code ftyp} box that claims to be an AVIF file */
    private static byte[] ftyp(String majorBrand) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, 28);
        writeAscii(out, "ftyp");
        writeAscii(out, majorBrand);
        writeInt(out, 0);
        writeAscii(out, "mif1");
        writeAscii(out, "miaf");
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    private static ImageInputStream stream(byte[] data) throws IOException {
        return ImageIO.createImageInputStream(new ByteArrayInputStream(data));
    }

    /** @return the image wrapped the way {@code ImageWriter.write()} wants it */
    private static IIOImage iioImage(RenderedImage image) {
        return new IIOImage(image, null, null);
    }
}
