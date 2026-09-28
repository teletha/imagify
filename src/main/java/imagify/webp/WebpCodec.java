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

import dev.matrixlab.webp4j.WebPCodec;
import dev.matrixlab.webp4j.animation.AnimatedWebPDecoder;
import dev.matrixlab.webp4j.animation.AnimatedWebPEncoder;
import dev.matrixlab.webp4j.gif.GifToWebPConfig;
import dev.matrixlab.webp4j.internal.NativeWebP;
import dev.matrixlab.webp4j.model.AnimatedWebPData;
import dev.matrixlab.webp4j.model.AnimatedWebPFrame;
import dev.matrixlab.webp4j.model.VP8StatusCode;
import dev.matrixlab.webp4j.model.WebPBitstreamFeatures;

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * WebP encoding and decoding, delegated to {@code libwebp} through {@code webp4j}.
 *
 * <p>This class is the single place that knows the third party API. Everything above it works in
 * terms of {@link BufferedImage} and {@code 0..100} quality, and never in terms of the quirks of
 * {@code webp4j}:
 *
 * <ul>
 *   <li>{@code webp4j} takes a {@link BufferedImage} and scales quality to {@code 0..100}, while
 *       {@code javax.imageio} speaks in {@code 0.0..1.0}, so the conversion lives in the writer.</li>
 *   <li>{@code webp4j} decodes animated files only through a separate entry point; its single image
 *       {@code decodeImage} fails outright on an animation. Both paths are reachable from here.</li>
 *   <li>{@code web4j} wraps a decoded still image in a custom colour model rather than one of the
 *       standard {@link BufferedImage} types, so the result is normalised before it is handed out.</li>
 * </ul>
 *
 * <p>Images are always converted to a layout {@code libwebp} can import directly, and always
 * through {@code getRGB()}/{@code setRGB()}, so an unassociated alpha channel survives without
 * being premultiplied against an opaque background.
 */
public final class WebpCodec {

    /** {@code libwebp} reports a lossy {@code VP8} bitstream with this format code. */
    public static final int FORMAT_VP8 = 1;

    /** {@code libwebp} reports a lossless {@code VP8L} bitstream with this format code. */
    public static final int FORMAT_VP8L = 2;

    /** {@code libwebp} reports the {@code VP8X} container, which is what an animation uses. */
    public static final int FORMAT_VP8X = 0;

    /** Quality used when the caller does not ask for one: {@code 75}, the {@code libwebp} default. */
    public static final int DEFAULT_QUALITY = 75;

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
     * @return whether WebP encoding and decoding can be used in this JVM
     */
    public static boolean isAvailable() {
        try {
            return WebPCodec.isAvailable();
        } catch (Throwable t) {
            // A broken native library surfaces as a LinkageError, which must not escape as an
            // unexpected error from a mere availability question.
            return false;
        }
    }

    /**
     * @return why the native library cannot be used, or {@code null} when everything is fine
     */
    public static String getUnavailableReason() {
        try {
            if (WebPCodec.isAvailable()) {
                return null;
            }
        } catch (Throwable t) {
            return describe(t);
        }
        Throwable cause = unavailabilityCause();
        return cause == null
                ? "the native WebP library could not be loaded"
                : describe(cause);
    }

