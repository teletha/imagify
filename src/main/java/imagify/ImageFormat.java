/*
 * Copyright (C) 2026 Nameless Production Committee
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import imagify.ImageFormat.Jpeg.Subsampling;
import imagify.avif.jna.AvifLibrary;
import imagify.jpeg.jna.JpegliLibrary;
import imagify.webp.ffm.WebpCodec;

/**
 * Supported image formats with auto-detection capabilities.
 *
 * <p>
 * Each format is a singleton whose own class describes it: {@link #JPEG} is a {@link Jpeg},
 * {@link #PNG} is a {@link Png}, and so on. The subclass is the natural home for whatever only
 * that one format does, so a format whose name, encoder or quirks differ from the others keeps
 * that difference next to its own name rather than in a switch somewhere else.
 * </p>
 *
 * <p>
 * A format whose encoder has settings of its own carries them as well, and a format asking for
 * something other than the default is a value rather than a constant:
 * {@link #WEBP} is the lossy bitstream at the usual encoder effort and {@link Webp#lossless()} the
 * lossless one, {@link #JPEG} is 4:2:0 with the standard entropy coder tables and
 * {@link Jpeg#subsampling(Subsampling)} is another way to ask for colour detail,
 * {@link #PNG} is the deflate effort the ImageIO plug-in would have picked and
 * {@link Png#compressionLevel(int)} is another, {@link Avif} is the encoder's own speed and alpha
 * quality and {@link Avif#speed(int)} is another. Each method answers with a new value, leaving the
 * one it was called on untouched.
 * </p>
 *
 * <p>
 * Each format provides file extension, MIME type, magic byte headers,
 * and whether alpha channel is supported.
 * </p>
 *
 * <p>
 * Auto-detection can be performed via:
 * <ul>
 * <li>{@link #fromExtension(String)} - by file extension</li>
 * <li>{@link #fromHeader(byte[])} - by magic byte header</li>
 * <li>{@link #fromPath(Path)} - by file extension + header fallback</li>
 * </ul>
 * </p>
 */
public abstract class ImageFormat {

    /**
     * JPEG format (.jpg, .jpeg). Lossy compression. No alpha support.
     * Magic bytes: no reliable header (starts with 0xFF 0xD8).
     *
     * <p>
     * The quality says how much of the picture survives, but it says nothing about how the two
     * colour-difference channels are laid down. That is {@link #subsampling}: a JPEG may store
     * them at full size, or half the width, or half in both directions, and the choice is
     * orthogonal to the quality. {@link #optimizeHuffmanTables} is the other thing the quality
     * does not say, whether the entropy coder tables are the standard ones or computed from the
     * image being written.
     * </p>
     *
     * <p>
     * Both are carried here rather than being a write argument for the same reason the AVIF and
     * WebP settings are: an {@link javax.imageio.ImageWriteParam} has room for a quality and a
     * compression type and for nothing else. They describe the file a caller wants, not one
     * particular write.
     * </p>
     */
    public static final class Jpeg extends ImageFormat {

        /**
         * The colour-difference resolution a JPEG is written at when the format does not say,
         * which is the one {@code libjpeg} and every other encoder writes by default.
         */
        public static final Subsampling DEFAULT_SUBSAMPLING = Subsampling.S420;

        /**
         * Whether the entropy coder tables are computed from the image when the format does not
         * say, which they are not: the standard tables are used, because a caller has to ask for
         * the extra encode and the extra memory that computing them costs.
         */
        public static final boolean DEFAULT_OPTIMIZE_HUFFMAN_TABLES = false;

        /**
         * How finely the two colour-difference channels are stored, which is what decides the
         * sharpness of the edges between areas of colour.
         *
         * <p>
         * It costs nothing in the quality argument and shows plainly at the same quality: the
         * three are three different files of three different sizes holding three different
         * amounts of the picture. {@link Subsampling#S444} keeps the most and writes the largest,
         * which is what a screenshot of text wants; {@link Subsampling#S420} is the default and the
         * smallest.
         * </p>
         */
        public final Subsampling subsampling;

        /**
         * Whether the entropy coder tables are computed from the image rather than taken from the
         * standard set, which makes a smaller file for the same pixels at the cost of a slower
         * encode and a table the decoder has to read rather than one it already knows.
         */
        public final boolean optimizeHuffmanTables;

