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
 * A flat C ABI over the public libavif API, for the FFM binding in imagify.avif.ffm. See
 * imagify_avif.c for why this layer exists at all.
 */

#ifndef IMAGIFY_AVIF_H_
#define IMAGIFY_AVIF_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * The version of this ABI, not of libavif. The FFM binding gates on it so that a jar built against
 * one revision of this file refuses a library built against another rather than reading arguments
 * from the wrong offsets.
 */
#define IMAGIFY_AVIF_ABI_VERSION 1

/* Status codes. Every entry point answers one of these, and IMAGIFY_AVIF_OK is the only success. */
#define IMAGIFY_AVIF_OK 0              /* the operation completed */
#define IMAGIFY_AVIF_ERR_ARGUMENT 1    /* a parameter was out of range, before any work was done */
#define IMAGIFY_AVIF_ERR_CORRUPT 2     /* the input is not an AVIF file, or is a damaged one */
#define IMAGIFY_AVIF_ERR_MEMORY 3      /* an allocation failed */
#define IMAGIFY_AVIF_ERR_UNSUPPORTED 4 /* libavif will not do this */
#define IMAGIFY_AVIF_ERR_INTERNAL 5    /* libavif failed in a way its own error text does not explain */

/*
 * How much room an entry point has to describe a failure, and so how large the caller's buffer has
 * to be. A failure this layer has a description of always fits; a failure libavif describes for
 * itself is truncated rather than dropped.
 */
#define IMAGIFY_AVIF_MESSAGE_LENGTH 256

/*
 * The positions imagify_avif_picture_info answers in, in the block it writes them to.
 *
 * They are an ABI, and so are named on both sides of it. A property added to libavif, or to what a
 * caller wants to know, is one more name here and one more read at that offset in the binding, and a
 * list that grew on one side and not the other is caught by the first offset that differs rather
 * than showing up later as a field that is quietly wrong.
 */
enum {
    IMAGIFY_AVIF_INFO_WIDTH = 0,
    IMAGIFY_AVIF_INFO_HEIGHT,
    IMAGIFY_AVIF_INFO_DEPTH,
    IMAGIFY_AVIF_INFO_YUV_FORMAT,
    IMAGIFY_AVIF_INFO_YUV_RANGE,
    IMAGIFY_AVIF_INFO_CHROMA_SAMPLE_POSITION,
    IMAGIFY_AVIF_INFO_COLOR_PRIMARIES,
    IMAGIFY_AVIF_INFO_TRANSFER_CHARACTERISTICS,
    IMAGIFY_AVIF_INFO_MATRIX_COEFFICIENTS,
    IMAGIFY_AVIF_INFO_HAS_ALPHA,
    IMAGIFY_AVIF_INFO_ROTATION_DEGREES,
    IMAGIFY_AVIF_INFO_MIRRORED,
    IMAGIFY_AVIF_INFO_ICC_SIZE,
    IMAGIFY_AVIF_INFO_EXIF_SIZE,
    IMAGIFY_AVIF_INFO_XMP_SIZE,
    IMAGIFY_AVIF_INFO_COUNT
};

/*
 * The version of libavif in use, for the diagnostic AvifCodec.getVersion() reports.
 */
const char* imagify_avif_libavif_version(void);

/*
 * A picture created by this library, holding the libavif state a call needs. The FFM binding never
 * sees an avifImage or an avifEncoder, only this.
 */
typedef struct imagify_avif_picture imagify_avif_picture;

/*
 * Creates a picture of the given geometry with its planes allocated.
 *
 * @return a new picture, or NULL when the arguments are not a geometry libavif accepts or an
 *         allocation failed
 */
imagify_avif_picture* imagify_avif_picture_create(int width, int height, int depth, int yuv_format);

void imagify_avif_picture_destroy(imagify_avif_picture* picture);

