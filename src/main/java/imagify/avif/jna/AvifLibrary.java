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

import com.sun.jna.Library;
import com.sun.jna.Pointer;

/**
 * JNA binding for the subset of the {@code libavif} C API that is required to decode and encode
 * AVIF images.
 *
 * <p>The binding is modelled on {@code include/avif/avif.h} of libavif 1.x. Because {@code libavif}
 * only ever appends new members to the end of its public structures, the layouts declared in this
 * package are binary compatible with every {@code libavif} release from 1.0.0 up to and including
 * 1.4.x. Fields that were added after 1.1.0 are simply not exposed.
 *
 * <p>The native library is <em>not</em> loaded by this interface; see {@link AvifCodec} for the lazy,
 * failure tolerant loader.
 *
 * @see <a href="https://github.com/AOMediaCodec/libavif">AOMediaCodec/libavif</a>
 */
public interface AvifLibrary extends Library {

    /** Name of the native library as it is known to the platform dynamic linker. */
    String LIBRARY_NAME = "avif";

    // ---------------------------------------------------------------------------------- avifBool

    int AVIF_FALSE = 0;
    int AVIF_TRUE = 1;

    // ------------------------------------------------------------------------------ avifResult

    int AVIF_RESULT_OK = 0;
    int AVIF_RESULT_UNKNOWN_ERROR = 1;
    int AVIF_RESULT_INVALID_FTYP = 2;
    int AVIF_RESULT_NO_CONTENT = 3;
    int AVIF_RESULT_NO_YUV_FORMAT_SELECTED = 4;
    int AVIF_RESULT_REFORMAT_FAILED = 5;
    int AVIF_RESULT_UNSUPPORTED_DEPTH = 6;
    int AVIF_RESULT_ENCODE_COLOR_FAILED = 7;
    int AVIF_RESULT_ENCODE_ALPHA_FAILED = 8;
    int AVIF_RESULT_BMFF_PARSE_FAILED = 9;
    int AVIF_RESULT_MISSING_IMAGE_ITEM = 10;
    int AVIF_RESULT_DECODE_COLOR_FAILED = 11;
    int AVIF_RESULT_DECODE_ALPHA_FAILED = 12;
    int AVIF_RESULT_COLOR_ALPHA_SIZE_MISMATCH = 13;
    int AVIF_RESULT_ISPE_SIZE_MISMATCH = 14;
    int AVIF_RESULT_NO_CODEC_AVAILABLE = 15;
    int AVIF_RESULT_NO_IMAGES_REMAINING = 16;
    int AVIF_RESULT_INVALID_EXIF_PAYLOAD = 17;
    int AVIF_RESULT_INVALID_IMAGE_GRID = 18;
    int AVIF_RESULT_INVALID_CODEC_SPECIFIC_OPTION = 19;
    int AVIF_RESULT_TRUNCATED_DATA = 20;
    int AVIF_RESULT_IO_NOT_SET = 21;
    int AVIF_RESULT_IO_ERROR = 22;
    int AVIF_RESULT_WAITING_ON_IO = 23;
    int AVIF_RESULT_INVALID_ARGUMENT = 24;
    int AVIF_RESULT_NOT_IMPLEMENTED = 25;
    int AVIF_RESULT_INVALID_STATE = 26;
    int AVIF_RESULT_ENCODER_NOT_FINISHED = 27;
    int AVIF_RESULT_ENCODE_GAIN_MAP_FAILED = 28;
    int AVIF_RESULT_DECODE_GAIN_MAP_FAILED = 29;
    int AVIF_RESULT_INVALID_TONE_MAPPED_IMAGE = 30;
    int AVIF_RESULT_REFORMAT_FAILED_FATAL = 31;

    // -------------------------------------------------------------------------- avifPixelFormat

