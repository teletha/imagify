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
 * A flat C ABI over the libjpeg62 compatible API that jpegli implements, for the JNA binding in
 * imagify.jpeg.jna. See imagify_jpegli.c for why this layer exists at all.
 */

#ifndef IMAGIFY_JPEGLI_H_
#define IMAGIFY_JPEGLI_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * The version of this ABI, not of jpegli. The JNA binding gates on it so that a jar built against
 * one revision of this file refuses a library built against another rather than reading arguments
 * from the wrong offsets.
 */
#define IMAGIFY_JPEGLI_ABI_VERSION 1

/* Status codes. Every entry point answers one of these, and IMAGIFY_JPEG_OK is the only success. */
#define IMAGIFY_JPEG_OK 0           /* the operation completed */
#define IMAGIFY_JPEG_ERR_ARGUMENT 1 /* a parameter was out of range, before any work was done */
#define IMAGIFY_JPEG_ERR_CORRUPT 2  /* the input is not a JPEG, or is a damaged one */
#define IMAGIFY_JPEG_ERR_MEMORY 3    /* an allocation failed */
#define IMAGIFY_JPEG_ERR_INTERNAL 4  /* jpegli failed in a way its own error text does not explain */

/*
 * How finely the two colour difference channels are stored, which is the jpegli meaning of
 * TJSAMP_440 rather than the libjpeg one. The numbers are chosen so that the "4" in the name is the
 * numerator, and they are the same order as the JCS_* values they map onto.
 */
#define IMAGIFY_JPEG_SAMP_444 0 /* no subsampling at all */
#define IMAGIFY_JPEG_SAMP_422 1 /* half the width */
#define IMAGIFY_JPEG_SAMP_420 2 /* half the width and half the height */

/* The lowest and highest quality the encoder accepts, which are the ends of the libjpeg scale. */
#define IMAGIFY_JPEG_MIN_QUALITY 1
#define IMAGIFY_JPEG_MAX_QUALITY 100

/*
 * @return the ABI version this library was built with, as a decimal string such as "1"
 */
const char* imagify_jpegli_abi_version(void);

/*
 * @return the jpegli version this library was built from, as a string such as "0.5.0 (abc1234)"
 */
const char* imagify_jpegli_jpegli_version(void);

/*
 * Parses the frame header of a JPEG and reports what it says.
 *
 * Nothing here decodes a pixel, so a caller that only wants the size of a file does not have to pay
 * for its content. Every out parameter is left untouched on failure.
 *
 * @param data the encoded JPEG, at least two bytes
 * @param length the number of bytes at data
 * @param width receives the image width
 * @param height receives the image height
 * @param components receives the number of colour channels, which is 1 for a greyscale file
 * @param progressive receives nonzero for a progressive file and 0 for a sequential one
 * @param horizontalFactor receives the luma sampling factor along the width, which is 1 unless the
 *        file subsamples its colour channels
 * @param verticalFactor receives the luma sampling factor along the height
 * @param densityUnit receives 0 for an aspect ratio, 1 for dots per inch and 2 for dots per
 *        centimetre
 * @param horizontalDensity receives the horizontal density, in the unit just named
 * @param verticalDensity receives the vertical density
 * @param precision receives the number of bits per channel, which is 8 for every file jpegli reads
 * @param message receives a description of a failure, and is left untouched on success
 * @param message_capacity the size of message in bytes
 * @return IMAGIFY_JPEG_OK, or a status that says what went wrong
 */
int imagify_jpegli_read_header(const uint8_t* data, size_t length, int* width, int* height,
    int* components, int* progressive, int* horizontalFactor, int* verticalFactor, int* densityUnit,
    int* horizontalDensity, int* verticalDensity, int* precision, char* message,
    size_t message_capacity);

/*
 * Decodes a JPEG into a buffer of tightly packed A, B, G, R bytes.
 *
 * The alpha byte is always 255. A JPEG cannot carry a real one, and compositing the picture over an
 * opaque background instead of leaving it transparent would quietly change every pixel the caller
 * goes on to compare.
 *
 * On success *out holds a buffer the caller owns and must hand back to imagify_jpegli_free. It is
 * *out_length bytes long, which is width * height * 4, and is null on failure.
 *
 * @param data the encoded JPEG, at least two bytes
 * @param length the number of bytes at data
 * @param out receives the buffer to free with imagify_jpegli_free, or NULL on failure
 * @param out_length receives the number of bytes at *out
 * @param width receives the image width
 * @param height receives the image height
 * @param message receives a description of a failure, and is left untouched on success
 * @param message_capacity the size of message in bytes
 * @return IMAGIFY_JPEG_OK, or a status that says what went wrong
 */
int imagify_jpegli_decode(const uint8_t* data, size_t length, uint8_t** out, size_t* out_length,
    int* width, int* height, char* message, size_t message_capacity);

/*
 * Encodes tightly packed A, B, G, R bytes as a baseline JPEG.
 *
 * The alpha byte is discarded, because a JPEG cannot store one. Note that this means an image with
 * transparency is written against whatever colour sits behind it rather than losing the picture: a
 * caller that needs the picture has to flatten it first.
 *
 * On success *encoded holds a buffer the caller owns and must hand back to imagify_jpegli_free. It
 * is null on failure.
 *
 * @param pixels width * height * 4 bytes in A, B, G, R order
 * @param width the image width, at least 1
 * @param height the image height, at least 1
 * @param quality IMAGIFY_JPEG_MIN_QUALITY to IMAGIFY_JPEG_MAX_QUALITY
 * @param subsampling an IMAGIFY_JPEG_SAMP_* value
 * @param optimize_coding nonzero to compute the entropy coder tables from the image being written
 * @param encoded receives the buffer to free with imagify_jpegli_free, or NULL on failure
 * @param encoded_length receives the number of bytes at *encoded
 * @param message receives a description of a failure, and is left untouched on success
 * @param message_capacity the size of message in bytes
 * @return IMAGIFY_JPEG_OK, or a status that says what went wrong
 */
int imagify_jpegli_encode(const uint8_t* pixels, int width, int height, int quality, int subsampling,
    int optimize_coding, uint8_t** encoded, size_t* encoded_length, char* message,
    size_t message_capacity);

/*
 * Frees a buffer handed out by imagify_jpegli_decode or imagify_jpegli_encode. Accepts NULL.
 *
 * @param buffer the buffer to free
 */
void imagify_jpegli_free(void* buffer);

#ifdef __cplusplus
}
#endif

#endif /* IMAGIFY_JPEGLI_H_ */
