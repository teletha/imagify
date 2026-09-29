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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests reading an animated AVIF through {@code ImageIO}, which is what
 * {@link imagify.ImageReader} and therefore {@link imagify.Imagify} do when they are handed an
 * animated AVIF file.
 *
 * <p>
 * Before the animation was understood, the reader reported one image no matter what the file held
 * and handed back the first frame, so an animation was silently reduced to a still. These tests pin
 * down that the frame count, the individual frames, the frame timing and the loop count all survive
 * the trip.
 */
class AnimatedAvifImageIOTest {

    @BeforeAll
    static void requireLibavif() {
        assumeTrue(imagify.avif.ffm.AvifCodec
                .isAvailable(), () -> "skipped: libavif is not available (" + imagify.avif.ffm.AvifCodec.getUnavailableReason() + ")");
    }

    // -------------------------------------------------------------------------------- fixture

    /**
     * @return a frame whose green channel says which frame it is, moving in steps of 10 so that the
     *         frames stay apart even after the lossy encoder has had its say
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

    private static byte[] animation(int count, int width, int height, int[] delaysMs, int loopCount) {
        List<BufferedImage> frames = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            frames.add(frame(index, width, height));
        }
        try {
            return imagify.avif.ffm.AvifCodec.encodeAnimation(frames, delaysMs, 70, loopCount);
        } catch (imagify.avif.AvifException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * @return a single frame AVIF, which is the same reader asked to handle one image rather than a
     *         sequence
     */
    private static byte[] still(int width, int height) {
        try {
            return imagify.avif.ffm.AvifCodec.encode(frame(0, width, height), 70, 8);
        } catch (AvifException e) {
            throw new AssertionError(e);
        }
    }