/*
 * Converts tightly packed A, B, G, R bytes into the picture's YUV and alpha planes, without the
 * bytes being copied: the buffer given is the one libavif reads, and it has to stay valid and
 * unmodified for the length of the call.
 *
 * This is the zero copy. A Java array cannot be stored into avifRGBImage.pixels, because the FFM
 * ABI will not put a heap segment into a pointer-typed struct field, and so an entry point that
 * takes the pixels as its own argument is what makes handing one over possible at all. The binding
 * passes such a call with Linker.Option.critical set, which pins the array for the length of the
 * call rather than copying it, and this function is on the stack for exactly that long.
 *
 * @param picture the picture to fill
 * @param pixels width * height * 4 bytes in A, B, G, R order
 * @param row_bytes the stride of the pixels, which is width * 4 for tightly packed 8 bit ABGR
 * @param chroma_downsampling an AVIF_CHROMA_DOWNSAMPLING_* value
 * @return an IMAGIFY_AVIF_* status
 */
int imagify_avif_picture_from_abgr(imagify_avif_picture* picture, const uint8_t* pixels,
    int row_bytes, int chroma_downsampling);

/*
 * Converts the picture's YUV and alpha planes back into tightly packed A, B, G, R bytes.
 *
 * The pixels are written into a buffer this library allocates and hands back, so a caller that wants
 * no copy of its own reads them out of it. The buffer is released with imagify_avif_picture_pixels_free.
 *
 * @param picture the picture to read
 * @param max_threads the number of threads the YUV to RGB conversion may use, 0 or less for one
 * @param out receives the allocated buffer, which is the caller's to free
 * @param out_length receives its length in bytes
 * @return an IMAGIFY_AVIF_* status
 */
int imagify_avif_picture_to_abgr(imagify_avif_picture* picture, int max_threads, uint8_t** out,
    size_t* out_length);

/*
 * The A, B, G, R bytes of the last imagify_avif_picture_to_abgr call, in one buffer, as an
 * alternative to taking them from the picture. The buffer belongs to this library and lives until
 * the next call on the same picture or until it is destroyed.
 */
const uint8_t* imagify_avif_picture_pixels(const imagify_avif_picture* picture, size_t* out_length);

void imagify_avif_picture_pixels_free(imagify_avif_picture* picture);

/*
 * The properties the container headers of a decoded picture say about it, as the numbers
 * AvifImageInfo carries, so a caller never has to know what an avifImage looks like.
 *
 * @param picture the picture to describe
 * @param out IMAGIFY_AVIF_INFO_COUNT four byte slots to answer into, at the positions named above
 * @return an IMAGIFY_AVIF_* status
 */
int imagify_avif_picture_info(const imagify_avif_picture* picture, int* out);

/*
 * Encodes a picture as a complete AVIF file.
 *
 * @param picture the picture to encode
 * @param quality 0 to 100, or -1 for libavif's own
 * @param speed 0 to 10, or -1 for libavif's own
 * @param alpha_quality 0 to 100, or -1 for libavif's own
 * @param max_threads the number of threads libavif may use
 * @param encoded receives the allocated file, which the caller frees with imagify_avif_free
 * @param encoded_length receives its length in bytes
 * @return an IMAGIFY_AVIF_* status
 */
int imagify_avif_picture_encode(const imagify_avif_picture* picture, int quality, int speed,
    int alpha_quality, int max_threads, uint8_t** encoded, size_t* encoded_length);

/*
 * Decodes a complete AVIF file into a picture.
 *
 * @param data the encoded file
 * @param length its length in bytes
 * @param max_threads the number of threads libavif may use
 * @param out receives a new picture, which the caller destroys with imagify_avif_picture_destroy
 * @return an IMAGIFY_AVIF_* status
 */
int imagify_avif_picture_decode(const uint8_t* data, size_t length, int max_threads,
    imagify_avif_picture** out);

