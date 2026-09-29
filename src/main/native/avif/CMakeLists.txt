# Copyright (C) 2026 The IMAGIFY Development Team
#
# Licensed under the MIT License (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#          http://opensource.org/licenses/mit-license.php

# Builds the shared library that imagify.avif.ffm loads, for all six targets this jar supports.
#
# This is the same shape as the libwebp shim next door: libavif is compiled from source here as a
# static library, linked into the shim, and one file per platform ships in the jar. That is a
# deliberate change from what this file first did, which was to link a prebuilt libavif and ship it
# beside the shim, and the reason is worth recording because it cost three CI runs to find.
#
# A shim that imports libavif records the SONAME of the library it linked against, and the two Unix
# loaders resolve a dependency by that name rather than against the directory the loading module
# sits in. libavif sets its own SOVERSION unconditionally, with no cache variable to override it, so
# the name in the shim is libavif.so.16 or libavif.16.dylib. The jar cannot ship either, because the
# files are named for the platform and the architecture so that six of them fit in one directory.
#
# Three ways of fixing that were tried and all three fail:
#
#   CMAKE_SHARED_LIBRARY_SONAME_C_FLAG   a flag CMake hands to nothing, because avif.h is a C
#                                       header and the library behind it is C++.
#   its CXX twin as well                 additive, because libavif sets SOVERSION itself, and the
#                                       linker concatenates the two into a name nothing resolves.
#   patchelf --set-soname                renames the file and then leaves DT_STRTAB outside every
#                                       PT_LOAD, so check-self-contained.py can no longer read
#                                       the very library it was checking.
#
# Linking libavif statically removes the question rather than answering it: the shim has no libavif
# dependency, so there is no name to get right on any platform, and the self-contained check applies
# to it exactly as it does to the WebP shim. The cost is that libavif cannot be swapped without
# rebuilding the shim, and the jar is about the same size either way, since the bytes move from one
# file into the other.
#
# libavif is BSD 2 clause, so unlike the Little-CMS that comes with jpegli it does not have to
# redistribute with the result.

cmake_minimum_required(VERSION 3.16...3.27)
project(imagify-avif LANGUAGES C)

# The libavif build tree this shim is linked against. It has to be a static build: a shared one
# gives libavif a SONAME, which is the whole of what this file is arranged to avoid.
set(IMAGIFY_AVIF_SOURCE_DIR "" CACHE PATH "Path to a libavif build tree configured with BUILD_SHARED_LIBS=OFF")

if(NOT IMAGIFY_AVIF_SOURCE_DIR)
  message(FATAL_ERROR
    "Point -DIMAGIFY_AVIF_SOURCE_DIR at a libavif build tree, for example\n"
    "  cmake -S libavif -B build -DBUILD_SHARED_LIBS=OFF -DAVIF_BUILD_APPS=OFF -DAVIF_CODEC_AOM=LOCAL\n"
    "  cmake --build build --target avif_static")
endif()

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# /MT rather than the /MD that CMake picks by default, and it has to be set before the first target
# is created or CMake ignores it. A /MD build leaves the library importing VCRUNTIME140.dll, which
# comes from the Visual C++ redistributable rather than from Windows, so a user who has installed
# nothing would be missing it. The bundled library is loaded by absolute path out of a temporary
# directory and can be counted on to find nothing next to itself.
if(WIN32)
  set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded")
endif()

# --- the libavif to link in -------------------------------------------------------------------------
#
# libavif with BUILD_SHARED_LIBS=OFF builds an avif_static target, merges every LOCAL dependency
# into it and writes it out as libavif.a or avif.lib under the name avif. The merge is what makes
# this work: the whole point of the LOCAL option is that a static consumer gets one archive
# containing libavif and everything it needs, with no separate archives to find.
#
# The architecture is not baked into the search above because one file has to build for the three
# targets of a platform, and the arm64 binary in the jar is the same shim built for arm64.
find_library(IMAGIFY_AVIF_LIBRARY
  NAMES avif-static avif libavif
  HINTS "${IMAGIFY_AVIF_SOURCE_DIR}" "${IMAGIFY_AVIF_SOURCE_DIR}/.." "${IMAGIFY_AVIF_SOURCE_DIR}/lib"
  NO_DEFAULT_PATH)
if(NOT IMAGIFY_AVIF_LIBRARY)
  message(FATAL_ERROR
    "No static libavif under -DIMAGIFY_AVIF_SOURCE_DIR=${IMAGIFY_AVIF_SOURCE_DIR}.\n"
    "This links a libavif that already exists as a static library; it does not build one.\n"
    "Configure it with -DBUILD_SHARED_LIBS=OFF and build the avif_static target.")
endif()

