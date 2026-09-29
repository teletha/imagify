/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.webp;

import static java.lang.System.*;

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Locale;

/**
 * The WebP codec, whichever backend of it is in use.
 *
 * <p>This is the entry point for everything this library does with WebP: the {@code ImageIO}
 * service providers of {@code imagify.webp}, the direct calls {@link imagify.ImageReader} and
 * {@link imagify.ImageWriter} make, and the encode and decode below. Two backends sit behind it
 * and both ship in this jar, so a program that reaches {@code WebpCodec} gets WebP either way and
 * the choice is a switch rather than a decision about which dependency to declare:
 *
 * <dl>
 * <dt>{@link Backend#FFM}</dt>
 * <dd>{@code libwebp} bound with Java's Foreign Function &amp; Memory API, and the one that
 * runs unless told otherwise. It is the default because it needs no third party jar, its
 * prebuilt libraries are unpacked on demand from this one, and it is the only backend that
 * can be told how hard to try. On JDK 24 and newer a program using it from the class path is
 * asked to allow native access; nothing fails without it, but the JDK warns on every run
 * and will block the call in a later release, so
 * {@code java --enable-native-access=ALL-UNNAMED -cp ... YourApp}.</dd>
 *
 * <dt>{@link Backend#WEBP4J}</dt>
 * <dd>{@code libwebp} bound through JNI by {@code webp4j}, which is the answer on a platform
 * where the bundled library is not the one to use, or for a program that would rather not
 * have the JDK ask about native access. It carries its own copy of {@code libwebp} inside
 * its own jar. Its one gap is the encoding effort of a still image, which it cannot be told
 * and always encodes at libwebp's default of {@link #DEFAULT_COMPRESSION_METHOD}; see
 * {@link imagify.webp.webp4j.WebpCodec#encode(RenderedImage, int, boolean, int)}.</dd>
 * </dl>
 *
 * <p>Which one runs is named by a system property, read once, the first time this class is used:
 *
 * <pre>
 * java -Dimagify.webp.backend=webp4j -cp ... YourApp
 * </pre>
 *
 * <p>which accepts {@code ffm} and {@code webp4j} in any case. Leaving it unset, or setting it to
 * something else, means the {@link Backend#FFM default}. A backend that is named but cannot load
 * its native library is not a failure of the call that follows: the other one is used instead and
 * says so at {@code WARNING}, and only when neither can load is {@link #isAvailable()} false and
 * the {@code ImageIO} service providers stand aside for the JDK's own WebP support. That is the
 * same bargain the {@code ImageIO} plug-ins have always made, and it is why a wrong name or a
 * library that will not unpack costs a log line rather than a broken {@code ImageIO} for every
 * other format.
 *
 * <p>Once anything has used WebP the choice is settled for the life of the JVM, and
 * {@code System.setProperty} after that is too late: an {@code ImageIO.write} or {@code ImageIO.read}
 * settles it just as a direct call does, because the plug-ins ask {@link #isAvailable()} to decide
 * whether to stand aside. A program that sets the property has to do it before any of that.
 *
 * <p>{@link #backend()} answers which one is in use, for a program that reports it or measures
 * against it.
 *
 * <p>A program that wants one backend for one call and the other for the next does not go through
 * this class at all. Both backends are public and carry the same API, so a call names the one it
 * wants and the choice is per call, with no shared state behind it:
 *
 * <pre>
 * byte[] a = imagify.webp.ffm.WebpCodec.encode(image, 80, false, 4);
 * byte[] b = imagify.webp.webp4j.WebpCodec.encode(image, 80, false);
 * </pre>
 *
 * <p>That reaches the codec and not the {@code ImageIO} plug-ins, which go through whichever
 * backend this class settled on. A provider registered under the one format name {@code webp} is
 * one implementation of that format, and naming two of them is what a container chooses between
 * rather than what a single writer does per call.
 */
public final class WebpCodec {

    /**
     * The system property that names the backend, holding {@code ffm} or {@code webp4j}.
     */
    public static final String BACKEND_PROPERTY = "imagify.webp.backend";

    /**
     * Quality used when a caller does not ask for one: {@code 75}, {@code libwebp}'s own
     * {@code WEBP_QUALITY_DEFAULT}.
     */
    public static final int DEFAULT_QUALITY = 75;

    /** The smallest {@code quality} an encode accepts. */
    public static final int MIN_QUALITY = 0;

    /** The largest {@code quality} an encode accepts. */
    public static final int MAX_QUALITY = 100;

    /**
     * The quickest encoding effort {@code libwebp} accepts, and the one {@code method} is measured
     * against in {@link #encode(RenderedImage, int, boolean, int)}.
     */
    public static final int MIN_METHOD = 0;

