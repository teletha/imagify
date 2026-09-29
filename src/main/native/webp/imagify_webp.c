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
 * A flat C ABI over the public libwebp API, for the FFM binding in imagify.webp.ffm.
 *
 * libwebp's C API is public, has no error callbacks and never unwinds, so a binding could in
 * principle talk to it directly, and a previous version of this jar did: it went through a third
 * party JNI wrapper. This file is here anyway, and for two reasons that are worth stating because
 * they are the whole of the argument for it.
 *
 * The structures. Encoding a picture means filling in struct WebPPicture, which is 256 bytes on a
 * 64 bit platform and most of that is not data: it is the padding libwebp reserves for itself, a
 * union of three sample pointers, a statistics block it may write through, and four function
 * pointers. A binding would have to name every one of those fields and get every offset right, and
 * a mistake in any of them is not a compile error and not an exception but a wild pointer write in
 * the middle of encoding a picture. The animation API cannot be driven without one either, since
 * it takes a WebPPicture per frame. The layout is described in encode.h as "private fields,
 * padding for later use", so there is no upstream promise to hold a mapping of it to. Here it stays
 * on the C side of the boundary, where the compiler checks every assignment, and what crosses is
 * plain scalars, pointers and buffers.
 *
 * The exported names. Only the imagify_webp_* entry points below leave this library, because
 * libwebp's own names are exactly the ones a system libwebp also claims. This jar has no reason to
 * interoperate with one, and two copies of libwebp mapped in a process is a coin toss over which of
 * them a call resolves to, so the version of libwebp that answers is the one named here and no
 * other. See the export rules in CMakeLists.txt.
 *
 * The pixel layout is one thing both of us agree on, and it is worth stating because it is easy to
 * assume the opposite. Every buffer crossing this boundary is tightly packed A, B, G, R bytes: the
 * byte at offset 0 of a pixel is its alpha and the byte at offset 3 is its red. That is the order a
 * java.awt.image.BufferedImage of type TYPE_4BYTE_ABGR keeps its banks in, because such a raster
 * declares band offsets of 3, 2, 1, 0 even though Raster.getDataElements reports the very same
 * sample as R, G, B, A. Reading it the other way round swaps red and blue in both directions
 * without ever failing. libwebp does not speak that order: it wants 0xAARRGGBB words going in and
 * R, G, B, A bytes coming out, so the shuffling happens here.
 */

#include "imagify_webp.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <webp/decode.h>
#include <webp/demux.h>
#include <webp/encode.h>
#include <webp/mux.h>

/* --------------------------------------------------------------------------------------------- */
/* reporting                                                                                       */
/* --------------------------------------------------------------------------------------------- */

static int imagify_fail(char* message, size_t capacity, int status, const char* text) {
  if (message != NULL && capacity > 0) {
    snprintf(message, capacity, "%s", text);
  }
  return status;
}

/*
 * Takes a copy of one of libwebp's own error strings.
 *
 * The string WebPAnimEncoderGetError returns belongs to the encoder and is only good until the next
 * call on it, so it has to be read out before the encoder is deleted. It is a plain char* that may
 * be NULL, and handing a NULL to printf's %s is undefined: whether that prints (null) or crashes is
 * a matter of the C library rather than of the caller.
 */
static void imagify_copy_reason(char* buffer, size_t capacity, const char* text) {
  if (buffer == NULL || capacity == 0) {
    return;
  }
  if (text == NULL) {
    buffer[0] = '\0';
    return;
  }
  snprintf(buffer, capacity, "%s", text);
}

/*
 * Turns a status into the one message that fits it.
 *
 * The animation encoder is the only part of libwebp that describes a failure in words, and its text
 * is passed through as it is: it is the only description of a failed animation encode that exists,
 * and it names the frame. Everything else gets a sentence written here.
 */
static int imagify_encoder_failure(char* message, size_t capacity, const char* reason) {
  if (reason == NULL || reason[0] == '\0') {
    return imagify_fail(message, capacity, IMAGIFY_WEBP_ERR_INTERNAL,
        "libwebp failed to encode the image and gave no reason");
  }
  return imagify_fail(message, capacity, IMAGIFY_WEBP_ERR_INTERNAL, reason);
}

