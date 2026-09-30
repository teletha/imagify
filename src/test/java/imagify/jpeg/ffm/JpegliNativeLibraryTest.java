/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.jpeg.ffm;

import static imagify.ffm.NativeRepository.NativeCodec.JPEGLI;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import imagify.ffm.NativeRepository;

/**
 * Pins the mapping from a running JVM to the release asset it needs, and the tag that names the
 * release holding it.
 *
 * <p>Nothing here needs a native library. That is the point: a wrong asset name cannot throw at
 * build time, it simply makes {@link JpegliNativeLibrary#extract()} return {@code null} on exactly
 * one platform, and jpegli quietly stops being used there while every test still passes — because a
 * missing library is not a failure, it is the JDK's JPEG support taking over. So the names are
 * pinned here, against the files the jpegli-natives workflow publishes under the tag in
 * {@code src/main/resources/imagify/jpeg/native/native.properties}.
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
    @DisplayName("every supported platform resolves to one release asset")
    void supported(String osName, String arch, String expected) {
        assertEquals(expected, NativeRepository.assetName(JPEGLI, osName, arch));
    }

    @Test
    @DisplayName("the asset is not one a system libjpeg would claim")
    void assetNameAvoidsLibJpeg() {
        // jpegli's own libjpeg62 library has the soname libjpeg.so.62, which is also the soname of
        // every system libjpeg on Linux. The name of the asset shows up in bug reports and would
        // read as a system libjpeg to anyone who knows one, so it is deliberately none of the names
        // a libjpeg installs itself as.
        String[][] platforms = {
                {"Windows 10", "amd64"},
                {"Windows 11", "aarch64"},
                {"Linux", "amd64"},
                {"Linux", "aarch64"},
                {"Mac OS X", "x86_64"},
                {"Mac OS X", "aarch64"},
        };
        for (String[] pair : platforms) {
            String asset = NativeRepository.assetName(JPEGLI, pair[0], pair[1]);
            assertTrue(asset.startsWith("libjpegli-"), asset);
            for (String claimed : new String[] {"libjpeg.so.62", "libjpeg.so", "libjpeg.dylib", "jpeg.dll"}) {
                assertNotEquals(claimed, asset, asset + " reads as a system libjpeg");
            }
        }
        assertEquals("jpegli", JpegliLibrary.LIBRARY_NAME);
    }

    @Test
    @DisplayName("the asset keeps the extension of its platform")
    void assetKeepsTheExtension() {
        String[][] platforms = {
                {"Windows 10", "amd64"},
                {"Windows 11", "aarch64"},
                {"Linux", "amd64"},
                {"Linux", "aarch64"},
                {"Mac OS X", "x86_64"},
                {"Mac OS X", "aarch64"},
        };
        for (String[] pair : platforms) {
            String asset = NativeRepository.assetName(JPEGLI, pair[0], pair[1]);
            assertEquals(NativeRepository.extension(NativeRepository.platform(pair[0])),
                    asset.substring(asset.lastIndexOf('.')), asset);
        }
    }

    @Test
    @DisplayName("the resource root holds the properties that name the release")
    void resourceRoot() {
        assertEquals("/imagify/jpeg/native/", JpegliNativeLibrary.RESOURCE_ROOT);
        assertNotNull(JpegliNativeLibrary.class.getResource(JpegliNativeLibrary.RESOURCE_ROOT + "native.properties"),
                "native.properties is not where the loader looks for it");
        String tag = NativeRepository.tag(JPEGLI);
        assertNotNull(tag, "native.properties names no release, so the runtime can never fetch a library");
        assertEquals("jpegli-natives-0.12.0", tag);
    }
}