#!/usr/bin/env python3
"""Fails when a shared library still has dependencies that would not be found next to it.

The libraries bundled into the imagify jar are loaded by absolute path out of a temporary
directory. On Windows the loader resolves the dependencies of the module it loads against the
directory of the *executable* and against PATH, never against the directory of the module
itself, so a libavif that links aom.dll next to it, or a jpegli that links highway.dll next
to it, or a libwebp that links pthread.dll next to it, would fail to load for every user. A
distribution build such as MSYS2's libavif, which
is only 326 KB precisely because it links aom, dav1d, rav1e, SvtAv1Enc, libyuv, libjpeg,
libpng, libxml2 and zlib dynamically, cannot be shipped this way.

Hence the bundled binaries must be self contained: whatever they link may only be the C runtime
and the operating system. This script reads the dependency list straight out of the PE, ELF and
Mach-O containers instead of shelling out to objdump, otool or dumpbin, because the first two are
unavailable on Windows runners and dumpbin needs an MSVC environment that is not set up in a bash
step. Parsing the container ourselves keeps the check identical on all six targets and free of any
dependency beyond the Python that every runner already has.

Usage: check-self-contained.py <library> [--allow NAME]...
Exit code 0 when self contained, 1 when a dependency is unexpected, 2 when the file cannot be read.
"""

import fnmatch
import os
import struct
import sys

# Anything the platform always provides. Matched case insensitively, either exactly or as a glob.
# An unexpected name fails the build loudly on purpose: it is much better to break CI than to ship
# a library that cannot be loaded.
WINDOWS_ALLOWED = [
    # Universal CRT, which Windows 10 and later ship as api-ms-win-crt-* forwarders. This is what a
    # /MT build imports once the static C runtime is linked in.
    "api-ms-*", "ext-ms-*", "ucrtbase.dll", "ucrtbased.dll", "ucrt*.dll", "msvcrt*.dll",
    # The kernel and the handful of system libraries libavif, aom, jpegli and highway actually touch.
    "kernel32.dll", "kernelbase.dll", "ntdll.dll", "user32.dll", "gdi32.dll",
    "advapi32.dll", "ole32.dll", "oleaut32.dll", "shell32.dll", "shlwapi.dll",
    "ws2_32.dll", "mswsock.dll", "bcrypt.dll", "ncrypt.dll", "rpcrt4.dll",
    "secur32.dll", "crypt32.dll", "combase.dll", "imm32.dll", "winmm.dll",
    "iphlpapi.dll", "setupapi.dll", "cfgmgr32.dll", "powrprof.dll", "dbghelp.dll",
    "psapi.dll", "shcore.dll", "dxva2.dll", "dxgi.dll", "d3d11.dll", "version.dll",
]
# VCRUNTIME140.dll and MSVCP140.dll are deliberately absent. They ship with the Visual C++
# redistributable, not with Windows, so a library that imports them needs something installed that
# this project does not install. The Windows jobs build with /MT to avoid them, and the bundled
# libwebp shim is in the same position, depending on nothing but KERNEL32 and ucrtbase.

LINUX_ALLOWED = [
    "libc.so.6", "libm.so.6", "libdl.so.2", "librt.so.1", "libpthread.so.0",
    "libgcc_s.so.1", "libstdc++.so.6", "ld-linux*.so.*", "ld64.so.*", "libresolv.so.2",
]

MACOS_ALLOWED = [
    "/usr/lib/libSystem.B.dylib", "/usr/lib/libc++.1.dylib", "/usr/lib/libobjc.A.dylib",
    "/usr/lib/swift/libswiftCore.dylib", "/System/Library/Frameworks/*.framework/*",
    "/usr/lib/swift/*.dylib", "@rpath/libswift*.dylib", "/usr/lib/libgmalloc*.dylib",
]


def fail(message):
    print(f"::error::{message}", file=sys.stderr)
    return 2


def pe_imports(data):
    """Returns the DLL names a PE image imports, or None when the file is not a PE image."""
    if data[:2] != b"MZ":
        return None
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    if data[pe:pe + 4] != b"PE\0\0":
        return None
    _, sections, _, _, _, optional_size = struct.unpack_from("<HHIIIH", data, pe + 4)
    optional = pe + 24
    directories = optional + (112 if struct.unpack_from("<H", data, optional)[0] == 0x20B else 96)

    # Data directory entry 1 is the import table.
    import_rva = struct.unpack_from("<I", data, directories + 8)[0]
    if import_rva == 0:
        return []

    table = optional + optional_size
    segments = []
    for i in range(sections):
        entry = table + i * 40
        virtual_size, virtual_address, raw_size, raw_pointer = struct.unpack_from("<IIII", data, entry + 8)
        segments.append((virtual_address, max(virtual_size, raw_size), raw_pointer))

    def to_offset(rva):
        for virtual_address, size, raw_pointer in segments:
            if virtual_address <= rva < virtual_address + size:
                return raw_pointer + rva - virtual_address
        return None

    names = []
    descriptor = to_offset(import_rva)
    if descriptor is None:
        raise ValueError("the import directory points outside every section")
    while True:
        name_rva = struct.unpack_from("<I", data, descriptor + 12)[0]
        if name_rva == 0:
            return names
        offset = to_offset(name_rva)
        if offset is None:
            raise ValueError("an imported DLL name points outside every section")
        names.append(data[offset:data.index(b"\0", offset)].decode("ascii", "replace"))
        descriptor += 20