    int AVIF_PIXEL_FORMAT_NONE = 0;
    int AVIF_PIXEL_FORMAT_YUV444 = 1;
    int AVIF_PIXEL_FORMAT_YUV422 = 2;
    int AVIF_PIXEL_FORMAT_YUV420 = 3;
    int AVIF_PIXEL_FORMAT_YUV400 = 4;
    int AVIF_PIXEL_FORMAT_COUNT = 5;

    // ------------------------------------------------------------------------------ avifRange

    int AVIF_RANGE_LIMITED = 0;
    int AVIF_RANGE_FULL = 1;

    // ------------------------------------------------------------- avifChromaSamplePosition

    int AVIF_CHROMA_SAMPLE_POSITION_UNKNOWN = 0;
    int AVIF_CHROMA_SAMPLE_POSITION_VERTICAL = 1;
    int AVIF_CHROMA_SAMPLE_POSITION_COLOCATED = 2;

    // --------------------------------------------------------------------- avifColorPrimaries

    int AVIF_COLOR_PRIMARIES_UNKNOWN = 0;
    int AVIF_COLOR_PRIMARIES_BT709 = 1;
    int AVIF_COLOR_PRIMARIES_UNSPECIFIED = 2;
    int AVIF_COLOR_PRIMARIES_BT470M = 4;
    int AVIF_COLOR_PRIMARIES_BT470BG = 5;
    int AVIF_COLOR_PRIMARIES_BT601 = 6;
    int AVIF_COLOR_PRIMARIES_SMPTE240 = 7;
    int AVIF_COLOR_PRIMARIES_GENERIC_FILM = 8;
    int AVIF_COLOR_PRIMARIES_BT2020 = 9;
    int AVIF_COLOR_PRIMARIES_XYZ = 10;
    int AVIF_COLOR_PRIMARIES_SMPTE431 = 11;
    int AVIF_COLOR_PRIMARIES_SMPTE432 = 12;
    int AVIF_COLOR_PRIMARIES_EBU3213 = 22;

    // ------------------------------------------------------------- avifTransferCharacteristics

    int AVIF_TRANSFER_CHARACTERISTICS_UNKNOWN = 0;
    int AVIF_TRANSFER_CHARACTERISTICS_BT709 = 1;
    int AVIF_TRANSFER_CHARACTERISTICS_UNSPECIFIED = 2;
    int AVIF_TRANSFER_CHARACTERISTICS_BT470M = 4;
    int AVIF_TRANSFER_CHARACTERISTICS_BT470BG = 5;
    int AVIF_TRANSFER_CHARACTERISTICS_BT601 = 6;
    int AVIF_TRANSFER_CHARACTERISTICS_SMPTE240 = 7;
    int AVIF_TRANSFER_CHARACTERISTICS_LINEAR = 8;
    int AVIF_TRANSFER_CHARACTERISTICS_LOG100 = 9;
    int AVIF_TRANSFER_CHARACTERISTICS_LOG100_SQRT10 = 10;
    int AVIF_TRANSFER_CHARACTERISTICS_IEC61966 = 11;
    int AVIF_TRANSFER_CHARACTERISTICS_BT1361 = 12;
    int AVIF_TRANSFER_CHARACTERISTICS_SRGB = 13;
    int AVIF_TRANSFER_CHARACTERISTICS_BT2020_10BIT = 14;
    int AVIF_TRANSFER_CHARACTERISTICS_BT2020_12BIT = 15;
    int AVIF_TRANSFER_CHARACTERISTICS_PQ = 16;
    int AVIF_TRANSFER_CHARACTERISTICS_SMPTE428 = 17;
    int AVIF_TRANSFER_CHARACTERISTICS_HLG = 18;

    // --------------------------------------------------------------- avifMatrixCoefficients