/* --------------------------------------------------------------------------------------------- */
/* sizes and pixel shuffling                                                                       */
/* --------------------------------------------------------------------------------------------- */

/*
 * @return width * height * 4, or 0 when that is not a size this process can allocate
 */
static size_t imagify_pixel_bytes(int width, int height) {
  if (width <= 0 || height <= 0 || width > WEBP_MAX_DIMENSION || height > WEBP_MAX_DIMENSION) {
    return 0;
  }
  const size_t pixels = (size_t)width * (size_t)height;
  if (pixels > (size_t)-1 / 4u) {
    return 0;
  }
  return pixels * 4u;
}

/*
 * Unpacks A, B, G, R bytes into the 0xAARRGGBB words libwebp reads a picture from.
 *
 * @param abgr pixels * 4 bytes in A, B, G, R order
 * @param pixels the number of pixels
 * @return the words, or NULL when the allocation failed
 */
static uint32_t* imagify_to_argb(const uint8_t* abgr, size_t pixels) {
  uint32_t* argb = (uint32_t*)WebPMalloc(pixels * sizeof(*argb));
  if (argb == NULL) {
    return NULL;
  }
  for (size_t i = 0; i < pixels; i++) {
    const uint8_t* p = abgr + i * 4u;
    argb[i] = ((uint32_t)p[0] << 24) | ((uint32_t)p[3] << 16) | ((uint32_t)p[2] << 8) | (uint32_t)p[1];
  }
  return argb;
}

/*
 * Repacks R, G, B, A bytes into A, B, G, R bytes.
 *
 * Both decoders hand back R, G, B, A: the still one because that is what WebPDecodeRGBA was asked
 * for, the animation one because the four byte colour modes are the only ones it supports, and
 * neither can be asked for a 0xAARRGGBB word instead. So this runs on the way out of both, and
 * there is one place where the order is decided.
 */
static void imagify_from_rgba(const uint8_t* rgba, size_t pixels, uint8_t* abgr) {
  for (size_t i = 0; i < pixels; i++) {
    const uint8_t* p = rgba + i * 4u;
    uint8_t* q = abgr + i * 4u;
    q[0] = p[3];
    q[1] = p[2];
    q[2] = p[1];
    q[3] = p[0];
  }
}

/* --------------------------------------------------------------------------------------------- */
/* encoder options                                                                                 */
/* --------------------------------------------------------------------------------------------- */

/*
 * @return whether quality and method are on the scales libwebp accepts
 */
static int imagify_encode_options_valid(int quality, int method) {
  return quality >= IMAGIFY_WEBP_MIN_QUALITY && quality <= IMAGIFY_WEBP_MAX_QUALITY &&
         method >= IMAGIFY_WEBP_MIN_METHOD && method <= IMAGIFY_WEBP_MAX_METHOD;
}

/*
 * Fills in the encoder options this jar uses: libwebp's own defaults, plus the two knobs a caller
 * is allowed to turn.
 *
 * exact is set to 1 and left there in both modes, which is not the default. Without it libwebp is
 * free to replace the colour of a fully transparent pixel with something that compresses better, and
 * a Java caller that reads such a pixel back with getRGB() then gets the replacement rather than
 * what it wrote. A file gets a little larger and an image stops changing colour behind the caller's
 * back, which is the better trade for an image library that has been asked to store pixels.
 *
 * Lossless asks for the slowest of libwebp's own lossless presets rather than treating quality as an
 * amount of effort, which is what libwebp would do with it there. A caller that has chosen not to
 * lose anything has not asked a fidelity question, so the field is not theirs to answer. Method is
 * left alone, so a caller that has said how hard the encoder should try has still been heard.
 */
static int imagify_make_config(WebPConfig* config, int quality, int lossless, int method) {
  if (!WebPConfigInit(config)) {
    return 0;
  }
  if (lossless) {
    /* Level 9 is libwebp's slowest and best lossless preset. */
    WebPConfigLosslessPreset(config, 9);
    config->method = method;
  } else {
    config->quality = (float)quality;
    config->method = method;
  }
  config->exact = 1;
  return WebPValidateConfig(config) ? 1 : 0;
}

/* --------------------------------------------------------------------------------------------- */
/* headers                                                                                         */
/* --------------------------------------------------------------------------------------------- */

const char* imagify_webp_abi_version(void) {
  return "1";
}

