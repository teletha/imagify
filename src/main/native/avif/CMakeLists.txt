# Copyright (C) 2026 The IMAGIFY Development Team
#
# Licensed under the MIT License (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#          http://opensource.org/licenses/mit-license.php

# Builds the shared library that imagify.avif.ffm loads, for all six targets this jar supports.
#
# This one is the opposite of the libwebp build next door. libwebp is a set of static libraries that
# is compiled from source here, so that one links them in and exports six entry points of its own.
# libavif is not built here at all: this jar already ships it as a separate shared library per
# platform, and this shim is built against that. So the shim is a small object that forwards to a
# libavif the loader finds at run time, rather than a second copy of a ten megabyte library.
#
# That is also what makes the shim worth building. A Java binding cannot store a Java array into
# avifRGBImage.pixels, because the FFM ABI will not put a heap segment into a pointer-typed struct
# field, so an entry point that takes the pixels as its own argument and is called with
# Linker.Option.critical set is what lets a picture be encoded without a copy of its own pixels. See
# imagify_avif.c for the rest of the reasoning.
#
# libavif is BSD 2 clause, so unlike the Little-CMS that comes with jpegli it does not have to
# redistribute with the result: it is already a separate binary in the jar.

cmake_minimum_required(VERSION 3.16...3.27)
project(imagify-avif LANGUAGES C)

# The directory the caller checked libavif out into. Its built shared library is what this links
# against, and the jar's copy of it is what it ends up importing.
set(IMAGIFY_AVIF_SOURCE_DIR "" CACHE PATH "Path to a libavif build tree or checkout")

if(NOT IMAGIFY_AVIF_SOURCE_DIR)
  message(FATAL_ERROR
    "Point -DIMAGIFY_AVIF_SOURCE_DIR at a built libavif, for example\n"
    "  cmake -S . -B build -DIMAGIFY_AVIF_SOURCE_DIR=../libavif/build")
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

# --- the libavif this shim is built against ----------------------------------------------------------
#
# The file name is the whole of the search. On Windows the shim's import table names this library,
# and the loader resolves that name against the files sitting beside the shim, so the shim and
# libavif have to agree on it and the copy in the jar is already named for the platform it is for.
# The resource name is therefore derived here rather than passed in, and a build whose platform the
# jar has no name for is a build that would ship a library nobody can load.
if(WIN32)
  set(IMAGIFY_AVIF_PLATFORM "windows")
  set(IMAGIFY_AVIF_LIBRARY_NAME "libavif-windows-x64.dll")
elseif(APPLE)
  set(IMAGIFY_AVIF_PLATFORM "macos")
  set(IMAGIFY_AVIF_LIBRARY_NAME "libavif-macos-x64.dylib")
else()
  set(IMAGIFY_AVIF_PLATFORM "linux")
  set(IMAGIFY_AVIF_LIBRARY_NAME "libavif-linux-x64.so")
endif()

# The architecture is not baked in above on purpose: one file has to build for the three targets of
# a platform, and the arm64 binary in the jar is the same shim built for arm64. What is checked here
# is that the caller pointed at something that looks like a libavif, because a wrong path otherwise
# becomes a link error about an undefined avifVersion rather than a message about a wrong directory.
find_library(IMAGIFY_AVIF_LIBRARY
  NAMES ${IMAGIFY_AVIF_LIBRARY_NAME} avif avif.lib
  HINTS "${IMAGIFY_AVIF_SOURCE_DIR}" "${IMAGIFY_AVIF_SOURCE_DIR}/.." "${IMAGIFY_AVIF_SOURCE_DIR}/lib"
  NO_DEFAULT_PATH)
if(NOT IMAGIFY_AVIF_LIBRARY)
  message(FATAL_ERROR
    "No ${IMAGIFY_AVIF_LIBRARY_NAME} under -DIMAGIFY_AVIF_SOURCE_DIR=${IMAGIFY_AVIF_SOURCE_DIR}.\n"
    "This builds the shim against a libavif that already exists; it does not build libavif.")
