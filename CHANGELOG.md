# Changelog

## [Unreleased]

### ⚠ Breaking change

* the AVIF codec is `imagify.avif.ffm.AvifCodec`, bound through a C shim and Java's Foreign
  Function & Memory API, and the `imagify.avif.jna` package is gone along with the JNA
  binding it held: `AvifCodec`, `AvifLibrary` and the fourteen structures it declared, plus
  `AvifAnimationDecoder` and `AvifNativeLibrary`.

  A program that named `imagify.avif.jna.AvifCodec` has to name `imagify.avif.ffm.AvifCodec`
  instead. The static methods on it are the same and take the same arguments, so this is one
  import and one class name, with two exceptions: `AvifAnimationDecoder` is now
  `AvifSequence` and is opened with `AvifCodec.openSequence` rather than
  `AvifAnimationDecoder.open`, and `AvifCodec.library()` and `requireLibrary()` are gone
  because there is no binding object to hand back.

* the WebP codec is `imagify.webp.ffm.WebpCodec`, bound through Java's Foreign Function &
  Memory API, and there is nothing in front of it any more. Both the second backend and the
  facade that dispatched to it are gone: the `webp4j-core` dependency, the
  `imagify.webp.backend` system property, `WebpCodec.Backend`, `WebpCodec.backend()`,
  `WebpCodec.Backend` and the `imagify.webp.webp4j` package, along with the
  `imagify.webp.WebpCodec` facade and the `WebpBackendTest` that pinned the two backends to
  agreeing.

  A program that named `imagify.webp.WebpCodec` or set the property compiles and means the
  same thing as before, one import shorter. One that named `imagify.webp.webp4j.WebpCodec`
  has to name `imagify.webp.ffm.WebpCodec` instead, and one that read `WebpCodec.backend()`
  has nothing left to ask.

* the default AVIF and WebP codecs are bound through `java.lang.foreign`, so a program on
  JDK 24 or newer that uses either from the class path is asked to allow native access.
  Nothing fails without it, but the JDK warns on every run and will block the call in a
  later release:

      java --enable-native-access=ALL-UNNAMED -cp ... YourApp

### Fixed

* the three CICP fields of an AVIF file were read as garbage. `colorPrimaries`,
  `transferCharacteristics` and `matrixCoefficients` are `uint16_t` in `avifImage`, and the
  JNA binding declared all three as `int`, which is two bytes wider and shifts every field
  after them. A file whose colour primaries are 2 was reported as **131074**, which is that
  2 and the next field shifted up by sixteen bits. The pictures were unaffected: libavif
  reads its own fields by pointer and never went through the binding's copy. Only
  `AvifImageInfo`, and so only what `AvifMetadata` publishes through `ImageIO`, was wrong.
  The shim reads them in C, where the compiler checks the width against the header it was
  compiled with, and `AvifShimTest` pins the value against a file that carries 2.

### Features

* encode and decode AVIF with a bundled `libavif` through the Foreign Function & Memory
  API, and dropped the JNA binding. libavif is a plain C library and was bound directly at
  first, which needed a hand written `MemoryLayout` for each of its thirteen structures;
  the shim replaced those with a C file the compiler checks, and made an encode's pixels
  reach libavif without a copy of them.
* `AvifCodec.readHeader` reads a file's container without decoding a pixel, and
  `AvifCodec.openSequence` walks a file's frames one at a time instead of decoding all of
  them. A 498x280 header read measures under a millisecond against 23 ms for the decode.

* encode and decode WebP with a bundled `libwebp` through the Foreign Function &amp; Memory
  API, and dropped the JNA binding
* `WebpCodec.encode` takes an encoding effort, so a still image is no longer stuck at
  libwebp's default. Measured on a 498x280 photograph at quality 75, method 2 is
  **2.7x** quicker than method 4 for 4.3% more bytes; on a 1600x1200 gradient,
  method 0 is **4.9x** quicker for 3.9% more bytes. The default is still libwebp's
  own method 4, so a file written by this version is byte for byte the file
  `cwebp` writes from the same options.
* `ImageWriter.toBytes(List, ...)` and `ImageWriter.toFiles(List, ...)` encode many
  images across every core. Twelve independent 1600x1200 lossy encodes measured
  **6.4x** faster on 12 threads than on one, and the result is the same list in the
  same order either way.

### Performance