const char* imagify_webp_webp_version(void) {
#ifdef IMAGIFY_WEBP_VERSION
  return IMAGIFY_WEBP_VERSION;
#else
  return "unknown";
#endif
}

static void imagify_report_features(imagify_webp_features* features,
    const WebPBitstreamFeatures* parsed) {
  features->width = (int)parsed->width;
  features->height = (int)parsed->height;
  features->has_alpha = parsed->has_alpha ? 1 : 0;
  features->has_animation = parsed->has_animation ? 1 : 0;
  features->format = (int)parsed->format;
}

static int imagify_status_of(int status) {
  switch (status) {
    case VP8_STATUS_OK:
      return IMAGIFY_WEBP_OK;
    case VP8_STATUS_OUT_OF_MEMORY:
      return IMAGIFY_WEBP_ERR_MEMORY;
    case VP8_STATUS_INVALID_PARAM:
      return IMAGIFY_WEBP_ERR_ARGUMENT;
    case VP8_STATUS_UNSUPPORTED_FEATURE:
      return IMAGIFY_WEBP_ERR_UNSUPPORTED;
    default:
      /* BITSTREAM_ERROR, NOT_ENOUGH_DATA and SUSPENDED all mean the same thing here: libwebp was
       * handed something it could not read. */
      return IMAGIFY_WEBP_ERR_CORRUPT;
  }
}

static const char* imagify_status_text(int status) {
  switch (status) {
    case VP8_STATUS_OUT_OF_MEMORY:
      return "libwebp ran out of memory";
    case VP8_STATUS_INVALID_PARAM:
      return "libwebp was handed something it will not accept";
    case VP8_STATUS_UNSUPPORTED_FEATURE:
      return "libwebp does not support this file";
    case VP8_STATUS_SUSPENDED:
      return "libwebp needs more of the file before it can decode it";
    case VP8_STATUS_NOT_ENOUGH_DATA:
      return "the file ends in the middle of the image";
    case VP8_STATUS_BITSTREAM_ERROR:
      return "the data is not a readable WebP bitstream";
    default:
      return "libwebp could not read the file and said nothing about why";
  }
}

int imagify_webp_read_features(const uint8_t* data, size_t length, imagify_webp_features* features,
    char* message, size_t message_capacity) {
  if (data == NULL || features == NULL) {
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_ARGUMENT, "no data to inspect");
  }
  WebPBitstreamFeatures parsed;
  const int status = WebPGetFeatures(data, length, &parsed);
  if (status != VP8_STATUS_OK) {
    return imagify_fail(message, message_capacity, imagify_status_of(status),
        imagify_status_text(status));
  }
  imagify_report_features(features, &parsed);
  return IMAGIFY_WEBP_OK;
}

/* --------------------------------------------------------------------------------------------- */
/* still images                                                                                    */
/* --------------------------------------------------------------------------------------------- */

int imagify_webp_decode(const uint8_t* data, size_t length, uint8_t** out, size_t* out_length,
    imagify_webp_features* features, char* message, size_t message_capacity) {
  if (data == NULL || out == NULL || out_length == NULL || features == NULL) {
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_ARGUMENT, "no data to decode");
  }
  *out = NULL;
  *out_length = 0;

  WebPBitstreamFeatures parsed;
  const int feature_status = WebPGetFeatures(data, length, &parsed);
  if (feature_status != VP8_STATUS_OK) {
    return imagify_fail(message, message_capacity, imagify_status_of(feature_status),
        imagify_status_text(feature_status));
  }
  if (parsed.has_animation) {
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_UNSUPPORTED,
        "an animated WebP has no single image; its frames are read with "
        "imagify_webp_decode_animation");
  }

  int width = 0;
  int height = 0;
  uint8_t* rgba = WebPDecodeRGBA(data, length, &width, &height);
  if (rgba == NULL) {
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_CORRUPT,
        "libwebp could not decode the image");
  }
  if (width <= 0 || height <= 0) {
    WebPFree(rgba);
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_CORRUPT,
        "libwebp reported a decoded image of no size");
  }
  /* libwebp has just allocated this many bytes itself and said they were enough, so the size cannot
   * overflow anything and the only way this fails is the way libwebp's own call just did. */
  const size_t pixels = (size_t)width * (size_t)height;
  uint8_t* abgr = (uint8_t*)WebPMalloc(pixels * 4u);
  if (abgr == NULL) {
    WebPFree(rgba);
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_MEMORY, "out of memory");
  }
  imagify_from_rgba(rgba, pixels, abgr);
  WebPFree(rgba);

  *out = abgr;
  *out_length = pixels * 4u;
  imagify_report_features(features, &parsed);
  return IMAGIFY_WEBP_OK;
}

