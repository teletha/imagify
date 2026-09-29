# Changelog

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
