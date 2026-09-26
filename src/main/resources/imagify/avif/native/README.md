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
      -DAVIF_LIBYUV=LOCAL \
      -DAVIF_LIBSHARPYUV=LOCAL \
      -DAVIF_CODEC_AOM=LOCAL
cmake --build build --config Release --target avif
```

On Windows add the linker flags that keep the MinGW runtime out of the import table:

```
"-DCMAKE_SHARED_LINKER_FLAGS=-static-libgcc -static-libstdc++ -Wl,-Bstatic -lwinpthread -Wl,-Bdynamic"
```

The CI runner images have no MSVC environment for Ninja, so CMake picks the MinGW GCC that ships
in the image, and a MinGW DLL imports `libwinpthread-1.dll`. That file is not part of Windows, so a
library depending on it would not load on a machine where the user has installed nothing. Those
flags leave only `KERNEL32.dll` and the `api-ms-win-crt-*` forwarders, which Windows itself
provides. `check-self-contained.py` enforces exactly this.

`=LOCAL` makes libavif fetch and build each dependency in-tree instead of linking against whatever
the build machine happens to ship. The result is a **self-contained** library with no further
shared dependencies, which is what lets a single file per platform be shipped.

`AVIF_LIBYUV` is not optional in the same way: it defaults to `SYSTEM`, and libavif aborts the
configure outright when `pkg-config` cannot find it. `AVIF_LIBSHARPYUV` is optional but supplies
the fast RGB to YUV conversion the encoder path uses.

### Why only libaom

`libaom` encodes and decodes, and it is the codec libavif picks by default, so one is enough. The
other three are left off deliberately, because each drags in a build tool that the CI runner
images do not reliably provide:

| codec | needs | how it failed |
| --- | --- | --- |
| `AVIF_CODEC_DAV1D` | Meson | built through Meson rather than CMake; on Windows runners Chocolatey installs Meson as an MSI, so the new `PATH` never reaches the configure step |
| `AVIF_CODEC_RAV1E` | a Rust toolchain | the macOS arm64 image reinstalls `rust-std` while cargo is still running, and the link then fails on missing `.rlib` files |
| `AVIF_CODEC_SVT` | NASM | hard failure at `enable_language(ASM_NASM)` on x86 when NASM is absent |

Turning one back on means installing its tool on all three platforms first. `AVIF_CODEC_AOM` plus
NASM is the whole set of requirements: CMake, Ninja and a C compiler.

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