int imagify_webp_encode(const uint8_t* pixels, int width, int height, int quality, int lossless,
    int method, uint8_t** encoded, size_t* encoded_length, char* message,
    size_t message_capacity) {
  if (pixels == NULL || encoded == NULL || encoded_length == NULL) {
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_ARGUMENT, "no image to encode");
  }
  *encoded = NULL;
  *encoded_length = 0;

  const size_t bytes = imagify_pixel_bytes(width, height);
  if (bytes == 0) {
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_UNSUPPORTED,
        "libwebp will not encode a picture this size");
  }
  if (!imagify_encode_options_valid(quality, method)) {
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_ARGUMENT,
        "quality and method are outside the ranges libwebp accepts");
  }
  WebPConfig config;
  if (!imagify_make_config(&config, quality, lossless, method)) {
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_ARGUMENT,
        "libwebp rejected the encoding options");
  }

  const size_t count = bytes / 4u;
  uint32_t* argb = imagify_to_argb(pixels, count);
  if (argb == NULL) {
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_MEMORY, "out of memory");
  }

  WebPPicture picture;
  if (!WebPPictureInit(&picture)) {
    WebPFree(argb);
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_INTERNAL,
        "libwebp would not initialise a picture");
  }
  picture.use_argb = 1;
  picture.width = width;
  picture.height = height;
  picture.argb = argb;
  picture.argb_stride = width;
  /* The memory writer the animation encoder installs on its own pictures. WebPEncode writes
   * through picture.writer and would not write at all without one, so this is the difference
   * between the bitstream landing in a buffer and the encode failing. */
  WebPMemoryWriter writer;
  WebPMemoryWriterInit(&writer);
  picture.writer = WebPMemoryWrite;
  picture.custom_ptr = &writer;

  const int ok = WebPEncode(&config, &picture);
  const int error_code = (int)picture.error_code;
  WebPPictureFree(&picture);
  WebPFree(argb);

  if (!ok) {
    WebPMemoryWriterClear(&writer);
    if (message != NULL && message_capacity > 0) {
      snprintf(message, message_capacity, "libwebp would not encode the image (error code %d)",
          error_code);
    }
    return IMAGIFY_WEBP_ERR_INTERNAL;
  }
  if (writer.mem == NULL || writer.size == 0) {
    WebPMemoryWriterClear(&writer);
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_INTERNAL,
        "libwebp encoded nothing");
  }
  /* writer.mem came from WebPSafeRealloc, so imagify_webp_free, which is WebPFree, releases it.
   * Clearing the writer here would free it out from under the caller instead. */
  *encoded = writer.mem;
  *encoded_length = writer.size;
  return IMAGIFY_WEBP_OK;
}

/* --------------------------------------------------------------------------------------------- */
/* animations                                                                                      */
/* --------------------------------------------------------------------------------------------- */

static int imagify_open_decoder(const uint8_t* data, size_t length, WebPData* bitstream,
    WebPAnimDecoderOptions* options, WebPAnimDecoder** decoder) {
  if (!WebPAnimDecoderOptionsInit(options)) {
    return IMAGIFY_WEBP_ERR_INTERNAL;
  }
  /* The animation decoder can only produce the four byte colour modes, and the packing they use is
   * undone on the way out by imagify_from_rgba. Its threading only ever helps when several frames
   * are being decoded at once, which is not something a caller can ask it for here. */
  options->color_mode = MODE_RGBA;
  options->use_threads = 0;
  bitstream->bytes = data;
  bitstream->size = length;
  *decoder = WebPAnimDecoderNew(bitstream, options);
  return (*decoder == NULL) ? IMAGIFY_WEBP_ERR_CORRUPT : IMAGIFY_WEBP_OK;
}

/*
 * Reads the canvas size, the frame count and the loop count out of an animation decoder.
 */
