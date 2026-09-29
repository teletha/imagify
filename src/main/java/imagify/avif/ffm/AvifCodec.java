/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.avif.ffm;

import static imagify.avif.ffm.AvifConstants.*;

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

import imagify.avif.AvifException;
import imagify.avif.AvifImageInfo;
import imagify.pixels.AbgrPixels;

/**
 * AVIF encoding and decoding, bound to {@code libavif} through a C shim and the Foreign Function
 * &amp; Memory API.
 *
 * <p>Neither side of the boundary moves a pixel. The pixels of a picture on the way in are the
 * caller's own array, handed to {@link AvifShim#pictureFromAbgr} as a heap segment, which the
 * linker passes through as a pointer and the shim reads without copying. On the way out they are
 * one buffer the shim allocated, read once into the image it becomes. The JNA binding this
 * replaces made two copies of every decoded picture, the second of them into the very data buffer
 * the image is made of, because a {@code DataBufferByte} cannot be handed an array that already
 * exists.
 *
 * <p>There is a C shim here, unlike for a binding that can talk to a library's C API directly, and
 * it exists for the one thing FFM will not do from Java. A heap segment, which is what a Java array
 * becomes, cannot be stored into a pointer-typed struct field, and {@code avifRGBImage.pixels} is
 * exactly such a field. A function parameter is not a struct field, so an entry point that takes
 * the pixels as its own argument has no such restriction, and that entry point is the shim.
 *
 * <p>Loading is lazy and failing to load is never fatal; see {@link AvifLoader} and
 * {@link #getUnavailableReason()}.
 */
public final class AvifCodec {

    private AvifCodec() {
        // utility class
    }

    /**
     * A decoded picture and what the file said about it.
     *
     * @param image the pixels, a {@link BufferedImage#TYPE_4BYTE_ABGR} image
     * @param info the properties libavif read out of the file's headers
     */
    public record DecodedImage(BufferedImage image, AvifImageInfo info) {

        public DecodedImage {
            java.util.Objects.requireNonNull(image, "no image");
            java.util.Objects.requireNonNull(info, "no info");
        }
    }

    // ------------------------------------------------------------------------------- availability

    /**
     * @return whether AVIF can be used in this JVM
     */
    public static boolean isAvailable() {
        return AvifLoader.shim() != null;
    }

    /**
     * @return why AVIF cannot be used, or {@code null} when it can
     */
    public static String getUnavailableReason() {
        return AvifLoader.unavailableReason();
    }

    /**
     * @return the libavif release in use, or {@code null} when it is not available
     */
    public static String getVersion() {
        AvifShim shim = AvifLoader.shim();
        return shim == null ? null : shim.libavifVersion();
    }

    /**
     * @return the number of threads worth handing libavif on this machine
     */
    public static int defaultThreads() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Returns the supported {@code libavif} version range.
     *
     * <p>The shim is compiled against whatever {@code avif.h} was on the machine that built it and
     * calls nothing newer than 1.0, so this is the range rather than a single version.
     *
     * @return for example {@code "1.0 - 1.4"}
     */
    public static String supportedVersions() {
        return "1.0 - 1.4";
    }

    // -------------------------------------------------------------------------------- enum names

    /**
     * @param yuvFormat an {@code AVIF_PIXEL_FORMAT_*} value
     * @return the symbolic name, for example {@code "YUV420"}
     */
    public static String pixelFormatName(int yuvFormat) {
        return switch (yuvFormat) {
            case PIXEL_FORMAT_NONE -> "None";
            case PIXEL_FORMAT_YUV444 -> "YUV444";
            case PIXEL_FORMAT_YUV422 -> "YUV422";
            case PIXEL_FORMAT_YUV420 -> "YUV420";
            case PIXEL_FORMAT_YUV400 -> "YUV400";
            default -> "Unknown(" + yuvFormat + ")";
        };
    }

    /**
     * @param value an {@code AVIF_RANGE_*} value
     * @return the symbolic name
     */
    public static String rangeName(int value) {
        return switch (value) {
            case RANGE_LIMITED -> "Limited";
            case RANGE_FULL -> "Full";
            default -> "Unknown(" + value + ")";
        };
    }

