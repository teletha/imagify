/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.jpeg.ffm;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * FFM binding for the C ABI in {@code src/main/native/jpegli/imagify_jpegli.h}.
 *
 * <p>This is the Foreign Function &amp; Memory API (JEP 454) counterpart to the JNA binding that
 * used to sit in {@code imagify.jpeg.jna}. It describes the function signatures of the
 * {@code jpegli} based shared library and calls them through {@code java.lang.foreign}. There are no
 * structures to describe: the shim keeps {@code struct jpeg_compress_struct} and
 * {@code struct jpeg_decompress_struct} on its own side of the boundary and exposes only plain
 * scalars, pointers and buffers, which is exactly what makes an FFM binding of it small.
 *
 * <p>See {@link JpegliCodec} for how the native library is loaded and called.
 *
 * @see <a href="https://github.com/google/jpegli">google/jpegli</a>
 */
public final class JpegliLibrary {

    /**
     * Name of the shared library as this jar builds it.
     *
     * <p>Deliberately not {@code jpeg}. jpegli's own libjpeg62 compatible library is named
     * {@code libjpeg.so.62}, which is also the name of every system libjpeg on Linux, and two of
     * those can end up mapped in one process. This jar has no reason to interoperate with a system
     * libjpeg, so the bundled library is given a name of its own.
     */
    public static final String LIBRARY_NAME = "jpegli";

    /**
     * The version of the C ABI this binding was written against, as
     * {@code IMAGIFY_JPEGLI_ABI_VERSION}
     * in {@code src/main/native/jpegli/imagify_jpegli.h} spells it.
     *
     * <p>It is checked once when the library is loaded and nothing else depends on it, because the
     * only way it could ever be wrong is a library built against a different revision of the
     * header, and that has to be caught before the first call rather than after it has read a size
     * from the wrong offset.
     */
    public static final String ABI_VERSION = "1";

    // ---------------------------------------------------------------------------------- statuses

    /** The operation completed. */
    public static final int IMAGIFY_JPEG_OK = 0;

    /** A parameter was out of range. */
    public static final int IMAGIFY_JPEG_ERR_ARGUMENT = 1;

    /** The input is not a JPEG, or is a damaged one. */
    public static final int IMAGIFY_JPEG_ERR_CORRUPT = 2;

    /** An allocation failed. */
    public static final int IMAGIFY_JPEG_ERR_MEMORY = 3;

    /** jpegli failed in a way its own error text does not explain. */
    public static final int IMAGIFY_JPEG_ERR_INTERNAL = 4;

    // ----------------------------------------------------------------------------- subsampling

    /** No subsampling at all, the largest file. */
    public static final int IMAGIFY_JPEG_SAMP_444 = 0;

    /** The colour channels half the width. */
    public static final int IMAGIFY_JPEG_SAMP_422 = 1;

    /** The colour channels half in both directions, the smallest file. */
    public static final int IMAGIFY_JPEG_SAMP_420 = 2;

    /** The lowest quality the encoder accepts. */
    public static final int IMAGIFY_JPEG_MIN_QUALITY = 1;

    /** The highest quality the encoder accepts. */
    public static final int IMAGIFY_JPEG_MAX_QUALITY = 100;

    /**
     * Quality the ImageIO writer and {@code JpegWriteParam} use when a caller does not ask for one.
     *
     * <p>The number is the one {@code imagify.ImageFormat.Jpeg} carries as its 0.0 to 1.0
     * {@code defaultQuality}, restated on the encoder's own 1 to 100 scale, the same way
     * {@code AvifConstants.DEFAULT_QUALITY} and {@code WebpLibrary.DEFAULT_QUALITY} restate theirs.
     * A codec cannot read a package private member of the format that describes it, so each one
     * says what it does by default and the format's own value follows it.
     */
    public static final int DEFAULT_QUALITY = 85;

    /**
     * Size of the buffer every entry point writes its failure description into.
     *
     * <p>Matches {@code JMSG_LENGTH_MAX} in {@code jpeglib.h}, which is what bounds the text
     * libjpeg has formatted before it reports the error.
     */
    public static final int MESSAGE_LENGTH = 200;

    // ----------------------------------------------------------------- function descriptors

    private static final Linker LINKER = Linker.nativeLinker();

    private static final FunctionDescriptor ABI_VERSION_DESC = FunctionDescriptor.of(ValueLayout.ADDRESS);

    private static final FunctionDescriptor JPEGLI_VERSION_DESC = FunctionDescriptor.of(ValueLayout.ADDRESS);

