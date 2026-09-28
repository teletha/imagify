/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.jpeg;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import imagify.ImageFormat.Jpeg.Subsampling;
import imagify.jpeg.jna.JpegliCodec;
import imagify.jpeg.jna.JpegliLibrary;

/**
 * Tests the {@code ImageIO} integration, and above all what happens when there is no jpegli to
 * integrate with.
 *
 * <p>JPEG is the one format this library replaces rather than adds to. The JDK already reads and
 * writes it, so registering a provider of our own is only safe if it steps aside the moment its
 * native library is missing: otherwise a single missing binary turns {@code ImageIO.read()} of a
 * JPEG into an exception for every user on that platform. The fallback half of these tests is
 * therefore the important half, and it is the half that runs on a machine with no bundled library at
 * all, which is the normal state of a source checkout.
 */
class JpegImageIOTest {

    // ------------------------------------------------------------------- service registration

    @Test
    @DisplayName("ImageIO discovers the JPEG reader and writer through the service files")
    void registration() {
        // Registered is not the same as usable: both providers are always registered, and both answer
        // false to what ImageIO asks when the library is missing.
        assertTrue(ImageIO.getImageReadersByFormatName("JPEG").hasNext(), "the reader is not registered");
        assertTrue(ImageIO.getImageWritersByFormatName("JPEG").hasNext(), "the writer is not registered");
        assertTrue(ImageIO.getImageReadersBySuffix("jpg").hasNext());
        assertTrue(ImageIO.getImageWritersBySuffix("jpg").hasNext());
        assertTrue(ImageIO.getImageReadersByMIMEType("image/jpeg").hasNext());
        assertTrue(ImageIO.getImageWritersByMIMEType("image/jpeg").hasNext());
    }

    @Test
    @DisplayName("our providers are found among the ones ImageIO knows for JPEG")
    void ourProvidersAreRegistered() {
        assertTrue(containsReader(), "ImageIO does not know about " + JpegImageReaderSpi.class.getName());
        assertTrue(containsWriter(), "ImageIO does not know about " + JpegImageWriterSpi.class.getName());
    }

    // ------------------------------------------------------------------ the fallback contract

    @Test
    @DisplayName("without jpegli the providers decline every JPEG, leaving the JDK's own to serve it")
    void providersStandDownWithoutTheLibrary() throws IOException {
        assumeTrue(!JpegliCodec.isAvailable(), "skipped: jpegli is available on this machine");
        BufferedImage image = gradient(32, 24);
        byte[] jpeg = writtenByTheJdk(image);

        assertFalse(new JpegImageWriterSpi().canEncodeImage(image),
                "a writer that cannot encode must say so, or it is offered images it will fail on");
        assertFalse(new JpegImageWriterSpi().canEncodeImage(
                        ImageTypeSpecifier.createFromRenderedImage(image)),
                "the image type is asked too, and the answer has to be the same one");
        assertFalse(new JpegImageReaderSpi().canDecodeInput(jpeg), "and likewise for the reader");
        assertFalse(new JpegImageReaderSpi().canDecodeInput(new ByteArrayInputStream(jpeg)),
                "for a plain stream as well as for the bytes");
        assertFalse(new JpegImageWriterSpi().canEncodeImage((java.awt.image.RenderedImage) null),
                "null is not an image");
        assertFalse(new JpegImageWriterSpi().canEncodeImage((ImageTypeSpecifier) null),
                "and null is not an image type either");
        assertFalse(new JpegImageReaderSpi().canDecodeInput(null), "and null is not a file");
    }

