/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.jpeg.jna;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static imagify.jpeg.jna.JpegliNativeLibrary.cpu;
import static imagify.jpeg.jna.JpegliNativeLibrary.fileName;
import static imagify.jpeg.jna.JpegliNativeLibrary.platform;
import static imagify.jpeg.jna.JpegliNativeLibrary.resourceName;
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
 * Pins the mapping from a running JVM to the bundled {@code jpegli} resource.
 *
 * <p>Nothing here needs a native library, or even a bundled one. That is the point: a wrong name
 * cannot throw at build time, it simply makes {@link JpegliNativeLibrary#extract()} return {@code null}
 * on exactly one platform, and jpegli quietly stops being used there while every test still passes —
 * because a missing library is not a failure, it is the JDK's JPEG support taking over. So the names
 * are pinned here, against the list in
 * {@code src/main/resources/imagify/jpeg/native/README.md}.
 */
class JpegliNativeLibraryTest {

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            "Windows 10,        amd64,  libjpegli-windows-x64.dll",
            "Windows 11,        x86_64, libjpegli-windows-x64.dll",
            "Windows Server 2022, aarch64, libjpegli-windows-arm64.dll",
            "Linux,             amd64,  libjpegli-linux-x64.so",
            "Linux,             aarch64, libjpegli-linux-arm64.so",
            "Mac OS X,          x86_64, libjpegli-macos-x64.dylib",
            "Mac OS X,          aarch64, libjpegli-macos-arm64.dylib",
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
        assertEquals("jpegli.dll", fileName("windows"));
        assertEquals("libjpegli.so", fileName("linux"));
        assertEquals("libjpegli.dylib", fileName("macos"));
    }

    @Test
    @DisplayName("the unpacked name is not the one a system libjpeg would claim")
    void unpackedNameAvoidsLibJpeg() {
        // jpegli's own libjpeg62 library has the soname libjpeg.so.62, which is also the soname of
        // every system libjpeg on Linux. Two of those mapped in one process means whichever was
        // mapped first wins the symbols, so the name is deliberately not one of them.
        for (String platform : new String[] {"linux", "macos", "windows"}) {
            assertNotEquals("libjpeg.so.62", fileName(platform));
            assertNotEquals("libjpeg.so", fileName(platform));
            assertNotEquals("libjpeg.dylib", fileName(platform));
            assertNotEquals("jpeg.dll", fileName(platform));
        }
        assertEquals("jpegli", JpegliLibrary.LIBRARY_NAME);
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
        assertEquals("/imagify/jpeg/native/", JpegliNativeLibrary.RESOURCE_ROOT);
    }

    @Test
    @DisplayName("a bundled resource is copied out of the jar under the name the linker is given")
    void unpacksToTheRightName() throws IOException {
        Path unpacked = JpegliNativeLibrary.unpack("fake-library.dll", "jpegli.dll");
        assertNotNull(unpacked, "the test resource was not found on the classpath");
        assertTrue(unpacked.isAbsolute(), unpacked + " must be absolute");
        assertEquals("jpegli.dll", unpacked.getFileName().toString());
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
        Path first = JpegliNativeLibrary.unpack("fake-library.dll", "jpegli.dll");
        Path second = JpegliNativeLibrary.unpack("fake-library.dll", "jpegli.dll");
        assertNotEquals(first, second);
        assertNotEquals(first.getParent(), second.getParent());
    }

    @Test
    @DisplayName("a resource that is not in the jar is not an error")
    void missingResource() {
        assertNull(JpegliNativeLibrary.unpack("there-is-no-such-library.dll", "jpegli.dll"));
    }

    @Test
    @DisplayName("the library bundled for this platform, if any, is a real shared library")
    void bundledLibraryIsNotAPointerFile() {
        // Absent is the normal state until the jpegli-natives workflow fills the directory in. What
        // must never happen is the file being there but useless: a git LFS pointer or a truncated
        // checkout would fail at System.load time with a message that points at the wrong thing.
        String resource = resourceName(System.getProperty("os.name"), System.getProperty("os.arch"));
        var url = JpegliNativeLibrary.class.getResource(JpegliNativeLibrary.RESOURCE_ROOT + resource);
        assumeTrue(url != null, "skipped: no jpegli is bundled for " + resource);

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
