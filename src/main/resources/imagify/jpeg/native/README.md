# `jpegli` release metadata

This directory no longer holds shared libraries. They are built by the `jpegli-natives` workflow
and published as a GitHub release, and the only thing this directory carries is the one thing the
jar needs to know about that release: the tag, in `native.properties`. `JpegliNativeLibrary` hands
the tag to `NativeRepository`, which downloads the file for the platform the codec runs on the first
time one is needed and loads it by absolute path, so users never have to install anything.

## The release the other side of this directory names

| File | Platform | Build |
| --- | --- | --- |
| `libjpegli-windows-x64.dll` | Windows x64 | MSVC, `/MT`, x64 |
| `libjpegli-windows-arm64.dll` | Windows arm64 | MSVC, `/MT`, arm64 |
| `libjpegli-linux-x64.so` | Linux x64 | glibc, x86_64 |
| `libjpegli-linux-arm64.so` | Linux arm64 | glibc, aarch64 |
| `libjpegli-macos-x64.dylib` | macOS x64 | AppleClang, x86_64 |
| `libjpegli-macos-arm64.dylib` | macOS arm64 | AppleClang, arm64 |

The names are built by `NativeRepository.assetName` and pinned by tests. A release that lacks one of
them silently disables the jpegli JPEG codec on that platform, which is not an error anywhere — the
JDK's own JPEG support takes over and keeps working.

What the runtime downloads sits in a local cache, keyed by the release tag and the file name, so a
library is fetched once and reused until the cache is deleted. Where and whether anything is fetched
is configurable:

```
-Dimagify.native.cache=/path/to/cache   # where downloads are cached
-Dimagify.native.download=false         # never download; use a system library
-Dimagify.native.url=https://mirror/... # where to fetch from
-Dimagify.jpeg.library=/path/to.so      # one explicit library, no download
-Dimagify.jpeg.bundled=false            # use a system jpegli instead
```

## Publishing a release

`.github/workflows/jpegli-natives.yml` builds all six and publishes them under the tag in
`native.properties`. Run it with:

```
gh workflow run jpegli-natives.yml
```

The release is immutable: the workflow refuses to publish a tag that already exists, because a
client caches what it downloaded for a tag and would have no way to learn that a file changed. A
rebuild of the same jpegli version is published under the same tag plus a dash suffix
(`jpegli-natives-0.12.0-2`), and the suffix is written into `native.properties` in the same commit,
so the change ships with the next imagify release.

## Building them

The build is driven by `native/jpegli/CMakeLists.txt`, which is an overlay on jpegli's own
build rather than a fork of it. jpegli publishes neither tags nor releases, so it is pinned to a
commit:

```
git clone --recurse-submodules https://github.com/google/jpegli
git -C jpegli checkout 031a0077f5799a6041004267fc12b956c1f52a20
cmake -S native/jpegli -B build \
      -DCMAKE_BUILD_TYPE=Release \
      -DIMAGIFY_JPEGLI_SOURCE_DIR=$PWD/jpegli
cmake --build build --target imagify_jpegli
```

The workflow reproduces this for all six platforms, with the commit and the version it reports
pinned at the top of the workflow.

## Licenses

The shim links `jpegli` and, behind it, `hwy` (highway), and the jar carries each library's license
beside this README:

| File | Library | License | Upstream |
| --- | --- | --- | --- |
| `LICENSE-jpegli.txt` | jpegli @ `031a0077` | BSD-3-Clause | `LICENSE` in google/jpegli |
| `LICENSE-highway.txt` | highway | Apache-2.0 / BSD-3-Clause (dual) | `LICENSE` in google/highway |

The shim links only `jpegli-static`, built from the `lib/jpegli/*` sources, plus `hwy` behind it.
jpegli's build tree insists on a colour management library at configure time, which is why the
checkout brings the Little-CMS submodule in, but the target here never links it, so it is not part
of the binary and needs no license text. The texts above ship in the jar and are expected to stay
there: `NativeRepositoryTest` pins them.