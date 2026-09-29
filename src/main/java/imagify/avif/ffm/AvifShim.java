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

import static java.lang.foreign.ValueLayout.*;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.Linker.Option;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * The {@code imagify_avif_*} entry points of the C shim, which is what makes the zero copy
 * possible.
 *
 * <p>The shim is a thin C ABI over libavif that exists for one reason. {@code avifRGBImage.pixels}
 * is a pointer-typed struct field, and FFM will not put a heap segment, which is what a Java array
 * is, into one: the field is a pointer into native memory that the struct itself lives in, and a
 * pointer to the Java heap has neither the lifetime nor the alignment the struct would then be
 * promising. An encode that fills that field from Java therefore has to copy the pixels into native
 * memory first, and the copy is of a whole image.
 *
 * <p>A function parameter is a different matter. A call reaches the callee with
 * {@link Option#critical(boolean)} set, which pins the array for the length of the call
 * rather than copying it, and the shim's entry points take the pixels that way. The callee is on
 * the
 * stack for the length of the call and cannot store the pointer anywhere that outlives the pin, so
 * the array does not have to be native memory to be safe. That is the whole of the shim, and
 * everything else it does is so that no libavif struct has to be described in Java.
 */
public final class AvifShim {

    private final MethodHandle libavifVersion;

    private final MethodHandle pictureCreate;

    private final MethodHandle pictureDestroy;

    private final MethodHandle pictureFromAbgr;

    private final MethodHandle pictureFromBgr;

    private final MethodHandle pictureToAbgr;

    private final MethodHandle pictureToAbgrInto;

    private final MethodHandle pictureInfo;

    private final MethodHandle pictureEncode;

    private final MethodHandle pictureDecode;

    private final MethodHandle animationEncode;

    private final MethodHandle sequenceOpen;

    private final MethodHandle sequenceClose;

    private final MethodHandle sequenceRead;

    private final MethodHandle sequenceSizes;

    private final MethodHandle sequenceFrame;

    private final MethodHandle sequenceFrameInto;

    private final MethodHandle free;

    /**
     * @param lookup a lookup that can resolve the {@code imagify_avif_*} symbols of a loaded shim
     * @throws IllegalArgumentException when a symbol is missing, which means the shim is not this
     *             revision of the ABI
     */
    public AvifShim(SymbolLookup lookup) {
        Linker linker = Linker.nativeLinker();
        this.libavifVersion = downcall(linker, lookup, "imagify_avif_libavif_version", FunctionDescriptor.of(ADDRESS));
        this.pictureCreate = downcall(linker, lookup, "imagify_avif_picture_create", FunctionDescriptor
                .of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        this.pictureDestroy = downcall(linker, lookup, "imagify_avif_picture_destroy", FunctionDescriptor.ofVoid(ADDRESS));
        // The one call in this binding that reads a Java array where it lies rather than a copy of
        // it, and it is the whole reason the shim exists.
        //
        // critical(true) is what makes it possible. Without it FFM refuses the segment outright
        // with "Heap segment not allowed", because a Java array is not native memory and the call
        // is about to read it from C. With it the array is pinned for the length of the call in
        // place, which is safe here because imagify_avif_picture_from_abgr is on the stack for the
        // length of the call and stores the pointer nowhere: the only thing it does with the
        // address
        // is read width * height * 4 bytes of it and let avifImageRGBToYUV write somewhere else.
        //
        // The cost is the pin. A long call holds a garbage collection up, and an encode is exactly
        // as long as the colour conversion it is about to do, so this is a collection that waits
        // rather than work that is saved. It is paid only by callers that chose this path by having
        // an image already in this layout, and it is smaller than a whole image of memcpy, which is
        // what the other path costs.
        this.pictureFromAbgr = downcall(linker, lookup, "imagify_avif_picture_from_abgr", FunctionDescriptor
                .of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT), Linker.Option.critical(true));
        // Fast path for TYPE_3BYTE_BGR: the band order is already B, G, R, so libavif can read the
        // backing array directly with AVIF_RGB_FORMAT_BGR.
        this.pictureFromBgr = optionalDowncall(linker, lookup, "imagify_avif_picture_from_bgr", FunctionDescriptor
                .of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT), Linker.Option.critical(true));
        this.pictureToAbgr = downcall(linker, lookup, "imagify_avif_picture_to_abgr", FunctionDescriptor
                .of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        // Decode straight into a Java byte[] that the caller has already allocated, avoiding the
        // C-side malloc and the copy into a Java array.
        this.pictureToAbgrInto = optionalDowncall(linker, lookup, "imagify_avif_picture_to_abgr_into", FunctionDescriptor
                .of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG), Linker.Option.critical(true));
        // Fifteen answers into one block rather than fifteen pointers, so the shape of the call is
        // the shim's and not this binding's. The positions are named in AvifConstants and in the
        // shim's header, and they are an ABI that both sides read by name.
        this.pictureInfo = downcall(linker, lookup, "imagify_avif_picture_info", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        this.pictureEncode = downcall(linker, lookup, "imagify_avif_picture_encode", FunctionDescriptor
                .of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        this.pictureDecode = downcall(linker, lookup, "imagify_avif_picture_decode", FunctionDescriptor
                .of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS));
        // The second critical call, and the one that matters most: a sequence is a hundred frames,
        // so a copy of each of them is a hundred copies of a whole image that nobody asked for.
        // The frames are one block here so that there is a single pointer to pin.
        this.animationEncode = downcall(linker, lookup, "imagify_avif_animation_encode", FunctionDescriptor
                .of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS), Linker.Option
                        .critical(true));
        this.sequenceOpen = downcall(linker, lookup, "imagify_avif_sequence_open", FunctionDescriptor
                .of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS));
        this.sequenceClose = downcall(linker, lookup, "imagify_avif_sequence_close", FunctionDescriptor.ofVoid(ADDRESS));
        this.sequenceRead = downcall(linker, lookup, "imagify_avif_sequence_read", FunctionDescriptor
                .of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        this.sequenceSizes = downcall(linker, lookup, "imagify_avif_sequence_sizes", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        this.sequenceFrame = downcall(linker, lookup, "imagify_avif_sequence_frame", FunctionDescriptor
                .of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        this.sequenceFrameInto = optionalDowncall(linker, lookup, "imagify_avif_sequence_frame_into", FunctionDescriptor
                .of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS), Linker.Option.critical(true));
        this.free = downcall(linker, lookup, "imagify_avif_free", FunctionDescriptor.ofVoid(ADDRESS));
    }

    // ------------------------------------------------------------------------------- operations

    /**
     * @return the libavif version the shim was linked against
     */
    public String libavifVersion() {
        try {
            MemorySegment text = (MemorySegment) libavifVersion.invokeExact();
            return text.reinterpret(Integer.MAX_VALUE).getString(0);
        } catch (Throwable t) {
            throw new IllegalStateException("imagify_avif_libavif_version() failed", t);
        }
    }

    /**
     * @param width image width in pixels
     * @param height image height in pixels
     * @param depth bits per channel
     * @param yuvFormat an {@code AVIF_PIXEL_FORMAT_*} value
     * @return a new picture, or {@link MemorySegment#NULL} when the geometry is not one libavif
     *         takes
     */
    public MemorySegment pictureCreate(int width, int height, int depth, int yuvFormat) {
        try {
            return (MemorySegment) pictureCreate.invokeExact(width, height, depth, yuvFormat);
        } catch (Throwable t) {
            throw new IllegalStateException("imagify_avif_picture_create() failed", t);
        }
    }

    public void pictureDestroy(MemorySegment picture) {
        try {
            pictureDestroy.invokeExact(picture);
        } catch (Throwable t) {
            throw new IllegalStateException("imagify_avif_picture_destroy() failed", t);
        }
    }

    /**
     * Converts tightly packed A, B, G, R bytes into a picture's planes, without a copy.
     *
     * @param picture the picture to fill
     * @param pixels the address of the caller's own bytes, which is what makes this zero copy: the
     *            parameter is an address, not a segment, so a Java array reaches the shim as itself
     * @param rowBytes the stride of the pixels
     * @param chromaDownsampling an {@code AVIF_CHROMA_DOWNSAMPLING_*} value, or -1 for the default
     * @return an {@code IMAGIFY_AVIF_*} status
     */
    public int pictureFromAbgr(MemorySegment picture, MemorySegment pixels, int rowBytes, int chromaDownsampling) {
        try {
            // An ADDRESS parameter is a MemorySegment as far as the call goes, and a heap segment
            // is
            // allowed there, which is the difference between this and a MemorySegment parameter.
            // The
            // segment is only alive because it is an argument of this call: the shim reads the
            // bytes
            // where they are and does not store the pointer anywhere that outlives them.
            return (int) pictureFromAbgr.invokeExact(picture, pixels, rowBytes, chromaDownsampling);
        } catch (Throwable t) {
            throw new IllegalStateException("imagify_avif_picture_from_abgr() failed", t);
        }
    }

    /**
     * Whether the loaded shim has the BGR encode entry point.
     */
    public boolean hasPictureFromBgr() {
        return pictureFromBgr != null;
    }

    /**
     * Converts tightly packed B, G, R bytes into a picture's planes, without a copy.
     *
     * @throws IllegalStateException when the loaded shim does not export the entry point
     */
    public int pictureFromBgr(MemorySegment picture, MemorySegment pixels, int rowBytes, int chromaDownsampling) {
        if (pictureFromBgr == null) {
            throw new IllegalStateException("the loaded AVIF shim has no imagify_avif_picture_from_bgr()");
        }
        try {
            return (int) pictureFromBgr.invokeExact(picture, pixels, rowBytes, chromaDownsampling);
        } catch (Throwable t) {
            throw new IllegalStateException("imagify_avif_picture_from_bgr() failed", t);
        }
    }

    /**
     * Converts a picture's planes into tightly packed A, B, G, R bytes the caller can read once.
     *
     * @param picture the picture to read
     * @param maxThreads the number of threads the conversion may use, 0 or less for one
     * @param pixels receives the allocated buffer
     * @param length receives its length in bytes
     * @return an {@code IMAGIFY_AVIF_*} status
     */
    public int pictureToAbgr(MemorySegment picture, int maxThreads, MemorySegment pixels, MemorySegment length) {
        try {
            return (int) pictureToAbgr.invokeExact(picture, maxThreads, pixels, length);
        } catch (Throwable t) {
            throw new IllegalStateException("imagify_avif_picture_to_abgr() failed", t);
        }
    }

    /**
     * Whether the loaded shim has the decode-into entry point.
     */
    public boolean hasPictureToAbgrInto() {
        return pictureToAbgrInto != null;
    }

    /**
     * Converts a picture's planes into tightly packed A, B, G, R bytes the caller has already
     * allocated.
     *
     * @throws IllegalStateException when the loaded shim does not export the entry point
     */
    public int pictureToAbgrInto(MemorySegment picture, int maxThreads, MemorySegment pixels, long length) {
        if (pictureToAbgrInto == null) {
            throw new IllegalStateException("the loaded AVIF shim has no imagify_avif_picture_to_abgr_into()");
        }
        try {
            return (int) pictureToAbgrInto.invokeExact(picture, maxThreads, pixels, length);
        } catch (Throwable t) {
            throw new IllegalStateException("imagify_avif_picture_to_abgr_into() failed", t);
        }
    }

    /**
     * Asks the shim to fill in the properties of a picture.
     *
     * @param picture the picture to describe
     * @param out {@link AvifConstants#INFO_COUNT} four byte slots, at the offsets in AvifConstants
     * @return an {@code IMAGIFY_AVIF_*} status
     */
    public int pictureInfo(MemorySegment picture, MemorySegment out) {
        try {
            return (int) pictureInfo.invokeExact(picture, out);
        } catch (Throwable t) {
            throw new IllegalStateException("imagify_avif_picture_info() failed", t);
        }
    }

    public int pictureEncode(MemorySegment picture, int quality, int speed, int alphaQuality, int maxThreads, MemorySegment encoded, MemorySegment length) {
        try {
            return (int) pictureEncode.invokeExact(picture, quality, speed, alphaQuality, maxThreads, encoded, length);
        } catch (Throwable t) {
            throw new IllegalStateException("imagify_avif_picture_encode() failed", t);
        }
    }

    public int pictureDecode(MemorySegment data, long length, int maxThreads, MemorySegment out) {
        try {
            return (int) pictureDecode.invokeExact(data, length, maxThreads, out);
        } catch (Throwable t) {
            throw new IllegalStateException("imagify_avif_picture_decode() failed", t);
        }
    }

    /**
     * Encodes a sequence of frames as one animated AVIF file, with the frames read where they lie.
     *
     * @param frameCount how many frames are in the block
     * @param frames one block of {@code frameCount * width * height * 4} bytes in A, B, G, R order
     * @param durationsMs how long each frame is shown, one entry per frame
     * @param yuvFormat an {@code AVIF_PIXEL_FORMAT_*} value
     * @param chromaDownsampling an {@code AVIF_CHROMA_DOWNSAMPLING_*} value, or -1 for the default
     * @param quality 0 to 100, or -1 for libavif's own
     * @param speed 0 to 10, or -1 for libavif's own
     * @param alphaQuality 0 to 100, or -1 for lossless
     * @param loopCount how often it repeats, 0 meaning forever
     * @param maxThreads the number of threads libavif may use
     * @param encoded receives the allocated file
     * @param length receives its length in bytes
     * @return an {@code IMAGIFY_AVIF_*} status
     */
    public int animationEncode(int frameCount, MemorySegment frames, MemorySegment durationsMs, int width, int height, int yuvFormat, int chromaDownsampling, int quality, int speed, int alphaQuality, int loopCount, int maxThreads, MemorySegment encoded, MemorySegment length) {
        try {
            return (int) animationEncode
                    .invokeExact(frameCount, frames, durationsMs, width, height, yuvFormat, chromaDownsampling, quality, speed, alphaQuality, loopCount, maxThreads, encoded, length);
        } catch (Throwable t) {
            throw new IllegalStateException("imagify_avif_animation_encode() failed", t);
        }
    }

    /**
     * @param data the encoded file
     * @param length its length in bytes
     * @param maxThreads the number of threads libavif may use
     * @param out receives the open sequence
     * @return an {@code IMAGIFY_AVIF_*} status
     */
    public int sequenceOpen(MemorySegment data, long length, int maxThreads, MemorySegment out) {
        try {
            return (int) sequenceOpen.invokeExact(data, length, maxThreads, out);
        } catch (Throwable t) {
            throw new IllegalStateException("imagify_avif_sequence_open() failed", t);
        }
    }

    public void sequenceClose(MemorySegment sequence) {
        try {
            sequenceClose.invokeExact(sequence);
        } catch (Throwable t) {
            throw new IllegalStateException("imagify_avif_sequence_close() failed", t);
        }
    }

    /**
     * Reads the file's own properties, its frame count and its loop count, none of which costs a
     * pixel.
     *
     * @param sequence the open sequence
     * @param out {@link AvifConstants#INFO_COUNT} four byte slots for the file's properties
     * @param frameCount receives how many frames there are
     * @param loopCount receives how often it repeats
     * @return an {@code IMAGIFY_AVIF_*} status
     */
    public int sequenceRead(MemorySegment sequence, MemorySegment out, MemorySegment frameCount, MemorySegment loopCount) {
        try {
            return (int) sequenceRead.invokeExact(sequence, out, frameCount, loopCount);
        } catch (Throwable t) {
            throw new IllegalStateException("imagify_avif_sequence_read() failed", t);
        }
    }

    /**
     * Reads the size and the duration of every frame in one pass over the container.
     *
     * @param sequence the open sequence
     * @param sizes three arrays of {@code frameCount} entries back to back: widths, heights and
     *            durations in milliseconds
     * @return an {@code IMAGIFY_AVIF_*} status
     */
    public int sequenceSizes(MemorySegment sequence, MemorySegment sizes) {
        try {
            return (int) sequenceSizes.invokeExact(sequence, sizes);
        } catch (Throwable t) {
            throw new IllegalStateException("imagify_avif_sequence_sizes() failed", t);
        }
    }

    /**
     * Decodes one frame of a sequence into tightly packed A, B, G, R bytes.
     *
     * @param sequence the open sequence
     * @param index the frame to decode
     * @param maxThreads the number of threads the conversion may use, 0 or less for one
     * @param pixels receives the allocated buffer
     * @param length receives its length in bytes
     * @param width receives the frame's own width, which can be smaller than the file's
     * @param height receives the frame's own height
     * @return an {@code IMAGIFY_AVIF_*} status
     */
    public int sequenceFrame(MemorySegment sequence, int index, int maxThreads, MemorySegment pixels, MemorySegment length, MemorySegment width, MemorySegment height) {
        try {
            return (int) sequenceFrame.invokeExact(sequence, index, maxThreads, pixels, length, width, height);
        } catch (Throwable t) {
            throw new IllegalStateException("imagify_avif_sequence_frame() failed", t);
        }
    }

    /**
     * Whether the loaded shim has the frame-decode-into entry point.
     */
    public boolean hasSequenceFrameInto() {
        return sequenceFrameInto != null;
    }

    /**
     * Decodes one frame of a sequence into a caller-allocated buffer.
     *
     * @throws IllegalStateException when the loaded shim does not export the entry point
     */
    public int sequenceFrameInto(MemorySegment sequence, int index, int maxThreads, MemorySegment pixels, long length, MemorySegment width, MemorySegment height) {
        if (sequenceFrameInto == null) {
            throw new IllegalStateException("the loaded AVIF shim has no imagify_avif_sequence_frame_into()");
        }
        try {
            return (int) sequenceFrameInto.invokeExact(sequence, index, maxThreads, pixels, length, width, height);
        } catch (Throwable t) {
            throw new IllegalStateException("imagify_avif_sequence_frame_into() failed", t);
        }
    }

    public void free(MemorySegment buffer) {
        try {
            free.invokeExact(buffer);
        } catch (Throwable t) {
            throw new IllegalStateException("imagify_avif_free() failed", t);
        }
    }

    private static MethodHandle downcall(Linker linker, SymbolLookup lookup, String symbol, FunctionDescriptor descriptor, Linker.Option... options) {
        return linker.downcallHandle(lookup.find(symbol)
                .orElseThrow(() -> new IllegalArgumentException("the AVIF shim does not export " + symbol)), descriptor, options);
    }

    private static MethodHandle optionalDowncall(Linker linker, SymbolLookup lookup, String symbol, FunctionDescriptor descriptor, Linker.Option... options) {
        MemorySegment address = lookup.find(symbol).orElse(null);
        if (address == null) {
            return null;
        }
        try {
            return linker.downcallHandle(address, descriptor, options);
        } catch (Throwable t) {
            throw new IllegalStateException("cannot create downcall handle for " + symbol, t);
        }
    }
}
