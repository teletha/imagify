/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.jpeg.jna;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.awt.image.BufferedImage;
import java.util.Arrays;

import imagify.ImageFormat.Jpeg.Subsampling;
import imagify.jpeg.JpegException;
import imagify.jpeg.JpegImageInfo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests the jpegli codec, and the contract it keeps when there is no jpegli to call.
 *
 * <p>The two halves matter for opposite reasons. When the native library is present the encode and
 * decode paths are the ones that would silently corrupt pixels if the pixel layout or the argument
 * order were wrong, so they are held to a real round trip. When it is absent nothing above this class
 * may notice, which is a claim about behaviour and is just as easy to break by accident: a
 * {@code NoClassDefFoundError} escaping from a SPI registration would take every other format down
 * with it, so the unavailable path is pinned here too rather than left to the JDK to discover.
 *
 * <p>A machine with no bundled library for its platform skips the encode and decode half. That is the
 * normal state of a source checkout; the workflow that fills the directory in is what makes them run.
 */
class JpegliCodecTest {

    @Test
    @DisplayName("availability, the reason and the handle never disagree")
    void availabilityIsConsistent() throws JpegException {
        // The three answers come from one cached load, so a caller that asks any of them first is
        // never told "yes" by one and "no" by another, which is what would let a provider claim a
        // format it then fails to encode.
        assertEquals(JpegliCodec.isAvailable(), JpegliCodec.getUnavailableReason() == null,
                "isAvailable() and getUnavailableReason() must be two views of one answer");
        assertEquals(JpegliCodec.isAvailable(), JpegliCodec.library() != null);

        if (JpegliCodec.isAvailable()) {
            assertNotNull(JpegliCodec.library());
            assertSame(JpegliCodec.library(), JpegliCodec.library(), "the handle must not be reloaded");
            assertNull(JpegliCodec.getUnavailableReason());
            assertNotNull(JpegliCodec.getVersion(), "a loaded library can report the version it is");
            assertSame(JpegliCodec.library(), JpegliCodec.requireLibrary());
        } else {
            // The reason is what a user is shown, so it must say what failed rather than be null,
            // which would read as "available, nothing to report".
            assertNotNull(JpegliCodec.getUnavailableReason());
            JpegException e = assertThrows(JpegException.class, JpegliCodec::requireLibrary);
            assertEquals(JpegliCodec.getUnavailableReason(), e.getMessage(),
                    "the exception must carry the same reason the accessor reports");
        }
    }

    @Test
    @DisplayName("an unavailable library is not an error, it is the JDK's JPEG support taking over")
    void unavailableIsNotFatal() {
        assumeTrue(!JpegliCodec.isAvailable(), "skipped: jpegli is available on this machine");
        assertThrows(JpegException.class, () -> JpegliCodec.encode(gradient(16, 16), 85));
        assertThrows(JpegException.class, () -> JpegliCodec.decode(new byte[] {(byte) 0xFF, (byte) 0xD8}));
        assertThrows(JpegException.class, () -> JpegliCodec.readHeader(new byte[] {(byte) 0xFF, (byte) 0xD8}));
    }

    @ParameterizedTest(name = "status {0} is {1}")
    @CsvSource({
            "0, 'no error'",
            "1, 'a parameter was out of range'",
            "2, 'the input is not a JPEG, or is a damaged one'",
            "3, 'out of memory'",
            "4, 'jpegli failed for no stated reason'",
    })
    @DisplayName("every status the shim can return has something to say")
    void statusNames(int status, String expected) {
        assertEquals(expected, JpegliCodec.statusName(status));
    }

    @Test
    @DisplayName("a status nobody defined is reported rather than guessed at")
    void unknownStatus() {
        assertEquals("status 99", JpegliCodec.statusName(99));
    }

    @Test
    @DisplayName("the ABI version the binding speaks is the one the header declares")
    void abiVersion() {
        assertEquals("1", JpegliLibrary.ABI_VERSION);
        assumeTrue(JpegliCodec.isAvailable(), "skipped: no jpegli to ask");
        assertEquals(JpegliLibrary.ABI_VERSION, JpegliCodec.library().imagify_jpegli_abi_version(),
                "a library speaking another ABI has to be refused at load time");
    }

