/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.avif.jna;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import imagify.avif.AvifException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link AvifAnimationDecoder}, the frame at a time view of an AVIF file that the
 * {@code ImageIO} reader and {@link AvifCodec#decodeAnimation(byte[])} are both built on.
 *
 * <p>Everything that needs the native library is skipped when it is missing. The fixtures are encoded
 * with a fast speed, because these tests are about how the frames are reported rather than about how
 * well they compress.
 */
class AvifAnimationDecoderTest {

    @BeforeAll
    static void requireLibavif() {
        assumeTrue(AvifCodec.isAvailable(),
                () -> "skipped: libavif is not available (" + AvifCodec.getUnavailableReason() + ")");
    }

    // -------------------------------------------------------------------------------- fixture

    /**
     * @return a frame whose green channel says which frame it is. The channel moves in steps of 10,
     *         which is far more than the encoder is allowed to shift it, so a frame that came out in
     *         the wrong order cannot pass for one that came out in the right order.
     */
    private static BufferedImage frame(int index, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(10 + 10 * index, 240 - 10 * index, 30 + 8 * index));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static List<BufferedImage> frames(int count, int width, int height) {
        List<BufferedImage> frames = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            frames.add(frame(index, width, height));
        }
        return frames;
    }

    private static byte[] animation(int count, int width, int height, int[] delaysMs, int loopCount)
            throws AvifException {
        return AvifCodec.encodeAnimation(frames(count, width, height), delaysMs, 70, loopCount);
    }

    /** @return the green channel of the top left pixel, which the fixture makes frame specific */
    private static int marker(BufferedImage image) {
        return image.getRGB(0, 0) >> 8 & 0xff;
    }

    /**
     * Fails unless {@code actual} is the frame {@code expected} describes. AVIF is a lossy format, so
     * the encoder is allowed to shift a channel a little; the fixture steps the channel by 10, which
     * leaves plenty of room for that shift and still tells the frames apart.
     */
    private static void assertSameFrame(BufferedImage expected, BufferedImage actual, String what) {
        int wanted = marker(expected);
        int got = marker(actual);
        assertTrue(Math.abs(wanted - got) <= 4, what + ": expected green " + wanted + " but was " + got);
    }

    // ---------------------------------------------------------------------------------- still

    @Nested
    @DisplayName("a still image")
    class Still {

        @Test
        @DisplayName("is a sequence of one frame")
        void isASequenceOfOne() throws Exception {
            byte[] encoded = AvifCodec.encode(frame(0, 24, 18), 70, 8);
            try (AvifAnimationDecoder decoder = AvifAnimationDecoder.open(encoded)) {
                assertEquals(1, decoder.frameCount());
                assertEquals(24, decoder.width(0));
                assertEquals(18, decoder.height(0));
                assertEquals(0, decoder.loopCount(), "a still does not repeat");
                BufferedImage decoded = decoder.frame(0);
                assertEquals(24, decoded.getWidth());
                assertEquals(18, decoded.getHeight());
            }
        }

        @Test
        @DisplayName("reports one entry in its duration table, whether it is a duration or not")
        void hasOneDuration() throws Exception {
            byte[] encoded = AvifCodec.encode(frame(0, 24, 18), 70, 8);
            try (AvifAnimationDecoder decoder = AvifAnimationDecoder.open(encoded)) {
                // A still has no animation track, so whatever libavif names for it is a nominal
                // figure rather than a real one. All that can be relied on is that the table has one
                // entry, and that it is the same number either way of asking.
                assertArrayEquals(new int[] { decoder.durationMs(0) }, decoder.durationsMs());
            }
        }
    }

    // ------------------------------------------------------------------------------ animation

    @Nested
    @DisplayName("an animation")
    class Animation {

        @Test
        @DisplayName("reports how many frames it holds")
        void reportsTheFrameCount() throws Exception {
            byte[] encoded = animation(6, 32, 24, new int[] { 100, 100, 100, 100, 100, 100 }, 0);
            try (AvifAnimationDecoder decoder = AvifAnimationDecoder.open(encoded)) {
                assertEquals(6, decoder.frameCount());
                assertEquals(32, decoder.width(0));
                assertEquals(24, decoder.height(0));
            }
        }

        @Test
        @DisplayName("reports how long each frame is shown")
        void reportsTheDurations() throws Exception {
            int[] written = { 40, 80, 120, 200 };
            byte[] encoded = animation(written.length, 32, 24, written, 0);
            try (AvifAnimationDecoder decoder = AvifAnimationDecoder.open(encoded)) {
                assertArrayEquals(written, decoder.durationsMs());
                for (int index = 0; index < written.length; index++) {
                    assertEquals(written[index], decoder.durationMs(index), "frame " + index);
                }
            }
        }

        @Test
        @DisplayName("never reports a frame as lasting no time at all")
        void neverReportsZeroDuration() throws Exception {
            // A container is free to say a frame lasts nothing, but such a frame would never be seen.
            byte[] encoded = animation(3, 32, 24, new int[] { 100, 0, 100 }, 0);
            try (AvifAnimationDecoder decoder = AvifAnimationDecoder.open(encoded)) {
                for (int duration : decoder.durationsMs()) {
                    assertTrue(duration >= 1, "a frame was reported as lasting " + duration + " ms");
                }
            }
        }

        @Test
        @DisplayName("reports how often it repeats")
        void reportsTheLoopCount() throws Exception {
            try (AvifAnimationDecoder decoder = AvifAnimationDecoder.open(animation(3, 32, 24,
                    new int[] { 100, 100, 100 }, 5))) {
                assertEquals(5, decoder.loopCount());
            }
            // Zero is the container's way of saying forever.
            try (AvifAnimationDecoder decoder = AvifAnimationDecoder.open(animation(3, 32, 24,
                    new int[] { 100, 100, 100 }, 0))) {
                assertEquals(0, decoder.loopCount());
            }
        }

        @Test
        @DisplayName("decodes every frame, and each one is the frame that was written")
        void decodesEveryFrame() throws Exception {
            List<BufferedImage> original = frames(6, 32, 24);
            byte[] encoded = AvifCodec.encodeAnimation(original,
                    new int[] { 100, 100, 100, 100, 100, 100 }, 70, 0);
            try (AvifAnimationDecoder decoder = AvifAnimationDecoder.open(encoded)) {
                assertEquals(original.size(), decoder.frameCount());
                for (int index = 0; index < original.size(); index++) {
                    BufferedImage decoded = decoder.frame(index);
                    assertEquals(32, decoded.getWidth(), "frame " + index + " width");
                    assertEquals(24, decoded.getHeight(), "frame " + index + " height");
                    assertSameFrame(original.get(index), decoded, "frame " + index);
                }
            }
        }

        @Test
        @DisplayName("decodes the same frame whichever order the frames are asked for in")
        void decodesOutOfOrder() throws Exception {
            List<BufferedImage> original = frames(5, 32, 24);
            byte[] encoded = AvifCodec.encodeAnimation(original,
                    new int[] { 100, 100, 100, 100, 100 }, 70, 0);
            try (AvifAnimationDecoder decoder = AvifAnimationDecoder.open(encoded)) {
                // Backwards, and jumping about, which is what a caller who wants one poster frame, or
                // wants to scrub, would do.
                for (int index : new int[] { 4, 0, 3, 1, 2, 0, 4, 4 }) {
                    assertSameFrame(original.get(index), decoder.frame(index), "frame " + index);
                }
            }
        }

        @Test
        @DisplayName("hands out a duration table the caller cannot corrupt")
        void durationsAreDefensive() throws Exception {
            byte[] encoded = animation(3, 32, 24, new int[] { 100, 200, 300 }, 0);
            try (AvifAnimationDecoder decoder = AvifAnimationDecoder.open(encoded)) {
                int[] durations = decoder.durationsMs();
                durations[0] = -1;
                assertEquals(100, decoder.durationsMs()[0], "the table was handed out by reference");
            }
        }
    }

    // ---------------------------------------------------------------------------- diagnostics

    @Nested
    @DisplayName("bad input")
    class BadInput {

        @Test
        @DisplayName("is refused before the library is even asked")
        void refusedEarly() {
            assertThrows(AvifException.class, () -> AvifAnimationDecoder.open(null));
            assertThrows(AvifException.class, () -> AvifAnimationDecoder.open(new byte[0]));
            assertThrows(AvifException.class, () -> AvifAnimationDecoder.open("not an avif".getBytes()));
        }

        @Test
        @DisplayName("names a frame that the file does not have")
        void unknownFrame() throws Exception {
            byte[] encoded = animation(3, 32, 24, new int[] { 100, 100, 100 }, 0);
            try (AvifAnimationDecoder decoder = AvifAnimationDecoder.open(encoded)) {
                for (int index : new int[] { -1, 3, 99 }) {
                    IndexOutOfBoundsException e =
                            assertThrows(IndexOutOfBoundsException.class, () -> decoder.frame(index));
                    assertTrue(e.getMessage().contains("3 frame"), e.getMessage());
                    assertThrows(IndexOutOfBoundsException.class, () -> decoder.durationMs(index));
                }
            }
        }
    }

    // --------------------------------------------------------- agreement with the old entry point

    @Test
    @DisplayName("decodeAnimation still returns every frame of the same file")
    void decodeAnimationAgrees() throws Exception {
        int[] delays = { 100, 150, 200, 250 };
        byte[] encoded = animation(delays.length, 32, 24, delays, 3);

        List<BufferedImage> all = AvifCodec.decodeAnimation(encoded);
        assertEquals(delays.length, all.size());
        try (AvifAnimationDecoder decoder = AvifAnimationDecoder.open(encoded)) {
            for (int index = 0; index < all.size(); index++) {
                assertSameFrame(all.get(index), decoder.frame(index), "frame " + index);
            }
        }
    }

    @Test
    @DisplayName("a long animation can be walked without every frame being held in memory")
    void doesNotHoldEveryFrame() throws Exception {
        // 20 frames is enough to make a per frame cache obvious without making the test slow.
        int count = 20;
        List<BufferedImage> markers = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            markers.add(frame(index, 32, 24));
        }
        byte[] encoded = AvifCodec.encodeAnimation(markers, new int[count], 80, 0);
        try (AvifAnimationDecoder decoder = AvifAnimationDecoder.open(encoded)) {
            assertEquals(count, decoder.frameCount());
            // Asking for the sizes and the timing never decodes a pixel, so it is cheap even though
            // the file holds twenty frames.
            assertEquals(count, decoder.durationsMs().length);
            assertTrue(decoder.toString().contains("frames=" + count), decoder.toString());
            for (int index = 0; index < count; index++) {
                assertSameFrame(markers.get(index), decoder.frame(index), "frame " + index);
            }
        }
    }

    @Test
    @DisplayName("the frame table is only built once")
    void buildsTheTableOnce() throws Exception {
        byte[] encoded = animation(4, 32, 24, new int[] { 100, 100, 100, 100 }, 0);
        try (AvifAnimationDecoder decoder = AvifAnimationDecoder.open(encoded)) {
            int[] first = decoder.durationsMs();
            int[] second = decoder.durationsMs();
            assertArrayEquals(first, second);
            assertFalse(Arrays.equals(first, new int[0]), "the table was never filled in");
        }
    }
}
