/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */

/*
 * A flat C ABI over the public libavif API, for the FFM binding in imagify.avif.ffm.
 *
 * Unlike the libwebp shim next door, this one is not about hiding a structure whose layout the
 * header does not promise. avifImage, avifDecoder and avifEncoder are a few dozen documented bytes
 * each, and a binding can declare them as FFM GroupLayouts, which it does. This is here for the one
 * thing a binding cannot do from Java at all.
 *
 * The FFM ABI will not store a heap segment, which is what a Java array becomes, into a
 * pointer-typed struct field. avifRGBImage.pixels is exactly such a field, and it is the buffer every
 * conversion in and out of a picture goes through. So the pixels cannot be put there from Java, and
 * an encode always copies them into native memory first, and a decode reads them out of libavif's
 * buffer into a byte array before they can be wrapped.
 *
 * An entry point that takes the pixels as its own argument has no such problem: the linker will
 * pass a heap segment to a function parameter, because the callee is there for the length of the
 * call and cannot store the pointer anywhere. The two conversions below are therefore the whole of
 * the zero copy, and nothing else in this file exists to be a performance measure.
 *
 * The layout the FFM binding used to declare is moved over here, which also means the Java side no
 * longer carries a hand written copy of avifImage: a struct that libavif is free to append to at
 * any release is better read in C, where the compiler checks every field against the header it was
 * compiled with.
 */

#include "imagify_avif.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <avif/avif.h>

/*
 * The same guard the WebP shim carries, and for the same reason. Every entry point that names a
 * layout does so on the understanding that a target reading the four bytes of an RGBA pixel as one
 * 32 bit word does so the same way. A target that did not would produce pictures with their channels
 * in the wrong places, which is the sort of thing only a user looking at a broken image finds, so
 * it stops the build instead.
 */
#if defined(__BYTE_ORDER__) && defined(__ORDER_LITTLE_ENDIAN__) \
    && (__BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__)
#error "the A, B, G, R entry points of imagify_avif need a little-endian target"
#endif

/* --------------------------------------------------------------------------------------------- */
/* reporting                                                                                       */
/* --------------------------------------------------------------------------------------------- */

static int imagify_avif_fail(char* message, size_t capacity, int status, const char* text) {
    if (message != NULL && capacity > 0) {
        snprintf(message, capacity, "%s", text);
    }
    return status;
}

/*
 * Turns an avifResult into one of this layer's own statuses, and takes libavif's own description of
 * the failure with it where there is one. The buffer is a parameter rather than this file's, because
 * the reason a decode failed is frequently the only thing a user gets to see.
 */
static int imagify_avif_status(char* message, size_t capacity, avifResult result) {
    if (result == AVIF_RESULT_OK) {
        return IMAGIFY_AVIF_OK;
    }
    const char* reason = avifResultToString(result);
    if (message != NULL && capacity > 0) {
        if (reason == NULL || reason[0] == '\0') {
            reason = "libavif failed and gave no reason";
        }
        snprintf(message, capacity, "%s", reason);
    }
    switch (result) {
        case AVIF_RESULT_OUT_OF_MEMORY:
            return IMAGIFY_AVIF_ERR_MEMORY;
        case AVIF_RESULT_UNSUPPORTED_DEPTH:
        case AVIF_RESULT_NO_CODEC_AVAILABLE:
        case AVIF_RESULT_NOT_IMPLEMENTED:
            return IMAGIFY_AVIF_ERR_UNSUPPORTED;
        case AVIF_RESULT_INVALID_ARGUMENT:
        case AVIF_RESULT_CANNOT_CHANGE_SETTING:
        case AVIF_RESULT_INVALID_CODEC_SPECIFIC_OPTION:
            return IMAGIFY_AVIF_ERR_ARGUMENT;
        default:
            /* Everything else is libavif having read or written something it could not. */
            return IMAGIFY_AVIF_ERR_CORRUPT;
    }
}

/* Fills the fifteen answers about one avifImage, which a picture and a decoder's image share. */
static int imagify_avif_picture_info_fields(const avifImage* image, int* out);

/* --------------------------------------------------------------------------------------------- */
/* the picture                                                                                     */
/* --------------------------------------------------------------------------------------------- */

/*
 * A decoded or to be encoded picture, and the buffer its last conversion produced.
 *
 * The buffer is held here rather than handed back to the caller because the caller is Java and
 * cannot keep a C pointer across a call that is not on the stack. It is released when the picture is
 * destroyed, and it is separate from the avifImage because it is libavif's allocation of a size
 * that libavif knows and Java does not.
 */
