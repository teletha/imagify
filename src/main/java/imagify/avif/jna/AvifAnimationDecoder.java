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

import com.sun.jna.Memory;

import imagify.avif.AvifException;
import imagify.avif.AvifImageInfo;

import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * Walks the frames of an AVIF file, decoding them one at a time.
 *
 * <p>A file holds either one still image or an image sequence, and this class treats both the same
 * way: a still is a sequence of one. {@link #frameCount()} is answered from the container headers
 * alone, so asking how many frames there are never decodes a pixel, and {@link #frame(int)} decodes
 * only the frame asked for rather than the whole file.
 *
 * <p>The decoder holds native memory, so it is meant to be closed, ideally with try with resources:
 *
 * <pre>{@code
 * try (AvifAnimationDecoder decoder = AvifAnimationDecoder.open(bytes)) {
 *     for (int i = 0; i < decoder.frameCount(); i++) {
 *         BufferedImage frame = decoder.frame(i);
 *         // ...
 *     }
 * }
 * }</pre>
 *
 * <p>Instances are not thread safe.
 *
 * @see AvifCodec#decodeAnimation(byte[])
 */
public final class AvifAnimationDecoder implements AutoCloseable {

    /**
     * How long a frame is assumed to be shown when the container declares no timescale to measure it
     * against. It matches the fallback libavif itself applies and is short enough to stay unnoticed
     * if it is ever wrong.
     */
    private static final int UNSCALED_DURATION_MS = 100;

    private final AvifLibrary lib;
    private final Memory buffer;
    private final int size;
    private final AvifDecoder decoder;
    private final int frameCount;
    private final int loopCount;
    private final AvifImageInfo info;

    /** Sizes and durations of every frame, filled in together on first use. */
    private int[] widths;
    private int[] heights;
    private int[] durationsMs;

    /** The frame the decoder is parked on, or {@code -1} while it sits before the first one. */
    private int position = -1;

    /** Cleared the first time {@code avifDecoderNthImage()} turns out to be missing. */
    private boolean seekable = true;

    private AvifAnimationDecoder(AvifLibrary lib, Memory buffer, int size, AvifDecoder decoder,
            int frameCount, int loopCount, AvifImageInfo info) {
        this.lib = lib;
        this.buffer = buffer;
        this.size = size;
        this.decoder = decoder;
        this.frameCount = frameCount;
        this.loopCount = loopCount;
        this.info = info;
    }

    /**
     * Opens a decoder over an AVIF file, using the default number of threads.
     *
     * @param data the complete AVIF file
     * @return an open decoder, which the caller has to close
     * @throws AvifException when the library is unavailable or the file cannot be parsed
     */
    public static AvifAnimationDecoder open(byte[] data) throws AvifException {
        return open(data, AvifCodec.defaultThreads());
    }

    /**
     * Opens a decoder over an AVIF file.
     *
     * @param data    the complete AVIF file
     * @param threads the number of threads {@code libavif} may use
     * @return an open decoder, which the caller has to close
     * @throws AvifException when the library is unavailable or the file cannot be parsed
     */
    public static AvifAnimationDecoder open(byte[] data, int threads) throws AvifException {
        AvifLibrary lib = AvifCodec.requireLibrary();
        if (data == null || data.length == 0) {
            throw new AvifException("no input data");
        }
        if (!AvifCodec.isAvif(data)) {
            throw new AvifException("not an AVIF file");
        }

        Memory buffer = new Memory(Math.max(data.length, 1));
        AvifDecoder decoder = null;
        try {
            buffer.write(0, data, 0, data.length);
            decoder = lib.avifDecoderCreate();
            if (decoder == null) {
                throw new AvifException("avifDecoderCreate() returned NULL");
            }
            decoder.maxThreads = threads;
            decoder.ignoreExif = AvifLibrary.AVIF_TRUE;
            decoder.ignoreXMP = AvifLibrary.AVIF_TRUE;
            // Zero means no cap, which matters for a long animation and changes nothing for a still.
            decoder.imageCountLimit = 0;
            // These two checks reject files that older, and still widely deployed, encoders emit.
            decoder.strictFlags &=
                    ~(AvifLibrary.AVIF_STRICT_CLAP_VALID | AvifLibrary.AVIF_STRICT_PIXI_REQUIRED);
            decoder.write();

            AvifCodec.check(lib, lib.avifDecoderSetIOMemory(decoder, buffer, data.length),
                    "avifDecoderSetIOMemory()");
            AvifCodec.check(lib, lib.avifDecoderParse(decoder), "avifDecoderParse()");
            decoder.read();
            decoder.image.read();

            int frameCount = decoder.imageCount;
            if (frameCount <= 0) {
                throw new AvifException("no frames found in the AVIF file");
            }
            return new AvifAnimationDecoder(lib, buffer, data.length, decoder, frameCount,
                    decoder.repetitionCount, AvifCodec.describeImage(decoder.image));
        } catch (AvifException | RuntimeException e) {
            if (decoder != null) {
                lib.avifDecoderDestroy(decoder);
            }
            buffer.close();
            throw e;
        }
    }

    /**
     * @return the number of frames, which is 1 for a still image and is read from the headers alone
     */
    public int frameCount() {
        return frameCount;
    }

    /**
     * @return how often the sequence repeats, 0 meaning forever
     */
    public int loopCount() {
        return loopCount;
    }

    /**
     * @return the properties of the file as a whole, taken from its first frame
     */
    public AvifImageInfo info() {
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
        return widths[index];
    }

    /**
     * @param index the frame
     * @return the height of that frame in pixels
     * @throws AvifException when the frame cannot be read
     * @throws IndexOutOfBoundsException when there is no such frame
     */
    public int height(int index) throws AvifException {
        ensureTable();
        return heights[index];
    }

    /**
     * @param index the frame
     * @return how long that frame is shown, in milliseconds, never below 1
     * @throws AvifException when the frame cannot be read
     * @throws IndexOutOfBoundsException when there is no such frame
     */
    public int durationMs(int index) throws AvifException {
        ensureTable();
        if (index < 0 || index >= frameCount) {
            throw new IndexOutOfBoundsException(
                    "frame " + index + " is out of bounds: the file holds " + frameCount + " frame(s)");
        }
        return durationsMs[index];
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
     * {@code A, B, G, R} layout {@code libavif} fills in for {@code AVIF_RGB_FORMAT_ABGR}.
     *
     * <p>Frames of an animation are returned at the size they are stored at, which for a sequence
     * that updates only part of the canvas can be smaller than {@link #info()}. The alpha channel is
     * fully opaque when the file carries no alpha.</p>
     *
     * @param index the frame to decode
     * @return the decoded frame
     * @throws AvifException when the frame cannot be decoded
     * @throws IndexOutOfBoundsException when there is no such frame
     */
    public BufferedImage frame(int index) throws AvifException {
        AvifImage image = seek(index);
        int width = image.width;
        int height = image.height;
        AvifRGBImage rgb = new AvifRGBImage();
        lib.avifRGBImageSetDefaults(rgb, image);
        rgb.format = AvifLibrary.AVIF_RGB_FORMAT_ABGR;
        rgb.depth = 8;
        rgb.maxThreads = width * height > (1 << 22) ? AvifCodec.defaultThreads() : 1;
        rgb.rowBytes = width * lib.avifRGBImagePixelSize(rgb);
        AvifCodec.check(lib, lib.avifRGBImageAllocatePixels(rgb), "avifRGBImageAllocatePixels()");
        try {
            AvifCodec.check(lib, lib.avifImageYUVToRGB(image, rgb), "avifImageYUVToRGB()");
            byte[] pixels = rgb.getPixels();
            if (pixels == null) {
                throw new AvifException("avifImageYUVToRGB() did not produce any pixel");
            }
            return AbgrPixels.toBufferedImage(pixels, width, height);
        } finally {
            lib.avifRGBImageFreePixels(rgb);
        }
    }

    // ------------------------------------------------------------------------- internals

    /**
     * Fills in the size and duration of every frame in one pass, so that a caller looping over the
     * frames pays for one traversal instead of one per frame.
     */
    private void ensureTable() throws AvifException {
        if (durationsMs != null) {
            return;
        }
        widths = new int[frameCount];
        heights = new int[frameCount];
        durationsMs = new int[frameCount];
        for (int index = 0; index < frameCount; index++) {
            AvifImage image = seek(index);
            widths[index] = image.width;
            heights[index] = image.height;
            long timescale = decoder.imageTiming.timescale;
            // A frame with no duration would never be seen, so the smallest possible value is used
            // instead of reporting the zero the container asked for.
            durationsMs[index] = timescale > 0
                    ? (int) Math.max(1, Math.round(
                            decoder.imageTiming.durationInTimescales * 1000.0 / timescale))
                    : UNSCALED_DURATION_MS;
        }
    }

    /**
     * Parks the decoder on a frame and returns its native image.
     *
     * <p>{@code avifDecoderNthImage()} is tried first because it lands on any frame directly.
     * It only exists from libavif 1.3.0 onwards, so on an older library the decoder falls back to
     * walking forward with {@code avifDecoderNextImage()} and reparsing when a frame behind the
     * current one is asked for.</p>
     */
    private AvifImage seek(int index) throws AvifException {
        if (index < 0 || index >= frameCount) {
            throw new IndexOutOfBoundsException(
                    "frame " + index + " is out of bounds: the file holds " + frameCount + " frame(s)");
        }
        if (position != index) {
            if (seekable && !trySeek(index)) {
                seekable = false;
            }
            if (!seekable) {
                walk(index);
            }
        }
        decoder.read();
        AvifImage image = decoder.image;
        image.read();
        return image;
    }

    /**
     * @return {@code true} when the frame was reached, and {@code false} when this build of
     *         {@code libavif} has no {@code avifDecoderNthImage()} to call
     */
    private boolean trySeek(int index) throws AvifException {
        try {
            AvifCodec.check(lib, lib.avifDecoderNthImage(decoder, index),
                    "avifDecoderNthImage(" + index + ")");
            position = index;
            return true;
        } catch (UnsatisfiedLinkError e) {
            // JNA fails before it enters the library, so the decoder is untouched and walking on from
            // the frame it already sits on is safe.
            return false;
        }
    }

    private void walk(int index) throws AvifException {
        if (position < 0 || index < position) {
            rewind();
        }
        while (position < index) {
            AvifCodec.check(lib, lib.avifDecoderNextImage(decoder),
                    "avifDecoderNextImage() at frame " + (position + 1));
            position++;
        }
    }

    /**
     * Puts the decoder back before the first frame. libavif documents that parsing again resets the
     * decoder on its own, so the IO is only restated to be certain it is still attached.
     */
    private void rewind() throws AvifException {
        AvifCodec.check(lib, lib.avifDecoderSetIOMemory(decoder, buffer, size), "avifDecoderSetIOMemory()");
        AvifCodec.check(lib, lib.avifDecoderParse(decoder), "avifDecoderParse()");
        position = -1;
    }

    @Override
    public void close() {
        lib.avifDecoderDestroy(decoder);
        buffer.close();
    }

    @Override
    public String toString() {
        return "AvifAnimationDecoder[frames=" + frameCount + ", loop=" + loopCount
                + ", " + info.width() + "x" + info.height()
                + ", durations=" + Arrays.toString(durationsMs == null ? new int[0] : durationsMs) + "]";
    }
}
