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

The build is driven by `src/main/native/jpegli/CMakeLists.txt`, which is an overlay on jpegli's own build
rather than a fork of it:

```
git clone --recurse-submodules https://github.com/google/jpegli
git -C jpegli checkout 031a0077f5799a6041004267fc12b956c1f52a20
cmake -S src/main/native/jpegli -B build \
      -DCMAKE_BUILD_TYPE=Release \
      -DIMAGIFY_JPEGLI_SOURCE_DIR=$PWD/jpegli \
      -DIMAGIFY_JPEGLI_VERSION=0.12.0
cmake --build build --target imagify_jpegli
```

`--recurse-submodules` is not optional. `highway` is a hard dependency the jpegli configure refuses
to continue without, and Little-CMS is one too even though nothing here links it: jpegli always
includes `lib/jpegli_cms.cmake`, and `third_party/CMakeLists.txt` aborts on finding neither Little-CMS
nor skcms. The other submodules are only for tools and tests, all of which are configured off.

On Windows, build with MSVC from a developer command prompt:

```
-DCMAKE_C_COMPILER=cl -DCMAKE_CXX_COMPILER=cl
```

Without one, CMake silently selects the MinGW GCC that ships in the runner image, and a MinGW DLL
imports `libwinpthread-1.dll`. That file is not part of Windows, so a library depending on it would
not load on a machine where the user has installed nothing. The static C runtime needs no flag — the
overlay sets it, and it has to be set before the first target is created or CMake ignores it.

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

What that gate guards is more than the `add_library`. The object library that compiles
`lib/jpegli/libjpeg_wrapper.cc` is inside it too, along with the `target_link_libraries` and the
`VERSION`/`SOVERSION` properties, right down to the version script that goes with the result. None of
that is about whether the code compiles, so the overlay re-declares it for all six targets and
expresses the same packaging in whatever the local linker does understand:

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
mapped — `jpeg_read_header` is the name a system libjpeg would also claim. Two mechanisms take them
out of the dynamic symbol table, and neither is a hidden visibility preset: a version script's
`global:` cannot promote a symbol the compiler has already marked hidden, so `-fvisibility=hidden`
would have taken the six entry points down with everything else.

- `--exclude-libs,ALL` on ELF covers the static archives, which is highway and `jpegli-static`, and is
  the same flag jpegli passes for the same reason.
- The `local: *` in the version script covers everything else, which is the wrapper's own object files:
  they are compiled straight into the shared library rather than out of an archive, so
  `--exclude-libs` never sees them.

### Only the C runtime and the OS

`BUILD_SHARED_LIBS` is off, and jpegli additionally pins highway to a static archive with an
`INTERNAL` cache entry of its own, so the result has no highway `.so` to find. The check that runs
after every build rejects one next to the result anyway. On Windows this matters most: `LoadLibrary`
resolves the dependencies of the module it loads against the directory of the *executable* and against
`PATH`, never against the directory of the module itself, so a `jpegli.dll` that linked `highway.dll`
next to it would fail to load from a temporary directory.

## Licensing

`jpegli` and `highway` are both Apache-2.0, and Little-CMS — which jpegli's configure requires even
though nothing here links it — is MIT, so all three redistribute. Keep the upstream `LICENSE` files
next to the binaries and reproduce them in the distribution notices.

## Pinning the version

jpegli publishes neither tags nor releases, so there is no version to pin and a commit is the only
thing that can be. `JPEGLI_COMMIT` and `JPEGLI_VERSION` sit together at the top of
`.github/workflows/jpegli-natives.yml` and are only ever meaningful as a pair: the commit is what gets
checked out, and the version is what `JpegliCodec.getVersion()` reports, which is the thing a bug
report will quote.

That said, the bundled library is whatever the workflow last built, so its version drifts over time.
What this jar actually binds is not jpegli but the flat C ABI declared in
`src/main/native/jpegli/imagify_jpegli.h`, and that is what `JpegliLibrary.ABI_VERSION` gates on: a library
built against a different revision of the header answers the version call with something else and is
refused before the first call, rather than being discovered later as garbage pixels. Raising
`ABI_VERSION` is a deliberate act that means the header changed in a way the binding does not know
about.