endif()

# avif.h, which the shim includes as <avif/avif.h> because that is how libavif installs it. A build
# tree keeps it under include/ and an install prefix under include/ as well, while a checkout that has
# only been configured has it in the source tree, so all three are offered. Finding the header and
# the library independently is deliberate: they can come from one install or from a build tree beside
# a checkout, and a version that disagreed would be a wrong struct layout rather than a build error.
#
# The cache variable is the third case and the one the CI uses: a libavif build tree and its checkout
# are in different places, so -DIMAGIFY_AVIF_SOURCE_DIR=build finds the library under build/ and the
# header is at the checkout's include/ rather than anywhere near it. Naming the header explicitly is
# what that needs, and it is a hint that is overridden rather than replaced, so a caller who only has
# one directory keeps the search above.
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
# silent failure: the import library comes out with no EXPORTS at all, the shim links with nothing
# unresolved because there is nothing to resolve, and the first avif call is the first anyone finds
# out.
#
# The entry points this shim exports. Only these may leave the library; the avif* names below are
# imported rather than defined, so they are not this library's to export and cannot collide with
# anything, but the version script on the two Unix targets is still worth having, because it is what
# makes everything the compiler emitted for this file local.
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

# The libavif entry points the shim calls, which is what the import library on Windows is generated
# from. Every one of them has been in libavif since 1.0.0, which is what lets this shim run against a
# libavif the jar shipped rather than one it built. They are listed rather than scraped out of the
# binary because the list is short enough to read and to check against the header, and because a
# build that scraped it would silently start importing whatever the next libavif release added.
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

