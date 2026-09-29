# Bundled `imagifyavif` shared libraries

This directory holds one prebuilt shared library per supported platform. It is the shim that
`imagify.avif.ffm` loads, and it has `libavif` linked into it, so one file per platform is all there
is. `AvifLoader` picks the right one at runtime, unpacks it to a temporary directory and loads it by
absolute path, so users never have to install anything.

The arrangement is the same one the WebP shim uses, and getting here took three attempts worth
recording, because the two-file version was tried first and does not work.

## Expected files

| File | Platform | Build |
| --- | --- | --- |
| `imagifyavif-windows-x64.dll` | Windows x64 | MSVC, `/MT`, x64 |
| `imagifyavif-windows-arm64.dll` | Windows arm64 | MSVC, `/MT`, arm64 |
| `imagifyavif-linux-x64.so` | Linux x64 | glibc, x86_64 |
| `imagifyavif-linux-arm64.so` | Linux arm64 | glibc, aarch64 |
| `imagifyavif-macos-x64.dylib` | macOS x64 | AppleClang, x86_64 |
| `imagifyavif-macos-arm64.dylib` | macOS arm64 | AppleClang, arm64 |

The names are derived in `AvifLoader.shimName` and pinned by `AvifLoaderTest`. Renaming a file here
silently disables AVIF support on that platform, which is why those tests exist rather than a
comment.

## Building them

`.github/workflows/avif-natives.yml` builds all six and attaches them to a rolling GitHub release.
Run it with:

```
gh workflow run avif-natives.yml
```

Then drop the six files into this directory and commit them. There is nothing to put beside them.

### libavif

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
```

`BUILD_SHARED_LIBS=OFF` is what makes this work and it is not a detail. With it, libavif builds an
`avif_static` target that merges every `LOCAL` dependency into one archive, so the shim links against
libavif and finds libaom, libyuv and sharpyuv inside it. That is what `=LOCAL` means for a static
consumer, and it is why one file per platform ships rather than one plus six side dependencies.

`AVIF_LIBYUV` is not optional in the same way: it defaults to `SYSTEM`, and libavif aborts the
configure outright when `pkg-config` cannot find it. `AVIF_LIBSHARPYUV` is optional but supplies the
fast RGB to YUV conversion the encoder path uses.

Only libaom is enabled, because it encodes and decodes and is what libavif picks by default. The
other codecs are off because each drags in a build tool the CI runner images do not reliably
provide:

| codec | needs | how it failed |
| --- | --- | --- |
| `AVIF_CODEC_DAV1D` | Meson | built through Meson rather than CMake; on Windows runners Chocolatey installs Meson as an MSI, so the new `PATH` never reaches the configure step |
| `AVIF_CODEC_RAV1E` | a Rust toolchain | the macOS arm64 image reinstalls `rust-std` while cargo is still running, and the link then fails on missing `.rlib` files |
| `AVIF_CODEC_SVT` | NASM | hard failure at `enable_language(ASM_NASM)` on x86 when NASM is absent |

Turning one back on means installing its tool on all three platforms first. `AVIF_CODEC_AOM` plus
NASM is the whole set of requirements: CMake, Ninja and a C compiler.

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

### The shim

```
cmake -S src/main/native/avif -B shim-build \
      -DCMAKE_BUILD_TYPE=Release \
      -DIMAGIFY_AVIF_SOURCE_DIR=../libavif/build \
      -DIMAGIFY_AVIF_HEADER_DIR=../libavif/include
cmake --build shim-build
```

## Why one file rather than a libavif beside the shim

The first version of this linked a prebuilt shared libavif and shipped it in the jar next to the
shim, which imports the `avif*` symbols from it. That does not work on the two Unix platforms, and
three fixes were tried before the question was removed.

A shim that imports libavif records the **SONAME** of the library it linked against, and the two
Unix loaders resolve a dependency by that name rather than against the directory the loading module
sits in. libavif sets its own `SOVERSION` unconditionally, with no cache variable to override it:

```cmake
set_target_properties(avif PROPERTIES VERSION ${LIBRARY_VERSION} SOVERSION ${LIBRARY_SOVERSION})
```

so the name in the shim is `libavif.so.16` or `libavif.16.dylib`. The jar cannot ship either,
because the files are named for the platform and the architecture so that six of them fit in one
directory. A shim with a stock name loads on the machine that built it and on no other.

| attempt | what happened |
| --- | --- |
| `CMAKE_SHARED_LIBRARY_SONAME_C_FLAG` | a flag CMake hands to nothing, because `avif.h` is a C header and the library is C++ |
| its `CXX` twin as well | additive, because libavif sets `SOVERSION` itself, and the linker concatenated the two into `libavif-linux-x64.solavif.so.16` |
| `patchelf --set-soname` on libavif | renames the file, then leaves `DT_STRTAB` outside every `PT_LOAD`, so `check-self-contained.py` can no longer read the library it was checking |

Linking libavif in has no name to get right on any platform, and the self-contained check then
applies to the shim exactly as it does to the WebP one. The cost is that libavif cannot be swapped
without rebuilding the shim, and the jar is about the same size either way, since the bytes move
from one file into the other.

## Why a shim at all, if libavif is linked in anyway

For the same reason the WebP shim exists, and it is one line of the FFM ABI.

FFM will not store a heap segment, which is what a Java array becomes, into a pointer-typed struct
field. `avifRGBImage.pixels` is exactly such a field, and it is the buffer every conversion in and
out of a picture goes through, so an encode that filled it in from Java would copy a whole image
first. A function *parameter* is different: a downcall made with `Linker.Option.critical(true)` pins
the array for the length of the call rather than copying it, and the callee is on the stack for
exactly that long. One entry point that takes the pixels as its own argument is therefore the whole
of the zero copy.

The second reason is the thirteen libavif structures. A direct FFM binding needs a hand written
`MemoryLayout` for each, and a struct whose fields are in the wrong order does not fail, it returns
a wrong picture. In C the compiler checks every field against the header it was compiled with.

`Linker.Option.critical(true)` is what makes the first of these work and what a binding must remember
to pass: without it, FFM refuses a heap segment outright with `Heap segment not allowed`, and every
encode fails before libavif is reached. `AvifShimTest` pins that.

## Self containment

`check-self-contained.py` fails the build when a bundled library still depends on something a user's
machine would not have. The shim links libavif and libaom in, so what it brings with it is the C and
C++ runtimes, and those are on the allow list: `libstdc++` on Linux, `libc++` inside `libSystem` on
macOS, and on Windows nothing at all under `/MT`.

`/MT` rather than `/MD` is deliberate. `/MD` would leave the library importing `VCRUNTIME140.dll`
and `MSVCP140.dll`, which arrive with the Visual C++ redistributable rather than with Windows, so a
user who has installed nothing would be missing them. The bundled library is loaded by absolute path
out of a temporary directory and can be counted on to find nothing next to itself.

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