def elf_needed(data):
    """Returns the DT_NEEDED entries of an ELF object, or None when the file is not an ELF object."""
    if data[:4] != b"\x7fELF":
        return None
    if data[5] != 1:  # little endian only; every target we build is little endian
        raise ValueError("big endian ELF is not supported")
    is64 = data[4] == 2
    if is64:
        program_offset = struct.unpack_from("<Q", data, 0x20)[0]
        entry_size, count = struct.unpack_from("<HH", data, 0x36)
    else:
        program_offset = struct.unpack_from("<I", data, 0x1C)[0]
        entry_size, count = struct.unpack_from("<HH", data, 0x2A)

    # After p_type, an ELF64 program header widens every field to 8 bytes while an ELF32 one keeps
    # them at 4, so the offsets of p_offset, p_vaddr and p_filesz differ between the two.
    offset_of, vaddr_of, filesz_of = (8, 16, 32) if is64 else (4, 8, 16)

    dynamic, loads = None, []
    for i in range(count):
        entry = program_offset + i * entry_size
        kind = struct.unpack_from("<I", data, entry)[0]
        p_offset, p_vaddr = struct.unpack_from("<II", data, entry + offset_of)
        p_filesz = struct.unpack_from("<I", data, entry + filesz_of)[0]
        if kind == 2:  # PT_DYNAMIC
            dynamic = p_offset
        elif kind == 1:  # PT_LOAD
            loads.append((p_vaddr, p_filesz, p_offset))

    if dynamic is None:
        return []  # statically linked, so there is nothing to resolve

    def to_offset(vaddr):
        for p_vaddr, p_filesz, p_offset in loads:
            if p_vaddr <= vaddr < p_vaddr + p_filesz:
                return p_offset + vaddr - p_vaddr
        return None

    needed, strings, offset = [], None, dynamic
    step = 16 if is64 else 8
    while True:
        tag, value = struct.unpack_from("<QQ" if is64 else "<II", data, offset)
        offset += step
        if tag == 0:  # DT_NULL
            break
        if tag == 1:  # DT_NEEDED
            needed.append(value)
        elif tag == 5:  # DT_STRTAB
            strings = value
    if strings is None:
        raise ValueError("the object has dependencies but no DT_STRTAB")
    base = to_offset(strings)
    if base is None:
        raise ValueError("DT_STRTAB points outside every loadable segment")
    return [data[base + n:data.index(b"\0", base + n)].decode("ascii", "replace") for n in needed]


def macho_dylibs(data):
    """Returns the dylibs a Mach-O image loads, or None when the file is not a Mach-O image."""
    # These four constants cover the four combinations of byte order and pointer width: read as a
    # little endian integer, a little endian 32 bit image yields 0xFEEDFACE, a little endian 64 bit
    # one 0xFEEDFACF, and the two big endian images the byte swapped 0xCEFAEDFE and 0xCFFAEDFE.
    magic = struct.unpack_from("<I", data, 0)[0]
    if magic in (0xFEEDFACE, 0xFEEDFACF):
        endian, is64 = "<", magic == 0xFEEDFACF
    elif magic in (0xCEFAEDFE, 0xCFFAEDFE):
        endian, is64 = ">", magic == 0xCFFAEDFE
    else:
        return None
    count = struct.unpack_from(endian + "I", data, 16)[0]  # ncmds
    offset = 32 if is64 else 28
    # LC_LOAD_DYLIB, LC_LAZY_LOAD_DYLIB, and the three variants that carry LC_REQ_DYLD.
    kinds = {0x0C, 0x20, 0x80000018, 0x8000001F, 0x80000023}
    names = []
    for _ in range(count):
        kind, size = struct.unpack_from(endian + "II", data, offset)
        if size <= 0:
            raise ValueError("a load command declares a non positive size")
        if kind in kinds:
            name_offset = struct.unpack_from(endian + "I", data, offset + 8)[0]
            start = offset + name_offset
            names.append(data[start:data.index(b"\0", start)].decode("utf-8", "replace"))
        offset += size
    return names


def main(argv):
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    try:
        with open(argv[1], "rb") as handle:
            data = handle.read()
    except OSError as e:
        return fail(f"cannot read {argv[1]}: {e}")

    try:
        if data[:2] == b"MZ":
            names, allowed = pe_imports(data), WINDOWS_ALLOWED
        elif data[:4] == b"\x7fELF":
            names, allowed = elf_needed(data), LINUX_ALLOWED
        else:
            names, allowed = macho_dylibs(data), MACOS_ALLOWED
    except (ValueError, struct.error, IndexError) as e:
        return fail(f"cannot read the dependencies of {argv[1]}: {e}")

    if names is None:
        return fail(f"{argv[1]} is neither a PE, an ELF nor a Mach-O image")

    print(f"{argv[1]} depends on:")
    for name in names:
        print(f"  {name}")
    if not names:
        print("  (nothing)")

    def is_allowed(name):
        lowered = name.lower()
        return any(fnmatch.fnmatch(lowered, pattern.lower()) for pattern in allowed)

    self_name = os.path.basename(argv[1]).lower()
    extra = argv[2:]
    unexpected = [n for n in names
                  if os.path.basename(n).lower() != self_name and not is_allowed(n) and n not in extra]
    if unexpected:
        print("::error::unexpected dependencies, the bundled library would not load from a "
              "temporary directory:", file=sys.stderr)
        for name in unexpected:
            print(f"  {name}", file=sys.stderr)
        return 1
    print("self contained")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