/*
 * Encodes a sequence of frames as one animated AVIF file.
 *
 * The frames are passed as one block rather than as a list of pictures, because an animation is
 * exactly the case where the zero copy is worth the most: a hundred frames is a hundred pictures'
 * worth of pixels, and a copy of each of them is a hundred copies nobody asked for.
 *
 * @param frame_count how many frames follow
 * @param frames frame_count blocks of width * height * 4 bytes in A, B, G, R order, back to back
 * @param durations_ms how long each frame is shown, in milliseconds, one entry per frame
 * @param width the frame width, the same for every frame
 * @param height the frame height, the same for every frame
 * @param yuv_format an AVIF_PIXEL_FORMAT_* value
 * @param chroma_downsampling an AVIF_CHROMA_DOWNSAMPLING_* value, or -1 for the default
 * @param quality 0 to 100, or -1 for libavif's own
 * @param speed 0 to 10, or -1 for libavif's own
 * @param alpha_quality 0 to 100, or -1 for libavif's own
 * @param loop_count how often the animation repeats, 0 meaning forever
 * @param max_threads the number of threads libavif may use
 * @param encoded receives the allocated file, which the caller frees with imagify_avif_free
 * @param encoded_length receives its length in bytes
 * @return an IMAGIFY_AVIF_* status
 */
int imagify_avif_animation_encode(int frame_count, const uint8_t* frames, const int* durations_ms,
    int width, int height, int yuv_format, int chroma_downsampling, int quality, int speed,
    int alpha_quality, int loop_count, int max_threads, uint8_t** encoded, size_t* encoded_length);

/*
 * A file open for walking its frames, which is what an animation is.
 *
 * A still is a sequence of one, and the two are treated the same way here. The frame count and the
 * loop count are read from the container headers alone, so asking how many frames there are never
 * decodes a pixel.
 */
typedef struct imagify_avif_sequence imagify_avif_sequence;

/*
 * Opens an AVIF file for walking its frames.
 *
 * @param data the encoded file
 * @param length its length in bytes
 * @param max_threads the number of threads libavif may use
 * @param out receives the open sequence, which the caller closes with imagify_avif_sequence_close
 * @return an IMAGIFY_AVIF_* status
 */
int imagify_avif_sequence_open(const uint8_t* data, size_t length, int max_threads,
    imagify_avif_sequence** out);

void imagify_avif_sequence_close(imagify_avif_sequence* sequence);

/*
 * The properties of the file as a whole, how many frames it holds and how often it repeats.
 *
 * None of that costs a pixel: all three come out of the container headers, which is what makes it
 * safe to size an array for the frame table from the answer.
 *
 * @param sequence the open sequence
 * @param out the IMAGIFY_AVIF_INFO_COUNT answers about the file
 * @param frame_count receives how many frames it holds
 * @param loop_count receives how often it repeats, 0 meaning forever
 * @return an IMAGIFY_AVIF_* status
 */
int imagify_avif_sequence_read(imagify_avif_sequence* sequence, int* out, int* frame_count,
    int* loop_count);

/*
 * The size and the duration of every frame, filled in together.
 *
 * One call rather than one per frame is the whole of the point: a caller looping over the frames
 * would otherwise walk the container once for the durations and once again for the pixels.
 *
 * @param sequence the open sequence
 * @param sizes three arrays of frame_count entries back to back: widths, heights, and durations in
 *        milliseconds
 * @return an IMAGIFY_AVIF_* status
 */
int imagify_avif_sequence_sizes(imagify_avif_sequence* sequence, int* sizes);

/*
 * Decodes one frame as tightly packed A, B, G, R bytes.
 *
 * A frame of a sequence that updates only part of the canvas is smaller than the file, so the
 * dimensions that come back are the frame's own rather than the file's.
 *
 * @param sequence the open sequence
 * @param index the frame to decode
 * @param max_threads the number of threads the conversion may use, 0 or less for one
 * @param out receives the allocated buffer, which the caller frees with imagify_avif_free
 * @param out_length receives its length in bytes
 * @param out_width receives the frame's width in pixels
 * @param out_height receives the frame's height in pixels
 * @return an IMAGIFY_AVIF_* status
 */
int imagify_avif_sequence_frame(imagify_avif_sequence* sequence, int index, int max_threads,
    uint8_t** out, size_t* out_length, int* out_width, int* out_height);

/*
 * Releases a buffer this library allocated, such as one handed back by an encode.
 */
void imagify_avif_free(void* buffer);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* IMAGIFY_AVIF_H_ */
