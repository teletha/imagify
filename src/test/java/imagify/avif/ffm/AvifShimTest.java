/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.avif.ffm;

import imagify.avif.AvifException;
import imagify.avif.AvifImageInfo;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.awt.image.BufferedImage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the things only the shim can get wrong.
 *
 * <p>Everything else about AVIF in this project is covered by the {@code ImageIO} tests, which run
 * against this binding now that it is the one in use. What those cannot reach is this file's subject:
 * the C shim's own entry points, and the three properties that came out of the shim being built at
 * all.
 *
 * <p>Those are the CICP fields, the zero copy, and the loop count. Each of the three was wrong at
 * some point in this binding's own history, and none of them would have failed a test that only
 * checked a decoded image looks right.
 */
class AvifShimTest {

    private static byte[] still;

    @BeforeAll
    static void readTheTestFile() throws Exception {
        still = Files.readAllBytes(Path.of("src/test/resources/anime avif/220354.avif"));
    }

    // ------------------------------------------------------------------------------- zero copy

    @Nested
    @DisplayName("the zero copy")
    class ZeroCopy {

        /**
         * The pixels are read where the caller's array lies, not copied out of it first.
         *
         * <p>Two things have to hold for that, and this is the only test that checks either of them.
         * The entry point has to take the pixels as its own argument, because FFM will not store a
         * heap segment into a pointer-typed struct field and {@code avifRGBImage.pixels} is one. And
         * the downcall has to be made with {@link java.lang.foreign.Linker.Option#critical} set,
         * because without it FFM refuses the segment outright and the whole thing raises before
         * libavif is reached.
         *
         * <p>That second one fails loudly rather than silently, which is what makes it worth pinning:
         * a handle built without the option would turn every encode into an IllegalArgumentException.
         */
        @Test
        @DisplayName("a Java array is read in place rather than copied into native memory")
        void readsTheCallersArrayInPlace() throws Exception {
            AvifShim shim = requireShim();
            int width = 32;
            int height = 32;
            byte[] abgr = new byte[width * height * 4];
            // A gradient rather than a flat fill, so a conversion that went somewhere else would have
            // to leave a trace a flat fill could not.
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int at = (y * width + x) * 4;
                    abgr[at] = (byte) 0xFF;
                    abgr[at + 1] = (byte) (x * 8);
                    abgr[at + 2] = (byte) (y * 8);
                    abgr[at + 3] = (byte) ((x + y) * 4);
                }
            }
            byte[] before = abgr.clone();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment picture = shim.pictureCreate(width, height, 8, AvifConstants.PIXEL_FORMAT_YUV444);
                assertNotEquals(0, picture.address(), "the picture was not created");
                try {
                    // A MemorySegment over a Java array, handed to a call that is declared critical.
                    // FFM refuses this pair without the option, so reaching libavif at all is the
                    // assertion.
                    int status = shim.pictureFromAbgr(picture, MemorySegment.ofArray(abgr), width * 4,
                            AvifConstants.CHROMA_DOWNSAMPLING_AUTOMATIC);
                    assertEquals(0, status, "the shim refused the pixels it was given");
                } finally {
                    shim.pictureDestroy(picture);
                }
            }
            // A conversion into YUV reads the buffer and writes the picture's planes, which are
            // libavif's. A write through the caller's own array would mean the shim had handed libavif
            // something it was free to modify, and the pin would no longer be a promise.
            assertArrayEquals(before, abgr, "the caller's pixels were modified by the conversion");
        }

        @Test
        @DisplayName("encoding an image goes through the zero copy path and produces a file")
        void encodeGoesThroughTheShim() throws Exception {
            BufferedImage image = AvifCodec.decode(still).image();
            byte[] encoded = AvifCodec.encode(image, 60, 8, 100);
            assertTrue(AvifCodec.isAvif(encoded), "the encode did not produce an AVIF file");
            assertNotEquals(0, encoded.length);
        }
    }

    // ------------------------------------------------------------------------------------- CICP

    @Nested
    @DisplayName("the CICP fields")
    class Cicp {

        /**
         * The three CICP fields are read as numbers, not as three four byte reads.
         *
         * <p>This is the bug the JNA binding this replaces had. It declared CICP's three
         * {@code uint16_t} fields as {@code int}, which shifts everything after them by two bytes, and
         * the symptom was a colour primaries of 1 read back as 1 plus its neighbour shifted up: 131074
         * where the file says 1. Nothing else went wrong, so a test that only checked the picture
         * looked right passed for as long as the bug was there.
         */
        @Test
        @DisplayName("are the numbers the file says, not a short and the short after it")
        void areReadAsNumbers() throws Exception {
            AvifImageInfo info = AvifCodec.readHeader(still);
            // The file says "unspecified" in all three, which is 2. That is the number the JNA binding
            // reported as 131074, because it declared these three uint16_t fields as int and read a
            // short and the short after it as one number. So 2 is not just the right answer here, it
            // is the one value that distinguishes the two layouts.
            assertEquals(2, info.colorPrimaries(), "colour primaries were read as more than one number");
            assertEquals(2, info.transferCharacteristics(),
                    "transfer characteristics were read as more than one number");
            assertEquals(2, info.matrixCoefficients(),
                    "matrix coefficients were read as more than one number");
        }

        @Test
        @DisplayName("are all well under the value a misread layout produces")
        void areNotShifted() throws Exception {
            AvifImageInfo info = AvifCodec.readHeader(still);
            // A uint16_t of 2 read as a four byte int is 2, and the next one shifted up by sixteen bits
            // makes the pair 131074. Anything at or above 256 is the bug rather than a value.
            for (int value : new int[] {info.colorPrimaries(), info.transferCharacteristics(),
                    info.matrixCoefficients()}) {
                assertTrue(value >= 0 && value < 256,
                        "a CICP field read as " + value + " means the struct layout is wrong");
            }
        }

        @Test
        @DisplayName("agree between reading the header and decoding the picture, except for alpha")
        void headerAndDecodeAgree() throws Exception {
            AvifImageInfo header = AvifCodec.readHeader(still);
            AvifImageInfo decoded = AvifCodec.decode(still).info();
            // hasAlpha is excluded on purpose and readHeader says so: the alpha plane does not exist
            // until a frame is decoded, so the header parse has nothing to report. Comparing the rest
            // is what proves the two paths read the same fields the same way.
            assertEquals(decoded.width(), header.width());
            assertEquals(decoded.height(), header.height());
            assertEquals(decoded.depth(), header.depth());
            assertEquals(decoded.yuvFormat(), header.yuvFormat());
            assertEquals(decoded.yuvRange(), header.yuvRange());
            assertEquals(decoded.chromaSamplePosition(), header.chromaSamplePosition());
            assertEquals(decoded.colorPrimaries(), header.colorPrimaries());
            assertEquals(decoded.transferCharacteristics(), header.transferCharacteristics());
            assertEquals(decoded.matrixCoefficients(), header.matrixCoefficients());
            assertEquals(decoded.iccSize(), header.iccSize());
            assertEquals(decoded.exifSize(), header.exifSize());
            assertEquals(decoded.xmpSize(), header.xmpSize());
            assertEquals(decoded.rotationDegrees(), header.rotationDegrees());
            assertEquals(decoded.mirrored(), header.mirrored());
        }

        @ParameterizedTest(name = "colorPrimaries={0} -> {1}")
        @CsvSource({
                "0, Unknown",
                "1, BT709",
                "2, Unspecified",
                "6, BT601",
                "9, BT2020",
                "99, Unknown(99)",
        })
        @DisplayName("have a symbolic name, and a value outside the enumeration admits it")
        void names(int value, String expected) {
            assertEquals(expected, AvifCodec.colorPrimariesName(value));
        }
    }

    // -------------------------------------------------------------------------------- animation

    @Nested
    @DisplayName("an animation")
    class Animation {

        private static final int WIDTH = 48;
        private static final int HEIGHT = 32;

        private List<BufferedImage> frames(int count) {
            List<BufferedImage> frames = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_4BYTE_ABGR);
                for (int y = 0; y < HEIGHT; y++) {
                    for (int x = 0; x < WIDTH; x++) {
                        // A frame that visibly differs from its neighbours, so a frame decoded from
                        // the wrong one is obvious rather than a shade off.
                        int value = (x * 3 + i * 25) & 0xFF;
                        frame.setRGB(x, y, 0xFF000000 | (value << 16) | (value << 8) | value);
                    }
                }
                frames.add(frame);
            }
            return frames;
        }

        @Test
        @DisplayName("round trips its frames, its durations and its loop count")
        void roundTrips() throws Exception {
            List<BufferedImage> original = frames(5);
            // One of them is longer than the rest, so a container that lost the timing would still
            // report five frames and the right total.
            int[] durations = { 40, 40, 40, 150, 40 };
            byte[] encoded = AvifCodec.encodeAnimation(original, durations, 95, 0, 6, 100);
            assertTrue(AvifCodec.isAvif(encoded));

            try (AvifSequence sequence = AvifCodec.openSequence(encoded)) {
                assertEquals(5, sequence.frameCount());
                assertArrayEquals(durations, sequence.durationsMs());
                // 0 on this side means forever, and libavif counts plays and says so with -1. The
                // shim translates in both directions, so what comes back is what was asked for.
                assertEquals(0, sequence.loopCount(), "the shim leaked libavif's own convention");
                for (int i = 0; i < sequence.frameCount(); i++) {
                    assertEquals(WIDTH, sequence.width(i));
                    assertEquals(HEIGHT, sequence.height(i));
                }
                // Every frame has to come back as the frame that went in. The top left pixel is
                // enough, because it moves with the frame index.
                for (int i = 0; i < sequence.frameCount(); i++) {
                    BufferedImage decoded = sequence.frame(i);
                    assertEquals(BufferedImage.TYPE_4BYTE_ABGR, decoded.getType());
                    int expected = (i * 25) & 0xFF;
                    int actual = decoded.getRGB(0, 0) & 0xFF;
                    // q95 on a flat colour is close enough that exact equality would be a test of
                    // libavif's rate control rather than of the shim.
                    assertTrue(Math.abs(actual - expected) <= 2,
                            "frame " + i + " came back as " + actual + " where " + expected + " went in");
                }
            }
        }

        @Test
        @DisplayName("carries a loop count that is not forever")
        void carriesALoopCount() throws Exception {
            byte[] encoded = AvifCodec.encodeAnimation(frames(3), new int[] { 50, 50, 50 }, 90, 3, 6, 100);
            try (AvifSequence sequence = AvifCodec.openSequence(encoded)) {
                assertEquals(3, sequence.loopCount());
            }
        }

        @Test
        @DisplayName("decodes every frame at once when asked to")
        void decodeAnimationReturnsThemAll() throws Exception {
            byte[] encoded = AvifCodec.encodeAnimation(frames(4), new int[] { 30, 30, 30, 30 }, 90, 0, 6, 100);
            List<BufferedImage> decoded = AvifCodec.decodeAnimation(encoded);
            assertEquals(4, decoded.size());
            for (BufferedImage frame : decoded) {
                assertEquals(WIDTH, frame.getWidth());
                assertEquals(HEIGHT, frame.getHeight());
            }
        }

        @Test
        @DisplayName("reads the test file, which is a sequence, as every frame of it")
        void readsTheTestFileAsASequence() throws Exception {
            // This is not a still: it is the 58 frame animation the test resources carry, and treating
            // it as one is how a decoder that only ever looked at the first frame would pass.
            try (AvifSequence sequence = AvifCodec.openSequence(still)) {
                assertEquals(58, sequence.frameCount());
                assertEquals(498, sequence.width(0));
                assertEquals(280, sequence.height(0));
                assertEquals(498, sequence.width(57));
                assertEquals(280, sequence.height(57));
                assertEquals(58, sequence.durationsMs().length);
                // The last frame has to differ from the first, or a decoder that returned the same
                // frame every time would not be caught.
                assertNotEquals(sequence.frame(0).getRGB(100, 100), sequence.frame(57).getRGB(100, 100),
                        "two different frames decoded to the same pixel");
            }
        }

        @Test
        @DisplayName("reports a still as a sequence of one")
        void stillIsASequenceOfOne() throws Exception {
            // Encoded here rather than taken from the resources, because every AVIF file in them is a
            // sequence and a still is a distinct case: it is a file with no durations and no loop
            // count, and the shim has to treat it as a sequence of one rather than special case it.
            byte[] single = AvifCodec.encode(AvifCodec.decode(still).image(), 90, 6, 100);
            try (AvifSequence sequence = AvifCodec.openSequence(single)) {
                assertEquals(1, sequence.frameCount());
                assertEquals(498, sequence.width(0));
                assertEquals(280, sequence.height(0));
            }
        }

        @Test
        @DisplayName("is rejected without a delay for every frame")
        void needsOneDelayPerFrame() throws Exception {
            List<BufferedImage> original = frames(3);
            assertThrows(AvifException.class,
                    () -> AvifCodec.encodeAnimation(original, new int[] { 50, 50 }, 90, 0),
                    "two delays for three frames was accepted");
            assertThrows(AvifException.class,
                    () -> AvifCodec.encodeAnimation(original, null, 90, 0),
                    "no delays at all was accepted");
        }

        @Test
        @DisplayName("is rejected when the frames are not all the same size")
        void needsFramesOfOneSize() throws Exception {
            List<BufferedImage> original = frames(2);
            original.add(new BufferedImage(WIDTH + 1, HEIGHT, BufferedImage.TYPE_4BYTE_ABGR));
            assertThrows(AvifException.class,
                    () -> AvifCodec.encodeAnimation(original, new int[] { 50, 50, 50 }, 90, 0));
        }
    }

    // -------------------------------------------------------------------------------- inspection

    @Nested
    @DisplayName("recognising a file")
    class Inspection {

        @Test
        @DisplayName("accepts the avif and avis brands and refuses everything else")
        void brands() {
            assertTrue(AvifCodec.isAvif(still));
            // "avis" is the brand an image sequence carries, so a still must not be the only thing
            // that is recognised or a sequence is unreadable. The test file happens to be one.
            assertTrue(AvifCodec.isAvif(rebrand(still, "avis")));
        }

        @Test
        @DisplayName("refuses an ftyp box with no AVIF brand in it")
        void refusesAnFtypWithNoAvifInIt() {
            // Rebranding the major brand alone is not enough to make a file unreadable, and should not
            // be: a file may name any major brand and list avif among the compatible ones, which is
            // what a still inside a HEIF sequence does. So the rejection is tested on a box that has
            // no AVIF brand anywhere in it rather than on a rebranded real file.
            assertFalse(AvifCodec.isAvif(ftyp("heic", "mif1", "heic")));
            assertTrue(AvifCodec.isAvif(ftyp("avif", "mif1", "miaf")));
            assertTrue(AvifCodec.isAvif(ftyp("mif1", "avif", "miaf")),
                    "an AVIF brand in the compatible list is enough");
        }

        /**
         * A minimal {@code ftyp} box, which is all {@code isAvif} looks at.
         *
         * <p>The minor version between the major brand and the compatible list is not optional, and
         * leaving it out is a mistake that produces a box whose second brand is read as the first
         * compatible one, which is a test that passes for the wrong reason.
         *
         * @param brands four character brands: the major one first, then the compatible ones
         * @return a box naming them, sized to hold them all
         */
        private static byte[] ftyp(String... brands) {
            int size = 16 + brands.length * 4;
            ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
            buffer.putInt(size);
            buffer.put("ftyp".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            // size, 'ftyp', major_brand, minor_version, compatible_branches
            buffer.put(brands[0].getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            buffer.putInt(0);
            for (int i = 1; i < brands.length; i++) {
                buffer.put(brands[i].getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            return buffer.array();
        }

        @Test
        @DisplayName("is decided in Java, so it works with no library at all")
        void worksWithoutTheLibrary() {
            // Pure Java on purpose: the ImageIO plug-in has to recognise an AVIF file to be able to
            // offer to read it, and a machine that cannot load the library is exactly when a user
            // most needs to be told what the file is.
            assertTrue(AvifCodec.isAvif(still));
            assertFalse(AvifCodec.isAvif(null));
            assertFalse(AvifCodec.isAvif(new byte[0]));
            assertFalse(AvifCodec.isAvif("not an avif file at all".getBytes()));
            assertFalse(AvifCodec.isAvif(Arrays.copyOf(still, 8)));
        }

        @Test
        @DisplayName("reports the libavif it was linked against")
        void reportsItsVersion() throws Exception {
            requireShim();
            String version = AvifCodec.getVersion();
            assertTrue(version != null && version.startsWith("1."),
                    "libavif version was " + version);
        }
    }

    // ------------------------------------------------------------------------------- arguments

    @Nested
    @DisplayName("refusing bad input")
    class Arguments {

        @Test
        @DisplayName("nothing is not a file to decode")
        void nullIsNotAFile() {
            assertThrows(AvifException.class, () -> AvifCodec.decode(null));
            assertThrows(AvifException.class, () -> AvifCodec.readHeader(null));
            assertThrows(AvifException.class, () -> AvifCodec.openSequence(new byte[0]));
        }

        @Test
        @DisplayName("a truncated file is refused rather than read past")
        void truncatedIsRefused() {
            byte[] half = Arrays.copyOf(still, still.length / 2);
            assumeTrue(AvifCodec.isAvif(half), "skipped: half the file has no ftyp box left");
            assertThrows(AvifException.class, () -> AvifCodec.decode(half));
        }

        @Test
        @DisplayName("a frame that is not there is an index problem, not a decode failure")
        void frameOutOfBounds() throws Exception {
            try (AvifSequence sequence = AvifCodec.openSequence(still)) {
                // The test file holds 58 frames, so 58 is one past the end and 57 is the last one.
                assertThrows(IndexOutOfBoundsException.class, () -> sequence.frame(58));
                assertThrows(IndexOutOfBoundsException.class, () -> sequence.frame(-1));
                assertThrows(IndexOutOfBoundsException.class, () -> sequence.width(58));
                assertThrows(IndexOutOfBoundsException.class, () -> sequence.durationMs(58));
            }
        }
    }

    private static AvifShim requireShim() throws AvifException {
        AvifShim shim = AvifLoader.shim();
        assumeTrue(shim != null, () -> "skipped: " + AvifLoader.unavailableReason());
        return shim;
    }

    /**
     * The same file with its major brand replaced, which is the only thing {@code isAvif} looks at
     * before the compatible list.
     *
     * @param file a file whose major brand is four bytes at offset 8
     * @param brand the four character brand to put there instead
     * @return a copy of the file carrying that brand
     */
    private static byte[] rebrand(byte[] file, String brand) {
        byte[] rebranded = Arrays.copyOf(file, file.length);
        System.arraycopy(brand.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, rebranded, 8, 4);
        return rebranded;
    }
}
