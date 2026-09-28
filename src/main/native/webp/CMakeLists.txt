# Copyright (C) 2026 The IMAGIFY Development Team
#
# Licensed under the MIT License (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#          http://opensource.org/licenses/mit-license.php

# Builds the shared library that imagify.webp.jna loads, for all six targets this jar supports.
#
# libwebp is a set of static libraries, so nothing here re-declares what upstream declares. Unlike
# the jpegli overlay next door, which exists because jpegli only builds its libjpeg62 library on the
# three platforms whose linker understands a version script, this one only has to turn libwebp's
# tools off and its four libraries into one shared object.
#
# What upstream declares, and what is wanted, are:
#
#   webp         the VP8 and VP8L codec, both directions
#   webpdemux    the frame walker, and on top of it the animation decoder
#   libwebpmux   the animation encoder
#
# The fourth library upstream builds, sharpyuv, is a dependency of the encoder and is linked in
# behind it without being named here.
#
# libwebp is Apache 2.0 with a BSD 3 clause carve-out for the code it took from libjpeg-turbo, so
# unlike the Little-CMS that comes with jpegli it redistributes with the result.

cmake_minimum_required(VERSION 3.16...3.27)
project(imagify-webp LANGUAGES C)

# The directory the caller checked libwebp out into.
set(IMAGIFY_WEBP_SOURCE_DIR "" CACHE PATH "Path to a libwebp checkout")

if(NOT IMAGIFY_WEBP_SOURCE_DIR)
  message(FATAL_ERROR
    "Point -DIMAGIFY_WEBP_SOURCE_DIR at a libwebp checkout, for example\n"
    "  cmake -S . -B build -DIMAGIFY_WEBP_SOURCE_DIR=../libwebp")
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

# --- libwebp ---------------------------------------------------------------------------------------
#
# Everything that exists only to run libwebp's own tools or tests is off. They are not merely
# unwanted, they are the reason this file has to set them: cwebp and dwebp reach for a PNG, JPEG and
# TIFF library apiece, gif2webp for libgif, and the tests for googletest, and a checkout here has
# none of those submodules populated. img2webp would have been usable, since it reads nothing but
# WebP, but it needs the two libraries this one already needs and builds a second copy of them.
#
# BUILD_SHARED_LIBS off makes all four of libwebp's libraries static, which is the switch their own
# add_library calls read, and which a library loaded by absolute path out of a temporary directory
# wants anyway: it cannot be counted on to find a sibling .so.
#
# WEBP_USE_THREAD stays on, which is a departure from the value that would be safest to take. Its
# threading calls pthread_create on the three Unix targets and CreateThread on Windows, so the first
# needs a pthread link and the second needs nothing at all. The link is spelled out below rather than
# left to chance. What it buys is the same thing the webp4j this replaces bought, since that too was
# built with multiThreaded(true): libwebp parallelises the entropy coding of a frame, which is the
# expensive half of encoding a large image or one frame of an animation.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_ANIM_UTILS OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_CWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_DWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_GIF2WEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_IMG2WEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_VWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBPINFO OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBPMUX OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_EXTRAS OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBP_JS OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_FUZZTEST OFF CACHE BOOL "" FORCE)
# libwebpmux is the only one of the four that is not needed for still images, and it is needed for
# animations, so unlike everything above this one stays on.
set(WEBP_BUILD_LIBWEBPMUX ON CACHE BOOL "" FORCE)
# [[nodiscard]] on functions this shim calls for their effect, which is several of them. Turning it
# off costs nothing: there is no caller here that a warning could reach.
set(WEBP_ENABLE_WUNUSED_RESULT OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)

add_subdirectory("${IMAGIFY_WEBP_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/webp" EXCLUDE_FROM_ALL)

# The libwebp release this was built from, for the diagnostic WebpCodec.getVersion() reports.
#
# It is passed in rather than worked out, because the one version libwebp's own build does settle on
# is its library version: set_version() in its root CMakeLists.txt turns the libtool -version-info
# out of src/Makefile.am into a VERSION property, and at tag v1.6.0 that is 1.0.6, which is a real
# libwebp version and not the one that was built. A checkout's git tag would say it correctly, but a
# caller who downloaded an archive has no tags to read.
if(NOT IMAGIFY_WEBP_VERSION)
  set(IMAGIFY_WEBP_VERSION "unknown")
endif()

find_package(Threads REQUIRED)

