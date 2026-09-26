# Bundled `libavif` shared libraries

This directory holds the prebuilt `libavif` shared libraries that ship inside the jar, one per
supported platform. `AvifNativeLibrary` picks the right one at runtime, unpacks it to a temporary
directory and loads it by absolute path, so users never have to install anything.

## Expected files

| File | Platform | Build |
| --- | --- | --- |
| `libavif-windows-x64.dll` | Windows x64 | MSVC, `/MD`, x64 |
| `libavif-windows-arm64.dll` | Windows arm64 | MSVC, `/MD`, arm64 |
| `libavif-linux-x64.so` | Linux x64 | glibc, x86_64 |
| `libavif-linux-arm64.so` | Linux arm64 | glibc, aarch64 |
| `libavif-macos-x64.dylib` | macOS x64 | AppleClang, x86_64 |
| `libavif-macos-arm64.dylib` | macOS arm64 | AppleClang, arm64 |

The names are hardcoded in `AvifNativeLibrary.resourceName`; renaming a file here silently
disables AVIF support on that platform.

## Building them

`.github/workflows/avif-natives.yml` builds all six and attaches them to a rolling GitHub release.
Run it with:

```
gh workflow run avif-natives.yml
```

The build is a plain libavif CMake configure with the CLI tools turned off and `aom` linked in
statically:

```
cmake -S libavif -B build \
      -DCMAKE_BUILD_TYPE=Release \
      -DBUILD_SHARED_LIBS=ON \
      -DAVIF_BUILD_APPS=OFF \
      -DAVIF_BUILD_TESTS=OFF \
      -DAVIF_CODEC_AOM=LOCAL \
      -DAVIF_CODEC_DAV1D=LOCAL \
      -DAVIF_CODEC_RAV1E=LOCAL \
      -DAVIF_CODEC_SVT=LOCAL
cmake --build build --config Release --target avif
```

`AVIF_CODEC_*=LOCAL` matters: it makes libavif fetch and build its codecs in-tree instead of linking
against whatever the CI image happens to have. The result is a **self-contained** library with no
further shared dependencies, which is what lets a single file per platform be shipped.

## Why a self-contained library is required

On Windows, `LoadLibrary` resolves the dependencies of the module it loads against the directory of
the *executable* and against `PATH`, never against the directory of the module itself. A
`libavif.dll` that links `aom.dll` next to it would therefore fail to load from a temporary
directory. A distribution build of `libavif` such as MSYS2's, which is only 326 KB precisely because
it links aom, dav1d, rav1e, SvtAv1Enc, libyuv, libjpeg, libpng, libxml2 and zlib dynamically, cannot
be redistributed this way.

## Licensing

`libavif` is BSD-2-Clause and `aom`, `dav1d`, `rav1e` and `SVT-AV1` are all BSD-2-Clause as well, so
redistribution is permitted. Keep the upstream `LICENSE` files next to the binaries and reproduce
them in the distribution notices.

## Pinning the version

The bundled library is whatever the workflow last built, so its version drifts over time. The JNA
structures in this package are binary compatible with `libavif` 1.0.0 through 1.4.x, and
`AvifCodec` refuses anything outside that range, which is what keeps a freshly built binary from
silently corrupting pixels. Raise `MIN_VERSION` / `MAX_VERSION` only after re-checking the struct
layouts against the new header.
