# Copyright (C) 2026 The IMAGIFY Development Team
#
# Licensed under the MIT License (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#          http://opensource.org/licenses/mit-license.php

# Builds the shared library that imagify.jpeg.jna loads, for all six targets this jar supports.
#
# jpegli ships a libjpeg62 compatible shared library, but only declares the target where the linker
# understands a version script, which excludes Windows and macOS:
#
#   if (JPEGLI_ENABLE_JPEGLI_LIBJPEG AND NOT APPLE AND NOT WIN32 AND NOT EMSCRIPTEN)
#
# What that gate guards is not just the add_library. Everything from the object library onwards is
# inside it, down to the version script and the --exclude-libs flag that go with the shared library:
#
#   add_library(jpegli-libjpeg-obj OBJECT ...)   # compiles lib/jpegli/libjpeg_wrapper.cc
#   add_library(jpeg SHARED ${JPEGLI_LIBJPEG_INTERNAL_OBJECTS})
#   target_link_libraries(jpeg PRIVATE jpegli-static)
#   set_target_properties(jpeg PROPERTIES VERSION ... SOVERSION ...)
#
# None of that is about whether the code compiles, so all of it is re-declared here for the two
# platforms the gate excludes, with the same packaging expressed in whatever the local linker does
# understand: a .def file on Windows, an exported symbols list on macOS, a version script on ELF.
#
# The libjpeg62 API the wrapper implements is the one this project binds. jpegli picks it by
# SOVERSION, which defaults to 62, and lib/jpegli.cmake turns that into the C macro
# JPEG_LIB_VERSION both by way of the copy it makes of libjpeg-turbo's jconfig.h and, for the
# wrapper, by way of JPEGLI_LIBJPEG_OBJ_COMPILE_DEFINITIONS -- a variable that upstream never assigns
# anywhere. So the wrapper is compiled with that macro undefined and its `#if JPEG_LIB_VERSION >= 70`
# guards all read false, and the 6.2 API is what gets built no matter how this file configures it.

cmake_minimum_required(VERSION 3.16...3.27)
project(imagify-jpegli LANGUAGES C CXX)

# The directory the caller checked jpegli out into.
set(IMAGIFY_JPEGLI_SOURCE_DIR "" CACHE PATH "Path to a jpegli checkout")

if(NOT IMAGIFY_JPEGLI_SOURCE_DIR)
  message(FATAL_ERROR
    "Point -DIMAGIFY_JPEGLI_SOURCE_DIR at a jpegli checkout, for example\n"
    "  cmake -S . -B build -DIMAGIFY_JPEGLI_SOURCE_DIR=../jpegli")
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

# --- jpegli ---------------------------------------------------------------------------------------
#
# Nothing needed only to run jpegli's own tools and tests is on. The one that matters is
# JPEGLI_ENABLE_JPEGLI_LIBJPEG: left on, jpegli builds the Linux only libjpeg.so that the target
# below replaces, and on the two platforms the gate excludes it builds nothing at all and takes its
# sources with it.
#
# BUILD_TESTING is off because jpegli's root CMakeLists FATAL_ERRORs on an unpopulated testdata
# submodule, and because it is what turns on googletest. Nothing here is tested by ctest; the test
# suite in this repository exercises the library through JNA.
set(JPEGLI_ENABLE_JPEGLI_LIBJPEG OFF CACHE BOOL "" FORCE)
set(JPEGLI_INSTALL_JPEGLI_LIBJPEG OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_TOOLS OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_DEVTOOLS OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_BENCHMARK OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_FUZZERS OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_JNI OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_DOXYGEN OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_MANPAGES OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_SJPEG OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_OPENEXR OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_SKCMS OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_LTO OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)

# highway is a static archive whichever of the two switches is read: third_party/CMakeLists.txt in
# jpegli sets HWY_FORCE_STATIC_LIBS ON as an INTERNAL cache entry, which forces it, and a library
# loaded by absolute path out of a temporary directory cannot be counted on to find a sibling .so
# anyway. BUILD_SHARED_LIBS off as well, because it is the switch jpegli's own target names read.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# jpegli's own build reaches for a colour management library whichever way this is set, and aborts
# the configure when neither Little-CMS nor skcms is available. The Little-CMS submodule is the
# default and is what the checkout here provides; it is MIT, so it redistributes with the result.
#
# The target below never links it: jpegli-static's interface is highway, Threads and Atomics, and
# the libjpeg62 wrapper has no colour management in it.
add_subdirectory("${IMAGIFY_JPEGLI_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/jpegli" EXCLUDE_FROM_ALL)

# The jpegli version this was built from, for the diagnostic JpegliCodec.getVersion() reports. A
# caller pinning a commit should say what it pinned, because `git describe` has no tags to work
# from: jpegli publishes neither tags nor releases.
if(NOT IMAGIFY_JPEGLI_VERSION)
  find_program(IMAGIFY_JPEGLI_GIT git)
  if(IMAGIFY_JPEGLI_GIT)
    execute_process(
      COMMAND "${IMAGIFY_JPEGLI_GIT}" rev-parse --short HEAD
      WORKING_DIRECTORY "${IMAGIFY_JPEGLI_SOURCE_DIR}"
      OUTPUT_VARIABLE IMAGIFY_JPEGLI_VERSION
      OUTPUT_STRIP_TRAILING_WHITESPACE
      ERROR_QUIET)
  endif()
