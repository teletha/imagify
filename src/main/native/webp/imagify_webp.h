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
 * A flat C ABI over the public libwebp API, for the FFM binding in imagify.webp.ffm. See
 * imagify_webp.c for why this layer exists at all.
 */

#ifndef IMAGIFY_WEBP_H_
#define IMAGIFY_WEBP_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * The version of this ABI, not of libwebp. The FFM binding gates on it so that a jar built against
 * one revision of this file refuses a library built against another rather than reading arguments
 * from the wrong offsets.
 *
 * It stays at 1 across the entry points that take and return 0xAARRGGBB words, because none of the
 * entry points that made up version 1 changed: they are the same functions with the same arguments
 * and the same answers. A binding that does not know about them still works against a library that
 * has them, and a binding that does use them has to ask whether they are there rather than assume
 * it, since the libraries in the jar are built and shipped one platform at a time. Raising this
 * number is for the other direction, when a meaning that a binding already relies on has changed.
 */
#define IMAGIFY_WEBP_ABI_VERSION 1

/* Status codes. Every entry point answers one of these, and IMAGIFY_WEBP_OK is the only success. */
#define IMAGIFY_WEBP_OK 0              /* the operation completed */
#define IMAGIFY_WEBP_ERR_ARGUMENT 1    /* a parameter was out of range, before any work was done */
#define IMAGIFY_WEBP_ERR_CORRUPT 2     /* the input is not a WebP file, or is a damaged one */
#define IMAGIFY_WEBP_ERR_MEMORY 3      /* an allocation failed */
#define IMAGIFY_WEBP_ERR_UNSUPPORTED 4 /* libwebp will not do this, for instance a dimension too large */
#define IMAGIFY_WEBP_ERR_INTERNAL 5    /* libwebp failed in a way its own error text does not explain */

/* The ends of libwebp's quality scale, and of its encoding effort scale. */
#define IMAGIFY_WEBP_MIN_QUALITY 0
#define IMAGIFY_WEBP_MAX_QUALITY 100
#define IMAGIFY_WEBP_MIN_METHOD 0
#define IMAGIFY_WEBP_MAX_METHOD 6

/*
 * How much room an entry point has to describe a failure, and so how large the caller's buffer has
 * to be. A failure this layer has a description of always fits; a failure libwebp describes for
 * itself, which is only ever the animation encoder, is truncated rather than dropped.
 */
#define IMAGIFY_WEBP_MESSAGE_LENGTH 256

/* The bitstream format codes libwebp reports, which are the same numbers WebpCodec carries. */
#define IMAGIFY_WEBP_FORMAT_VP8 1  /* a lossy VP8 bitstream */
#define IMAGIFY_WEBP_FORMAT_VP8L 2 /* a lossless VP8L bitstream */
#define IMAGIFY_WEBP_FORMAT_VP8X 0 /* the extended container, which is what an animation uses */

/*
 * What the container headers of a WebP file say, without a pixel being decoded.
 *
 * The numbers are libwebp's own: width and height in pixels, has_alpha and has_animation as 0 or 1,
 * and format as one of the IMAGIFY_WEBP_FORMAT_* values. A still image has has_animation 0, and
 * reading a single frame out of an animation is not something this layer offers.
 */
typedef struct {
  int width;
  int height;
  int has_alpha;
  int has_animation;
  int format;
} imagify_webp_features;

/*
 * What the animation control chunk of a WebP file says.
 *
 * loop_count is 0 for an animation that repeats forever. width and height are the canvas, which is
 * the size of every frame this layer hands back, and frame_count is how many of them there are. The
 * per frame delays are reported beside this structure rather than inside it, because a caller that
 * only wants the timing has no use for the pixels and vice versa.
 */
typedef struct {
  int frame_count;
  int loop_count;
  int width;
  int height;
} imagify_webp_animation;

/*
 * @return the ABI version this library was built with, as a decimal string such as "1"
 */
const char* imagify_webp_abi_version(void);

/*
 * @return the libwebp version this library was built from, as a string such as "1.6.0"
 */
const char* imagify_webp_webp_version(void);

/*
 * Parses the container headers of a WebP file and reports what they say.
 *
 * Nothing here decodes a pixel, so a caller that only wants the size of a file does not have to pay
 * for its content. The out parameter is left untouched on failure.
 *
 * @param data the encoded WebP file
 * @param length the number of bytes at data
 * @param features receives what the headers say
 * @param message receives a description of a failure, and is left untouched on success
 * @param message_capacity the size of message in bytes
 * @return IMAGIFY_WEBP_OK, or a status that says what went wrong
 */