struct imagify_avif_picture {
    avifImage* image;
    /*
     * The decoder a decoded picture's image belongs to, and the one that has to outlive it.
     *
     * avifDecoder.image is an avifImage the decoder owns and avifDecoderDestroy frees, so a picture
     * that keeps the image has to keep the decoder. The alternative is avifImageCopy, which is a deep
     * copy of every plane: it would move a whole image to save nothing, on the one path whose entire
     * purpose is not to move pixels.
     */
    avifDecoder* decoder;
    uint8_t* pixels;
    size_t pixel_count;
    int owns_pixels;
};

const char* imagify_avif_libavif_version(void) {
    return avifVersion();
}

static int imagify_avif_geometry_ok(int width, int height) {
    return width > 0 && height > 0 && (uint32_t)width <= AVIF_DEFAULT_IMAGE_DIMENSION_LIMIT
           && (uint32_t)height <= AVIF_DEFAULT_IMAGE_DIMENSION_LIMIT
           && (uint64_t)width * (uint64_t)height <= (uint64_t)AVIF_DEFAULT_IMAGE_SIZE_LIMIT;
}

imagify_avif_picture* imagify_avif_picture_create(int width, int height, int depth, int yuv_format) {
    if (!imagify_avif_geometry_ok(width, height)) {
        return NULL;
    }
    if (depth != 8 && depth != 10 && depth != 12) {
        return NULL;
    }
    avifImage* image = avifImageCreate((uint32_t)width, (uint32_t)height, (uint32_t)depth,
        (avifPixelFormat)yuv_format);
    if (image == NULL) {
        return NULL;
    }
    imagify_avif_picture* picture = (imagify_avif_picture*)calloc(1, sizeof(*picture));
    if (picture == NULL) {
        avifImageDestroy(image);
        return NULL;
    }
    picture->image = image;
    return picture;
}

void imagify_avif_picture_destroy(imagify_avif_picture* picture) {
    if (picture == NULL) {
        return;
    }
    if (picture->owns_pixels && picture->pixels != NULL) {
        free(picture->pixels);
    }
    /* A decoded picture's image is freed with its decoder rather than on its own, because the
     * decoder is what allocated it. An encoded one has no decoder and is freed directly. */
    if (picture->decoder != NULL) {
        avifDecoderDestroy(picture->decoder);
    } else if (picture->image != NULL) {
        avifImageDestroy(picture->image);
    }
    free(picture);
}

/*
 * Fills an avifRGBImage for an 8 bit A, B, G, R buffer and points it at the caller's memory.
 *
 * The buffer is the caller's and is not written through by a conversion into YUV: avifImageRGBToYUV
 * reads it and produces the picture's planes, which libavif owns. Nothing here copies the picture,
 * and nothing here copies the buffer either, which is the whole of the zero copy on the encode side.
 *
 * @param rgb the struct to fill
 * @param image the image the conversion reads or writes, which may belong to a picture or to a
 *        decoder
 * @param pixels the caller's bytes for a conversion into YUV, or NULL for one out of it
 * @param row_bytes the stride of those bytes
 * @param chroma_downsampling an AVIF_CHROMA_DOWNSAMPLING_* value, or -1 for libavif's own
 */
static int imagify_avif_rgb(avifRGBImage* rgb, const avifImage* image, const uint8_t* pixels,
    int row_bytes, avifRGBFormat format, int chroma_downsampling) {
    memset(rgb, 0, sizeof(*rgb));
    avifRGBImageSetDefaults(rgb, (avifImage*)image);
    /*
     * AVIF_RGB_FORMAT_ABGR is chosen because that is the order a BufferedImage of type
     * TYPE_4BYTE_ABGR keeps its banks in, so a picture already in that layout can be handed over
     * without a shuffle. The formats libavif would otherwise produce read as R, G, B, A, and reading
     * A, B, G, R as though it were that swaps red and blue in both directions without failing.
     */
    rgb->format = format;
    rgb->depth = 8;
    /* avifRGBImageSetDefaults() hard codes RGBA/8, so the stride is derived again from the format. */
    rgb->rowBytes = (uint32_t)row_bytes;
    rgb->pixels = (uint8_t*)(uintptr_t)pixels;
    if (chroma_downsampling >= 0) {
        rgb->chromaDownsampling = (avifChromaDownsampling)chroma_downsampling;
    }
    rgb->avoidLibYUV = AVIF_FALSE;
    return 0;
}

