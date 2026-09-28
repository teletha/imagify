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
 * imagify.jpeg.jna.
 *
 * jpegli exports the whole of libjpeg's public interface, so a binding could in principle talk to it
 * directly. It cannot, and this file is the reason why.
 *
 * Error handling. libjpeg reports a fatal error by calling cinfo->err->error_exit, which by
 * contract must not return, and the only portable way of getting control back afterwards is
 * setjmp/longjmp. JNA cannot take part in that: com.sun.jna.CallbackReference.DefaultCallbackProxy
 * catches every Throwable a Callback throws and hands it to a CallbackExceptionHandler, and
 * documents that the method must not throw. A Callback used as error_exit would therefore return
 * normally, and libjpeg would carry on after an error it believes to be fatal, which is how a
 * process ends up reading a struct field that was never written. So the setjmp/longjmp happens
 * here, in C, and the caller is handed a status code and a message instead.
 *
 * The structures. The other thing a direct binding would have to do is describe
 * struct jpeg_compress_struct and struct jpeg_decompress_struct field by field, and those are
 * several hundred fields of nested substructures whose offsets follow the libjpeg revision and the
 * two structs are not even the same size. This file keeps them on this side of the boundary and
 * exposes only plain scalars, pointers and buffers.
 *
 * The pixel layout is one thing both of us agree on, and it is worth stating because it is easy to
 * assume the opposite. Every buffer crossing this boundary is tightly packed A, B, G, R bytes: the
 * byte at offset 0 of a pixel is its alpha and the byte at offset 3 is its red. That is the order a
 * java.awt.image.BufferedImage of type TYPE_4BYTE_ABGR keeps its banks in, because such a raster
 * declares band offsets of 3, 2, 1, 0, even though Raster.getDataElements reports the very same
 * sample as R, G, B, A. Reading it the other way round swaps red and blue in both directions
 * without ever failing.
 */

#include "imagify_jpegli.h"

#include <setjmp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <jpeglib.h>

/* Refuse anything whose geometry could overflow the size arithmetic below, or is not an image. */
#define IMAGIFY_JPEG_MAX_EDGE 100000

/*
 * The error manager.
 *
 * libjpeg hands a j_common_ptr to every one of its callbacks and expects to be able to cast that
 * back into whatever it was originally given, so struct jpeg_error_mgr has to be the first member
 * here. The fields after it are ours and are not visible to libjpeg.
 */
typedef struct {
  struct jpeg_error_mgr pub;
  jmp_buf escape;
  char* message;
  size_t message_capacity;
  int status;
} imagify_error;

/*
 * The message paths.
 *
 * jpeg_std_error installs handlers that write to stderr and, worse, one that calls exit(). A Java
 * library has no business doing either: a truncated file is an error the caller asked about, and
 * the caller is told about it through the status code instead. jpegli formats its own text into
 * msg_parm.s before it calls error_exit, which is where imagify_error_exit reads it from.
 */
static void imagify_discard_message(j_common_ptr cinfo, int msg_level) {
  (void)cinfo;
  (void)msg_level;
}

static void imagify_discard_output(j_common_ptr cinfo) {
  (void)cinfo;
}

static void imagify_copy_message(j_common_ptr cinfo, char* buffer) {
  snprintf(buffer, JMSG_LENGTH_MAX, "%s", cinfo->err->msg_parm.s);
}

static void imagify_reset_error(j_common_ptr cinfo) {
  memset(cinfo->err->msg_parm.s, 0, sizeof(cinfo->err->msg_parm.s));
  cinfo->err->num_warnings = 0;
}

static void imagify_error_exit(j_common_ptr cinfo) {
  imagify_error* err = (imagify_error*)cinfo->err;
  err->status = IMAGIFY_JPEG_ERR_CORRUPT;
  if (err->message != NULL && err->message_capacity > 0) {
    snprintf(err->message, err->message_capacity, "%s", cinfo->err->msg_parm.s);
  }
  /*
   * Release what libjpeg built before unwinding, because the caller cannot: all it knows is that
   * the structure it passed in is in a state that may not be one jpeg_destroy_* would accept. This
   * is the pattern jpegli's own JNI wrapper uses, down to calling the common destroy.
   */
  jpeg_destroy(cinfo);
  longjmp(err->escape, 1);
}

static void imagify_install_error(struct jpeg_common_struct* cinfo, imagify_error* err, char* message,
    size_t message_capacity) {
  memset(err, 0, sizeof(*err));
  err->message = message;
  err->message_capacity = message_capacity;
  err->status = IMAGIFY_JPEG_ERR_INTERNAL;
  cinfo->err = jpeg_std_error(&err->pub);
  err->pub.error_exit = imagify_error_exit;
  err->pub.emit_message = imagify_discard_message;
  err->pub.output_message = imagify_discard_output;
  err->pub.format_message = imagify_copy_message;
  err->pub.reset_error_mgr = imagify_reset_error;
}

