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
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadata;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import imagify.webp.ffm.WebpCodec;


/**
 * Tests that an animated WebP reports its frame timing and its loop count through the standard
 * {@code ImageIO} metadata, which is the only route {@link imagify.ImageReader} has.
 *
 * <p>
 * Two things used to be missing here. The reader returned {@code null} stream metadata, so the
 * loop count was thrown away and every animation looked as though it repeated forever; and the
 * frame
 * durations were never published, so every frame of every animation was reported as lasting a
 * second. Both are read out of the animation without decoding any pixels here, so these tests only
 * need the native library to be present, not any particular encoder.
 */
class AnimatedWebpImageIOTest {

    @BeforeAll
    static void requireLibwebp() {
        assumeTrue(WebpCodec.isAvailable(), () -> "skipped: libwebp is not available (" + WebpCodec.getUnavailableReason() + ")");
    }

    // -------------------------------------------------------------------------------- fixture

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

    private static byte[] animation(int count, int width, int height, int[] delaysMs, int loopCount) {
        try {
            return WebpCodec.encodeAnimation(frames(count, width, height), delaysMs, 80, false, loopCount);
        } catch (WebpException e) {
            throw new AssertionError(e);
        }
    }

    private static WebpImageReader reader(byte[] encoded) throws IOException {
        WebpImageReader reader = new WebpImageReader(new WebpImageReaderSpi());
        reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(encoded)));
        return reader;
    }

    private static String attribute(IIOMetadata metadata, String name) {
        var node = metadata.getAsTree(metadata.getNativeMetadataFormatName()).getAttributes().getNamedItem(name);
        return node == null ? null : node.getNodeValue();
    }

    // ------------------------------------------------------------------------- the frame timing

    @Nested
    @DisplayName("the frame metadata")
    class FrameMetadata {

        @Test
        @DisplayName("says how long each frame is shown")
        void carriesTheFrameDuration() throws IOException {
            int[] delays = {40, 80, 120, 200};
            WebpImageReader reader = reader(animation(delays.length, 32, 24, delays, 0));
            for (int index = 0; index < delays.length; index++) {
                assertEquals(Integer
                        .toString(delays[index]), attribute(reader.getImageMetadata(index), "durationMs"), "frame " + index + " duration");
            }
        }

        @Test
        @DisplayName("of a still says nothing about time, because a still has no timing")
        void aStillHasNoDuration() throws IOException {
            byte[] encoded;
            try {
                encoded = WebpCodec.encode(frame(0, 32, 24), 80, false);
            } catch (WebpException e) {
                throw new AssertionError(e);
            }
            WebpImageReader reader = reader(encoded);
            assertFalse(reader.getNumImages(true) > 1, "a still should hold one frame");
            assertNull(attribute(reader.getImageMetadata(0), "durationMs"), "a still image should publish no duration");
        }

        @Test
        @DisplayName("keeps the properties it always published")
        void keepsTheExistingProperties() throws IOException {
            WebpImageReader reader = reader(animation(4, 40, 30, new int[] {40, 40, 40, 40}, 3));
            for (int index = 0; index < 4; index++) {
                var root = reader.getImageMetadata(index).getAsTree(WebpMetadata.NATIVE_FORMAT);
                var attributes = root.getAttributes();
                assertEquals("40", attributes.getNamedItem("width").getNodeValue());
                assertEquals("30", attributes.getNamedItem("height").getNodeValue());
                assertEquals("true", attributes.getNamedItem("hasAnimation").getNodeValue());
                assertEquals("4", attributes.getNamedItem("frameCount").getNodeValue());
            }
        }
    }

    // ------------------------------------------------------------------------- the loop count

    @Nested
    @DisplayName("the stream metadata")
    class StreamMetadata {

        @Test
        @DisplayName("is not null any more, because there is something to say in it")
        void isNotNull() throws IOException {
            IIOMetadata metadata = reader(animation(3, 32, 24, new int[] {40, 40, 40}, 2)).getStreamMetadata();
            assertNotNull(metadata, "the loop count was being thrown away");
        }

        @Test
        @DisplayName("says how often the sequence repeats")
        void carriesTheLoopCount() throws IOException {
            assertEquals("5", attribute(reader(animation(3, 32, 24, new int[] {40, 40, 40}, 5)).getStreamMetadata(), "repetitionCount"));
            assertEquals("1", attribute(reader(animation(3, 32, 24, new int[] {40, 40, 40}, 1)).getStreamMetadata(), "repetitionCount"));
            // Zero is the container's way of saying forever.
            assertEquals("0", attribute(reader(animation(3, 32, 24, new int[] {40, 40, 40}, 0)).getStreamMetadata(), "repetitionCount"));
        }

        @Test
        @DisplayName("leaves the frame timing out, which is not what it is for")
        void leavesTheDurationOut() throws IOException {
            assertNull(attribute(reader(animation(3, 32, 24, new int[] {40, 40, 40}, 2)).getStreamMetadata(), "durationMs"));
        }
    }

    // ------------------------------------------------------------- through the public entry point

    @Nested
    @DisplayName("reading the file through ImageReader")
    class ThroughImageReader {

        @Test
        @DisplayName("keeps every frame, its timing and its loop count")
        void keepsEverything() throws IOException {
            int[] delays = {40, 80, 120, 200};
            imagify.FrameSequence sequence = imagify.ImageReader.read(animation(delays.length, 32, 24, delays, 5));
            assertEquals(delays.length, sequence.frameCount());
            assertArrayEquals(delays, sequence.delaysMs(), "the animation was reported as uniformly one second a frame");
            assertEquals(5, sequence.loopCount(), "the loop count was reported as if the animation repeated forever");
        }

        @Test
        @DisplayName("still reads a still WebP as one frame")
        void readsAStill() throws IOException {
            byte[] encoded;
            try {
                encoded = WebpCodec.encode(frame(0, 32, 24), 80, false);
            } catch (WebpException e) {
                throw new AssertionError(e);
            }
            imagify.FrameSequence sequence = imagify.ImageReader.read(encoded);
            assertEquals(1, sequence.frameCount());
            assertEquals(32, sequence.frames().get(0).getWidth());
        }

        @Test
        @DisplayName("applies a transform to every frame without disturbing the timing")
        void transformsEveryFrame() throws IOException {
            int[] delays = {40, 80, 120};
            imagify.Imagify image = imagify.Imagify.read(animation(delays.length, 32, 24, delays, 2)).rotate(90).crop(0, 0, 10, 10);
            assertEquals(delays.length, image.frameCount());
            assertArrayEquals(delays, image.get().delaysMs());
            assertEquals(2, image.get().loopCount());
            for (BufferedImage frame : image.get().frames()) {
                assertEquals(10, frame.getWidth());
                assertEquals(10, frame.getHeight());
            }
        }

        @Test
        @DisplayName("survives a round trip through the writer")
        void roundTripsThroughTheWriter() throws IOException {
            int[] delays = {40, 80, 120};
            byte[] again = imagify.Imagify.read(animation(delays.length, 32, 24, delays, 4)).writeToBytes(imagify.ImageFormat.WEBP);
            imagify.FrameSequence sequence = imagify.ImageReader.read(again);
            assertEquals(delays.length, sequence.frameCount());
            assertArrayEquals(delays, sequence.delaysMs());
            assertEquals(4, sequence.loopCount());
        }
    }

    // -------------------------------------------------------------------------------- lifecycle

    @Test
    @DisplayName("a still image still reports no repetition count, because it has none")
    void aStillRepeatsNothing() throws IOException {
        byte[] encoded;
        try {
            encoded = WebpCodec.encode(frame(0, 32, 24), 80, false);
        } catch (WebpException e) {
            throw new AssertionError(e);
        }
        assertEquals("0", attribute(reader(encoded).getStreamMetadata(), "repetitionCount"));
    }

    @Test
    @DisplayName("a missing input is still rejected before anything is decoded")
    void noInput() {
        WebpImageReader reader = new WebpImageReader(new WebpImageReaderSpi());
        assertThrows(IIOException.class, () -> reader.getStreamMetadata());
        assertThrows(IIOException.class, () -> reader.getImageMetadata(0));
    }
}