    private static final FunctionDescriptor READ_HEADER_DESC = FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);

    private static final FunctionDescriptor DECODE_DESC = FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);

    private static final FunctionDescriptor ENCODE_DESC = FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);

    private static final FunctionDescriptor FREE_DESC = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);

    // -------------------------------------------------------------------- downcall handles

    private final MethodHandle abiVersion;

    private final MethodHandle jpegliVersion;

    private final MethodHandle readHeader;

    private final MethodHandle decode;

    private final MethodHandle encode;

    private final MethodHandle free;

    /**
     * Creates a new FFM binding for the native library identified by {@code lookup}.
     *
     * <p>The caller is responsible for ensuring the library is already loaded (via
     * {@link System#load(String)} or {@link System#loadLibrary(String)}) so that its symbols are
     * visible to the lookup it passes in.
     *
     * @param lookup a symbol lookup that can resolve the {@code imagify_jpegli_*} symbols
     * @throws IllegalStateException when a symbol is missing, which means the shim is not this
     *             revision of the ABI
     */
    public JpegliLibrary(SymbolLookup lookup) {
        this.abiVersion = resolveSymbol(lookup, "imagify_jpegli_abi_version", ABI_VERSION_DESC);
        this.jpegliVersion = resolveSymbol(lookup, "imagify_jpegli_jpegli_version", JPEGLI_VERSION_DESC);
        this.readHeader = resolveSymbol(lookup, "imagify_jpegli_read_header", READ_HEADER_DESC);
        this.decode = resolveSymbol(lookup, "imagify_jpegli_decode", DECODE_DESC);
        // The one call in this binding that reads a Java array where it lies rather than a copy of
        // it. critical(true) is what lets it: a heap segment, which is what a Java array becomes, is
        // refused as a pointer argument otherwise. The array is pinned for the length of the call,
        // which is the length of an encode, and the shim reads width * height * 4 bytes of it in
        // place, so the bytes never go through a native buffer at all.
        this.encode = resolveSymbol(lookup, "imagify_jpegli_encode", ENCODE_DESC, Linker.Option.critical(true));
        this.free = resolveSymbol(lookup, "imagify_jpegli_free", FREE_DESC);
    }

    // ----------------------------------------------------------------------- abi version

    /**
     * Calls {@code imagify_jpegli_abi_version()}.
     *
     * @return the ABI version string, for example {@code "1"}
     */
    public String imagify_jpegli_abi_version() {
        return text(abiVersion, "imagify_jpegli_abi_version()");
    }

    /**
     * Calls {@code imagify_jpegli_jpegli_version()}.
     *
     * @return the jpegli version string, for example {@code "0.12.0"}
     */
    public String imagify_jpegli_jpegli_version() {
        return text(jpegliVersion, "imagify_jpegli_jpegli_version()");
    }

    // --------------------------------------------------------------------------- header

    /**
     * Calls {@code imagify_jpegli_read_header}.
     *
     * <p>The last seven out parameters may all be {@link MemorySegment#NULL}, which is how a caller
     * that only wants the size of a file says so.
     *
     * @return {@link #IMAGIFY_JPEG_OK}, or a status saying what went wrong
     */
    public int imagify_jpegli_read_header(MemorySegment data, long length, MemorySegment width,
            MemorySegment height, MemorySegment components, MemorySegment progressive,
            MemorySegment horizontalFactor, MemorySegment verticalFactor, MemorySegment densityUnit,
            MemorySegment horizontalDensity, MemorySegment verticalDensity, MemorySegment precision,
            MemorySegment message, long messageCapacity) {
        try {
            return (int) readHeader.invokeExact(data, length, width, height, components, progressive,
                    horizontalFactor, verticalFactor, densityUnit, horizontalDensity, verticalDensity,
                    precision, message, messageCapacity);
        } catch (Throwable t) {
            throw new IllegalStateException("imagify_jpegli_read_header() failed", t);
        }
    }

    // --------------------------------------------------------------------------- decode

    /**
     * Calls {@code imagify_jpegli_decode}.
     *
     * @return {@link #IMAGIFY_JPEG_OK}, or a status saying what went wrong
     */
    public int imagify_jpegli_decode(MemorySegment data, long length, MemorySegment out,
            MemorySegment outLength, MemorySegment width, MemorySegment height, MemorySegment message,
            long messageCapacity) {
        try {
            return (int) decode.invokeExact(data, length, out, outLength, width, height, message,
                    messageCapacity);
        } catch (Throwable t) {
            throw new IllegalStateException("imagify_jpegli_decode() failed", t);
        }
    }

    // --------------------------------------------------------------------------- encode

    /**
     * Calls {@code imagify_jpegli_encode}.
     *
     * <p>{@code pixels} may be a heap segment, which is what a Java array becomes: the call was
     * built with {@link Linker.Option#critical(boolean)} so that the shim reads the bytes where they
     * are rather than out of a copy of them.
     *
     * @return {@link #IMAGIFY_JPEG_OK}, or a status saying what went wrong
     */
    public int imagify_jpegli_encode(MemorySegment pixels, int width, int height, int quality,
            int subsampling, int optimizeCoding, MemorySegment encoded, MemorySegment encodedLength,
            MemorySegment message, long messageCapacity) {
        try {
            return (int) encode.invokeExact(pixels, width, height, quality, subsampling, optimizeCoding,
                    encoded, encodedLength, message, messageCapacity);
        } catch (Throwable t) {
            throw new IllegalStateException("imagify_jpegli_encode() failed", t);
        }
    }

    // --------------------------------------------------------------------------- free

    /**
     * Calls {@code imagify_jpegli_free}. A {@link MemorySegment#NULL} is accepted and ignored.
     *
     * @param buffer the buffer to free
     */
    public void imagify_jpegli_free(MemorySegment buffer) {
        if (buffer == null || buffer.address() == 0) {
            return;
        }
        try {
            free.invokeExact(buffer);
        } catch (Throwable t) {
            throw new IllegalStateException("imagify_jpegli_free() failed", t);
        }
    }

    // -------------------------------------------------------------------------- helpers

    private static String text(MethodHandle handle, String name) {
        try {
            MemorySegment pointer = (MemorySegment) handle.invokeExact();
            if (pointer.address() == 0) {
                return "NULL_POINTER";
            }
            return pointer.reinterpret(256).getString(0, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            throw new IllegalStateException(name + " failed", t);
        }
    }

    private static MethodHandle resolveSymbol(SymbolLookup lookup, String name,
            FunctionDescriptor descriptor, Linker.Option... options) {
        MemorySegment symbol = lookup.find(name)
                .orElseThrow(() -> new IllegalStateException("cannot find native symbol: " + name));
        try {
            return LINKER.downcallHandle(symbol, descriptor, options);
        } catch (Throwable t) {
            throw new IllegalStateException("cannot create downcall handle for " + name, t);
        }
    }
}