    @Test
    @DisplayName("an image survives the round trip through the encoder and the decoder")
    void roundTrip() throws JpegException {
        assumeTrue(JpegliCodec.isAvailable(), "skipped: no jpegli to encode or decode with");
        BufferedImage source = gradient(64, 48);

        byte[] encoded = JpegliCodec.encode(source, 100);
        assertTrue(encoded.length > 2 && (encoded[0] & 0xFF) == 0xFF && (encoded[1] & 0xFF) == 0xD8,
                "the file does not start with a start of image marker");
        assertEquals(0xFF, encoded[encoded.length - 2] & 0xFF, "and does not end with one either");

        JpegImageInfo info = JpegliCodec.readHeader(encoded);
        assertEquals(64, info.width());
        assertEquals(48, info.height());
        assertEquals(3, info.components());
        assertFalse(info.greyscale());
        assertFalse(info.progressive(), "this encoder writes a baseline file");
        assertEquals(8, info.precision());

        BufferedImage decoded = JpegliCodec.decode(encoded);
        assertEquals(64, decoded.getWidth());
        assertEquals(48, decoded.getHeight());

        // A JPEG cannot carry an alpha channel, so a decode must come back fully opaque rather than
        // partially transparent. Decoding it as anything else would change every pixel a caller goes
        // on to compare against, which is why nothing composites it over a background here.
        int[] argb = decoded.getRGB(0, 0, decoded.getWidth(), decoded.getHeight(), null, 0, decoded.getWidth());
        for (int i = 0; i < argb.length; i++) {
            final int pixel = i;
            assertEquals(255, argb[i] >>> 24, () -> "pixel " + pixel + " came back translucent");
        }
    }

    @Test
    @DisplayName("a higher quality keeps more of the picture in a bigger file")
    void qualityFollowsQuality() throws JpegException {
        assumeTrue(JpegliCodec.isAvailable(), "skipped: no jpegli to encode with");
        BufferedImage source = gradient(64, 64);

        byte[] smallest = JpegliCodec.encode(source, 10);
        byte[] middle = JpegliCodec.encode(source, 50);
        byte[] largest = JpegliCodec.encode(source, 100);

        assertTrue(smallest.length < middle.length && middle.length < largest.length,
                "quality should buy file size: " + smallest.length + ", " + middle.length + " and "
                        + largest.length);
        assertFalse(Arrays.equals(pixels(JpegliCodec.decode(smallest)), pixels(JpegliCodec.decode(largest))),
                "a large and a small file are different encodings of one picture, not of two");
    }

    @Test
    @DisplayName("a quality outside 1..100 is clamped rather than refused")
    void qualityIsClamped() throws JpegException {
        // The ImageIO compression quality is a 0.0 to 1.0 value, so a caller asking for the smallest
        // possible file asks for 0, which is legal everywhere else this library is asked to write a
        // JPEG. Throwing would turn a legal request into an exception.
        assumeTrue(JpegliCodec.isAvailable(), "skipped: no jpegli to encode with");
        BufferedImage source = gradient(32, 32);
        for (int quality : new int[] {Integer.MIN_VALUE, -1, 0, 1, 100, 101, Integer.MAX_VALUE}) {
            byte[] encoded = JpegliCodec.encode(source, quality);
            assertEquals(32, JpegliCodec.readHeader(encoded).width(), "at quality " + quality);
        }
        assertArrayEquals(JpegliCodec.encode(source, 0), JpegliCodec.encode(source, 1),
                "0 is the smallest file, which the encoder expresses as 1");
        assertArrayEquals(JpegliCodec.encode(source, 200), JpegliCodec.encode(source, 100));
    }

    @Test
    @DisplayName("the frame header carries the colour resolution that was asked for")
    void subsamplingReachesTheFrameHeader() throws JpegException {
        assumeTrue(JpegliCodec.isAvailable(), "skipped: no jpegli to encode with");
        BufferedImage source = gradient(64, 64);

        for (Subsampling subsampling : Subsampling.values()) {
            JpegImageInfo info = JpegliCodec.readHeader(
                    JpegliCodec.encode(source, 90, subsampling.samp, false));
            assertArrayEquals(new int[] {subsampling.horizontalFactor, subsampling.verticalFactor},
                    new int[] {info.horizontalFactor(), info.verticalFactor()},
                    subsampling + " should reach the luma sampling factors of the frame header");
        }
    }

    @Test
    @DisplayName("subsampling costs nothing in the quality and shows in the file size")
    void subsamplingFollowsSubsampling() throws JpegException {
        assumeTrue(JpegliCodec.isAvailable(), "skipped: no jpegli to encode with");
        BufferedImage source = gradient(64, 64);

        byte[] smallest = JpegliCodec.encode(source, 85, JpegliLibrary.IMAGIFY_JPEG_SAMP_420, false);
        byte[] largest = JpegliCodec.encode(source, 85, JpegliLibrary.IMAGIFY_JPEG_SAMP_444, false);
        assertTrue(smallest.length < largest.length,
                "4:2:0 stores half the colour channels in each direction and should be the smaller file: "
                        + smallest.length + " against " + largest.length);
    }