static int imagify_argument_error(char* message, size_t message_capacity, const char* text) {
  if (message != NULL && message_capacity > 0) {
    snprintf(message, message_capacity, "%s", text);
  }
  return IMAGIFY_JPEG_ERR_ARGUMENT;
}

static int imagify_memory_error(char* message, size_t message_capacity) {
  if (message != NULL && message_capacity > 0) {
    snprintf(message, message_capacity, "out of memory");
  }
  return IMAGIFY_JPEG_ERR_MEMORY;
}

/*
 * @return width * height * 4, or 0 when that is not a size this process can allocate
 */
static size_t imagify_pixel_bytes(int width, int height) {
  if (width <= 0 || height <= 0 || width > IMAGIFY_JPEG_MAX_EDGE || height > IMAGIFY_JPEG_MAX_EDGE) {
    return 0;
  }
  size_t total = (size_t)width * (size_t)height;
  if (total > (size_t)-1 / 4u) {
    return 0;
  }
  return total * 4u;
}

const char* imagify_jpegli_abi_version(void) {
  return "1";
}

const char* imagify_jpegli_jpegli_version(void) {
#ifdef IMAGIFY_JPEGLI_VERSION
  return IMAGIFY_JPEGLI_VERSION;
#else
  return "unknown";
#endif
}

int imagify_jpegli_read_header(const uint8_t* data, size_t length, int* width, int* height,
    int* components, int* progressive, int* horizontalFactor, int* verticalFactor, int* densityUnit,
    int* horizontalDensity, int* verticalDensity, int* precision, char* message,
    size_t message_capacity) {
  if (width == NULL || height == NULL || components == NULL) {
    return imagify_argument_error(message, message_capacity, "no room for the result");
  }
  /* A JPEG frame header is two bytes long, so anything shorter cannot be one. */
  if (data == NULL || length < 2) {
    return imagify_argument_error(message, message_capacity, "expected at least two bytes of JPEG");
  }

  struct jpeg_decompress_struct cinfo;
  imagify_error err;
  /* Anything the error handler may run before the longjmp has to survive it, so it is volatile. */
  volatile int result = IMAGIFY_JPEG_ERR_INTERNAL;

  imagify_install_error((struct jpeg_common_struct*)&cinfo, &err, message, message_capacity);
  if (setjmp(err.escape) == 0) {
    volatile int parsed_width = 0;
    volatile int parsed_height = 0;
    volatile int parsed_components = 0;

    jpeg_create_decompress(&cinfo);
    jpeg_mem_src(&cinfo, data, (unsigned long)length);
    if (jpeg_read_header(&cinfo, TRUE) != JPEG_HEADER_OK) {
      result = IMAGIFY_JPEG_ERR_CORRUPT;
    } else {
      parsed_width = (int)cinfo.image_width;
      parsed_height = (int)cinfo.image_height;
      parsed_components = (int)cinfo.num_components;
      if (parsed_width <= 0 || parsed_height <= 0 || parsed_components <= 0) {
        result = IMAGIFY_JPEG_ERR_CORRUPT;
      } else {
        *width = parsed_width;
        *height = parsed_height;
        *components = parsed_components;
        /*
         * Only the luma channel may be subsampled, so its two factors describe the file as a whole
         * and the two colour difference channels follow it. A greyscale file has one component and
         * no colour channels to subsample, and its factors are 1 by definition.
         */
        if (progressive != NULL) {
          *progressive = (cinfo.progressive_mode != 0) ? 1 : 0;
        }
        if (horizontalFactor != NULL) {
          *horizontalFactor = (int)cinfo.comp_info[0].h_samp_factor;
        }
        if (verticalFactor != NULL) {
          *verticalFactor = (int)cinfo.comp_info[0].v_samp_factor;
        }
        if (densityUnit != NULL) {
          *densityUnit = (int)cinfo.density_unit;
        }
        if (horizontalDensity != NULL) {
          *horizontalDensity = (int)cinfo.X_density;
        }
        if (verticalDensity != NULL) {
          *verticalDensity = (int)cinfo.Y_density;
        }
#ifdef IMAGIFY_JPEG_HAS_DATA_PRECISION
        if (precision != NULL) {
          *precision = (int)cinfo.data_precision;
        }
#else
        if (precision != NULL) {
          /* libjpeg 6.2 has no data_precision member at all, and jpegli implements 8 bits and no
             more, so the answer here is a property of the codec rather than of the file. */
          *precision = 8;
        }
#endif
        result = IMAGIFY_JPEG_OK;
      }
    }
    jpeg_destroy_decompress(&cinfo);
  }
  return (int)result;
}

