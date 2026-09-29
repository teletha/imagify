/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.avif.ffm;

import static imagify.avif.ffm.AvifLoader.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the mapping from a running JVM to the two bundled resources it needs.
 *
 * <p>Nothing here needs a native library, or even a bundled one. That is the point: a wrong name
 * cannot throw at build time, it simply makes {@link AvifLoader#shim()} return {@code null} on
 * exactly one platform, and AVIF support quietly disappears there while every test still passes. So
 * the names are pinned here, against the list in
 * {@code src/main/resources/imagify/avif/native/README.md}.
 *
 * <p>There are two names rather than one because the FFM binding is a shim: a small module that
 * imports the {@code avif*} symbols from libavif, and both have to be unpacked into one directory for
 * the loader to find one from the other. A binding that talked to libavif directly would have only
 * the first of these, which is the whole of what the shim costs.
 */
class AvifLoaderTest {

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            "Windows 10,        amd64,  imagifyavif-windows-x64.dll",
            "Windows 11,        x86_64, imagifyavif-windows-x64.dll",
            "Windows Server 2022, aarch64, imagifyavif-windows-arm64.dll",
            "Linux,             amd64,  imagifyavif-linux-x64.so",
            "Linux,             aarch64, imagifyavif-linux-arm64.so",
            "Mac OS X,          x86_64, imagifyavif-macos-x64.dylib",
            "Mac OS X,          aarch64, imagifyavif-macos-arm64.dylib",
    })
    @DisplayName("every supported platform resolves to one bundled file")
    void supported(String osName, String arch, String expected) {
        assertEquals(expected, resourceName(osName, arch));
    }

    @Test
    @DisplayName("the bundled file is the shim, not a libavif to sit beside it")
    void oneFileNotTwo() {
        // libavif is linked into the shim. When it was shipped beside the shim instead, the shim
        // recorded libavif's SONAME and the two Unix loaders looked for a file the jar did not have,
        // because its files are named for the platform and the architecture. Three ways of renaming
        // it were tried in the natives workflow before the question was removed rather than answered.
        String name = resourceName("Linux", "amd64");
        assertNotNull(name);
        assertTrue(name.startsWith("imagifyavif-"), name);
        assertFalse(name.contains("libavif"), name + " suggests a second file to resolve at run time");
    }

    @ParameterizedTest(name = "os.name={0}")
    @ValueSource(strings = {"Windows 10", "windows 11", "Windows Server 2022"})
    @DisplayName("every spelling of Windows resolves")
    void windowsSpellings(String osName) {
        assertEquals("windows", platform(osName));
    }

    @ParameterizedTest(name = "os.name={0}")
    @ValueSource(strings = {"Mac OS X", "macOS", "darwin", "Mac OS"})
    @DisplayName("every spelling of macOS resolves")
    void macSpellings(String osName) {
        assertEquals("macos", platform(osName));
    }

    @ParameterizedTest(name = "os.arch={0}")
    @ValueSource(strings = {"amd64", "x86_64", "x64", "AMD64"})
    @DisplayName("every spelling of x64 resolves")
    void x64Spellings(String arch) {
        assertEquals("x64", cpu(arch));
    }

    @ParameterizedTest(name = "os.arch={0}")
    @ValueSource(strings = {"aarch64", "arm64", "ARM64"})
    @DisplayName("every spelling of arm64 resolves")
    void arm64Spellings(String arch) {
        assertEquals("arm64", cpu(arch));
    }

    @ParameterizedTest(name = "os.name={0}")
    @ValueSource(strings = {"FreeBSD", "SunOS", "AIX"})
    @DisplayName("an unknown operating system is not silently mapped onto Linux")
    void unknownOs(String osName) {
        assertNull(platform(osName));
        assertNull(resourceName(osName, "amd64"));
    }

    @ParameterizedTest(name = "os.arch={0}")
    @ValueSource(strings = {"x86", "i386", "i686", "ppc64le", "s390x", "riscv64"})
    @DisplayName("a CPU without a bundled library is not silently mapped onto another one")
    void unknownArch(String arch) {
        assertNull(cpu(arch));
        assertNull(resourceName("Linux", arch));
    }

    @Test
    @DisplayName("a null os.name or os.arch is not an error")
    void nullProperties() {
        assertNull(platform(null));
        assertNull(cpu(null));
        assertNull(resourceName(null, "amd64"));
        assertNull(resourceName("Linux", null));
    }

    @Test
    @DisplayName("a shared library has the extension of its platform")
    void extensions() {
        assertEquals(".dll", fileName("windows"));
        assertEquals(".so", fileName("linux"));
        assertEquals(".dylib", fileName("macos"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "Windows 10, amd64",
            "Windows 11, aarch64",
            "Linux, amd64",
            "Linux, aarch64",
            "Mac OS X, x86_64",
            "Mac OS X, aarch64",
    })
    @DisplayName("the resource root is the directory the README documents")
    void resourceRoot(String osName, String arch) {
        assertEquals("/imagify/avif/native/", AvifLoader.RESOURCE_ROOT);
        assertNotNull(AvifLoader.class.getResource(AvifLoader.RESOURCE_ROOT + resourceName(osName, arch)),
                resourceName(osName, arch) + " is not where the loader looks for it");
    }

    @Test
    @DisplayName("a resource is copied into the directory it was named for, byte for byte")
    void unpackIsByteExact() throws IOException {
        Path directory = Files.createTempDirectory("imagify-avif-test-");
        Path unpacked = AvifLoader.unpack(directory, "fake-library.dll");
        assertNotNull(unpacked, "the test resource was not found on the classpath");
        assertTrue(unpacked.isAbsolute(), unpacked + " must be absolute");
        // The name is the load bearing part: the loader resolves a module's imports against the files
        // beside it, so a copy that came out under any other name would not be found.
        assertEquals("fake-library.dll", unpacked.getFileName().toString());
        assertTrue(Files.isRegularFile(unpacked), unpacked + " does not exist");

        // 0..255 round tripping proves the copy is byte exact and not, say, truncated by a buffer.
        byte[] expected = new byte[256];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) i;
        }
        assertArrayEquals(expected, Files.readAllBytes(unpacked));
    }

    @Test
    @DisplayName("each load gets its own directory, so two loads cannot collide")
    void unpacksIntoAFreshDirectory() throws IOException {
        Path one = Files.createTempDirectory("imagify-avif-test-");
        Path two = Files.createTempDirectory("imagify-avif-test-");
        Path first = AvifLoader.unpack(one, "fake-library.dll");
        Path second = AvifLoader.unpack(two, "fake-library.dll");
        assertNotEquals(first, second);
        assertNotEquals(first.getParent(), second.getParent());
    }

    @Test
    @DisplayName("a resource that is not in the jar is not an error")
    void missingResource() throws IOException {
        assertNull(AvifLoader.unpack(Files.createTempDirectory("imagify-avif-test-"),
                "there-is-no-such-library.dll"));
    }

    @Test
    @DisplayName("AVIF being unavailable is always explained")
    void unavailableIsNeverUnexplained() {
        // A shim for one platform and a libavif for six is the state a build that ran the natives
        // workflow for the library but not for the shim leaves behind, and from the outside that is
        // indistinguishable from any other way of failing to load. So every way of giving up has to
        // leave a reason, and this is what catches the ones that do not: it is a no-op where the
        // library loads, and a failure on the platform where it does not.
        //
        // The other refusals cannot be provoked from here to be checked the same way, because the
        // loader binds once and caches it, so a system property set afterwards changes nothing. That
        // is the intended behaviour rather than a limitation of the test.
        if (!AvifCodec.isAvailable()) {
            String reason = AvifCodec.getUnavailableReason();
            assertNotNull(reason, "AVIF is not available and getUnavailableReason() returned null");
            assertFalse(reason.isBlank(), "AVIF is not available and the reason is blank");
        }
    }

    @Test
    @DisplayName("the bundled file for this platform, if any, is a real binary")
    void bundledResourceIsNotAPointerFile() {
        // Absent is the normal state until the avif-natives workflow fills the directory in. What
        // must never happen is a file being there but useless: a git LFS pointer or a truncated
        // checkout would fail at System.load time with a message that points at the wrong thing.
        String resource = resourceName(System.getProperty("os.name"), System.getProperty("os.arch"));
        assumeTrue(resource != null, "skipped: no bundled library for this platform");
        var url = AvifLoader.class.getResource(AvifLoader.RESOURCE_ROOT + resource);
        assumeTrue(url != null, "skipped: " + resource + " is not in this jar");
        try (var in = url.openStream()) {
            byte[] head = in.readNBytes(64);
            assertTrue(head.length == 64, () -> resource + " is only " + head.length + " bytes long");
            assertFalse(head.length >= 64 && head[0] == 'v' && head[1] == 'e' && head[2] == 'r' && head[3] == 's',
                    () -> resource + " is a git LFS pointer, not a binary");
        } catch (IOException e) {
            fail(e);
        }
    }
}