    int AVIF_MATRIX_COEFFICIENTS_IDENTITY = 0;
    int AVIF_MATRIX_COEFFICIENTS_BT709 = 1;
    int AVIF_MATRIX_COEFFICIENTS_UNSPECIFIED = 2;
    int AVIF_MATRIX_COEFFICIENTS_FCC = 4;
    int AVIF_MATRIX_COEFFICIENTS_BT470BG = 5;
    int AVIF_MATRIX_COEFFICIENTS_BT601 = 6;
    int AVIF_MATRIX_COEFFICIENTS_SMPTE240 = 7;
    int AVIF_MATRIX_COEFFICIENTS_YCGCO = 8;
    int AVIF_MATRIX_COEFFICIENTS_BT2020_NCL = 9;
    int AVIF_MATRIX_COEFFICIENTS_BT2020_CL = 10;
    int AVIF_MATRIX_COEFFICIENTS_SMPTE2085 = 11;
    int AVIF_MATRIX_COEFFICIENTS_CHROMA_DERIVED_NCL = 12;
    int AVIF_MATRIX_COEFFICIENTS_CHROMA_DERIVED_CL = 13;
    int AVIF_MATRIX_COEFFICIENTS_ICTCP = 14;

    // ------------------------------------------------------------------------- avifTransformFlags

    int AVIF_TRANSFORM_NONE = 0;
    int AVIF_TRANSFORM_PASP = 1;
    int AVIF_TRANSFORM_CLAP = 1 << 1;
    int AVIF_TRANSFORM_IROT = 1 << 2;
    int AVIF_TRANSFORM_IMIR = 1 << 3;

    // ---------------------------------------------------------------------------- avifRGBFormat

    int AVIF_RGB_FORMAT_RGB = 0;
    int AVIF_RGB_FORMAT_RGBA = 1;
    int AVIF_RGB_FORMAT_ARGB = 2;
    int AVIF_RGB_FORMAT_BGR = 3;
    int AVIF_RGB_FORMAT_BGRA = 4;
    /**
     * The in-memory layout of a {@link java.awt.image.BufferedImage#TYPE_4BYTE_ABGR} raster.
     *
     * <p>That raster declares band offsets of {@code 3, 2, 1, 0}, so the byte at offset 0 of a pixel
     * is its alpha and the byte at offset 3 is its red. Passing the raster to libavif under
     * {@link #AVIF_RGB_FORMAT_RGBA} therefore swaps red and blue in both directions without ever
     * failing; see {@code AbgrPixels}.
     */
    int AVIF_RGB_FORMAT_ABGR = 5;
    int AVIF_RGB_FORMAT_RGB_565 = 6;
    int AVIF_RGB_FORMAT_COUNT = 7;

    // --------------------------------------------------------------------- avifChromaUpsampling

    int AVIF_CHROMA_UPSAMPLING_AUTOMATIC = 0;
    int AVIF_CHROMA_UPSAMPLING_FASTEST = 1;
    int AVIF_CHROMA_UPSAMPLING_BEST_QUALITY = 2;
    int AVIF_CHROMA_UPSAMPLING_NEAREST = 3;
    int AVIF_CHROMA_UPSAMPLING_BILINEAR = 4;

    // ------------------------------------------------------------------- avifChromaDownsampling

    int AVIF_CHROMA_DOWNSAMPLING_AUTOMATIC = 0;
    int AVIF_CHROMA_DOWNSAMPLING_FASTEST = 1;
    int AVIF_CHROMA_DOWNSAMPLING_BEST_QUALITY = 2;
    int AVIF_CHROMA_DOWNSAMPLING_AVERAGE = 3;
    int AVIF_CHROMA_DOWNSAMPLING_SHARP_YUV = 4;

    // ------------------------------------------------------------------------ avifPlanesFlag

    int AVIF_PLANES_YUV = 1;
    int AVIF_PLANES_A = 1 << 1;
    int AVIF_PLANES_ALL = 0xff;

    // ------------------------------------------------------------------------ avifStrictFlags

