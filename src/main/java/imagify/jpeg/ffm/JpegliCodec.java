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

import static java.lang.System.*;

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;

import imagify.jpeg.JpegException;
import imagify.jpeg.JpegImageInfo;
import imagify.pixels.AbgrPixels;

/**
 * Entry point to the {@code jpegli} based JPEG codec using Java's Foreign Function &amp; Memory API
 * (JEP 454).
 *
 * <p>This is the encoder and decoder behind both the ImageIO service providers in
 * {@code imagify.jpeg} and the direct calls {@link imagify.ImageWriter} and
 * {@link imagify.ImageReader} make, so a JPEG written through any of them comes out of the same
 * encoder with the same settings.
 *
 * <p>The native library is loaded lazily, on first use, and failing to load it is never fatal: the
 * ImageIO service providers of this library then stay inert instead of breaking {@code ImageIO} for
 * every other format, and the facade falls back to the JDK's own JPEG support. Use
 * {@link #isAvailable()} to find out which of the two a given call will use.
 *
 * <p>Shared libraries for Windows, macOS and Linux, in both 64 bit flavours, are fetched on first
 * use from the GitHub release the codec's {@code native.properties} names, and are cached locally,
 * so installing anything is not required. Should none be available for the current platform — an
 * unsupported one, an unpublished release, a blocked download — a jpegli based one found the usual
 * way is used instead: point the {@code java.library.path} system property (or the platform
 * specific {@code PATH} / {@code LD_LIBRARY_PATH}) at the directory that holds it.
 *
 * <pre>
 * java -Djava.library.path=/usr/local/lib -cp ... YourApp
 * </pre>
 *
 * <p>Set {@code -Dimagify.jpeg.bundled=false} to ignore the managed library and always look for one
 * installed on the system.
 *
 * <p>An encode whose source is already a {@link BufferedImage#TYPE_4BYTE_ABGR} image hands its own
 * backing array to the shim, which reads it in place, so the pixels are not copied into native
 * memory on the way in. Anything else is converted to that layout once, through the shared
 * {@link AbgrPixels}.
 *
 * @see <a href="https://github.com/google/jpegli">google/jpegli</a>
 */
public final class JpegliCodec {

    private static final Logger log = getLogger(JpegliCodec.class.getName());

    private static final Object LOCK = new Object();

    private static volatile boolean loaded;
    private static volatile JpegliLibrary library;
    private static volatile String failure;

    private JpegliCodec() {
        // utility class
    }

    // ---------------------------------------------------------------------------- availability

    /**
     * Returns whether JPEG encoding and decoding through jpegli can be used in this JVM.
     *
     * @return {@code true} when the native library was loaded and its ABI is one this jar speaks
     */
    public static boolean isAvailable() {
        return library() != null;
    }

    /**
     * Returns why the native library cannot be used, or {@code null} when everything is fine.
     *
     * @return a human readable description of the problem
     */
    public static String getUnavailableReason() {
        library();
        return failure;
    }

    /**
     * Returns the version of the loaded jpegli, for example {@code "0.12.0"}.
     *
     * @return the reported version, or {@code null} when the library is unavailable
     */
    public static String getVersion() {
        JpegliLibrary lib = library();
        return lib == null ? null : lib.imagify_jpegli_jpegli_version();
    }

    /**
     * Returns the shared handle to the native library, loading it on first use.
     *
     * @return the library, or {@code null} when it is unavailable
     */
    public static JpegliLibrary library() {
        if (loaded) {
            return library;
        }
        synchronized (LOCK) {
            if (loaded) {
                return library;
            }
            try {
                library = load();
            } catch (Throwable t) {
                failure = describe(t);
                log.log(Level.WARNING, "The jpegli JPEG codec is disabled: {0}. Its library is "
                        + "fetched on first use from a GitHub release for Windows, macOS and Linux "
                        + "on x64 and arm64, so either your platform is not one of those, the fetch "
                        + "failed, or downloads are turned off. You can also point -Djava.library.path "
                        + "at a directory that holds one. JPEG keeps working either way, through the "
                        + "JDK's own support.", failure);
            }
            loaded = true;
            return library;
        }
    }