    private static AvifImageReader reader(byte[] encoded) throws IOException {
        AvifImageReader reader = new AvifImageReader(new AvifImageReaderSpi());
        reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(encoded)));
        return reader;
    }

    private static int marker(BufferedImage image) {
        return image.getRGB(0, 0) >> 8 & 0xff;
    }

    /** AVIF is lossy, so a frame only has to land near the frame that was written. */
    private static void assertSameFrame(BufferedImage expected, BufferedImage actual, String what) {
        int wanted = marker(expected);
        int got = marker(actual);
        assertTrue(Math.abs(wanted - got) <= 4, what + ": expected green " + wanted + " but was " + got);
    }

    private static String attribute(IIOMetadata metadata, String name) {
        var node = metadata.getAsTree(metadata.getNativeMetadataFormatName()).getAttributes().getNamedItem(name);
        return node == null ? null : node.getNodeValue();
    }

    /** @return the value of an attribute of a named child of {@code parent}, or {@code null} */
    private static String childAttribute(org.w3c.dom.Node parent, String child, String name) {
        for (int i = 0; i < parent.getChildNodes().getLength(); i++) {
            var node = parent.getChildNodes().item(i);
            if (child.equals(node.getNodeName())) {
                var attribute = node.getAttributes().getNamedItem(name);
                return attribute == null ? null : attribute.getNodeValue();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------- the frame count

    @Nested
    @DisplayName("the frame count")
    class FrameCount {

        @Test
        @DisplayName("is the number of frames the file holds")
        void isTheRealCount() throws IOException {
            // An animation needs at least two frames, so a one frame file is a still image and is
            // covered by the round trip test instead.
            for (int count : new int[] {2, 5, 12}) {
                byte[] encoded = animation(count, 32, 24, new int[count], 0);
                assertEquals(count, reader(encoded).getNumImages(true), count + " frames");
                assertEquals(count, reader(encoded).getNumImages(false), count + " frames");
            }
            assertEquals(1, reader(still(32, 24)).getNumImages(true), "a still is one frame");
        }

        @Test
        @DisplayName("is not hard coded to one any more")
        void isNotHardCoded() throws IOException {
            assertEquals(7, reader(animation(7, 32, 24, new int[7], 0)).getNumImages(true));
        }

        @Test
        @DisplayName("is what ImageIO itself reports")
        void isWhatImageIOReports() throws IOException {
            byte[] encoded = animation(6, 32, 24, new int[6], 0);
            Iterator<ImageReader> readers = ImageIO.getImageReaders(ImageIO.createImageInputStream(new ByteArrayInputStream(encoded)));
            assertTrue(readers.hasNext(), "ImageIO found no AVIF reader");
            ImageReader reader = readers.next();
            try {
                reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(encoded)));
                assertEquals(6, reader.getNumImages(true));
            } finally {
                reader.dispose();
            }
        }
    }

    // -------------------------------------------------------------------------- reading frames

    @Nested
    @DisplayName("reading a frame")
    class Reading {

        @Test
        @DisplayName("gives the frame that was written at that index")
        void givesTheRightFrame() throws IOException {
            int count = 6;
            byte[] encoded = animation(count, 32, 24, new int[count], 0);
            AvifImageReader reader = reader(encoded);
            for (int index = 0; index < count; index++) {
                BufferedImage decoded = reader.read(index);
                assertEquals(32, decoded.getWidth(), "frame " + index + " width");
                assertEquals(24, decoded.getHeight(), "frame " + index + " height");
                assertSameFrame(frame(index, 32, 24), decoded, "frame " + index);
            }
        }

        @Test
        @DisplayName("gives the same frame whichever order the indices are asked in")
        void givesTheSameFrameInAnyOrder() throws IOException {
            int count = 5;
            byte[] encoded = animation(count, 32, 24, new int[count], 0);
            AvifImageReader reader = reader(encoded);
            BufferedImage[] forwards = new BufferedImage[count];
            for (int index = 0; index < count; index++) {
                forwards[index] = reader.read(index);
            }
            for (int index = count - 1; index >= 0; index--) {
                assertSameFrame(forwards[index], reader.read(index), "frame " + index + " on the way back");
            }
        }

        @Test
        @DisplayName("reports the size of every frame, not just the first")
        void reportsEverySize() throws IOException {
            byte[] encoded = animation(4, 40, 30, new int[4], 0);
            AvifImageReader reader = reader(encoded);
            for (int index = 0; index < 4; index++) {
                assertEquals(40, reader.getWidth(index), "frame " + index + " width");
                assertEquals(30, reader.getHeight(index), "frame " + index + " height");
            }
        }

        @Test
        @DisplayName("honours a source region on a frame other than the first")
        void honoursTheSourceRegion() throws IOException {
            byte[] encoded = animation(4, 40, 30, new int[4], 0);
            AvifImageReader reader = reader(encoded);
            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceRegion(new Rectangle(10, 5, 20, 12));
            for (int index = 0; index < 4; index++) {
                BufferedImage decoded = reader.read(index, param);
                assertEquals(20, decoded.getWidth(), "frame " + index + " width");
                assertEquals(12, decoded.getHeight(), "frame " + index + " height");
            }
        }

        @Test
        @DisplayName("refuses an index the file does not have")
        void refusesUnknownIndex() throws IOException {
            byte[] encoded = animation(3, 32, 24, new int[3], 0);
            AvifImageReader reader = reader(encoded);
            assertThrows(IndexOutOfBoundsException.class, () -> reader.read(3));
            assertThrows(IndexOutOfBoundsException.class, () -> reader.read(-1));
        }
    }

    // ---------------------------------------------------------------------- the frame metadata

    @Nested
    @DisplayName("the metadata")
    class Metadata {

        @Test
        @DisplayName("of a frame says how long that frame is shown")
        void carriesTheFrameDuration() throws IOException {
            int[] delays = {40, 80, 120, 200};
            byte[] encoded = animation(delays.length, 32, 24, delays, 0);
            AvifImageReader reader = reader(encoded);
            for (int index = 0; index < delays.length; index++) {
                assertEquals(Integer.toString(delays[index]), attribute(reader.getImageMetadata(index), "durationMs"), "frame " + index);
            }
        }

        @Test
        @DisplayName("of a still frame says nothing about time, because it has no timing")
        void aStillHasNoDuration() throws IOException {
            byte[] encoded = still(32, 24);
            AvifImageReader reader = reader(encoded);
            assertNull(attribute(reader.getImageMetadata(0), "durationMs"));
        }

        @Test
        @DisplayName("of the file says how often the sequence repeats")
        void carriesTheLoopCount() throws IOException {
            assertEquals("5", attribute(reader(animation(3, 32, 24, new int[3], 5)).getStreamMetadata(), "repetitionCount"));
            // Zero is the container's way of saying forever.
            assertEquals("0", attribute(reader(animation(3, 32, 24, new int[3], 0)).getStreamMetadata(), "repetitionCount"));
        }

        @Test
        @DisplayName("still answers the standard format, with the properties it always had")
        void stillAnswersTheStandardFormat() throws IOException {
            byte[] encoded = animation(3, 32, 24, new int[3], 0);
            AvifImageReader reader = reader(encoded);
            var tree = reader.getImageMetadata(1).getAsTree("javax_imageio_1.0");
            assertEquals("32", childAttribute(tree, "Dimension", "pixelWidth"));
            assertEquals("24", childAttribute(tree, "Dimension", "pixelHeight"));
            // The fixture is opaque, so there is no alpha plane to advertise.
            assertEquals("RGB", childAttribute(tree, "Data", "type"));
        }
    }

    // ------------------------------------------------------------- through the public entry point

    @Nested
    @DisplayName("reading the file through Imagify")
    class ThroughImagify {

        @Test
        @DisplayName("keeps every frame, its timing and its loop count")
        void keepsEverything() throws IOException {
            int[] delays = {40, 80, 120, 200};
            byte[] encoded = animation(delays.length, 32, 24, delays, 5);
            imagify.FrameSequence sequence = imagify.ImageReader.read(encoded);
            assertEquals(delays.length, sequence.frameCount());
            assertArrayEquals(delays, sequence.delaysMs());
            assertEquals(5, sequence.loopCount());
            for (int index = 0; index < delays.length; index++) {
                BufferedImage frame = sequence.frames().get(index);
                assertEquals(32, frame.getWidth(), "frame " + index + " width");
                assertEquals(24, frame.getHeight(), "frame " + index + " height");
            }
        }

        @Test
        @DisplayName("applies a transform to every frame of an animated AVIF")
        void transformsEveryFrame() throws IOException {
            int[] delays = {40, 80, 120, 200};
            byte[] encoded = animation(delays.length, 32, 24, delays, 0);
            imagify.Imagify image = imagify.Imagify.read(encoded).rotate(90).crop(0, 0, 10, 10);
            assertEquals(delays.length, image.frameCount());
            assertArrayEquals(delays, image.get().delaysMs());
            for (BufferedImage frame : image.get().frames()) {
                assertEquals(10, frame.getWidth());
                assertEquals(10, frame.getHeight());
            }
        }

        @Test
        @DisplayName("still reads a still AVIF as one frame")
        void readsAStill() throws IOException {
            byte[] encoded = still(32, 24);
            imagify.FrameSequence sequence = imagify.ImageReader.read(encoded);
            assertEquals(1, sequence.frameCount());
            assertEquals(32, sequence.frames().get(0).getWidth());
        }

        @Test
        @DisplayName("survives a round trip through the writer")
        void roundTripsThroughTheWriter() throws IOException {
            int[] delays = {40, 80, 120};
            byte[] encoded = animation(delays.length, 32, 24, delays, 4);
            byte[] again = imagify.Imagify.read(encoded).writeToBytes(imagify.ImageFormat.AVIF);
            imagify.FrameSequence sequence = imagify.ImageReader.read(again);
            assertEquals(delays.length, sequence.frameCount());
            assertArrayEquals(delays, sequence.delaysMs());
            assertEquals(4, sequence.loopCount());
        }
    }

    // ------------------------------------------------------------------------------ lifecycle

    @Test
    @DisplayName("setting a new input releases the decoder held for the old one")
    void resetClosesTheDecoder() throws IOException {
        byte[] first = animation(3, 32, 24, new int[3], 0);
        byte[] second = animation(5, 16, 12, new int[5], 0);
        AvifImageReader reader = reader(first);
        assertEquals(3, reader.getNumImages(true));
        // A reader is meant to be reusable, and each setInput() drops what was decoded for the
        // previous one. Nothing is expected of this call beyond it not blowing up.
        reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(second)));
        assertEquals(5, reader.getNumImages(true));
        assertEquals(16, reader.getWidth(0));
        reader.reset();
        assertThrows(IIOException.class, () -> reader.getNumImages(true), "reset() left the input in place");
    }

    @Test
    @DisplayName("a missing input is still rejected before anything is decoded")
    void noInput() {
        AvifImageReader reader = new AvifImageReader(new AvifImageReaderSpi());
        assertThrows(IIOException.class, () -> reader.getNumImages(true));
        assertThrows(IIOException.class, () -> reader.read(0));
        assertThrows(IIOException.class, () -> reader.getStreamMetadata());
    }
}
