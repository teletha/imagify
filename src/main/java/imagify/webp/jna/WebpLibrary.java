/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.webp.jna;

import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.Arrays;
import java.util.List;

/**
 * JNA binding for the C ABI in {@code src/main/native/webp/imagify_webp.h}.
 *
 * <p>It is a binding for that header and not for libwebp itself. libwebp's C API is public and has
 * no error callbacks, so a binding could in principle name {@code WebPEncode} directly, and doing
 * that has two problems it avoids.
 *
 * <p><b>Structures.</b> Encoding a picture means filling in {@code struct WebPPicture}, which is
 * 256 bytes on a 64 bit platform and mostly not data: it is the padding libwebp reserves for
 * itself, a union of three sample pointers, a statistics block it may write through, and four
 * function pointers. A binding would have to name every one of those fields and get every offset
 * right, and a mistake in any of them is not a compile error but a wild pointer write while a
 * picture is being encoded. The animation API cannot be driven without one either, since it takes a
 * {@code WebPPicture} per frame. The layout is declared as "private fields, padding for later use"
 * in {@code encode.h}, so there is no upstream promise to hold a mapping of it to. None of it
 * crosses this boundary: the shim keeps it on the C side, and what crosses is plain scalars,
 * pointers and buffers.
 *
 * <p><b>Names.</b> libwebp's own names are exactly the ones a system {@code libwebp} also claims,
 * and this jar has no reason to interoperate with one. The bundled library is named
 * {@code imagifywebp} and exports only the {@code imagify_webp_*} entry points, so a second copy of
 * libwebp in the process cannot be the one that answers a call.
 *
 * <p>Every {@code byte[]} that crosses this boundary is tightly packed {@code A, B, G, R}: the byte
 * at offset 0 of a pixel is its alpha and the byte at offset 3 is its red. That is the order a
 * {@link java.awt.image.BufferedImage#TYPE_4BYTE_ABGR} raster keeps its banks in, because such a
 * raster declares band offsets of {@code 3, 2, 1, 0} even though
 * {@link java.awt.image.Raster#getDataElements} reports the very same sample as {@code R, G, B, A}.
 *
 * <p>The native library is <em>not</em> loaded by this interface; see {@link WebpCodec} for the
 * lazy, failure tolerant loader.
 *
 * @see <a href="https://chromium.googlesource.com/webm/libwebp">webmproject/libwebp</a>
 */
public interface WebpLibrary extends Library {

    /**
     * Name of the shared library as this jar builds it.
     *
     * <p>Deliberately not {@code webp}. libwebp builds {@code libwebp.so}, which is also the name
     * of every system {@code libwebp} on Linux, and two of those can end up mapped in one process.
     * This jar has no reason to interoperate with a system libwebp, so the bundled library is given
     * a name of its own.
     */
    String LIBRARY_NAME = "imagifywebp";

    /**
     * The version of the C ABI this binding was written against, as
     * {@code IMAGIFY_WEBP_ABI_VERSION} in {@code src/main/native/webp/imagify_webp.h} spells it.
     *
     * <p>It is checked once when the library is loaded and nothing else depends on it, because the
     * only way it could ever be wrong is a library built against a different revision of the
     * header, and that has to be caught before the first call rather than after it has written
     * through a pointer read from the wrong offset.
     */
    String ABI_VERSION = "1";

    // ---------------------------------------------------------------------------------- statuses

    /** The operation completed. */
    int IMAGIFY_WEBP_OK = 0;

    /** A parameter was out of range, before any work was done. */
    int IMAGIFY_WEBP_ERR_ARGUMENT = 1;

    /** The input is not a WebP file, or is a damaged one. */
    int IMAGIFY_WEBP_ERR_CORRUPT = 2;

    /** An allocation failed. */
    int IMAGIFY_WEBP_ERR_MEMORY = 3;

    /** libwebp will not do this, for instance a dimension too large. */
    int IMAGIFY_WEBP_ERR_UNSUPPORTED = 4;

    /** libwebp failed in a way its own error text does not explain. */
    int IMAGIFY_WEBP_ERR_INTERNAL = 5;

    // ---------------------------------------------------------------------------------- formats

    /** {@code libwebp} reports a lossy {@code VP8} bitstream with this format code. */
    int FORMAT_VP8 = 1;

    /** {@code libwebp} reports a lossless {@code VP8L} bitstream with this format code. */
    int FORMAT_VP8L = 2;

    /** {@code libwebp} reports the {@code VP8X} container, which is what an animation uses. */
    int FORMAT_VP8X = 0;

    // ---------------------------------------------------------------------------------- ranges

