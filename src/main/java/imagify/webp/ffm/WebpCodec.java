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

import imagify.pixels.AbgrPixels;
import imagify.webp.WebpException;
import imagify.webp.WebpImageInfo;

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

import static java.lang.System.getLogger;

/**
 * Entry point to the {@code libwebp} based WebP codec using Java's Foreign Function &amp; Memory API (JEP 454).
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
 * instead: point the {@code java.library.path} system property (or the platform specific
 * {@code PATH} / {@code LD_LIBRARY_PATH}) at the directory that holds it.
 *
 * <pre>
 * java -Djava.library.path=/usr/local/lib -cp ... YourApp
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

    public static final int DEFAULT_QUALITY = WebpLibrary.DEFAULT_QUALITY;

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
    private static final int DEFAULT_COMPRESSION_METHOD = 4;

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
                log.log(Level.WARNING, "The WebP codec is disabled: {0}. This jar ships a WebP "
                        + "codec for Windows, macOS and Linux on x64 and arm64, so either your "
                        + "platform is not one of those or the bundled library could not be "
                        + "unpacked. You can also point -Djava.library.path at a directory that "
                        + "holds one. ImageIO falls back to the JDK's own WebP support either "
                        + "way.", failure);
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
        Path bundled = WebpNativeLibrary.extract();
        WebpLibrary lib = bundled == null
                ? loadSystem()
                : loadBundled(bundled);

        String abi = lib.imagify_webp_abi_version();
        if (!WebpLibrary.ABI_VERSION.equals(abi)) {
            throw new IllegalStateException("the WebP library speaks ABI version " + abi
                    + " but this jar speaks " + WebpLibrary.ABI_VERSION);
        }
        log.log(Level.DEBUG, "using libwebp {0}{1}", lib.imagify_webp_webp_version(),
                bundled == null ? " from system" : " from " + bundled);
        return lib;
    }

    private static WebpLibrary loadSystem() {
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = linker.defaultLookup();
        return new WebpLibrary(lookup);
    }

    private static WebpLibrary loadBundled(Path bundled) {
        System.load(bundled.toString());
        Linker linker = Linker.nativeLinker();
        // The symbols are resolved once and the downcall handles that capture them are then held
        // for as long as this class lives, which outlives the thread that loaded the library and is
        // called from every one of them. A confined arena would tie the symbols to the loading
        // thread, and every call from another thread would fail on it, so the library is opened
        // against the global arena instead. Nothing is ever unmapped this way, which is what a
        // process wide codec wants anyway.
        SymbolLookup lookup = linker.defaultLookup()
                .or(SymbolLookup.libraryLookup(bundled, Arena.global()));
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
            int status = lib.imagify_webp_read_features(input, encoded.length, features, message,
                    WebpLibrary.MESSAGE_LENGTH);
            check(lib, status, message, "imagify_webp_read_features()");
            int width = features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_WIDTH);
            int height = features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_HEIGHT);
            int hasAlpha = features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_HAS_ALPHA);
            int hasAnimation = features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_HAS_ANIMATION);
            int format = features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_FORMAT);
            if (hasAnimation == 0) {
                return new WebpImageInfo(width, height, hasAlpha != 0,
                        false, format, 1, 0);
            }
            MemorySegment animation = arena.allocate(WebpLibrary.WEBP_ANIMATION_LAYOUT);
            MemorySegment delaysRef = arena.allocate(ValueLayout.ADDRESS);
            check(lib, lib.imagify_webp_read_animation(input, encoded.length, animation, delaysRef,
                    message, WebpLibrary.MESSAGE_LENGTH), message, "imagify_webp_read_animation()");
            MemorySegment delaysPtr = delaysRef.get(ValueLayout.ADDRESS, 0);
            int frameCount = animation.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_FRAME_COUNT);
            int loopCount = animation.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_LOOP_COUNT);
            lib.imagify_webp_free(delaysPtr);
            return new WebpImageInfo(width, height, hasAlpha != 0,
                    true, format, frameCount, loopCount);
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
        return (data[offset + 4] & 0xFF) | (data[offset + 5] & 0xFF) << 8
                | (data[offset + 6] & 0xFF) << 16 | (data[offset + 7] & 0xFF) << 24;
    }

    public static int losslessHeaderLength() {
        return LOSSLESS_HEADER_LENGTH;
    }

    // ----------------------------------------------------------------------------------- encoding

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
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pixels = arena.allocate(abgr.length);
            pixels.copyFrom(MemorySegment.ofArray(abgr));
            MemorySegment message = arena.allocate(WebpLibrary.MESSAGE_LENGTH);
            MemorySegment encoded = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment encodedLength = arena.allocate(ValueLayout.JAVA_LONG);
            int status = lib.imagify_webp_encode(pixels, width, height, clamp(quality),
                        lossless ? 1 : 0, DEFAULT_COMPRESSION_METHOD, encoded, encodedLength, message,
                        WebpLibrary.MESSAGE_LENGTH);
            check(lib, status, message, "imagify_webp_encode()");
            MemorySegment file = encoded.get(ValueLayout.ADDRESS, 0);
            int length = Math.toIntExact(encodedLength.get(ValueLayout.JAVA_LONG, 0));
            if (file.address() == 0 || length <= 0) {
                lib.imagify_webp_free(file);
                throw new WebpException("imagify_webp_encode() did not produce any output");
            }
            try {
                return file.reinterpret(length).toArray(ValueLayout.JAVA_BYTE);
            } finally {
                lib.imagify_webp_free(file);
            }
        }
    }

    public static byte[] encodeAnimation(List<BufferedImage> frames, int[] delaysMs,
            int quality, boolean lossless, int loopCount) throws WebpException {
        return encodeAnimation(frames, delaysMs, quality, lossless, loopCount, DEFAULT_COMPRESSION_METHOD);
    }

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

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pixels = arena.allocate(abgr.length);
            pixels.copyFrom(MemorySegment.ofArray(abgr));
            MemorySegment delays = arena.allocate((long) count * Integer.BYTES);
            for (int i = 0; i < count; i++) {
                delays.set(ValueLayout.JAVA_INT, (long) i * Integer.BYTES, delaysMs[i]);
            }
            MemorySegment message = arena.allocate(WebpLibrary.MESSAGE_LENGTH);
            MemorySegment encoded = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment encodedLength = arena.allocate(ValueLayout.JAVA_LONG);
            int status = lib.imagify_webp_encode_animation(pixels, count, width, height, delays,
                        clamp(quality), lossless ? 1 : 0, loopCount, compressionMethod, encoded,
                        encodedLength, message, WebpLibrary.MESSAGE_LENGTH);
            check(lib, status, message, "imagify_webp_encode_animation()");
            MemorySegment file = encoded.get(ValueLayout.ADDRESS, 0);
            int length = Math.toIntExact(encodedLength.get(ValueLayout.JAVA_LONG, 0));
            if (file.address() == 0 || length <= 0) {
                lib.imagify_webp_free(file);
                throw new WebpException("imagify_webp_encode_animation() did not produce any output");
            }
            try {
                return file.reinterpret(length).toArray(ValueLayout.JAVA_BYTE);
            } finally {
                lib.imagify_webp_free(file);
            }
        }
    }

    // ----------------------------------------------------------------------------------- decoding

    public static BufferedImage decode(byte[] encoded) throws WebpException {
        WebpLibrary lib = requireLibrary();
        Objects.requireNonNull(encoded, "no data to decode");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = input(encoded, arena);
            MemorySegment message = arena.allocate(WebpLibrary.MESSAGE_LENGTH);
            MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment outLength = arena.allocate(ValueLayout.JAVA_LONG);
            MemorySegment features = arena.allocate(WebpLibrary.WEBP_FEATURES_LAYOUT);
            int status = lib.imagify_webp_decode(input, encoded.length, out, outLength, features,
                        message, WebpLibrary.MESSAGE_LENGTH);
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

    public record DecodedWebp(WebpImageInfo info, List<BufferedImage> frames, int[] delaysMs) {
        public DecodedWebp {
            frames = List.copyOf(frames);
            delaysMs = delaysMs == null ? null : delaysMs.clone();
        }
    }

    public static DecodedWebp decodeFile(byte[] encoded) throws WebpException {
        WebpLibrary lib = requireLibrary();
        Objects.requireNonNull(encoded, "no data to decode");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment input = input(encoded, arena);
            MemorySegment message = arena.allocate(WebpLibrary.MESSAGE_LENGTH);
            MemorySegment features = arena.allocate(WebpLibrary.WEBP_FEATURES_LAYOUT);
            int status = lib.imagify_webp_read_features(input, encoded.length, features, message,
                    WebpLibrary.MESSAGE_LENGTH);
            check(lib, status, message, "imagify_webp_read_features()");
            int hasAlpha = features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_HAS_ALPHA);
            int width = features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_WIDTH);
            int height = features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_HEIGHT);
            int format = features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_FORMAT);
            int hasAnimation = features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_HAS_ANIMATION);
            if (hasAnimation == 0) {
                WebpImageInfo info = new WebpImageInfo(width, height, hasAlpha != 0,
                        false, format, 1, 0);
                return new DecodedWebp(info, List.of(decode(encoded)), null);
            }
            DecodedAnimation animation = decodeAnimationData(lib, input, encoded.length, message, features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_HAS_ALPHA) != 0, arena);
            WebpImageInfo info = new WebpImageInfo(width, height, hasAlpha != 0, true, format,
                    animation.info.frameCount(), animation.info.loopCount());
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
            int status = lib.imagify_webp_read_features(input, encoded.length, features, message,
                    WebpLibrary.MESSAGE_LENGTH);
            check(lib, status, message, "imagify_webp_read_features()");
            DecodedAnimation animation = decodeAnimationData(lib, input, encoded.length, message,
                    features.get(ValueLayout.JAVA_INT, WebpLibrary.OFFSET_HAS_ALPHA) != 0, arena);
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
            check(lib, lib.imagify_webp_read_animation(input, encoded.length, animation, delaysRef,
                    message, WebpLibrary.MESSAGE_LENGTH), message, "imagify_webp_read_animation()");
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
            return new int[][] { { frameCount, loopCount }, timings };
        }
    }

    private record DecodedAnimation(WebpLibrary.WebpAnimationInfo info, List<BufferedImage> frames,
            int[] delays) {}

    private static DecodedAnimation decodeAnimationData(WebpLibrary lib, MemorySegment input, int length,
            MemorySegment message, boolean alpha, Arena arena) throws WebpException {
        MemorySegment animation = arena.allocate(WebpLibrary.WEBP_ANIMATION_LAYOUT);
        MemorySegment framesRef = arena.allocate(ValueLayout.ADDRESS);
        MemorySegment delaysRef = arena.allocate(ValueLayout.ADDRESS);
        int status = lib.imagify_webp_decode_animation(input, length, animation, framesRef,
                    delaysRef, message, WebpLibrary.MESSAGE_LENGTH);
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

    private static int clamp(int quality) {
        return Math.max(WebpLibrary.MIN_QUALITY, Math.min(WebpLibrary.MAX_QUALITY, quality));
    }

    private static void check(WebpLibrary lib, int status, MemorySegment message, String operation)
            throws WebpException {
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
        throw new WebpException(operation + " failed: "
                + (text.isBlank() ? statusName(status) : text));
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