if(WIN32)
  # MSVC's linker will not take a DLL as an input: it wants an import library, and passing the DLL
  # itself is LNK1107. One is generated from a module definition file naming the nineteen libavif
  # entry points the shim calls, which is a list short enough to read and check rather than one
  # scraped out of the binary at build time.
  #
  # The LIBRARY name in it is not cosmetic. It is the module name the shim's import table ends up
  # with, and the loader looks that name up among the files next to the shim, so naming it anything
  # other than the bundled libavif's file name produces a DLL that loads and then fails to find
  # avifVersion. On a build for arm64 the same applies, which is why this is derived from the
  # platform rather than written out.
  set(IMAGIFY_AVIF_DEF_CONTENT "LIBRARY ${IMAGIFY_AVIF_LIBRARY_NAME}\r\nEXPORTS\r\n")
  foreach(symbol IN LISTS IMAGIFY_AVIF_IMPORTS)
    string(APPEND IMAGIFY_AVIF_DEF_CONTENT "  ${symbol}\r\n")
  endforeach()
  file(GENERATE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/imagify_avif_imports.def"
    CONTENT "${IMAGIFY_AVIF_DEF_CONTENT}")

  # Windows resolves a module's imports against the directory of the module being loaded, so
  # recording the bare file name is already enough. The two Unix targets need more, below.
  # -------------------------------------------------------------------------------------------

  # /DEF is what makes the DLL itself export the list. A .def that is only handed to the linker for
  # the import library does not change what the DLL exports.
  set(IMAGIFY_AVIF_EXPORT_DEF_CONTENT "LIBRARY imagifyavif\r\nEXPORTS\r\n")
  foreach(symbol IN LISTS IMAGIFY_AVIF_SYMBOLS)
    string(APPEND IMAGIFY_AVIF_EXPORT_DEF_CONTENT "  ${symbol}\r\n")
  endforeach()
  file(GENERATE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/imagify_avif.def"
    CONTENT "${IMAGIFY_AVIF_EXPORT_DEF_CONTENT}")

  # The generated .lib is a real build step rather than a file that has to be there already, and the
  # shim depends on it explicitly. A custom command whose OUTPUT nothing links against is never run
  # by MSBuild, and the build then fails with LNK1181 on a file that was never generated, which says
  # nothing about the thing that is actually wrong.
  add_custom_command(
    OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/libavif_import.lib"
    COMMAND lib /nologo
            "/def:${CMAKE_CURRENT_BINARY_DIR}/imagify_avif_imports.def"
            "/out:${CMAKE_CURRENT_BINARY_DIR}/libavif_import.lib"
            "/machine:${CMAKE_SYSTEM_PROCESSOR}"
    DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/imagify_avif_imports.def"
    COMMENT "Generating the libavif import library"
    VERBATIM)
  add_custom_target(imagify_avif_import ALL
    DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/libavif_import.lib")
  add_dependencies(imagify_avif imagify_avif_import)
  target_link_libraries(imagify_avif PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/libavif_import.lib")
  target_link_options(imagify_avif PRIVATE "/DEF:${CMAKE_CURRENT_BINARY_DIR}/imagify_avif.def")
else()
  # The two Unix targets resolve a module's dependencies by name rather than against the directory
  # the module sits in, which is the opposite of Windows and is the whole of what is left to arrange.
  #
  # The name the shim records is libavif's SONAME, not the file it was linked against, and libavif
  # builds itself the conventional one: libavif.so.16 on Linux, libavif.16.dylib on macOS. The jar
  # ships neither of those, because the files are named for the platform and the architecture so that
  # six of them can sit in one directory. So a shim linked against a stock libavif records a name
  # that nothing in the jar answers to, and it fails to load on every platform but the one this was
  # hand-built on.
  #
  # IMAGIFY_AVIF_SONAME_C_FLAG is how that is fixed from the outside. libavif is configured with
  # -DCMAKE_SHARED_LIBRARY_SONAME_C_FLAG pointing at the bundled name, which is what the caller has
  # to do; this variable is only how the flag reaches it. The workflow passes:
  #   Linux   -Wl,-soname,libavif-linux-x64.so
  #   macOS   -install_name,@loader_path/libavif-macos-x64.dylib
  # macOS needs no rpath because @loader_path is the directory of the loading module, which is the
  # unpacked directory holding both files. Linux does, because a bare DT_NEEDED name is searched in
  # RUNPATH, then LD_LIBRARY_PATH, then the loader's cache, and never beside the shim, so $ORIGIN is
  # what puts it there. The two dollars and the backslash keep CMake from expanding $ORIGIN as one
  # of its own variables.
  target_link_libraries(imagify_avif PRIVATE "${IMAGIFY_AVIF_LIBRARY}")
  if(NOT APPLE)
    # Setting BUILD_RPATH replaces the absolute build tree path CMake would otherwise put in the
    # RUNPATH, rather than adding to it, so the shim has exactly one search location and it is the
    # one it will be unpacked into. BUILD_RPATH_USE_ORIGIN is what stops CMake expanding $ORIGIN.
    set_target_properties(imagify_avif PROPERTIES
      BUILD_RPATH "$ORIGIN"
      BUILD_RPATH_USE_ORIGIN ON
      INSTALL_RPATH "$ORIGIN")
  endif()
  # macOS needs no rpath at all: the install name libavif is configured with is @loader_path, which
  # dyld reads as the directory of the loading module, and both files are unpacked into one.
endif()

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
if(APPLE)
  # One glob rather than the seventeen names, because ld64 matches an exported symbol against a glob
  # and the prefix is the whole of the interface anyway.
  file(GENERATE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/imagify_avif.exp" CONTENT "_imagify_avif_*\n")
  target_link_options(imagify_avif
    PRIVATE "-Wl,-exported_symbols_list,${CMAKE_CURRENT_BINARY_DIR}/imagify_avif.exp")
  target_link_options(imagify_avif PRIVATE "-Wl,-install_name,@rpath/libimagifyavif.1.dylib")

elseif(NOT WIN32)
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
endif()