        private Jpeg(Subsampling subsampling, boolean optimizeHuffmanTables) {
            super("JPEG", "jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8}, false, false, 0.85);
            this.subsampling = subsampling;
            this.optimizeHuffmanTables = optimizeHuffmanTables;
        }

        /**
         * @param subsampling how finely the colour-difference channels are stored
         * @return this format asking for that much colour detail
         * @throws IllegalArgumentException if {@code subsampling} is {@code null}
         */
        public Jpeg subsampling(Subsampling subsampling) {
            if (subsampling == null) {
                throw new IllegalArgumentException("the subsampling cannot be null");
            }
            return new Jpeg(subsampling, optimizeHuffmanTables);
        }

        /**
         * @param optimizeHuffmanTables whether the encoder may compute entropy coder tables from
         *            the image it is writing
         * @return this format leaving that choice to the encoder
         */
        public Jpeg optimizeHuffmanTables(boolean optimizeHuffmanTables) {
            return new Jpeg(subsampling, optimizeHuffmanTables);
        }

        /**
         * {@inheritDoc}
         *
         * <p>
         * The settings are part of what a JPEG format is, so the same picture asked for as 4:4:4
         * is not the same value as the same picture asked for as 4:2:0.
         * </p>
         */
        @Override
        public boolean equals(Object object) {
            return object instanceof Jpeg other && super.equals(object) && subsampling == other.subsampling && optimizeHuffmanTables == other.optimizeHuffmanTables;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), subsampling, optimizeHuffmanTables);
        }

        /**
         * How finely a JPEG stores its two colour-difference channels, named the way the format's
         * own sampling factors are usually written down.
         *
         * <p>
         * The two factors are the sampling rates the file's frame header carries, and the numbers
         * in the name are the ratio of luma samples to chroma samples along each axis. Only the
         * luma channel may be subsampled, so these are the three combinations a three component
         * JPEG can express; {@link #S420} is the default because it is the smallest, and
         * {@link #S444} the only one that stores the whole picture.
         * </p>
         */
        public enum Subsampling {

            /** 4:4:4, the colour channels at full size: the most detail and the largest file. */
            S444(1, 1, JpegliLibrary.IMAGIFY_JPEG_SAMP_444),

            /**
             * 4:2:2, the colour channels half the width: the usual choice for text and line art.
             */
            S422(2, 1, JpegliLibrary.IMAGIFY_JPEG_SAMP_422),

            /**
             * 4:2:0, the colour channels half in both directions: the smallest, and what is written
             * unless asked otherwise.
             */
            S420(2, 2, JpegliLibrary.IMAGIFY_JPEG_SAMP_420);

            /** The frame header's luma sampling factor along the width. */
            public final int horizontalFactor;

            /** The frame header's luma sampling factor along the height. */
            public final int verticalFactor;

            /**
             * The same choice as an {@code IMAGIFY_JPEG_SAMP_*} value, which is what the jpegli
             * encoder is given.
             *
             * <p>
             * Both spellings are kept because both are asked for somewhere: a caller reading a
             * frame header thinks in sampling factors, and the encoder takes a single code. Deriving
             * one from the other at every call site is how the two drift apart.
             * </p>
             */
            public final int samp;