static int imagify_animation_info(WebPAnimDecoder* decoder, imagify_webp_animation* animation) {
  WebPAnimInfo info;
  if (!WebPAnimDecoderGetInfo(decoder, &info)) {
    return IMAGIFY_WEBP_ERR_INTERNAL;
  }
  if (info.canvas_width == 0 || info.canvas_height == 0 || info.frame_count == 0) {
    return IMAGIFY_WEBP_ERR_CORRUPT;
  }
  animation->width = (int)info.canvas_width;
  animation->height = (int)info.canvas_height;
  animation->frame_count = (int)info.frame_count;
  animation->loop_count = (int)info.loop_count;
  return IMAGIFY_WEBP_OK;
}

/*
 * Fills delays[0..frame_count) with how long each frame is shown.
 *
 * The durations are read from the demuxer rather than worked out by differencing the timestamps the
 * animation decoder reports as it hands out frames, because the demuxer states each frame's
 * duration outright and asking it costs nothing: it walks the frame headers and decodes no pixel
 * either way. Both entry points below go through here, so the timing of an animation is read the
 * same way whether the caller wanted the timing or the pictures.
 */
static int imagify_frame_delays(WebPAnimDecoder* decoder, int frame_count, int* delays) {
  const WebPDemuxer* demux = WebPAnimDecoderGetDemuxer(decoder);
  if (demux == NULL) {
    return IMAGIFY_WEBP_ERR_INTERNAL;
  }
  WebPIterator iter;
  if (!WebPDemuxGetFrame(demux, 1, &iter)) {
    return IMAGIFY_WEBP_ERR_CORRUPT;
  }
  int found = 0;
  do {
    if (found < frame_count) {
      delays[found] = iter.duration;
    }
    found++;
  } while (WebPDemuxNextFrame(&iter));
  WebPDemuxReleaseIterator(&iter);
  return (found == frame_count) ? IMAGIFY_WEBP_OK : IMAGIFY_WEBP_ERR_CORRUPT;
}

/*
 * Says what a failure while reading an animation was, given the one thing the caller knows about it
 * that the status does not: whether the file was an animation at all.
 */
static int imagify_animation_failure(char* message, size_t capacity, int status,
    const char* what_went_wrong) {
  if (status == IMAGIFY_WEBP_ERR_MEMORY) {
    return imagify_fail(message, capacity, status, "out of memory");
  }
  if (status == IMAGIFY_WEBP_ERR_CORRUPT) {
    return imagify_fail(message, capacity, status, what_went_wrong);
  }
  return imagify_fail(message, capacity, status,
      "libwebp failed on the animation for a reason of its own");
}

int imagify_webp_read_animation(const uint8_t* data, size_t length,
    imagify_webp_animation* animation, int** delays, char* message, size_t message_capacity) {
  if (data == NULL || animation == NULL || delays == NULL) {
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_ARGUMENT,
        "no animation to inspect");
  }
  *delays = NULL;

  WebPData bitstream;
  WebPAnimDecoderOptions options;
  WebPAnimDecoder* decoder = NULL;
  int status = imagify_open_decoder(data, length, &bitstream, &options, &decoder);
  if (status == IMAGIFY_WEBP_OK) {
    status = imagify_animation_info(decoder, animation);
  }
  if (status == IMAGIFY_WEBP_OK) {
    *delays = (int*)WebPMalloc(sizeof(**delays) * (size_t)animation->frame_count);
    if (*delays == NULL) {
      status = IMAGIFY_WEBP_ERR_MEMORY;
    } else {
      status = imagify_frame_delays(decoder, animation->frame_count, *delays);
    }
  }
  WebPAnimDecoderDelete(decoder);

  if (status != IMAGIFY_WEBP_OK) {
    WebPFree(*delays);
    *delays = NULL;
    return imagify_animation_failure(message, message_capacity, status,
        "the WebP animation holds no frames libwebp can show");
  }
  return IMAGIFY_WEBP_OK;
}