# avif.h, which the shim includes as <avif/avif.h> because that is how libavif installs it. A build
# tree keeps it under the source tree's include/ and an install prefix under its own include/, so
# both are offered, plus the build tree itself for a caller who put the two together.
#
# The cache variable is the third case and the one the CI uses: a libavif build tree and its
# checkout are in different places, so -DIMAGIFY_AVIF_SOURCE_DIR=build finds the library under
# build/ and the header is at the checkout's include/ rather than anywhere near it.
set(IMAGIFY_AVIF_HEADER_DIR "" CACHE PATH "The directory holding avif/avif.h, if it is not under IMAGIFY_AVIF_SOURCE_DIR")
if(IMAGIFY_AVIF_HEADER_DIR)
  set(IMAGIFY_AVIF_INCLUDE_DIR "${IMAGIFY_AVIF_HEADER_DIR}")
else()
  find_path(IMAGIFY_AVIF_INCLUDE_DIR
    NAMES avif/avif.h
    HINTS "${IMAGIFY_AVIF_SOURCE_DIR}/include" "${IMAGIFY_AVIF_SOURCE_DIR}/../include"
          "${IMAGIFY_AVIF_SOURCE_DIR}/.." "${IMAGIFY_AVIF_SOURCE_DIR}"
    NO_DEFAULT_PATH)
endif()
if(NOT IMAGIFY_AVIF_INCLUDE_DIR)
  message(FATAL_ERROR
    "No avif/avif.h under -DIMAGIFY_AVIF_SOURCE_DIR=${IMAGIFY_AVIF_SOURCE_DIR} or one level above it.\n"
    "Point -DIMAGIFY_AVIF_HEADER_DIR at the directory that contains avif/avif.h if it is elsewhere.")
endif()

# The libavif release this was built against, for the diagnostic AvifCodec.getVersion() reports. It
# is not read out of the libavif tree for the reason the libwebp build spells out at length: a
# workflow step with no shell: key runs under pwsh on a Windows runner, and PowerShell hands a
# native command -DIMAGIFY_AVIF_VERSION=1.4.2 as two arguments, which cmake then caches as 1.
set(IMAGIFY_AVIF_VERSION "1.4.2" CACHE STRING
    "The libavif release version that AvifCodec.getVersion() reports")
if(IMAGIFY_AVIF_VERSION STREQUAL "")
  set(IMAGIFY_AVIF_VERSION "unknown")
elseif(NOT IMAGIFY_AVIF_VERSION MATCHES "^[0-9]+\\.[0-9]+(\\.[0-9]+)*$")
  message(FATAL_ERROR
    "IMAGIFY_AVIF_VERSION is [${IMAGIFY_AVIF_VERSION}], which is not a version number.\n"
    "The usual cause is a shell that split the argument in two. Quote the whole argument:\n"
    "  \"-DIMAGIFY_AVIF_VERSION=1.4.2\"")
endif()

# --- the two symbol lists ---------------------------------------------------------------------------
#
# Both are declared here rather than next to the thing that uses them, because CMake reads this file
# from the top down and a set() further down would expand to nothing where it is read. That is a
# silent failure: the .def comes out with no EXPORTS at all, the shim links with nothing unresolved
# because there is nothing to resolve, and the first avif call is the first anyone finds out.
#
# The entry points this shim exports. Only these may leave the library; everything else the compiler
# emitted for this file is made local by the version script on the two Unix targets, or by the .def
# on Windows.
set(IMAGIFY_AVIF_SYMBOLS
  imagify_avif_libavif_version
  imagify_avif_picture_create
  imagify_avif_picture_destroy
  imagify_avif_picture_from_abgr
  imagify_avif_picture_to_abgr
  imagify_avif_picture_pixels
  imagify_avif_picture_pixels_free
  imagify_avif_picture_info
  imagify_avif_picture_encode
  imagify_avif_picture_decode
  imagify_avif_animation_encode
  imagify_avif_sequence_open
  imagify_avif_sequence_close
  imagify_avif_sequence_read
  imagify_avif_sequence_sizes
  imagify_avif_sequence_frame
  imagify_avif_free)

# The libavif entry points the shim calls. Every one of them has been in libavif since 1.0.0, which
# is what lets one shim run against every libavif from 1.0.0 through 1.4.x.
set(IMAGIFY_AVIF_IMPORTS
  avifVersion
  avifResultToString
  avifImageCreate
  avifImageDestroy
  avifImageRGBToYUV
  avifImageYUVToRGB
  avifRGBImageSetDefaults
  avifDecoderCreate
  avifDecoderDestroy
  avifDecoderSetIOMemory
  avifDecoderParse
  avifDecoderNextImage
  avifDecoderNthImage
  avifEncoderCreate
  avifEncoderDestroy
  avifEncoderAddImage
  avifEncoderFinish
  avifRWDataFree
  avifFree)