    @Test
    @DisplayName("computing the entropy coder tables costs a smaller file and no pixels")
    void optimizedTablesAreFree() throws JpegException {
        assumeTrue(JpegliCodec.isAvailable(), "skipped: no jpegli to encode with");
        BufferedImage source = gradient(64, 64);

        byte[] standard = JpegliCodec.encode(source, 85, JpegliLibrary.IMAGIFY_JPEG_SAMP_420, false);
        byte[] optimised = JpegliCodec.encode(source, 85, JpegliLibrary.IMAGIFY_JPEG_SAMP_420, true);

        assertFalse(Arrays.equals(standard, optimised),
                "the same image at the same quality should not produce the same file");
        assertTrue(optimised.length < standard.length,
                "the tables were computed from the image, so the file should be smaller: "
                        + optimised.length + " against " + standard.length);
        // The tables change how the coefficients are coded and nothing else, which is what makes this
        // a free win rather than a quality setting.
        assertArrayEquals(pixels(JpegliCodec.decode(standard)), pixels(JpegliCodec.decode(optimised)),
                "the same pixels should come back either way, only the file should differ");
    }

    @Test
    @DisplayName("the alpha byte of the source is discarded, because a JPEG has nowhere to put it")
    void encodeDropsAlpha() throws JpegException {
        assumeTrue(JpegliCodec.isAvailable(), "skipped: no jpegli to encode with");
        BufferedImage opaque = gradient(32, 32);
        BufferedImage translucent = new BufferedImage(32, 32, BufferedImage.TYPE_4BYTE_ABGR);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                translucent.setRGB(x, y, 0x00000000 | (opaque.getRGB(x, y) & 0x00FFFFFF));
            }
        }

        // The picture is written against whatever sits behind a transparent pixel rather than being
        // composited over a background of the encoder's choosing, so the two agree here.
        assertArrayEquals(JpegliCodec.encode(opaque, 85), JpegliCodec.encode(translucent, 85),
                "a fully transparent image is the same picture without its alpha");
    }

    @Test
    @DisplayName("anything that is not a JPEG is refused with something a caller can read")
    void refusesWhatIsNotAJpeg() {
        assumeTrue(JpegliCodec.isAvailable(), "skipped: no jpegli to decode with");
        // A PNG is the interesting one: it is a perfectly good image, it is simply not a JPEG, and
        // decoding it anyway is how a reader ends up returning uninitialised memory.
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        for (byte[] input : new byte[][] {png, new byte[0], {(byte) 0xFF}, {(byte) 0xFF, (byte) 0xD8},
                {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'}}) {
            assertThrows(JpegException.class, () -> JpegliCodec.decode(input));
            assertThrows(JpegException.class, () -> JpegliCodec.readHeader(input));
        }
    }

    @Test
    @DisplayName("a file that is truncated after its frame header is refused, not half decoded")
    void refusesATruncatedFile() throws JpegException {
        assumeTrue(JpegliCodec.isAvailable(), "skipped: no jpegli to decode with");
        byte[] encoded = JpegliCodec.encode(gradient(64, 64), 85);
        assertThrows(JpegException.class, () -> JpegliCodec.decode(Arrays.copyOf(encoded, encoded.length / 2)),
                "half a JPEG is not half a picture");
    }

    @Test
    @DisplayName("null and empty images and inputs are refused before anything is allocated")
    void refusesNothingAtAll() {
        assumeTrue(JpegliCodec.isAvailable(), "skipped: no jpegli to call");
        assertThrows(JpegException.class, () -> JpegliCodec.encode(null, 85));
        assertThrows(JpegException.class, () -> JpegliCodec.decode(null));
        assertThrows(JpegException.class, () -> JpegliCodec.readHeader(null));
        assertThrows(JpegException.class, () -> JpegliCodec.encode(new BufferedImage(0, 0, BufferedImage.TYPE_INT_RGB), 85));
    }

    @Test
    @DisplayName("an image of any layout the encoder cannot read directly is converted first")
    void encodesAwkwardLayouts() throws JpegException {
        assumeTrue(JpegliCodec.isAvailable(), "skipped: no jpegli to encode with");
        int[][] types = {
                {BufferedImage.TYPE_INT_RGB, 3},
                {BufferedImage.TYPE_INT_ARGB, 4},
                {BufferedImage.TYPE_4BYTE_ABGR, 4},
                {BufferedImage.TYPE_BYTE_GRAY, 1},
                {BufferedImage.TYPE_3BYTE_BGR, 3},
                {BufferedImage.TYPE_USHORT_565_RGB, 3},
        };
        for (int[] type : types) {
            BufferedImage source = new BufferedImage(32, 24, type[0]);
            byte[] encoded = JpegliCodec.encode(source, 90);
            assertEquals(32, JpegliCodec.readHeader(encoded).width(), "type " + type[0]);
            assertEquals(24, JpegliCodec.readHeader(encoded).height(), "type " + type[0]);
            assertEquals(32, JpegliCodec.decode(encoded).getWidth(), "type " + type[0]);
        }
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

    /**
     * @param image the image to read every pixel of
     * @return the pixels as ARGB, so that two images can be compared
     */
    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }
}