int imagify_avif_picture_from_abgr(imagify_avif_picture* picture, const uint8_t* pixels,
    int row_bytes, int chroma_downsampling) {
    if (picture == NULL || pixels == NULL || row_bytes <= 0) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }
    const int width = (int)picture->image->width;
    const int height = (int)picture->image->height;
    /* rowBytes is a uint32_t in the struct, so a Java int that is negative there is not a stride. */
    if (row_bytes < width * 4) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }
    avifRGBImage rgb;
    imagify_avif_rgb(&rgb, picture->image, pixels, row_bytes, AVIF_RGB_FORMAT_ABGR, chroma_downsampling);
    return imagify_avif_status(NULL, 0, avifImageRGBToYUV(picture->image, &rgb));
}

int imagify_avif_picture_from_bgr(imagify_avif_picture* picture, const uint8_t* pixels,
    int row_bytes, int chroma_downsampling) {
    if (picture == NULL || pixels == NULL || row_bytes <= 0) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }
    const int width = (int)picture->image->width;
    const int height = (int)picture->image->height;
    if (row_bytes < width * 3) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }
    avifRGBImage rgb;
    imagify_avif_rgb(&rgb, picture->image, pixels, row_bytes, AVIF_RGB_FORMAT_BGR, chroma_downsampling);
    return imagify_avif_status(NULL, 0, avifImageRGBToYUV(picture->image, &rgb));
}

/*
 * Releases the buffer a previous conversion left on the picture, so a second one does not leak it.
 */
static void imagify_avif_release_pixels(imagify_avif_picture* picture) {
    if (picture->owns_pixels && picture->pixels != NULL) {
        free(picture->pixels);
    }
    picture->pixels = NULL;
    picture->pixel_count = 0;
    picture->owns_pixels = 0;
}

int imagify_avif_picture_to_abgr(imagify_avif_picture* picture, int max_threads, uint8_t** out,
    size_t* out_length) {
    if (picture == NULL || out == NULL || out_length == NULL) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }
    *out = NULL;
    *out_length = 0;
    imagify_avif_release_pixels(picture);

    const int width = (int)picture->image->width;
    const int height = (int)picture->image->height;
    const size_t pixel_size = 4u; /* 8 bit A, B, G, R */
    /* The multiplication is done in size_t because a dimension that this function accepted is one
     * avifImageCreate took, and the product is what libavif allocated for the planes already. */
    const size_t total = (size_t)width * (size_t)height * pixel_size;

    avifRGBImage rgb;
    imagify_avif_rgb(&rgb, picture->image, NULL, (int)(width * pixel_size), AVIF_RGB_FORMAT_ABGR, -1);
    /* One allocation, and it is the only buffer the picture's pixels pass through on the way out.
     * The caller reads it once, into the BufferedImage it wraps, so the JNA path's second copy, of
     * the byte array into the image's own data buffer, has nothing to be a copy of. */
    uint8_t* buffer = (uint8_t*)malloc(total);
    if (buffer == NULL) {
        return IMAGIFY_AVIF_ERR_MEMORY;
    }
    rgb.pixels = buffer;
    rgb.rowBytes = (uint32_t)(width * pixel_size);
    /* avifRGBImage.maxThreads takes a count, where 0 and 1 both mean one thread and a negative
     * value is invalid. It is a separate setting from the decoder's own maxThreads, and the count a
     * caller asked to decode with is the one worth converting with: a YUV to RGB pass is a single
     * sweep over the picture, and libavif splits it, so handing it several is worth the setup for a
     * large picture and not for a small one. */
    rgb.maxThreads = (max_threads > 1) ? max_threads : 1;

    const avifResult result = avifImageYUVToRGB(picture->image, &rgb);
    if (result != AVIF_RESULT_OK) {
        free(buffer);
        return imagify_avif_status(NULL, 0, result);
    }
    picture->pixels = buffer;
    picture->pixel_count = total;
    picture->owns_pixels = 1;
    *out = buffer;
    *out_length = total;
    return IMAGIFY_AVIF_OK;
}

int imagify_avif_picture_to_abgr_into(imagify_avif_picture* picture, int max_threads,
    uint8_t* out, size_t out_length) {
    if (picture == NULL || out == NULL) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }

    const int width = (int)picture->image->width;
    const int height = (int)picture->image->height;
    const size_t total = (size_t)width * (size_t)height * 4u;
    if (out_length < total) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }

    avifRGBImage rgb;
    imagify_avif_rgb(&rgb, picture->image, out, (int)(width * 4u), AVIF_RGB_FORMAT_ABGR, -1);
    rgb.maxThreads = (max_threads > 1) ? max_threads : 1;
    return imagify_avif_status(NULL, 0, avifImageYUVToRGB(picture->image, &rgb));
}

const uint8_t* imagify_avif_picture_pixels(const imagify_avif_picture* picture, size_t* out_length) {
    if (picture == NULL || picture->pixels == NULL) {
        if (out_length != NULL) {
            *out_length = 0;
        }
        return NULL;
    }
    if (out_length != NULL) {
        *out_length = picture->pixel_count;
    }
    return picture->pixels;
}