    /** The most thorough encoding effort {@code libwebp} accepts. */
    public static final int MAX_METHOD = 6;

    /**
     * The encoding effort used when a caller does not name one: libwebp's own
     * {@code WEBP_PRESET_DEFAULT} value of 4, and the one that is a reasonable answer for a file
     * being written rather than produced as fast as it can be. It is also the one the
     * {@link Backend#WEBP4J} backend always encodes a still image at.
     */
    public static final int DEFAULT_COMPRESSION_METHOD = 4;

    /** {@code libwebp} reports a lossy {@code VP8} bitstream with this format code. */
    public static final int FORMAT_VP8 = 1;

    /** {@code libwebp} reports a lossless {@code VP8L} bitstream with this format code. */
    public static final int FORMAT_VP8L = 2;

    /** {@code libwebp} reports the {@code VP8X} container, which is what an animation uses. */
    public static final int FORMAT_VP8X = 0;

    private static final Logger log = getLogger(WebpCodec.class.getName());

    /**
     * {@code RIFF}, a four byte size and {@code WEBP} is the smallest header that can name a file.
     */
    private static final int HEADER_LENGTH = 30;

    private static final Object LOCK = new Object();

    private static volatile Backend resolved;

    private WebpCodec() {
        // utility class
    }

    /**
     * The two ways this library reaches {@code libwebp}.
     *
     * <p>Each one knows whether it can be used in this JVM and why not, which is what the choice
     * between them is made on.
     */
    public enum Backend {

        /**
         * {@code libwebp} through Java's Foreign Function &amp; Memory API, in
         * {@code imagify.webp.ffm}. The default, and the one that can be told how hard to try.
         */
        FFM {
            @Override
            public boolean isAvailable() {
                return imagify.webp.ffm.WebpCodec.isAvailable();
            }

            @Override
            public String getUnavailableReason() {
                return imagify.webp.ffm.WebpCodec.getUnavailableReason();
            }
        },

        /**
         * {@code libwebp} through JNI and {@code webp4j}, in {@code imagify.webp.webp4j}. It needs
         * no third party native access flag, and it cannot be told how hard to encode a still
         * image.
         */
        WEBP4J {
            @Override
            public boolean isAvailable() {
                return imagify.webp.webp4j.WebpCodec.isAvailable();
            }

            @Override
            public String getUnavailableReason() {
                return imagify.webp.webp4j.WebpCodec.getUnavailableReason();
            }
        };

        /**
         * @return whether this backend can be used in this JVM
         */
        public abstract boolean isAvailable();

        /**
         * @return why this backend cannot be used, or {@code null} when it can
         */
        public abstract String getUnavailableReason();

        /**
         * @return the name {@link #BACKEND_PROPERTY} spells this backend with
         */
        public String title() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * @param name a backend name, in any case
         * @return the backend of that name, or {@code null} when it names neither of them
         */
        public static Backend of(String name) {
            if (name == null) {
                return null;
            }
            String wanted = name.trim();
            for (Backend backend : values()) {
                if (backend.title().equalsIgnoreCase(wanted)) {
                    return backend;
                }
            }
            return null;
        }
    }

    // --------------------------------------------------------------------------------- selection

    /**
     * @return the backend in use, chosen on the first call and the same one for the rest of the
     *         life of the JVM
     */
    public static Backend backend() {
        Backend backend = resolved;
        return backend == null ? select() : backend;
    }

    private static Backend select() {
        synchronized (LOCK) {
            if (resolved != null) {
                return resolved;
            }
            String asked = System.getProperty(BACKEND_PROPERTY);
            Backend wanted = Backend.of(asked);
            if (wanted == null) {
                if (asked != null && !asked.isBlank()) {
                    log.log(Level.WARNING, "The {0} system property is {1}, which names neither " + "backend. The known ones are {2} and {3}, so {2} is used.", BACKEND_PROPERTY, asked, Backend.FFM
                            .title(), Backend.WEBP4J.title());
                }
                wanted = Backend.FFM;
            }
            if (wanted.isAvailable()) {
                resolved = wanted;
                return wanted;
            }
            // The backend that was named cannot load its library, and the whole point of shipping
            // both is that the other one still can, so it is used and the substitution is said
            // out loud. A program that asked for a specific backend finds out which one it got.
            Backend other = wanted == Backend.FFM ? Backend.WEBP4J : Backend.FFM;
            resolved = other.isAvailable() ? other : wanted;
            log.log(Level.WARNING, "{0} was asked for through the {1} system property and cannot " + "be used: {2}. {3} is used instead.", wanted, BACKEND_PROPERTY, wanted
                    .getUnavailableReason(), resolved == other ? "The " + other + " backend" : "Neither backend can be used");
            return resolved;
        }
    }

