# Bundled `libavif` shared libraries

This directory holds the prebuilt `libavif` shared libraries that ship inside the jar, one per
supported platform, and the small shim that binds them. `AvifLoader` picks the right pair at runtime,
unpacks them into one temporary directory and loads them by absolute path, so users never have to
install anything.

There are two files per platform because AVIF is bound through a C shim rather than directly. See
"Why a shim" below; the short version is that the FFM ABI will not let a Java array be stored into
`avifRGBImage.pixels`, so an entry point that takes the pixels as its own argument is what makes an
encode possible without copying them.

## Expected files

| File | Platform | Build |
| --- | --- | --- |
| `libavif-windows-x64.dll` | Windows x64 | MSVC, `/MT`, x64 |
| `libavif-windows-arm64.dll` | Windows arm64 | MSVC, `/MT`, arm64 |
| `libavif-linux-x64.so` | Linux x64 | glibc, x86_64 |
| `libavif-linux-arm64.so` | Linux arm64 | glibc, aarch64 |
| `libavif-macos-x64.dylib` | macOS x64 | AppleClang, x86_64 |
| `libavif-macos-arm64.dylib` | macOS arm64 | AppleClang, arm64 |
| `imagifyavif-windows-x64.dll` | Windows x64 | MSVC, `/MT`, x64 |
| `imagifyavif-windows-arm64.dll` | Windows arm64 | MSVC, `/MT`, arm64 |
| `imagifyavif-linux-x64.so` | Linux x64 | glibc, x86_64 |
| `imagifyavif-linux-arm64.so` | Linux arm64 | glibc, aarch64 |
| `imagifyavif-macos-x64.dylib` | macOS x64 | AppleClang, x86_64 |
| `imagifyavif-macos-arm64.dylib` | macOS arm64 | AppleClang, arm64 |

The names are derived in `AvifLoader.resourceName` and `AvifLoader.shimName`, and pinned by
`AvifLoaderTest`. Renaming a file here silently disables AVIF support on that platform, which is why
those tests exist rather than a comment.

The shim's name has to match the library's for the platform and the architecture, because the shim
imports libavif by its file name. `AvifLoader.shimName` derives one from the other so the two cannot
drift, and the shim is unpacked into the same directory for the same reason.

## Building them

`.github/workflows/avif-natives.yml` builds all six libavif binaries and all six shims and attaches
them to a rolling GitHub release. Run it with:

```
gh workflow run avif-natives.yml
```

### libavif

The libavif build is a plain configure with the CLI tools turned off and `aom` linked in statically:

```
cmake -S libavif -B build \
      -DCMAKE_BUILD_TYPE=Release \
      -DBUILD_SHARED_LIBS=ON \
      -DAVIF_BUILD_APPS=OFF \
      -DAVIF_BUILD_TESTS=OFF \
      -DAVIF_LIBYUV=LOCAL \
      -DAVIF_LIBSHARPYUV=LOCAL \
      -DAVIF_CODEC_AOM=LOCAL
cmake --build build --config Release --target avif
```

On Windows, build with MSVC and the static C runtime:

```
-DCMAKE_C_COMPILER=cl -DCMAKE_CXX_COMPILER=cl
-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded
```

A developer command prompt has to be active first, otherwise CMake silently selects the MinGW GCC
that ships in the runner image, and a MinGW DLL imports `libwinpthread-1.dll`. That file is not part
of Windows, so a library depending on it would not load on a machine where the user has installed
nothing.

`MultiThreaded` is `/MT` rather than `/MD`. `/MD` would leave the library importing
`VCRUNTIME140.dll` and `MSVCP140.dll`, which arrive with the Visual C++ redistributable rather than
with Windows, so again a user who has installed nothing would be missing them. The shim is built
the same way and for the same reason: it is loaded by absolute path out of a temporary directory and
can be counted on to find nothing next to itself.

`check-self-contained.py` enforces the result: `VCRUNTIME140.dll` and `MSVCP140.dll` are not on its
allow list, so a build that picks them up fails. The imagify shims are in the same position,
importing nothing beyond `KERNEL32.dll` and the C runtime that Windows provides.

### Naming the target CPU