    /**
     * Returns the shared handle to the native library or fails.
     *
     * @return the library, never {@code null}
     * @throws JpegException when the native library is unavailable or speaks another ABI
     */
    public static JpegliLibrary requireLibrary() throws JpegException {
        JpegliLibrary lib = library();
        if (lib == null) {
            throw new JpegException(failure == null ? "jpegli is not available" : failure);
        }
        return lib;
    }

    private static JpegliLibrary load() {
        Path managed = JpegliNativeLibrary.extract();
        String managedReason = JpegliNativeLibrary.reason();
        try {
            JpegliLibrary lib = managed == null ? loadSystem() : loadManaged(managed);

            // A library built against a different revision of the header would answer these calls
            // with arguments read from the wrong offsets, so the check has to happen before the
            // first one rather than being discovered as garbage pixels.
            String abi = lib.imagify_jpegli_abi_version();
            if (!JpegliLibrary.ABI_VERSION.equals(abi)) {
                throw new IllegalStateException("the jpegli library speaks ABI version " + abi
                        + " but this jar speaks " + JpegliLibrary.ABI_VERSION);
            }
            log.log(Level.DEBUG, "using jpegli {0}{1}", lib.imagify_jpegli_jpegli_version(),
                    managed == null ? " from system" : " from " + managed);
            return lib;
        } catch (Throwable t) {
            if (managedReason == null) {
                throw t;
            }
            // The managed library is what this jar would normally use, so when it could not be
            // obtained the failing fallback to a system library is explained by how the managed one
            // failed, which is where the actionable cause is.
            throw new IllegalStateException("the jpegli library could not be fetched (" + managedReason
                    + "), and no jpegli from the system could be loaded either", t);
        }
    }

    /**
     * Looks up a jpegli on the platform's own search path, which is what
     * {@code -Dimagify.jpeg.bundled=false} asks for.
     */
    private static JpegliLibrary loadSystem() {
        System.loadLibrary(JpegliLibrary.LIBRARY_NAME);
        return new JpegliLibrary(Linker.nativeLinker().defaultLookup());
    }

    private static JpegliLibrary loadManaged(Path managed) {
        System.load(managed.toString());
        Linker linker = Linker.nativeLinker();
        // The symbols are resolved once and the downcall handles that capture them are then held
        // for as long as this class lives, which outlives the thread that loaded the library and is
        // called from every one of them. A confined arena would tie the symbols to the loading
        // thread, so the library is opened against the global arena instead, which is what a
        // process wide codec wants anyway.
        SymbolLookup lookup = linker.defaultLookup().or(SymbolLookup.libraryLookup(managed, Arena.global()));
        return new JpegliLibrary(lookup);
    }