    int AVIF_STRICT_DISABLED = 0;
    int AVIF_STRICT_PIXI_REQUIRED = 1;
    int AVIF_STRICT_CLAP_VALID = 1 << 1;
    int AVIF_STRICT_ALPHA_ISPE_REQUIRED = 1 << 2;
    int AVIF_STRICT_ENABLED = AVIF_STRICT_PIXI_REQUIRED | AVIF_STRICT_CLAP_VALID | AVIF_STRICT_ALPHA_ISPE_REQUIRED;

    // ------------------------------------------------------------------------ avifAddImageFlag

    int AVIF_ADD_IMAGE_FLAG_NONE = 0;
    int AVIF_ADD_IMAGE_FLAG_FORCE_KEYFRAME = 1;
    int AVIF_ADD_IMAGE_FLAG_SINGLE = 1 << 1;

    // ------------------------------------------------------------------- encoder quality range

    /** Use the codec specific default quality. */
    int AVIF_QUALITY_DEFAULT = -1;
    int AVIF_QUALITY_WORST = 0;
    int AVIF_QUALITY_BEST = 100;
    int AVIF_QUALITY_LOSSLESS = 100;

    // ------------------------------------------------------------------------------ constants

    int AVIF_PLANE_COUNT_YUV = 3;

    /** Default quality used by the image writer when the caller does not specify one. */
    int DEFAULT_QUALITY = 60;
    /** Default encoder speed (0 = slowest/best, 10 = fastest/worst). */
    int DEFAULT_SPEED = 6;

    // ------------------------------------------------------------------------------ functions

    /**
     * Returns the {@code libavif} version, for example {@code "1.3.0 (a1b2c3d)"}.
     *
     * @return the version string, never {@code null}
     */
    String avifVersion();

    /**
     * Converts an {@code avifResult} into a human readable message.
     *
     * @param result an {@code AVIF_RESULT_*} value
     * @return the message
     */
    String avifResultToString(int result);

    /**
     * Tests whether the given bytes look like an AVIF file.
     *
     * @param input the encoded data, at least 12 bytes long
     * @return {@link #AVIF_TRUE} or {@link #AVIF_FALSE}
     */
    int avifPeekCompatibleFileType(AvifROData input);

    // --------------------------------------------------------------------------------- avifImage

    /**
     * Creates a new {@code avifImage} with the given geometry and pre-allocated YUV (and alpha)
     * planes. The image owns its planes; free it with {@link #avifImageDestroy(AvifImage)}.
     *
     * @param width image width in pixels
     * @param height image height in pixels
     * @param depth bits per channel, one of 8, 10 or 12
     * @param yuvFormat an {@code AVIF_PIXEL_FORMAT_*} value
     * @return the new image, never {@code null}
     */
    AvifImage avifImageCreate(int width, int height, int depth, int yuvFormat);

    /**
     * Destroys an image created by this library and releases all memory it owns.
     *
     * @param image the image to destroy
     */
    void avifImageDestroy(AvifImage image);

    /**
     * @param image the image to inspect
     * @return {@link #AVIF_TRUE} when the image has no alpha channel or a fully opaque one
     */
    int avifImageIsOpaque(AvifImage image);

    // ------------------------------------------------------------------------------ avifRGBImage

    /**
     * Fills {@code rgb} with sensible defaults derived from {@code image}. Always resets
     * {@code format} to {@code AVIF_RGB_FORMAT_RGBA} and {@code depth} to 8, so both may be
     * overridden afterwards, in which case {@code rowBytes} has to be derived again.
     *
     * @param rgb the structure to initialise
     * @param image the associated image, or {@code null}
     */
    void avifRGBImageSetDefaults(AvifRGBImage rgb, AvifImage image);

    /**
     * @param rgb the structure to inspect
     * @return the number of bytes per pixel for the configured format and depth
     */
    int avifRGBImagePixelSize(AvifRGBImage rgb);