int imagify_webp_decode_animation(const uint8_t* data, size_t length,
    imagify_webp_animation* animation, uint8_t** frames, int** delays, char* message,
    size_t message_capacity) {
  if (data == NULL || animation == NULL || frames == NULL || delays == NULL) {
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_ARGUMENT,
        "no animation to decode");
  }
  *frames = NULL;
  *delays = NULL;

  WebPData bitstream;
  WebPAnimDecoderOptions options;
  WebPAnimDecoder* decoder = NULL;
  uint8_t* pixels = NULL;
  int status = imagify_open_decoder(data, length, &bitstream, &options, &decoder);
  if (status != IMAGIFY_WEBP_OK) {
    return imagify_animation_failure(message, message_capacity, status,
        "the data is not a readable animated WebP");
  }
  status = imagify_animation_info(decoder, animation);
  if (status != IMAGIFY_WEBP_OK) {
    WebPAnimDecoderDelete(decoder);
    return imagify_animation_failure(message, message_capacity, status,
        "the WebP animation holds no frames libwebp can show");
  }

  const size_t one_frame = (size_t)animation->width * (size_t)animation->height;
  const size_t frame_bytes = (one_frame <= (size_t)-1 / 4u) ? one_frame * 4u : 0;
  if (frame_bytes == 0 || (size_t)animation->frame_count > (size_t)-1 / frame_bytes) {
    WebPAnimDecoderDelete(decoder);
    return imagify_animation_failure(message, message_capacity, IMAGIFY_WEBP_ERR_MEMORY,
        "the animation is too large to hold in memory");
  }
  pixels = (uint8_t*)WebPMalloc(frame_bytes * (size_t)animation->frame_count);
  *delays = (int*)WebPMalloc(sizeof(**delays) * (size_t)animation->frame_count);
  if (pixels == NULL || *delays == NULL) {
    WebPFree(pixels);
    WebPFree(*delays);
    *delays = NULL;
    WebPAnimDecoderDelete(decoder);
    return imagify_animation_failure(message, message_capacity, IMAGIFY_WEBP_ERR_MEMORY,
        "out of memory");
  }
  status = imagify_frame_delays(decoder, animation->frame_count, *delays);
  if (status != IMAGIFY_WEBP_OK) {
    WebPFree(pixels);
    WebPFree(*delays);
    *delays = NULL;
    WebPAnimDecoderDelete(decoder);
    return imagify_animation_failure(message, message_capacity, status,
        "the WebP animation holds no frames libwebp can show");
  }

  int decoded = 0;
  while (WebPAnimDecoderHasMoreFrames(decoder) && decoded < animation->frame_count) {
    uint8_t* frame = NULL;
    int timestamp = 0;
    if (!WebPAnimDecoderGetNext(decoder, &frame, &timestamp) || frame == NULL) {
      break;
    }
    imagify_from_rgba(frame, one_frame, pixels + (size_t)decoded * frame_bytes);
    decoded++;
  }
  WebPAnimDecoderDelete(decoder);

  if (decoded != animation->frame_count) {
    WebPFree(pixels);
    WebPFree(*delays);
    *delays = NULL;
    return imagify_animation_failure(message, message_capacity, IMAGIFY_WEBP_ERR_CORRUPT,
        "the WebP animation ended before every frame was decoded");
  }
  *frames = pixels;
  return IMAGIFY_WEBP_OK;
}