# --- the shim -------------------------------------------------------------------------------------
#
# imagify_webp.c is the only C in this project, and it is what makes a JNA binding of libwebp
# possible at all. Encoding a picture means filling in struct WebPPicture, which is 256 bytes on a 64
# bit platform and mostly not data, and the animation encoder cannot be driven without one. Its
# header says the whole of it.
add_library(imagify_webp SHARED imagify_webp.c imagify_webp.h)
target_include_directories(imagify_webp PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(imagify_webp PRIVATE webp webpdemux libwebpmux)
# Named explicitly for the reason given where WEBP_USE_THREAD is set above: libwebp's own libraries
# do not link a threading library, only its tools do, and a tool is not what is being built here.
target_link_libraries(imagify_webp PRIVATE Threads::Threads)
target_compile_definitions(imagify_webp
  PRIVATE "IMAGIFY_WEBP_VERSION=\"${IMAGIFY_WEBP_VERSION}\"")

# The library is named imagifywebp rather than webp. libwebp's shared library is libwebp.so, which
# is the soname of every system libwebp on Linux, so two of them can end up mapped in one process
# and whichever was mapped first wins the symbols. This jar never has to interoperate with a system
# libwebp, so a name of its own makes that collision impossible rather than merely unlikely.
#
# Note what is deliberately not here: a hidden visibility preset. It is the obvious way to keep
# symbols in, and on ELF it does not work the way it looks like it should -- a version script's
# global: cannot promote a symbol the compiler already marked hidden, so the six entry points below
# would be localised along with everything else and the library would export nothing at all. The two
# mechanisms that do work are used instead.
set_target_properties(imagify_webp PROPERTIES
  OUTPUT_NAME "imagifywebp"
  POSITION_INDEPENDENT_CODE ON)

# CMake puts no prefix on a DLL, so this is only wanted on the two Unix targets.
if(NOT WIN32)
  set_target_properties(imagify_webp PROPERTIES PREFIX "lib")
endif()

# --- exported symbols -----------------------------------------------------------------------------
#
# Only the imagify_webp_* entry points may leave this library. libwebp is linked in statically and
# would otherwise contribute several thousand symbols, any of which could be interposed on by, or
# interpose on, a library the host application has already mapped -- WebPGetFeatures is the name a
# system libwebp also claims, and the one call on the decode path that would then read a different
# structure layout. What follows names the handful that are meant to be visible and makes everything
# else local rather than merely unlisted.
set(IMAGIFY_WEBP_SYMBOLS
  imagify_webp_abi_version
  imagify_webp_webp_version
  imagify_webp_read_features
  imagify_webp_decode
  imagify_webp_encode
  imagify_webp_read_animation
  imagify_webp_decode_animation
  imagify_webp_encode_animation
  imagify_webp_free)

if(WIN32)
  set(IMAGIFY_WEBP_DEF "${CMAKE_CURRENT_BINARY_DIR}/imagify_webp.def")
  # The module name is the DLL's own, which is imagifywebp rather than this target's, because the
  # .def is handed to the linker that is producing it and a mismatch there is at best ignored and at
  # worst a DLL whose internal name disagrees with its file name.
  set(IMAGIFY_WEBP_DEF_CONTENT "LIBRARY imagifywebp\r\nEXPORTS\r\n")
  foreach(symbol IN LISTS IMAGIFY_WEBP_SYMBOLS)
    string(APPEND IMAGIFY_WEBP_DEF_CONTENT "  ${symbol}\r\n")
  endforeach()
  # file(GENERATE) rather than file(WRITE): it is evaluated at generate time, so a reconfigure that
  # changes the symbol list cannot leave a stale .def behind.
  file(GENERATE OUTPUT "${IMAGIFY_WEBP_DEF}" CONTENT "${IMAGIFY_WEBP_DEF_CONTENT}")
  # The /DEF flag is what makes the DLL itself export the list. A .def that is only handed to the
  # linker for the import library does not change what the DLL exports.
  target_link_options(imagify_webp PRIVATE "/DEF:${IMAGIFY_WEBP_DEF}")

elseif(APPLE)
  # One glob rather than the nine names, because ld64 matches an exported symbol against a glob and
  # the prefix is the whole of the interface anyway.
  file(GENERATE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/imagify_webp.exp" CONTENT "_imagify_webp_*\n")
  target_link_options(imagify_webp
    PRIVATE "-Wl,-exported_symbols_list,${CMAKE_CURRENT_BINARY_DIR}/imagify_webp.exp")
  # An install name of our own, for the reason the version script below is given: a soname that
  # collides with a system libwebp is a coin toss over which copy of it a process ends up with.
  target_link_options(imagify_webp PRIVATE "-Wl,-install_name,@rpath/libimagifywebp.1.dylib")

else()
  # The version node's name is one identifier, not a name and a version: there is no version number
  # field in a version script, so a space between the two parses the name and then trips over the
  # second token where the brace is expected.
  #
  # The soname is a separate matter and comes from the VERSION and SOVERSION properties below, not
  # from anything in here.
  set(IMAGIFY_WEBP_MAP_CONTENT "IMAGIFYWEBP_1 {\n  global:\n")
  foreach(symbol IN LISTS IMAGIFY_WEBP_SYMBOLS)
    string(APPEND IMAGIFY_WEBP_MAP_CONTENT "    ${symbol};\n")
  endforeach()
  string(APPEND IMAGIFY_WEBP_MAP_CONTENT "  local:\n    *;\n};\n")
  file(GENERATE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/imagify_webp.map"
    CONTENT "${IMAGIFY_WEBP_MAP_CONTENT}")
  target_link_options(imagify_webp
    PRIVATE "-Wl,--version-script=${CMAKE_CURRENT_BINARY_DIR}/imagify_webp.map")
  # The static archives behind the link bring their own symbol tables along, and --exclude-libs is
  # what takes those out of the dynamic symbol table. libwebp's own build passes this same flag for
  # the same reason, and notes that without it the symbols of the libraries it links leak out.
  target_link_options(imagify_webp PRIVATE "-Wl,--exclude-libs,ALL")
  set_target_properties(imagify_webp PROPERTIES
    VERSION "1.0.0"
    SOVERSION "1")
endif()
