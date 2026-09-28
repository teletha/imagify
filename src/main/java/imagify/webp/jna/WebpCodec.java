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

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import imagify.pixels.AbgrPixels;
import imagify.webp.WebpException;
import imagify.webp.WebpImageInfo;

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static java.lang.System.getLogger;

/**
 * Entry point to the {@code libwebp} based WebP codec.
 *
 * <p>This is the encoder and decoder behind both the ImageIO service providers in
 * {@code imagify.webp} and the direct calls {@link imagify.ImageWriter} and
 * {@link imagify.ImageReader} make, so a WebP written through any of them comes out of the same
 * encoder with the same settings.
 *
 * <p>The native library is loaded lazily, on first use, and failing to load it is never fatal: the
 * ImageIO service providers of this library then stay inert instead of breaking {@code ImageIO} for
 * every other format, and the facade falls back to the JDK's own WebP support, which is the
 * {@link javax.imageio} providers that ship with a JDK 13 or newer. Use {@link #isAvailable()} to
 * find out which of the two a given call will use.
 *
 * <p>Prebuilt shared libraries for Windows, macOS and Linux, in both 64 bit flavours, ship inside
 * this jar and are unpacked on demand, so installing anything is not required. Should this jar hold
 * no library for the current platform, a {@code libwebp} based one found the usual way is used
 * instead: point the {@code jna.library.path} system property (or the platform specific
 * {@code PATH} / {@code LD_LIBRARY_PATH}) at the directory that holds it.
 *
 * <pre>
 * java -Djna.library.path=/usr/local/lib -cp ... YourApp
 * </pre>
 *
 * <p>Set {@code -Dimagify.webp.bundled=false} to ignore the bundled library and always look for one
 * installed on the system.
 *
 * <p>Images are always converted to the tightly packed {@code A, B, G, R} layout the native library
 * imports and exports, through the shared {@link AbgrPixels}, so an unassociated alpha channel
 * survives without being premultiplied against an opaque background and a fully transparent pixel
 * keeps the colour underneath it. Decoded stills are handed out as the standard
 * {@link BufferedImage#TYPE_INT_ARGB} or {@link BufferedImage#TYPE_INT_RGB} types, with the alpha
 * type chosen by whether the file carries an alpha channel.
 *
 * @see <a href="https://chromium.googlesource.com/webm/libwebp">webmproject/libwebp</a>
 */
public final class WebpCodec {

    private static final Logger log = getLogger(WebpCodec.class.getName());

    private static final Object LOCK = new Object();

    private static volatile boolean loaded;
    private static volatile WebpLibrary library;
    private static volatile String failure;

    /**
     * {@code libwebp} reports a lossy {@code VP8} bitstream with this format code.
     *
     * @see WebpLibrary#FORMAT_VP8
     */
    public static final int FORMAT_VP8 = WebpLibrary.FORMAT_VP8;

    /**
     * {@code libwebp} reports a lossless {@code VP8L} bitstream with this format code.
     *
     * @see WebpLibrary#FORMAT_VP8L
     */
    public static final int FORMAT_VP8L = WebpLibrary.FORMAT_VP8L;

    /**
     * {@code libwebp} reports the {@code VP8X} container, which is what an animation uses.
     *
     * @see WebpLibrary#FORMAT_VP8X
     */
    public static final int FORMAT_VP8X = WebpLibrary.FORMAT_VP8X;

    /**
     * Quality used when the caller does not ask for one: {@code 75}, the {@code libwebp} default.
     *
     * @see WebpLibrary#DEFAULT_QUALITY
     */
    public static final int DEFAULT_QUALITY = WebpLibrary.DEFAULT_QUALITY;

    private static final int HEADER_LENGTH = 30;

    /** Where the first chunk of a {@code RIFF} container starts, after {@code RIFF}, size, {@code WEBP}. */
    private static final int FIRST_CHUNK = 12;

    /** A chunk header is its four character name and its size, which is what is stepped over. */
    private static final int CHUNK_HEADER_LENGTH = 8;

    /**
     * Where the bitstream of an animation frame starts: the frame header of the {@code ANMF} chunk
     * names the rectangle the frame covers and how long it is shown for, and the bitstream follows.
     */
    private static final int ANMF_FRAME_HEADER_LENGTH = CHUNK_HEADER_LENGTH + 16;

