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

import static imagify.avif.ffm.AvifConstants.*;

import java.awt.image.BufferedImage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import imagify.avif.AvifException;
import imagify.avif.AvifImageInfo;
import imagify.pixels.AbgrPixels;

/**
 * Walks the frames of an AVIF file, decoding them one at a time.
 *
 * <p>A file holds either one still image or a sequence, and this treats both the same way: a still is
 * a sequence of one. {@link #frameCount()} is answered from the container headers alone, so asking
 * how many frames there are never decodes a pixel, and {@link #frame(int)} decodes only the frame
 * asked for.
 *
 * <p>The sequence holds a decoder that the shim allocated, so it is meant to be closed, ideally with
 * try with resources:
 *
 * <pre>{@code
 * try (AvifSequence sequence = AvifCodec.openSequence(bytes)) {
 *     for (int i = 0; i < sequence.frameCount(); i++) {
 *         BufferedImage frame = sequence.frame(i);
 *         // ...
 *     }
 * }
 * }</pre>
 *
 * <p>Instances are not thread safe.
 *
 * @see AvifCodec#decodeAnimation(byte[])
 */
public final class AvifSequence implements AutoCloseable {

    private final AvifShim shim;
    private final MemorySegment sequence;
    private final int threads;

    /** Filled in together on the first question about a frame, so that asking is one traversal. */
    private int[] widths;
    private int[] heights;
    private int[] durationsMs;
    private int loopCount = -1;
    private AvifImageInfo info;

    AvifSequence(AvifShim shim, MemorySegment sequence, int threads) {
        this.shim = shim;
        this.sequence = sequence;
        this.threads = threads;
    }