Pass `AOM_TARGET_CPU` explicitly (`x86_64` or `arm64`) rather than letting libaom detect it. libaom
derives the target from `CMAKE_SYSTEM_PROCESSOR`, which on Windows reflects the environment rather
than the compiler. Build steps run under Git Bash, an x64 process even on the ARM64 image, so CMake
reports `AMD64`, libaom announces `Detected CPU: x86_64`, and the build then either demands NASM
that arm64 does not need or fails compiling `aom_dsp/x86` with `immintrin.h`. Naming the target
also keeps the output from depending on which shell the build happened to run under.

MSVC cannot read NASM syntax, so the x64 targets still need NASM installed, while the arm64 targets
must not have it on the `PATH`: libaom treats any assembler it finds as permission to build its
x86 sources.

`=LOCAL` makes libavif fetch and build each dependency in-tree instead of linking against whatever
the build machine happens to ship. The result is a **self-contained** library with no further
shared dependencies, which is what lets a single file per platform be shipped.

`AVIF_LIBYUV` is not optional in the same way: it defaults to `SYSTEM`, and libavif aborts the
configure outright when `pkg-config` cannot find it. `AVIF_LIBSHARPYUV` is optional but supplies
the fast RGB to YUV conversion the encoder path uses.

### The shim

```
cmake -S src/main/native/avif -B build \
      -DCMAKE_BUILD_TYPE=Release \
      -DIMAGIFY_AVIF_SOURCE_DIR=<where libavif was built>
cmake --build build --config Release
```

The shim is *not* built from a libavif source tree. It is built against a libavif that already
exists, which is the opposite of the libwebp build next door, where libwebp is compiled from source
and linked in statically. That is what keeps it 140 KB rather than a second copy of a 10 MB library.

On Windows the shim needs an import library, because MSVC's linker will not take a DLL as an input.
`CMakeLists.txt` generates one from a module definition file naming the nineteen `avif*` entry
points the shim calls, which is short enough to read and check against the header. The module name
in that file has to be the bundled libavif's own file name, since that is the name the loader looks
for beside the shim.

## Why a self-contained library is required

On Windows, `LoadLibrary` resolves the dependencies of the module it loads against the directory of
the *executable* and against `PATH`, never against the directory of the module itself. A
`libavif.dll` that links `aom.dll` next to it would therefore fail to load from a temporary
directory. A distribution build of `libavif` such as MSYS2's, which is only 326 KB precisely because
it links aom, dav1d, rav1e, SvtAv1Enc, libyuv, libjpeg, libpng, libxml2 and zlib dynamically, cannot
be redistributed this way.

This is also why the shim and libavif are unpacked into **one** directory. The shim's import table
names libavif, and a module's imports are resolved when the module is loaded rather than at first
use, so both files have to be sitting next to each other under the names they have here.

## Why a shim

The FFM ABI will not store a heap segment, which is what a Java array is, into a pointer-typed struct
field. `avifRGBImage.pixels` is exactly such a field, and it is the buffer every conversion in and
out of a picture goes through. An encode that filled it in from Java would have to copy the pixels
into native memory first, which is a copy of a whole image.

A function *parameter* is a different matter. A downcall made with `Linker.Option.critical(true)`
pins the array for the length of the call rather than copying it, and the callee is on the stack for
exactly that long, so it cannot store the pointer anywhere that outlives the pin. One entry point
that takes the pixels as its own argument is therefore the whole of the zero copy.

Without the shim, this is unreachable from Java. With it, an encode reads the caller's pixels where
they are and a decode copies them out of libavif's own buffer once rather than twice.

`Linker.Option.critical(true)` is what makes this work and what a binding must remember to pass:
without it, FFM refuses a heap segment outright with `Heap segment not allowed`, and every encode
fails before libavif is reached. `AvifShimTest` pins that.

## Licensing

`libavif` is BSD-2-Clause and `aom`, `dav1d`, `rav1e` and `SVT-AV1` are all BSD-2-Clause as well, so
redistribution is permitted. Keep the upstream `LICENSE` files next to the binaries and reproduce
them in the distribution notices.

## Pinning the version

The bundled library is whatever the workflow last built, so its version drifts over time. The shim
calls nothing newer than libavif 1.0.0, which is what lets one shim run against every libavif from
1.0.0 through 1.4.x, and `AvifCodec.supportedVersions` reports that range.

There is one thing to re-check when libavif is upgraded, and it is not a struct layout: the shim is
compiled against whatever `avif.h` was on the machine that built it, so a release that changed a
*signature* would need the shim rebuilt even though nothing about the layout moved. A release that
only appended a field to a struct is invisible to the shim, which is the point of having one.
