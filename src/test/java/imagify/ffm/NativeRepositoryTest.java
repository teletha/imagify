/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.ffm;

import static imagify.ffm.NativeRepository.NativeCodec.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpServer;

/**
 * Pins the shared resolution of the three native libraries: how a running JVM maps onto a release
 * asset, where the release is named, and how a fetch lands in the cache.
 *
 * <p>The download path is exercised against an {@link HttpServer} on localhost rather than the real
 * release, so it never needs a network connection and a missing release can be provoked on purpose.
 * Everything here is resolution, not loading: the returned file is never handed to
 * {@code System.load}, so the bytes can be anything.
 *
 * <p>The {@code imagify.*} properties are the only real state the resolution reads, and each test
 * restores them afterwards, so a property a test left behind cannot change the resolution of the
 * test that runs next.
 */
class NativeRepositoryTest {

    /** The properties the tests below change, each restored in {@link #restoreProperties()}. */
    private static final String[] PROPERTIES = {
            NativeRepository.PROPERTY_CACHE,
            NativeRepository.PROPERTY_DOWNLOAD,
            NativeRepository.PROPERTY_URL,
            "imagify.webp.library", "imagify.webp.bundled",
            "imagify.jpeg.library", "imagify.jpeg.bundled",
            "imagify.avif.library", "imagify.avif.bundled",
    };

    /** The value each property had before a test changed it, keyed by the property name. */
    private final Map<String, String> saved = new HashMap<>();

    private HttpServer server;

    /** The paths the release server answers, keyed by the request path. */
    private final Map<String, byte[]> assets = new HashMap<>();

    /** The paths the release server promises more bytes than it sends, keyed by the request path. */
    private final Set<String> truncated = new HashSet<>();

