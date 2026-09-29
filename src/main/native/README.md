# Native sources

The C sources here are the shims behind the two codecs of this library that cannot be bound straight
through the standard Java FFM API: `jpegli/` for `imagify.jpeg.ffm.JpegliCodec` and `webp/` for
`imagify.webp.ffm.WebpCodec`. Everything else this project does in native code is a dependency it
ships prebuilt; this is the one place where the jar has to carry code of its own, because both
bindings need a layer that keeps a third party structure layout on the C side.

## What is here

| Directory | What it is |
| --- | --- |
| `jpegli/imagify_jpegli.h` | the flat C ABI, which is the whole contract between this jar and the jpegli shared library |
| `jpegli/imagify_jpegli.c` | the jpegli shim: libjpeg's error handling, and the `A, B, G, R` pixel conversion |
| `jpegli/CMakeLists.txt` | builds the jpegli shared library for all six targets this jar supports |
| `webp/imagify_webp.h` | the flat C ABI, which is the whole contract between this jar and the libwebp shared library |
| `webp/imagify_webp.c` | the libwebp shim: the encoder and decoder calls, and the `A, B, G, R` pixel conversion |
| `webp/CMakeLists.txt` | builds the libwebp shared library for all six targets this jar supports |

The binaries these produce go into `src/main/resources/imagify/jpeg/native/` and
`src/main/resources/imagify/webp/native/`, which are documented by the `README.md` files in those
directories and built by `.github/workflows/jpegli-natives.yml` and
`.github/workflows/webp-natives.yml`.

## Why a shim at all

The obvious binding is straight onto the third party API, and that does not work for each codec
for a reason of its own.

### The jpegli shim

jpegli implements the whole of libjpeg's public interface, so a binding could in principle name
`jpeg_start_compress` and friends directly. Two problems make it not worth doing:

- libjpeg reports a fatal error by calling `cinfo->err->error_exit`, and that callback must not
  return. The only portable way of getting control back is `setjmp` / `longjmp` across the call that
  failed. A binding cannot take part in it: a handler that has to return into Java must return, and
  `error_exit` must not, so a handler wired up as `error_exit` would return normally, libjpeg would
  carry on past an error it believes to be fatal, and a damaged file could be read into a structure
  field that was never written. The unwinding happens in the shim instead, where `setjmp` is real and
  every entry point answers a status code rather than throwing.
- A direct binding would also have to describe `struct jpeg_compress_struct` and `struct
  jpeg_decompress_struct` field by field: several hundred fields of nested substructures, two
  different layouts, and both of them following the libjpeg revision rather than anything this
  project controls. The header here is a handful of `int`, `size_t` and `void *` parameters instead.

### The libwebp shim

libwebp's C API has no error callbacks, so the reason there is structures. Encoding a picture means
filling in `struct WebPPicture`, which is 256 bytes on a 64 bit platform and mostly not data: it is
the padding libwebp reserves for itself, a union of three sample pointers, a statistics block it may
write through, and four function pointers. A binding would have to name every one of those fields
and get every offset right, and a mistake in any of them is not a compile error but a wild pointer
write while a picture is being encoded. The animation API cannot be driven without one either, since
it takes a `WebPPicture` per frame: an animated file is built by handing the encoder one picture
after another.

The `imagify_webp.h` header is a handful of `int`, `size_t` and `void *` parameters instead, exactly
like the jpegli one, and it is what `WebpLibrary.java` mirrors.

## Why the libraries are not called `jpeg` and `webp`

jpegli's own target has the soname `libjpeg.so.62`, and libwebp's has `libwebp.so.7`; both are also
the sonames of every system library of that kind on Linux. Two libraries with the same soname can
end up mapped in one process and whichever was mapped first wins the symbols. This jar has no reason
to interoperate with a system libjpeg or libwebp, so the shims are called `jpegli` and
`imagifywebp` and nothing in them can collide.

## ABI versioning

`IMAGIFY_JPEGLI_ABI_VERSION` and `IMAGIFY_WEBP_ABI_VERSION` in the headers are checked once when the
library is loaded. A library built against a different revision of a header answers the version call
with a different number and is refused before the first real call, rather than being discovered later
as garbage pixels. Raise one only when its header changes in a way the Java binding does not already
know about.