int imagify_webp_read_features(const uint8_t* data, size_t length,
    imagify_webp_features* features, char* message, size_t message_capacity);

/*
 * Decodes a still image into a buffer of tightly packed A, B, G, R bytes.
 *
 * The alpha byte is always 255 when the file has no alpha channel, and the has_alpha field says
 * which of the two a caller is looking at. A caller that is handed the picture has to trust the
 * flag rather than the channel, because an image with no channel has nothing to read.
 *
 * An animation is refused with IMAGIFY_WEBP_ERR_UNSUPPORTED: a single animated WebP has no single
 * image to return, and its frames have to be read with imagify_webp_decode_animation.
 *
 * On success *out holds a buffer the caller owns and must hand back to imagify_webp_free. It is
 * *out_length bytes long, which is width * height * 4, and is null on failure.
 *
 * @param data the encoded WebP file
 * @param length the number of bytes at data
 * @param out receives the buffer to free with imagify_webp_free, or NULL on failure
 * @param out_length receives the number of bytes at *out
 * @param features receives what the headers say, including the size the pixels were decoded at
 * @param message receives a description of a failure, and is left untouched on success
 * @param message_capacity the size of message in bytes
 * @return IMAGIFY_WEBP_OK, or a status that says what went wrong
 */
int imagify_webp_decode(const uint8_t* data, size_t length, uint8_t** out, size_t* out_length,
    imagify_webp_features* features, char* message, size_t message_capacity);

/*
 * Encodes tightly packed A, B, G, R bytes as a complete WebP file.
 *
 * An alpha of 0 is kept as it is, and so is the colour underneath it, so an image with transparency
 * comes back out of a lossless round trip as the same pixels.
 *
 * quality is honoured in both modes, and means different things in each. It is the fidelity to
 * trade away when lossless is zero, and it is the amount of effort libwebp spends when it is not:
 * the same field, read two ways, which is libwebp's own doing. So a caller that has chosen not to
 * lose anything has said nothing about fidelity and can still say how hard to try, and the whole of
 * the range is worth using. It is a size rather than a fidelity there, and a file encoded at
 * IMAGIFY_WEBP_MIN_QUALITY with lossless set is a small file that is still the same picture.
 *
 * On success *encoded holds a buffer the caller owns and must hand back to imagify_webp_free. It is
 * null on failure.
 *
 * @param pixels width * height * 4 bytes in A, B, G, R order
 * @param width the image width, at least 1
 * @param height the image height, at least 1
 * @param quality IMAGIFY_WEBP_MIN_QUALITY to IMAGIFY_WEBP_MAX_QUALITY, a fidelity or an effort
 * @param lossless nonzero to store the pixels without loss
 * @param method IMAGIFY_WEBP_MIN_METHOD to IMAGIFY_WEBP_MAX_METHOD, how hard the encoder tries
 * @param encoded receives the buffer to free with imagify_webp_free, or NULL on failure
 * @param encoded_length receives the number of bytes at *encoded
 * @param message receives a description of a failure, and is left untouched on success
 * @param message_capacity the size of message in bytes
 * @return IMAGIFY_WEBP_OK, or a status that says what went wrong
 */
int imagify_webp_encode(const uint8_t* pixels, int width, int height, int quality, int lossless,
    int method, uint8_t** encoded, size_t* encoded_length, char* message, size_t message_capacity);

/*
 * Encodes 0xAARRGGBB words as a complete WebP file, without a copy of the pixels.
 *
 * The same encode as imagify_webp_encode, and the same options in the same range, with the pixels
 * given in the layout libwebp reads them in rather than the layout this library hands them around
 * in. A caller whose pixels are already in that layout saves the whole of the shuffle: the byte
 * buffer imagify_webp_encode has to fill, the copy out of it into the words libwebp wants, and the
 * image's worth of allocation either of those needs. A caller whose pixels are not already in that
 * layout is better off with imagify_webp_encode, since the shuffle still has to happen and this
 * only moves where.
 *
 * The words are only read, and only the width * height of them the caller says there are.
 *
 * On success *encoded holds a buffer the caller owns and must hand back to imagify_webp_free. It is
 * null on failure.
 *
 * @param pixels width * height 0xAARRGGBB words
 * @param width the image width, at least 1
 * @param height the image height, at least 1
 * @param quality IMAGIFY_WEBP_MIN_QUALITY to IMAGIFY_WEBP_MAX_QUALITY, a fidelity or an effort
 * @param lossless nonzero to store the pixels without loss
 * @param method IMAGIFY_WEBP_MIN_METHOD to IMAGIFY_WEBP_MAX_METHOD, how hard the encoder tries
 * @param encoded receives the buffer to free with imagify_webp_free, or NULL on failure
 * @param encoded_length receives the number of bytes at *encoded
 * @param message receives a description of a failure, and is left untouched on success
 * @param message_capacity the size of message in bytes
 * @return IMAGIFY_WEBP_OK, or a status that says what went wrong
 */