    private static String describe(Throwable t) {
        if (t instanceof UnsatisfiedLinkError || t instanceof NoClassDefFoundError) {
            return "cannot load the native library '" + JpegliLibrary.LIBRARY_NAME + "': " + t.getMessage();
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    // --------------------------------------------------------------------------------- header

    /**
     * Parses the frame header of a JPEG and reports what it says, without decoding a pixel.
     *
     * @param encoded the complete JPEG file
     * @return the image properties
     * @throws JpegException when the library is unavailable or the file cannot be parsed
     */
    public static JpegImageInfo readHeader(byte[] encoded) throws JpegException {
        JpegliLibrary lib = requireLibrary();
        if (encoded == null) {
            throw new JpegException("no input");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = input(encoded, arena);
            MemorySegment message = arena.allocate(JpegliLibrary.MESSAGE_LENGTH);
            MemorySegment width = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment height = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment components = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment progressive = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment horizontalFactor = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment verticalFactor = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment densityUnit = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment horizontalDensity = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment verticalDensity = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment precision = arena.allocate(ValueLayout.JAVA_INT);

            int status = lib.imagify_jpegli_read_header(data, encoded.length, width, height, components,
                    progressive, horizontalFactor, verticalFactor, densityUnit, horizontalDensity,
                    verticalDensity, precision, message, JpegliLibrary.MESSAGE_LENGTH);
            check(status, message, "imagify_jpegli_read_header()");
            return new JpegImageInfo(at(width), at(height), at(components), at(components) == 1,
                    at(progressive) != 0, at(horizontalFactor), at(verticalFactor), at(densityUnit),
                    at(horizontalDensity), at(verticalDensity), at(precision));
        }
    }

    // --------------------------------------------------------------------------------- decode

    /**
     * Decodes a JPEG using jpegli.
     *
     * <p>The pixels are always delivered as a {@link BufferedImage#TYPE_4BYTE_ABGR} image, whose
     * banks are the very {@code A, B, G, R} layout the shim fills in. The alpha channel is fully
     * opaque, because a JPEG cannot carry a real one; it is left opaque rather than composited over
     * a background of this library's choosing, which would quietly change every pixel the caller
     * goes on to compare.
     *
     * @param encoded the complete JPEG file
     * @return the decoded image
     * @throws JpegException when the library is unavailable or the file cannot be decoded
     */
    public static BufferedImage decode(byte[] encoded) throws JpegException {
        JpegliLibrary lib = requireLibrary();
        if (encoded == null) {
            throw new JpegException("no input");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = input(encoded, arena);
            MemorySegment message = arena.allocate(JpegliLibrary.MESSAGE_LENGTH);
            MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment outLength = arena.allocate(ValueLayout.JAVA_LONG);
            MemorySegment width = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment height = arena.allocate(ValueLayout.JAVA_INT);
            int status;
            try {
                status = lib.imagify_jpegli_decode(data, encoded.length, out, outLength, width, height,
                        message, JpegliLibrary.MESSAGE_LENGTH);
            } catch (RuntimeException e) {
                // Whatever the call was doing when it failed, the buffer may already be allocated.
                lib.imagify_jpegli_free(pointer(out));
                throw new JpegException("imagify_jpegli_decode() failed", e);
            }
            check(status, message, "imagify_jpegli_decode()");
            MemorySegment pixels = pointer(out);
            long length = outLength.get(ValueLayout.JAVA_LONG, 0);
            if (pixels.address() == 0 || length <= 0) {
                lib.imagify_jpegli_free(pixels);
                throw new JpegException("imagify_jpegli_decode() did not produce any pixel");
            }
            try {
                return AbgrPixels.toBufferedImage(pixels.reinterpret(length).toArray(ValueLayout.JAVA_BYTE),
                        at(width), at(height));
            } finally {
                lib.imagify_jpegli_free(pixels);
            }
        }
    }

    // --------------------------------------------------------------------------------- encode

    /**
     * Encodes an image as a JPEG at 4:2:0 with the standard entropy coder tables, which is what
     * {@link imagify.ImageFormat.Jpeg} names.
     *
     * @param source the image to encode; any {@link RenderedImage} is accepted
     * @param quality 1 (smallest) to 100 (most detail)
     * @return the complete JPEG file
     * @throws JpegException when the library is unavailable or the image cannot be encoded
     */
    public static byte[] encode(RenderedImage source, int quality) throws JpegException {
        return encode(source, quality, JpegliLibrary.IMAGIFY_JPEG_SAMP_420, false);
    }

    /**
     * Encodes an image as a JPEG.
     *
     * <p>A quality below 1 is raised to 1 and one above 100 is lowered to 100, which is what
     * {@code jpeg_set_quality} does with them. The ImageIO compression quality is a
     * {@code 0.0} to {@code 1.0} value, so a caller that asks for the smallest possible file asks
     * for 0, and quietly failing on it would turn a legal request into an exception.
     *
     * <p>The alpha channel is discarded, since a JPEG cannot store one. An image with transparency
     * is therefore written against whatever colour sits behind it rather than losing the picture; a
     * caller that needs the picture has to flatten it first.
     *
     * <p>An image already in {@link BufferedImage#TYPE_4BYTE_ABGR} hands its own backing array to
     * the shim, which reads it where it lies; every other layout is converted to that one first.
     *
     * @param source the image to encode; any {@link RenderedImage} is accepted
     * @param quality 1 (smallest) to 100 (most detail)
     * @param subsampling an {@code IMAGIFY_JPEG_SAMP_*} value, which is what
     *        {@link imagify.ImageFormat.Jpeg.Subsampling} carries
     * @param optimizeHuffmanTables whether the entropy coder tables are computed from this image
     *        rather than taken from the standard set
     * @return the complete JPEG file
     * @throws JpegException when the library is unavailable or the image cannot be encoded
     */
    public static byte[] encode(RenderedImage source, int quality, int subsampling,
            boolean optimizeHuffmanTables) throws JpegException {
        JpegliLibrary lib = requireLibrary();
        if (source == null) {
            throw new JpegException("no image to encode");
        }
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) {
            throw new JpegException("cannot encode a " + width + "x" + height + " image");
        }
        int clamped = Math.max(JpegliLibrary.IMAGIFY_JPEG_MIN_QUALITY,
                Math.min(JpegliLibrary.IMAGIFY_JPEG_MAX_QUALITY, quality));
        // The shim reads A, B, G, R bytes. An image that already keeps them that way is handed
        // over as itself; anything else is converted once, and that array is what crosses.
        byte[] abgr = source instanceof BufferedImage image ? AbgrPixels.abgrBytesOrNull(image) : null;
        if (abgr == null) {
            abgr = AbgrPixels.toAbgrBytes(source, 0, 0, width, height, 1, 1);
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment message = arena.allocate(JpegliLibrary.MESSAGE_LENGTH);
            MemorySegment encoded = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment encodedLength = arena.allocate(ValueLayout.JAVA_LONG);
            int status;
            try {
                status = lib.imagify_jpegli_encode(MemorySegment.ofArray(abgr), width, height, clamped,
                        subsampling, optimizeHuffmanTables ? 1 : 0, encoded, encodedLength, message,
                        JpegliLibrary.MESSAGE_LENGTH);
            } catch (RuntimeException e) {
                lib.imagify_jpegli_free(pointer(encoded));
                throw new JpegException("imagify_jpegli_encode() failed", e);
            }
            check(status, message, "imagify_jpegli_encode()");
            MemorySegment file = pointer(encoded);
            long length = encodedLength.get(ValueLayout.JAVA_LONG, 0);
            if (file.address() == 0 || length <= 0) {
                lib.imagify_jpegli_free(file);
                throw new JpegException("imagify_jpegli_encode() did not produce any output");
            }
            try {
                return file.reinterpret(length).toArray(ValueLayout.JAVA_BYTE);
            } finally {
                lib.imagify_jpegli_free(file);
            }
        }
    }

    // ------------------------------------------------------------------------------- internals

    /**
     * @param encoded the bytes to hand to the shim
     * @param arena where the copy lives
     * @return a native segment holding them, at least one byte long
     */
    private static MemorySegment input(byte[] encoded, Arena arena) {
        // A segment needs a positive size, and an empty input is a caller error rather than a
        // reason to allocate a zero length buffer the shim would then reject.
        MemorySegment data = arena.allocate(Math.max(encoded.length, 1));
        if (encoded.length > 0) {
            data.copyFrom(MemorySegment.ofArray(encoded));
        }
        return data;
    }

    private static int at(MemorySegment value) {
        return value.get(ValueLayout.JAVA_INT, 0);
    }

    private static MemorySegment pointer(MemorySegment reference) {
        return reference.get(ValueLayout.ADDRESS, 0);
    }

    private static void check(int status, MemorySegment message, String operation) throws JpegException {
        if (status == JpegliLibrary.IMAGIFY_JPEG_OK) {
            return;
        }
        String text = message.getString(0);
        throw new JpegException(operation + " failed: "
                + (text == null || text.isBlank() ? statusName(status) : text));
    }

    /**
     * @param status one of the {@code IMAGIFY_JPEG_*} values
     * @return a description for the ones the shim does not always put a message beside
     */
    public static String statusName(int status) {
        return switch (status) {
            case JpegliLibrary.IMAGIFY_JPEG_OK -> "no error";
            case JpegliLibrary.IMAGIFY_JPEG_ERR_ARGUMENT -> "a parameter was out of range";
            case JpegliLibrary.IMAGIFY_JPEG_ERR_CORRUPT -> "the input is not a JPEG, or is a damaged one";
            case JpegliLibrary.IMAGIFY_JPEG_ERR_MEMORY -> "out of memory";
            case JpegliLibrary.IMAGIFY_JPEG_ERR_INTERNAL -> "jpegli failed for no stated reason";
            default -> "status " + status;
        };
    }
}