    private static final String CHUNK_VP8L = "VP8L";

    private static final String CHUNK_VP8 = "VP8 ";

    private static final String CHUNK_ANMF = "ANMF";

    /**
     * How far into a file the bitstream that decides the flavour can sit. An animation that
     * carries nothing but its frames puts the first one at byte 68, so this is generous.
     */
    private static final int LOSSLESS_HEADER_LENGTH = 128;

    /**
     * How hard an encode tries when the caller does not say. Both libwebp's own default and the
     * gif2webp tool's, which is what animation encoding has always used in this library.
     */
    private static final int DEFAULT_COMPRESSION_METHOD = 4;

    private WebpCodec() {
        // utility class
    }

    /**
     * @param format a format code reported by {@code libwebp}
     * @return the name of the bitstream format
     */
    public static String formatName(int format) {
        return switch (format) {
            case FORMAT_VP8 -> "VP8";
            case FORMAT_VP8L -> "VP8L";
            case FORMAT_VP8X -> "VP8X";
            default -> "unknown(" + format + ")";
        };
    }

    // ---------------------------------------------------------------------------- availability

    /**
     * Returns whether WebP encoding and decoding through {@code libwebp} can be used in this JVM.
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
     * Returns the version of the loaded {@code libwebp}, for example {@code "1.6.0"}.
     *
     * @return the reported version, or {@code null} when the library is unavailable
     */
    public static String getVersion() {
        WebpLibrary lib = library();
        return lib == null ? null : lib.imagify_webp_webp_version();
    }

