/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.avif.jna;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static imagify.avif.jna.AvifNativeLibrary.cpu;
import static imagify.avif.jna.AvifNativeLibrary.fileName;
import static imagify.avif.jna.AvifNativeLibrary.platform;
import static imagify.avif.jna.AvifNativeLibrary.resourceName;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the mapping from a running JVM to the bundled {@code libavif} resource.
 *
 * <p>Nothing here needs a native library, or even a bundled one. That is the point: a wrong name
 * cannot throw at build time, it simply makes {@link AvifNativeLibrary#extract()} return {@code null}
 * on exactly one platform, and AVIF support quietly disappears there while every test still passes.
 * So the names are pinned here, against the list in
 * {@code src/main/resources/imagify/avif/native/README.md}.
 */
class AvifNativeLibraryTest {

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            "Windows 10,        amd64,  libavif-windows-x64.dll",
            "Windows 11,        x86_64, libavif-windows-x64.dll",
            "Windows Server 2022, aarch64, libavif-windows-arm64.dll",
            "Linux,             amd64,  libavif-linux-x64.so",
            "Linux,             aarch64, libavif-linux-arm64.so",
            "Mac OS X,          x86_64, libavif-macos-x64.dylib",
            "Mac OS X,          aarch64, libavif-macos-arm64.dylib",
    })
    @DisplayName("every supported platform resolves to a bundled resource")
    void supported(String osName, String arch, String expected) {
        assertEquals(expected, resourceName(osName, arch));
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
    @DisplayName("the library is unpacked under the name the dynamic linker knows it by")
    void unpackedName() {
        assertEquals("avif.dll", fileName("windows"));
        assertEquals("libavif.so", fileName("linux"));
        assertEquals("libavif.dylib", fileName("macos"));
    }

    @Test
    @DisplayName("the unpacked name keeps the extension of the resource it came from")
    void unpackedNameMatchesResource() {
        String[][] platforms = {
                {"Windows 10", "amd64"},
                {"Windows 11", "aarch64"},
                {"Linux", "amd64"},
                {"Linux", "aarch64"},
                {"Mac OS X", "x86_64"},
                {"Mac OS X", "aarch64"},
        };
        for (String[] pair : platforms) {
            String resource = resourceName(pair[0], pair[1]);
            String unpacked = fileName(platform(pair[0]));
            assertEquals(resource.substring(resource.lastIndexOf('.')), unpacked.substring(unpacked.lastIndexOf('.')),
                    resource);
        }
    }

    @Test
    @DisplayName("the resource root is the directory the README documents")
    void resourceRoot() {
        assertEquals("/imagify/avif/native/", AvifNativeLibrary.RESOURCE_ROOT);
    }

    @Test
    @DisplayName("a bundled resource is copied out of the jar under the name the linker is given")
    void unpacksToTheRightName() throws IOException {
        Path unpacked = AvifNativeLibrary.unpack("fake-library.dll", "avif.dll");
        assertNotNull(unpacked, "the test resource was not found on the classpath");
        assertTrue(unpacked.isAbsolute(), unpacked + " must be absolute");
        assertEquals("avif.dll", unpacked.getFileName().toString());
        assertTrue(Files.isRegularFile(unpacked), unpacked + " does not exist");

        // 0..255 round tripping proves the copy is byte exact and not, say, truncated by a buffer.
        byte[] expected = new byte[256];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) i;
        }
        assertArrayEquals(expected, Files.readAllBytes(unpacked));
    }

    @Test
    @DisplayName("each unpack gets its own directory, so two libraries cannot collide")
    void unpacksIntoAFreshDirectory() {
        Path first = AvifNativeLibrary.unpack("fake-library.dll", "avif.dll");
        Path second = AvifNativeLibrary.unpack("fake-library.dll", "avif.dll");
        assertNotEquals(first, second);
        assertNotEquals(first.getParent(), second.getParent());
    }

    @Test
    @DisplayName("a resource that is not in the jar is not an error")
    void missingResource() {
        assertNull(AvifNativeLibrary.unpack("there-is-no-such-library.dll", "avif.dll"));
    }

    @Test
    @DisplayName("the library bundled for this platform, if any, is a real shared library")
    void bundledLibraryIsNotAPointerFile() {
        // Absent is the normal state until the avif-natives workflow fills the directory in. What
        // must never happen is the file being there but useless: a git LFS pointer or a truncated
        // checkout would fail at System.load time with a message that points at the wrong thing.
        String resource = resourceName(System.getProperty("os.name"), System.getProperty("os.arch"));
        var url = AvifNativeLibrary.class.getResource(AvifNativeLibrary.RESOURCE_ROOT + resource);
        assumeTrue(url != null, "skipped: no libavif is bundled for " + resource);

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
