# Copyright (C) 2026 The IMAGIFY Development Team
#
# Licensed under the MIT License (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#          http://opensource.org/licenses/mit-license.php

# Builds the shared library that imagify.jpeg.jna loads, for all six targets this jar supports.
#
# jpegli ships a libjpeg62 compatible shared library, but only builds it where the linker understands
# a version script, which excludes Windows and macOS:
#
#   if (JPEGLI_ENABLE_JPEGLI_LIBJPEG AND NOT APPLE AND NOT WIN32 AND NOT EMSCRIPTEN)
#
# What that gate guards is three commands. Everything around them is packaging:
#
#   add_library(jpeg SHARED ${JPEGLI_LIBJPEG_INTERNAL_OBJECTS})
#   target_link_libraries(jpeg PRIVATE jpegli-static)
#   set_target_properties(jpeg PROPERTIES VERSION ... SOVERSION ...)
#
# The platform gating is in the version script, which tags every symbol with a LIBJPEG_6.2 version
# node, and in the --exclude-libs flag that hides the symbols of the statically linked dependencies.
# Neither has anything to do with whether the code compiles, so the target is declared here instead,
# with the equivalent packaging expressed in whatever the local linker understands: a .def file on
# Windows, an exported symbols list on macOS, a version script on ELF platforms.
#
# The library is named jpegli rather than jpeg. jpegli's own target has the soname libjpeg.so.62,
# which is also the soname of every system libjpeg on Linux, so two of them can end up mapped in one
# process and whichever was mapped first wins the symbols. This jar never has to interoperate with a
# system libjpeg, so a name of its own makes that collision impossible rather than merely unlikely.

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

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# /MT rather than the /MD that CMake picks by default, and it has to be set before the first target
# is created or CMake ignores it. A /MD build leaves the library importing VCRUNTIME140.dll, which
# comes with the Visual C++ redistributable rather than with Windows, so a user who has installed
# nothing would be missing it. The bundled library is loaded by absolute path out of a temporary
# directory and can be counted on to find nothing next to itself.
if(WIN32)
  set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded")
endif()

# --- jpegli ---------------------------------------------------------------------------------------
#
# JPEGLI_ENABLE_JPEGLI_LIBJPEG is off so that jpegli does not build the Linux only libjpeg.so that
# this target replaces. Everything needed only to run jpegli's own tools and tests is off as well:
# this library has no command line in it, and BUILD_TESTING off is what keeps a checkout without its
# submodules from failing the configure step on a missing googletest.
set(JPEGLI_ENABLE_JPEGLI_LIBJPEG OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_TOOLS OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_BENCHMARK OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_FUZZERS OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_JNI OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_DOXYGEN OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_MANPAGES OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_SJPEG OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_OPENEXR OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_SKCMS OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)