    /**
     * @param value an {@code AVIF_CHROMA_SAMPLE_POSITION_*} value
     * @return the symbolic name
     */
    public static String chromaSamplePositionName(int value) {
        return switch (value) {
            case CHROMA_SAMPLE_POSITION_UNKNOWN -> "Unknown";
            case CHROMA_SAMPLE_POSITION_VERTICAL -> "Vertical";
            case CHROMA_SAMPLE_POSITION_COLOCATED -> "Colocated";
            default -> "Unknown(" + value + ")";
        };
    }

    /**
     * @param value an {@code AVIF_COLOR_PRIMARIES_*} value
     * @return the symbolic name
     */
    public static String colorPrimariesName(int value) {
        return switch (value) {
            case COLOR_PRIMARIES_UNKNOWN -> "Unknown";
            case COLOR_PRIMARIES_BT709 -> "BT709";
            case COLOR_PRIMARIES_UNSPECIFIED -> "Unspecified";
            case COLOR_PRIMARIES_BT470M -> "BT470M";
            case COLOR_PRIMARIES_BT470BG -> "BT470BG";
            case COLOR_PRIMARIES_BT601 -> "BT601";
            case COLOR_PRIMARIES_SMPTE240 -> "SMPTE240";
            case COLOR_PRIMARIES_GENERIC_FILM -> "GenericFilm";
            case COLOR_PRIMARIES_BT2020 -> "BT2020";
            case COLOR_PRIMARIES_XYZ -> "XYZ";
            case COLOR_PRIMARIES_SMPTE431 -> "SMPTE431";
            case COLOR_PRIMARIES_SMPTE432 -> "SMPTE432";
            case COLOR_PRIMARIES_EBU3213 -> "EBU3213";
            default -> "Unknown(" + value + ")";
        };
    }

    /**
     * @param value an {@code AVIF_TRANSFER_CHARACTERISTICS_*} value
     * @return the symbolic name
     */
    public static String transferName(int value) {
        return switch (value) {
            case TRANSFER_UNKNOWN -> "Unknown";
            case TRANSFER_BT709 -> "BT709";
            case TRANSFER_UNSPECIFIED -> "Unspecified";
            case TRANSFER_BT470M -> "BT470M";
            case TRANSFER_BT470BG -> "BT470BG";
            case TRANSFER_BT601 -> "BT601";
            case TRANSFER_SMPTE240 -> "SMPTE240";
            case TRANSFER_LINEAR -> "Linear";
            case TRANSFER_LOG100 -> "Log100";
            case TRANSFER_LOG100_SQRT10 -> "Log100Sqrt10";
            case TRANSFER_IEC61966 -> "IEC61966";
            case TRANSFER_BT1361 -> "BT1361";
            case TRANSFER_SRGB -> "SRGB";
            case TRANSFER_BT2020_10BIT -> "BT2020_10Bit";
            case TRANSFER_BT2020_12BIT -> "BT2020_12Bit";
            case TRANSFER_PQ -> "PQ";
            case TRANSFER_SMPTE428 -> "SMPTE428";
            case TRANSFER_HLG -> "HLG";
            default -> "Unknown(" + value + ")";
        };
    }

    /**
     * @param value an {@code AVIF_MATRIX_COEFFICIENTS_*} value
     * @return the symbolic name
     */
    public static String matrixCoefficientsName(int value) {
        return switch (value) {
            case MATRIX_IDENTITY -> "Identity";
            case MATRIX_BT709 -> "BT709";
            case MATRIX_UNSPECIFIED -> "Unspecified";
            case MATRIX_FCC -> "FCC";
            case MATRIX_BT470BG -> "BT470BG";
            case MATRIX_BT601 -> "BT601";
            case MATRIX_SMPTE240 -> "SMPTE240";
            case MATRIX_YCGCO -> "YCGCO";
            case MATRIX_BT2020_NCL -> "BT2020_NCL";
            case MATRIX_BT2020_CL -> "BT2020_CL";
            case MATRIX_SMPTE2085 -> "SMPTE2085";
            case MATRIX_CHROMA_DERIVED_NCL -> "ChromaDerived_NCL";
            case MATRIX_CHROMA_DERIVED_CL -> "ChromaDerived_CL";
            case MATRIX_ICTCP -> "ICtCp";
            default -> "Unknown(" + value + ")";
        };
    }