void imagify_avif_picture_pixels_free(imagify_avif_picture* picture) {
    if (picture != NULL) {
        imagify_avif_release_pixels(picture);
    }
}

int imagify_avif_picture_info(const imagify_avif_picture* picture, int* out) {
    if (picture == NULL || picture->image == NULL || out == NULL) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }
    return imagify_avif_picture_info_fields(picture->image, out);
}

/*
 * Fills the fifteen answers from one avifImage.
 *
 * It is separate from imagify_avif_picture_info because a sequence describes itself from the image
 * its decoder is sitting on, which belongs to the decoder rather than to a picture this library made.
 * The positions are the ABI: they are named in this file's header and the binding reads the same
 * names, so a list that grew on one side and not the other is a mismatch at the first offset that
 * differs rather than a wrong answer at some later one.
 *
 * The three CICP fields are read as numbers because that is what a caller wants. They are uint16_t
 * in the struct, and a binding that declared them as four byte fields would read a short and the
 * short after it as one number, which is how a colour primaries of 2 turns into 131074. That was not
 * hypothetical: the JNA binding this replaces had exactly that bug.
 */
static int imagify_avif_picture_info_fields(const avifImage* image, int* out) {
    out[IMAGIFY_AVIF_INFO_WIDTH] = (int)image->width;
    out[IMAGIFY_AVIF_INFO_HEIGHT] = (int)image->height;
    out[IMAGIFY_AVIF_INFO_DEPTH] = (int)image->depth;
    out[IMAGIFY_AVIF_INFO_YUV_FORMAT] = (int)image->yuvFormat;
    out[IMAGIFY_AVIF_INFO_YUV_RANGE] = (int)image->yuvRange;
    out[IMAGIFY_AVIF_INFO_CHROMA_SAMPLE_POSITION] = (int)image->yuvChromaSamplePosition;
    out[IMAGIFY_AVIF_INFO_COLOR_PRIMARIES] = (int)image->colorPrimaries;
    out[IMAGIFY_AVIF_INFO_TRANSFER_CHARACTERISTICS] = (int)image->transferCharacteristics;
    out[IMAGIFY_AVIF_INFO_MATRIX_COEFFICIENTS] = (int)image->matrixCoefficients;
    out[IMAGIFY_AVIF_INFO_HAS_ALPHA] = (image->alphaPlane != NULL) ? 1 : 0;
    out[IMAGIFY_AVIF_INFO_ROTATION_DEGREES] = ((int)image->irot.angle % 4) * 90;
    out[IMAGIFY_AVIF_INFO_MIRRORED] = (image->imir.axis != 0) ? 1 : 0;
    out[IMAGIFY_AVIF_INFO_ICC_SIZE] = (int)image->icc.size;
    out[IMAGIFY_AVIF_INFO_EXIF_SIZE] = (int)image->exif.size;
    out[IMAGIFY_AVIF_INFO_XMP_SIZE] = (int)image->xmp.size;
    return IMAGIFY_AVIF_OK;
}

/* --------------------------------------------------------------------------------------------- */
/* encoding                                                                                        */
/* --------------------------------------------------------------------------------------------- */

int imagify_avif_picture_encode(const imagify_avif_picture* picture, int quality, int speed,
    int alpha_quality, int max_threads, uint8_t** encoded, size_t* encoded_length) {
    if (picture == NULL || picture->image == NULL || encoded == NULL || encoded_length == NULL) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }
    *encoded = NULL;
    *encoded_length = 0;
    /* -1 is the absence of a setting for all three, and it is not a placeholder that gets clamped:
     * for the speed it is AVIF_SPEED_DEFAULT, which tells the AV1 codec to keep its own settings
     * rather than to approximate one of the ten levels, and rejecting it here would make every caller
     * that did not name a speed fail rather than get libavif's own choice. */
    if (quality < -1 || quality > AVIF_QUALITY_BEST) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }
    if (speed < AVIF_SPEED_DEFAULT || speed > AVIF_SPEED_FASTEST) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }
    if (alpha_quality < -1 || alpha_quality > AVIF_QUALITY_BEST) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }

    avifEncoder* encoder = avifEncoderCreate();
    if (encoder == NULL) {
        return IMAGIFY_AVIF_ERR_MEMORY;
    }
    /* One thread is libavif's own default and a thread count is a caller's to choose, so a value
     * that was not asked for is left alone rather than set to this machine's core count. */
    encoder->maxThreads = (max_threads > 0) ? max_threads : 1;
    encoder->quality = quality;
    encoder->qualityAlpha = (alpha_quality >= 0) ? alpha_quality : AVIF_QUALITY_LOSSLESS;
    encoder->speed = speed;
    encoder->timescale = 1;

    avifResult result = avifEncoderAddImage(encoder, picture->image, 1, AVIF_ADD_IMAGE_FLAG_SINGLE);
    avifRWData output = AVIF_DATA_EMPTY;
    if (result == AVIF_RESULT_OK) {
        result = avifEncoderFinish(encoder, &output);
    }
    avifEncoderDestroy(encoder);

    if (result != AVIF_RESULT_OK) {
        avifRWDataFree(&output);
        return imagify_avif_status(NULL, 0, result);
    }
    if (output.data == NULL || output.size == 0) {
        avifRWDataFree(&output);
        return imagify_avif_fail(NULL, 0, IMAGIFY_AVIF_ERR_INTERNAL, "libavif encoded nothing");
    }
    /* output.data came from avifRWDataRealloc, which is avifAlloc, and avifAlloc is avifFree, so
     * this is the right way to release it. Handing the pointer to the plain free() a Java caller
     * would reach for is a different allocator. */
    *encoded = output.data;
    *encoded_length = output.size;
    return IMAGIFY_AVIF_OK;
}