    // ---------------------------------------------------------------------------- availability

    /**
     * @return whether WebP encoding and decoding can be used in this JVM
     */
    public static boolean isAvailable() {
        return backend().isAvailable();
    }

    /**
     * @return why the backend in use cannot be used, or {@code null} when everything is fine
     */
    public static String getUnavailableReason() {
        return backend().getUnavailableReason();
    }

    /**
     * @return the {@code libwebp} release the backend in use is built against, or {@code null}
     *         when it does not publish one
     */
    public static String getVersion() {
        return switch (backend()) {
        case FFM -> imagify.webp.ffm.WebpCodec.getVersion();
        case WEBP4J -> imagify.webp.webp4j.WebpCodec.getVersion();
        };
    }

    // -------------------------------------------------------------------------------- inspection

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

    /**
     * Reads the container headers of a WebP file without decoding the pixels.
     *
     * @param encoded a complete WebP file
     * @return the properties reported by the headers
     * @throws WebpException when the input is not a WebP file or the headers are unreadable
     */
    public static WebpImageInfo readHeader(byte[] encoded) throws WebpException {
        return switch (backend()) {
        case FFM -> imagify.webp.ffm.WebpCodec.readHeader(encoded);
        case WEBP4J -> imagify.webp.webp4j.WebpCodec.readHeader(encoded);
        };
    }

    /**
     * @param data the leading bytes of a file
     * @return whether they start a {@code RIFF} container whose form type is {@code WEBP}
     */
    public static boolean isWebP(byte[] data) {
        return switch (backend()) {
        case FFM -> imagify.webp.ffm.WebpCodec.isWebP(data);
        case WEBP4J -> imagify.webp.webp4j.WebpCodec.isWebP(data);
        };
    }

    /**
     * @return how many leading bytes {@link #isWebP(byte[])} needs to decide
     */
    public static int headerLength() {
        return HEADER_LENGTH;
    }

    /**
     * Reports which of the two bitstreams a WebP file stores its pixels in, from the container
     * alone, decoding nothing.
     *
     * @param data a complete WebP file, or as many of its leading bytes as are to hand
     * @return whether the image inside is stored without loss
     */
    public static boolean isLossless(byte[] data) {
        return switch (backend()) {
        case FFM -> imagify.webp.ffm.WebpCodec.isLossless(data);
        case WEBP4J -> imagify.webp.webp4j.WebpCodec.isLossless(data);
        };
    }

    /**
     * @return how many leading bytes {@link #isLossless(byte[])} reads at most
     */
    public static int losslessHeaderLength() {
        return switch (backend()) {
        case FFM -> imagify.webp.ffm.WebpCodec.losslessHeaderLength();
        case WEBP4J -> imagify.webp.webp4j.WebpCodec.losslessHeaderLength();
        };
    }

    // ----------------------------------------------------------------------------------- encoding

    /**
     * @param source the image to encode
     * @param quality {@link #MIN_QUALITY} to {@link #MAX_QUALITY}
     * @param lossless whether to store the pixels without loss
     * @return the complete WebP file
     * @throws WebpException when the backend is unavailable or the image cannot be encoded
     */
    public static byte[] encode(RenderedImage source, int quality, boolean lossless) throws WebpException {
        return encode(source, quality, lossless, DEFAULT_COMPRESSION_METHOD);
    }

    /**
     * Encodes an image, choosing how hard the encoder tries.
     *
     * <p>{@code method} is libwebp's own effort scale and runs against {@link #MIN_METHOD} and
     * {@link #MAX_METHOD}. Lower is faster and produces a larger file, and the difference is large
     * enough to be worth naming: on a 1600x1200 photograph, method 0 takes roughly a quarter of the
     * time method 4 does and gives up about a third more bytes. Neither end is wrong and the choice
     * is a caller's, which is why the default of {@value #DEFAULT_COMPRESSION_METHOD} is only a
     * default and not a fixed point.
     *
     * <p>The {@link Backend#WEBP4J} backend cannot pass this on for a still image and encodes at
     * the default instead, saying so once. The {@link Backend#FFM} one always can.
     *
     * @param source the image to encode
     * @param quality {@link #MIN_QUALITY} to {@link #MAX_QUALITY}; a fidelity to trade away, or,
     *            with {@code lossless}, how hard to try to make the file small
     * @param lossless whether to store the pixels without loss
     * @param method {@link #MIN_METHOD} to {@link #MAX_METHOD}
     * @return the encoded file
     * @throws WebpException when the backend is unavailable or libwebp refuses the image
     */
    public static byte[] encode(RenderedImage source, int quality, boolean lossless, int method) throws WebpException {
        return switch (backend()) {
        case FFM -> imagify.webp.ffm.WebpCodec.encode(source, quality, lossless, method);
        case WEBP4J -> imagify.webp.webp4j.WebpCodec.encode(source, quality, lossless, method);
        };
    }