endif()
if(NOT IMAGIFY_JPEGLI_VERSION)
  set(IMAGIFY_JPEGLI_VERSION "unknown")
endif()

# --- the libjpeg62 compatibility layer --------------------------------------------------------------
#
# lib/jpegli.cmake copies jpeglib.h, jmorecfg.h and jconfig.h out of the libjpeg-turbo submodule into
# its own build tree and adds that directory to jpegli-static's INTERFACE include path, so linking
# jpegli-static is all it takes for <jpeglib.h> to resolve. Naming the directory here instead would
# mean guessing at jpegli's layout and keeping up with it.
#
# jpeglib.h is libjpeg-turbo's, and whether struct jpeg_decompress_struct has a data_precision
# member is a property of that header rather than of anything this project can assume: the field
# arrived in libjpeg 7 and the 6.2 API this wrapper implements predates it. Asking is one compile,
# and it is asked with jpegli-static on the line so that the answer is about the header rather than
# about how this file happened to configure it. A probe that fails for any other reason falls the
# same way, which is the safe direction: the shim then reports 8, which is right regardless because
# jpegli is an 8 bit codec.
#
# The try_compile is compile only, which is both the right kind of question and the one that does not
# drag highway and Threads into the probe's link line as a side effect of asking about a header.
include(CheckCSourceCompiles)
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)
set(CMAKE_REQUIRED_LIBRARIES jpegli-static)
check_c_source_compiles("
#include <jpeglib.h>
int main(void) {
  struct jpeg_decompress_struct cinfo;
  return cinfo.data_precision;
}" IMAGIFY_JPEG_HAS_DATA_PRECISION)
unset(CMAKE_REQUIRED_LIBRARIES)
set(CMAKE_TRY_COMPILE_TARGET_TYPE EXECUTABLE)
if(IMAGIFY_JPEG_HAS_DATA_PRECISION)
  message(STATUS "jpeglib.h declares data_precision; a decoded precision is read from the file")
else()
  message(STATUS "jpeglib.h has no data_precision; a decoded precision is reported as 8")
endif()

# Upstream names this one source file and nothing else, in lib/jpegli_lists.cmake. It is compiled
# here rather than by jpegli because jpegli only compiles it on the three platforms its own gate
# allows, and the object library holding it is inside that gate too.
#
# No compile definitions are set, deliberately: upstream would add
# JPEGLI_LIBJPEG_OBJ_COMPILE_DEFINITIONS here and never assigns it, and adding one would change which
# of the #if JPEG_LIB_VERSION branches in the wrapper are compiled.
add_library(imagify_jpegli_libjpeg OBJECT "${IMAGIFY_JPEGLI_SOURCE_DIR}/lib/jpegli/libjpeg_wrapper.cc")
target_link_libraries(imagify_jpegli_libjpeg PRIVATE jpegli-static)
# jpegli-static keeps the jpegli root on its PRIVATE include path, and the wrapper includes its
# headers as "lib/jpegli/decode.h" and "lib/jpegli/encode.h" from there. Only the generated copy of
# jpeglib.h travels the other way, on jpegli-static's PUBLIC one.
target_include_directories(imagify_jpegli_libjpeg PRIVATE "${IMAGIFY_JPEGLI_SOURCE_DIR}")
set_property(TARGET imagify_jpegli_libjpeg PROPERTY POSITION_INDEPENDENT_CODE ON)

# --- the shim -------------------------------------------------------------------------------------
#
# imagify_jpegli.c is the only C in this project, and it is what makes a JNA binding of jpegli
# possible at all. libjpeg unwinds out of a fatal error with setjmp/longjmp, while JNA silently
# swallows an exception thrown from a callback, so the handler has to live on the C side of the
# boundary. Its header says the whole of it.
add_library(imagify_jpegli SHARED
  imagify_jpegli.c imagify_jpegli.h $<TARGET_OBJECTS:imagify_jpegli_libjpeg>)
target_include_directories(imagify_jpegli PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(imagify_jpegli PRIVATE jpegli-static)
target_compile_definitions(imagify_jpegli
  PRIVATE "IMAGIFY_JPEGLI_VERSION=\"${IMAGIFY_JPEGLI_VERSION}\"")
if(IMAGIFY_JPEG_HAS_DATA_PRECISION)
  target_compile_definitions(imagify_jpegli PRIVATE IMAGIFY_JPEG_HAS_DATA_PRECISION)
endif()

# The library is named jpegli rather than jpeg. jpegli's own target has the soname libjpeg.so.62,
# which is also the soname of every system libjpeg on Linux, so two of them can end up mapped in one
# process and whichever was mapped first wins the symbols. This jar never has to interoperate with a
# system libjpeg, so a name of its own makes that collision impossible rather than merely unlikely.
#
# Note what is deliberately not here: a hidden visibility preset. It is the obvious way to keep
# symbols in, and on ELF it does not work the way it looks like it should -- a version script's
# global: cannot promote a symbol the compiler already marked hidden, so the six entry points below
# would be localised along with everything else and the library would export nothing at all. The two
# mechanisms that do work are used instead.
set_target_properties(imagify_jpegli PROPERTIES
  OUTPUT_NAME "jpegli"
  POSITION_INDEPENDENT_CODE ON)

# CMake puts no prefix on a DLL, so this is only wanted on the two Unix targets.
if(NOT WIN32)
  set_target_properties(imagify_jpegli PROPERTIES PREFIX "lib")
endif()

# --- exported symbols -----------------------------------------------------------------------------
#
# Only the imagify_jpegli_* entry points may leave this library. jpegli, highway and Little-CMS are
# linked in statically and would otherwise contribute several thousand symbols, any of which could be
# interposed on by, or interpose on, a library the host application has already mapped --
# jpeg_read_header is the name a system libjpeg would also claim. What follows names the handful that
# are meant to be visible and makes everything else local rather than merely unlisted.
set(IMAGIFY_JPEGLI_SYMBOLS
  imagify_jpegli_abi_version
  imagify_jpegli_jpegli_version
  imagify_jpegli_read_header
  imagify_jpegli_decode
  imagify_jpegli_encode
  imagify_jpegli_free)

if(WIN32)
  set(IMAGIFY_JPEGLI_DEF "${CMAKE_CURRENT_BINARY_DIR}/imagify_jpegli.def")
  # The module name is the DLL's own, which is jpegli rather than this target's, because the .def is
  # handed to the linker that is producing it and a mismatch there is at best ignored and at worst a
  # DLL whose internal name disagrees with its file name.
  set(IMAGIFY_JPEGLI_DEF_CONTENT "LIBRARY jpegli\r\nEXPORTS\r\n")
  foreach(symbol IN LISTS IMAGIFY_JPEGLI_SYMBOLS)
    string(APPEND IMAGIFY_JPEGLI_DEF_CONTENT "  ${symbol}\r\n")
  endforeach()
  # file(GENERATE) rather than file(WRITE): it is evaluated at generate time, so a reconfigure that
  # changes the symbol list cannot leave a stale .def behind.
  file(GENERATE OUTPUT "${IMAGIFY_JPEGLI_DEF}" CONTENT "${IMAGIFY_JPEGLI_DEF_CONTENT}")
  # The /DEF flag is what makes the DLL itself export the list. A .def that is only handed to the
  # linker for the import library does not change what the DLL exports.
  target_link_options(imagify_jpegli PRIVATE "/DEF:${IMAGIFY_JPEGLI_DEF}")

elseif(APPLE)
  # One glob rather than the six names, because ld64 matches an exported symbol against a glob and
  # the prefix is the whole of the interface anyway.
  file(GENERATE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/imagify_jpegli.exp" CONTENT "_imagify_jpegli_*\n")
  target_link_options(imagify_jpegli
    PRIVATE "-Wl,-exported_symbols_list,${CMAKE_CURRENT_BINARY_DIR}/imagify_jpegli.exp")
  # An install name of our own, for the reason the version script below is given: a soname that
  # collides with a system libjpeg is a coin toss over which copy of it a process ends up with.
  target_link_options(imagify_jpegli PRIVATE "-Wl,-install_name,@rpath/libjpegli.1.dylib")

else()
  set(IMAGIFY_JPEGLI_MAP_CONTENT "LIBJPEGLI 1.0 {\n  global:\n")
  foreach(symbol IN LISTS IMAGIFY_JPEGLI_SYMBOLS)
    string(APPEND IMAGIFY_JPEGLI_MAP_CONTENT "    ${symbol};\n")
  endforeach()
  string(APPEND IMAGIFY_JPEGLI_MAP_CONTENT "  local:\n    *;\n};\n")
  file(GENERATE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/imagify_jpegli.map"
    CONTENT "${IMAGIFY_JPEGLI_MAP_CONTENT}")
  # jpegli's own jpeg.target would reuse lib/jpegli/jpeg.version.62, which exports the whole of
  # the libjpeg62 interface. That is the wrong list here: it is exactly the set of names a system
  # libjpeg also defines, so exporting them from a second library in the same process is the
  # collision this library is named to avoid.
  target_link_options(imagify_jpegli
    PRIVATE "-Wl,--version-script=${CMAKE_CURRENT_BINARY_DIR}/imagify_jpegli.map")
  # The static archives behind the link bring their own symbol tables along, and --exclude-libs is
  # what takes those out of the dynamic symbol table. jpegli passes this same flag for the same
  # reason, and notes that without it highway's symbols leak out.
  target_link_options(imagify_jpegli PRIVATE "-Wl,--exclude-libs,ALL")
  set_target_properties(imagify_jpegli PROPERTIES
    VERSION "1.0.0"
    SOVERSION "1")
endif()