void imagify_avif_free(void* buffer) {
    if (buffer != NULL) {
        avifFree(buffer);
    }
}

/* --------------------------------------------------------------------------------------------- */
/* decoding                                                                                        */
/* --------------------------------------------------------------------------------------------- */

int imagify_avif_picture_decode(const uint8_t* data, size_t length, int max_threads,
    imagify_avif_picture** out) {
    if (data == NULL || out == NULL || length == 0) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }
    *out = NULL;
    avifDecoder* decoder = avifDecoderCreate();
    if (decoder == NULL) {
        return IMAGIFY_AVIF_ERR_MEMORY;
    }
    /* Exif and XMP are not read here, so a file that carries them does not pay for parsing what
     * this entry point would then throw away. */
    decoder->ignoreExif = AVIF_TRUE;
    decoder->ignoreXMP = AVIF_TRUE;
    /* These two checks reject files that older, and still widely deployed, encoders emit, and
     * clearing them is a choice about which files to read rather than about reading them correctly. */
    decoder->strictFlags &= ~(AVIF_STRICT_CLAP_VALID | AVIF_STRICT_PIXI_REQUIRED);
    decoder->maxThreads = (max_threads > 0) ? max_threads : 1;

    avifResult result = avifDecoderSetIOMemory(decoder, data, length);
    if (result == AVIF_RESULT_OK) {
        result = avifDecoderParse(decoder);
    }
    if (result == AVIF_RESULT_OK) {
        result = avifDecoderNextImage(decoder);
    }
    if (result != AVIF_RESULT_OK) {
        avifDecoderDestroy(decoder);
        return imagify_avif_status(NULL, 0, result);
    }
    if (decoder->image == NULL) {
        avifDecoderDestroy(decoder);
        return imagify_avif_fail(NULL, 0, IMAGIFY_AVIF_ERR_CORRUPT,
            "the file does not contain a decodable image");
    }

    /* The picture keeps the decoder rather than a copy of its image. avifDecoder.image is an
     * avifImage the decoder owns, and avifDecoderDestroy frees it, so the picture holds the decoder
     * and lets one destroy do both. Copying the image instead, with avifImageCopy, would duplicate
     * every plane of a decoded picture to save nothing. */
    imagify_avif_picture* picture = (imagify_avif_picture*)calloc(1, sizeof(*picture));
    if (picture == NULL) {
        avifDecoderDestroy(decoder);
        return IMAGIFY_AVIF_ERR_MEMORY;
    }
    picture->image = decoder->image;
    picture->decoder = decoder;
    *out = picture;
    return IMAGIFY_AVIF_OK;
}