            Subsampling(int horizontalFactor, int verticalFactor, int samp) {
                this.horizontalFactor = horizontalFactor;
                this.verticalFactor = verticalFactor;
                this.samp = samp;
            }
        }
    }

    /**
     * PNG format (.png). Lossless compression. Alpha support.
     * Magic bytes: 0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A
     *
     * <p>
     * PNG has no quality to lose. Whatever the encoder is told, the pixels that come back are the
     * pixels that went in, so the only knob left is how hard the deflate stage works, and that
     * shows up in the size of the file and in the time it takes and nowhere else. It is
     * {@link #compressionLevel}, and it is a setting of the format rather than an argument of a
     * write, because unlike every other format's quality it does not say what the file should
     * hold: a caller who wants a small PNG says so with the format, and the {@code quality} of
     * {@link ImageWriter} is left with nothing to do here, as it already had for GIF and BMP.
     * </p>
     */
    public static final class Png extends ImageFormat {

        /**
         * The deflate effort a PNG is written at when the format does not name one, which is the
         * effort the ImageIO plug-in picks when it is not told either.
         *
         * <p>
         * Note that this is not the level {@code java.util.zip.Deflater} calls its default, which
         * is
         * {@code 6}: the PNG writer settles on {@code 4}, and this is that one, so a plain PNG
         * written here is the file {@code ImageIO.write} would have written.
         * </p>
         */
        public static final int DEFAULT_COMPRESSION_LEVEL = 4;

        /**
         * The most effort a deflate stage can be asked for, and the smallest file it will produce.
         */
        public static final int MAX_COMPRESSION_LEVEL = 9;

        /**
         * How hard the deflate stage works, {@code 0} being the quickest and
         * {@link #MAX_COMPRESSION_LEVEL} the most thorough.
         *
         * <p>
         * Two levels apart are worth a great deal: on a flat 400x400 image the difference between
         * level {@code 0} and level {@code 9} is 207 times in file size, for the same pixels. The
         * levels near each other are worth much less and may be worth nothing at all, so this is
         * a size and a time trade rather than a curve to tune.
         * </p>
         */
        public final int compressionLevel;

        private Png(int compressionLevel) {
            super("PNG", "png", "image/png", new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, true, false, 0.0);
            this.compressionLevel = compressionLevel;
        }

        /**
         * @param level how hard the deflate stage works, 0 (quickest) to 9 (most thorough)
         * @return this format asking the encoder for that much effort
         * @throws IllegalArgumentException if the effort is outside what deflate accepts
         */
        public Png compressionLevel(int level) {
            if (level < 0 || level > MAX_COMPRESSION_LEVEL) {
                throw new IllegalArgumentException("the compression level must be between 0 and " + MAX_COMPRESSION_LEVEL + ", got " + level);
            }
            return new Png(level);
        }

        /**
         * {@inheritDoc}
         *
         * <p>
         * The effort is part of what a PNG format is, so the same picture asked for at two levels
         * is not the same value.
         * </p>
         */
        @Override
        public boolean equals(Object object) {
            return object instanceof Png other && super.equals(object) && compressionLevel == other.compressionLevel;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), compressionLevel);
        }
    }

    /**
     * GIF format (.gif). Lossless, indexed color. Alpha support (1-bit).
     * Magic bytes: "GIF8", the four bytes every revision of the header begins with.
     *
     * <p>
     * A GIF file names its revision in the header, GIF87a or GIF89a, and outside that number
     * there is nothing here the two differ about that this library can act on. 89a is the revision
     * that adds the graphic control extension, which is how a frame says it is transparent, and it
     * is the revision every encoder here writes, because {@link #supportsAlpha()} promises a GIF
     * can be transparent and 87a has no way to say so. So there is one format, and
     * {@link #fromHeader(byte[])} answers with it whichever revision the bytes carry.
     * </p>
     */
    public static final class Gif extends ImageFormat {

        private Gif() {
            super("GIF", "gif", "image/gif", new byte[] {'G', 'I', 'F', '8'}, true, true, 0.0);
        }
    }

    /**
     * WebP format (.webp). Lossy/lossless. Alpha support.
     * Magic bytes: "RIFF...." + "WEBP"
     *
     * <p>
     * WebP is the one format that comes in more than one flavour: {@link #WEBP} is the lossy
     * {@code VP8} bitstream and {@link #lossless()} the lossless {@code VP8L} one. They are
     * separate
     * values because the choice belongs to the encoder, and because a caller that has just read a
     * file back wants to write it in the flavour it was read in: {@link #fromHeader(byte[])}
     * answers
     * with whichever of the two the bytes say, so a file that survives a read and a write still
     * holds
     * every pixel it held before.
     * </p>
     *
     * <p>
     * A flavour is a value rather than a constant, because a format may well come to carry more
     * settings than this one, and a value that answers to its settings is the one that can. Two
     * flavours holding the same settings are equal without being the same object.
     * </p>
     *
     * <p>
     * The encoder has settings of its own besides the flavour, and the one worth choosing is
     * carried here too: {@link #compressionMethod}, how hard the encoder tries. It belongs to an
     * animation rather than to a single image, and the WebP binding this library uses wires only
     * its animation encoder for it, so it says nothing about a still image.
     * </p>
     */
    public static final class Webp extends ImageFormat {

        /**
         * The effort the encoder puts in when the format does not name one, which is what
         * {@code libwebp} and the {@code gif2webp} tool use.
         */
        public static final int DEFAULT_COMPRESSION_METHOD = 4;

        /** Whether the pixels are stored without loss, which also means the quality is ignored. */
        public final boolean lossless;

        /**
         * How hard the encoder tries, {@code 0} being the quickest and {@code 6} the most thorough.
         *
         * <p>
         * More effort buys a smaller file for the same quality at the cost of a slower encode. This
         * is {@code libwebp}'s {@code method}, and it is honoured for an animation; the binding
         * this
         * library uses does not offer it to its single image encoder, so a still image is always
         * written at {@link #DEFAULT_COMPRESSION_METHOD}.
         * </p>
         */
        public final int compressionMethod;

        private Webp(boolean lossless, int compressionMethod) {
            super("WEBP", "webp", "image/webp", new byte[] {'R', 'I', 'F', 'F'}, false, true, 0.80);
            this.lossless = lossless;
            this.compressionMethod = compressionMethod;
        }

        /**
         * @return the lossless flavour of this format, whose {@code VP8L} bitstream keeps every
         *         pixel as it was given
         */
        public Webp lossless() {
            return new Webp(true, compressionMethod);
        }

        /**
         * @param method how hard the encoder tries, 0 (quickest) to 6 (most thorough)
         * @return this format asking the encoder for that much effort
         * @throws IllegalArgumentException if the effort is outside what {@code libwebp} accepts
         */
        public Webp compressionMethod(int method) {
            if (method < 0 || method > 6) {
                throw new IllegalArgumentException("the compression method must be between 0 and 6, got " + method);
            }
            return new Webp(lossless, method);
        }

        /**
         * {@inheritDoc}
         *
         * <p>
         * The flavour and the effort are part of what a WebP format is, so a lossy animation
         * written
         * with more effort is not the same value as a lossy one written with less.
         * </p>
         */
        @Override
        public boolean equals(Object object) {
            return object instanceof Webp other && super.equals(object) && lossless == other.lossless && compressionMethod == other.compressionMethod;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), lossless, compressionMethod);
        }
    }

    /**
     * AVIF format (.avif). AV1-based. Alpha support.
     * Magic bytes: "ftyp" box with "avif" brand
     *
     * <p>
     * AVIF has no switch for lossless encoding: it is a quality of {@code AVIF_QUALITY_LOSSLESS}
     * out
     * of {@code AVIF_QUALITY_BEST}, so the quality a write is asked for is what says whether the
     * pixels are kept. The settings that have no other home are carried here instead:
     * {@link #speed}, how long the encoder may take, and {@link #alphaQuality}, how hard the alpha
     * plane is compressed. Both are honoured for a still image as well as for an animation.
     * </p>
     *
     * <p>
     * Two more concern the colour the file holds rather than the effort spent on it:
     * {@link #subsampling}, how finely the two colour-difference channels are stored, and
     * {@link #chromaDownsampling}, the filter the conversion from RGB reduces them with. A setting
     * is a value, or it is left unset, which is not the same thing: unset means the encoder keeps
     * its own choice, and for the subsampling that choice differs between a still image and an
     * animation.
     * </p>
     */
    public static final class Avif extends ImageFormat {

        /**
         * How long the encoder may take, {@code 0} being the slowest and most thorough and
         * {@code 10} the quickest. Unset means {@link AvifLibrary#DEFAULT_SPEED} for a still image
         * and {@link AvifLibrary#DEFAULT_ANIMATION_SPEED} for an animation, which pays the cost of
         * a speed setting once per frame.
         */
        public final Integer speed;

        /**
         * How hard the alpha plane is compressed, {@code AVIF_QUALITY_WORST} being the worst and
         * {@link AvifLibrary#AVIF_QUALITY_BEST} keeping every alpha value as it was given. Unset
         * means {@link AvifLibrary#AVIF_QUALITY_LOSSLESS}, because lossy alpha is the single most
         * visible AVIF artefact and an alpha plane is cheap to store.
         */
        public final Integer alphaQuality;

        /**
         * How finely the two colour-difference channels are stored, or {@code null} to leave the
         * choice to the encoder, which is not one value but two: {@link Subsampling#YUV444} for a
         * still image, because a lone picture is where someone is most likely to be looking
         * closely at a saturated edge, and {@link Subsampling#YUV420} for an animation, where the
         * cost of the alternative is paid once per frame.
         *
         * <p>
         * Naming a value here overrides both, which is the point of the setting: a caller who wants
         * the smallest animation there is asks for {@link Subsampling#YUV420} on a still image too,
         * and a caller who wants a text screenshot as an animation asks for
         * {@link Subsampling#YUV444} there as well.
         * </p>
         */
        public final Subsampling subsampling;

        /**
         * The filter the conversion from RGB reduces the colour-difference channels with, or
         * {@code null} to leave the choice to the encoder, which is
         * {@link ChromaDownsampling#AUTOMATIC}.
         *
         * <p>
         * This is a second, narrower thing than {@link #subsampling}: it decides how the reduction
         * is done, not whether it happens, and it therefore has nothing at all to do with a file
         * stored as {@link Subsampling#YUV444}, where no channel is ever reduced. On a
         * {@link Subsampling#YUV420} file it is worth having, because reducing by averaging keeps
         * colour edges from bleeding into one another while
         * {@link ChromaDownsampling#SHARP_YUV} keeps them from smearing, and the two are not the
         * same picture.
         * </p>
         */
        public final ChromaDownsampling chromaDownsampling;

        private Avif() {
            this(null, null, null, null);
        }

        private Avif(Integer speed, Integer alphaQuality, Subsampling subsampling,
                ChromaDownsampling chromaDownsampling) {
            super("AVIF", "avif", "image/avif", null, true, true, 0.70);
            this.speed = speed;
            this.alphaQuality = alphaQuality;
            this.subsampling = subsampling;
            this.chromaDownsampling = chromaDownsampling;
        }

        /**
         * @param speed 0 (slowest, best quality) to 10 (fastest, worst quality)
         * @return this format asking the encoder for that much speed
         * @throws IllegalArgumentException if the speed is outside what {@code libavif} accepts
         */
        public Avif speed(int speed) {
            if (speed < 0 || speed > 10) {
                throw new IllegalArgumentException("the encoder speed must be between 0 and 10, got " + speed);
            }
            return new Avif(speed, alphaQuality, subsampling, chromaDownsampling);
        }

        /**
         * @param alphaQuality 0 (worst) to 100 (every alpha value kept as it was given)
         * @return this format asking for that much alpha quality
         * @throws IllegalArgumentException if the quality is outside what {@code libavif} accepts
         */
        public Avif alphaQuality(int alphaQuality) {
            if (alphaQuality < AvifLibrary.AVIF_QUALITY_WORST || alphaQuality > AvifLibrary.AVIF_QUALITY_BEST) {
                throw new IllegalArgumentException("the alpha quality must be between " + AvifLibrary.AVIF_QUALITY_WORST + " and " + AvifLibrary.AVIF_QUALITY_BEST + ", got " + alphaQuality);
            }
            return new Avif(speed, alphaQuality, subsampling, chromaDownsampling);
        }

        /**
         * @param subsampling how finely to store the colour-difference channels, or {@code null} to
         *                    leave it to the encoder
         * @return this format asking for that much colour detail
         */
        public Avif subsampling(Subsampling subsampling) {
            return new Avif(speed, alphaQuality, subsampling, chromaDownsampling);
        }

        /**
         * @param chromaDownsampling the filter to reduce the colour-difference channels with, or
         *                            {@code null} to leave it to the encoder
         * @return this format asking for that filter
         */
        public Avif chromaDownsampling(ChromaDownsampling chromaDownsampling) {
            return new Avif(speed, alphaQuality, subsampling, chromaDownsampling);
        }

        /**
         * {@inheritDoc}
         *
         * <p>
         * The settings are part of what an AVIF format is, so the same file asked for at two
         * different speeds is not the same value.
         * </p>
         */
        @Override
        public boolean equals(Object object) {
            return object instanceof Avif other && super.equals(object) && Objects.equals(speed, other.speed)
                    && Objects.equals(alphaQuality, other.alphaQuality) && subsampling == other.subsampling
                    && chromaDownsampling == other.chromaDownsampling;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), speed, alphaQuality, subsampling, chromaDownsampling);
        }

        /**
         * How finely the colour-difference channels are stored, named the way the format's own pixel
         * format enumeration writes it down.
         */
        public enum Subsampling {
            /** YUV444, the colour channels at full size: the most detail and the largest file. */
            YUV444(AvifLibrary.AVIF_PIXEL_FORMAT_YUV444),

            /** YUV422, the colour channels half the width: the usual choice for text and line art. */
            YUV422(AvifLibrary.AVIF_PIXEL_FORMAT_YUV422),

            /** YUV420, the colour channels half in both directions: the smallest, and what is written for an animation unless asked otherwise. */
            YUV420(AvifLibrary.AVIF_PIXEL_FORMAT_YUV420),

            /** YUV400, no colour channels at all: greyscale, the smallest of all. */
            YUV400(AvifLibrary.AVIF_PIXEL_FORMAT_YUV400);

            /** The underlying {@code AVIF_PIXEL_FORMAT_*} value. */
            public final int pixelFormat;

            Subsampling(int pixelFormat) {
                this.pixelFormat = pixelFormat;
            }
        }

        /**
         * The filter the conversion from RGB reduces the colour-difference channels with, named the
         * way the RGB image description enumeration writes it down.
         *
         * <p>
         * This is a second, narrower thing than {@link Subsampling}: it decides how the reduction is
         * done, not whether it happens. It therefore has nothing at all to do with a file stored as
         * {@link Subsampling#YUV444}, where no channel is ever reduced.
         * </p>
         */
        public enum ChromaDownsampling {
            /** The encoder chooses, which is the best balance of quality and speed. */
            AUTOMATIC(AvifLibrary.AVIF_CHROMA_DOWNSAMPLING_AUTOMATIC),

            /** The quickest reduction, which may show visible colour bleeding on saturated edges. */
            FASTEST(AvifLibrary.AVIF_CHROMA_DOWNSAMPLING_FASTEST),

            /** The best-looking reduction, which averages the chroma values. */
            BEST_QUALITY(AvifLibrary.AVIF_CHROMA_DOWNSAMPLING_BEST_QUALITY),

            /** A simple box average of the chroma samples. */
            AVERAGE(AvifLibrary.AVIF_CHROMA_DOWNSAMPLING_AVERAGE),

            /** A sharpness-preserving filter that tries to keep colour edges from smearing. */
            SHARP_YUV(AvifLibrary.AVIF_CHROMA_DOWNSAMPLING_SHARP_YUV);

            /** The underlying {@code AVIF_CHROMA_DOWNSAMPLING_*} value. */
            public final int value;

            ChromaDownsampling(int value) {
                this.value = value;
            }
        }
    }

    /**
     * BMP format (.bmp). Uncompressed. Alpha support varies.
     * Magic bytes: "BM"
     */
    public static final class Bmp extends ImageFormat {

        private Bmp() {
            super("BMP", "bmp", "image/bmp", new byte[] {'B', 'M'}, false, false, 0.0);
        }
    }

    /**
     * The defaults are spelled out here instead of being read from {@link Jpeg#DEFAULT_SUBSAMPLING}
     * and {@link Jpeg#DEFAULT_OPTIMIZE_HUFFMAN_TABLES}, because a static field of a subclass cannot
     * be read while that subclass is still initialising, and comes back {@code null} instead of its
     * value. {@link Jpeg} extends this class, so a caller who names {@link Jpeg#DEFAULT_SUBSAMPLING}
     * before naming any format makes the virtual machine initialise {@code Jpeg} first, which
     * initialises this class first, and the two are then initialising each other. Naming
     * {@link Jpeg.Subsampling#S420} asks for the nested enum alone, which depends on neither and so
     * is safe whichever of the three classes is initialised first.
     *
     * @see Jpeg
     */
    public static final Jpeg JPEG = new Jpeg(Jpeg.Subsampling.S420, false);

    /** @see Png */
    public static final Png PNG = new Png(Png.DEFAULT_COMPRESSION_LEVEL);

    /** @see Gif */
    public static final Gif GIF = new Gif();

    /** @see Webp */
    public static final Webp WEBP = new Webp(false, Webp.DEFAULT_COMPRESSION_METHOD);

    /** @see Avif */
    public static final Avif AVIF = new Avif();

    /** @see Bmp */
    public static final Bmp BMP = new Bmp();

    private static final List<ImageFormat> ALL = List.of(JPEG, PNG, GIF, WEBP, AVIF, BMP);

    /** The format name as an enum constant used to spell it, such as {@code "PNG"}. */
    private final String name;

    private final String extension;

    private final String mimeType;

    private final byte[] magicBytes;

    private final boolean supportsAlpha;

    private final boolean supportsAnimation;

    private final double defaultQuality;

    ImageFormat(String name, String extension, String mimeType, byte[] magicBytes, boolean supportsAlpha, boolean supportsAnimation, double defaultQuality) {
        this.name = name;
        this.extension = extension;
        this.mimeType = mimeType;
        this.magicBytes = magicBytes;
        this.supportsAlpha = supportsAlpha;
        this.supportsAnimation = supportsAnimation;
        this.defaultQuality = defaultQuality;
    }

    /**
     * Returns the name of this format, in upper case, as {@code "PNG"} or {@code "GIF"}.
     */
    String name() {
        return name;
    }

    /**
     * Returns the primary file extension for this format.
     */
    String getExtension() {
        return extension;
    }

    /**
     * Returns the MIME type for this format.
     */
    String getMimeType() {
        return mimeType;
    }

    /**
     * Returns whether this format supports animation.
     *
     * @return {@code true} for GIF, WebP, and AVIF
     */
    boolean supportsAnimation() {
        return supportsAnimation;
    }

    /**
     * Returns whether this format supports alpha channels.
     */
    boolean supportsAlpha() {
        return supportsAlpha;
    }

    /**
     * Returns the default quality value (0.0-1.0) for lossy formats.
     * Lossless formats return 0.0.
     */
    double getDefaultQuality() {
        return defaultQuality;
    }

    /**
     * Returns the magic byte header for this format, or null if not applicable.
     */
    byte[] getMagicBytes() {
        return magicBytes != null ? magicBytes.clone() : null;
    }

    /**
     * Returns the name {@code ImageIO} registers readers and writers under.
     *
     * <p>
     * Every constant is named after its format in lower case, which is exactly the name the
     * {@code ImageIO} registry uses.
     * </p>
     */
    String getFormatName() {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Returns every format, in declaration order.
     *
     * @return an unmodifiable list of all formats
     */
    public static List<ImageFormat> all() {
        return ALL;
    }

    /**
     * Resolves the format from a file extension.
     *
     * @param extension the file extension (e.g., "png", ".jpg")
     * @return the matching ImageFormat
     * @throws IllegalArgumentException if the extension is not recognized
     */
    public static ImageFormat fromExtension(String extension) {
        String ext = extension.toLowerCase().replaceFirst("^\\.", "");
        for (ImageFormat format : ALL) {
            if (format.extension.equals(ext)) return format;
        }
        // Handle aliases
        switch (ext) {
        case "jpg":
        case "jpeg":
            return JPEG;
        // The revision a GIF header names is not a format of its own, so both spellings of it
        // land on the one GIF. See Gif.
        case "gif87a":
        case "gif89a":
            return GIF;
        default:
            throw new IllegalArgumentException("Unknown extension: ." + ext);
        }
    }

    /**
     * Resolves the format by inspecting the magic byte header of the data.
     *
     * <p>
     * WebP is the one format that comes in more than one flavour, and a header long enough to
     * hold the chunk that names the image bitstream says which one this is, so the answer is
     * {@link #WEBP} or {@link Webp#lossless()} rather than always the lossy one. As many leading
     * bytes as the caller has are all that is needed to decide, and the ones that are missing are
     * simply not able to change the answer.
     *
     * @param header the first few bytes of the file/data
     * @return the matching ImageFormat
     * @throws IllegalArgumentException if the header does not match any known format
     */
    public static ImageFormat fromHeader(byte[] header) {
        if (header == null || header.length == 0) throw new IllegalArgumentException("Header cannot be null or empty");

        // Check JPEG first (0xFF 0xD8)
        if (header.length >= 2 && header[0] == (byte) 0xFF && header[1] == (byte) 0xD8) return JPEG;

        // Check PNG (89 50 4E 47 0D 0A 1A 0A)
        if (header.length >= 8 && header[0] == (byte) 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G' && header[4] == 0x0D && header[5] == 0x0A && header[6] == 0x1A && header[7] == 0x0A)
            return PNG;

        // Check GIF, either revision
        if (header.length >= 6 && header[0] == 'G' && header[1] == 'I' && header[2] == 'F' && header[3] == '8' && (header[4] == '7' || header[4] == '9') && header[5] == 'a')
            return GIF;

        // Check WEBP (RIFF....WEBP) - need at least 12 bytes for full detection
        if (header.length >= 4 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F') {
            // Need to check WEBP at offset 8-11
            if (header.length >= 12) {
                if (header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
                    return webp(header);
                }
            }
            // Partial match, assume WEBP if RIFF
            return WEBP;
        }

        // Check BMP (BM)
        if (header.length >= 2 && header[0] == 'B' && header[1] == 'M') return BMP;

        // Check AVIF. An ISO base media file opens with a big endian box size, so the "ftyp"
        // signature sits at offset 4 rather than 0 and AVIF has no offset 0 signature, which is
        // why it carries no magicBytes. The major brand that follows names the format: "avif" for
        // a still image and "avis" for a sequence. Only the major brand is readable here, since
        // the caller passes just a header; a file whose major brand is something else and that
        // merely lists avif among its compatible brands is not detected here.
        if (header.length >= 12 && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p' && header[8] == 'a' && header[9] == 'v' && header[10] == 'i' && (header[11] == 'f' || header[11] == 's'))
            return AVIF;

        throw new IllegalArgumentException("Unknown file format header");
    }

    /**
     * Resolves which flavour of WebP the data is in, which is the one thing a file name cannot say.
     *
     * <p>
     * Only the bytes can tell a {@code VP8L} file from a {@code VP8 } one, and the chunk that
     * names the bitstream is a little way into the file, so a caller that passes a shorter prefix
     * than {@link WebpCodec#losslessHeaderLength()} gets the lossy flavour. That is the right way
     * round to be wrong in: a file read as lossy still decodes, a file read as lossless would only
     * ever be a guess about something the encoder is asked for anyway.
     */
    private static ImageFormat webp(byte[] header) {
        return WebpCodec.isLossless(header) ? WEBP.lossless() : WEBP;
    }

    /**
     * Resolves the format from a file path (extension-based), with header fallback.
     *
     * <p>
     * The name alone decides here, which is deliberate: this is what a writer resolves the
     * format of a destination from, and a file that is already sitting at that path must not
     * decide what is written over it. A reader that wants the flavour of a file rather than the
     * format of a name hands its bytes to {@link #fromHeader(byte[])} instead.
     *
     * @param path the file path
     * @return the detected ImageFormat
     * @throws IOException if the file cannot be read for header detection
     */
    public static ImageFormat fromPath(Path path) throws IOException {
        String ext = getExtension(path);
        try {
            return fromExtension(ext);
        } catch (IllegalArgumentException e) {
            // Fall back to header detection. The buffer is long enough for the container to say
            // which flavour of a format it holds, not merely which format that is.
            java.io.InputStream in = java.nio.file.Files.newInputStream(path);
            byte[] header = new byte[WebpCodec.losslessHeaderLength()];
            int bytesRead = in.read(header);
            in.close();
            if (bytesRead > 0) {
                return fromHeader(Arrays.copyOf(header, bytesRead));
            }
            throw e;
        }
    }

    /**
     * Attempts to detect the format by checking magic bytes.
     * Returns null if detection fails (not an error, just unknown).
     */
    public static ImageFormat detect(byte[] header) {
        try {
            return fromHeader(header);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String getExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * A format is a value: a subclass that carries settings of its own is expected to add them
     * here,
     * since {@link #WEBP} and the lossless WebP that {@link Webp#lossless()} hands out are two
     * requests rather than two constants, and a caller comparing them with {@code equals} is asking
     * whether they ask for the same thing.
     * </p>
     */
    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (object == null || getClass() != object.getClass()) return false;
        ImageFormat other = (ImageFormat) object;
        return name.equals(other.name) && extension.equals(other.extension) && mimeType
                .equals(other.mimeType) && supportsAlpha == other.supportsAlpha && supportsAnimation == other.supportsAnimation && Double
                        .compare(defaultQuality, other.defaultQuality) == 0 && Arrays.equals(magicBytes, other.magicBytes);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        int hash = Objects.hash(name, extension, mimeType, supportsAlpha, supportsAnimation, defaultQuality);
        return hash * 31 + Arrays.hashCode(magicBytes);
    }

    @Override
    public String toString() {
        return extension.toUpperCase(Locale.ROOT);
    }
}
