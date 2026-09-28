# Bundled `jpegli` shared libraries

This directory holds the prebuilt `jpegli` shared libraries that ship inside the jar, one per
supported platform. `JpegliNativeLibrary` picks the right one at runtime, unpacks it to a temporary
directory and loads it by absolute path, so users never have to install anything.

## Expected files

| File | Platform | Build |
| --- | --- | --- |
| `libjpegli-windows-x64.dll` | Windows x64 | MSVC, `/MT`, x64 |
| `libjpegli-windows-arm64.dll` | Windows arm64 | MSVC, `/MT`, arm64 |
| `libjpegli-linux-x64.so` | Linux x64 | glibc, x86_64 |
| `libjpegli-linux-arm64.so` | Linux arm64 | glibc, aarch64 |
| `libjpegli-macos-x64.dylib` | macOS x64 | AppleClang, x86_64 |
| `libjpegli-macos-arm64.dylib` | macOS arm64 | AppleClang, arm64 |

The names are hardcoded in `JpegliNativeLibrary.resourceName`; renaming a file here silently
disables the jpegli JPEG codec on that platform, which is not an error anywhere — the JDK's own JPEG
support takes over and keeps working.

A `git add` of a `.dll` here is silently dropped, because a widely used global gitignore excludes
`*.dll`. Add these with `git add -f`, which is how the `libavif` ones above are tracked too.

## Building them

`.github/workflows/jpegli-natives.yml` builds all six and attaches them to a rolling GitHub release.
Run it with:

```
gh workflow run jpegli-natives.yml
```

The build is driven by `src/main/native/CMakeLists.txt`, which is an overlay on jpegli's own build
rather than a fork of it:

```
git clone --recurse-submodules --branch v0.5.0 https://github.com/google/jpegli
cmake -S src/main/native -B build \
      -DCMAKE_BUILD_TYPE=Release \
      -DIMAGIFY_JPEGLI_SOURCE_DIR=$PWD/jpegli
cmake --build build --target imagify_jpegli
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
with Windows, so again a user who has installed nothing would be missing them.

`check-self-contained.py` enforces the result: `VCRUNTIME140.dll` and `MSVCP140.dll` are not on its
allow list, so a build that picks them up fails.

### Why the CMake overlay exists

jpegli ships a `libjpeg62` compatible shared library, but only declares the target where the linker
understands a version script:

```cmake
if (JPEGLI_ENABLE_JPEGLI_LIBJPEG AND NOT APPLE AND NOT WIN32 AND NOT EMSCRIPTEN)
```

What that gate guards is three commands: `add_library`, `target_link_libraries` and
`set_target_properties` with a `VERSION`/`SOVERSION`. None of them has anything to do with whether
the code compiles, so the overlay re-declares the target for all six targets and expresses the same
packaging in whatever the local linker does understand:

| platform | mechanism |
| --- | --- |
| Windows | a generated `.def` file passed as `/DEF`, which is what makes the DLL itself export the list |
| macOS | `-Wl,-exported_symbols_list` with a one line `_imagify_jpegli_*` glob |
| Linux | a `--version-script` naming the six entry points, plus `--exclude-libs,ALL` |

Patching jpegli itself would mean carrying a fork of a third party build system and rebasing it on
every bump. The overlay is a file this project owns that stops at an upstream boundary.

### Why the library is not called `jpeg`

jpegli's own target has the soname `libjpeg.so.62`, which is also the soname of every system libjpeg
on Linux. Two libraries with the same soname can end up mapped in one process and whichever was mapped
first wins the symbols. This jar has no reason to interoperate with a system libjpeg, so the shim is
called `jpegli` and nothing in it can collide.

### Only the six entry points are exported

jpegli and highway are linked in statically and would otherwise contribute several thousand symbols,
any of which could be interposed on by, or interpose on, a library the host application has already
mapped — `jpeg_read_header` is the name a system libjpeg would also claim. The visibility preset in
the CMakeLists keeps them out of the dynamic symbol table to begin with, and the version script or
`.def` names the handful that are meant to be visible.

### Only the C runtime and the OS

`BUILD_SHARED_LIBS` is off, so highway is built and linked in as a static archive, and the check that
runs after every build rejects a `.so` next to the result. On Windows this matters most: `LoadLibrary`
resolves the dependencies of the module it loads against the directory of the *executable* and
against `PATH`, never against the directory of the module itself, so a `jpegli.dll` that linked
`highway.dll` next to it would fail to load from a temporary directory.

## Licensing

`jpegli` and `highway` are both Apache-2.0, so redistribution is permitted. Keep the upstream
`LICENSE` files next to the binaries and reproduce them in the distribution notices.

## Pinning the version

The bundled library is whatever the workflow last built, so its version drifts over time. What this
jar actually binds is not jpegli but the flat C ABI declared in `src/main/native/imagify_jpegli.h`,
and that is what `JpegliLibrary.ABI_VERSION` gates on: a library built against a different revision
of the header answers the version call with something else and is refused before the first call,
rather than being discovered later as garbage pixels. Raising `ABI_VERSION` is a deliberate act that
means the header changed in a way the binding does not know about.