int imagify_webp_encode_argb(const uint32_t* pixels, int width, int height, int quality, int lossless,
    int method, uint8_t** encoded, size_t* encoded_length, char* message, size_t message_capacity);

/*
 * Decodes a still image straight into 0xAARRGGBB words the caller has already allocated, which is
 * the same trade as imagify_webp_encode_argb at the other end of the call.
 *
 * The buffer has to be big enough before the decode starts, and the size is in the file's headers,
 * so a caller reads it with imagify_webp_read_features first. This reads the headers as well, to
 * refuse an animation and to report what the file holds, and that is the same call the other decode
 * entry point makes.
 *
 * out_stride is in words and may be larger than the width, in which case each row of the file
 * starts that many words into the buffer and the rows are not contiguous. Nothing else in this ABI
 * strides, and a caller with an image whose rows are not contiguous is a caller no picture library
 * can hand a WebP to cheaply anyway.
 *
 * An animation is refused with IMAGIFY_WEBP_ERR_UNSUPPORTED, as it is everywhere else.
 *
 * @param data the encoded WebP file
 * @param length the number of bytes at data
 * @param out the words to decode into, at least width * height of them
 * @param out_stride how many words apart the rows of out are, at least the image width
 * @param features receives what the headers say
 * @param message receives a description of a failure, and is left untouched on success
 * @param message_capacity the size of message in bytes
 * @return IMAGIFY_WEBP_OK, or a status that says what went wrong
 */
int imagify_webp_decode_into_argb(const uint8_t* data, size_t length, uint32_t* out, int out_stride,
    imagify_webp_features* features, char* message, size_t message_capacity);

/*
 * Reads the frame count, the loop count and the per frame delay of an animation without decoding a
 * pixel.
 *
 * On success *delays holds frame_count integers the caller owns and must hand back to
 * imagify_webp_free, in presentation order. The out parameters are left untouched on failure.
 *
 * @param data the encoded animated WebP file
 * @param length the number of bytes at data
 * @param animation receives the canvas size, the frame count and the loop count
 * @param delays receives the buffer to free with imagify_webp_free, or NULL on failure
 * @param message receives a description of a failure, and is left untouched on success
 * @param message_capacity the size of message in bytes
 * @return IMAGIFY_WEBP_OK, or a status that says what went wrong
 */
int imagify_webp_read_animation(const uint8_t* data, size_t length,
    imagify_webp_animation* animation, int** delays, char* message, size_t message_capacity);

/*
 * Decodes every frame of an animation.
 *
 * The frames are composited onto the canvas, so each of them is width * height * 4 bytes of A, B, G,
 * R in the one buffer at *frames, and what a viewer would show at that point in time is what is
 * there. That is the same promise WebPAnimDecoder makes, and it is the one an animation has to
 * keep: a frame that covers only part of the canvas says nothing about the rest of it.
 *
 * On success *frames and *delays hold buffers the caller owns and must hand back to
 * imagify_webp_free. *frames is frame_count * width * height * 4 bytes and *delays is frame_count
 * integers. Both are null on failure.
 *
 * @param data the encoded animated WebP file
 * @param length the number of bytes at data
 * @param animation receives the canvas size, the frame count and the loop count
 * @param frames receives the buffer to free with imagify_webp_free, or NULL on failure
 * @param delays receives the buffer to free with imagify_webp_free, or NULL on failure
 * @param message receives a description of a failure, and is left untouched on success
 * @param message_capacity the size of message in bytes
 * @return IMAGIFY_WEBP_OK, or a status that says what went wrong
 */