    @Test
    @DisplayName("without jpegli, ImageIO still writes a JPEG")
    void imageIoStillWritesAJpegWithoutTheLibrary() throws IOException {
        assumeTrue(!JpegliCodec.isAvailable(), "skipped: jpegli is available on this machine");
        BufferedImage image = gradient(32, 24);

        // ImageTypeSpecifier rather than the name alone: the name alone hands back every provider
        // registered under it, including this library's, and the point is that ImageIO finds a
        // writer that accepts the image without being asked to prefer the JDK's.
        ImageWriter writer = ImageIO.getImageWriters(
                ImageTypeSpecifier.createFromRenderedImage(image), "jpeg").next();
        Object provider = writer.getOriginatingProvider().getClass();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            try {
                writer.setOutput(out);
                writer.write(image);
            } finally {
                writer.dispose();
            }
            out.flush();
        }
        byte[] encoded = bytes.toByteArray();
        assertTrue(encoded.length > 2 && (encoded[0] & 0xFF) == 0xFF && (encoded[1] & 0xFF) == 0xD8,
                "what was written is not a JPEG");
        assertNotEquals(JpegImageWriterSpi.class, provider,
                "this library's writer declines every image while jpegli is missing, so it cannot "
                        + "be the one ImageIO reached for");
    }

    @Test
    @DisplayName("without jpegli, ImageIO still reads a JPEG")
    void imageIoStillReadsAJpegWithoutTheLibrary() throws IOException {
        assumeTrue(!JpegliCodec.isAvailable(), "skipped: jpegli is available on this machine");
        BufferedImage source = gradient(32, 24);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(writtenByTheJdk(source)));
        assertNotNull(decoded, "a perfectly good JPEG came back null");
        assertEquals(source.getWidth(), decoded.getWidth());
        assertEquals(source.getHeight(), decoded.getHeight());
    }

    @Test
    @DisplayName("a PNG is not a JPEG, and neither provider says it is")
    void refusesWhatIsNotAJpeg() throws IOException {
        assertFalse(new JpegImageReaderSpi().canDecodeInput(pngBytes()),
                "a perfectly good PNG is still not a JPEG");
        assertFalse(new JpegImageReaderSpi().canDecodeInput(new byte[0]));
        assertFalse(new JpegImageReaderSpi().canDecodeInput(new byte[] {(byte) 0xFF}),
                "one byte is not a start of image marker either");
        assertFalse(new JpegImageReaderSpi().canDecodeInput(
                new File("this-file-does-not-exist.jpg")));
    }

    @Test
    @DisplayName("the two byte test is the same one the JDK's own reader uses")
    void sniffingMatchesTheJdk() {
        assertTrue(JpegImageReaderSpi.isJpeg(new byte[] {(byte) 0xFF, (byte) 0xD8}, 0));
        assertTrue(JpegImageReaderSpi.isJpeg(new byte[] {1, 2, (byte) 0xFF, (byte) 0xD8}, 2),
                "the marker is at an offset too, which is what an embedded thumbnail looks like");
        assertFalse(JpegImageReaderSpi.isJpeg(new byte[] {(byte) 0xFF}, 0));
        assertFalse(JpegImageReaderSpi.isJpeg(new byte[0], 0));
        assertFalse(JpegImageReaderSpi.isJpeg(null, 0));
        assertFalse(JpegImageReaderSpi.isJpeg(new byte[] {(byte) 0xFF, (byte) 0xD8}, 1));
    }

    // ------------------------------------------------------------------------- the jpegli path

    @Test
    @DisplayName("with jpegli, ImageIO offers this library's providers for the jpeg format name")
    void providersAreOfferedToImageIoWithTheLibrary() throws IOException, JpegException {
        assumeTrue(JpegliCodec.isAvailable(), "skipped: no jpegli on this machine");
        BufferedImage image = gradient(32, 24);
        assertTrue(new JpegImageWriterSpi().canEncodeImage(image));
        assertTrue(new JpegImageReaderSpi().canDecodeInput(JpegliCodec.encode(image, 85)));

        // Membership, not position. The JDK registers its own JPEG provider from the boot class
        // loader, which ImageIO enumerates before the application loader's, so a jar cannot get
        // itself first in this list however it is registered, and nothing here relies on doing so:
        // imagify.ImageWriter and imagify.ImageReader both name this library's provider outright
        // rather than ask ImageIO to pick one. Asserting the order would only be asserting which
        // loader the JDK put its plug-ins in.
        List<String> writers = new ArrayList<>();
        Iterator<ImageWriter> found = ImageIO.getImageWritersByFormatName("jpeg");
        while (found.hasNext()) {
            ImageWriter writer = found.next();
            try {
                writers.add(writer.getOriginatingProvider().getClass().getName());
            } finally {
                writer.dispose();
            }
        }
        assertTrue(writers.contains(JpegImageWriterSpi.class.getName()),
                "the writer should be registered under the jpeg format name, got " + writers);

        List<String> readers = new ArrayList<>();
        Iterator<javax.imageio.ImageReader> foundReaders = ImageIO.getImageReadersByFormatName("jpeg");
        while (foundReaders.hasNext()) {
            javax.imageio.ImageReader reader = foundReaders.next();
            try {
                readers.add(reader.getOriginatingProvider().getClass().getName());
            } finally {
                reader.dispose();
            }
        }
        assertTrue(readers.contains(JpegImageReaderSpi.class.getName()),
                "the reader should be registered under the jpeg format name, got " + readers);
    }

    @Test
    @DisplayName("with jpegli, a JPEG written through ImageIO round trips")
    void roundTripThroughImageIo() throws IOException {
        assumeTrue(JpegliCodec.isAvailable(), "skipped: no jpegli on this machine");
        BufferedImage source = gradient(48, 32);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            ImageWriter writer = new JpegImageWriterSpi().createWriterInstance(null);
            try {
                writer.setOutput(out);
                writer.write(null, new IIOImage(source, null, null), writer.getDefaultWriteParam());
            } finally {
                writer.dispose();
            }
            out.flush();
        }

        BufferedImage decoded;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertNotNull(in);
            javax.imageio.ImageReader reader = new JpegImageReaderSpi().createReaderInstance(null);
            try {
                reader.setInput(in, false, true);
                assertEquals(1, reader.getNumImages(true), "a JPEG holds exactly one image");
                assertEquals(source.getWidth(), reader.getWidth(0));
                assertEquals(source.getHeight(), reader.getHeight(0));
                decoded = reader.read(0);
            } finally {
                reader.dispose();
            }
        }
        assertEquals(source.getWidth(), decoded.getWidth());
        assertEquals(source.getHeight(), decoded.getHeight());
    }

    @Test
    @DisplayName("the write parameters carry the settings an ImageWriteParam has no field for")
    void writeParamCarriesTheSettings() throws IOException {
        assumeTrue(JpegliCodec.isAvailable(), "skipped: no jpegli on this machine");
        ImageWriter writer = new JpegImageWriterSpi().createWriterInstance(null);
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            // The JDK's own JPEG writer answers a JPEGImageWriteParam here, and a caller casting to
            // that would get a ClassCastException from this one. JpegWriteParam is the same bargain
            // for the same reason: canWriteCompressed and compressionTypes are protected with no
            // setters, so this is the only way to extend an ImageWriteParam at all.
            assertInstanceOf(JpegWriteParam.class, param);
            JpegWriteParam jpeg = (JpegWriteParam) param;

            assertEquals(Subsampling.S420, jpeg.getSubsampling(),
                    "4:2:0 is the resolution an encoder writes unless told otherwise");
            assertFalse(jpeg.getOptimizeHuffmanTables(),
                    "the standard tables are the ones used unless the caller asks for the work");
            // Readable in the default mode, which is the point of the override: the runtime's own
            // getter throws unless the mode is MODE_EXPLICIT, and the mode has to be switched
            // before the quality can be set at all, so on a param nobody has touched the stock
            // getter would throw on a param that is perfectly usable.
            assertEquals(ImageWriteParam.MODE_DEFAULT, jpeg.getCompressionMode());
            assertEquals(JpegliLibrary.DEFAULT_QUALITY / 100f, jpeg.getCompressionQuality(), 1e-6f,
                    "the default quality is the one the format names, not the 0.5 the JDK plug-ins use");
            assertEquals("JPEG", jpeg.getCompressionType());
            assertTrue(jpeg.canWriteCompressed());

            jpeg.setSubsampling(Subsampling.S444);
            jpeg.setOptimizeHuffmanTables(true);
            assertEquals(Subsampling.S444, jpeg.getSubsampling());
            assertTrue(jpeg.getOptimizeHuffmanTables());

            jpeg.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            assertEquals("JPEG", jpeg.getCompressionType(),
                    "switching to MODE_EXPLICIT clears the type on this runtime, and this setter "
                            + "has to put it back or the mode is unusable");
            jpeg.setCompressionQuality(0.5f);
            assertEquals(0.5f, jpeg.getCompressionQuality(), 1e-6f,
                    "and with the mode explicit the caller's own quality is what comes back");

            assertThrows(IllegalArgumentException.class, () -> jpeg.setSubsampling(null));
        } finally {
            writer.dispose();
        }
    }

    @Test
    @DisplayName("the subsampling a caller asks for through the write parameters reaches the file")
    void writeParamSubsamplingReachesTheFile() throws IOException, JpegException {
        assumeTrue(JpegliCodec.isAvailable(), "skipped: no jpegli on this machine");
        for (Subsampling subsampling : Subsampling.values()) {
            ImageWriter writer = new JpegImageWriterSpi().createWriterInstance(null);
            try {
                JpegWriteParam param = (JpegWriteParam) writer.getDefaultWriteParam();
                param.setSubsampling(subsampling);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
                    writer.setOutput(out);
                    writer.write(null, new IIOImage(gradient(48, 48), null, null), param);
                    out.flush();
                } finally {
                    writer.reset();
                }
                JpegImageInfo info = JpegliCodec.readHeader(bytes.toByteArray());
                assertEquals(subsampling.horizontalFactor, info.horizontalFactor(), subsampling.toString());
                assertEquals(subsampling.verticalFactor, info.verticalFactor(), subsampling.toString());
            } finally {
                writer.dispose();
            }
        }
    }

    @Test
    @DisplayName("a writer with no output, or a reader with no input, says so rather than failing obscurely")
    void refusesToWorkWithoutAnInputOrOutput() throws IOException {
        assumeTrue(JpegliCodec.isAvailable(), "skipped: no jpegli on this machine");
        ImageWriter writer = new JpegImageWriterSpi().createWriterInstance(null);
        try {
            assertThrows(IIOException.class, () -> writer.write(null, new IIOImage(gradient(8, 8), null, null),
                    writer.getDefaultWriteParam()), "there is nowhere to write to");
        } finally {
            writer.dispose();
        }

        javax.imageio.ImageReader reader = new JpegImageReaderSpi().createReaderInstance(null);
        try {
            assertThrows(IIOException.class, () -> reader.read(0), "there is nothing to read");
        } finally {
            reader.dispose();
        }
    }

    @Test
    @DisplayName("the writer describes itself, which is what ImageIO prints when a lookup fails")
    void describesItself() {
        assertEquals("JPEG writer backed by jpegli",
                new JpegImageWriterSpi().getDescription(Locale.ENGLISH));
        assertEquals("JPEG reader backed by jpegli",
                new JpegImageReaderSpi().getDescription(Locale.ENGLISH));
        assertEquals("imagify.jpeg.JpegImageWriter", new JpegImageWriterSpi().getPluginClassName());
        assertEquals("imagify.jpeg.JpegImageReader", new JpegImageReaderSpi().getPluginClassName());
    }

    // -------------------------------------------------------------------------------- helpers

    private static boolean containsReader() {
        for (Iterator<javax.imageio.ImageReader> it = ImageIO.getImageReadersByFormatName("JPEG"); it.hasNext();) {
            if (it.next().getOriginatingProvider() instanceof JpegImageReaderSpi) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWriter() {
        for (Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("JPEG"); it.hasNext();) {
            if (it.next().getOriginatingProvider() instanceof JpegImageWriterSpi) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param image the image to encode
     * @return a JPEG written by the JDK's own writer, found by asking for every writer registered
     *         under the name and taking the one this library did not register
     */
    private static byte[] writtenByTheJdk(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            ImageWriter writer = jdkWriter();
            try {
                writer.setOutput(out);
                writer.write(image);
            } finally {
                writer.dispose();
            }
            out.flush();
        }
        return bytes.toByteArray();
    }

    /**
     * @return a writer for JPEG that is not this library's
     * @throws AssertionError when there is none, which would mean the JDK lost its own JPEG support
     */
    private static ImageWriter jdkWriter() {
        for (Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg"); it.hasNext();) {
            ImageWriter writer = it.next();
            if (!(writer.getOriginatingProvider() instanceof JpegImageWriterSpi)) {
                return writer;
            }
        }
        throw new AssertionError("the JDK has no JPEG writer of its own left");
    }

    private static byte[] pngBytes() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(gradient(16, 16), "png", bytes));
        return bytes.toByteArray();
    }

    /** A colour gradient, because flat colour is the one thing a lossy codec cannot be measured on. */
    private static BufferedImage gradient(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xFF000000
                        | (x * 255 / Math.max(1, width - 1)) << 16
                        | (y * 255 / Math.max(1, height - 1)) << 8
                        | ((x + y) * 255 / Math.max(1, width + height - 2)));
            }
        }
        return image;
    }
}