    /**
     * @return the number of frames, which is 1 for a still image, read from the headers alone
     */
    public int frameCount() {
        try {
            ensureTable();
        } catch (AvifException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        return widths.length;
    }

    /**
     * @return how often the sequence repeats, 0 meaning forever
     */
    public int loopCount() {
        try {
            ensureTable();
        } catch (AvifException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        return loopCount;
    }

    /**
     * @return the properties of the file as a whole, taken from its first frame
     */
    public AvifImageInfo info() {
        try {
            ensureTable();
        } catch (AvifException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        return info;
    }

    /**
     * @param index the frame
     * @return the width of that frame in pixels
     * @throws AvifException when the frame cannot be read
     * @throws IndexOutOfBoundsException when there is no such frame
     */
    public int width(int index) throws AvifException {
        ensureTable();
        return widths[check(index)];
    }

    /**
     * @param index the frame
     * @return the height of that frame in pixels
     * @throws AvifException when the frame cannot be read
     * @throws IndexOutOfBoundsException when there is no such frame
     */
    public int height(int index) throws AvifException {
        ensureTable();
        return heights[check(index)];
    }

    /**
     * @param index the frame
     * @return how long that frame is shown, in milliseconds, never below 1
     * @throws AvifException when the frame cannot be read
     * @throws IndexOutOfBoundsException when there is no such frame
     */
    public int durationMs(int index) throws AvifException {
        ensureTable();
        return durationsMs[check(index)];
    }

    /**
     * @return how long every frame is shown, in milliseconds
     * @throws AvifException when a frame cannot be read
     */
    public int[] durationsMs() throws AvifException {
        ensureTable();
        return durationsMs.clone();
    }

    /**
     * Decodes one frame as a {@link BufferedImage#TYPE_4BYTE_ABGR} image, whose banks are the very
     * {@code A, B, G, R} layout the shim produces.
     *
     * <p>Frames come back at the size they are stored at, which for a sequence that updates only part
     * of the canvas is smaller than {@link #info()}. The alpha channel is fully opaque when the file
     * carries none.
     *
     * @param index the frame to decode
     * @return the decoded frame
     * @throws AvifException when the frame cannot be decoded
     * @throws IndexOutOfBoundsException when there is no such frame
     */
    public BufferedImage frame(int index) throws AvifException {
        // The table first, because check() reads the frame count out of it. Decoding a frame without
        // asking how many there are is the ordinary way to reach this method, so the table has to be
        // filled in here rather than assumed to have been filled in already.
        ensureTable();
        check(index);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment width = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment height = arena.allocate(ValueLayout.JAVA_INT);
            int frameWidth = widths[index];
            int frameHeight = heights[index];
            byte[] abgr;
            if (shim.hasSequenceFrameInto()) {
                abgr = new byte[frameWidth * frameHeight * 4];
                if (shim.sequenceFrameInto(sequence, index, threads, MemorySegment.ofArray(abgr),
                        abgr.length, width, height) != 0) {
                    throw new AvifException("avif could not decode frame " + index);
                }
            } else {
                MemorySegment pixels = arena.allocate(ValueLayout.ADDRESS);
                MemorySegment length = arena.allocate(ValueLayout.JAVA_LONG);
                if (shim.sequenceFrame(sequence, index, threads, pixels, length, width, height) != 0) {
                    throw new AvifException("avif could not decode frame " + index);
                }
                MemorySegment buffer = pixels.get(ValueLayout.ADDRESS, 0);
                int count = (int) length.get(ValueLayout.JAVA_LONG, 0);
                if (buffer.address() == 0 || count <= 0) {
                    throw new AvifException("avif produced no pixels for frame " + index);
                }
                abgr = buffer.reinterpret(count).toArray(ValueLayout.JAVA_BYTE);
            }
            return AbgrPixels.toBufferedImage(abgr,
                    width.get(ValueLayout.JAVA_INT, 0), height.get(ValueLayout.JAVA_INT, 0));
        }
    }

    /**
     * Asks the shim for the size and the duration of every frame in one pass, so that a caller looping
     * over the frames pays for one traversal of the container rather than one per frame.
     */
    private void ensureTable() throws AvifException {
        if (widths != null) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(INFO_COUNT * (long) Integer.BYTES, Integer.BYTES);
            MemorySegment frameCount = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment loops = arena.allocate(ValueLayout.JAVA_INT);
            if (shim.sequenceRead(sequence, out, frameCount, loops) != 0) {
                throw new AvifException("avif could not read the properties of the file");
            }
            int frames = frameCount.get(ValueLayout.JAVA_INT, 0);
            loopCount = loops.get(ValueLayout.JAVA_INT, 0);
            info = describe(out);

            // The table is sized from the count the headers just gave, so one call fills all three of
            // width, height and duration for every frame and the container is walked once.
            MemorySegment sizes = arena.allocate(Math.max(frames, 1) * 3L * Integer.BYTES, Integer.BYTES);
            if (shim.sequenceSizes(sequence, sizes) != 0) {
                throw new AvifException("avif could not read the frames of the file");
            }
            widths = new int[frames];
            heights = new int[frames];
            durationsMs = new int[frames];
            for (int i = 0; i < frames; i++) {
                widths[i] = sizes.get(ValueLayout.JAVA_INT, (long) i * Integer.BYTES);
                heights[i] = sizes.get(ValueLayout.JAVA_INT, (long) (frames + i) * Integer.BYTES);
                durationsMs[i] = sizes.get(ValueLayout.JAVA_INT, (long) (2L * frames + i) * Integer.BYTES);
            }
        }
    }

    private static AvifImageInfo describe(MemorySegment out) {
        return new AvifImageInfo(
                at(out, AvifConstants.INFO_WIDTH),
                at(out, AvifConstants.INFO_HEIGHT),
                at(out, AvifConstants.INFO_DEPTH),
                at(out, AvifConstants.INFO_YUV_FORMAT),
                at(out, AvifConstants.INFO_YUV_RANGE),
                at(out, AvifConstants.INFO_CHROMA_SAMPLE_POSITION),
                at(out, AvifConstants.INFO_COLOR_PRIMARIES),
                at(out, AvifConstants.INFO_TRANSFER_CHARACTERISTICS),
                at(out, AvifConstants.INFO_MATRIX_COEFFICIENTS),
                at(out, AvifConstants.INFO_HAS_ALPHA) != 0,
                at(out, AvifConstants.INFO_ICC_SIZE),
                at(out, AvifConstants.INFO_EXIF_SIZE),
                at(out, AvifConstants.INFO_XMP_SIZE),
                at(out, AvifConstants.INFO_ROTATION_DEGREES),
                at(out, AvifConstants.INFO_MIRRORED) != 0);
    }

    private static int at(MemorySegment out, int index) {
        return out.get(ValueLayout.JAVA_INT, (long) index * Integer.BYTES);
    }

    private int check(int index) {
        if (index < 0 || index >= widths.length) {
            throw new IndexOutOfBoundsException(
                    "frame " + index + " is out of bounds: the file holds " + widths.length + " frame(s)");
        }
        return index;
    }

    @Override
    public void close() {
        shim.sequenceClose(sequence);
    }
}