# highway is linked in statically. A shared library loaded by absolute path out of a temporary
# directory cannot be counted on to find a sibling, and the check that runs after every build would
# reject a .so next to it anyway. BUILD_SHARED_LIBS off before add_subdirectory, because that is the
# switch highway reads.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
if(WIN32)
  set(HWY_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory("${IMAGIFY_JPEGLI_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/jpegli" EXCLUDE_FROM_ALL)

# The shim includes <jpeglib.h>, which lives in jpegli's own tree rather than in any install prefix.
# Whether jpegli exports that directory from its static target is jpegli's business and has changed
# between releases, so both of the places the header has lived are named here rather than relying on
# the link to bring one of them along.
set(IMAGIFY_JPEGLI_INCLUDES
  "${IMAGIFY_JPEGLI_SOURCE_DIR}/src"
  "${IMAGIFY_JPEGLI_SOURCE_DIR}/src/libjpeg")

# jpegli implements the libjpeg 6.2 API, and struct jpeg_decompress_struct only grew a
# data_precision member in libjpeg 7, so whether imagify_jpegli.c can read it is a property of the
# jpeglib.h this build is about to compile against rather than anything this project can assume.
# Asking the header is one compile, and the answer only decides what precision a decoded file is
# reported at, which is 8 either way because jpegli does 8 bits and no more. A probe that fails for
# some other reason falls the same way, which is the safe direction: the shim then reports 8 rather
# than failing to build.
include(CheckCSourceCompiles)
set(CMAKE_REQUIRED_INCLUDES ${IMAGIFY_JPEGLI_INCLUDES})
# Linked so that the probe sees the same include directories and compile options the shim will, which
# is what makes its answer about the header rather than about how it was configured.
set(CMAKE_REQUIRED_LIBRARIES jpegli-static)
check_c_source_compiles("
#include <jpeglib.h>
int main(void) {
  struct jpeg_decompress_struct cinfo;
  return cinfo.data_precision;
}" IMAGIFY_JPEG_HAS_DATA_PRECISION)
unset(CMAKE_REQUIRED_INCLUDES)
unset(CMAKE_REQUIRED_LIBRARIES)
if(IMAGIFY_JPEG_HAS_DATA_PRECISION)
  message(STATUS "jpeglib.h declares data_precision; the decoded precision is read from the file")
else()
  message(STATUS "jpeglib.h has no data_precision; the decoded precision is reported as 8")
endif()

# The version of jpegli this was built from, for the diagnostic JpegliCodec.getVersion() reports. A
# release tarball has no git in it, so this is only a fallback and a caller may set it outright.
if(NOT IMAGIFY_JPEGLI_VERSION)
  find_program(IMAGIFY_JPEGLI_GIT git)
  if(IMAGIFY_JPEGLI_GIT)
    execute_process(
      COMMAND "${IMAGIFY_JPEGLI_GIT}" describe --always --tags
      WORKING_DIRECTORY "${IMAGIFY_JPEGLI_SOURCE_DIR}"
      OUTPUT_VARIABLE IMAGIFY_JPEGLI_VERSION
      OUTPUT_STRIP_TRAILING_WHITESPACE
      ERROR_QUIET)
  endif()
endif()
if(NOT IMAGIFY_JPEGLI_VERSION)
  set(IMAGIFY_JPEGLI_VERSION "unknown")
endif()

# --- the shim -------------------------------------------------------------------------------------
#
# imagify_jpegli.c is the only C in this project, and it is what makes a JNA binding of jpegli
# possible at all. libjpeg unwinds out of a fatal error with setjmp/longjmp, while JNA silently
# swallows an exception thrown from a callback, so the handler has to live on the C side of the
# boundary. Its header says the whole of it.
add_library(imagify_jpegli SHARED imagify_jpegli.c imagify_jpegli.h)
target_include_directories(imagify_jpegli PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}" ${IMAGIFY_JPEGLI_INCLUDES})
target_link_libraries(imagify_jpegli PRIVATE jpegli-static)
target_compile_definitions(imagify_jpegli
  PRIVATE "IMAGIFY_JPEGLI_VERSION=\"${IMAGIFY_JPEGLI_VERSION}\"")
if(IMAGIFY_JPEG_HAS_DATA_PRECISION)
  target_compile_definitions(imagify_jpegli PRIVATE IMAGIFY_JPEG_HAS_DATA_PRECISION)
endif()

set_target_properties(imagify_jpegli PROPERTIES
  OUTPUT_NAME "jpegli"
  C_VISIBILITY_PRESET hidden
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN ON
  POSITION_INDEPENDENT_CODE ON)

if(NOT WIN32)
  # A lib prefix applies to a DLL too under CMake, so it is only wanted on the Unix targets.
  set_target_properties(imagify_jpegli PROPERTIES PREFIX "lib")
endif()

# --- exported symbols -----------------------------------------------------------------------------
#
# Only the imagify_jpegli_* entry points may leave this library. jpegli and highway are linked in
# statically and would otherwise contribute several thousand symbols, any of which could be
# interposed on by, or interpose on, a library the host application has already mapped. The
# visibility preset above keeps them out of the dynamic symbol table to begin with; what follows
# names the handful that are meant to be visible.
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
  target_link_options(imagify_jpegli
    PRIVATE "-Wl,--version-script=${CMAKE_CURRENT_BINARY_DIR}/imagify_jpegli.map")
  # The static archives behind the link bring their own symbol tables along, and --exclude-libs is
  # what takes those out of the dynamic symbol table. jpegli's own build passes this same flag for
  # the same reason, and notes that without it highway's symbols leak out.
  target_link_options(imagify_jpegli PRIVATE "-Wl,--exclude-libs,ALL")
  set_target_properties(imagify_jpegli PROPERTIES
    VERSION "1.0.0"
    SOVERSION "1")
endif()
