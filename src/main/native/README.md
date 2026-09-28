# Native sources

The C sources here are the jpegli based JPEG codec that `imagify.jpeg.jna.JpegliCodec` binds through
JNA. Everything else this project does in native code is a dependency it ships prebuilt; this is the
one place where the jar has to carry code of its own, because the binding needs somewhere for libjpeg's
error handling to live.

## What is here

| File | What it is |
| --- | --- |
| `imagify_jpegli.h` | the flat C ABI, which is the whole contract between this jar and the shared library |
| `imagify_jpegli.c` | the shim: libjpeg's error handling, and the `A, B, G, R` pixel conversion |
| `CMakeLists.txt` | builds the shared library for all six targets this jar supports |

The binaries these produce go into `src/main/resources/imagify/jpeg/native/`, which is documented by
`README.md` in that directory and built by `.github/workflows/jpegli-natives.yml`.

## Why a shim at all

The obvious binding is JNA straight onto jpegli's libjpeg62 interface: name `jpeg_create_compress`
and friends on a `Library` interface and call them. That does not work, for one reason that is not
about structures and cannot be worked around.

libjpeg reports a fatal error by calling `cinfo->err->error_exit`, and that callback must not return.
The only portable way of getting control back is `setjmp` / `longjmp` across the call that failed.
JNA cannot take part in it: `CallbackReference.DefaultCallbackProxy` catches every `Throwable` a
`Callback` throws, passes it to a `CallbackExceptionHandler`, and documents that the method must not
throw. A callback wired up as `error_exit` would therefore return normally, libjpeg would carry on
past an error it believes to be fatal, and a damaged file could be read into a structure field that
was never written. The unwinding happens in the shim instead, where `setjmp` is real and every entry
point answers a status code rather than throwing.

That leaves the other thing a direct binding would have to do, which is describe `struct
jpeg_compress_struct` and `struct jpeg_decompress_struct` field by field: several hundred fields of
nested substructures, two different layouts, and both of them following the libjpeg revision rather
than anything this project controls. The header here is a handful of `int`, `size_t` and `void *`
parameters instead, and it is what `JpegliLibrary.java` mirrors.

## ABI versioning

`IMAGIFY_JPEGLI_ABI_VERSION` in the header is checked once when the library is loaded. A library
built against a different revision of the header answers the version call with a different number and
is refused before the first real call, rather than being discovered later as garbage pixels. Raise it
only when the header changes in a way the Java binding does not already know about.
