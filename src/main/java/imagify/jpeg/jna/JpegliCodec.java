/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.jpeg.jna;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import imagify.jpeg.JpegException;
import imagify.jpeg.JpegImageInfo;
import imagify.pixels.AbgrPixels;

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;

import static java.lang.System.getLogger;

/**
 * Entry point to the {@code jpegli} based JPEG codec.
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
 * <p>Prebuilt shared libraries for Windows, macOS and Linux, in both 64 bit flavours, ship inside
 * this jar and are unpacked on demand, so installing anything is not required. Should this jar hold
 * no library for the current platform, a jpegli based one found the usual way is used instead: point
 * the {@code jna.library.path} system property (or the platform specific {@code PATH} /
 * {@code LD_LIBRARY_PATH}) at the directory that holds it.
 *
 * <pre>
 * java -Djna.library.path=/usr/local/lib -cp ... YourApp
 * </pre>
 *
 * <p>Set {@code -Dimagify.jpeg.bundled=false} to ignore the bundled library and always look for one
 * installed on the system.
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
                log.log(Level.WARNING, "The jpegli JPEG codec is disabled: {0}. This jar ships a "
                        + "jpegli for Windows, macOS and Linux on x64 and arm64, so either your "
                        + "platform is not one of those or the bundled library could not be "
                        + "unpacked. You can also point -Djna.library.path at a directory that "
                        + "holds one. JPEG keeps working either way, through the JDK's own "
                        + "support.", failure);
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
        if (Native.SIZE_T_SIZE != 8) {
            throw new IllegalStateException("a 64 bit JVM is required, got " + System.getProperty("os.arch"));
        }
        Path bundled = JpegliNativeLibrary.extract();
        JpegliLibrary lib = bundled == null
                ? Native.load(JpegliLibrary.LIBRARY_NAME, JpegliLibrary.class)
                : Native.load(bundled.toString(), JpegliLibrary.class);

        // A library built against a different revision of the header would answer these calls with
        // arguments read from the wrong offsets, so the check has to happen before the first one
        // rather than being discovered as garbage pixels.
        String abi = lib.imagify_jpegli_abi_version();
        if (!JpegliLibrary.ABI_VERSION.equals(abi)) {
            throw new IllegalStateException("the jpegli library speaks ABI version " + abi
                    + " but this jar speaks " + JpegliLibrary.ABI_VERSION);
        }
        log.log(Level.DEBUG, "using jpegli {0}{1}", lib.imagify_jpegli_jpegli_version(),
                bundled == null ? "" : " from " + bundled);
        return lib;
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
        try (Memory input = input(encoded); Memory message = new Memory(JpegliLibrary.MESSAGE_LENGTH)) {
            IntByReference width = new IntByReference();
            IntByReference height = new IntByReference();
            IntByReference components = new IntByReference();
            IntByReference progressive = new IntByReference();
            IntByReference horizontalFactor = new IntByReference();
            IntByReference verticalFactor = new IntByReference();
            IntByReference densityUnit = new IntByReference();
            IntByReference horizontalDensity = new IntByReference();
            IntByReference verticalDensity = new IntByReference();
            IntByReference precision = new IntByReference();

            int status = lib.imagify_jpegli_read_header(input, encoded.length, width, height, components,
                    progressive, horizontalFactor, verticalFactor, densityUnit, horizontalDensity,
                    verticalDensity, precision, message, JpegliLibrary.MESSAGE_LENGTH);
            check(lib, status, message, "imagify_jpegli_read_header()");
            return new JpegImageInfo(width.getValue(), height.getValue(), components.getValue(),
                    components.getValue() == 1, progressive.getValue() != 0, horizontalFactor.getValue(),
                    verticalFactor.getValue(), densityUnit.getValue(), horizontalDensity.getValue(),
                    verticalDensity.getValue(), precision.getValue());
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
        IntByReference width = new IntByReference();
        IntByReference height = new IntByReference();
        try (Memory input = input(encoded); Memory message = new Memory(JpegliLibrary.MESSAGE_LENGTH)) {
            PointerByReference out = new PointerByReference();
            LongByReference outLength = new LongByReference();
            int status;
            try {
                status = lib.imagify_jpegli_decode(input, encoded.length, out, outLength, width, height,
                        message, JpegliLibrary.MESSAGE_LENGTH);
            } catch (RuntimeException e) {
                // Whatever the call was doing when Java threw, the buffer may already be allocated.
                free(lib, out.getValue());
                throw new JpegException("imagify_jpegli_decode() failed", e);
            }
            check(lib, status, message, "imagify_jpegli_decode()");
            Pointer pixels = out.getValue();
            int length = Math.toIntExact(outLength.getValue());
            if (pixels == null || length <= 0) {
                free(lib, pixels);
                throw new JpegException("imagify_jpegli_decode() did not produce any pixel");
            }
            try {
                return AbgrPixels.toBufferedImage(pixels.getByteArray(0, length),
                        width.getValue(), height.getValue());
            } finally {
                free(lib, pixels);
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
        byte[] abgr = AbgrPixels.toAbgrBytes(source, 0, 0, width, height, 1, 1);

        try (Memory pixels = new Memory(abgr.length); Memory message = new Memory(JpegliLibrary.MESSAGE_LENGTH)) {
            pixels.write(0, abgr, 0, abgr.length);
            PointerByReference encoded = new PointerByReference();
            LongByReference encodedLength = new LongByReference();
            int status;
            try {
                status = lib.imagify_jpegli_encode(pixels, width, height, clamped, subsampling,
                        optimizeHuffmanTables ? 1 : 0, encoded, encodedLength, message,
                        JpegliLibrary.MESSAGE_LENGTH);
            } catch (RuntimeException e) {
                free(lib, encoded.getValue());
                throw new JpegException("imagify_jpegli_encode() failed", e);
            }
            check(lib, status, message, "imagify_jpegli_encode()");
            Pointer file = encoded.getValue();
            int length = Math.toIntExact(encodedLength.getValue());
            if (file == null || length <= 0) {
                free(lib, file);
                throw new JpegException("imagify_jpegli_encode() did not produce any output");
            }
            try {
                return file.getByteArray(0, length);
            } finally {
                free(lib, file);
            }
        }
    }

    // ------------------------------------------------------------------------------- internals

    private static Memory input(byte[] encoded) {
        // A Memory needs a positive size, and an empty input is a caller error rather than a
        // reason to allocate a zero length buffer the shim would then reject.
        Memory memory = new Memory(Math.max(encoded.length, 1));
        if (encoded.length > 0) {
            memory.write(0, encoded, 0, encoded.length);
        }
        return memory;
    }

    private static void check(JpegliLibrary lib, int status, Memory message, String operation)
            throws JpegException {
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

    private static void free(JpegliLibrary lib, Pointer buffer) {
        if (buffer != null) {
            lib.imagify_jpegli_free(buffer);
        }
    }
}