    /** The lowest quality the encoder accepts. */
    int MIN_QUALITY = 0;

    /** The highest quality the encoder accepts. */
    int MAX_QUALITY = 100;

    /** The quickest encoding effort the encoder accepts. */
    int MIN_METHOD = 0;

    /** The most thorough encoding effort the encoder accepts. */
    int MAX_METHOD = 6;

    /**
     * Quality used when the caller does not ask for one: {@code 75}, the {@code libwebp} default.
     *
     * <p>The number is the one {@link imagify.ImageFormat.Webp} carries as its 0.0 to 1.0
     * {@code defaultQuality}, restated on the encoder's own 0 to 100 scale. That is the same thing
     * {@link imagify.avif.jna.AvifLibrary#DEFAULT_QUALITY} and
     * {@link imagify.jpeg.jna.JpegliLibrary#DEFAULT_QUALITY} do: a codec cannot read a package
     * private member of the format that describes it, so each one says what it does by default and
     * the format's own value follows it. Restating it rather than copying it into two call sites is
     * what keeps the two from drifting apart.
     */
    int DEFAULT_QUALITY = 75;

    /**
     * Size of the buffer every entry point writes its failure description into.
     *
     * <p>Matches {@code IMAGIFY_WEBP_MESSAGE_LENGTH} in {@code imagify_webp.h}.
     */
    int MESSAGE_LENGTH = 256;

    // --------------------------------------------------------------------------------- structures

    /**
     * What the container headers of a WebP file say, without a pixel being decoded.
     *
     * <p>This is {@code imagify_webp_features} in {@code imagify_webp.h}. It is filled in by
     * {@link #imagify_webp_read_features} and {@link #imagify_webp_decode}.
     */
    class WebpFeatures extends Structure {

        /** Canvas width in pixels. */
        public int width;

        /** Canvas height in pixels. */
        public int height;

        /** 1 when the bitstream carries a non premultiplied alpha channel. */
        public int hasAlpha;

        /** 1 when the file holds an animation rather than a single still frame. */
        public int hasAnimation;

