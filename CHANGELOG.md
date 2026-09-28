# Changelog

## [Unreleased]

### Features

* encode and decode WebP with a bundled `libwebp`, dropped `webp4j`

### ⚠ Breaking change

* `imagify.webp.WebpCodec` moved to `imagify.webp.jna.WebpCodec`, alongside `AvifCodec` and
  `JpegliCodec`. The public API of the class is unchanged; the import is what changed. The
  `webp4j-core` dependency is gone, replaced by a bundled `libwebp` shim built for the same six
  platforms the other codecs ship for. Update the import and, if one was declared, drop the
  `webp4j-core` dependency.

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