    /**
     * Allocates {@code rgb.pixels} using {@code rgb.rowBytes * rgb.height}.
     *
     * @param rgb the structure whose buffers should be allocated
     * @return an {@code AVIF_RESULT_*} value
     */
    int avifRGBImageAllocatePixels(AvifRGBImage rgb);

    /**
     * Frees {@code rgb.pixels}.
     *
     * @param rgb the structure whose buffers should be released
     */
    void avifRGBImageFreePixels(AvifRGBImage rgb);

    // --------------------------------------------------------------------- colour conversion

    /**
     * Converts {@code rgb} into the YUV/alpha planes of {@code image}.
     *
     * @param image destination image
     * @param rgb source pixels
     * @return an {@code AVIF_RESULT_*} value
     */
    int avifImageRGBToYUV(AvifImage image, AvifRGBImage rgb);

    /**
     * Converts the YUV/alpha planes of {@code image} into {@code rgb}.
     *
     * @param image source image
     * @param rgb destination pixels
     * @return an {@code AVIF_RESULT_*} value
     */
    int avifImageYUVToRGB(AvifImage image, AvifRGBImage rgb);

    // ----------------------------------------------------------------------------- avifDecoder

    /**
     * @return a new decoder, never {@code null}
     */
    AvifDecoder avifDecoderCreate();

    /**
     * Destroys a decoder and the image currently referenced by it.
     *
     * @param decoder the decoder to destroy
     */
    void avifDecoderDestroy(AvifDecoder decoder);

    /**
     * Points the decoder at an in-memory encoded image. The buffer must stay valid and unmodified
     * for the whole lifetime of the decoder.
     *
     * @param decoder the decoder
     * @param data pointer to the encoded bytes
     * @param size number of encoded bytes
     * @return an {@code AVIF_RESULT_*} value
     */
    int avifDecoderSetIOMemory(AvifDecoder decoder, Pointer data, long size);

    /**
     * Parses the container headers. Afterwards {@code decoder.image} describes the first image.
     *
     * @param decoder the decoder
     * @return an {@code AVIF_RESULT_*} value
     */
    int avifDecoderParse(AvifDecoder decoder);

    /**
     * Decodes the image the decoder currently points at.
     *
     * @param decoder the decoder
     * @return an {@code AVIF_RESULT_*} value
     */
    int avifDecoderNextImage(AvifDecoder decoder);

    /**
     * Rewinds a parsed decoder so that it can be used again.
     *
     * @param decoder the decoder
     * @return an {@code AVIF_RESULT_*} value
     */
    int avifDecoderReset(AvifDecoder decoder);

    // ----------------------------------------------------------------------------- avifEncoder

    /**
     * @return a new encoder, never {@code null}
     */
    AvifEncoder avifEncoderCreate();

    /**
     * Destroys an encoder.
     *
     * @param encoder the encoder to destroy
     */
    void avifEncoderDestroy(AvifEncoder encoder);

    /**
     * Adds a single still image to the encoder.
     *
     * @param encoder the encoder
     * @param image the image to encode
     * @param durationInTimescales display duration, in {@code encoder.timescale} units
     * @param addImageFlags an {@code AVIF_ADD_IMAGE_FLAG_*} value
     * @return an {@code AVIF_RESULT_*} value
     */
    int avifEncoderAddImage(AvifEncoder encoder, AvifImage image, long durationInTimescales, int addImageFlags);

    /**
     * Finishes the encode and writes the complete AVIF file to {@code output}.
     *
     * @param encoder the encoder
     * @param output receives the encoded file
     * @return an {@code AVIF_RESULT_*} value
     */
    int avifEncoderFinish(AvifEncoder encoder, AvifRWData output);

    // ---------------------------------------------------------------------------- avifRWData

    /**
     * Frees the buffer held by {@code raw} and resets the structure.
     *
     * @param raw the buffer holder
     */
    void avifRWDataFree(AvifRWData raw);
}