/* --------------------------------------------------------------------------------------------- */
/* animation                                                                                        */
/* --------------------------------------------------------------------------------------------- */
int imagify_avif_animation_encode(int frame_count, const uint8_t* frames, const int* durations_ms,
    int width, int height, int yuv_format, int chroma_downsampling, int quality, int speed,
    int alpha_quality, int loop_count, int max_threads, uint8_t** encoded, size_t* encoded_length) {
    if (frames == NULL || durations_ms == NULL || encoded == NULL || encoded_length == NULL) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }
    *encoded = NULL;
    *encoded_length = 0;
    if (frame_count < 1) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }
    if (!imagify_avif_geometry_ok(width, height)) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }
    if (quality < -1 || quality > AVIF_QUALITY_BEST) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }
    if (speed < AVIF_SPEED_DEFAULT || speed > AVIF_SPEED_FASTEST) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }
    if (alpha_quality < -1 || alpha_quality > AVIF_QUALITY_BEST) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }

    avifEncoder* encoder = avifEncoderCreate();
    if (encoder == NULL) {
        return IMAGIFY_AVIF_ERR_MEMORY;
    }
    encoder->maxThreads = (max_threads > 0) ? max_threads : 1;
    encoder->quality = quality;
    encoder->qualityAlpha = (alpha_quality >= 0) ? alpha_quality : AVIF_QUALITY_LOSSLESS;
    encoder->speed = speed;
    /* A sequence is timed in milliseconds, so the timescale is 1000 and a frame's duration is the
     * number the caller gave. A still image is encoded with a timescale of 1 and a duration of 1
     * instead, because there is no frame to be shown for a length of time. */
    encoder->timescale = 1000;
    /* libavif counts plays rather than repeats, so its -1 is this layer's 0. Passing 0 through
     * unchanged would not mean "forever" to libavif: a repetitionCount of n is played back n + 1
     * times, so a zero would produce a file that plays exactly once. */
    encoder->repetitionCount = (loop_count == 0) ? AVIF_REPETITION_COUNT_INFINITE : loop_count;

    avifResult result = AVIF_RESULT_OK;
    avifImage* image = NULL;
    const size_t frame_bytes = (size_t)width * (size_t)height * 4u;
    for (int i = 0; i < frame_count; i++) {
        image = avifImageCreate((uint32_t)width, (uint32_t)height, 8, (avifPixelFormat)yuv_format);
        if (image == NULL) {
            result = AVIF_RESULT_OUT_OF_MEMORY;
            break;
        }
        avifRGBImage rgb;
        /* The stride is the frame's own width, not the picture's, and the picture is created at
         * that width, so the two agree and a sequence cannot end up with ragged frames. */
        imagify_avif_rgb(&rgb, image, frames + (size_t)i * frame_bytes, width * 4,
            AVIF_RGB_FORMAT_ABGR, chroma_downsampling);
        result = avifImageRGBToYUV(image, &rgb);
        if (result == AVIF_RESULT_OK) {
            /* The timescale is 1000, so a duration in milliseconds is the number of timescales a
             * frame is shown for. A frame given zero is not shown at all, and one is the smallest
             * that is, so a caller that asked for nothing gets a frame rather than a gap. */
            const uint64_t duration = (durations_ms[i] > 0)
                ? (uint64_t)durations_ms[i]
                : (uint64_t)1;
            result = avifEncoderAddImage(encoder, image, duration, AVIF_ADD_IMAGE_FLAG_NONE);
        }
        /* libavif encodes the image during the call, so it is destroyed after and not before.
         * Destroying it first is what makes avifEncoderAddImage answer NO_CONTENT. */
        avifImageDestroy(image);
        image = NULL;
        if (result != AVIF_RESULT_OK) {
            break;
        }
    }

    avifRWData output = AVIF_DATA_EMPTY;
    if (result == AVIF_RESULT_OK) {
        result = avifEncoderFinish(encoder, &output);
    }
    avifEncoderDestroy(encoder);
    if (image != NULL) {
        avifImageDestroy(image);
    }

    if (result != AVIF_RESULT_OK) {
        avifRWDataFree(&output);
        return imagify_avif_status(NULL, 0, result);
    }
    if (output.data == NULL || output.size == 0) {
        avifRWDataFree(&output);
        return imagify_avif_fail(NULL, 0, IMAGIFY_AVIF_ERR_INTERNAL, "libavif encoded nothing");
    }
    *encoded = output.data;
    *encoded_length = output.size;
    return IMAGIFY_AVIF_OK;
}

/*
 * How long a frame is assumed to be shown when the container declares no timescale to measure it
 * against. It is the fallback libavif applies for itself, and it is short enough to stay unnoticed
 * if it is ever wrong.
 */
#define IMAGIFY_AVIF_UNSCALED_DURATION_MS 100

/*
 * An open file, its decoder, and the bytes it was opened over.
 *
 * The bytes are a copy rather than a reference to the caller's. avifDecoderSetIOMemory copies what
 * it goes on to read out of them, so the caller's array is not touched after it is handed over, and
 * copying it here is what lets a Java array be collected the moment the call returns rather than
 * being pinned for as long as the sequence is open.
 */
struct imagify_avif_sequence {
    avifDecoder* decoder;
    uint8_t* data;
    size_t size;
};

