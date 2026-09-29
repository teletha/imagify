/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.webp.ffm;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * FFM binding for the C ABI in {@code src/main/native/webp/imagify_webp.h}.
 *
 * <p>This is the Foreign Function &amp; Memory API (JEP 454) counterpart to the JNA binding.
 * It describes the function signatures and structure layouts needed to call the native
 * {@code libwebp}-based shared library through Java's standard {@code java.lang.foreign} API.
 *
 * <p>See {@link WebpCodec} for how the native library is loaded and called.
 *
 * @see <a href="https://chromium.googlesource.com/webm/libwebp">webmproject/libwebp</a>
 */
public final class WebpLibrary {

    /**
     * Name of the shared library as this jar builds it.
     *
     * <p>Deliberately not {@code webp}. libwebp builds {@code libwebp.so}, which is also the name
     * of every system {@code libwebp} on Linux, so two of those can end up mapped in one process.
     * This jar has no reason to interoperate with a system libwebp, so the bundled library is given
     * a name of its own.
     */
    public static final String LIBRARY_NAME = "imagifywebp";

    /**
     * The version of the C ABI this binding was written against, as
     * {@code IMAGIFY_WEBP_ABI_VERSION} in {@code src/main/native/webp/imagify_webp.h} spells it.
     */
    public static final String ABI_VERSION = "1";

    // ---------------------------------------------------------------------------------- statuses

    /** The operation completed. */
    public static final int IMAGIFY_WEBP_OK = 0;

    /** A parameter was out of range, before any work was done. */
    public static final int IMAGIFY_WEBP_ERR_ARGUMENT = 1;

    /** The input is not a WebP file, or is a damaged one. */
    public static final int IMAGIFY_WEBP_ERR_CORRUPT = 2;

    /** An allocation failed. */
    public static final int IMAGIFY_WEBP_ERR_MEMORY = 3;

    /** libwebp will not do this, for instance a dimension too large. */
    public static final int IMAGIFY_WEBP_ERR_UNSUPPORTED = 4;

    /** libwebp failed in a way its own error text does not explain. */
    public static final int IMAGIFY_WEBP_ERR_INTERNAL = 5;

    // ---------------------------------------------------------------------------------- formats

    /** {@code libwebp} reports a lossy {@code VP8} bitstream with this format code. */
    public static final int FORMAT_VP8 = 1;

    /** {@code libwebp} reports a lossless {@code VP8L} bitstream with this format code. */
    public static final int FORMAT_VP8L = 2;

    /** {@code libwebp} reports the {@code VP8X} container, which is what an animation uses. */
    public static final int FORMAT_VP8X = 0;

    // ---------------------------------------------------------------------------------- ranges

    /** The lowest quality the encoder accepts. */
    public static final int MIN_QUALITY = 0;

    /** The highest quality the encoder accepts. */
    public static final int MAX_QUALITY = 100;

    /** The quickest encoding effort the encoder accepts. */
    public static final int MIN_METHOD = 0;

    /** The most thorough encoding effort the encoder accepts. */
    public static final int MAX_METHOD = 6;

    /**
     * Quality used when the caller does not ask for one: {@code 75}, the {@code libwebp} default.
     */
    public static final int DEFAULT_QUALITY = 75;

    /**
     * Size of the buffer every entry point writes its failure description into.
     *
     * <p>Matches {@code IMAGIFY_WEBP_MESSAGE_LENGTH} in {@code imagify_webp.h}.
     */
    public static final int MESSAGE_LENGTH = 256;

    // ----------------------------------------------------------------------- struct layouts