* the default AVIF encoder speed is 6 rather than `libavif`'s own `AVIF_SPEED_DEFAULT`.
  These are not the same thing on a current build: measured at 517x380 and quality 70
  against libavif 1.4.2, `AVIF_SPEED_DEFAULT` is indistinguishable from
  `AVIF_SPEED_SLOWEST` and costs **4917 ms** against **170 ms** at speed 6, for a file of
  the same **77 kB**. A default that is 29x slower for the same bytes is not a default
  anybody wants, and every AVIF file this version writes is produced at a different point
  on that curve: at quality 10 a 517x380 card goes from 45.5 kB at 19.9 dB PSNR to
  44.8 kB at 18.4 dB, and at quality 90 from 135.6 kB at 37.5 dB to 137.3 kB at 36.0 dB.
* an AVIF encode hands its pixels to `libavif` where they are instead of copying them
  into native memory first, and a decode copies them out of `libavif`'s own buffer once
  rather than twice. Neither side of the boundary moves a pixel any more, which is a
  change in what is copied rather than a measurable change in how long a decode takes:
  libavif's own YUV to RGB conversion is most of the work, so on a 12 core Ryzen 9 a
  498x280 decode measured **23 ms** and the copy that was removed measured **below the
  resolution of the measurement**. At 4000x3000, where the pixels are 45 MB, a decode
  measured **169 ms** and one `memcpy` of that many bytes **2 ms**, so the copy was
  about **1%** of a decode. The structural change is real and the speedup is not worth
  claiming as one.
* a lossless encode honours the quality it is given instead of having it pinned to
  100. libwebp reads `quality` in lossless mode as an amount of effort, and the
  pinned 100 was the most effort it can be given: 86 iterations against 51 at 75, and
  a backward reference window of the whole picture rather than 256 rows. Measured
  **3.5x** faster on noise and **2.5x** on an alpha image, and the caller's number is
  no longer discarded.
* pixels that are already a run of `0xAARRGGBB` words are handed to `libwebp` as
  themselves instead of being packed into `A, B, G, R` bytes first, and a decoded
  image is written into words rather than into bytes that are then repacked. Nothing
  on either side of the boundary moves a pixel. Measured: animation encode
  **1.44x**, decode **1.73x**, still encode about 1.08x.
* the eight other `WebPConfig` fields were measured and none of them makes a lossy
  encode faster at the same quality: `exact` 1.00x and byte identical, `sns_strength`
  within noise and worse at the fast end, `filter_strength` slower, `pass` 0.19x to
  0.53x, `segments` 1.06x for 0.80 dB, `use_sharp_yuv` 0.53x, and `target_size` no
  effect at all. So the encoder settings are otherwise libwebp's own.
* `thread_level` is set to 1 rather than left at libwebp's zero. It used to be left
  there on the strength of a measurement taken on one 24 core host that found the flag
  not worth setting, which was generalised into a claim about the encoder. Measured on
  a 12 core Ryzen 9, medians over 20 to 60 iterations with the same method on both
  sides: 107x103 **2.68 ms to 2.17 ms**, 517x380 **37.19 ms to 22.47 ms**, so a 517x380
  encode went from 2.02x slower than the JNI binding it replaced to 1.19x. Only
  `VP8EncAnalyze` reads the flag and it splits a frame in two rather than across as many
  threads as it has, which caps the gain rather than making it zero. Level 1 rather than
  more because that is what level 1 buys. `exact` was re-measured on the same host and
  is still 1.00x and byte identical, so it stays at 1.

### Notes

* the three new native entry points are looked up as optional symbols and the ABI
  version is unchanged, so a library built for one platform still works with a
  binding built for another. The whole test suite passes against the previous
  `windows-x64` binary, where none of the three exist and every encode takes the
  byte path. The speedups above need the rebuilt native, and the lossless quality
  fix and the batch API do not.

## [1.0.3](https://github.com/teletha/imagify/compare/1.0.2...1.0.3) (2026-09-26)


### Bug Fixes

* env ([24da187](https://github.com/teletha/imagify/commit/24da1877da3f5c524f136916a7025e274dabc3ff))

## [1.0.2](https://github.com/teletha/imagify/compare/1.0.1...1.0.2) (2026-09-26)


### Bug Fixes

* env ([3f0142e](https://github.com/teletha/imagify/commit/3f0142e6d423cbe0eaf742d7bc56c9deb2d86dc4))

## [1.0.1](https://github.com/teletha/imagify/compare/1.0.0...1.0.1) (2026-09-26)


### Bug Fixes

* env ([1e79d02](https://github.com/teletha/imagify/commit/1e79d022072742ab35f7bec5c3895a393711b6c7))

## 1.0.0 (2026-09-26)


### Features

* init relase ([580b767](https://github.com/teletha/imagify/commit/580b767955176651a2d478fa785981e8f9fc9a13))
