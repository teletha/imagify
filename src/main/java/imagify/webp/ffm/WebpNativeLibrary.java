/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.webp.ffm;

import static java.lang.System.*;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;

import imagify.ffm.NativeRepository;
import imagify.ffm.NativeRepository.NativeCodec;

/**
 * Locates the {@code libwebp} based shared library that backs this codec and fetches it on first
 * use.
 *
 * <p>The library is not inside the jar. It is built by the {@code webp-natives} workflow and
 * published as a GitHub release whose tag is the only thing the jar knows about it:
 * {@value #RESOURCE_ROOT}native.properties names the release. {@link NativeRepository} downloads
 * the file for the platform the codec runs on into a local cache the first time one is asked for,
 * and it is loaded from there by its absolute path using {@link System#load(String)}, which makes it
 * visible to {@link java.lang.foreign.Linker#nativeLinker()}.
 *
 * <p>Nothing here ever throws. An unsupported platform, a release that has not been published and a
 * download that fails all mean "no managed library", and the reason is kept for the codec to report;
 * a missing managed library leaves the codec free to fall back to a library installed on the
 * system.
 *
 * <p>See {@code native/webp/CMakeLists.txt} for how the libraries are built.
 */
final class WebpNativeLibrary {

    private static final Logger log = getLogger(WebpNativeLibrary.class.getName());

    /** Classpath directory that holds the per format release metadata. */
    static final String RESOURCE_ROOT = "/imagify/webp/native/";

    /**
     * Set this system property to {@code false} to ignore the managed library and always look for a
     * {@code libwebp} based one installed on the system.
     */
    static final String BUNDLED_PROPERTY = "imagify.webp.bundled";

    private static final Object LOCK = new Object();

    private static volatile boolean resolved;

    private static volatile Path extracted;

    private static volatile String reason;

    private WebpNativeLibrary() {
        // utility class
    }

    /**
     * Resolves the managed shared library for the current platform, downloading it when it is not in
     * the cache, at most once.
     *
     * @return the absolute path of the library, or {@code null} when this platform has no library,
     *         the managed library is disabled or it could not be obtained
     */
    static Path extract() {
        Path path = extracted;
        if (resolved) {
            return path;
        }
        synchronized (LOCK) {
            if (resolved) {
                return extracted;
            }
            extracted = resolve();
            resolved = true;
            return extracted;
        }
    }

    /** @return why the last {@link #extract()} refused, or {@code null} when it found a library */
    static String reason() {
        return reason;
    }

    private static Path resolve() {
        if (!Boolean.parseBoolean(System.getProperty(BUNDLED_PROPERTY, "true"))) {
            return refuse("the managed WebP library is turned off by -D" + BUNDLED_PROPERTY + "=false");
        }
        Path path = NativeRepository.resolve(NativeCodec.WEBP);
        if (path == null) {
            return refuse(NativeRepository.lastReason(NativeCodec.WEBP));
        }
        return path;
    }

    private static Path refuse(String refuseReason) {
        reason = refuseReason;
        log.log(Level.DEBUG, "no managed WebP library: {0}", refuseReason);
        return null;
    }
}