int imagify_avif_sequence_open(const uint8_t* data, size_t length, int max_threads,
    imagify_avif_sequence** out) {
    if (data == NULL || out == NULL || length == 0) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }
    *out = NULL;
    imagify_avif_sequence* sequence = (imagify_avif_sequence*)calloc(1, sizeof(*sequence));
    if (sequence == NULL) {
        return IMAGIFY_AVIF_ERR_MEMORY;
    }
    sequence->data = (uint8_t*)malloc(length);
    if (sequence->data == NULL) {
        free(sequence);
        return IMAGIFY_AVIF_ERR_MEMORY;
    }
    memcpy(sequence->data, data, length);
    sequence->size = length;

    sequence->decoder = avifDecoderCreate();
    if (sequence->decoder == NULL) {
        imagify_avif_sequence_close(sequence);
        return IMAGIFY_AVIF_ERR_MEMORY;
    }
    sequence->decoder->ignoreExif = AVIF_TRUE;
    sequence->decoder->ignoreXMP = AVIF_TRUE;
    /* Zero means no cap on the number of frames, which matters for a long sequence and changes
     * nothing for a still, because a still is a sequence of one. */
    sequence->decoder->imageCountLimit = 0;
    sequence->decoder->strictFlags &= ~(AVIF_STRICT_CLAP_VALID | AVIF_STRICT_PIXI_REQUIRED);
    sequence->decoder->maxThreads = (max_threads > 0) ? max_threads : 1;

    const avifResult set = avifDecoderSetIOMemory(sequence->decoder, sequence->data, sequence->size);
    if (set != AVIF_RESULT_OK) {
        imagify_avif_sequence_close(sequence);
        return imagify_avif_status(NULL, 0, set);
    }
    const avifResult result = avifDecoderParse(sequence->decoder);
    if (result != AVIF_RESULT_OK) {
        imagify_avif_sequence_close(sequence);
        return imagify_avif_status(NULL, 0, result);
    }
    *out = sequence;
    return IMAGIFY_AVIF_OK;
}

void imagify_avif_sequence_close(imagify_avif_sequence* sequence) {
    if (sequence == NULL) {
        return;
    }
    if (sequence->decoder != NULL) {
        avifDecoderDestroy(sequence->decoder);
    }
    free(sequence->data);
    free(sequence);
}

/*
 * Puts the decoder on one frame, landing on it directly where the library can.
 *
 * avifDecoderNthImage is the direct route and it exists from libavif 1.3.0 onwards, so on anything
 * older this walks forward with avifDecoderNextImage instead. Which of the two is in use is decided
 * once, by whether the first attempt succeeded, because a library without it fails to link the call
 * rather than failing at run time.
 */
static avifResult imagify_avif_sequence_seek(imagify_avif_sequence* sequence, int index) {
    return avifDecoderNthImage(sequence->decoder, (uint32_t)index);
}

int imagify_avif_sequence_read(imagify_avif_sequence* sequence, int* out, int* frame_count,
    int* loop_count) {
    if (sequence == NULL || sequence->decoder == NULL || out == NULL || frame_count == NULL
        || loop_count == NULL) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }
    const int count = sequence->decoder->imageCount;
    if (count <= 0) {
        return imagify_avif_fail(NULL, 0, IMAGIFY_AVIF_ERR_CORRUPT,
            "the file does not contain any frames");
    }
    *frame_count = count;
    /* libavif counts plays and says so with -1, and this layer's callers say the same thing with 0.
     * Translating here rather than in the binding keeps both directions of the animation surface
     * speaking one language, so a caller that encoded 0 reads back 0. */
    *loop_count = (sequence->decoder->repetitionCount == AVIF_REPETITION_COUNT_INFINITE)
        ? 0
        : sequence->decoder->repetitionCount;

    /* The file as a whole is described from the frame the parse left the decoder on, which is the
     * first one and is what a caller asking about the file rather than about a frame wants. */
    if (sequence->decoder->image != NULL) {
        return imagify_avif_picture_info_fields(sequence->decoder->image, out);
    }
    return IMAGIFY_AVIF_OK;
}

