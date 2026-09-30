# `imagifyavif` release metadata

This directory no longer holds shared libraries. The shim is built by the `avif-natives` workflow
and published as a GitHub release, and the only thing this directory carries is the one thing the
jar needs to know about that release: the tag, in `native.properties`. `AvifLoader` hands the tag to
`NativeRepository`, which downloads the file for the platform the codec runs on the first time one
is needed and loads it by absolute path, so users never have to install anything.

There is one file per platform and nothing to put beside it: libavif is linked into the shim, which
is the same shape the WebP shim has. The two-file version was tried first and does not work; the
reasoning is in `src/main/native/avif/CMakeLists.txt`.

## The release the other side of this directory names

| File | Platform | Build |
| --- | --- | --- |
| `imagifyavif-windows-x64.dll` | Windows x64 | MSVC, `/MT`, x64 |
| `imagifyavif-windows-arm64.dll` | Windows arm64 | MSVC, `/MT`, arm64 |
| `imagifyavif-linux-x64.so` | Linux x64 | glibc, x86_64 |
| `imagifyavif-linux-arm64.so` | Linux arm64 | glibc, aarch64 |
| `imagifyavif-macos-x64.dylib` | macOS x64 | AppleClang, x86_64 |
| `imagifyavif-macos-arm64.dylib` | macOS arm64 | AppleClang, arm64 |

The names are built by `NativeRepository.assetName` and pinned by tests. A release that lacks one of
them silently disables AVIF support on that platform, which is why the tests exist rather than a
comment.

What the runtime downloads sits in a local cache, keyed by the release tag and the file name, so a
library is fetched once and reused until the cache is deleted. Where and whether anything is fetched
is configurable:

```
-Dimagify.native.cache=/path/to/cache   # where downloads are cached
-Dimagify.native.download=false         # never download; use a system library
-Dimagify.native.url=https://mirror/... # where to fetch from
-Dimagify.avif.library=/path/to.so      # one explicit library, no download
-Dimagify.avif.bundled=false            # use a system libavif instead
```

## Publishing a release

`.github/workflows/avif-natives.yml` builds all six and publishes them under the tag in
`native.properties`. Run it with:

```
gh workflow run avif-natives.yml
```

The release is immutable: the workflow refuses to publish a tag that already exists, because a
client caches what it downloaded for a tag and would have no way to learn that a file changed. A
rebuild of the same libavif version is published under the same tag plus a dash suffix
(`avif-natives-1.4.2-2`), and the suffix is written into `native.properties` in the same commit, so
the change ships with the next imagify release.

## Building them

An AVIF encode and decode binds libavif through the shim, so both have to be built:

```
cmake -S libavif -B build -G Ninja \
      -DCMAKE_BUILD_TYPE=Release \
      -DBUILD_SHARED_LIBS=OFF \
      -DAVIF_BUILD_APPS=OFF \
      -DAVIF_BUILD_TESTS=OFF \
      -DAVIF_LIBYUV=LOCAL \
      -DAVIF_LIBSHARPYUV=LOCAL \
      -DAVIF_CODEC_AOM=LOCAL
cmake --build build --target avif_static
cmake -S src/main/native/avif -B shim-build -G Ninja \
      -DCMAKE_BUILD_TYPE=Release \
      -DIMAGIFY_AVIF_SOURCE_DIR=$PWD/build \
      -DIMAGIFY_AVIF_HEADER_DIR=$PWD/libavif/include
cmake --build shim-build
```

The workflow reproduces this for all six platforms; the version of libavif it checks out is the pin
at the top of the workflow.