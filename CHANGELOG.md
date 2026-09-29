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

* `imagify.webp.WebpCodec` is the entry point again, and dispatches to a backend rather
  than being one. The FFM implementation stays where it was, in
  `imagify.webp.ffm.WebpCodec`, so code importing that still compiles and still means the
  FFM backend, and code importing `imagify.webp.WebpCodec` gets whichever backend is in
  use. Nothing has to change.

  `WebpCodec.DecodedWebp` is now the top level `imagify.webp.DecodedWebp`, because both
  backends answer with it and a nested type cannot be named from a class of the same
  simple name. `WebpCodec.Backend` is new, and answers which backend is in use.

  The default WebP codec is bound through `java.lang.foreign`, so a program on JDK 24 or
  newer that uses it from the class path is asked to allow native access. Nothing fails
  without it, but the JDK warns on every run and will block the call in a later release:

      java --enable-native-access=ALL-UNNAMED -cp ... YourApp

### Features

* two WebP backends ship in the jar and the system property `imagify.webp.backend`
  chooses between them, read once on first use:

      java -Dimagify.webp.backend=webp4j -cp ... YourApp

  `ffm` is the default and is the one described below. `webp4j` binds the same `libwebp`
  through JNI, so it needs no `--enable-native-access` flag, and it ships its own copy of
  the library inside its own jar. The `webp4j-core` dependency comes back, so a project
  that had to declare it by hand for the WebP plug-in no longer has to.

  A backend that is named but cannot load its library is not a failure: the other one is
  used instead and the substitution is logged, and only when neither can load does
  `WebpCodec.isAvailable()` answer false. A name that is neither backend is a warning and
  the default, which is the same bargain the ImageIO plug-ins have always made.

  The one setting the two do not share is the encoding effort of a still image. `webp4j`'s
  still image entry point takes quality, lossless and threading and nothing else, so it
  always encodes a still at libwebp's own default of method 4 and says so once at
  `WARNING` rather than refusing, which would make `ImageFormat.Webp.compressionMethod()`
  unusable on that backend. An animation can be told either effort on both. Everything
  else is the same work either way, and a file written through one backend is read by the
  other: `WebpBackendTest` pins that by driving both of them and asking them to agree.

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
  effect at all. `thread_level` is compiled in and deliberately left at zero: only
  `VP8EncAnalyze` reads it, and it splits a frame in two rather than across as many
  threads as it has. The default therefore does not move.

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