# --- the shim -------------------------------------------------------------------------------------
add_library(imagify_avif SHARED imagify_avif.c imagify_avif.h)
target_include_directories(imagify_avif PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}" "${IMAGIFY_AVIF_INCLUDE_DIR}")
target_link_libraries(imagify_avif PRIVATE "${IMAGIFY_AVIF_LIBRARY}")

# libavif is C, but with libyuv and libsharpyuv built locally it is a C++ program underneath, so the
# link needs the C++ runtime. That is a new dependency this shim does not have with libavif linked
# dynamically, and it is why the self-contained allow list mentions libstdc++ and libc++: a static
# libavif brings the C++ runtime with it.
target_link_libraries(imagify_avif PRIVATE stdc++)

target_compile_definitions(imagify_avif
  PRIVATE "IMAGIFY_AVIF_VERSION=\"${IMAGIFY_AVIF_VERSION}\"")

# The library is named imagifyavif rather than avif for the reason the libwebp one is: a library
# called avif would collide with the system libavif every Linux distribution ships, and whichever of
# the two a process maps first wins the symbols. This jar never has to interoperate with a system
# libavif, so a name of its own makes that impossible rather than merely unlikely.
set_target_properties(imagify_avif PROPERTIES
  OUTPUT_NAME "imagifyavif"
  POSITION_INDEPENDENT_CODE ON)

# CMake puts no prefix on a DLL, so this is only wanted on the two Unix targets.
if(NOT WIN32)
  set_target_properties(imagify_avif PROPERTIES PREFIX "lib")
endif()

# --- exported symbols -----------------------------------------------------------------------------
if(WIN32)
  # The module name is the DLL's own, which is imagifyavif rather than this target's, because the
  # .def is handed to the linker that is producing it and a mismatch there is at best ignored and at
  # worst a DLL whose internal name disagrees with its file name.
  set(IMAGIFY_AVIF_DEF_CONTENT "LIBRARY imagifyavif\r\nEXPORTS\r\n")
  foreach(symbol IN LISTS IMAGIFY_AVIF_SYMBOLS)
    string(APPEND IMAGIFY_AVIF_DEF_CONTENT "  ${symbol}\r\n")
  endforeach()
  file(GENERATE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/imagify_avif.def"
    CONTENT "${IMAGIFY_AVIF_DEF_CONTENT}")
  # /DEF is what makes the DLL itself export the list. A .def that is only handed to the linker for
  # the import library does not change what the DLL exports.
  target_link_options(imagify_avif PRIVATE "/DEF:${CMAKE_CURRENT_BINARY_DIR}/imagify_avif.def")

elseif(APPLE)
  # One glob rather than the seventeen names, because ld64 matches an exported symbol against a glob
  # and the prefix is the whole of the interface anyway.
  file(GENERATE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/imagify_avif.exp" CONTENT "_imagify_avif_*\n")
  target_link_options(imagify_avif
    PRIVATE "-Wl,-exported_symbols_list,${CMAKE_CURRENT_BINARY_DIR}/imagify_avif.exp")
  target_link_options(imagify_avif PRIVATE "-Wl,-install_name,@rpath/libimagifyavif.1.dylib")

else()
  # The version node's name is one identifier, not a name and a version: there is no version number
  # field in a version script, so a space between the two parses the name and then trips over the
  # second token where the brace is expected.
  set(IMAGIFY_AVIF_MAP_CONTENT "IMAGIFYAVIF_1 {\n  global:\n")
  foreach(symbol IN LISTS IMAGIFY_AVIF_SYMBOLS)
    string(APPEND IMAGIFY_AVIF_MAP_CONTENT "    ${symbol};\n")
  endforeach()
  string(APPEND IMAGIFY_AVIF_MAP_CONTENT "  local:\n    *;\n};\n")
  file(GENERATE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/imagify_avif.map"
    CONTENT "${IMAGIFY_AVIF_MAP_CONTENT}")
  target_link_options(imagify_avif
    PRIVATE "-Wl,--version-script=${CMAKE_CURRENT_BINARY_DIR}/imagify_avif.map")
  # The static archives behind the link bring their own symbol tables along, and --exclude-libs is
  # what takes those out of the dynamic symbol table. libavif and libaom between them export several
  # thousand names, any of which could be interposed on by a library the host has already mapped.
  # This is the same reason and the same flag the libwebp build uses.
  target_link_options(imagify_avif PRIVATE "-Wl,--exclude-libs,ALL")
  set_target_properties(imagify_avif PROPERTIES
    VERSION "1.0.0"
    SOVERSION "1")
endif()
