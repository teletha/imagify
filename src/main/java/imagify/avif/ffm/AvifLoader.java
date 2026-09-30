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

import static java.lang.System.*;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;

import imagify.ffm.NativeRepository;
import imagify.ffm.NativeRepository.NativeCodec;

/**
 * Loads the {@code imagifyavif} shared library and binds it, lazily and at most once.
 *
 * <p>One file per platform. It is a shim over libavif with libavif and libaom linked into it, so
 * there is nothing beside it for a loader to have to find, which is what makes it the same shape as
 * the WebP shim next door. An earlier version shipped libavif beside the shim and imported it
 * instead, and that needs the shim's import table to name a file the jar can actually ship: the two
 * Unix loaders resolve a dependency by the SONAME of the library it was linked against, which
 * libavif sets to {@code libavif.so.16} or {@code libavif.16.dylib}, and the jar's files are named
 * for the platform and the architecture. Linking it in removes the question rather than answering it.
 *
 * <p>The file is not inside the jar. It is built by the {@code avif-natives} workflow and published
 * as a GitHub release whose tag is the only thing the jar knows about it:
 * {@value #RESOURCE_ROOT}native.properties names the release, and {@link NativeRepository} downloads
 * the file for the platform the codec runs on into a local cache the first time one is asked for.
 *
 * <p>Nothing here ever throws. A platform with no release asset, a release that has not been
 * published, a download that fails and a library that will not load all mean "unavailable", and the
 * reason is kept for {@link AvifCodec#getUnavailableReason()} to report. That is the bargain the
 * {@code ImageIO} plug-ins have always made: a codec that cannot reach its library stands aside
 * rather than breaking {@code ImageIO} for every other format.
 */
final class AvifLoader {

    private static final Logger log = getLogger(AvifLoader.class.getName());

    /**
     * Where the release metadata lives inside the jar.
     *
     * <p>It is a constant rather than something assembled at each use so that the directory the
     * properties are in can be pinned by a test against the one the README documents. A resource
     * root that drifts makes every platform unavailable at run time and no test fails.
     */
    static final String RESOURCE_ROOT = "/imagify/avif/native/";

    private static final Object LOCK = new Object();

    private static volatile AvifShim shim;
    private static volatile String unavailable;

    private AvifLoader() {
        // utility class
    }

    /**
     * @return the bound shim, or {@code null} when it could not be loaded
     */
    static AvifShim shim() {
        AvifShim bound = shim;
        if (bound != null) {
            return bound;
        }
        synchronized (LOCK) {
            if (shim != null) {
                return shim;
            }
            try {
                shim = bind();
            } catch (Throwable t) {
                // An UnsatisfiedLinkError is the ordinary case of a library for another platform,
                // and a SecurityException the ordinary case of a restricted runtime. Both mean the
                // same thing to a caller, which is that AVIF is not available.
                unavailable = describe(t);
                log.log(Level.DEBUG, "libavif is not available", t);
            }
            return shim;
        }
    }

    /** @return why the shim could not be loaded, or {@code null} when it was */
    static String unavailableReason() {
        shim();
        return unavailable;
    }

    /**
     * A reason a caller can act on, from an exception or from a plain refusal to load.
     *
     * <p>Every way of failing says something, and "AVIF is not available" with no more is the one
     * that cannot be acted on: an unpublished release, a platform with no release asset and a
     * library that would not load all look identical from the outside otherwise. A null reason here
     * is a loader path that returns without recording why, which is a bug in the path rather than a
     * situation the caller can guess at.
     */
    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.toString() : message;
    }

    private static AvifShim bind() {
        Path file = NativeRepository.resolve(NativeCodec.AVIF);
        if (file == null) {
            return refuse(NativeRepository.lastReason(NativeCodec.AVIF));
        }
        try {
            System.load(file.toString());
        } catch (Throwable t) {
            return refuse("cannot load " + file + ": " + describe(t));
        }
        log.log(Level.DEBUG, "loaded the AVIF shim from {0}", file);
        return new AvifShim(SymbolLookup.loaderLookup());
    }

    /**
     * Records why there is no shim and reports that there is none.
     *
     * <p>Always {@code null}, so every caller that gives up has to say why on the way out. A loader
     * that returned null without saying anything is how a jar ends up reporting
     * {@code "libavif is not available: null"} to someone who cannot tell what to do about it.
     */
    private static AvifShim refuse(String refuseReason) {
        unavailable = refuseReason;
        log.log(Level.DEBUG, "libavif is not available: {0}", refuseReason);
        return null;
    }
}