    /**
     * Layout of {@code imagify_webp_features}, matching the C struct in {@code imagify_webp.h}.
     * Fields are laid out in declaration order with no padding (all are {@code int}, 4 bytes each).
     */
    public static final StructLayout WEBP_FEATURES_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("width"),
            ValueLayout.JAVA_INT.withName("height"),
            ValueLayout.JAVA_INT.withName("hasAlpha"),
            ValueLayout.JAVA_INT.withName("hasAnimation"),
            ValueLayout.JAVA_INT.withName("format")
    );

    /**
     * Layout of {@code imagify_webp_animation}, matching the C struct in {@code imagify_webp.h}.
     */
    public static final StructLayout WEBP_ANIMATION_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("frameCount"),
            ValueLayout.JAVA_INT.withName("loopCount"),
            ValueLayout.JAVA_INT.withName("width"),
            ValueLayout.JAVA_INT.withName("height")
    );

    /** The byte size of the {@link #WEBP_FEATURES_LAYOUT} struct. */
    public static final long WEBP_FEATURES_SIZE = WEBP_FEATURES_LAYOUT.byteSize();

    /** The byte size of the {@link #WEBP_ANIMATION_LAYOUT} struct. */
    public static final long WEBP_ANIMATION_SIZE = WEBP_ANIMATION_LAYOUT.byteSize();

    // -------------------------------------------------------------------- field offsets

    /*
     * Read out of the layouts above rather than written out by hand. Both structures hold five and
     * four ints, so a hand written offset is only ever right by coincidence, and reading a field out
     * of the wrong structure returns a plausible looking number rather than failing: the two put
     * frameCount and width at different offsets, and mixing them up decodes an animation as a canvas
     * a few pixels wide. These cannot drift away from the layouts they are meant to describe.
     */

    /** Offset of {@code width} in {@link #WEBP_FEATURES_LAYOUT}. */
    public static final long OFFSET_WIDTH = offsetOf(WEBP_FEATURES_LAYOUT, "width");

    /** Offset of {@code height} in {@link #WEBP_FEATURES_LAYOUT}. */
    public static final long OFFSET_HEIGHT = offsetOf(WEBP_FEATURES_LAYOUT, "height");

    /** Offset of {@code hasAlpha} in {@link #WEBP_FEATURES_LAYOUT}. */
    public static final long OFFSET_HAS_ALPHA = offsetOf(WEBP_FEATURES_LAYOUT, "hasAlpha");

    /** Offset of {@code hasAnimation} in {@link #WEBP_FEATURES_LAYOUT}. */
    public static final long OFFSET_HAS_ANIMATION = offsetOf(WEBP_FEATURES_LAYOUT, "hasAnimation");

    /** Offset of {@code format} in {@link #WEBP_FEATURES_LAYOUT}. */
    public static final long OFFSET_FORMAT = offsetOf(WEBP_FEATURES_LAYOUT, "format");

    /** Offset of {@code frameCount} in {@link #WEBP_ANIMATION_LAYOUT}. */
    public static final long OFFSET_FRAME_COUNT = offsetOf(WEBP_ANIMATION_LAYOUT, "frameCount");

    /** Offset of {@code loopCount} in {@link #WEBP_ANIMATION_LAYOUT}. */
    public static final long OFFSET_LOOP_COUNT = offsetOf(WEBP_ANIMATION_LAYOUT, "loopCount");

    /**
     * Offset of {@code width} in {@link #WEBP_ANIMATION_LAYOUT}.
     *
     * <p>Not {@link #OFFSET_WIDTH}: that one is where the width sits in
     * {@link #WEBP_FEATURES_LAYOUT}, and the two structures do not put it in the same place.
     */
    public static final long OFFSET_ANIMATION_WIDTH = offsetOf(WEBP_ANIMATION_LAYOUT, "width");

    /**
     * Offset of {@code height} in {@link #WEBP_ANIMATION_LAYOUT}.
     *
     * <p>Not {@link #OFFSET_HEIGHT}, for the reason given on {@link #OFFSET_ANIMATION_WIDTH}.
     */
    public static final long OFFSET_ANIMATION_HEIGHT = offsetOf(WEBP_ANIMATION_LAYOUT, "height");

    private static long offsetOf(StructLayout layout, String member) {
        return layout.byteOffset(MemoryLayout.PathElement.groupElement(member));
    }

    // ----------------------------------------------------------------- function descriptors

    private static final Linker LINKER = Linker.nativeLinker();

    /** Function descriptor for {@code imagify_webp_abi_version()}. */
    public static final FunctionDescriptor ABI_VERSION_DESC = FunctionDescriptor.of(ValueLayout.ADDRESS);

    /** Function descriptor for {@code imagify_webp_webp_version()}. */
    public static final FunctionDescriptor WEBP_VERSION_DESC = FunctionDescriptor.of(ValueLayout.ADDRESS);

    /** Function descriptor for {@code imagify_webp_read_features}. */
    public static final FunctionDescriptor READ_FEATURES_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG
    );

    /** Function descriptor for {@code imagify_webp_decode}. */
    public static final FunctionDescriptor DECODE_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG
    );

    /** Function descriptor for {@code imagify_webp_encode}. */
    public static final FunctionDescriptor ENCODE_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG
    );

    /** Function descriptor for {@code imagify_webp_read_animation}. */
    public static final FunctionDescriptor READ_ANIMATION_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG
    );

    /** Function descriptor for {@code imagify_webp_decode_animation}. */
    public static final FunctionDescriptor DECODE_ANIMATION_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG
    );

    /** Function descriptor for {@code imagify_webp_encode_animation}. */
    public static final FunctionDescriptor ENCODE_ANIMATION_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG
    );

    /** Function descriptor for {@code imagify_webp_encode_argb}. */
    public static final FunctionDescriptor ENCODE_ARGB_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG
    );

    /** Function descriptor for {@code imagify_webp_encode_animation_argb}. */
    public static final FunctionDescriptor ENCODE_ANIMATION_ARGB_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG
    );

    /** Function descriptor for {@code imagify_webp_decode_into_argb}. */
    public static final FunctionDescriptor DECODE_INTO_ARGB_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG
    );

    /** Function descriptor for {@code imagify_webp_free}. */
    public static final FunctionDescriptor FREE_DESC = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);

    // ------------------------------------------------------------------- data records

    /**
     * The properties of a WebP file's container headers, as reported by
     * {@link #imagify_webp_read_features}.
     */
    public record WebpFeaturesInfo(int width, int height, int hasAlpha, int hasAnimation, int format) {}

    /**
     * The properties of a WebP animation's control chunk, as reported by
     * {@link #imagify_webp_read_animation} and {@link #imagify_webp_decode_animation}.
     */
    public record WebpAnimationInfo(int frameCount, int loopCount, int width, int height) {}

    // -------------------------------------------------------------------- downcall handles

    private final MethodHandle abiVersionHandle;
    private final MethodHandle webpVersionHandle;
    private final MethodHandle readFeaturesHandle;
    private final MethodHandle decodeHandle;
    private final MethodHandle encodeHandle;
    private final MethodHandle readAnimationHandle;
    private final MethodHandle decodeAnimationHandle;
    private final MethodHandle encodeAnimationHandle;
    private final MethodHandle freeHandle;

    private final MethodHandle encodeArgbHandle;
    private final MethodHandle encodeAnimationArgbHandle;
    private final MethodHandle decodeIntoArgbHandle;

    /**
     * Creates a new FFM binding for the native library identified by {@code lookup}.
     *
     * <p>The caller is responsible for ensuring the library is already loaded (via
     * {@link System#load(String)} or {@link System#loadLibrary(String)}) so that its
     * symbols are visible to the default symbol lookup.
     *
     * @param lookup a symbol lookup that can resolve the {@code imagify_webp_*} symbols
     */
    public WebpLibrary(SymbolLookup lookup) {
        this.abiVersionHandle = resolveSymbol(lookup, "imagify_webp_abi_version", ABI_VERSION_DESC);
        this.webpVersionHandle = resolveSymbol(lookup, "imagify_webp_webp_version", WEBP_VERSION_DESC);
        this.readFeaturesHandle = resolveSymbol(lookup, "imagify_webp_read_features", READ_FEATURES_DESC);
        this.decodeHandle = resolveSymbol(lookup, "imagify_webp_decode", DECODE_DESC);
        this.encodeHandle = resolveSymbol(lookup, "imagify_webp_encode", ENCODE_DESC);
        this.readAnimationHandle = resolveSymbol(lookup, "imagify_webp_read_animation", READ_ANIMATION_DESC);
        this.decodeAnimationHandle = resolveSymbol(lookup, "imagify_webp_decode_animation", DECODE_ANIMATION_DESC);
        this.encodeAnimationHandle = resolveSymbol(lookup, "imagify_webp_encode_animation", ENCODE_ANIMATION_DESC);
        this.freeHandle = resolveSymbol(lookup, "imagify_webp_free", FREE_DESC);
        this.encodeArgbHandle = resolveOptionalSymbol(lookup, "imagify_webp_encode_argb", ENCODE_ARGB_DESC);
        this.encodeAnimationArgbHandle = resolveOptionalSymbol(lookup, "imagify_webp_encode_animation_argb", ENCODE_ANIMATION_ARGB_DESC);
        this.decodeIntoArgbHandle = resolveOptionalSymbol(lookup, "imagify_webp_decode_into_argb", DECODE_INTO_ARGB_DESC);
    }

    /**
     * Whether the loaded library has the entry points that read and write {@code 0xAARRGGBB} words
     * directly, which is what lets an image already in that layout be encoded and a decoded one be
     * wrapped without a shuffle of the pixels in between.
     *
     * <p>All three or none: they were added to the header together and the export list lists them
     * together, so a library that has two of them is not one this binding knows how to talk to, and
     * treating it as though it has none is the answer that still works.
     *
     * @return {@code true} when the direct word entry points can be called
     */
    public boolean hasArgbEntryPoints() {
        return encodeArgbHandle != null && encodeAnimationArgbHandle != null
                && decodeIntoArgbHandle != null;
    }

    // ----------------------------------------------------------------------- abi version

    /**
     * Calls {@code imagify_webp_abi_version()}.
     *
     * @return the ABI version string
     */
    public String imagify_webp_abi_version() {
        try {
            Object result = abiVersionHandle.invoke();
            MemorySegment ptr = (MemorySegment) result;
            if (ptr.address() == 0) {
                return "NULL_POINTER";
            }
            // Reinterpret as a bounded segment to read the null-terminated string
            MemorySegment bounded = ptr.reinterpret(256);
            return bounded.getString(0, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            throw new RuntimeException("imagify_webp_abi_version() failed", t);
        }
    }

    /**
     * Calls {@code imagify_webp_webp_version()}.
     *
     * @return the libwebp version string
     */
    public String imagify_webp_webp_version() {
        try {
            Object result = webpVersionHandle.invoke();
            MemorySegment ptr = (MemorySegment) result;
            if (ptr.address() == 0) {
                return "NULL_POINTER";
            }
            MemorySegment bounded = ptr.reinterpret(256);
            return bounded.getString(0, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            throw new RuntimeException("imagify_webp_webp_version() failed", t);
        }
    }

    // ----------------------------------------------------------------------- read features

    /**
     * Calls {@code imagify_webp_read_features}.
     */
    public int imagify_webp_read_features(MemorySegment data, long length, MemorySegment features, MemorySegment message, long messageCapacity) {
        try {
            return (int) readFeaturesHandle.invokeExact(data, length, features, message, messageCapacity);
        } catch (Throwable t) {
            throw new RuntimeException("imagify_webp_read_features() failed", t);
        }
    }

    // ------------------------------------------------------------------------- decode

    /**
     * Calls {@code imagify_webp_decode}.
     */
    public int imagify_webp_decode(MemorySegment data, long length, MemorySegment out, MemorySegment outLength, MemorySegment features, MemorySegment message, long messageCapacity) {
        try {
            return (int) decodeHandle.invoke(data, length, out, outLength, features, message, messageCapacity);
        } catch (Throwable t) {
            throw new RuntimeException("imagify_webp_decode() failed", t);
        }
    }

    // ------------------------------------------------------------------------- encode

    /**
     * Calls {@code imagify_webp_encode}.
     */
    public int imagify_webp_encode(MemorySegment pixels, int width, int height, int quality, int lossless, int method, MemorySegment encoded, MemorySegment encodedLength, MemorySegment message, long messageCapacity) {
        try {
            return (int) encodeHandle.invoke(pixels, width, height, quality, lossless, method, encoded, encodedLength, message, messageCapacity);
        } catch (Throwable t) {
            throw new RuntimeException("imagify_webp_encode() failed", t);
        }
    }

    // ----------------------------------------------------------------- the word entry points

    /*
     * The three below take pixels as 0xAARRGGBB words rather than as A, B, G, R bytes. They are
     * optional: hasArgbEntryPoints() has to be true before any of them is called, and a library
     * without them is a normal thing to load rather than a broken one.
     */

    /**
     * Calls {@code imagify_webp_decode_into_argb}.
     *
     * @throws IllegalStateException when the loaded library does not have the entry point
     */
    public int imagify_webp_decode_into_argb(MemorySegment data, long length, MemorySegment out, int outStride, MemorySegment features, MemorySegment message, long messageCapacity) {
        requireArgb(decodeIntoArgbHandle, "imagify_webp_decode_into_argb");
        try {
            return (int) decodeIntoArgbHandle.invoke(data, length, out, outStride, features, message, messageCapacity);
        } catch (Throwable t) {
            throw new RuntimeException("imagify_webp_decode_into_argb() failed", t);
        }
    }

    /**
     * Calls {@code imagify_webp_encode_argb}.
     *
     * @throws IllegalStateException when the loaded library does not have the entry point
     */
    public int imagify_webp_encode_argb(MemorySegment pixels, int width, int height, int quality, int lossless, int method, MemorySegment encoded, MemorySegment encodedLength, MemorySegment message, long messageCapacity) {
        requireArgb(encodeArgbHandle, "imagify_webp_encode_argb");
        try {
            return (int) encodeArgbHandle.invoke(pixels, width, height, quality, lossless, method, encoded, encodedLength, message, messageCapacity);
        } catch (Throwable t) {
            throw new RuntimeException("imagify_webp_encode_argb() failed", t);
        }
    }

    /**
     * Calls {@code imagify_webp_encode_animation_argb}.
     *
     * @throws IllegalStateException when the loaded library does not have the entry point
     */
    public int imagify_webp_encode_animation_argb(MemorySegment frames, int frameCount, int width, int height, MemorySegment delays, int quality, int lossless, int loopCount, int method, MemorySegment encoded, MemorySegment encodedLength, MemorySegment message, long messageCapacity) {
        requireArgb(encodeAnimationArgbHandle, "imagify_webp_encode_animation_argb");
        try {
            return (int) encodeAnimationArgbHandle.invoke(frames, frameCount, width, height, delays, quality, lossless, loopCount, method, encoded, encodedLength, message, messageCapacity);
        } catch (Throwable t) {
            throw new RuntimeException("imagify_webp_encode_animation_argb() failed", t);
        }
    }

    private static void requireArgb(MethodHandle handle, String name) {
        if (handle == null) {
            throw new IllegalStateException("the loaded WebP library has no " + name
                    + "(); it was built before that entry point was added to"
                    + " src/main/native/webp/imagify_webp.h");
        }
    }

    // ------------------------------------------------------------------- read animation

    /**
     * Calls {@code imagify_webp_read_animation}.
     */
    public int imagify_webp_read_animation(MemorySegment data, long length, MemorySegment animation, MemorySegment delays, MemorySegment message, long messageCapacity) {
        try {
            return (int) readAnimationHandle.invokeExact(data, length, animation, delays, message, messageCapacity);
        } catch (Throwable t) {
            throw new RuntimeException("imagify_webp_read_animation() failed", t);
        }
    }

    // ------------------------------------------------------------------- decode animation

    /**
     * Calls {@code imagify_webp_decode_animation}.
     */
    public int imagify_webp_decode_animation(MemorySegment data, long length, MemorySegment animation, MemorySegment frames, MemorySegment delays, MemorySegment message, long messageCapacity) {
        try {
            return (int) decodeAnimationHandle.invokeExact(data, length, animation, frames, delays, message, messageCapacity);
        } catch (Throwable t) {
            throw new RuntimeException("imagify_webp_decode_animation() failed", t);
        }
    }

    // ------------------------------------------------------------------ encode animation

    /**
     * Calls {@code imagify_webp_encode_animation}.
     */
    public int imagify_webp_encode_animation(MemorySegment frames, int frameCount, int width, int height, MemorySegment delays, int quality, int lossless, int loopCount, int method, MemorySegment encoded, MemorySegment encodedLength, MemorySegment message, long messageCapacity) {
        try {
            return (int) encodeAnimationHandle.invoke(frames, frameCount, width, height, delays, quality, lossless, loopCount, method, encoded, encodedLength, message, messageCapacity);
        } catch (Throwable t) {
            throw new RuntimeException("imagify_webp_encode_animation() failed", t);
        }
    }

    // --------------------------------------------------------------------------- free

    /**
     * Calls {@code imagify_webp_free}.
     */
    public void imagify_webp_free(MemorySegment buffer) {
        if (buffer.address() == 0) {
            return;
        }
        try {
            freeHandle.invokeExact(buffer);
        } catch (Throwable t) {
            throw new RuntimeException("imagify_webp_free() failed", t);
        }
    }

    // -------------------------------------------------------------------------- helpers

    /**
     * Resolves a native symbol by name and creates a downcall handle for it.
     *
     * @param lookup the symbol lookup
     * @param name the symbol name
     * @param descriptor the function descriptor
     * @return a downcall handle for the symbol
     * @throws IllegalStateException if the symbol cannot be found
     */
    private static MethodHandle resolveSymbol(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = lookup.find(name)
                .orElseThrow(() -> new IllegalStateException("cannot find native symbol: " + name));
        try {
            return LINKER.downcallHandle(symbol, descriptor);
        } catch (Throwable t) {
            throw new IllegalStateException("cannot create downcall handle for " + name, t);
        }
    }

    /**
     * Resolves a symbol that a library built before this entry point existed will not have.
     *
     * <p>The 0xAARRGGBB entry points are additive: nothing that made up ABI version 1 changed, and
     * the libraries this jar ships are built one platform at a time, so one of them is quite likely
     * to predate them while the binding that reads all six does not. A missing symbol is therefore
     * an ordinary outcome and not a failure to load, and a {@code null} handle is what says so.
     * Every call site checks {@link #hasArgbEntryPoints()} before it uses one, and the byte layout
     * entry points it would otherwise have used are still there to be used.
     *
     * <p>{@code critical} is what lets these be called with a segment over a Java array. The FFM
     * linker will not pass a heap segment down by default, and the whole reason these entry points
     * exist is that the pixels are not copied, so an arena and a copy of an image's worth of words
     * is the one thing they must not do. The price is that the array is pinned for the length of the
     * call, which is the length of an encode: a long one holds a young generation collection up, and
     * a short one is over before the next one would have started. That is a real cost, and it is
     * paid only by the callers that chose this path by having an image already in the layout, and
     * it is smaller than the shuffle the other path performs on the same image.
     *
     * @param lookup the symbol lookup
     * @param name the symbol name
     * @param descriptor the function descriptor
     * @return a downcall handle for the symbol, or {@code null} when the library does not export it
     */
    private static MethodHandle resolveOptionalSymbol(SymbolLookup lookup, String name,
            FunctionDescriptor descriptor) {
        MemorySegment symbol = lookup.find(name).orElse(null);
        if (symbol == null) {
            return null;
        }
        try {
            return LINKER.downcallHandle(symbol, descriptor, Linker.Option.critical(true));
        } catch (Throwable t) {
            // A library that exports the name but not in the shape this binding expects is a
            // different failure from one that does not export it, and is worth saying out loud
            // rather than quietly treating as absent.
            throw new IllegalStateException("cannot create downcall handle for " + name, t);
        }
    }
}
