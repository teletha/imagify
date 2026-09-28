# Bundled `libwebp` shared libraries

This directory holds the prebuilt `libwebp` shared libraries that ship inside the jar, one per
supported platform. `WebpNativeLibrary` picks the right one at runtime, unpacks it to a temporary
directory and loads it by absolute path, so users never have to install anything.

## Expected files

| File | Platform | Build |
| --- | --- | --- |
| `imagifywebp-windows-x64.dll` | Windows x64 | MSVC, `/MT`, x64 |
| `imagifywebp-windows-arm64.dll` | Windows arm64 | MSVC, `/MT`, arm64 |
| `imagifywebp-linux-x64.so` | Linux x64 | glibc, x86_64 |
| `imagifywebp-linux-arm64.so` | Linux arm64 | glibc, aarch64 |
| `imagifywebp-macos-x64.dylib` | macOS x64 | AppleClang, x86_64 |
| `imagifywebp-macos-arm64.dylib` | macOS arm64 | AppleClang, arm64 |

The names are hardcoded in `WebpNativeLibrary.resourceName`; renaming a file here silently
disables the WebP codec on that platform, which is not an error anywhere: the JDK's own WebP
support takes over and keeps working.

A `git add` of a `.dll` here is silently dropped, because a widely used global gitignore excludes
`*.dll`. Add these with `git add -f`, which is how the `libavif` ones above are tracked too.

## Building them

`.github/workflows/webp-natives.yml` builds all six and attaches them to a rolling GitHub release.
Run it with:

```
gh workflow run webp-natives.yml
```

The build is driven by `src/main/native/webp/CMakeLists.txt`, which is an overlay on libwebp's own
build rather than a fork of it:

```
git clone --branch v1.6.0 --depth 1 https://github.com/webmproject/libwebp
cmake -S src/main/native/webp -B build \
      -DCMAKE_BUILD_TYPE=Release \
      -DIMAGIFY_WEBP_SOURCE_DIR=$PWD/libwebp \
      -DIMAGIFY_WEBP_VERSION=1.6.0
cmake --build build --target imagify_webp
```

libwebp has no submodules the codec needs: everything it pulls in serves its own tools and tests,
which are configured off. The `WEBP_BUILD_*` switches leave only the codec, the demuxer and the
muxer libraries, and those three are linked statically into the one shared object.

On Windows, build with MSVC from a developer command prompt:

```
-DCMAKE_C_COMPILER=cl -DCMAKE_CXX_COMPILER=cl
```

Without one, CMake silently selects the MinGW GCC that ships in the runner image, and a MinGW DLL
imports `libwinpthread-1.dll`. That file is not part of Windows, so a library depending on it would
not load on a machine where the user has installed nothing.

`MultiThreaded` is `/MT` rather than `/MD`. `/MD` would leave the library importing
`VCRUNTIME140.dll` and `MSVCP140.dll`, which arrive with the Visual C++ redistributable rather than
with Windows, so again a user who has installed nothing would be missing them.

`check-self-contained.py` enforces the result: `VCRUNTIME140.dll` and `MSVCP140.dll` are not on its
allow list, so a build that picks them up fails.

### Why the C shim exists at all

Encoding a picture to WebP means filling in `struct WebPPicture`, which is 256 bytes on a 64 bit
platform and mostly not data: it is the padding libwebp reserves for itself, a union of three sample
pointers, a statistics block it may write through, and four function pointers. A JNA binding would
have to name every one of those fields and get every offset right, and a mistake in any of them is
not a compile error but a wild pointer write while a picture is being encoded. The animation API
cannot be driven without one either, since it takes a `WebPPicture` per frame: an animated file is
built by handing the encoder one picture after another.

The shim in `src/main/native/webp` keeps that layout on the C side. What crosses this boundary is
plain scalars, pointers and buffers: a byte array of tightly packed `A, B, G, R` pixels in and a
byte array of the same out.

### Why the library is not called `webp`

libwebp's own shared library has the name `libwebp.so.7`, which is also the name of every system
libwebp on Linux. Two copies of libwebp can end up mapped in one process and whichever was mapped
first wins the symbols, which is a real hazard when the host application has already loaded a system
one. This jar has no reason to interoperate with a system libwebp, so the shim is called
`imagifywebp` and nothing in it can collide.

### Only the eight entry points are exported

libwebp's own library exports several hundred symbols, any of which could be interposed on by, or
interpose on, a library the host application has already mapped. Two mechanisms take them out of the
dynamic symbol table, and neither is a hidden visibility preset: a version script's `global:` cannot
promote a symbol the compiler has already marked hidden, so `-fvisibility=hidden` would have taken
the entry points down with everything else.

- `--exclude-libs,ALL` on ELF covers the static archives, which are `webp`, `webpdemux` and
  `libwebpmux`.
- The `local: *` in the version script covers everything else, which is the shim's own object files:
  they are compiled straight into the shared library rather than out of an archive, so
  `--exclude-libs` never sees them.

macOS gets the same from `-Wl,-exported_symbols_list` with a one line `_imagify_webp_*` glob, and
Windows from a generated `.def` file passed as `/DEF`, which is what makes the DLL itself export the
list.

### Only the C runtime and the OS

`BUILD_SHARED_LIBS` is off, so the result has no `libwebp.so` to find. The check that runs after
every build rejects one next to the result anyway. On Windows this matters most: `LoadLibrary`
resolves the dependencies of the module it loads against the directory of the *executable* and
against `PATH`, never against the directory of the module itself, so a DLL that linked the demo
tool's `libwebp` shared library next to it would fail to load from a temporary directory.

One dependency the other codecs do not have is the threading library. `WEBP_USE_THREAD` is on,
which is what the `webp4j` this replaces used too (it always set the encoder to multithreaded), and
libwebp's own libraries do not link `pthread` themselves, so the overlay links `Threads::Threads`
explicitly. On Linux that is `libpthread.so.0`, which is on the allow list of
`check-self-contained.py` together with `libc` and `libm`.

## Licensing

`libwebp` is BSD-3-Clause, so redistribution is permitted. Keep the upstream `LICENSE` files next
to the binaries and reproduce them in the distribution notices.

## Pinning the version

`WEBP_TAG` and `WEBP_VERSION` sit together at the top of `.github/workflows/webp-natives.yml` and
are only ever meaningful as a pair: the tag is what gets checked out, and the version is what
`WebpCodec.getVersion()` reports, which is the thing a bug report will quote. The version is passed
into the build rather than derived, because libwebp's own CMake settles on its library version
(1.0.6 at tag v1.6.0) rather than the release version.

That said, the bundled library is whatever the workflow last built, so its version drifts over time.
What this jar actually binds is not libwebp but the flat C ABI declared in
`src/main/native/webp/imagify_webp.h`, and that is what `WebpLibrary.ABI_VERSION` gates on: a library
built against a different revision of the header answers the version call with something else and is
refused before the first call, rather than being discovered later as garbage pixels. Raising
`ABI_VERSION` is a deliberate act that means the header changed in a way the binding does not know
about.