int imagify_jpegli_decode(const uint8_t* data, size_t length, uint8_t** out, size_t* out_length,
    int* width, int* height, char* message, size_t message_capacity) {
  if (out == NULL || out_length == NULL || width == NULL || height == NULL) {
    return imagify_argument_error(message, message_capacity, "no room for the result");
  }
  *out = NULL;
  *out_length = 0;
  if (data == NULL || length < 2) {
    return imagify_argument_error(message, message_capacity, "expected at least two bytes of JPEG");
  }

  struct jpeg_decompress_struct cinfo;
  imagify_error err;
  volatile int result = IMAGIFY_JPEG_ERR_INTERNAL;
  /* Freeing happens after the setjmp region, and longjmp leaves these indeterminate unless they
     are volatile, so the two allocations are reached through volatile pointers. */
  uint8_t* volatile pixels = NULL;
  JSAMPLE* volatile row = NULL;

  imagify_install_error((struct jpeg_common_struct*)&cinfo, &err, message, message_capacity);
  if (setjmp(err.escape) == 0) {
    volatile int image_width = 0;
    volatile int image_height = 0;
    volatile int y = 0;

    jpeg_create_decompress(&cinfo);
    jpeg_mem_src(&cinfo, data, (unsigned long)length);
    jpeg_read_header(&cinfo, TRUE);
    /* Asking for RGB makes libjpeg run the greyscale to RGB and the chroma upsampling itself, so
       there is one layout on this side of the loop below whatever the file was written as. */
    cinfo.out_color_space = JCS_RGB;
    jpeg_start_decompress(&cinfo);

    image_width = (int)cinfo.output_width;
    image_height = (int)cinfo.output_height;
    {
      size_t needed = imagify_pixel_bytes(image_width, image_height);
      if (needed == 0) {
        result = IMAGIFY_JPEG_ERR_CORRUPT;
      } else {
        pixels = (uint8_t*)malloc(needed);
        row = (JSAMPLE*)malloc((size_t)image_width * 3u);
        if (pixels == NULL || row == NULL) {
          result = imagify_memory_error(message, message_capacity);
        } else {
          for (y = 0; y < image_height; y++) {
            JSAMPROW scanline = row;
            if (jpeg_read_scanlines(&cinfo, &scanline, 1) != 1) {
              result = IMAGIFY_JPEG_ERR_CORRUPT;
              break;
            }
            {
              uint8_t* target = pixels + (size_t)y * (size_t)image_width * 4u;
              int x;
              for (x = 0; x < image_width; x++) {
                /* A JPEG cannot carry a real alpha channel. The byte is left opaque rather than
                   composited over a background of our choosing, which would change every pixel
                   the caller goes on to compare. */
                target[x * 4 + 0] = 0xFF;
                target[x * 4 + 1] = row[x * 3 + 2];
                target[x * 4 + 2] = row[x * 3 + 1];
                target[x * 4 + 3] = row[x * 3 + 0];
              }
            }
          }
          if (result != IMAGIFY_JPEG_ERR_CORRUPT) {
            jpeg_finish_decompress(&cinfo);
            *width = (int)image_width;
            *height = (int)image_height;
            *out = pixels;
            *out_length = imagify_pixel_bytes(image_width, image_height);
            pixels = NULL;
            result = IMAGIFY_JPEG_OK;
          }
        }
      }
    }
    free(row);
    row = NULL;
    jpeg_destroy_decompress(&cinfo);
  }
  free(row);
  free(pixels);
  return (int)result;
}