    /**
     * Returns the shared handle to the native library, loading it on first use.
     *
     * @return the library, or {@code null} when it is unavailable
     */
    public static WebpLibrary library() {
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
                log.log(Level.WARNING, "The WebP codec is disabled: {0}. This jar ships a WebP "
                        + "codec for Windows, macOS and Linux on x64 and arm64, so either your "
                        + "platform is not one of those or the bundled library could not be "
                        + "unpacked. You can also point -Djna.library.path at a directory that "
                        + "holds one. ImageIO falls back to the JDK's own WebP support either "
                        + "way.", failure);
            }
            loaded = true;
            return library;
        }
    }

    /**
     * Returns the shared handle to the native library or fails.
     *
     * @return the library, never {@code null}
     * @throws WebpException when the native library is unavailable or speaks another ABI
     */
    public static WebpLibrary requireLibrary() throws WebpException {
        WebpLibrary lib = library();
        if (lib == null) {
            throw new WebpException(failure == null ? "libwebp is not available" : failure);
        }
        return lib;
    }

    private static WebpLibrary load() {
        if (Native.SIZE_T_SIZE != 8) {
            throw new IllegalStateException("a 64 bit JVM is required, got " + System.getProperty("os.arch"));
        }
        Path bundled = WebpNativeLibrary.extract();
        WebpLibrary lib = bundled == null
                ? Native.load(WebpLibrary.LIBRARY_NAME, WebpLibrary.class)
                : Native.load(bundled.toString(), WebpLibrary.class);

        // A library built against a different revision of the header would answer these calls with
        // arguments read from the wrong offsets, so the check has to happen before the first one
        // rather than being discovered as garbage pixels.
        String abi = lib.imagify_webp_abi_version();
        if (!WebpLibrary.ABI_VERSION.equals(abi)) {
            throw new IllegalStateException("the WebP library speaks ABI version " + abi
                    + " but this jar speaks " + WebpLibrary.ABI_VERSION);
        }
        log.log(Level.DEBUG, "using libwebp {0}{1}", lib.imagify_webp_webp_version(),
                bundled == null ? "" : " from " + bundled);
        return lib;
    }

    private static String describe(Throwable t) {
        if (t instanceof UnsatisfiedLinkError || t instanceof NoClassDefFoundError) {
            return "cannot load the native library '" + WebpLibrary.LIBRARY_NAME + "': " + t.getMessage();
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    // -------------------------------------------------------------------------------- inspection

    /**
     * Reads the container headers of a WebP file without decoding the pixels.
     *
     * <p>For an animation the frame count and the loop count come from the animation decoder,
     * because the still image headers do not carry them. That makes this call as expensive as
     * decoding an animation, so the {@code ImageIO} reader calls it once and caches the answer.
     *
     * @param encoded a complete WebP file
     * @return the properties reported by the headers
     * @throws WebpException when the input is not a WebP file or the headers are unreadable
     */
    public static WebpImageInfo readHeader(byte[] encoded) throws WebpException {
        WebpLibrary lib = requireLibrary();
        Objects.requireNonNull(encoded, "no data to inspect");
        try (Memory input = input(encoded); Memory message = new Memory(WebpLibrary.MESSAGE_LENGTH)) {
            WebpLibrary.WebpFeatures features = new WebpLibrary.WebpFeatures();
            check(lib, lib.imagify_webp_read_features(input, encoded.length, features, message,
                    WebpLibrary.MESSAGE_LENGTH), message, "imagify_webp_read_features()");
            if (features.hasAnimation == 0) {
                return new WebpImageInfo(features.width, features.height, features.hasAlpha != 0,
                        false, features.format, 1, 0);
            }
            WebpLibrary.WebpAnimation animation = new WebpLibrary.WebpAnimation();
            PointerByReference delays = new PointerByReference();
            check(lib, lib.imagify_webp_read_animation(input, encoded.length, animation, delays,
                    message, WebpLibrary.MESSAGE_LENGTH), message, "imagify_webp_read_animation()");
            free(lib, delays.getValue());
            return new WebpImageInfo(features.width, features.height, features.hasAlpha != 0,
                    true, features.format, animation.frameCount, animation.loopCount);
        }
    }

    /**
     * @param data the leading bytes of a file
     * @return whether they start a {@code RIFF} container whose form type is {@code WEBP}
     */
    public static boolean isWebP(byte[] data) {
        // "RIFF" + 4 byte size + "WEBP" is the smallest header that can identify a WebP file.
        if (data == null || data.length < 12) {
            return false;
        }
        return matches(data, 0, "RIFF") && matches(data, 8, "WEBP");
    }

    private static boolean matches(byte[] data, int offset, String ascii) {
        for (int i = 0; i < ascii.length(); i++) {
            if (data[offset + i] != (byte) ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return how many leading bytes {@link #isWebP(byte[])} needs to decide
     */
    public static int headerLength() {
        return HEADER_LENGTH;
    }

    /**
     * Reports which of the two bitstreams a WebP file stores its pixels in, from the container
     * alone.
     *
     * <p>A still image names its bitstream in the first chunk of the file, {@code VP8L} when it is
     * lossless and {@code VP8 } when it is lossy. An animation is an extended file whose frames
     * carry a bitstream of their own, so the first frame is the one that is looked at; a file that
     * mixes the two is reported as its first frame has it. A container that says neither before the
     * data runs out is reported as lossy, which is what {@link #FORMAT_VP8L} is not.
     *
     * <p>Unlike {@link #readHeader(byte[])} this needs no native library and decodes nothing, which
     * is what makes it usable while only a format is being detected, before any pixel has been
     * looked at. Only the first {@link #losslessHeaderLength()} bytes are read, so a caller that
     * has a shorter prefix than that is answered from what it has.
     *
     * @param data a complete WebP file, or as many of its leading bytes as are to hand
     * @return whether the image inside is stored without loss
     */
    public static boolean isLossless(byte[] data) {
        if (!isWebP(data)) {
            return false;
        }
        return lossless(data, FIRST_CHUNK);
    }

    /**
     * Walks the chunks of a {@code RIFF} container until it meets the one that holds the image.
     *
     * <p>The chunks that are not the image, which is nearly all of them, are stepped over by their
     * declared size: {@code VP8X} and {@code ANIM} for an animation, {@code ALPH} for an alpha
     * plane, and whatever metadata chunks a file chooses to carry.
     *
     * @param offset where the next chunk header starts
     * @return whether the image bitstream that was found is the lossless one
     */
    private static boolean lossless(byte[] data, int offset) {
        while (offset + CHUNK_HEADER_LENGTH <= data.length) {
            if (matches(data, offset, CHUNK_VP8L)) {
                return true;
            }
            if (matches(data, offset, CHUNK_VP8)) {
                return false;
            }
            if (matches(data, offset, CHUNK_ANMF)) {
                // A frame of an animation: its own bitstream follows the frame header, which is the
                // rectangle the frame covers plus how long it is shown for.
                return lossless(data, offset + ANMF_FRAME_HEADER_LENGTH);
            }
            int size = chunkSize(data, offset);
            if (size < 0) {
                // The size field does not fit in a signed int, so the walk cannot go on from here.
                return false;
            }
            // Chunks are padded to an even number of bytes, and the arithmetic stays in a long so
            // that a size that runs past the data ends the walk instead of wrapping around.
            long next = (long) offset + CHUNK_HEADER_LENGTH + size + (size & 1);
            if (next >= data.length) {
                return false;
            }
            offset = (int) next;
        }
        return false;
    }

    private static int chunkSize(byte[] data, int offset) {
        return (data[offset + 4] & 0xFF) | (data[offset + 5] & 0xFF) << 8
                | (data[offset + 6] & 0xFF) << 16 | (data[offset + 7] & 0xFF) << 24;
    }

    /**
     * @return how many leading bytes {@link #isLossless(byte[])} reads at most, which is how many a
     *         caller should hand it for the answer to be the one the file gives
     */
    public static int losslessHeaderLength() {
        return LOSSLESS_HEADER_LENGTH;
    }

    // ----------------------------------------------------------------------------------- encoding

    /**
     * Encodes a single image as a lossless or lossy WebP file.
     *
     * @param source the image to encode; any {@link RenderedImage} is accepted
     * @param quality 0 (smallest) to 100, ignored when {@code lossless} is {@code true}
     * @param lossless whether to store the pixels without loss
     * @return the complete WebP file
     * @throws WebpException when the library is unavailable or the image cannot be encoded
     */
    public static byte[] encode(RenderedImage source, int quality, boolean lossless) throws WebpException {
        WebpLibrary lib = requireLibrary();
        if (source == null) {
            throw new WebpException("no image to encode");
        }
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) {
            throw new WebpException("cannot encode a " + width + "x" + height + " image");
        }
        byte[] abgr = AbgrPixels.toAbgrBytes(source, 0, 0, width, height, 1, 1);
        try (Memory pixels = new Memory(abgr.length); Memory message = new Memory(WebpLibrary.MESSAGE_LENGTH)) {
            pixels.write(0, abgr, 0, abgr.length);
            PointerByReference encoded = new PointerByReference();
            LongByReference encodedLength = new LongByReference();
            int status;
            try {
                status = lib.imagify_webp_encode(pixels, width, height, clamp(quality),
                        lossless ? 1 : 0, DEFAULT_COMPRESSION_METHOD, encoded, encodedLength, message,
                        WebpLibrary.MESSAGE_LENGTH);
            } catch (RuntimeException e) {
                free(lib, encoded.getValue());
                throw new WebpException("imagify_webp_encode() failed", e);
            }
            check(lib, status, message, "imagify_webp_encode()");
            Pointer file = encoded.getValue();
            int length = Math.toIntExact(encodedLength.getValue());
            if (file == null || length <= 0) {
                free(lib, file);
                throw new WebpException("imagify_webp_encode() did not produce any output");
            }
            try {
                return file.getByteArray(0, length);
            } finally {
                free(lib, file);
            }
        }
    }

    /**
     * Encodes a sequence of frames as an animated WebP file at the default encoder effort.
     *
     * @param frames the frames, all of the same size, at least two
     * @param delaysMs how long each frame is shown, in milliseconds, one entry per frame
     * @param quality 0 (smallest) to 100, ignored when {@code lossless} is {@code true}
     * @param lossless whether to store the pixels without loss
     * @param loopCount how often the animation repeats, 0 meaning forever
     * @return the complete animated WebP file
     * @throws WebpException when the library is unavailable or the frames cannot be encoded
     * @see #encodeAnimation(List, int[], int, boolean, int, int)
     */
    public static byte[] encodeAnimation(List<BufferedImage> frames, int[] delaysMs,
            int quality, boolean lossless, int loopCount) throws WebpException {
        // 4 is the default of both libwebp and the gif2webp tool, which is what this form has
        // always encoded at.
        return encodeAnimation(frames, delaysMs, quality, lossless, loopCount, DEFAULT_COMPRESSION_METHOD);
    }

    /**
     * Encodes a sequence of frames as an animated WebP file.
     *
     * @param frames the frames, all of the same size, at least two
     * @param delaysMs how long each frame is shown, in milliseconds, one entry per frame
     * @param quality 0 (smallest) to 100, ignored when {@code lossless} is {@code true}
     * @param lossless whether to store the pixels without loss
     * @param loopCount how often the animation repeats, 0 meaning forever
     * @param compressionMethod how hard the encoder tries, 0 (quickest) to 6 (most thorough)
     * @return the complete animated WebP file
     * @throws WebpException when the library is unavailable or the frames cannot be encoded
     */
    public static byte[] encodeAnimation(List<BufferedImage> frames, int[] delaysMs,
            int quality, boolean lossless, int loopCount, int compressionMethod) throws WebpException {
        WebpLibrary lib = requireLibrary();
        if (frames == null || frames.size() < 2) {
            throw new WebpException("an animation needs at least two frames, got "
                    + (frames == null ? 0 : frames.size()));
        }
        if (delaysMs == null || delaysMs.length != frames.size()) {
            throw new WebpException("expected one delay per frame: " + frames.size()
                    + " frames but " + (delaysMs == null ? "no" : delaysMs.length + "") + " delays");
        }
        int width = frames.get(0).getWidth();
        int height = frames.get(0).getHeight();
        if (width <= 0 || height <= 0) {
            throw new WebpException("cannot encode a " + width + "x" + height + " animation");
        }
        int count = frames.size();
        long frameBytes = (long) width * height * 4;
        long total = frameBytes * count;
        if (total > Integer.MAX_VALUE - 8L) {
            throw new WebpException("the animation is too large to hold in memory");
        }
        byte[] abgr = new byte[(int) total];
        int offset = 0;
        for (BufferedImage frame : frames) {
            if (frame == null || frame.getWidth() != width || frame.getHeight() != height) {
                throw new WebpException("every frame must be " + width + "x" + height);
            }
            byte[] row = AbgrPixels.toAbgrBytes(frame, 0, 0, width, height, 1, 1);
            System.arraycopy(row, 0, abgr, offset, row.length);
            offset += row.length;
        }

        try (Memory pixels = new Memory(abgr.length);
                Memory delays = new Memory((long) count * Integer.BYTES);
                Memory message = new Memory(WebpLibrary.MESSAGE_LENGTH)) {
            pixels.write(0, abgr, 0, abgr.length);
            delays.write(0, delaysMs, 0, count);
            PointerByReference encoded = new PointerByReference();
            LongByReference encodedLength = new LongByReference();
            int status;
            try {
                status = lib.imagify_webp_encode_animation(pixels, count, width, height, delays,
                        clamp(quality), lossless ? 1 : 0, loopCount, compressionMethod, encoded,
                        encodedLength, message, WebpLibrary.MESSAGE_LENGTH);
            } catch (RuntimeException e) {
                free(lib, encoded.getValue());
                throw new WebpException("imagify_webp_encode_animation() failed", e);
            }
            check(lib, status, message, "imagify_webp_encode_animation()");
            Pointer file = encoded.getValue();
            int length = Math.toIntExact(encodedLength.getValue());
            if (file == null || length <= 0) {
                free(lib, file);
                throw new WebpException("imagify_webp_encode_animation() did not produce any output");
            }
            try {
                return file.getByteArray(0, length);
            } finally {
                free(lib, file);
            }
        }
    }

    // ----------------------------------------------------------------------------------- decoding

    /**
     * Decodes a still image.
     *
     * <p>An animation is rejected: its frames have to be read with {@link #decodeAnimation(byte[])},
     * because a single animated WebP has no single image to return.
     *
     * @param encoded a complete WebP file
     * @return the pixels as a standard {@link BufferedImage} type
     * @throws WebpException when the library is unavailable or the file cannot be decoded
     */
    public static BufferedImage decode(byte[] encoded) throws WebpException {
        WebpLibrary lib = requireLibrary();
        Objects.requireNonNull(encoded, "no data to decode");
        try (Memory input = input(encoded); Memory message = new Memory(WebpLibrary.MESSAGE_LENGTH)) {
            PointerByReference out = new PointerByReference();
            LongByReference outLength = new LongByReference();
            WebpLibrary.WebpFeatures features = new WebpLibrary.WebpFeatures();
            int status;
            try {
                status = lib.imagify_webp_decode(input, encoded.length, out, outLength, features,
                        message, WebpLibrary.MESSAGE_LENGTH);
            } catch (RuntimeException e) {
                // Whatever the call was doing when Java threw, the buffer may already be allocated.
                free(lib, out.getValue());
                throw new WebpException("imagify_webp_decode() failed", e);
            }
            check(lib, status, message, "imagify_webp_decode()");
            Pointer pixels = out.getValue();
            int length = Math.toIntExact(outLength.getValue());
            if (pixels == null || length <= 0) {
                free(lib, pixels);
                throw new WebpException("imagify_webp_decode() did not produce any pixel");
            }
            try {
                BufferedImage image = AbgrPixels.toBufferedImage(pixels.getByteArray(0, length),
                        features.width, features.height);
                return normalise(image, features.hasAlpha != 0);
            } finally {
                free(lib, pixels);
            }
        }
    }

    /**
     * A WebP file read in a single pass, holding its properties and its frames together.
     *
     * <p>The two belong in one result because they cannot be had apart cheaply. An animation's frame
     * count and loop count live in the animation control chunk, which only the animation decoder
     * reads, and that decoder hands back every frame at once. Asking for the headers and then
     * decoding again therefore walks the same file twice, and asking for a frame on its own walks
     * it once more. One pass gives all of it.
     *
     * @param info     the properties reported by the headers
     * @param frames   the frames in presentation order, composited onto the canvas for an animation,
     *                 and exactly one frame for a still image
     * @param delaysMs how long each frame is shown in milliseconds, parallel to {@code frames}, or
     *                 {@code null} for a still image, which has no timing of its own
     */
    public record DecodedWebp(WebpImageInfo info, List<BufferedImage> frames, int[] delaysMs) {

        public DecodedWebp {
            frames = List.copyOf(frames);
            delaysMs = delaysMs == null ? null : delaysMs.clone();
        }
    }

    /**
     * Reads a WebP file and decodes its frames in one pass.
     *
     * <p>Prefer this over {@link #readHeader(byte[])} followed by a decode when the frames are
     * wanted anyway, which is the case for every {@code ImageIO} read. {@link #readHeader(byte[])}
     * stays the cheaper choice for a still image inspected for its size alone, because it decodes no
     * pixels.
     *
     * @param encoded a complete WebP file, still or animated
     * @return the properties and the frames, never empty
     * @throws WebpException when the library is unavailable or the file cannot be decoded
     */
    public static DecodedWebp decodeFile(byte[] encoded) throws WebpException {
        WebpLibrary lib = requireLibrary();
        Objects.requireNonNull(encoded, "no data to decode");
        try (Memory input = input(encoded); Memory message = new Memory(WebpLibrary.MESSAGE_LENGTH)) {
            WebpLibrary.WebpFeatures features = new WebpLibrary.WebpFeatures();
            check(lib, lib.imagify_webp_read_features(input, encoded.length, features, message,
                    WebpLibrary.MESSAGE_LENGTH), message, "imagify_webp_read_features()");
            if (features.hasAnimation == 0) {
                WebpImageInfo info = new WebpImageInfo(features.width, features.height,
                        features.hasAlpha != 0, false, features.format, 1, 0);
                return new DecodedWebp(info, List.of(decode(encoded)), null);
            }
            DecodedAnimation animation = decodeAnimationData(lib, input, encoded.length, message,
                    features.hasAlpha != 0);
            WebpImageInfo info = new WebpImageInfo(features.width, features.height,
                    features.hasAlpha != 0, true, features.format,
                    animation.info().frameCount, animation.info().loopCount);
            return new DecodedWebp(info, animation.frames(), animation.delays());
        }
    }

    /**
     * Decodes every frame of an animation.
     *
     * @param encoded a complete animated WebP file
     * @return the composited canvas sized frames in presentation order, never empty
     * @throws WebpException when the library is unavailable or the file cannot be decoded
     */
    public static List<BufferedImage> decodeAnimation(byte[] encoded) throws WebpException {
        WebpLibrary lib = requireLibrary();
        Objects.requireNonNull(encoded, "no data to decode");
        try (Memory input = input(encoded); Memory message = new Memory(WebpLibrary.MESSAGE_LENGTH)) {
            WebpLibrary.WebpFeatures features = new WebpLibrary.WebpFeatures();
            check(lib, lib.imagify_webp_read_features(input, encoded.length, features, message,
                    WebpLibrary.MESSAGE_LENGTH), message, "imagify_webp_read_features()");
            DecodedAnimation animation = decodeAnimationData(lib, input, encoded.length, message,
                    features.hasAlpha != 0);
            List<BufferedImage> frames = animation.frames();
            if (frames.isEmpty()) {
                throw new WebpException("the WebP animation holds no frames");
            }
            return frames;
        }
    }

    /**
     * Decodes the frame timings of an animation without keeping the pixels around.
     *
     * @param encoded a complete animated WebP file
     * @return the number of frames, the loop count and the per frame delay in milliseconds
     * @throws WebpException when the library is unavailable or the file cannot be decoded
     */
    public static int[][] readAnimationTiming(byte[] encoded) throws WebpException {
        WebpLibrary lib = requireLibrary();
        Objects.requireNonNull(encoded, "no data to decode");
        try (Memory input = input(encoded); Memory message = new Memory(WebpLibrary.MESSAGE_LENGTH)) {
            WebpLibrary.WebpAnimation animation = new WebpLibrary.WebpAnimation();
            PointerByReference delays = new PointerByReference();
            check(lib, lib.imagify_webp_read_animation(input, encoded.length, animation, delays,
                    message, WebpLibrary.MESSAGE_LENGTH), message, "imagify_webp_read_animation()");
            Pointer delaysPtr = delays.getValue();
            if (delaysPtr == null) {
                throw new WebpException("imagify_webp_read_animation() did not produce any delay");
            }
            try {
                int[] timings = delaysPtr.getIntArray(0, animation.frameCount);
                return new int[][] { { animation.frameCount, animation.loopCount }, timings };
            } finally {
                free(lib, delaysPtr);
            }
        }
    }

    /**
     * What a single pass over an animation reads out of the native library.
     *
     * @param info the animation control chunk, whose {@code frameCount} and {@code loopCount} the
     *        caller reports and whose size selects the frames
     * @param frames the frames in presentation order, composited onto the canvas
     * @param delays how long each frame is shown in milliseconds
     */
    private record DecodedAnimation(WebpLibrary.WebpAnimation info, List<BufferedImage> frames,
            int[] delays) {
    }

    private static DecodedAnimation decodeAnimationData(WebpLibrary lib, Memory input, int length,
            Memory message, boolean alpha) throws WebpException {
        WebpLibrary.WebpAnimation animation = new WebpLibrary.WebpAnimation();
        PointerByReference framesRef = new PointerByReference();
        PointerByReference delaysRef = new PointerByReference();
        int status;
        try {
            status = lib.imagify_webp_decode_animation(input, length, animation, framesRef,
                    delaysRef, message, WebpLibrary.MESSAGE_LENGTH);
        } catch (RuntimeException e) {
            // Whatever the call was doing when Java threw, the buffers may already be allocated.
            free(lib, framesRef.getValue());
            free(lib, delaysRef.getValue());
            throw new WebpException("imagify_webp_decode_animation() failed", e);
        }
        check(lib, status, message, "imagify_webp_decode_animation()");
        Pointer framesPtr = framesRef.getValue();
        Pointer delaysPtr = delaysRef.getValue();
        if (framesPtr == null) {
            free(lib, framesPtr);
            free(lib, delaysPtr);
            throw new WebpException("imagify_webp_decode_animation() did not produce any pixel");
        }
        try {
            long frameBytes = (long) animation.width * animation.height * 4;
            byte[] all = framesPtr.getByteArray(0,
                    Math.toIntExact(Math.multiplyExact((long) animation.frameCount, frameBytes)));
            List<BufferedImage> frames = splitFrames(all, animation.frameCount, animation.width,
                    animation.height, alpha);
            int[] delays = delaysPtr.getIntArray(0, animation.frameCount);
            return new DecodedAnimation(animation, frames, delays);
        } finally {
            free(lib, framesPtr);
            free(lib, delaysPtr);
        }
    }

    /**
     * Splits one native buffer of ABGR bytes into a frame per animation frame.
     *
     * @param bytes the whole animation, {@code frameCount * width * height * 4} bytes
     * @param frameCount the number of frames
     * @param width the canvas width in pixels
     * @param height the canvas height in pixels
     * @param alpha whether the frames are normalised to {@link BufferedImage#TYPE_INT_ARGB} rather
     *        than {@link BufferedImage#TYPE_INT_RGB}
     * @return the frames in presentation order
     */
    private static List<BufferedImage> splitFrames(byte[] bytes, int frameCount, int width, int height,
            boolean alpha) {
        int frameBytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        List<BufferedImage> frames = new ArrayList<>(frameCount);
        for (int frame = 0; frame < frameCount; frame++) {
            byte[] abgr = Arrays.copyOfRange(bytes, frame * frameBytes, (frame + 1) * frameBytes);
            frames.add(normalise(AbgrPixels.toBufferedImage(abgr, width, height), alpha));
        }
        return frames;
    }

    // ---------------------------------------------------------------------------------- internals

    /**
     * Turns a decoded image into one of the standard {@link BufferedImage} types.
     *
     * <p>The native library hands back tightly packed {@code A, B, G, R} bytes, which
     * {@link AbgrPixels#toBufferedImage} wraps as a {@link BufferedImage#TYPE_4BYTE_ABGR} image. A
     * {@code TYPE_4BYTE_ABGR} raster always has an alpha channel, so one that the file does not
     * carry is taken away: the caller asked for an image and has a right to one that says what the
     * file says, whether the alpha byte is a real sample or an opaque placeholder.
     */
    private static BufferedImage normalise(BufferedImage image, boolean alpha) {
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        if (image.getType() == type) {
            return image;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage target = new BufferedImage(width, height, type);
        target.setRGB(0, 0, width, height, image.getRGB(0, 0, width, height, null, 0, width), 0, width);
        return target;
    }

    private static Memory input(byte[] encoded) {
        // A Memory needs a positive size, and an empty input is a caller error rather than a
        // reason to allocate a zero length buffer the shim would then reject.
        Memory memory = new Memory(Math.max(encoded.length, 1));
        if (encoded.length > 0) {
            memory.write(0, encoded, 0, encoded.length);
        }
        return memory;
    }

    private static int clamp(int quality) {
        return Math.max(WebpLibrary.MIN_QUALITY, Math.min(WebpLibrary.MAX_QUALITY, quality));
    }

    private static void check(WebpLibrary lib, int status, Memory message, String operation)
            throws WebpException {
        if (status == WebpLibrary.IMAGIFY_WEBP_OK) {
            return;
        }
        String text = message.getString(0);
        throw new WebpException(operation + " failed: "
                + (text == null || text.isBlank() ? statusName(status) : text));
    }

    /**
     * @param status one of the {@code IMAGIFY_WEBP_*} values
     * @return a description for the ones the shim does not always put a message beside
     */
    public static String statusName(int status) {
        return switch (status) {
            case WebpLibrary.IMAGIFY_WEBP_OK -> "no error";
            case WebpLibrary.IMAGIFY_WEBP_ERR_ARGUMENT -> "a parameter was out of range";
            case WebpLibrary.IMAGIFY_WEBP_ERR_CORRUPT -> "the input is not a WebP file, or is a damaged one";
            case WebpLibrary.IMAGIFY_WEBP_ERR_MEMORY -> "out of memory";
            case WebpLibrary.IMAGIFY_WEBP_ERR_UNSUPPORTED -> "libwebp will not do this";
            case WebpLibrary.IMAGIFY_WEBP_ERR_INTERNAL -> "libwebp failed for no stated reason";
            default -> "status " + status;
        };
    }

    private static void free(WebpLibrary lib, Pointer buffer) {
        if (buffer != null) {
            lib.imagify_webp_free(buffer);
        }
    }
}