    private static Throwable unavailabilityCause() {
        try {
            return NativeWebP.unavailabilityCause();
        } catch (Throwable t) {
            return t;
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getName() : message;
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
        Objects.requireNonNull(encoded, "no data to inspect");
        WebPBitstreamFeatures features = features(encoded);
        if (features.isHasAnimation()) {
            AnimatedWebPData animation = animationData(encoded);
            return new WebpImageInfo(features.getWidth(), features.getHeight(),
                    features.isHasAlpha(), true, features.getFormat(),
                    animation.getFrameCount(), animation.getLoopCount());
        }
        return new WebpImageInfo(features.getWidth(), features.getHeight(),
                features.isHasAlpha(), false, features.getFormat(), 1, 0);
    }

    private static WebPBitstreamFeatures features(byte[] encoded) throws WebpException {
        WebPBitstreamFeatures features = new WebPBitstreamFeatures();
        int status;
        try {
            status = NativeWebP.getFeatures(encoded, encoded.length, features);
        } catch (Throwable t) {
            throw new WebpException("cannot read the WebP header: " + describe(t), t);
        }
        if (status != VP8StatusCode.VP8_STATUS_OK.ordinal()) {
            VP8StatusCode code = VP8StatusCode.getStatusCode(status);
            throw new WebpException("the data is not a readable WebP bitstream: "
                    + (code == null ? "error " + status : code));
        }
        return features;
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
        if (source == null) {
            throw new WebpException("no image to encode");
        }
        if (source.getWidth() <= 0 || source.getHeight() <= 0) {
            throw new WebpException("cannot encode a " + source.getWidth() + "x" + source.getHeight() + " image");
        }
        requireAvailable();
        try {
            return WebPCodec.encodeImage(toArgbOrRgb(source), quality, lossless);
        } catch (IOException e) {
            throw new WebpException("cannot encode the image as WebP: " + describe(e), e);
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
     * @see #encodeAnimation(List, int[], int, boolean, int, int, boolean)
     */
    public static byte[] encodeAnimation(List<BufferedImage> frames, int[] delaysMs,
            int quality, boolean lossless, int loopCount) throws WebpException {
        // 4 and false are the defaults of libwebp and of the gif2webp tool, which is what this
        // form has always encoded at.
        return encodeAnimation(frames, delaysMs, quality, lossless, loopCount, 4, false);
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
     * @param allowMixed whether the encoder may store some frames without loss and others with loss
     * @return the complete animated WebP file
     * @throws WebpException when the library is unavailable or the frames cannot be encoded
     */
    public static byte[] encodeAnimation(List<BufferedImage> frames, int[] delaysMs,
            int quality, boolean lossless, int loopCount, int compressionMethod, boolean allowMixed)
            throws WebpException {
        if (frames == null || frames.size() < 2) {
            throw new WebpException("an animation needs at least two frames, got "
                    + (frames == null ? 0 : frames.size()));
        }
        if (delaysMs == null || delaysMs.length != frames.size()) {
            throw new WebpException("expected one delay per frame: " + frames.size()
                    + " frames but " + (delaysMs == null ? "no" : delaysMs.length + "") + " delays");
        }
        requireAvailable();
        int width = frames.get(0).getWidth();
        int height = frames.get(0).getHeight();
        if (width <= 0 || height <= 0) {
            throw new WebpException("cannot encode a " + width + "x" + height + " animation");
        }
        List<BufferedImage> prepared = new ArrayList<>(frames.size());
        for (BufferedImage frame : frames) {
            if (frame == null || frame.getWidth() != width || frame.getHeight() != height) {
                throw new WebpException("every frame must be " + width + "x" + height);
            }
            prepared.add(toArgbOrRgb(frame));
        }
        GifToWebPConfig config = new GifToWebPConfig()
                .setQuality(quality)
                .setLossless(lossless)
                .setLoopCount(loopCount)
                .setCompressionMethod(compressionMethod)
                .setAllowMixed(allowMixed)
                .setMultiThreaded(true);
        try {
            return AnimatedWebPEncoder.encode(prepared, delaysMs, config);
        } catch (IOException e) {
            throw new WebpException("cannot encode the frames as an animated WebP: " + describe(e), e);
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
        Objects.requireNonNull(encoded, "no data to decode");
        requireAvailable();
        try {
            return normalise(WebPCodec.decodeImage(encoded));
        } catch (IOException e) {
            throw new WebpException("cannot decode the WebP image: " + describe(e), e);
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
        Objects.requireNonNull(encoded, "no data to decode");
        WebPBitstreamFeatures features = features(encoded);
        if (!features.isHasAnimation()) {
            WebpImageInfo info = new WebpImageInfo(features.getWidth(), features.getHeight(),
                    features.isHasAlpha(), false, features.getFormat(), 1, 0);
            return new DecodedWebp(info, List.of(decode(encoded)), null);
        }
        AnimatedWebPData data = animationData(encoded);
        List<BufferedImage> frames = framesOf(data);
        if (frames.isEmpty()) {
            throw new WebpException("the WebP animation holds no frames");
        }
        WebpImageInfo info = new WebpImageInfo(features.getWidth(), features.getHeight(),
                features.isHasAlpha(), true, features.getFormat(),
                frames.size(), data.getLoopCount());
        return new DecodedWebp(info, frames, data.getDelays());
    }

    /**
     * Decodes every frame of an animation.
     *
     * @param encoded a complete animated WebP file
     * @return the composited canvas sized frames in presentation order, never empty
     * @throws WebpException when the library is unavailable or the file cannot be decoded
     */
    public static List<BufferedImage> decodeAnimation(byte[] encoded) throws WebpException {
        Objects.requireNonNull(encoded, "no data to decode");
        requireAvailable();
        List<BufferedImage> frames = framesOf(animationData(encoded));
        if (frames.isEmpty()) {
            throw new WebpException("the WebP animation holds no frames");
        }
        return frames;
    }

    /**
     * Decodes the frame timings of an animation without keeping the pixels around.
     *
     * @param encoded a complete animated WebP file
     * @return the number of frames, the loop count and the per frame delay in milliseconds
     * @throws WebpException when the library is unavailable or the file cannot be decoded
     */
    public static int[][] readAnimationTiming(byte[] encoded) throws WebpException {
        requireAvailable();
        try {
            AnimatedWebPData data = AnimatedWebPDecoder.decode(encoded);
            return new int[][] { { data.getFrameCount(), data.getLoopCount() }, data.getDelays() };
        } catch (IOException | RuntimeException e) {
            throw new WebpException("cannot read the WebP animation timing: " + describe(e), e);
        }
    }

    private static AnimatedWebPData animationData(byte[] encoded) throws WebpException {
        try {
            return AnimatedWebPDecoder.decode(encoded);
        } catch (IOException | RuntimeException e) {
            throw new WebpException("cannot decode the WebP animation: " + describe(e), e);
        }
    }

    private static void requireAvailable() throws WebpException {
        String reason = getUnavailableReason();
        if (reason != null) {
            throw new WebpException("WebP support is unavailable: " + reason);
        }
    }

    // ---------------------------------------------------------------------------------- internals

    /**
     * Converts an image into a layout {@code webp4j} can hand to {@code libwebp} without a copy.
     *
     * <p>{@code webp4j} imports a packed {@code TYPE_INT_ARGB} or {@code TYPE_INT_RGB} raster
     * directly and falls back to a Java2D blit for anything else, so those two types are the ones
     * worth producing here. Premultiplied alpha is never introduced: {@code getRGB()} un-associates
     * the colour and {@code setRGB()} on {@code TYPE_INT_ARGB} stores it un-associated.
     */
    private static BufferedImage toArgbOrRgb(RenderedImage source) {
        boolean alpha = source.getColorModel() != null && source.getColorModel().hasAlpha();
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        if (source instanceof BufferedImage image && image.getType() == type
                && isPackedInt(image)) {
            return image;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage target = new BufferedImage(width, height, type);
        int[] argb = new int[Math.multiplyExact(width, height)];
        readArgb(source, argb);
        target.setRGB(0, 0, width, height, argb, 0, width);
        return target;
    }

    /**
     * @return whether the image is a whole {@code TYPE_INT_*} image, rather than a translated view
     *         into a larger buffer, whose backing array would cover more than the image
     */
    private static boolean isPackedInt(BufferedImage image) {
        var raster = image.getRaster();
        if (raster.getSampleModelTranslateX() != 0 || raster.getSampleModelTranslateY() != 0) {
            return false;
        }
        if (!(raster.getDataBuffer() instanceof java.awt.image.DataBufferInt buffer)
                || buffer.getNumBanks() != 1
                || buffer.getOffset() != 0
                || buffer.getSize() != image.getWidth() * image.getHeight()) {
            return false;
        }
        return buffer.getData().length == image.getWidth() * image.getHeight();
    }

    private static void readArgb(RenderedImage source, int[] argb) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (source instanceof BufferedImage image) {
            // getRGB() rejects a region that reaches past the edge, so the overlap is worked out
            // first and the samples are then scattered into place.
            int left = Math.max(0, image.getMinX());
            int top = Math.max(0, image.getMinY());
            int right = Math.min(width, image.getMinX() + image.getWidth());
            int bottom = Math.min(height, image.getMinY() + image.getHeight());
            if (left >= right || top >= bottom) {
                return;
            }
            int clippedWidth = right - left;
            int clippedHeight = bottom - top;
            int[] clipped = new int[clippedWidth * clippedHeight];
            image.getRGB(left, top, clippedWidth, clippedHeight, clipped, 0, clippedWidth);
            for (int row = 0; row < clippedHeight; row++) {
                System.arraycopy(clipped, row * clippedWidth, argb, (top + row) * width + left, clippedWidth);
            }
            return;
        }
        var raster = source.getData();
        var model = source.getColorModel();
        if (model == null) {
            model = java.awt.image.ColorModel.getRGBdefault();
        }
        // A RenderedImage's raster lives in the image's own coordinate system, so the region is
        // clipped against the raster bounds and every sample is converted through the colour model.
        int minX = raster.getMinX();
        int minY = raster.getMinY();
        int left = Math.max(0, minX);
        int top = Math.max(0, minY);
        int right = Math.min(width, minX + raster.getWidth());
        int bottom = Math.min(height, minY + raster.getHeight());
        for (int row = top; row < bottom; row++) {
            for (int column = left; column < right; column++) {
                argb[row * width + column] = model.getRGB(raster.getDataElements(column, row, null));
            }
        }
    }

    /**
     * Turns a decoded image into one of the standard {@link BufferedImage} types.
     *
     * <p>{@code webp4j} hands back a custom {@code DirectColorModel} backed by a packed
     * {@code int[]}, which is pixel perfect but of an unnamed type, so it is not equal to, nor a
     * subclass of, {@code TYPE_INT_ARGB} as far as callers are concerned.
     */
    private static BufferedImage normalise(BufferedImage image) {
        int type = image.getColorModel().hasAlpha()
                ? BufferedImage.TYPE_INT_ARGB
                : BufferedImage.TYPE_INT_RGB;
        if (image.getType() == type) {
            return image;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage target = new BufferedImage(width, height, type);
        target.setRGB(0, 0, width, height, image.getRGB(0, 0, width, height, null, 0, width), 0, width);
        return target;
    }

    /**
     * @param data a decoded animation
     * @return the frames in presentation order
     */
    private static List<BufferedImage> framesOf(AnimatedWebPData data) {
        List<BufferedImage> frames = new ArrayList<>();
        for (AnimatedWebPFrame frame : data.getFrames()) {
            frames.add(frame.getImage());
        }
        return frames;
    }
}