int imagify_webp_decode_animation(const uint8_t* data, size_t length,
    imagify_webp_animation* animation, uint8_t** frames, int** delays, char* message,
    size_t message_capacity);

/*
 * Encodes a sequence of frames as an animated WebP file.
 *
 * The frames are given as one buffer, each of them width * height * 4 bytes of A, B, G, R in
 * presentation order, which is the layout the animation decoder hands back and so the layout a
 * caller that has just read an animation already has.
 *
 * quality is honoured in both modes, as it is for the still encode: a fidelity to trade away when
 * lossless is zero, an amount of effort when it is not. loop_count of 0 means the animation repeats
 * forever, which is what the WebP container itself means by it.
 *
 * On success *encoded holds a buffer the caller owns and must hand back to imagify_webp_free. It is
 * null on failure.
 *
 * @param frames frame_count * width * height * 4 bytes in A, B, G, R order
 * @param frame_count the number of frames, at least 1
 * @param width the canvas width, at least 1
 * @param height the canvas height, at least 1
 * @param delays frame_count integers, how long each frame is shown in milliseconds
 * @param quality IMAGIFY_WEBP_MIN_QUALITY to IMAGIFY_WEBP_MAX_QUALITY, a fidelity or an effort
 * @param lossless nonzero to store the pixels without loss
 * @param loop_count how often the animation repeats, 0 meaning forever
 * @param method IMAGIFY_WEBP_MIN_METHOD to IMAGIFY_WEBP_MAX_METHOD, how hard the encoder tries
 * @param encoded receives the buffer to free with imagify_webp_free, or NULL on failure
 * @param encoded_length receives the number of bytes at *encoded
 * @param message receives a description of a failure, and is left untouched on success
 * @param message_capacity the size of message in bytes
 * @return IMAGIFY_WEBP_OK, or a status that says what went wrong
 */
int imagify_webp_encode_animation(const uint8_t* frames, int frame_count, int width, int height,
    const int* delays, int quality, int lossless, int loop_count, int method, uint8_t** encoded,
    size_t* encoded_length, char* message, size_t message_capacity);

/*
 * Encodes a sequence of frames of 0xAARRGGBB words as an animated WebP file, without a copy of the
 * pixels.
 *
 * The same encode as imagify_webp_encode_animation, with the frames given in the layout libwebp
 * reads them in rather than the layout this library hands them around in. The saving is the pass
 * over the pixels that would otherwise unpack Java bytes into these words first; the animation
 * encoder copies each frame into a frame buffer of its own either way, so unlike the still case
 * there is no second copy here to save as well.
 *
 * The words are only read, and only the frame_count * width * height of them the caller says there
 * are. An alpha of 0 is kept as it is here as it is in the byte version.
 *
 * On success *encoded holds a buffer the caller owns and must hand back to imagify_webp_free. It is
 * null on failure.
 *
 * @param frames frame_count * width * height 0xAARRGGBB words in presentation order
 * @param frame_count the number of frames, at least 1
 * @param width the canvas width, at least 1
 * @param height the canvas height, at least 1
 * @param delays frame_count integers, how long each frame is shown in milliseconds
 * @param quality IMAGIFY_WEBP_MIN_QUALITY to IMAGIFY_WEBP_MAX_QUALITY, a fidelity or an effort
 * @param lossless nonzero to store the pixels without loss
 * @param loop_count how often the animation repeats, 0 meaning forever
 * @param method IMAGIFY_WEBP_MIN_METHOD to IMAGIFY_WEBP_MAX_METHOD, how hard the encoder tries
 * @param encoded receives the buffer to free with imagify_webp_free, or NULL on failure
 * @param encoded_length receives the number of bytes at *encoded
 * @param message receives a description of a failure, and is left untouched on success
 * @param message_capacity the size of message in bytes
 * @return IMAGIFY_WEBP_OK, or a status that says what went wrong
 */
int imagify_webp_encode_animation_argb(const uint32_t* frames, int frame_count, int width,
    int height, const int* delays, int quality, int lossless, int loop_count, int method,
    uint8_t** encoded, size_t* encoded_length, char* message, size_t message_capacity);

/*
 * Frees a buffer handed out by any of the entry points above that allocates one. Accepts NULL.
 *
 * @param buffer the buffer to free
 */
void imagify_webp_free(void* buffer);

#ifdef __cplusplus
}
#endif

#endif /* IMAGIFY_WEBP_H_ */