int imagify_jpegli_encode(const uint8_t* pixels, int width, int height, int quality, int subsampling,
    int optimize_coding, uint8_t** encoded, size_t* encoded_length, char* message,
    size_t message_capacity) {
  if (encoded == NULL || encoded_length == NULL) {
    return imagify_argument_error(message, message_capacity, "no room for the result");
  }
  *encoded = NULL;
  *encoded_length = 0;
  if (pixels == NULL) {
    return imagify_argument_error(message, message_capacity, "no pixels to encode");
  }
  if (imagify_pixel_bytes(width, height) == 0) {
    return imagify_argument_error(message, message_capacity, "cannot encode the given size");
  }
  if (quality < IMAGIFY_JPEG_MIN_QUALITY || quality > IMAGIFY_JPEG_MAX_QUALITY) {
    return imagify_argument_error(message, message_capacity, "the quality is out of range");
  }
  if (subsampling != IMAGIFY_JPEG_SAMP_444 && subsampling != IMAGIFY_JPEG_SAMP_422
      && subsampling != IMAGIFY_JPEG_SAMP_420) {
    return imagify_argument_error(message, message_capacity, "the subsampling is not one of 4:4:4, "
                                                             "4:2:2 or 4:2:0");
  }

  struct jpeg_compress_struct cinfo;
  imagify_error err;
  volatile int result = IMAGIFY_JPEG_ERR_INTERNAL;
  /* jpeg_mem_dest keeps the addresses of these two, so it has to write through them, and the error
     handler may free the buffer on its way out. */
  unsigned char* volatile destination = NULL;
  unsigned long volatile destination_length = 0;
  /* Reached through a volatile pointer for the same reason as the two allocations on the decode
     side: the free() that would release it lives outside the setjmp region, and longjmp would leave
     it reading a value the compiler is free to assume is still whatever it was last assigned. */
  JSAMPLE* volatile row = NULL;

  imagify_install_error((struct jpeg_common_struct*)&cinfo, &err, message, message_capacity);
  if (setjmp(err.escape) == 0) {
    volatile int y = 0;
    volatile int complete = 0;

    jpeg_create_compress(&cinfo);
    jpeg_mem_dest(&cinfo, &destination, &destination_length);
    cinfo.image_width = (JDIMENSION)width;
    cinfo.image_height = (JDIMENSION)height;
    cinfo.input_components = 3;
    cinfo.in_color_space = JCS_RGB;
    jpeg_set_defaults(&cinfo);
    /*
     * There is no libjpeg constant for 4:2:2 or for 4:4:4. J_COLOR_SPACE names the colour transform
     * and says nothing about how finely the two colour difference channels are stored, so plain
     * JCS_YCbCr on its own means 4:2:0, which is what the two factors below default to.
     *
     * Only the luma channel's pair is written, because libjpeg requires the others to divide it
     * evenly and derives them from it. 4:4:4 is 1 by 1 rather than a colour space of its own for
     * the same reason: expressing it as JCS_YCbCr plus 1 by 1 is the only way to say it.
     *
     * These are written after jpeg_set_colorspace rather than before it, because that call rebuilds
     * comp_info from the colour space it is given and would otherwise overwrite them.
     */
    jpeg_set_colorspace(&cinfo, JCS_YCbCr);
    cinfo.comp_info[0].h_samp_factor = (subsampling == IMAGIFY_JPEG_SAMP_422) ? 2 : 1;
    cinfo.comp_info[0].v_samp_factor = (subsampling == IMAGIFY_JPEG_SAMP_420) ? 2 : 1;
    jpeg_set_quality(&cinfo, quality, TRUE);
    /* The standard tables are the default, and computing a set per image costs a pass over the
       coefficients and a table the decoder has to read. */
    cinfo.optimize_coding = optimize_coding ? TRUE : FALSE;
    jpeg_start_compress(&cinfo, TRUE);

    {
      row = (JSAMPLE*)malloc((size_t)width * 3u);
      if (row == NULL) {
        result = imagify_memory_error(message, message_capacity);
      } else {
        JSAMPLE* buffer = row;
        for (y = 0; y < height; y++) {
          JSAMPROW scanline = buffer;
          const uint8_t* source = pixels + (size_t)y * (size_t)width * 4u;
          int x;
          for (x = 0; x < width; x++) {
            /* The alpha byte is dropped, since a JPEG cannot store one. */
            buffer[x * 3 + 0] = source[x * 4 + 3];
            buffer[x * 3 + 1] = source[x * 4 + 2];
            buffer[x * 3 + 2] = source[x * 4 + 1];
          }
          if (jpeg_write_scanlines(&cinfo, &scanline, 1) != 1) {
            break;
          }
        }
        /* Released here rather than after the region so that a longjmp out of jpeg_finish_compress
           has nothing left to leak, and set to NULL so the free() below has nothing to double. */
        free(row);
        row = NULL;
        complete = (y == height);
        if (!complete) {
          /* jpeg_finish_compress was not reached, so there is no file to hand back. */
          result = IMAGIFY_JPEG_ERR_INTERNAL;
        } else {
          jpeg_finish_compress(&cinfo);
          if (destination == NULL || destination_length == 0) {
            result = IMAGIFY_JPEG_ERR_INTERNAL;
          } else {
            uint8_t* copy = (uint8_t*)malloc((size_t)destination_length);
            if (copy == NULL) {
              result = imagify_memory_error(message, message_capacity);
            } else {
              memcpy(copy, destination, (size_t)destination_length);
              *encoded = copy;
              *encoded_length = (size_t)destination_length;
              result = IMAGIFY_JPEG_OK;
            }
          }
        }
      }
    }
    /* jpeg_destroy_compress frees the destination buffer jpeg_mem_dest owns. */
    jpeg_destroy_compress(&cinfo);
  }
  free(row);
  return (int)result;
}

void imagify_jpegli_free(void* buffer) {
  free(buffer);
}
