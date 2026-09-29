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

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;

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
 * <p>Nothing here ever throws. A platform with no bundled library, a missing resource, a library
 * that will not load and a full temporary directory all mean "unavailable", and the reason is kept
 * for {@link AvifCodec#getUnavailableReason()} to report. That is the bargain the {@code ImageIO}
 * plug-ins have always made: a codec that cannot reach its library stands aside rather than
 * breaking {@code ImageIO} for every other format.
 */
final class AvifLoader {

    private static final Logger log = getLogger(AvifLoader.class.getName());

    /**
     * Set this system property to {@code false} to ignore the bundled library and load a
     * {@code libavif} from the system instead.
     */
    static final String BUNDLED_PROPERTY = "imagify.avif.bundled";

    /**
     * Where the bundled libraries live inside the jar.
     *
     * <p>It is a constant rather than something assembled at each use so that the directory the
     * resources are in can be pinned by a test against the one the README documents. A resource root
     * that drifts makes every platform unavailable at run time and no test fails.
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
     * that cannot be acted on: a missing resource, a platform with no bundled binary and a library
     * that would not load all look identical from the outside otherwise. A null reason here is a
     * loader path that returns without recording why, which is a bug in the path rather than a
     * situation the caller can guess at.
     */
    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.toString() : message;
    }

    private static AvifShim bind() {
        String platform = platform(System.getProperty("os.name"));
        String cpu = cpu(System.getProperty("os.arch"));
        if (platform == null || cpu == null) {
            return refuse("this jar bundles no libavif for " + System.getProperty("os.name") + " on "
                    + System.getProperty("os.arch") + ", and imagify supports Windows, macOS and Linux"
                    + " on x64 and arm64");
        }
        if (!Boolean.parseBoolean(System.getProperty(BUNDLED_PROPERTY, "true"))) {
            return refuse("the bundled libavif is turned off by -D" + BUNDLED_PROPERTY + "=false");
        }
        String name = shimName(platform, cpu);
        Path directory;
        try {
            directory = Files.createTempDirectory("imagify-avif-");
        } catch (IOException e) {
            return refuse("cannot create a directory to unpack " + name + " into: " + describe(e));
        }
        if (unpack(directory, name) == null) {
            return refuse(name + " is not in this jar. The six of them are built by the avif-natives "
                    + "workflow and belong in src/main/resources/imagify/avif/native/, one per platform.");
        }
        // One library and nothing beside it, so there is no order to get right and no sibling to
        // fail to find. It is unpacked under the name it has in the jar, which is also the name the
        // loader is asked for, so nothing is renamed on the way out.
        Path file = directory.resolve(name);
        System.load(file.toString());
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
    private static AvifShim refuse(String reason) {
        unavailable = reason;
        log.log(Level.DEBUG, "libavif is not available: {0}", reason);
        return null;
    }

    /**
     * Copies a classpath resource into a directory on disk, because a shared library cannot be
     * mapped straight out of a jar and {@code System.load} only takes an absolute path.
     *
     * @param directory where to put it
     * @param resource the file name below {@code /imagify/avif/native/}, which is also the name to
     *        give the copy
     * @return the absolute path of the copy, or {@code null} when there is no such resource
     */
    static Path unpack(Path directory, String resource) {
        try (var in = AvifLoader.class.getResourceAsStream(RESOURCE_ROOT + resource)) {
            if (in == null) {
                return null;
            }
            Path file = directory.resolve(resource);
            Files.copy(in, file);
            return file.toAbsolutePath();
        } catch (Exception e) {
            log.log(Level.DEBUG, "cannot unpack " + resource, e);
            return null;
        }
    }

    /**
     * The one file this loader wants for a platform.
     *
     * <p>It is the shim rather than a libavif, because libavif is linked into it. An earlier version
     * wanted two files, a libavif and the shim beside it, and the reason it no longer does is in the
     * class documentation: the shim recorded libavif's SONAME and the jar could not ship a file by
     * that name.
     *
     * @param osName the value of the {@code os.name} system property
     * @param arch the value of the {@code os.arch} system property
     * @return the resource name for that platform, or {@code null} when there is none
     */
    static String resourceName(String osName, String arch) {
        String platform = platform(osName);
        String cpu = cpu(arch);
        return platform == null || cpu == null ? null : shimName(platform, cpu);
    }

    /**
     * @param osName the value of the {@code os.name} system property
     * @return the platform name the resources are filed under, or {@code null} for one that has none
     */
    static String platform(String osName) {
        if (osName == null) {
            return null;
        }
        String name = osName.toLowerCase(java.util.Locale.ROOT);
        if (name.contains("windows")) {
            return "windows";
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return "macos";
        }
        if (name.contains("linux")) {
            return "linux";
        }
        return null;
    }

    /**
     * @param arch the value of the {@code os.arch} system property
     * @return the architecture name the resources are filed under, or {@code null} for one that has
     *         none
     */
    static String cpu(String arch) {
        if (arch == null) {
            return null;
        }
        return switch (arch.toLowerCase(java.util.Locale.ROOT)) {
            case "amd64", "x86_64", "x64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> null;
        };
    }

    /**
     * The name of the shim for a platform, which is {@code imagifyavif-<platform>-<cpu>.<ext>}.
     *
     * <p>It keeps the platform and the architecture in the name so that six of them can sit in one
     * directory in one jar, and so that a name in a bug report says which build it came from.
     *
     * @param platform one of the values {@link #platform(String)} returns
     * @param cpu one of the values {@link #cpu(String)} returns
     * @return the resource name for that platform
     */
    static String shimName(String platform, String cpu) {
        return "imagifyavif-" + platform + "-" + cpu + fileName(platform);
    }

    /**
     * @param platform one of the values {@link #platform(String)} returns
     * @return the file extension a shared library has on that platform
     */
    static String fileName(String platform) {
        return switch (platform) {
            case "windows" -> ".dll";
            case "macos" -> ".dylib";
            default -> ".so";
        };
    }
}
