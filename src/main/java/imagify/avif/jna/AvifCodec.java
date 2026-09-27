/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.avif.jna;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import imagify.avif.AvifException;
import imagify.avif.AvifImageInfo;

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.System.getLogger;

/**
 * Entry point to the {@code libavif} based AVIF codec.
 *
 * <p>The native library is loaded lazily, on first use, and failing to load it is never fatal: the
 * ImageIO service providers of this library then stay inert instead of breaking {@code ImageIO} for
 * every other format. Use {@link #isAvailable()} to find out.
 *
 * <p>Prebuilt shared libraries for Windows, macOS and Linux, in both 64 bit flavours, ship inside
 * this jar and are unpacked on demand, so installing anything is not required. Should this jar hold
 * no library for the current platform, a {@code libavif} found the usual way is used instead: point
 * the {@code jna.library.path} system property (or the platform specific {@code PATH} /
 * {@code LD_LIBRARY_PATH}) at the directory that holds it.
 *
 * <pre>
 * java -Djna.library.path=/usr/local/lib -cp ... YourApp
 * </pre>
 *
 * <p>Set {@code -Dimagify.avif.bundled=false} to ignore the bundled library and always look for one
 * installed on the system.
 *
 * <p>Supported {@code libavif} versions: 1.0.0 up to and including 1.4.x.
 */
public final class AvifCodec {

    private static final Logger log = getLogger(AvifCodec.class.getName());

    /** Oldest supported {@code libavif} version, as {@code major * 100 + minor}. */
    private static final int MIN_VERSION = 100;
    /** Newest supported {@code libavif} version, as {@code major * 100 + minor}. */
    private static final int MAX_VERSION = 104;

    /** Upper bound for the worker thread count handed to {@code libavif}. */
    private static final int MAX_THREADS = 16;

    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private static final Object LOCK = new Object();

    private static volatile boolean loaded;
    private static volatile AvifLibrary library;
    private static volatile String failure;

    private AvifCodec() {
        // utility class
    }

    /**
     * A decoded image together with the properties read from its container.
     *
     * @param image the pixels
     * @param info the properties reported by the container headers
     */
    public record DecodedImage(BufferedImage image, AvifImageInfo info) {
    }

    // ---------------------------------------------------------------------------- availability

    /**
     * Returns whether AVIF encoding and decoding can be used in this JVM.
     *
     * @return {@code true} when the native library was loaded and its version is supported
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
     * Returns the version of the loaded {@code libavif}, for example {@code "1.1.0 (abc1234)"}.
     *
     * @return the reported version, or {@code null} when the library is unavailable
     */
    public static String getVersion() {
        AvifLibrary lib = library();
        return lib == null ? null : lib.avifVersion();
    }

    /**
     * Returns the supported {@code libavif} version range.
     *
     * @return for example {@code "1.0 - 1.4"}
     */
    public static String supportedVersions() {
        return name(MIN_VERSION) + " - " + name(MAX_VERSION);
    }

    private static String name(int version) {
        return version / 100 + "." + version % 100;
    }

