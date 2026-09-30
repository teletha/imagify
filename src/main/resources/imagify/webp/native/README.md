# `libwebp` release metadata

This directory no longer holds shared libraries. They are built by the `webp-natives` workflow and
published as a GitHub release, and the only thing this directory carries is the one thing the jar
needs to know about that release: the tag, in `native.properties`. `WebpNativeLibrary` hands the tag
to `NativeRepository`, which downloads the file for the platform the codec runs on the first time
one is needed and loads it by absolute path, so users never have to install anything.

## The release the other side of this directory names

| File | Platform | Build |
| --- | --- | --- |
| `imagifywebp-windows-x64.dll` | Windows x64 | MSVC, `/MT`, x64 |
| `imagifywebp-windows-arm64.dll` | Windows arm64 | MSVC, `/MT`, arm64 |
| `imagifywebp-linux-x64.so` | Linux x64 | glibc, x86_64 |
| `imagifywebp-linux-arm64.so` | Linux arm64 | glibc, aarch64 |
| `imagifywebp-macos-x64.dylib` | macOS x64 | AppleClang, x86_64 |
| `imagifywebp-macos-arm64.dylib` | macOS arm64 | AppleClang, arm64 |

The names are built by `NativeRepository.assetName` and pinned by tests. A release that lacks one of
them silently disables the WebP codec on that platform, which is not an error anywhere: the JDK's
own WebP support takes over and keeps working.

What the runtime downloads sits in a local cache, keyed by the release tag and the file name, so a
library is fetched once and reused until the cache is deleted. Where and whether anything is fetched
is configurable:

```
-Dimagify.native.cache=/path/to/cache   # where downloads are cached
-Dimagify.native.download=false         # never download; use a system library
-Dimagify.native.url=https://mirror/... # where to fetch from
-Dimagify.webp.library=/path/to.so      # one explicit library, no download
-Dimagify.webp.bundled=false            # use a system libwebp instead
```

## Publishing a release

`.github/workflows/webp-natives.yml` builds all six and publishes them under the tag in
`native.properties`. Run it with:

```
gh workflow run webp-natives.yml
```

The release is immutable: the workflow refuses to publish a tag that already exists, because a
client caches what it downloaded for a tag and would have no way to learn that a file changed. A
rebuild of the same libwebp version is published under the same tag plus a dash suffix
(`webp-natives-1.6.0-2`), and the suffix is written into `native.properties` in the same commit,
so the change ships with the next imagify release.

## Building them

The build is driven by `src/main/native/webp/CMakeLists.txt`, which is an overlay on libwebp's own
build rather than a fork of it:

```
git clone --branch v1.6.0 --depth 1 https://github.com/webmproject/libwebp
cmake -S src/main/native/webp -B build \
      -DCMAKE_BUILD_TYPE=Release \
      -DIMAGIFY_WEBP_SOURCE_DIR=$PWD/libwebp
cmake --build build --target imagify_webp
```

The workflow replaces the version on the command line above and checks it against the CMakeLists
before building, so the library reports the version it was actually built from.