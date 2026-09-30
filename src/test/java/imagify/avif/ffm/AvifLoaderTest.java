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

import static imagify.ffm.NativeRepository.NativeCodec.AVIF;
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
 * build time, it simply makes {@link AvifLoader#shim()} return {@code null} on exactly one platform,
 * and AVIF support quietly disappears there while every test still passes. So the names are pinned
 * here, against the files the avif-natives workflow publishes under the tag in
 * {@code src/main/resources/imagify/avif/native/native.properties}.
 *
 * <p>There is one asset rather than two because the FFM binding is a shim: a small module that
 * imports the {@code avif*} symbols from libavif, which is linked into it, so nothing has to sit
 * beside it. A binding that talked to libavif directly would have only the first of these, which is
 * the whole of what the shim costs.
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
    @DisplayName("every supported platform resolves to one release asset")
    void supported(String osName, String arch, String expected) {
        assertEquals(expected, NativeRepository.assetName(AVIF, osName, arch));
    }

    @Test
    @DisplayName("the asset is the shim, not a libavif to sit beside it")
    void oneFileNotTwo() {
        // libavif is linked into the shim. When it was shipped beside the shim instead, the shim
        // recorded libavif's SONAME and the two Unix loaders looked for a file the release did not
        // have, because its files are named for the platform and the architecture. Three ways of
        // renaming it were tried in the natives workflow before the question was removed rather than
        // answered.
        String name = NativeRepository.assetName(AVIF, "Linux", "amd64");
        assertNotNull(name);
        assertTrue(name.startsWith("imagifyavif-"), name);
        assertFalse(name.contains("libavif"), name + " suggests a second file to resolve at run time");
    }

    @Test
    @DisplayName("the resource root holds the properties that name the release")
    void resourceRoot() {
        assertEquals("/imagify/avif/native/", AvifLoader.RESOURCE_ROOT);
        assertNotNull(AvifLoader.class.getResource(AvifLoader.RESOURCE_ROOT + "native.properties"),
                "native.properties is not where the loader looks for it");
        String tag = NativeRepository.tag(AVIF);
        assertNotNull(tag, "native.properties names no release, so the runtime can never fetch a library");
        assertEquals("avif-natives-1.4.2", tag);
    }

    @Test
    @DisplayName("AVIF being unavailable is always explained")
    void unavailableIsNeverUnexplained() {
        // One file for one platform and a release missing another is the state a build that ran the
        // natives workflow for some platforms but not others leaves behind, and from the outside
        // that is indistinguishable from any other way of failing to load. So every way of giving
        // up has to leave a reason, and this is what catches the ones that do not: it is a no-op
        // where the library loads, and a failure on the platform where it does not.
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
}