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

import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * JNA binding for the C ABI in {@code src/main/native/imagify_jpegli.h}.
 *
 * <p>It is a binding for that header and not for jpegli itself. jpegli implements the whole of
 * libjpeg's public interface, so a binding could in principle name {@code jpeg_start_compress} and
 * friends directly, and doing that has two problems it avoids:
 *
 * <p><b>Errors.</b> libjpeg reports a fatal error by calling {@code cinfo->err->error_exit}, which
 * must not return, and the only portable way of getting control back is {@code setjmp} /
 * {@code longjmp}. JNA cannot take part in that: {@code CallbackReference.DefaultCallbackProxy}
 * catches every {@code Throwable} a {@link com.sun.jna.Callback} throws and passes it to a
 * {@code CallbackExceptionHandler}, and documents that the method must not throw. A callback used as
 * {@code error_exit} would therefore return normally and libjpeg would carry on after an error it
 * believes to be fatal, so a damaged file could be read into a structure field that was never
 * written. The unwinding happens in the shim instead, where it belongs, and every call here answers
 * a status code rather than throwing.
 *
 * <p><b>Structures.</b> The other thing a direct binding would have to do is describe {@code struct
 * jpeg_compress_struct} and {@code struct jpeg_decompress_struct} field by field. They are several
 * hundred fields of nested substructures, the two are not the same size, and their layout follows
 * the libjpeg revision rather than anything this library controls. Nothing above this interface
 * mentions them.
 *
 * <p>Every {@code byte[]} that crosses this boundary is tightly packed {@code A, B, G, R}: the byte
 * at offset 0 of a pixel is its alpha and the byte at offset 3 is its red. That is the order a
 * {@link java.awt.image.BufferedImage#TYPE_4BYTE_ABGR} raster keeps its banks in, because such a
 * raster declares band offsets of {@code 3, 2, 1, 0} even though
 * {@link java.awt.image.Raster#getDataElements} reports the very same sample as {@code R, G, B, A}.
 *
 * <p>The native library is <em>not</em> loaded by this interface; see {@link JpegliCodec} for the
 * lazy, failure tolerant loader.
 *
 * @see <a href="https://github.com/google/jpegli">google/jpegli</a>
 */
public interface JpegliLibrary extends Library {

    /**
     * Name of the shared library as this jar builds it.
     *
     * <p>Deliberately not {@code jpeg}. jpegli's own libjpeg62 compatible library is named
     * {@code libjpeg.so.62}, which is also the name of every system libjpeg on Linux, and two of
     * those can end up mapped in one process. This jar has no reason to interoperate with a system
     * libjpeg, so the bundled library is given a name of its own.
     */
    String LIBRARY_NAME = "jpegli";

    /**
     * The version of the C ABI this binding was written against, as {@code IMAGIFY_JPEGLI_ABI_VERSION}
     * in {@code src/main/native/imagify_jpegli.h} spells it.
     *
     * <p>It is checked once when the library is loaded and nothing else depends on it, because the
     * only way it could ever be wrong is a library built against a different revision of the header,
     * and that has to be caught before the first call rather than after it has read a size from the
     * wrong offset.
     */
    String ABI_VERSION = "1";

    // ---------------------------------------------------------------------------------- statuses

    /** The operation completed. */
    int IMAGIFY_JPEG_OK = 0;
    /** A parameter was out of range. */
    int IMAGIFY_JPEG_ERR_ARGUMENT = 1;
    /** The input is not a JPEG, or is a damaged one. */
    int IMAGIFY_JPEG_ERR_CORRUPT = 2;
    /** An allocation failed. */
    int IMAGIFY_JPEG_ERR_MEMORY = 3;
    /** jpegli failed in a way its own error text does not explain. */
    int IMAGIFY_JPEG_ERR_INTERNAL = 4;

    // ----------------------------------------------------------------------------- subsampling

    /** No subsampling at all, the largest file. */
    int IMAGIFY_JPEG_SAMP_444 = 0;
    /** The colour channels half the width. */
    int IMAGIFY_JPEG_SAMP_422 = 1;
    /** The colour channels half in both directions, the smallest file. */
    int IMAGIFY_JPEG_SAMP_420 = 2;

    /** The lowest quality the encoder accepts. */
    int IMAGIFY_JPEG_MIN_QUALITY = 1;

    /** The highest quality the encoder accepts. */
    int IMAGIFY_JPEG_MAX_QUALITY = 100;

    /**
     * Quality the {@link JpegImageWriter} and {@link JpegWriteParam} use when a caller does not
     * ask for one.
     *
     * <p>The number is the one {@link imagify.ImageFormat.Jpeg} carries as its 0.0 to 1.0
     * {@code defaultQuality}, restated on the encoder's own 1 to 100 scale. That is the same thing
     * {@link imagify.avif.jna.AvifLibrary#DEFAULT_QUALITY} and
     * {@link imagify.webp.WebpCodec#DEFAULT_QUALITY} do: a codec cannot read a package private
     * member of the format that describes it, so each one says what it does by default and the
     * format's own value follows it. Restating it rather than copying it into two call sites is
     * what keeps the two from drifting apart.
     */
    int DEFAULT_QUALITY = 85;

    /**
     * Size of the buffer every entry point writes its failure description into.
     *
     * <p>Matches {@code JMSG_LENGTH_MAX} in {@code jpeglib.h}, which is what bounds the text
     * libjpeg has formatted before it reports the error.
     */
    int MESSAGE_LENGTH = 200;

    // --------------------------------------------------------------------------------- functions

    /**
     * @return the version of the C ABI, as a decimal string, for example {@code "1"}
     */
    String imagify_jpegli_abi_version();

    /**
     * @return the jpegli version the library was built from, for example {@code "0.12.0"}
     */
    String imagify_jpegli_jpegli_version();

    /**
     * Parses the frame header and reports the geometry, without decoding any pixel.
     *
     * <p>The last seven out parameters may all be null, which is how a caller that only wants the
     * size of a file says so. The first three may not.
     *
     * @param data the encoded JPEG
     * @param length the number of bytes at {@code data}
     * @param width receives the image width
     * @param height receives the image height
     * @param components receives the channel count, which is 1 for a greyscale file
     * @param progressive receives 1 for a progressive file and 0 for a sequential one, or null
     * @param horizontalFactor receives the luma sampling factor along the width, or null
     * @param verticalFactor receives the luma sampling factor along the height, or null
     * @param densityUnit receives 0 for an aspect ratio, 1 for dots per inch or 2 for dots per
     *        centimetre, or null
     * @param horizontalDensity receives the horizontal density, or null
     * @param verticalDensity receives the vertical density, or null
     * @param precision receives the bits per channel, which is 8 for anything jpegli reads, or null
     * @param message receives the description of a failure
     * @return {@link #IMAGIFY_JPEG_OK}, or a status saying what went wrong
     */
    int imagify_jpegli_read_header(Pointer data, long length, IntByReference width,
            IntByReference height, IntByReference components, IntByReference progressive,
            IntByReference horizontalFactor, IntByReference verticalFactor, IntByReference densityUnit,
            IntByReference horizontalDensity, IntByReference verticalDensity, IntByReference precision,
            Pointer message, long messageCapacity);

    /**
     * Decodes into a buffer the caller owns, and must hand back to {@link #imagify_jpegli_free}.
     *
     * @param data the encoded JPEG
     * @param length the number of bytes at {@code data}
     * @param out receives the new buffer, or a null pointer on failure
     * @param outLength receives the buffer size in bytes, which is {@code width * height * 4}
     * @param width receives the image width
     * @param height receives the image height
     * @param message receives the description of a failure
     * @return {@link #IMAGIFY_JPEG_OK}, or a status saying what went wrong
     */
    int imagify_jpegli_decode(Pointer data, long length, PointerByReference out,
            LongByReference outLength, IntByReference width, IntByReference height, Pointer message,
            long messageCapacity);

    /**
     * Encodes {@code width * height * 4} bytes of {@code A, B, G, R} into a baseline JPEG.
     *
     * <p>The buffer that comes back is owned by the caller and must be handed to
     * {@link #imagify_jpegli_free}. The alpha byte is discarded, since a JPEG cannot store one.
     *
     * @param pixels the image, tightly packed {@code A, B, G, R}
     * @param width the image width, at least 1
     * @param height the image height, at least 1
     * @param quality {@link #IMAGIFY_JPEG_MIN_QUALITY} to {@link #IMAGIFY_JPEG_MAX_QUALITY}
     * @param subsampling an {@code IMAGIFY_JPEG_SAMP_*} value
     * @param optimizeCoding nonzero to compute the entropy coder tables from the image
     * @param encoded receives the new buffer, or a null pointer on failure
     * @param encodedLength receives the buffer size in bytes
     * @param message receives the description of a failure
     * @return {@link #IMAGIFY_JPEG_OK}, or a status saying what went wrong
     */
    int imagify_jpegli_encode(Pointer pixels, int width, int height, int quality, int subsampling,
            int optimizeCoding, PointerByReference encoded, LongByReference encodedLength,
            Pointer message, long messageCapacity);

    /**
     * Frees a buffer handed out by {@link #imagify_jpegli_decode} or {@link #imagify_jpegli_encode}.
     * Accepts a null pointer.
     *
     * @param buffer the buffer to free
     */
    void imagify_jpegli_free(Pointer buffer);
}