    /**
     * Returns the shared handle to the native library, loading it on first use.
     *
     * @return the library, or {@code null} when it is unavailable
     */
    public static AvifLibrary library() {
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
                log.log(Level.WARNING, "AVIF support is disabled: {0}. This jar ships a libavif for "
                        + "Windows, macOS and Linux on x64 and arm64, so either your platform is not "
                        + "one of those or the bundled library could not be unpacked. You can also "
                        + "install libavif {1} yourself and point -Djna.library.path at the directory "
                        + "that contains it.", failure, supportedVersions());
            }
            loaded = true;
            return library;
        }
    }

    /**
     * Returns the shared handle to the native library or fails.
     *
     * @return the library, never {@code null}
     * @throws AvifException when the native library is unavailable or unsupported
     */
    public static AvifLibrary requireLibrary() throws AvifException {
        AvifLibrary lib = library();
        if (lib == null) {
            throw new AvifException(failure == null ? "libavif is not available" : failure);
        }
        return lib;
    }

    private static AvifLibrary load() {
        if (Native.SIZE_T_SIZE != 8) {
            throw new IllegalStateException("a 64 bit JVM is required, got " + System.getProperty("os.arch"));
        }
        Path bundled = AvifNativeLibrary.extract();
        AvifLibrary lib = bundled == null
                ? Native.load(AvifLibrary.LIBRARY_NAME, AvifLibrary.class)
                : Native.load(bundled.toString(), AvifLibrary.class);

        String version = lib.avifVersion();
        int actual = parseVersion(version);
        if (actual < MIN_VERSION || actual > MAX_VERSION) {
            throw new IllegalStateException("unsupported libavif version: " + version
                    + " (supported: " + supportedVersions() + ")");
        }
        log.log(Level.DEBUG, "using libavif {0}{1}", version,
                bundled == null ? "" : " from " + bundled);
        return lib;
    }

    /**
     * Extracts the {@code major.minor} part of a {@code libavif} version string as
     * {@code major * 100 + minor}, so that versions compare numerically.
     *
     * @param version for example {@code "1.1.0 (a1b2c3d)"}
     * @return the comparable version
     * @throws IllegalStateException when the string does not start with a version number
     */
    static int parseVersion(String version) {
        Matcher matcher = VERSION.matcher(version);
        if (!matcher.find()) {
            throw new IllegalStateException("unrecognised libavif version: " + version);
        }
        return Integer.parseInt(matcher.group(1)) * 100 + Integer.parseInt(matcher.group(2));
    }

    private static String describe(Throwable t) {
        if (t instanceof UnsatisfiedLinkError || t instanceof NoClassDefFoundError) {
            return "cannot load the native library '" + AvifLibrary.LIBRARY_NAME + "': " + t.getMessage();
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /**
     * Returns the number of threads {@code libavif} may use.
     *
     * <p>Overridable with the {@code imagify.avif.threads} system property.
     *
     * @return a value between 1 and 16
     */
    public static int defaultThreads() {
        int threads = Integer.getInteger("imagify.avif.threads", Runtime.getRuntime().availableProcessors());
        return Math.max(1, Math.min(threads, MAX_THREADS));
    }

    // ---------------------------------------------------------------------------------- decode

    /**
     * Parses only the container headers of an AVIF file.
     *
     * @param encoded the complete AVIF file
     * @return the image properties
     * @throws AvifException when the library is unavailable or the file cannot be parsed
     */
    public static AvifImageInfo readHeader(byte[] encoded) throws AvifException {
        try (Session session = Session.open(encoded, defaultThreads(), false)) {
            return session.info();
        }
    }

    /**
     * Decodes an AVIF file using the default number of threads.
     *
     * @param encoded the complete AVIF file
     * @return the decoded image and its properties
     * @throws AvifException when the library is unavailable or the file cannot be decoded
     */
    public static DecodedImage decode(byte[] encoded) throws AvifException {
        return decode(encoded, defaultThreads());
    }

    /**
     * Decodes an AVIF file.
     *
     * <p>The pixels are always delivered as a {@link BufferedImage#TYPE_4BYTE_ABGR} image, whose
     * banks are the very {@code A, B, G, R} layout {@code libavif} fills in for
     * {@code AVIF_RGB_FORMAT_ABGR}. The alpha channel is fully opaque when the file carries no
     * alpha.
     *
     * @param encoded the complete AVIF file
     * @param threads the number of threads {@code libavif} may use
     * @return the decoded image and its properties
     * @throws AvifException when the library is unavailable or the file cannot be decoded
     */
    public static DecodedImage decode(byte[] encoded, int threads) throws AvifException {
        try (Session session = Session.open(encoded, threads, true)) {
            byte[] pixels = session.toAbgrPixels();
            return new DecodedImage(
                    AbgrPixels.toBufferedImage(pixels, session.width(), session.height()),
                    session.info());
        }
    }

    // ---------------------------------------------------------------------------------- encode

    /**
     * Encodes an image as AVIF using the default quality and speed.
     *
     * @param source the image to encode
     * @return the complete AVIF file
     * @throws AvifException when the library is unavailable or the image cannot be encoded
     */
    public static byte[] encode(RenderedImage source) throws AvifException {
        return encode(source, AvifLibrary.DEFAULT_QUALITY, AvifLibrary.DEFAULT_SPEED);
    }

    /**
     * Encodes an image as AVIF.
     *
     * @param source the image to encode; any {@link RenderedImage} is accepted
     * @param quality 0 (smallest) to 100 (lossless)
     * @param speed 0 (slowest, best quality) to 10 (fastest, worst quality)
     * @return the complete AVIF file
     * @throws AvifException when the library is unavailable or the image cannot be encoded
     */
    public static byte[] encode(RenderedImage source, int quality, int speed) throws AvifException {
        AvifLibrary lib = requireLibrary();
        if (source == null) {
            throw new AvifException("no image to encode");
        }
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) {
            throw new AvifException("cannot encode a " + width + "x" + height + " image");
        }
        byte[] pixels = AbgrPixels.toAbgrBytes(source, 0, 0, width, height, 1, 1);

        // 8 bit 4:4:4 keeps the round trip through Java's 8 bit colour model free of surprises.
        // Chroma subsampling is a file size decision, not a codec one.
        AvifImage image = lib.avifImageCreate(width, height, 8, AvifLibrary.AVIF_PIXEL_FORMAT_YUV444);
        if (image == null) {
            throw new AvifException("avifImageCreate() returned NULL for " + width + "x" + height);
        }
        try {
            image.read();
            toYuv(lib, image, pixels);
            return finish(lib, image, quality, speed);
        } finally {
            lib.avifImageDestroy(image);
        }
    }

    private static void toYuv(AvifLibrary lib, AvifImage image, byte[] pixels) throws AvifException {
        AvifRGBImage rgb = new AvifRGBImage();
        lib.avifRGBImageSetDefaults(rgb, image);
        // A TYPE_4BYTE_ABGR raster keeps its banks in A, B, G, R order, so the buffer can be handed
        // to libavif as is. See AbgrPixels for why the RGBA format would swap red and blue.
        rgb.format = AvifLibrary.AVIF_RGB_FORMAT_ABGR;
        rgb.depth = 8;
        // avifRGBImageSetDefaults() hard codes RGBA/8, so the stride has to be derived again.
        rgb.rowBytes = image.width * lib.avifRGBImagePixelSize(rgb);

        check(lib, lib.avifRGBImageAllocatePixels(rgb), "avifRGBImageAllocatePixels()");
        try {
            rgb.read();
            rgb.pixels.write(0, pixels, 0, pixels.length);
            check(lib, lib.avifImageRGBToYUV(image, rgb), "avifImageRGBToYUV()");
        } finally {
            lib.avifRGBImageFreePixels(rgb);
        }
    }

    private static byte[] finish(AvifLibrary lib, AvifImage image, int quality, int speed) throws AvifException {
        AvifEncoder encoder = lib.avifEncoderCreate();
        if (encoder == null) {
            throw new AvifException("avifEncoderCreate() returned NULL");
        }
        try {
            encoder.maxThreads = defaultThreads();
            encoder.speed = speed;
            encoder.quality = quality;
            // Alpha planes are cheap, and lossy alpha is the single most visible AVIF artefact.
            encoder.qualityAlpha = AvifLibrary.AVIF_QUALITY_LOSSLESS;
            encoder.timescale = 1;
            encoder.write();

            check(lib, lib.avifEncoderAddImage(encoder, image, 1, AvifLibrary.AVIF_ADD_IMAGE_FLAG_SINGLE),
                    "avifEncoderAddImage()");

            AvifRWData output = new AvifRWData();
            try {
                check(lib, lib.avifEncoderFinish(encoder, output), "avifEncoderFinish()");
                byte[] encoded = output.toByteArray();
                if (encoded == null) {
                    throw new AvifException("avifEncoderFinish() did not produce any output");
                }
                return encoded;
            } finally {
                lib.avifRWDataFree(output);
            }
        } finally {
            lib.avifEncoderDestroy(encoder);
        }
    }

    // ------------------------------------------------------------------------- encode

    /**
     * Encodes a sequence of frames as an animated AVIF file.
     *
     * @param frames       the frames, all of the same size, at least two
     * @param durationsMs  how long each frame is shown, in milliseconds, one entry per frame
     * @param quality      0 (smallest) to 100 (lossless)
     * @param loopCount    how often the animation repeats, 0 meaning forever
     * @return the complete animated AVIF file
     * @throws AvifException when the library is unavailable or the frames cannot be encoded
     */
    public static byte[] encodeAnimation(List<BufferedImage> frames, int[] durationsMs,
            int quality, int loopCount) throws AvifException {
        AvifLibrary lib = requireLibrary();
        if (frames == null || frames.size() < 2) {
            throw new AvifException("an animation needs at least two frames, got "
                    + (frames == null ? 0 : frames.size()));
        }
        if (durationsMs == null || durationsMs.length != frames.size()) {
            throw new AvifException("expected one delay per frame: " + frames.size()
                    + " frames but " + (durationsMs == null ? "no" : durationsMs.length + "") + " delays");
        }
        int width = frames.get(0).getWidth();
        int height = frames.get(0).getHeight();
        if (width <= 0 || height <= 0) {
            throw new AvifException("cannot encode a " + width + "x" + height + " animation");
        }
        for (BufferedImage frame : frames) {
            if (frame == null || frame.getWidth() != width || frame.getHeight() != height) {
                throw new AvifException("every frame must be " + width + "x" + height);
            }
        }

        AvifEncoder encoder = lib.avifEncoderCreate();
        if (encoder == null) {
            throw new AvifException("avifEncoderCreate() returned NULL");
        }
        try {
            encoder.maxThreads = defaultThreads();
            encoder.speed = AvifLibrary.DEFAULT_SPEED;
            encoder.quality = quality;
            encoder.qualityAlpha = AvifLibrary.AVIF_QUALITY_LOSSLESS;
            encoder.timescale = 1000;
            encoder.repetitionCount = loopCount;
            encoder.write();

            for (int i = 0; i < frames.size(); i++) {
                BufferedImage frame = frames.get(i);
                byte[] pixels = AbgrPixels.toAbgrBytes(frame, 0, 0, width, height, 1, 1);
                AvifImage image = lib.avifImageCreate(width, height, 8, AvifLibrary.AVIF_PIXEL_FORMAT_YUV444);
                try {
                    image.read();
                    toYuv(lib, image, pixels);
                    long duration = Math.round((long) durationsMs[i] * encoder.timescale / 1000.0);
                    check(lib, lib.avifEncoderAddImage(encoder, image, duration, AvifLibrary.AVIF_ADD_IMAGE_FLAG_NONE),
                            "avifEncoderAddImage() at frame " + i);
                } finally {
                    lib.avifImageDestroy(image);
                }
            }

            AvifRWData output = new AvifRWData();
            try {
                check(lib, lib.avifEncoderFinish(encoder, output), "avifEncoderFinish()");
                byte[] encoded = output.toByteArray();
                if (encoded == null) {
                    throw new AvifException("avifEncoderFinish() did not produce any output");
                }
                return encoded;
            } finally {
                lib.avifRWDataFree(output);
            }
        } finally {
            lib.avifEncoderDestroy(encoder);
        }
    }

    /**
     * Decodes all frames of an animated AVIF file.
     *
     * @param data the encoded AVIF bytes
     * @return the list of decoded frames
     * @throws AvifException when the data is not a valid AVIF or cannot be decoded
     */
    public static List<BufferedImage> decodeAnimation(byte[] data) throws AvifException {
        AvifLibrary lib = requireLibrary();
        if (data == null || data.length == 0) {
            throw new AvifException("no input data");
        }
        if (!isAvif(data)) {
            throw new AvifException("not an AVIF file");
        }
        if (!isAvailable()) {
            throw new AvifException("libavif is not available");
        }

        Memory buffer = new Memory(Math.max(data.length, 1));
        AvifDecoder decoder = null;
        try {
            buffer.write(0, data, 0, data.length);
            decoder = lib.avifDecoderCreate();
            if (decoder == null) {
                throw new AvifException("avifDecoderCreate() returned NULL");
            }
            decoder.maxThreads = defaultThreads();
            decoder.imageCountLimit = 0;
            decoder.ignoreExif = AvifLibrary.AVIF_TRUE;
            decoder.ignoreXMP = AvifLibrary.AVIF_TRUE;
            decoder.write();
            check(lib, lib.avifDecoderSetIOMemory(decoder, buffer, data.length), "avifDecoderSetIOMemory()");
            check(lib, lib.avifDecoderParse(decoder), "avifDecoderParse()");

            int frameCount = decoder.imageCount;
            if (frameCount <= 0) {
                throw new AvifException("no frames found in AVIF file");
            }
            List<BufferedImage> frames = new ArrayList<>(frameCount);
            for (int i = 0; i < frameCount; i++) {
                check(lib, lib.avifDecoderNextImage(decoder), "avifDecoderNextImage() at " + i);
                decoder.read();
                AvifImage image = decoder.image;
                if (image == null || image.width <= 0 || image.height <= 0) {
                    throw new AvifException("invalid frame at index " + i);
                }
                int w = image.width;
                int h = image.height;
                AvifRGBImage rgb = new AvifRGBImage();
                lib.avifRGBImageSetDefaults(rgb, image);
                rgb.format = AvifLibrary.AVIF_RGB_FORMAT_ABGR;
                rgb.depth = 8;
                rgb.rowBytes = w * lib.avifRGBImagePixelSize(rgb);
                check(lib, lib.avifRGBImageAllocatePixels(rgb), "avifRGBImageAllocatePixels()");
                try {
                    check(lib, lib.avifImageYUVToRGB(image, rgb), "avifImageYUVToRGB()");
                    byte[] pixels = rgb.getPixels();
                    if (pixels == null) {
                        throw new AvifException("avifImageYUVToRGB() did not produce pixels");
                    }
                    frames.add(AbgrPixels.toBufferedImage(pixels, w, h));
                } finally {
                    lib.avifRGBImageFreePixels(rgb);
                }
            }
            return frames;
        } finally {
            if (decoder != null) {
                lib.avifDecoderDestroy(decoder);
            }
            buffer.close();
        }
    }

    // --------------------------------------------------------------------------------- session

    /**
     * Owns the native memory and the decoder for the duration of one decode operation.
     */
    private static final class Session implements AutoCloseable {

        private final AvifLibrary lib;
        private final Memory encoded;
        private final AvifDecoder decoder;
        private final AvifImage image;
        private final AvifImageInfo info;

        private Session(AvifLibrary lib, Memory encoded, AvifDecoder decoder, AvifImage image) {
            this.lib = lib;
            this.encoded = encoded;
            this.decoder = decoder;
            this.image = image;
            this.info = describe(image);
        }

        static Session open(byte[] encoded, int threads, boolean decodePixels) throws AvifException {
            AvifLibrary lib = requireLibrary();
            if (encoded == null) {
                throw new AvifException("no input");
            }
            if (!isAvif(encoded)) {
                throw new AvifException("not an AVIF file: no avif or avis brand in the ftyp box");
            }
            Memory buffer = new Memory(Math.max(encoded.length, 1));
            AvifDecoder decoder = null;
            try {
                buffer.write(0, encoded, 0, encoded.length);

                decoder = lib.avifDecoderCreate();
                if (decoder == null) {
                    throw new AvifException("avifDecoderCreate() returned NULL");
                }
                decoder.maxThreads = threads;
                decoder.ignoreExif = AvifLibrary.AVIF_TRUE;
                decoder.ignoreXMP = AvifLibrary.AVIF_TRUE;
                // These two checks reject files that older, and still widely deployed, encoders emit.
                decoder.strictFlags &=
                        ~(AvifLibrary.AVIF_STRICT_CLAP_VALID | AvifLibrary.AVIF_STRICT_PIXI_REQUIRED);
                decoder.write();

                check(lib, lib.avifDecoderSetIOMemory(decoder, buffer, encoded.length), "avifDecoderSetIOMemory()");
                check(lib, lib.avifDecoderParse(decoder), "avifDecoderParse()");
                if (decodePixels) {
                    check(lib, lib.avifDecoderNextImage(decoder), "avifDecoderNextImage()");
                }
                decoder.read();

                AvifImage image = decoder.image;
                if (image == null || image.getPointer() == null) {
                    throw new AvifException("the file does not contain a decodable image");
                }
                image.read();
                if (image.width <= 0 || image.height <= 0) {
                    throw new AvifException("the file does not contain a decodable image");
                }
                return new Session(lib, buffer, decoder, image);
            } catch (AvifException | RuntimeException e) {
                if (decoder != null) {
                    lib.avifDecoderDestroy(decoder);
                }
                buffer.close();
                throw e;
            }
        }

        int width() {
            return image.width;
        }

        int height() {
            return image.height;
        }

        AvifImageInfo info() {
            return info;
        }

        byte[] toAbgrPixels() throws AvifException {
            AvifRGBImage rgb = new AvifRGBImage();
            lib.avifRGBImageSetDefaults(rgb, image);
            rgb.format = AvifLibrary.AVIF_RGB_FORMAT_ABGR;
            rgb.depth = 8;
            rgb.maxThreads = Math.max(1, image.width * image.height > (1 << 22) ? defaultThreads() : 1);
            rgb.rowBytes = image.width * lib.avifRGBImagePixelSize(rgb);

            check(lib, lib.avifRGBImageAllocatePixels(rgb), "avifRGBImageAllocatePixels()");
            try {
                check(lib, lib.avifImageYUVToRGB(image, rgb), "avifImageYUVToRGB()");
                byte[] pixels = rgb.getPixels();
                if (pixels == null) {
                    throw new AvifException("avifImageYUVToRGB() did not produce any pixel");
                }
                return pixels;
            } finally {
                lib.avifRGBImageFreePixels(rgb);
            }
        }

        @Override
        public void close() {
            lib.avifDecoderDestroy(decoder);
            encoded.close();
        }

        private static AvifImageInfo describe(AvifImage image) {
            int rotation = 0;
            if ((image.transformFlags & AvifLibrary.AVIF_TRANSFORM_IROT) != 0) {
                rotation = Math.floorMod(image.irot.angle, 4) * 90;
            }
            return new AvifImageInfo(
                    image.width,
                    image.height,
                    image.depth,
                    image.yuvFormat,
                    image.yuvRange,
                    image.yuvChromaSamplePosition,
                    image.colorPrimaries,
                    image.transferCharacteristics,
                    image.matrixCoefficients,
                    image.alphaPlane != null,
                    (int) image.icc.size,
                    (int) image.exif.size,
                    (int) image.xmp.size,
                    rotation,
                    (image.transformFlags & AvifLibrary.AVIF_TRANSFORM_IMIR) != 0);
        }
    }

    private static void check(AvifLibrary lib, int result, String operation) throws AvifException {
        if (result != AvifLibrary.AVIF_RESULT_OK) {
            String name;
            try {
                name = lib.avifResultToString(result);
            } catch (RuntimeException e) {
                name = "avifResult " + result;
            }
            throw new AvifException(operation + " failed: " + name);
        }
    }

    // ------------------------------------------------------------------------- format sniffing

    /**
     * Tests whether the given bytes are an AVIF file by looking for the {@code avif} or {@code avis}
     * brand in the leading {@code ftyp} box.
     *
     * <p>The check is pure Java and therefore also works when the native library is missing, which
     * lets {@code ImageIO} still recognise AVIF files.
     *
     * @param data the leading bytes of a file, at least 12 of them
     * @return {@code true} when the data looks like an AVIF file
     */
    public static boolean isAvif(byte[] data) {
        if (data == null || data.length < 12 || !"ftyp".equals(readAscii(data, 4, 4))) {
            return false;
        }
        long boxSize = readUInt32(data, 0);
        int majorBrand;
        if (boxSize == 1) {
            // A size of 1 means the real size follows as a 64 bit value and the brands move down by 8.
            if (data.length < 16) {
                return false;
            }
            boxSize = readUInt64(data, 8);
            majorBrand = 16;
        } else {
            majorBrand = 8;
        }
        // A size of 0 means the box runs to the end of the file; a size beyond it means the leading
        // bytes were clipped, so the brand list is all that can be trusted.
        int end = boxSize == 0 ? data.length : (int) Math.min(boxSize, data.length);
        if (end < majorBrand + 8) {
            return false;
        }
        if (isAvifBrand(readAscii(data, majorBrand, 4))) {
            return true;
        }
        for (int at = majorBrand + 8; at + 4 <= end; at += 4) {
            if (isAvifBrand(readAscii(data, at, 4))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAvifBrand(String brand) {
        return "avif".equals(brand) || "avis".equals(brand);
    }

    private static long readUInt32(byte[] data, int offset) {
        return ((long) (data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }

    private static long readUInt64(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[offset + i] & 0xff);
        }
        return value;
    }

    private static String readAscii(byte[] data, int offset, int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = (char) (data[offset + i] & 0xff);
        }
        return new String(chars);
    }

    // ------------------------------------------------------------------------- enum name lookups

    /**
     * @param yuvFormat an {@code AVIF_PIXEL_FORMAT_*} value
     * @return the symbolic name, for example {@code "YUV420"}
     */
    public static String pixelFormatName(int yuvFormat) {
        return switch (yuvFormat) {
            case AvifLibrary.AVIF_PIXEL_FORMAT_NONE -> "None";
            case AvifLibrary.AVIF_PIXEL_FORMAT_YUV444 -> "YUV444";
            case AvifLibrary.AVIF_PIXEL_FORMAT_YUV422 -> "YUV422";
            case AvifLibrary.AVIF_PIXEL_FORMAT_YUV420 -> "YUV420";
            case AvifLibrary.AVIF_PIXEL_FORMAT_YUV400 -> "YUV400";
            default -> "Unknown(" + yuvFormat + ")";
        };
    }

    /**
     * @param value an {@code AVIF_RANGE_*} value
     * @return the symbolic name
     */
    public static String rangeName(int value) {
        return switch (value) {
            case AvifLibrary.AVIF_RANGE_LIMITED -> "Limited";
            case AvifLibrary.AVIF_RANGE_FULL -> "Full";
            default -> "Unknown(" + value + ")";
        };
    }

    /**
     * @param value an {@code AVIF_CHROMA_SAMPLE_POSITION_*} value
     * @return the symbolic name
     */
    public static String chromaSamplePositionName(int value) {
        return switch (value) {
            case AvifLibrary.AVIF_CHROMA_SAMPLE_POSITION_UNKNOWN -> "Unknown";
            case AvifLibrary.AVIF_CHROMA_SAMPLE_POSITION_VERTICAL -> "Vertical";
            case AvifLibrary.AVIF_CHROMA_SAMPLE_POSITION_COLOCATED -> "Colocated";
            default -> "Unknown(" + value + ")";
        };
    }

    /**
     * @param value an {@code AVIF_COLOR_PRIMARIES_*} value
     * @return the symbolic name
     */
    public static String colorPrimariesName(int value) {
        return switch (value) {
            case AvifLibrary.AVIF_COLOR_PRIMARIES_UNKNOWN -> "Unknown";
            case AvifLibrary.AVIF_COLOR_PRIMARIES_BT709 -> "BT709";
            case AvifLibrary.AVIF_COLOR_PRIMARIES_UNSPECIFIED -> "Unspecified";
            case AvifLibrary.AVIF_COLOR_PRIMARIES_BT470M -> "BT470M";
            case AvifLibrary.AVIF_COLOR_PRIMARIES_BT470BG -> "BT470BG";
            case AvifLibrary.AVIF_COLOR_PRIMARIES_BT601 -> "BT601";
            case AvifLibrary.AVIF_COLOR_PRIMARIES_SMPTE240 -> "SMPTE240";
            case AvifLibrary.AVIF_COLOR_PRIMARIES_GENERIC_FILM -> "GenericFilm";
            case AvifLibrary.AVIF_COLOR_PRIMARIES_BT2020 -> "BT2020";
            case AvifLibrary.AVIF_COLOR_PRIMARIES_XYZ -> "XYZ";
            case AvifLibrary.AVIF_COLOR_PRIMARIES_SMPTE431 -> "SMPTE431";
            case AvifLibrary.AVIF_COLOR_PRIMARIES_SMPTE432 -> "SMPTE432";
            case AvifLibrary.AVIF_COLOR_PRIMARIES_EBU3213 -> "EBU3213";
            default -> "Unknown(" + value + ")";
        };
    }

    /**
     * @param value an {@code AVIF_TRANSFER_CHARACTERISTICS_*} value
     * @return the symbolic name
     */
    public static String transferName(int value) {
        return switch (value) {
            case AvifLibrary.AVIF_TRANSFER_CHARACTERISTICS_UNKNOWN -> "Unknown";
            case AvifLibrary.AVIF_TRANSFER_CHARACTERISTICS_BT709 -> "BT709";
            case AvifLibrary.AVIF_TRANSFER_CHARACTERISTICS_UNSPECIFIED -> "Unspecified";
            case AvifLibrary.AVIF_TRANSFER_CHARACTERISTICS_BT470M -> "BT470M";
            case AvifLibrary.AVIF_TRANSFER_CHARACTERISTICS_BT470BG -> "BT470BG";
            case AvifLibrary.AVIF_TRANSFER_CHARACTERISTICS_BT601 -> "BT601";
            case AvifLibrary.AVIF_TRANSFER_CHARACTERISTICS_SMPTE240 -> "SMPTE240";
            case AvifLibrary.AVIF_TRANSFER_CHARACTERISTICS_LINEAR -> "Linear";
            case AvifLibrary.AVIF_TRANSFER_CHARACTERISTICS_LOG100 -> "Log100";
            case AvifLibrary.AVIF_TRANSFER_CHARACTERISTICS_LOG100_SQRT10 -> "Log100Sqrt10";
            case AvifLibrary.AVIF_TRANSFER_CHARACTERISTICS_IEC61966 -> "IEC61966";
            case AvifLibrary.AVIF_TRANSFER_CHARACTERISTICS_BT1361 -> "BT1361";
            case AvifLibrary.AVIF_TRANSFER_CHARACTERISTICS_SRGB -> "SRGB";
            case AvifLibrary.AVIF_TRANSFER_CHARACTERISTICS_BT2020_10BIT -> "BT2020_10Bit";
            case AvifLibrary.AVIF_TRANSFER_CHARACTERISTICS_BT2020_12BIT -> "BT2020_12Bit";
            case AvifLibrary.AVIF_TRANSFER_CHARACTERISTICS_PQ -> "PQ";
            case AvifLibrary.AVIF_TRANSFER_CHARACTERISTICS_SMPTE428 -> "SMPTE428";
            case AvifLibrary.AVIF_TRANSFER_CHARACTERISTICS_HLG -> "HLG";
            default -> "Unknown(" + value + ")";
        };
    }

    /**
     * @param value an {@code AVIF_MATRIX_COEFFICIENTS_*} value
     * @return the symbolic name
     */
    public static String matrixCoefficientsName(int value) {
        return switch (value) {
            case AvifLibrary.AVIF_MATRIX_COEFFICIENTS_IDENTITY -> "Identity";
            case AvifLibrary.AVIF_MATRIX_COEFFICIENTS_BT709 -> "BT709";
            case AvifLibrary.AVIF_MATRIX_COEFFICIENTS_UNSPECIFIED -> "Unspecified";
            case AvifLibrary.AVIF_MATRIX_COEFFICIENTS_FCC -> "FCC";
            case AvifLibrary.AVIF_MATRIX_COEFFICIENTS_BT470BG -> "BT470BG";
            case AvifLibrary.AVIF_MATRIX_COEFFICIENTS_BT601 -> "BT601";
            case AvifLibrary.AVIF_MATRIX_COEFFICIENTS_SMPTE240 -> "SMPTE240";
            case AvifLibrary.AVIF_MATRIX_COEFFICIENTS_YCGCO -> "YCGCO";
            case AvifLibrary.AVIF_MATRIX_COEFFICIENTS_BT2020_NCL -> "BT2020_NCL";
            case AvifLibrary.AVIF_MATRIX_COEFFICIENTS_BT2020_CL -> "BT2020_CL";
            case AvifLibrary.AVIF_MATRIX_COEFFICIENTS_SMPTE2085 -> "SMPTE2085";
            case AvifLibrary.AVIF_MATRIX_COEFFICIENTS_CHROMA_DERIVED_NCL -> "ChromaDerived_NCL";
            case AvifLibrary.AVIF_MATRIX_COEFFICIENTS_CHROMA_DERIVED_CL -> "ChromaDerived_CL";
            case AvifLibrary.AVIF_MATRIX_COEFFICIENTS_ICTCP -> "ICtCp";
            default -> "Unknown(" + value + ")";
        };
    }
}
