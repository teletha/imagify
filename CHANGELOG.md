# Changelog

## [Unreleased]

### ⚠ Breaking change

* `imagify.webp.WebpCodec` moved from `imagify.webp.jna.WebpCodec` to
  `imagify.webp.ffm.WebpCodec`, replacing JNA with Java's Foreign Function &amp; Memory
  API (JEP 454). The public API of the class is unchanged; only the import and the
  internal implementation changed. Update the import, and drop the `webp4j-core`
  dependency if one was declared for the WebP plug-in. The `jna` dependency itself
  stays, because the AVIF and JPEG plug-ins still use it.

  The WebP codec is bound through `java.lang.foreign`, so a program on JDK 24 or newer
  that uses it from the class path is asked to allow native access. Nothing fails
  without it, but the JDK warns on every run and will block the call in a later
  release:

      java --enable-native-access=ALL-UNNAMED -cp ... YourApp

### Features

* encode and decode WebP with a bundled `libwebp`, dropped `webp4j`

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
