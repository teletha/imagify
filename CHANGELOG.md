# Changelog

## [1.1.0](https://github.com/teletha/imagify/compare/1.0.3...1.1.0) (2026-09-29)


### Features

* add a WebP ImageIO plug-in backed by webp4j ([9ad8b78](https://github.com/teletha/imagify/commit/9ad8b789c2f8ceda9b947580215d756a4e7a6039))
* add animated GIF to animated WebP conversion test ([f8b0d41](https://github.com/teletha/imagify/commit/f8b0d41b7325ad83df9b8686de0129472946af76))
* add animated GIF to AVIF conversion with resize support ([419639e](https://github.com/teletha/imagify/commit/419639ed7fc974a2abae97c5262a71bf78e34d52))
* add crop, rotate, flip and animation-aware AVIF ImageIO ([bdd89d0](https://github.com/teletha/imagify/commit/bdd89d0514ffbce94124acbfc53c07c4d31055fb))
* add ICO support and an SVG reader backed by JSVG ([ef9c712](https://github.com/teletha/imagify/commit/ef9c71229cd2e012736ec26700948de044a859aa))
* add ImagePipeline, SpriteSheet, ImageReader/Writer/Resizer with auto-detection ([46a8ba7](https://github.com/teletha/imagify/commit/46a8ba78502dd7bcec55bc188c511e1a97708cca))
* add Imagify.toStillImage() to drop animation frames ([d315a52](https://github.com/teletha/imagify/commit/d315a52c3ffe276197880911717782463bc415c3))
* add resize algorithms NEAREST AREA BSPLINE GAUSSIAN LANCZOS2; add resize comparison report with file sizes; fix Catmull-Rom kernel ([d81eeb4](https://github.com/teletha/imagify/commit/d81eeb4635279ae3254d19924ad8cd14225f0258))
* addFrame/read methods refactored with varargs and List support ([a68adde](https://github.com/teletha/imagify/commit/a68adde6c273c82af2a10bfece7a37126da22a9d))
* dual webp backend ([8530d17](https://github.com/teletha/imagify/commit/8530d177291f38221c0e5d07ce7b3ab18719e1da))
* encode and decode JPEG with jpegli ([7eed05b](https://github.com/teletha/imagify/commit/7eed05b5e1c06173113ef116227405bca8a727bb))
* expose Imagify#map ([c0edaa1](https://github.com/teletha/imagify/commit/c0edaa1c99a0e205aa554987c29435b9a728d9a3))
* let the AVIF format carry subsampling and chroma downsampling settings ([0939e59](https://github.com/teletha/imagify/commit/0939e59305d7585e8a5ad0fd2bf4bbaef6fc830e))
* let the JPEG and PNG formats carry encoder settings ([ef40136](https://github.com/teletha/imagify/commit/ef401361e75d1d5064ea27e4d911d0f5a09b2273))
* let the WebP and AVIF formats carry encoder settings ([93d5c4d](https://github.com/teletha/imagify/commit/93d5c4d31a7fba151a2d4545d4502bcadcabfc15))
* let the WebP format carry its lossless flavour into the codec ([1355e69](https://github.com/teletha/imagify/commit/1355e695cd3f6402613288b0fb5add8d35112241))
* support resizing ([5bfdf34](https://github.com/teletha/imagify/commit/5bfdf34a04f8952ce1e4f7de605ff8a41c19e13d))
* unify ImagePipeline API with FrameSequence and automatic animation detection ([d4aaa11](https://github.com/teletha/imagify/commit/d4aaa1147612e372cb8a517abb3fe57743c8f5e1))
* **webp:** bind the bundled libwebp with the Foreign Function & Memory API ([e008157](https://github.com/teletha/imagify/commit/e00815717d364552874eb15907eacf240f557279))
* **webp:** encode and decode WebP with a bundled libwebp, dropping webp4j ([b944682](https://github.com/teletha/imagify/commit/b9446824edc38d0c224982475b94e96822499313))


### Bug Fixes

* honour the quality argument and drop the AVIF reflection ([3ecdd34](https://github.com/teletha/imagify/commit/3ecdd34b1d36a80b8ec7f51062967e97898f3c7b))
* **jpeg:** build the JPEG singleton without reading the subclass default ([5e6e8da](https://github.com/teletha/imagify/commit/5e6e8da2ac8b1c547d001c33314ac2f894aac3c7))
* **jpeg:** let a caller ask what the provider writes at, and stop testing jpegli as libjpeg ([ac47774](https://github.com/teletha/imagify/commit/ac477749fcdb30a0dbaf320b17be17a592d916cc))
* **jpeg:** write the luma sampling factors as a pair, not as two independent choices ([52f251b](https://github.com/teletha/imagify/commit/52f251b1956d809e248fcdb6dcb85491806a999c))
* **native:** build jpegli from a pinned commit and against its real layout ([a5dabf5](https://github.com/teletha/imagify/commit/a5dabf52eb832d14343880a9a804fce972934fab))
* **native:** spell the ELF version node as one identifier ([aa90574](https://github.com/teletha/imagify/commit/aa905744c96a54c66d0644350204864869fd34ff))
* preserve alpha channel in all resize operations (bilinear, area, kernel); switch resize report to percentage scales; remove sliders from format report; limit image display to 480px; remove JPEG from format comparison ([df7d746](https://github.com/teletha/imagify/commit/df7d7463158e4fecf53c0920d312a3f159926b3f))
* separate output directories for animated GIF WebP tests ([8645e2c](https://github.com/teletha/imagify/commit/8645e2cd2074f1a3af718cdffe4c0b7eb031106d))
* **webp:** name the structure fields explicitly for JNA ([0fdc0bf](https://github.com/teletha/imagify/commit/0fdc0bfd9b6c0deeacc837a8d6f2d626568296b7))
* **webp:** report the frame timing and loop count of an animation ([43bf58d](https://github.com/teletha/imagify/commit/43bf58d7aa4c5e76143604b9a0402e8e8bbb522c))


### Performance Improvements

* **avif:** make the animation encoder 26x faster ([3b79567](https://github.com/teletha/imagify/commit/3b7956710a93339ceffe41a5b9e9297366470f70))
* **webp:** make lossless honour its quality, add zero-copy paths and batch writes ([38b2c10](https://github.com/teletha/imagify/commit/38b2c109fac545463c776a266767857c0f223e2a))
* **webp:** read an animation in one pass instead of one pass per frame ([a7e0b22](https://github.com/teletha/imagify/commit/a7e0b22988542c88af73e955baef874e70e8c8d4))

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

* the JPEG codec is `imagify.jpeg.ffm.JpegliCodec`, bound through a C shim and Java's Foreign
  Function & Memory API, and the `imagify.jpeg.jna` package is gone along with the JNA binding
  it held: `JpegliCodec`, `JpegliLibrary` and `JpegliNativeLibrary`.

  A program that named `imagify.jpeg.jna.JpegliCodec` has to name
  `imagify.jpeg.ffm.JpegliCodec` instead. The static methods on it are the same and take the same
  arguments, so this is one import and one class name. The one visible difference is that an
  encode whose source is already a `TYPE_4BYTE_ABGR` image hands its own backing array to the shim
  rather than copying it into native memory first. The `jna.library.path` system property no longer
  means anything; a system jpegli is now found through `java.library.path`, or the platform's own
  search path.

* the default AVIF, JPEG and WebP codecs are bound through `java.lang.foreign`, so a program on
  JDK 24 or newer that uses any of them from the class path is asked to allow native access.
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
  reach libavif without a copy of them. One native file per platform, as with WebP.
* `AvifCodec.readHeader` reads a file's container without decoding a pixel, and
  `AvifCodec.openSequence` walks a file's frames one at a time instead of decoding all of
  them. A 498x280 header read measures under a millisecond against 23 ms for the decode.

* encode and decode JPEG with a bundled `jpegli` through the Foreign Function & Memory API, and
  dropped the JNA binding. An image already in `TYPE_4BYTE_ABGR` is read where it lies rather
  than copied into native memory, and the shim keeps `jpeg_compress_struct` and
  `jpeg_decompress_struct` on its own side of the boundary, as it always did.
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