int imagify_webp_encode_animation(const uint8_t* frames, int frame_count, int width, int height,
    const int* delays, int quality, int lossless, int loop_count, int method, uint8_t** encoded,
    size_t* encoded_length, char* message, size_t message_capacity) {
  if (frames == NULL || delays == NULL || encoded == NULL || encoded_length == NULL) {
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_ARGUMENT,
        "no animation to encode");
  }
  *encoded = NULL;
  *encoded_length = 0;

  const size_t frame_bytes = imagify_pixel_bytes(width, height);
  if (frame_bytes == 0) {
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_UNSUPPORTED,
        "libwebp will not encode a canvas this size");
  }
  if (frame_count < 1) {
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_ARGUMENT,
        "an animation needs at least one frame");
  }
  if ((size_t)frame_count > (size_t)-1 / frame_bytes) {
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_MEMORY,
        "an animation this long cannot be held in memory");
  }
  if (loop_count < 0) {
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_ARGUMENT,
        "the loop count cannot be negative");
  }
  if (!imagify_encode_options_valid(quality, method)) {
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_ARGUMENT,
        "quality and method are outside the ranges libwebp accepts");
  }
  WebPConfig config;
  if (!imagify_make_config(&config, quality, lossless, method)) {
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_ARGUMENT,
        "libwebp rejected the encoding options");
  }

  const size_t count = frame_bytes / 4u;
  /* One allocation for the whole animation, so that the picture handed to each frame can point
   * straight into it rather than a frame being unpacked once per frame out of a Java array. */
  uint32_t* argb = imagify_to_argb(frames, count * (size_t)frame_count);
  if (argb == NULL) {
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_MEMORY, "out of memory");
  }

  WebPAnimEncoderOptions enc_options;
  if (!WebPAnimEncoderOptionsInit(&enc_options)) {
    WebPFree(argb);
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_INTERNAL,
        "libwebp would not initialise its animation encoder");
  }
  /* A loop count of 0 is the container's own way of saying forever, so it is passed through rather
   * than turned into some other number. */
  enc_options.anim_params.loop_count = loop_count;
  /* minimize_size and allow_mixed stay off. The first trades a great deal of time for a few per
   * cent, and the second lets the encoder pick a bitstream per frame on its own, which is a choice
   * for a caller that can say which frame gets which and is not one for a caller that cannot. */
  enc_options.minimize_size = 0;
  enc_options.allow_mixed = 0;
  enc_options.verbose = 0;

  WebPAnimEncoder* encoder = WebPAnimEncoderNew(width, height, &enc_options);
  if (encoder == NULL) {
    WebPFree(argb);
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_MEMORY,
        "libwebp would not start an animation encoder");
  }

  char reason[IMAGIFY_WEBP_MESSAGE_LENGTH];
  reason[0] = '\0';
  /* The encoder is told when each frame stops being shown rather than how long it is shown, so the
   * running total is carried here. It is kept in something wider than an int because the per frame
   * delays are ints and enough of them can overflow one. */
  long long timestamp = 0;
  int ok = 1;
  for (int i = 0; i < frame_count && ok; i++) {
    WebPPicture picture;
    if (!WebPPictureInit(&picture)) {
      snprintf(reason, sizeof(reason), "libwebp would not initialise frame %d", i);
      ok = 0;
      break;
    }
    picture.use_argb = 1;
    picture.width = width;
    picture.height = height;
    picture.argb = argb + (size_t)i * count;
    picture.argb_stride = width;
    ok = WebPAnimEncoderAdd(encoder, &picture, (int)timestamp, &config);
    WebPPictureFree(&picture);
    if (!ok) {
      imagify_copy_reason(reason, sizeof(reason), WebPAnimEncoderGetError(encoder));
    }
    /* A negative delay is a caller mistake that cannot be reported per frame, so it is treated as a
     * frame that is not shown at all rather than as one shown backwards. */
    timestamp += (delays[i] > 0) ? delays[i] : 0;
    if (timestamp > 0x7fffffffLL) {
      timestamp = 0x7fffffffLL;
    }
  }
  /* The last call carries no picture, and is what says when the final frame stops being shown. */
  if (ok && !WebPAnimEncoderAdd(encoder, NULL, (int)timestamp, NULL)) {
    imagify_copy_reason(reason, sizeof(reason), WebPAnimEncoderGetError(encoder));
    ok = 0;
  }

  WebPData assembled;
  WebPDataInit(&assembled);
  if (ok && !WebPAnimEncoderAssemble(encoder, &assembled)) {
    imagify_copy_reason(reason, sizeof(reason), WebPAnimEncoderGetError(encoder));
    ok = 0;
  }
  WebPAnimEncoderDelete(encoder);
  WebPFree(argb);

  if (!ok) {
    WebPDataClear(&assembled);
    return imagify_encoder_failure(message, message_capacity, reason);
  }
  if (assembled.bytes == NULL || assembled.size == 0) {
    WebPDataClear(&assembled);
    return imagify_fail(message, message_capacity, IMAGIFY_WEBP_ERR_INTERNAL,
        "libwebp encoded no animation");
  }
  /* assembled.bytes came from WebPMalloc, so imagify_webp_free, which is WebPFree, releases it.
   * Clearing the structure here would free it out from under the caller instead. */
  *encoded = (uint8_t*)assembled.bytes;
  *encoded_length = assembled.size;
  return IMAGIFY_WEBP_OK;
}

void imagify_webp_free(void* buffer) {
  WebPFree(buffer);
}