    // ------------------------------------------------------------------------------------- decode

    /**
     * Decodes an AVIF file.
     *
     * <p>The pixels are a {@link BufferedImage#TYPE_4BYTE_ABGR} image, whose banks are the very
     * {@code A, B, G, R} layout the shim produces, and the alpha channel is fully opaque when the
     * file carries none.
     *
     * @param encoded the complete AVIF file
     * @return the decoded image and its properties
     * @throws AvifException when the library is unavailable or the file cannot be decoded
     */
    public static DecodedImage decode(byte[] encoded) throws AvifException {
        return decode(encoded, defaultThreads());
    }

    /**
     * Decodes an AVIF file with an explicit thread count.
     *
     * @param encoded the complete AVIF file
     * @param threads the number of threads libavif may use
     * @return the decoded image and its properties
     * @throws AvifException when the library is unavailable or the file cannot be decoded
     */
    public static DecodedImage decode(byte[] encoded, int threads) throws AvifException {
        AvifShim shim = requireShim();
        if (encoded == null) {
            throw new AvifException("no input");
        }
        if (!isAvif(encoded)) {
            throw new AvifException("not an AVIF file: no avif or avis brand in the ftyp box");
        }
        // One arena, holding the encoded bytes, the slots the shim answers through, and nothing
        // else. The picture is not in it: the shim allocated that on the C heap and owns it, and it
        // is destroyed by hand below rather than by closing an arena over memory it does not own.
        //
        // The encoded bytes are copied in rather than pinned, and the reason is what libavif does
        // with them. avifDecoderSetIOMemory reads the header out of the bytes straight away and then
        // copies the parts it goes on to use, so the array is not read after that call returns and
        // there is nothing to hold a pin for. Pinning here would be pinning for as long as the
        // picture lives, which is the whole of a decode, and a decode is exactly the call a long
        // collection should not be waiting behind.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(Math.max(encoded.length, 1));
            data.copyFrom(MemorySegment.ofArray(encoded));
            MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
            int status = shim.pictureDecode(data, encoded.length, threads, out);
            if (status != 0) {
                throw new AvifException("avif decode failed (" + status + ")");
            }
            MemorySegment picture = out.get(ValueLayout.ADDRESS, 0);
            if (picture.address() == 0) {
                throw new AvifException("libavif produced no picture");
            }
            try {
                AvifImageInfo info = describe(shim, picture, arena);
                byte[] abgr;
                if (shim.hasPictureToAbgrInto()) {
                    // Decode straight into the array that will back the returned image.
                    abgr = new byte[info.width() * info.height() * 4];
                    status = shim.pictureToAbgrInto(picture, threads, MemorySegment.ofArray(abgr), abgr.length);
                    if (status != 0) {
                        throw new AvifException("avif colour conversion failed (" + status + ")");
                    }
                } else {
                    MemorySegment pixels = arena.allocate(ValueLayout.ADDRESS);
                    MemorySegment length = arena.allocate(ValueLayout.JAVA_LONG);
                    status = shim.pictureToAbgr(picture, threads, pixels, length);
                    if (status != 0) {
                        throw new AvifException("avif colour conversion failed (" + status + ")");
                    }
                    MemorySegment buffer = pixels.get(ValueLayout.ADDRESS, 0);
                    int count = (int) length.get(ValueLayout.JAVA_LONG, 0);
                    if (buffer.address() == 0 || count <= 0) {
                        throw new AvifException("libavif produced no pixels");
                    }
                    abgr = buffer.reinterpret(count).toArray(ValueLayout.JAVA_BYTE);
                }
                return new DecodedImage(
                        AbgrPixels.toBufferedImage(abgr, info.width(), info.height()), info);
            } finally {
                // The picture holds the decoder that holds the image, and one destroy does both.
                shim.pictureDestroy(picture);
            }
        }
    }

    // ------------------------------------------------------------------------------------- encode

    /**
     * Encodes an image as AVIF using the default quality and libavif's own speed and alpha quality.
     *
     * @param source the image to encode
     * @return the complete AVIF file
     * @throws AvifException when the library is unavailable or the image cannot be encoded
     */
    public static byte[] encode(RenderedImage source) throws AvifException {
        return encode(source, DEFAULT_QUALITY, null, null);
    }

    /**
     * Encodes an image as AVIF at the default alpha quality.
     *
     * @param source the image to encode; any {@link RenderedImage} is accepted
     * @param quality 0 (smallest) to 100 (lossless)
     * @param speed 0 (slowest, best quality) to 10 (fastest, worst quality)
     * @return the complete AVIF file
     * @throws AvifException when the library is unavailable or the image cannot be encoded
     */
    public static byte[] encode(RenderedImage source, int quality, int speed) throws AvifException {
        return encode(source, quality, Integer.valueOf(speed), null);
    }

    /**
     * Encodes an image as AVIF.
     *
     * <p>A setting that is {@code null} is an absence rather than a value, and libavif's own choice
     * is made in its place: {@link AvifConstants#DEFAULT_SPEED} for the speed and
     * {@link AvifConstants#QUALITY_LOSSLESS} for the alpha quality.
     *
     * @param source the image to encode; any {@link RenderedImage} is accepted
     * @param quality 0 (smallest) to 100 (lossless)
     * @param speed 0 to 10, or {@code null} for libavif's own choice
     * @param alphaQuality 0 to 100, or {@code null} for {@link AvifConstants#QUALITY_LOSSLESS}
     * @return the complete AVIF file
     * @throws AvifException when the library is unavailable or the image cannot be encoded
     */
    public static byte[] encode(RenderedImage source, int quality, Integer speed, Integer alphaQuality)
            throws AvifException {
        return encode(source, quality, speed, alphaQuality, -1, -1);
    }

    /**
     * Encodes an image as AVIF with explicit subsampling and chroma downsampling filter.
     *
     * @param source the image to encode; any {@link RenderedImage} is accepted
     * @param quality 0 (smallest) to 100 (lossless)
     * @param speed 0 to 10, or {@code null} for libavif's own choice
     * @param alphaQuality 0 to 100, or {@code null} for {@link AvifConstants#QUALITY_LOSSLESS}
     * @param pixelFormat an {@code AVIF_PIXEL_FORMAT_*} value, or {@code -1} for the encoder's choice
     * @param chromaDownsampling an {@code AVIF_CHROMA_DOWNSAMPLING_*} value, or {@code -1} for the
     *        encoder's choice
     * @return the complete AVIF file
     * @throws AvifException when the library is unavailable or the image cannot be encoded
     */
    public static byte[] encode(RenderedImage source, int quality, Integer speed, Integer alphaQuality,
            int pixelFormat, int chromaDownsampling) throws AvifException {
        AvifShim shim = requireShim();
        if (source == null) {
            throw new AvifException("no image to encode");
        }
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) {
            throw new AvifException("cannot encode a " + width + "x" + height + " image");
        }
        int yuvFormat = pixelFormat >= 0 ? pixelFormat : PIXEL_FORMAT_YUV444;

        // Try to hand the image's own backing array to libavif. TYPE_4BYTE_ABGR is already A, B, G,
        // R; TYPE_3BYTE_BGR is already B, G, R. Only fall back to a packed ABGR copy when the
        // source is neither or is a subimage/view.
        boolean hasAlpha = source.getColorModel() != null && source.getColorModel().hasAlpha();
        byte[] abgr = null;
        byte[] bgr = null;
        if (source instanceof BufferedImage image) {
            abgr = AbgrPixels.abgrBytesOrNull(image);
            if (abgr == null && !hasAlpha && shim.hasPictureFromBgr()) {
                bgr = AbgrPixels.bgrBytesOrNull(image);
            }
        }
        if (abgr == null && bgr == null) {
            abgr = new byte[width * height * 4];
            AbgrPixels.toAbgrBytes(source, 0, 0, width, height, 1, 1, abgr, 0);
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment picture = shim.pictureCreate(width, height, 8, yuvFormat);
            if (picture.address() == 0) {
                throw new AvifException("cannot create a " + width + "x" + height + " avif picture");
            }
            try {
                // The zero copy, and the reason the shim exists. The pixels are the caller's own
                // array, handed over as a heap segment and pinned by the call rather than copied:
                // the shim reads it in place, so the bytes never go through a native buffer at all.
                int status;
                if (bgr != null) {
                    status = shim.pictureFromBgr(picture, MemorySegment.ofArray(bgr), width * 3,
                            chromaDownsampling);
                } else {
                    status = shim.pictureFromAbgr(picture, MemorySegment.ofArray(abgr), width * 4,
                            chromaDownsampling);
                }
                if (status != 0) {
                    throw new AvifException("avif colour conversion failed (" + status + ")");
                }
                MemorySegment encoded = arena.allocate(ValueLayout.ADDRESS);
                MemorySegment length = arena.allocate(ValueLayout.JAVA_LONG);
                status = shim.pictureEncode(picture, quality, speed != null ? speed : DEFAULT_SPEED,
                        alphaQuality != null ? alphaQuality : QUALITY_LOSSLESS, defaultThreads(), encoded, length);
                if (status != 0) {
                    throw new AvifException("avif encode failed (" + status + ")");
                }
                MemorySegment file = encoded.get(ValueLayout.ADDRESS, 0);
                long size = length.get(ValueLayout.JAVA_LONG, 0);
                if (file.address() == 0 || size <= 0) {
                    throw new AvifException("avif produced no output");
                }
                try {
                    return file.reinterpret(size).toArray(ValueLayout.JAVA_BYTE);
                } finally {
                    shim.free(file);
                }
            } finally {
                shim.pictureDestroy(picture);
            }
        }
    }

    // ------------------------------------------------------------------------------- header

    /**
     * Reads the properties of an AVIF file's container without decoding a pixel.
     *
     * <p>A sequence is opened and its first frame is described, but no frame is walked and no plane
     * is allocated, so this costs a header parse and nothing else. A file that is a still is a
     * sequence of one, so it answers the same way.
     *
     * <p>One field of the answer is not real: {@link AvifImageInfo#hasAlpha()} always reads
     * {@code false} here. Whether a file carries alpha is a property of the alpha plane, and the alpha
     * plane is only allocated when a frame is decoded, so a header parse has nothing to look at.
     * libavif does not surface the sequence header's alpha flag separately. Use {@link #decode} when
     * the answer matters, and treat this one as unknown rather than as false.
     *
     * @param encoded the complete AVIF file
     * @return the image properties, of which {@code hasAlpha} is not one
     * @throws AvifException when the library is unavailable or the file cannot be parsed
     */
    public static AvifImageInfo readHeader(byte[] encoded) throws AvifException {
        AvifShim shim = requireShim();
        if (encoded == null || encoded.length == 0) {
            throw new AvifException("no input data");
        }
        if (!isAvif(encoded)) {
            throw new AvifException("not an AVIF file: no avif or avis brand in the ftyp box");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(encoded.length);
            buffer.copyFrom(MemorySegment.ofArray(encoded));
            MemorySegment sequence = arena.allocate(ValueLayout.ADDRESS);
            if (shim.sequenceOpen(buffer, encoded.length, 1, sequence) != 0) {
                throw new AvifException("could not read the AVIF file");
            }
            MemorySegment open = sequence.get(ValueLayout.ADDRESS, 0);
            if (open.address() == 0) {
                throw new AvifException("could not read the AVIF file");
            }
            try {
                MemorySegment out = arena.allocate(INFO_COUNT * (long) Integer.BYTES, Integer.BYTES);
                MemorySegment frameCount = arena.allocate(ValueLayout.JAVA_INT);
                MemorySegment loops = arena.allocate(ValueLayout.JAVA_INT);
                if (shim.sequenceRead(open, out, frameCount, loops) != 0) {
                    throw new AvifException("could not read the properties of the file");
                }
                return describe(out);
            } finally {
                shim.sequenceClose(open);
            }
        }
    }

    // --------------------------------------------------------------------------------- animation

    /**
     * Encodes a sequence of frames as an animated AVIF file.
     *
     * @param frames the frames, all of the same size, at least one
     * @param durationsMs how long each frame is shown, in milliseconds, one entry per frame
     * @param quality 0 (smallest) to 100 (lossless)
     * @param loopCount how often the animation repeats, 0 meaning forever
     * @return the complete animated AVIF file
     * @throws AvifException when the library is unavailable or the frames cannot be encoded
     */
    public static byte[] encodeAnimation(List<BufferedImage> frames, int[] durationsMs, int quality,
            int loopCount) throws AvifException {
        return encodeAnimation(frames, durationsMs, quality, loopCount, null, null, -1, -1);
    }

    /**
     * Encodes a sequence of frames as an animated AVIF file.
     *
     * <p>A setting that is {@code null} is an absence rather than a value, and libavif's own choice
     * is made in its place: {@link AvifConstants#DEFAULT_ANIMATION_SPEED} for the speed, which is
     * faster than the one for a single image because every frame is encoded, and
     * {@link AvifConstants#QUALITY_LOSSLESS} for the alpha quality.
     *
     * @param frames the frames, all of the same size, at least one
     * @param durationsMs how long each frame is shown, in milliseconds, one entry per frame
     * @param quality 0 (smallest) to 100 (lossless)
     * @param loopCount how often the animation repeats, 0 meaning forever
     * @param speed 0 to 10, or {@code null} for {@link AvifConstants#DEFAULT_ANIMATION_SPEED}
     * @param alphaQuality 0 to 100, or {@code null} for
     *        {@link AvifConstants#QUALITY_LOSSLESS}
     * @return the complete animated AVIF file
     * @throws AvifException when the library is unavailable or the frames cannot be encoded
     */
    public static byte[] encodeAnimation(List<BufferedImage> frames, int[] durationsMs, int quality,
            int loopCount, Integer speed, Integer alphaQuality) throws AvifException {
        return encodeAnimation(frames, durationsMs, quality, loopCount, speed, alphaQuality, -1, -1);
    }

    /**
     * Encodes a sequence of frames as an animated AVIF file with explicit subsampling and chroma
     * downsampling filter.
     *
     * <p>A setting that is {@code -1} is an absence rather than a value, and libavif's own choice is
     * made in its place: {@link AvifConstants#DEFAULT_ANIMATION_SPEED} for the speed,
     * {@link AvifConstants#QUALITY_LOSSLESS} for the alpha quality,
     * {@link AvifConstants#PIXEL_FORMAT_YUV420} for an animation, and
     * {@link AvifConstants#CHROMA_DOWNSAMPLING_AUTOMATIC} for the filter.
     *
     * <p>The frames are packed into one block before they are handed over, and that block is read
     * where it lies: the whole point of the shim's animation entry point is that a hundred frames
     * are not copied a hundred times on the way in.
     *
     * @param frames the frames, all of the same size, at least one
     * @param durationsMs how long each frame is shown, in milliseconds, one entry per frame
     * @param quality 0 (smallest) to 100 (lossless)
     * @param loopCount how often the animation repeats, 0 meaning forever
     * @param speed 0 to 10, or {@code null} for {@link AvifConstants#DEFAULT_ANIMATION_SPEED}
     * @param alphaQuality 0 to 100, or {@code null} for
     *        {@link AvifConstants#QUALITY_LOSSLESS}
     * @param pixelFormat an {@code AVIF_PIXEL_FORMAT_*} value, or {@code -1} for the encoder's choice
     * @param chromaDownsampling an {@code AVIF_CHROMA_DOWNSAMPLING_*} value, or {@code -1} for the
     *        encoder's choice
     * @return the complete animated AVIF file
     * @throws AvifException when the library is unavailable or the frames cannot be encoded
     */
    public static byte[] encodeAnimation(List<BufferedImage> frames, int[] durationsMs, int quality,
            int loopCount, Integer speed, Integer alphaQuality, int pixelFormat,
            int chromaDownsampling) throws AvifException {
        AvifShim shim = requireShim();
        if (frames == null || frames.isEmpty()) {
            throw new AvifException("an animation needs at least one frame, got "
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
        int count = frames.size();
        int frameBytes = width * height * 4;
        // One block for every frame, back to back, because the shim's entry point takes one pointer
        // and a critical call pins one array. A list of pictures would be a copy per frame.
        byte[] packed = new byte[frameBytes * count];
        for (int i = 0; i < count; i++) {
            BufferedImage frame = frames.get(i);
            if (frame == null || frame.getWidth() != width || frame.getHeight() != height) {
                throw new AvifException("every frame must be " + width + "x" + height);
            }
            AbgrPixels.toAbgrBytes(frame, 0, 0, width, height, 1, 1, packed, i * frameBytes);
        }
        int[] durations = new int[count];
        System.arraycopy(durationsMs, 0, durations, 0, count);

        try (Arena arena = Arena.ofConfined()) {
            // The durations go into native memory rather than being pinned, because there are as
            // many of them as there are frames and they are four bytes each, so the copy is a
            // rounding error next to a block of pixels and it costs no pin on the conversion.
            MemorySegment lengths = arena.allocate(Math.max(count, 1) * Integer.BYTES, Integer.BYTES);
            lengths.copyFrom(MemorySegment.ofArray(durations));
            MemorySegment encoded = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment size = arena.allocate(ValueLayout.JAVA_LONG);
            int status = shim.animationEncode(count, MemorySegment.ofArray(packed), lengths, width,
                    height, pixelFormat >= 0 ? pixelFormat : PIXEL_FORMAT_YUV420, chromaDownsampling,
                    quality, speed != null ? speed : DEFAULT_ANIMATION_SPEED,
                    alphaQuality != null ? alphaQuality : QUALITY_LOSSLESS, loopCount, defaultThreads(),
                    encoded, size);
            if (status != 0) {
                throw new AvifException("avif animation encode failed (" + status + ")");
            }
            MemorySegment file = encoded.get(ValueLayout.ADDRESS, 0);
            long bytes = size.get(ValueLayout.JAVA_LONG, 0);
            if (file.address() == 0 || bytes <= 0) {
                throw new AvifException("avif produced no output");
            }
            try {
                return file.reinterpret(bytes).toArray(ValueLayout.JAVA_BYTE);
            } finally {
                shim.free(file);
            }
        }
    }

    /**
     * Decodes every frame of an animated AVIF file.
     *
     * <p>A still is a sequence of one, so this answers a single frame for a still image as well. For
     * a long animation, prefer {@link #openSequence(byte[])} and walk it: this decodes everything,
     * which is what a caller who wants everything should ask for.
     *
     * @param data the complete AVIF file
     * @return the decoded frames, in order
     * @throws AvifException when the library is unavailable or the file cannot be decoded
     */
    public static List<BufferedImage> decodeAnimation(byte[] data) throws AvifException {
        try (AvifSequence sequence = openSequence(data)) {
            List<BufferedImage> frames = new ArrayList<>(sequence.frameCount());
            for (int index = 0; index < sequence.frameCount(); index++) {
                frames.add(sequence.frame(index));
            }
            return frames;
        }
    }

    /**
     * Opens an AVIF file for walking its frames.
     *
     * <p>Asking how many frames there are never decodes a pixel, and asking for one frame decodes
     * only that frame, so a caller that wants the timings and one thumbnail does not pay for the
     * whole sequence.
     *
     * @param data the complete AVIF file
     * @return an open sequence, which the caller has to close
     * @throws AvifException when the library is unavailable or the file cannot be parsed
     */
    public static AvifSequence openSequence(byte[] data) throws AvifException {
        return openSequence(data, defaultThreads());
    }

    /**
     * Opens an AVIF file for walking its frames, with an explicit thread count.
     *
     * @param data the complete AVIF file
     * @param threads the number of threads libavif may use
     * @return an open sequence, which the caller has to close
     * @throws AvifException when the library is unavailable or the file cannot be parsed
     */
    public static AvifSequence openSequence(byte[] data, int threads) throws AvifException {
        AvifShim shim = requireShim();
        if (data == null || data.length == 0) {
            throw new AvifException("no input data");
        }
        if (!isAvif(data)) {
            throw new AvifException("not an AVIF file: no avif or avis brand in the ftyp box");
        }
        // The bytes are copied into native memory rather than pinned, because libavif copies what it
        // goes on to read out of them itself. Pinning here would pin for as long as the sequence is
        // open, which can be a whole application, and would buy nothing.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(data.length);
            buffer.copyFrom(MemorySegment.ofArray(data));
            MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
            if (shim.sequenceOpen(buffer, data.length, threads, out) != 0) {
                throw new AvifException("could not read the AVIF file");
            }
            MemorySegment sequence = out.get(ValueLayout.ADDRESS, 0);
            if (sequence.address() == 0) {
                throw new AvifException("could not open the AVIF file");
            }
            return new AvifSequence(shim, sequence, threads);
        }
    }

    // ------------------------------------------------------------------------------ inspection

    /**
     * Tests whether the given bytes are an AVIF file by looking for the {@code avif} or {@code avis}
     * brand in the leading {@code ftyp} box.
     *
     * <p>The check is pure Java and therefore also works when the native library is missing, which
     * is what lets the {@code ImageIO} plug-in still recognise AVIF files.
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
            if (data.length < 16) {
                return false;
            }
            boxSize = readUInt64(data, 8);
            majorBrand = 16;
        } else {
            majorBrand = 8;
        }
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
                | ((long) (data[offset + 1] & 0xff) << 16)
                | ((long) (data[offset + 2] & 0xff) << 8)
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
        return new String(data, offset, length, java.nio.charset.StandardCharsets.US_ASCII);
    }

    // ----------------------------------------------------------------------------------- helpers

    /**
     * Asks the shim for the properties of a decoded picture.
     *
     * <p>Fifteen four byte out parameters are passed as one block of native memory rather than as
     * fifteen arguments, so the shape of the call is the shim's rather than this binding's, and a
     * field added to the shim's list is one more slot rather than a new signature.
     */
    private static AvifImageInfo describe(AvifShim shim, MemorySegment picture, Arena arena)
            throws AvifException {
        MemorySegment out = arena.allocate(INFO_COUNT * (long) Integer.BYTES, Integer.BYTES);
        if (shim.pictureInfo(picture, out) != 0) {
            throw new AvifException("could not read the properties of the decoded picture");
        }
        return describe(out);
    }

    /**
     * Reads the fifteen answers out of the block the shim filled in.
     *
     * <p>Every offset is a name the shim's header also declares, so the two cannot drift apart in
     * the way a list of bare multiples of four can.
     */
    private static AvifImageInfo describe(MemorySegment out) {
        return new AvifImageInfo(
                at(out, INFO_WIDTH),
                at(out, INFO_HEIGHT),
                at(out, INFO_DEPTH),
                at(out, INFO_YUV_FORMAT),
                at(out, INFO_YUV_RANGE),
                at(out, INFO_CHROMA_SAMPLE_POSITION),
                at(out, INFO_COLOR_PRIMARIES),
                at(out, INFO_TRANSFER_CHARACTERISTICS),
                at(out, INFO_MATRIX_COEFFICIENTS),
                at(out, INFO_HAS_ALPHA) != 0,
                at(out, INFO_ICC_SIZE),
                at(out, INFO_EXIF_SIZE),
                at(out, INFO_XMP_SIZE),
                at(out, INFO_ROTATION_DEGREES),
                at(out, INFO_MIRRORED) != 0
        );
    }

    /** Reads one of the fifteen answers out of the block the shim filled in. */
    private static int at(MemorySegment out, int index) {
        return out.get(ValueLayout.JAVA_INT, (long) index * Integer.BYTES);
    }

    private static AvifShim requireShim() throws AvifException {
        AvifShim shim = AvifLoader.shim();
        if (shim == null) {
            throw new AvifException("libavif is not available: " + AvifLoader.unavailableReason());
        }
        return shim;
    }
}