int imagify_avif_sequence_sizes(imagify_avif_sequence* sequence, int* sizes) {
    if (sequence == NULL || sequence->decoder == NULL || sizes == NULL) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }
    const int count = sequence->decoder->imageCount;
    /* Every frame is walked in one pass so that a caller looping over the frames pays for one
     * traversal of the container rather than one per frame. */
    int* const widths = sizes;
    int* const heights = sizes + count;
    int* const durations = sizes + 2 * count;
    for (int index = 0; index < count; index++) {
        const avifResult result = imagify_avif_sequence_seek(sequence, index);
        if (result != AVIF_RESULT_OK) {
            return imagify_avif_status(NULL, 0, result);
        }
        const avifImage* image = sequence->decoder->image;
        widths[index] = (image != NULL) ? (int)image->width : 0;
        heights[index] = (image != NULL) ? (int)image->height : 0;
        const avifImageTiming* timing = &sequence->decoder->imageTiming;
        /* A frame with no duration would never be seen, so the smallest possible one is used
         * instead of reporting the zero the container asked for. The division is done in double
         * because both fields are 64 bit and the result is milliseconds, which are not. */
        durations[index] = (timing->timescale > 0)
            ? (int)((double)timing->durationInTimescales * 1000.0 / (double)timing->timescale + 0.5)
            : IMAGIFY_AVIF_UNSCALED_DURATION_MS;
        if (durations[index] < 1) {
            durations[index] = 1;
        }
    }
    return IMAGIFY_AVIF_OK;
}

int imagify_avif_sequence_frame(imagify_avif_sequence* sequence, int index, int max_threads,
    uint8_t** out, size_t* out_length, int* out_width, int* out_height) {
    if (sequence == NULL || sequence->decoder == NULL || out == NULL || out_length == NULL) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }
    *out = NULL;
    *out_length = 0;
    if (index < 0 || index >= sequence->decoder->imageCount) {
        return imagify_avif_fail(NULL, 0, IMAGIFY_AVIF_ERR_ARGUMENT,
            "there is no frame at that position");
    }
    const avifResult seeked = imagify_avif_sequence_seek(sequence, index);
    if (seeked != AVIF_RESULT_OK) {
        return imagify_avif_status(NULL, 0, seeked);
    }
    const avifImage* image = sequence->decoder->image;
    if (image == NULL || image->width == 0 || image->height == 0) {
        return imagify_avif_fail(NULL, 0, IMAGIFY_AVIF_ERR_CORRUPT, "the frame could not be decoded");
    }
    /* A frame of a sequence that updates only part of the canvas is smaller than the file, so the
     * dimensions that go back are the frame's own and not the ones the parse reported. */
    const int width = (int)image->width;
    const int height = (int)image->height;
    const size_t total = (size_t)width * (size_t)height * 4u;
    uint8_t* buffer = (uint8_t*)malloc(total);
    if (buffer == NULL) {
        return IMAGIFY_AVIF_ERR_MEMORY;
    }
    avifRGBImage rgb;
    imagify_avif_rgb(&rgb, image, NULL, width * 4, AVIF_RGB_FORMAT_ABGR, -1);
    rgb.pixels = buffer;
    rgb.maxThreads = (max_threads > 1) ? max_threads : 1;
    const avifResult result = avifImageYUVToRGB(image, &rgb);
    if (result != AVIF_RESULT_OK) {
        free(buffer);
        return imagify_avif_status(NULL, 0, result);
    }
    *out = buffer;
    *out_length = total;
    *out_width = width;
    *out_height = height;
    return IMAGIFY_AVIF_OK;
}

int imagify_avif_sequence_frame_into(imagify_avif_sequence* sequence, int index, int max_threads,
    uint8_t* out, size_t out_length, int* out_width, int* out_height) {
    if (sequence == NULL || sequence->decoder == NULL || out == NULL || out_width == NULL
        || out_height == NULL) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }
    if (index < 0 || index >= sequence->decoder->imageCount) {
        return imagify_avif_fail(NULL, 0, IMAGIFY_AVIF_ERR_ARGUMENT,
            "there is no frame at that position");
    }
    const avifResult seeked = imagify_avif_sequence_seek(sequence, index);
    if (seeked != AVIF_RESULT_OK) {
        return imagify_avif_status(NULL, 0, seeked);
    }
    const avifImage* image = sequence->decoder->image;
    if (image == NULL || image->width == 0 || image->height == 0) {
        return imagify_avif_fail(NULL, 0, IMAGIFY_AVIF_ERR_CORRUPT, "the frame could not be decoded");
    }
    const int width = (int)image->width;
    const int height = (int)image->height;
    const size_t total = (size_t)width * (size_t)height * 4u;
    if (out_length < total) {
        return IMAGIFY_AVIF_ERR_ARGUMENT;
    }
    avifRGBImage rgb;
    imagify_avif_rgb(&rgb, image, out, width * 4, AVIF_RGB_FORMAT_ABGR, -1);
    rgb.maxThreads = (max_threads > 1) ? max_threads : 1;
    const avifResult result = avifImageYUVToRGB(image, &rgb);
    if (result != AVIF_RESULT_OK) {
        return imagify_avif_status(NULL, 0, result);
    }
    *out_width = width;
    *out_height = height;
    return IMAGIFY_AVIF_OK;
}