    /**
     * @param frames the frames, all of the same size, at least two
     * @param delaysMs how long each frame is shown, in milliseconds, one entry per frame
     * @param quality {@link #MIN_QUALITY} to {@link #MAX_QUALITY}
     * @param lossless whether to store the pixels without loss
     * @param loopCount how often the animation repeats, 0 meaning forever
     * @return the complete animated WebP file
     * @throws WebpException when the backend is unavailable or the frames cannot be encoded
     */
    public static byte[] encodeAnimation(List<BufferedImage> frames, int[] delaysMs, int quality, boolean lossless, int loopCount)
            throws WebpException {
        return encodeAnimation(frames, delaysMs, quality, lossless, loopCount, DEFAULT_COMPRESSION_METHOD);
    }

    /**
     * @param frames the frames, all of the same size, at least two
     * @param delaysMs how long each frame is shown, in milliseconds, one entry per frame
     * @param quality {@link #MIN_QUALITY} to {@link #MAX_QUALITY}
     * @param lossless whether to store the pixels without loss
     * @param loopCount how often the animation repeats, 0 meaning forever
     * @param compressionMethod {@link #MIN_METHOD} to {@link #MAX_METHOD}
     * @return the complete animated WebP file
     * @throws WebpException when the backend is unavailable or the frames cannot be encoded
     */
    public static byte[] encodeAnimation(List<BufferedImage> frames, int[] delaysMs, int quality, boolean lossless, int loopCount, int compressionMethod)
            throws WebpException {
        return switch (backend()) {
        case FFM -> imagify.webp.ffm.WebpCodec.encodeAnimation(frames, delaysMs, quality, lossless, loopCount, compressionMethod);
        case WEBP4J -> imagify.webp.webp4j.WebpCodec.encodeAnimation(frames, delaysMs, quality, lossless, loopCount, compressionMethod);
        };
    }

    // ----------------------------------------------------------------------------------- decoding

    /**
     * Decodes a still image.
     *
     * <p>An animation is rejected: its frames have to be read with
     * {@link #decodeAnimation(byte[])},
     * because a single animated WebP has no single image to return.
     *
     * @param encoded a complete WebP file
     * @return the pixels as a standard {@link BufferedImage} type
     * @throws WebpException when the backend is unavailable or the file cannot be decoded
     */
    public static BufferedImage decode(byte[] encoded) throws WebpException {
        return switch (backend()) {
        case FFM -> imagify.webp.ffm.WebpCodec.decode(encoded);
        case WEBP4J -> imagify.webp.webp4j.WebpCodec.decode(encoded);
        };
    }

    /**
     * Reads a WebP file and decodes its frames in one pass, which is the cheaper answer than
     * {@link #readHeader(byte[])} and then a decode.
     *
     * @param encoded a complete WebP file, still or animated
     * @return the properties and the frames, never empty
     * @throws WebpException when the backend is unavailable or the file cannot be decoded
     */
    public static DecodedWebp decodeFile(byte[] encoded) throws WebpException {
        return switch (backend()) {
        case FFM -> imagify.webp.ffm.WebpCodec.decodeFile(encoded);
        case WEBP4J -> imagify.webp.webp4j.WebpCodec.decodeFile(encoded);
        };
    }

    /**
     * Decodes every frame of an animation.
     *
     * @param encoded a complete animated WebP file
     * @return the composited canvas sized frames in presentation order, never empty
     * @throws WebpException when the backend is unavailable or the file cannot be decoded
     */
    public static List<BufferedImage> decodeAnimation(byte[] encoded) throws WebpException {
        return switch (backend()) {
        case FFM -> imagify.webp.ffm.WebpCodec.decodeAnimation(encoded);
        case WEBP4J -> imagify.webp.webp4j.WebpCodec.decodeAnimation(encoded);
        };
    }

    /**
     * Decodes the frame timings of an animation without keeping the pixels around.
     *
     * @param encoded a complete animated WebP file
     * @return the number of frames, the loop count and the per frame delay in milliseconds
     * @throws WebpException when the backend is unavailable or the file cannot be decoded
     */
    public static int[][] readAnimationTiming(byte[] encoded) throws WebpException {
        return switch (backend()) {
        case FFM -> imagify.webp.ffm.WebpCodec.readAnimationTiming(encoded);
        case WEBP4J -> imagify.webp.webp4j.WebpCodec.readAnimationTiming(encoded);
        };
    }
}