        /** One of the {@code FORMAT_*} values. */
        public int format;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("width", "height", "hasAlpha", "hasAnimation", "format");
        }
    }

    /**
     * What the animation control chunk of a WebP file says.
     *
     * <p>This is {@code imagify_webp_animation} in {@code imagify_webp.h}. It is filled in by
     * {@link #imagify_webp_read_animation} and {@link #imagify_webp_decode_animation}.
     */
    class WebpAnimation extends Structure {

        /** Number of frames. */
        public int frameCount;

        /** How often the animation repeats, 0 meaning forever. */
        public int loopCount;

        /** Canvas width in pixels, which is the size of every frame. */
        public int width;

        /** Canvas height in pixels, which is the size of every frame. */
        public int height;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("frameCount", "loopCount", "width", "height");
        }
    }

    // --------------------------------------------------------------------------------- functions

    /**
     * @return the version of the C ABI, as a decimal string, for example {@code "1"}
     */
    String imagify_webp_abi_version();

    /**
     * @return the libwebp version the library was built from, for example {@code "1.6.0"}
     */
    String imagify_webp_webp_version();

    /**
     * Parses the container headers of a WebP file and reports what they say, without decoding a
     * pixel.
     *
     * @param data the encoded WebP file
     * @param length the number of bytes at {@code data}
     * @param features receives what the headers say
     * @param message receives the description of a failure
     * @return {@link #IMAGIFY_WEBP_OK}, or a status saying what went wrong
     */
    int imagify_webp_read_features(Pointer data, long length, WebpFeatures features, Pointer message, long messageCapacity);

    /**
     * Decodes a still image into a buffer of tightly packed {@code A, B, G, R} bytes.
     *
     * <p>An animation is refused with {@link #IMAGIFY_WEBP_ERR_UNSUPPORTED}: a single animated WebP
     * has no single image to return. The buffer that comes back is owned by the caller and must be
     * handed to {@link #imagify_webp_free}.
     *
     * @param data the encoded WebP file
     * @param length the number of bytes at {@code data}
     * @param out receives the new buffer, or a null pointer on failure
     * @param outLength receives the buffer size in bytes, which is {@code width * height * 4}
     * @param features receives what the headers say, including the size the pixels were decoded at
     * @param message receives the description of a failure
     * @return {@link #IMAGIFY_WEBP_OK}, or a status saying what went wrong
     */
    int imagify_webp_decode(Pointer data, long length, PointerByReference out, LongByReference outLength,
            WebpFeatures features, Pointer message, long messageCapacity);

    /**
     * Encodes {@code width * height * 4} bytes of {@code A, B, G, R} into a complete WebP file.
     *
     * <p>The buffer that comes back is owned by the caller and must be handed to
     * {@link #imagify_webp_free}. Quality is ignored when {@code lossless} is nonzero.
     *
     * @param pixels the image, tightly packed {@code A, B, G, R}
     * @param width the image width, at least 1
     * @param height the image height, at least 1
     * @param quality {@link #MIN_QUALITY} to {@link #MAX_QUALITY}, ignored when {@code lossless}
     * @param lossless nonzero to store the pixels without loss
     * @param method {@link #MIN_METHOD} to {@link #MAX_METHOD}, how hard the encoder tries
     * @param encoded receives the new buffer, or a null pointer on failure
     * @param encodedLength receives the buffer size in bytes
     * @param message receives the description of a failure
     * @return {@link #IMAGIFY_WEBP_OK}, or a status saying what went wrong
     */
    int imagify_webp_encode(Pointer pixels, int width, int height, int quality, int lossless, int method,
            PointerByReference encoded, LongByReference encodedLength, Pointer message, long messageCapacity);

    /**
     * Reads the frame count, the loop count and the per frame delay of an animation without
     * decoding a pixel.
     *
     * <p>The buffer of frame count integers that comes back is owned by the caller and must be
     * handed to {@link #imagify_webp_free}.
     *
     * @param data the encoded animated WebP file
     * @param length the number of bytes at {@code data}
     * @param animation receives the canvas size, the frame count and the loop count
     * @param delays receives the frame count delays in milliseconds, or a null pointer on failure
     * @param message receives the description of a failure
     * @return {@link #IMAGIFY_WEBP_OK}, or a status saying what went wrong
     */
    int imagify_webp_read_animation(Pointer data, long length, WebpAnimation animation,
            PointerByReference delays, Pointer message, long messageCapacity);

    /**
     * Decodes every frame of an animation.
     *
     * <p>The frames are composited onto the canvas, so each of them is {@code width * height * 4}
     * bytes of {@code A, B, G, R} in the one buffer at {@code frames}. The buffers that come back
     * are owned by the caller and must be handed to {@link #imagify_webp_free}.
     *
     * @param data the encoded animated WebP file
     * @param length the number of bytes at {@code data}
     * @param animation receives the canvas size, the frame count and the loop count
     * @param frames receives the buffer of {@code frameCount * width * height * 4} bytes, or a null
     *            pointer on failure
     * @param delays receives the frame count delays in milliseconds, or a null pointer on failure
     * @param message receives the description of a failure
     * @return {@link #IMAGIFY_WEBP_OK}, or a status saying what went wrong
     */
    int imagify_webp_decode_animation(Pointer data, long length, WebpAnimation animation,
            PointerByReference frames, PointerByReference delays, Pointer message, long messageCapacity);

    /**
     * Encodes a sequence of frames as an animated WebP file.
     *
     * <p>The frames are given as one buffer, each of them {@code width * height * 4} bytes of
     * {@code A, B, G, R} in presentation order. Quality is ignored when {@code lossless} is
     * nonzero, and a loop count of 0 means the animation repeats forever. The buffer that comes
     * back is owned by the caller and must be handed to {@link #imagify_webp_free}.
     *
     * @param frames {@code frameCount * width * height * 4} bytes, tightly packed {@code A, B, G, R}
     * @param frameCount the number of frames, at least 1
     * @param width the canvas width, at least 1
     * @param height the canvas height, at least 1
     * @param delays {@code frameCount} integers, how long each frame is shown in milliseconds
     * @param quality {@link #MIN_QUALITY} to {@link #MAX_QUALITY}, ignored when {@code lossless}
     * @param lossless nonzero to store the pixels without loss
     * @param loopCount how often the animation repeats, 0 meaning forever
     * @param method {@link #MIN_METHOD} to {@link #MAX_METHOD}, how hard the encoder tries
     * @param encoded receives the new buffer, or a null pointer on failure
     * @param encodedLength receives the buffer size in bytes
     * @param message receives the description of a failure
     * @return {@link #IMAGIFY_WEBP_OK}, or a status saying what went wrong
     */
    int imagify_webp_encode_animation(Pointer frames, int frameCount, int width, int height, Pointer delays,
            int quality, int lossless, int loopCount, int method, PointerByReference encoded,
            LongByReference encodedLength, Pointer message, long messageCapacity);

    /**
     * Frees a buffer handed out by any of the entry points above that allocates one.
     * Accepts a null pointer.
     *
     * @param buffer the buffer to free
     */
    void imagify_webp_free(Pointer buffer);
}