    /** How often the release server answered a path. */
    private final Map<String, AtomicInteger> hits = new HashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/download/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
            if (truncated.contains(path)) {
                byte[] body = assets.get(path);
                // Promise more than there is and hang up halfway, which is a download that dies in
                // the middle.
                exchange.sendResponseHeaders(200, body.length * 2);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body, 0, body.length / 2);
                }
                return;
            }
            byte[] body = assets.get(path);
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        restoreProperties();
    }

    private static void set(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private void restoreProperties() {
        for (String name : PROPERTIES) {
            String original = saved.remove(name);
            if (original == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, original);
            }
        }
    }

    private void change(String name, String value) {
        saved.putIfAbsent(name, System.getProperty(name));
        set(name, value);
    }

    // ---------------------------------------------------------------- platform and CPU mapping

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "Windows 10,        windows",
            "Windows 11,        windows",
            "Windows Server 2022, windows",
            "Mac OS X,          macos",
            "macOS,             macos",
            "darwin,            macos",
            "Linux,             linux",
    })
    @DisplayName("every spelling of a platform resolves")
    void platform(String osName, String expected) {
        assertEquals(expected, NativeRepository.platform(osName));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "amd64,    x64",
            "x86_64,   x64",
            "x64,      x64",
            "AMD64,    x64",
            "aarch64,  arm64",
            "arm64,    arm64",
            "ARM64,    arm64",
    })
    @DisplayName("every spelling of a CPU resolves")
    void cpu(String arch, String expected) {
        assertEquals(expected, NativeRepository.cpu(arch));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"FreeBSD", "SunOS", "AIX"})
    @DisplayName("an unknown platform has no asset")
    void unknownPlatforms(String osName) {
        assertNull(NativeRepository.platform(osName));
        assertNull(NativeRepository.assetName(WEBP, osName, "amd64"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"x86", "i386", "i686", "ppc64le", "s390x", "riscv64"})
    @DisplayName("an unknown CPU has no asset")
    void unknownCpus(String arch) {
        assertNull(NativeRepository.cpu(arch));
        assertNull(NativeRepository.assetName(WEBP, "Linux", arch));
    }

    @Test
    @DisplayName("a null os.name or os.arch has no asset")
    void nullPlatformProperties() {
        assertNull(NativeRepository.platform(null));
        assertNull(NativeRepository.cpu(null));
        assertNull(NativeRepository.assetName(WEBP, null, "amd64"));
        assertNull(NativeRepository.assetName(WEBP, "Linux", null));
    }

    @Test
    @DisplayName("an asset is named after the platform and the CPU")
    void anAssetIsNamedAfterPlatformAndCpu() {
        assertEquals("imagifywebp-linux-x64.so", NativeRepository.assetName(WEBP, "linux", "x64"));
        assertEquals("libjpegli-windows-arm64.dll", NativeRepository.assetName(JPEGLI, "windows", "arm64"));
        assertEquals("imagifyavif-macos-arm64.dylib", NativeRepository.assetName(AVIF, "macos", "arm64"));
    }

    @Test
    @DisplayName("an asset keeps the extension of its platform")
    void extensions() {
        assertEquals(".dll", NativeRepository.extension("windows"));
        assertEquals(".so", NativeRepository.extension("linux"));
        assertEquals(".dylib", NativeRepository.extension("macos"));
    }

    // ---------------------------------------------------------------- the release and the cache

    @Test
    @DisplayName("each format is published under its own tag")
    void tagNamesAReleasePerFormat() {
        assertTrue(NativeRepository.tag(WEBP).matches("webp-natives-\\d+(\\.\\d+)+"), NativeRepository.tag(WEBP));
        assertTrue(NativeRepository.tag(JPEGLI).matches("jpegli-natives-\\d+(\\.\\d+)+"), NativeRepository.tag(JPEGLI));
        assertTrue(NativeRepository.tag(AVIF).matches("avif-natives-\\d+(\\.\\d+)+"), NativeRepository.tag(AVIF));
    }

    @Test
    @DisplayName("the cache is filed under the format and the tag")
    void cacheFileIsFiledUnderFormatAndTag() throws IOException {
        Path cache = Files.createTempDirectory("imagify-native-cache-");
        change(NativeRepository.PROPERTY_CACHE, cache.toString());
        assertEquals(cache.resolve("webp/webp-natives-1.6.0/imagifywebp-linux-x64.so"),
                NativeRepository.cacheFile(WEBP, "webp-natives-1.6.0", "imagifywebp-linux-x64.so"));
        assertEquals(cache.resolve("avif/avif-natives-1.4.2/imagifyavif-linux-x64.so"),
                NativeRepository.cacheFile(AVIF, "avif-natives-1.4.2", "imagifyavif-linux-x64.so"));
    }

    // ---------------------------------------------------------------- resolution behaviour

    @Test
    @DisplayName("an explicitly named library wins over everything")
    void anExplicitLibraryWins() throws IOException {
        Path library = Files.createTempFile("imagify-explicit-", ".so");
        change("imagify.webp.library", library.toString());
        assertEquals(library.toAbsolutePath(), NativeRepository.resolve(WEBP));
        assertNull(NativeRepository.lastReason(WEBP), "a successful resolution must not keep a stale reason");
    }

    @Test
    @DisplayName("an explicit library that is not there is refused with a reason")
    void anExplicitLibraryThatIsMissingIsRefused() {
        change("imagify.webp.library", Path.of("there", "is", "no", "such", "library.so").toString());
        assertNull(NativeRepository.resolve(WEBP));
        assertTrue(NativeRepository.lastReason(WEBP).contains("imagify.webp.library"),
                NativeRepository.lastReason(WEBP));
    }

    @Test
    @DisplayName("turning downloads off leaves a reason, not an exception")
    void downloadsAreOffByPolicy() throws IOException {
        Path cache = Files.createTempDirectory("imagify-native-cache-");
        change(NativeRepository.PROPERTY_CACHE, cache.toString());
        change(NativeRepository.PROPERTY_DOWNLOAD, "false");
        assertNull(NativeRepository.resolve(WEBP));
        String reason = NativeRepository.lastReason(WEBP);
        assertNotNull(reason);
        assertTrue(reason.contains(NativeRepository.PROPERTY_DOWNLOAD), reason);
    }

    @Test
    @DisplayName("a fetch lands in the cache, byte for byte, and is reused")
    void downloadsFromTheReleaseIntoTheCacheOnce() throws IOException {
        String asset = NativeRepository.assetName(WEBP, System.getProperty("os.name"), System.getProperty("os.arch"));
        assumeTrue(asset != null, "skipped: this machine is not one of the six supported platforms");
        byte[] library = new byte[1024];
        for (int i = 0; i < library.length; i++) {
            library[i] = (byte) i;
        }

        Path cache = Files.createTempDirectory("imagify-native-cache-");
        change(NativeRepository.PROPERTY_CACHE, cache.toString());
        change(NativeRepository.PROPERTY_URL, "http://127.0.0.1:" + server.getAddress().getPort() + "/download");

        String path = "/download/" + NativeRepository.tag(WEBP) + "/" + asset;
        assets.put(path, library);

        Path first = NativeRepository.resolve(WEBP);
        assertNotNull(first, "resolution refused: " + NativeRepository.lastReason(WEBP));
        assertTrue(Files.isRegularFile(first), first + " does not exist");
        assertArrayEquals(library, Files.readAllBytes(first), first + " is not what the release served");
        assertTrue(first.startsWith(cache), first + " is not inside the cache");
        assertEquals(cache.resolve("webp/" + NativeRepository.tag(WEBP) + "/" + asset), first);
        assertNull(NativeRepository.lastReason(WEBP));

        Path second = NativeRepository.resolve(WEBP);
        assertEquals(first, second);
        assertEquals(1, hits.get(path).get(), "the second resolve must come from the cache, not the release");
    }

    @Test
    @DisplayName("a missing release is a reason, not an exception")
    void aMissingReleaseIsAReasonNotAnException() throws IOException {
        String asset = NativeRepository.assetName(AVIF, System.getProperty("os.name"), System.getProperty("os.arch"));
        assumeTrue(asset != null, "skipped: this machine is not one of the six supported platforms");
        Path cache = Files.createTempDirectory("imagify-native-cache-");
        change(NativeRepository.PROPERTY_CACHE, cache.toString());
        change(NativeRepository.PROPERTY_URL, "http://127.0.0.1:" + server.getAddress().getPort() + "/download");

        // Nothing is registered for AVIF, so the release server answers 404, which is the case of
        // a release that has not been published yet.
        assertNull(NativeRepository.resolve(AVIF));
        String reason = NativeRepository.lastReason(AVIF);
        assertNotNull(reason);
        assertTrue(reason.contains(NativeRepository.tag(AVIF)), reason);
        assertTrue(reason.contains("404"), reason);
    }

    @Test
    @DisplayName("a download that dies halfway cannot poison the cache")
    void aFailedDownloadLeavesNoCacheFile() throws IOException {
        String asset = NativeRepository.assetName(JPEGLI, System.getProperty("os.name"), System.getProperty("os.arch"));
        assumeTrue(asset != null, "skipped: this machine is not one of the six supported platforms");
        Path cache = Files.createTempDirectory("imagify-native-cache-");
        change(NativeRepository.PROPERTY_CACHE, cache.toString());
        change(NativeRepository.PROPERTY_URL, "http://127.0.0.1:" + server.getAddress().getPort() + "/download");

        String path = "/download/" + NativeRepository.tag(JPEGLI) + "/" + asset;
        assets.put(path, new byte[1024]);
        truncated.add(path);

        assertNull(NativeRepository.resolve(JPEGLI));
        assertNotNull(NativeRepository.lastReason(JPEGLI));
        // The partial response may only ever exist under a sibling name: a partial file on the
        // cache name would be taken for the real library by the next resolve, with no way to tell.
        assertFalse(Files.exists(NativeRepository.cacheFile(JPEGLI, NativeRepository.tag(JPEGLI), asset)),
                "a partial download left a file the cache would take for the library");
    }
}