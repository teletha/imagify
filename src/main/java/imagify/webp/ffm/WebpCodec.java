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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import imagify.pixels.AbgrPixels;
import imagify.webp.DecodedWebp;
import imagify.webp.WebpException;
import imagify.webp.WebpImageInfo;

/**
 * Entry point to the {@code libwebp} based WebP codec using Java's Foreign Function &amp; Memory
 * API (JEP 454).
 *
 * <p>This is the encoder and decoder behind both the ImageIO service providers in
 * {@code imagify.webp} and the direct calls {@link imagify.ImageWriter} and
 * {@link imagify.ImageReader} make, so a WebP written through any of them comes out of the same
 * encoder with the same settings.
 *
 * <p>The native library is loaded lazily, on first use, and failing to load it is never fatal: the
 * ImageIO service providers of this library then stay inert instead of breaking {@code ImageIO} for
 * every other format, and they fall back to the JDK's own WebP support, which is the
 * {@link javax.imageio} providers that ship with a JDK 13 or newer. Use {@link #isAvailable()} to
 * find out which of the two a given call will use.
 *
 * <p>Shared libraries for Windows, macOS and Linux, in both 64 bit flavours, are fetched on first
 * use from the GitHub release the codec's {@code native.properties} names, and are cached locally,
 * so installing anything is not required. Should none be available for the current platform — an
 * unsupported one, an unpublished release, a blocked download — a {@code libwebp} based one found
 * the usual way is used instead: point the {@code java.library.path} system property (or the
 * platform specific {@code PATH} / {@code LD_LIBRARY_PATH}) at the directory that holds it.
 *
 * <pre>
 * java -Djava.library.path=/usr/local/lib -cp ... YourApp
 * </pre>
 *
 * <p>Set {@code -Dimagify.webp.bundled=false} to ignore the managed library and always look for one
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

    /**
     * The quality an encode uses when a caller does not name one, which is
     * {@code libwebp}'s own {@code WEBP_QUALITY_DEFAULT}.
     */
    public static final int DEFAULT_QUALITY = WebpLibrary.DEFAULT_QUALITY;

    /** The smallest {@code quality} an encode accepts. */
    public static final int MIN_QUALITY = WebpLibrary.MIN_QUALITY;

    /** The largest {@code quality} an encode accepts. */
    public static final int MAX_QUALITY = WebpLibrary.MAX_QUALITY;

    /**
     * The quickest encoding effort {@code libwebp} accepts, and the one {@code method} is measured
     * against in {@link #encode(RenderedImage, int, boolean, int)}.
     */
    public static final int MIN_METHOD = WebpLibrary.MIN_METHOD;

    /** The most thorough encoding effort {@code libwebp} accepts. */
    public static final int MAX_METHOD = WebpLibrary.MAX_METHOD;

    private static final Logger log = getLogger(WebpCodec.class.getName());

    private static final Object LOCK = new Object();

    private static volatile boolean loaded;

    private static volatile WebpLibrary library;

    private static volatile String failure;

    private static final int HEADER_LENGTH = 30;

    private static final int FIRST_CHUNK = 12;

    private static final int CHUNK_HEADER_LENGTH = 8;

    private static final int ANMF_FRAME_HEADER_LENGTH = CHUNK_HEADER_LENGTH + 16;

    private static final String CHUNK_VP8L = "VP8L";

    private static final String CHUNK_VP8 = "VP8 ";

    private static final String CHUNK_ANMF = "ANMF";

    private static final int LOSSLESS_HEADER_LENGTH = 128;

    /**
     * The encoding effort used when a caller does not name one: libwebp's own
     * {@code WEBP_PRESET_DEFAULT} value of 4, and the one that is a reasonable answer for a file
     * being written rather than produced as fast as it can be.
     */
    public static final int DEFAULT_COMPRESSION_METHOD = 4;

    private WebpCodec() {
        // utility class
    }

    public static final int FORMAT_VP8 = WebpLibrary.FORMAT_VP8;

    public static final int FORMAT_VP8L = WebpLibrary.FORMAT_VP8L;

    public static final int FORMAT_VP8X = WebpLibrary.FORMAT_VP8X;

    public static String formatName(int format) {
        return switch (format) {
        case WebpLibrary.FORMAT_VP8 -> "VP8";
        case WebpLibrary.FORMAT_VP8L -> "VP8L";
        case WebpLibrary.FORMAT_VP8X -> "VP8X";
        default -> "unknown(" + format + ")";
        };
    }

    // ---------------------------------------------------------------------------- availability

    public static boolean isAvailable() {
        return library() != null;
    }

    public static String getUnavailableReason() {
        library();
        return failure;
    }

    public static String getVersion() {
        WebpLibrary lib = library();
        return lib == null ? null : lib.imagify_webp_webp_version();
    }

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
                log.log(Level.WARNING, "The WebP codec is disabled: {0}. Its library is fetched on "
                        + "first use from a GitHub release for Windows, macOS and Linux on x64 and "
                        + "arm64, so either your platform is not one of those, the fetch failed, or "
                        + "downloads are turned off. You can also point -Djava.library.path at a "
                        + "directory that holds one. ImageIO falls back to the JDK's own WebP "
                        + "support either way.", failure);
            }
            loaded = true;
            return library;
        }
    }

    public static WebpLibrary requireLibrary() throws WebpException {
        WebpLibrary lib = library();
        if (lib == null) {
            throw new WebpException(failure == null ? "libwebp is not available" : failure);
        }
        return lib;
    }

    private static WebpLibrary load() {
        Path managed = WebpNativeLibrary.extract();
        String managedReason = WebpNativeLibrary.reason();
        try {
            WebpLibrary lib = managed == null ? loadSystem() : loadManaged(managed);

            String abi = lib.imagify_webp_abi_version();
            if (!WebpLibrary.ABI_VERSION.equals(abi)) {
                throw new IllegalStateException("the WebP library speaks ABI version " + abi + " but this jar speaks " + WebpLibrary.ABI_VERSION);
            }
            log.log(Level.DEBUG, "using libwebp {0}{1}", lib.imagify_webp_webp_version(), managed == null ? " from system"
                    : " from " + managed);
            return lib;
        } catch (Throwable t) {
            if (managedReason == null) {
                throw t;
            }
            // The managed library is what this jar would normally use, so when it could not be
            // obtained the failing fallback to a system library is explained by how the managed one
            // failed, which is where the actionable cause is.
            throw new IllegalStateException("the WebP library could not be fetched (" + managedReason
                    + "), and no libwebp from the system could be loaded either", t);
        }
    }

    private static WebpLibrary loadSystem() {
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = linker.defaultLookup();
        return new WebpLibrary(lookup);
    }

    private static WebpLibrary loadManaged(Path managed) {
        System.load(managed.toString());
        Linker linker = Linker.nativeLinker();
        // The symbols are resolved once and the downcall handles that capture them are then held
        // for as long as this class lives, which outlives the thread that loaded the library and is
        // called from every one of them. A confined arena would tie the symbols to the loading
        // thread, and every call from another thread would fail on it, so the library is opened
        // against the global arena instead. Nothing is ever unmapped this way, which is what a
        // process wide codec wants anyway.
        SymbolLookup lookup = linker.defaultLookup().or(SymbolLookup.libraryLookup(managed, Arena.global()));
        return new WebpLibrary(lookup);
    }

    private static String describe(Throwable t) {
        if (t instanceof UnsatisfiedLinkError || t instanceof NoClassDefFoundError) {
            return "cannot load the native library '" + WebpLibrary.LIBRARY_NAME + "': " + t.getMessage();
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    // -------------------------------------------------------------------------------- inspection

    public static WebpImageInfo readHeader(byte[] encoded) throws WebpException {
        WebpLibrary lib = requireLibrary();
        Objects.requireNonNull(encoded, "no data to inspect");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = input(encoded, arena);
            MemorySegment message = arena.allocate(WebpLibrary.MESSAGE_LENGTH);
            MemorySegment features = arena.allocate(WebpLibrary.WEBP_FEATURES_LAYOUT);
            int status = lib.imagify_webp_read_features(input, encoded.length, features, message, WebpLibrary.MESSAGE_LENGTH);
            check(lib, status, message, "imagify_webp_read_features()");
            int width = features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_WIDTH);
            int height = features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_HEIGHT);
            int hasAlpha = features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_HAS_ALPHA);
            int hasAnimation = features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_HAS_ANIMATION);
            int format = features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_FORMAT);
            if (hasAnimation == 0) {
                return new WebpImageInfo(width, height, hasAlpha != 0, false, format, 1, 0);
            }
            MemorySegment animation = arena.allocate(WebpLibrary.WEBP_ANIMATION_LAYOUT);
            MemorySegment delaysRef = arena.allocate(ValueLayout.ADDRESS);
            check(lib, lib
                    .imagify_webp_read_animation(input, encoded.length, animation, delaysRef, message, WebpLibrary.MESSAGE_LENGTH), message, "imagify_webp_read_animation()");
            MemorySegment delaysPtr = delaysRef.get(ValueLayout.ADDRESS, 0);
            int frameCount = animation.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_FRAME_COUNT);
            int loopCount = animation.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_LOOP_COUNT);
            lib.imagify_webp_free(delaysPtr);
            return new WebpImageInfo(width, height, hasAlpha != 0, true, format, frameCount, loopCount);
        }
    }

    public static boolean isWebP(byte[] data) {
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

    public static int headerLength() {
        return HEADER_LENGTH;
    }

    public static boolean isLossless(byte[] data) {
        if (!isWebP(data)) {
            return false;
        }
        return lossless(data, FIRST_CHUNK);
    }

    private static boolean lossless(byte[] data, int offset) {
        while (offset + CHUNK_HEADER_LENGTH <= data.length) {
            if (matches(data, offset, CHUNK_VP8L)) {
                return true;
            }
            if (matches(data, offset, CHUNK_VP8)) {
                return false;
            }
            if (matches(data, offset, CHUNK_ANMF)) {
                return lossless(data, offset + ANMF_FRAME_HEADER_LENGTH);
            }
            int size = chunkSize(data, offset);
            if (size < 0) {
                return false;
            }
            long next = (long) offset + CHUNK_HEADER_LENGTH + size + (size & 1);
            if (next >= data.length) {
                return false;
            }
            offset = (int) next;
        }
        return false;
    }

    private static int chunkSize(byte[] data, int offset) {
        return (data[offset + 4] & 0xFF) | (data[offset + 5] & 0xFF) << 8 | (data[offset + 6] & 0xFF) << 16 | (data[offset + 7] & 0xFF) << 24;
    }

    public static int losslessHeaderLength() {
        return LOSSLESS_HEADER_LENGTH;
    }

    // ----------------------------------------------------------------------------------- encoding

    public static byte[] encode(RenderedImage source, int quality, boolean lossless) throws WebpException {
        return encode(source, quality, lossless, DEFAULT_COMPRESSION_METHOD);
    }

    /**
     * Encodes an image, choosing how hard the encoder tries.
     *
     * <p>{@code method} is libwebp's own effort scale and runs against
     * {@link #MIN_METHOD} and {@link #MAX_METHOD}. Lower is faster and produces a larger file, and
     * the difference is large enough to be worth naming: on a 1600x1200 photograph, method 0 takes
     * roughly a quarter of the time method 4 does and gives up about a third more bytes. Neither
     * end
     * is wrong and the choice is a caller's, which is why the default of
     * {@value #DEFAULT_COMPRESSION_METHOD} is only a default and not a fixed point.
     *
     * @param source the image to encode
     * @param quality {@link #MIN_QUALITY} to {@link #MAX_QUALITY}; a fidelity to trade away, or,
     *            with
     *            {@code lossless}, how hard to try to make the file small
     * @param lossless whether to store the pixels without loss
     * @param method {@link #MIN_METHOD} to {@link #MAX_METHOD}
     * @return the encoded file
     * @throws WebpException when the library is unavailable or libwebp refuses the image
     */
    public static byte[] encode(RenderedImage source, int quality, boolean lossless, int method) throws WebpException {
        WebpLibrary lib = requireLibrary();
        if (source == null) {
            throw new WebpException("no image to encode");
        }
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) {
            throw new WebpException("cannot encode a " + width + "x" + height + " image");
        }
        int effort = clamp(method, WebpLibrary.MIN_METHOD, WebpLibrary.MAX_METHOD);
        boolean hasAlpha = source.getColorModel() != null && source.getColorModel().hasAlpha();

        // Try the zero-copy paths first: BGR bytes for opaque images, ARGB words for everything
        // else that already has them, and finally the image's own ABGR bytes or a packed copy.
        byte[] bgr = null;
        if (!hasAlpha && source instanceof BufferedImage image && lib.hasBgrEntryPoint()) {
            bgr = AbgrPixels.bgrBytesOrNull(image);
        }
        int[] words = bgr == null && lib.hasArgbEntryPoints() ? AbgrPixels.argbWords(source) : null;
        byte[] abgr = null;
        if (bgr == null && words == null && source instanceof BufferedImage image) {
            abgr = AbgrPixels.abgrBytesOrNull(image);
        }
        if (bgr == null && words == null && abgr == null) {
            abgr = new byte[width * height * 4];
            AbgrPixels.toAbgrBytes(source, 0, 0, width, height, 1, 1, abgr, 0);
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment message = arena.allocate(WebpLibrary.MESSAGE_LENGTH);
            MemorySegment encoded = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment encodedLength = arena.allocate(ValueLayout.JAVA_LONG);
            int q = clamp(quality);
            int losslessFlag = lossless ? 1 : 0;
            int status;
            String operation;
            if (bgr != null) {
                status = lib.imagify_webp_encode_bgr(MemorySegment.ofArray(bgr), width, height, q, losslessFlag,
                        effort, encoded, encodedLength, message, WebpLibrary.MESSAGE_LENGTH);
                operation = "imagify_webp_encode_bgr()";
            } else if (words != null) {
                status = lib.imagify_webp_encode_argb(MemorySegment.ofArray(words), width, height, q, losslessFlag,
                        effort, encoded, encodedLength, message, WebpLibrary.MESSAGE_LENGTH);
                operation = "imagify_webp_encode_argb()";
            } else {
                status = lib.imagify_webp_encode(MemorySegment.ofArray(abgr), width, height, q, losslessFlag,
                        effort, encoded, encodedLength, message, WebpLibrary.MESSAGE_LENGTH);
                operation = "imagify_webp_encode()";
            }
            check(lib, status, message, operation);
            return collect(lib, encoded, encodedLength, "imagify_webp_encode()");
        }
    }

    public static byte[] encodeAnimation(List<BufferedImage> frames, int[] delaysMs, int quality, boolean lossless, int loopCount)
            throws WebpException {
        return encodeAnimation(frames, delaysMs, quality, lossless, loopCount, DEFAULT_COMPRESSION_METHOD);
    }

    public static byte[] encodeAnimation(List<BufferedImage> frames, int[] delaysMs, int quality, boolean lossless, int loopCount, int compressionMethod)
            throws WebpException {
        WebpLibrary lib = requireLibrary();
        if (frames == null || frames.size() < 2) {
            throw new WebpException("an animation needs at least two frames, got " + (frames == null ? 0 : frames.size()));
        }
        if (delaysMs == null || delaysMs.length != frames.size()) {
            throw new WebpException("expected one delay per frame: " + frames
                    .size() + " frames but " + (delaysMs == null ? "no" : delaysMs.length + "") + " delays");
        }
        int width = frames.get(0).getWidth();
        int height = frames.get(0).getHeight();
        if (width <= 0 || height <= 0) {
            throw new WebpException("cannot encode a " + width + "x" + height + " animation");
        }
        int count = frames.size();
        long frameWords = (long) width * height;
        long total = frameWords * count;
        if (total > Integer.MAX_VALUE - 8L) {
            throw new WebpException("the animation is too large to hold in memory");
        }
        // One buffer for the whole animation, because the native encoder wants the frames one after
        // another and a run of its own is cheaper than a thousand little ones. Whether it is words
        // or bytes is decided once, by the first frame that cannot be used as it is: a single frame
        // of a different kind would have to be converted anyway, and converting it into a buffer of
        // the other kind is what the code below does by copying the ones that could have been kept.
        int[] words = lib.hasArgbEntryPoints() ? new int[(int) total] : null;
        byte[] abgr = words == null ? new byte[(int) total * 4] : null;
        for (int frame = 0; frame < count; frame++) {
            BufferedImage image = frames.get(frame);
            if (image == null || image.getWidth() != width || image.getHeight() != height) {
                throw new WebpException("every frame must be " + width + "x" + height);
            }
            int at = (int) (frame * frameWords);
            if (words != null) {
                int[] own = AbgrPixels.argbWords(image);
                if (own != null) {
                    System.arraycopy(own, 0, words, at, (int) frameWords);
                } else {
                    System.arraycopy(AbgrPixels.toArgbWords(image, 0, 0, width, height), 0, words, at, (int) frameWords);
                }
            } else {
                int atByte = frame * (int) frameWords * 4;
                byte[] row = AbgrPixels.toAbgrBytes(image, 0, 0, width, height, 1, 1);
                System.arraycopy(row, 0, abgr, atByte, row.length);
            }
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pixels = words != null ? MemorySegment.ofArray(words) : MemorySegment.ofArray(abgr);
            MemorySegment delays = arena.allocate((long) count * Integer.BYTES);
            for (int i = 0; i < count; i++) {
                delays.set(ValueLayout.JAVA_INT, (long) i * Integer.BYTES, delaysMs[i]);
            }
            MemorySegment message = arena.allocate(WebpLibrary.MESSAGE_LENGTH);
            MemorySegment encoded = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment encodedLength = arena.allocate(ValueLayout.JAVA_LONG);
            int effort = clamp(compressionMethod, WebpLibrary.MIN_METHOD, WebpLibrary.MAX_METHOD);
            int status = words != null
                    ? lib.imagify_webp_encode_animation_argb(pixels, count, width, height, delays, clamp(quality), lossless ? 1
                            : 0, loopCount, effort, encoded, encodedLength, message, WebpLibrary.MESSAGE_LENGTH)
                    : lib.imagify_webp_encode_animation(pixels, count, width, height, delays, clamp(quality), lossless ? 1
                            : 0, loopCount, effort, encoded, encodedLength, message, WebpLibrary.MESSAGE_LENGTH);
            check(lib, status, message, words != null ? "imagify_webp_encode_animation_argb()" : "imagify_webp_encode_animation()");
            return collect(lib, encoded, encodedLength, "imagify_webp_encode_animation()");
        }
    }

    // ----------------------------------------------------------------------------------- decoding

    public static BufferedImage decode(byte[] encoded) throws WebpException {
        WebpLibrary lib = requireLibrary();
        Objects.requireNonNull(encoded, "no data to decode");
        if (lib.hasArgbEntryPoints()) {
            BufferedImage direct = decodeIntoWords(lib, encoded);
            if (direct != null) {
                return direct;
            }
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = input(encoded, arena);
            MemorySegment message = arena.allocate(WebpLibrary.MESSAGE_LENGTH);
            MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment outLength = arena.allocate(ValueLayout.JAVA_LONG);
            MemorySegment features = arena.allocate(WebpLibrary.WEBP_FEATURES_LAYOUT);
            int status = lib.imagify_webp_decode(input, encoded.length, out, outLength, features, message, WebpLibrary.MESSAGE_LENGTH);
            check(lib, status, message, "imagify_webp_decode()");
            MemorySegment pixels = out.get(ValueLayout.ADDRESS, 0);
            int length = Math.toIntExact(outLength.get(ValueLayout.JAVA_LONG, 0));
            if (pixels.address() == 0 || length <= 0) {
                lib.imagify_webp_free(pixels);
                throw new WebpException("imagify_webp_decode() did not produce any pixel");
            }
            try {
                byte[] bytes = pixels.reinterpret(length).toArray(ValueLayout.JAVA_BYTE);
                int width = features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_WIDTH);
                int height = features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_HEIGHT);
                int hasAlpha = features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_HAS_ALPHA);
                BufferedImage image = AbgrPixels.toBufferedImage(bytes, width, height);
                return normalise(image, hasAlpha != 0);
            } finally {
                lib.imagify_webp_free(pixels);
            }
        }
    }

    /**
     * Decodes straight into an array of words, and answers {@code null} for anything this cannot do
     * so that the caller falls back rather than fails.
     *
     * <p>The only thing that cannot be done here is an animation, which has no single image to
     * decode and which the other entry point refuses as well; being told that is not a failure of
     * this path, it is the path not applying, and the caller already has a way of reading the
     * frames. The size has to be known before the buffer can be allocated, so the headers are read
     * first, which the entry point reads again for its own report.
     */
    private static BufferedImage decodeIntoWords(WebpLibrary lib, byte[] encoded) throws WebpException {
        WebpImageInfo info = readHeader(encoded);
        if (info.hasAnimation()) {
            return null;
        }
        int width = info.width();
        int height = info.height();
        long count = (long) width * height;
        if (width <= 0 || height <= 0 || count > Integer.MAX_VALUE) {
            return null;
        }
        int[] words = new int[(int) count];
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = input(encoded, arena);
            MemorySegment message = arena.allocate(WebpLibrary.MESSAGE_LENGTH);
            MemorySegment features = arena.allocate(WebpLibrary.WEBP_FEATURES_LAYOUT);
            check(lib, lib.imagify_webp_decode_into_argb(input, encoded.length, MemorySegment
                    .ofArray(words), width, features, message, WebpLibrary.MESSAGE_LENGTH), message, "imagify_webp_decode_into_argb()");
        }
        // The alpha type is chosen the same way it is on the other path, from whether the file
        // carries a channel rather than from whether the decoded words happen to be opaque, so that
        // a file with no channel comes back as the JDK's opaque type and not as an ARGB one.
        return normalise(AbgrPixels.toArgbImage(words, width, height), info.hasAlpha());
    }

    public static DecodedWebp decodeFile(byte[] encoded) throws WebpException {
        WebpLibrary lib = requireLibrary();
        Objects.requireNonNull(encoded, "no data to decode");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = input(encoded, arena);
            MemorySegment message = arena.allocate(WebpLibrary.MESSAGE_LENGTH);
            MemorySegment features = arena.allocate(WebpLibrary.WEBP_FEATURES_LAYOUT);
            int status = lib.imagify_webp_read_features(input, encoded.length, features, message, WebpLibrary.MESSAGE_LENGTH);
            check(lib, status, message, "imagify_webp_read_features()");
            int hasAlpha = features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_HAS_ALPHA);
            int width = features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_WIDTH);
            int height = features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_HEIGHT);
            int format = features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_FORMAT);
            int hasAnimation = features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_HAS_ANIMATION);
            if (hasAnimation == 0) {
                WebpImageInfo info = new WebpImageInfo(width, height, hasAlpha != 0, false, format, 1, 0);
                return new DecodedWebp(info, List.of(decode(encoded)), null);
            }
            DecodedAnimation animation = decodeAnimationData(lib, input, encoded.length, message, features
                    .get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_HAS_ALPHA) != 0, arena);
            WebpImageInfo info = new WebpImageInfo(width, height, hasAlpha != 0, true, format, animation.info.frameCount(), animation.info
                    .loopCount());
            return new DecodedWebp(info, animation.frames, animation.delays);
        }
    }

    public static List<BufferedImage> decodeAnimation(byte[] encoded) throws WebpException {
        WebpLibrary lib = requireLibrary();
        Objects.requireNonNull(encoded, "no data to decode");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = input(encoded, arena);
            MemorySegment message = arena.allocate(WebpLibrary.MESSAGE_LENGTH);
            MemorySegment features = arena.allocate(WebpLibrary.WEBP_FEATURES_LAYOUT);
            int status = lib.imagify_webp_read_features(input, encoded.length, features, message, WebpLibrary.MESSAGE_LENGTH);
            check(lib, status, message, "imagify_webp_read_features()");
            DecodedAnimation animation = decodeAnimationData(lib, input, encoded.length, message, features
                    .get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_HAS_ALPHA) != 0, arena);
            List<BufferedImage> frames = animation.frames;
            if (frames.isEmpty()) {
                throw new WebpException("the WebP animation holds no frames");
            }
            return frames;
        }
    }

    public static int[][] readAnimationTiming(byte[] encoded) throws WebpException {
        WebpLibrary lib = requireLibrary();
        Objects.requireNonNull(encoded, "no data to decode");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = input(encoded, arena);
            MemorySegment message = arena.allocate(WebpLibrary.MESSAGE_LENGTH);
            MemorySegment animation = arena.allocate(WebpLibrary.WEBP_ANIMATION_LAYOUT);
            MemorySegment delaysRef = arena.allocate(ValueLayout.ADDRESS);
            check(lib, lib
                    .imagify_webp_read_animation(input, encoded.length, animation, delaysRef, message, WebpLibrary.MESSAGE_LENGTH), message, "imagify_webp_read_animation()");
            MemorySegment delaysPtr = delaysRef.get(ValueLayout.ADDRESS, 0);
            if (delaysPtr.address() == 0) {
                throw new WebpException("imagify_webp_read_animation() did not produce any delay");
            }
            int frameCount = animation.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_FRAME_COUNT);
            int loopCount = animation.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_LOOP_COUNT);
            MemorySegment delays = delaysPtr.reinterpret((long) frameCount * Integer.BYTES);
            int[] timings = new int[frameCount];
            for (int i = 0; i < frameCount; i++) {
                timings[i] = delays.get(ValueLayout.JAVA_INT, (long) i * Integer.BYTES);
            }
            lib.imagify_webp_free(delaysPtr);
            return new int[][] {{frameCount, loopCount}, timings};
        }
    }

    private record DecodedAnimation(WebpLibrary.WebpAnimationInfo info, List<BufferedImage> frames, int[] delays) {
    }

    private static DecodedAnimation decodeAnimationData(WebpLibrary lib, MemorySegment input, int length, MemorySegment message, boolean alpha, Arena arena)
            throws WebpException {
        MemorySegment animation = arena.allocate(WebpLibrary.WEBP_ANIMATION_LAYOUT);
        MemorySegment framesRef = arena.allocate(ValueLayout.ADDRESS);
        MemorySegment delaysRef = arena.allocate(ValueLayout.ADDRESS);
        int status = lib.imagify_webp_decode_animation(input, length, animation, framesRef, delaysRef, message, WebpLibrary.MESSAGE_LENGTH);
        check(lib, status, message, "imagify_webp_decode_animation()");
        MemorySegment framesPtr = framesRef.get(ValueLayout.ADDRESS, 0);
        MemorySegment delaysPtr = delaysRef.get(ValueLayout.ADDRESS, 0);
        if (framesPtr.address() == 0 || delaysPtr.address() == 0) {
            lib.imagify_webp_free(framesPtr);
            lib.imagify_webp_free(delaysPtr);
            throw new WebpException("imagify_webp_decode_animation() did not produce any pixel");
        }
        try {
            int frameCount = animation.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_FRAME_COUNT);
            int width = animation.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_ANIMATION_WIDTH);
            int height = animation.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_ANIMATION_HEIGHT);
            int loopCount = animation.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_LOOP_COUNT);
            long frameBytes = (long) width * height * 4;
            long totalBytes = frameBytes * frameCount;
            byte[] all = framesPtr.reinterpret(totalBytes).toArray(ValueLayout.JAVA_BYTE);
            List<BufferedImage> frames = splitFrames(all, frameCount, width, height, alpha);
            MemorySegment delays = delaysPtr.reinterpret((long) frameCount * Integer.BYTES);
            int[] delaysMs = new int[frameCount];
            for (int i = 0; i < frameCount; i++) {
                delaysMs[i] = delays.get(ValueLayout.JAVA_INT, (long) i * Integer.BYTES);
            }
            return new DecodedAnimation(new WebpLibrary.WebpAnimationInfo(frameCount, loopCount, width, height), frames, delaysMs);
        } finally {
            lib.imagify_webp_free(framesPtr);
            lib.imagify_webp_free(delaysPtr);
        }
    }

    private static List<BufferedImage> splitFrames(byte[] bytes, int frameCount, int width, int height, boolean alpha) {
        int frameBytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        List<BufferedImage> frames = new ArrayList<>(frameCount);
        for (int frame = 0; frame < frameCount; frame++) {
            byte[] abgr = Arrays.copyOfRange(bytes, frame * frameBytes, (frame + 1) * frameBytes);
            frames.add(normalise(AbgrPixels.toBufferedImage(abgr, width, height), alpha));
        }
        return frames;
    }

    // ----------------------------------------------------------------------- internals

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

    private static MemorySegment input(byte[] encoded, Arena arena) {
        MemorySegment memory = arena.allocate(Math.max(encoded.length, 1));
        if (encoded.length > 0) {
            memory.copyFrom(MemorySegment.ofArray(encoded));
        }
        return memory;
    }

    /**
     * Takes the buffer an encode entry point wrote into a byte array and hands the buffer back.
     *
     * @param lib the library, for the free
     * @param encoded receives the pointer the entry point wrote
     * @param encodedLength receives the length the entry point wrote
     * @param operation the entry point's name, for the message when it produced nothing
     * @return the encoded file
     * @throws WebpException when the entry point reported success but produced nothing
     */
    private static byte[] collect(WebpLibrary lib, MemorySegment encoded, MemorySegment encodedLength, String operation)
            throws WebpException {
        MemorySegment file = encoded.get(ValueLayout.ADDRESS, 0);
        int length = Math.toIntExact(encodedLength.get(ValueLayout.JAVA_LONG, 0));
        if (file.address() == 0 || length <= 0) {
            lib.imagify_webp_free(file);
            throw new WebpException(operation + " did not produce any output");
        }
        try {
            return file.reinterpret(length).toArray(ValueLayout.JAVA_BYTE);
        } finally {
            lib.imagify_webp_free(file);
        }
    }

    private static int clamp(int quality) {
        return Math.max(WebpLibrary.MIN_QUALITY, Math.min(WebpLibrary.MAX_QUALITY, quality));
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static void check(WebpLibrary lib, int status, MemorySegment message, String operation) throws WebpException {
        if (status == WebpLibrary.IMAGIFY_WEBP_OK) {
            return;
        }
        byte[] textBytes = new byte[WebpLibrary.MESSAGE_LENGTH];
        for (int i = 0; i < textBytes.length; i++) {
            textBytes[i] = message.get(ValueLayout.JAVA_BYTE, i);
        }
        int nullIdx = 0;
        while (nullIdx < textBytes.length && textBytes[nullIdx] != 0) {
            nullIdx++;
        }
        String text = new String(textBytes, 0, nullIdx);
        throw new WebpException(operation + " failed: " + (text.isBlank() ? statusName(status) : text));
    }

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
}
