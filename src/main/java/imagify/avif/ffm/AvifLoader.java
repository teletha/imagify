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
 * Loads the C shim that fronts {@code libavif} and binds it, lazily and at most once.
 *
 * <p>Two libraries are involved, and both the order and the directory they share matter.
 * {@code libavif} is a 10 MB shared library that ships in the jar, and the shim is a small module
 * that imports the {@code avif*} symbols from it. A module's imports are resolved when the module
 * is loaded rather than at first use, and they are resolved against the files sitting beside the
 * module being loaded, so libavif has to be mapped first and has to keep the file name the shim's
 * import table records. That is why both are unpacked into one directory under the names they are
 * stored in the jar under, rather than one apiece into a directory apiece.
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
                unavailable = t.getMessage() == null ? t.toString() : t.getMessage();
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

    private static AvifShim bind() {
        String platform = platform(System.getProperty("os.name"));
        String cpu = cpu(System.getProperty("os.arch"));
        if (platform == null || cpu == null) {
            log.log(Level.DEBUG, "no bundled libavif for this platform");
            return null;
        }
        if (!Boolean.parseBoolean(System.getProperty(BUNDLED_PROPERTY, "true"))) {
            log.log(Level.DEBUG, "the bundled libavif is disabled by -D{0}=false", BUNDLED_PROPERTY);
            return null;
        }
        String avif = "libavif-" + platform + "-" + cpu + fileName(platform);
        String shim = "imagifyavif-" + platform + "-" + cpu + fileName(platform);
        // Both go into one directory and both keep their file names, because a module's imports are
        // resolved when it is loaded rather than at first use, and they are resolved against the
        // files sitting beside the module being loaded. The shim's import table names libavif by its
        // file name, so a libavif renamed on the way out is a load failure and not a deferred one.
        Path directory;
        try {
            directory = Files.createTempDirectory("imagify-avif-");
        } catch (IOException e) {
            log.log(Level.DEBUG, "cannot create a directory for the AVIF libraries", e);
            return null;
        }
        if (unpack(directory, avif) == null || unpack(directory, shim) == null) {
            return null;
        }
        // libavif first, so that the shim's import of it is already satisfied.
        System.load(directory.resolve(avif).toString());
        Path shimFile = directory.resolve(shim);
        System.load(shimFile.toString());
        log.log(Level.DEBUG, "loaded the AVIF shim from {0}", shimFile);
        return new AvifShim(SymbolLookup.loaderLookup());
    }

    /**
     * Copies a classpath resource into a directory on disk, because a shared library cannot be
     * mapped straight out of a jar and {@code System.load} only takes an absolute path.
     *
     * @param directory where to put it, which is also where the loader will look for what it imports
     * @param resource the file name below {@code /imagify/avif/native/}, which is also the name to
     *        give the copy
     * @return the absolute path of the copy, or {@code null} when there is no such resource
     */
    static Path unpack(Path directory, String resource) {
        try (var in = AvifLoader.class.getResourceAsStream(RESOURCE_ROOT + resource)) {
            if (in == null) {
                log.log(Level.DEBUG, "the bundled {0} is not in this jar", resource);
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
     * @param osName the value of the {@code os.name} system property
     * @param arch the value of the {@code os.arch} system property
     * @return the resource name of the library for that platform, or {@code null}
     */
    static String resourceName(String osName, String arch) {
        String platform = platform(osName);
        String cpu = cpu(arch);
        return platform == null || cpu == null ? null : "libavif-" + platform + "-" + cpu + fileName(platform);
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
     * The name of the shim for a platform, which is the libavif resource name with {@code libavif}
     * replaced by {@code imagifyavif}.
     *
     * <p>It keeps the platform and the architecture in the name for the reason the libavif ones do:
     * the shim and the library it imports have to be the same build, and two files in one jar both
     * called {@code imagifyavif.dll} and {@code libavif.dll} would leave nothing saying which.
     *
     * @param osName the value of the {@code os.name} system property
     * @param arch the value of the {@code os.arch} system property
     * @return the resource name of the shim for that platform, or {@code null}
     */
    static String shimName(String osName, String arch) {
        String libavif = resourceName(osName, arch);
        return libavif == null ? null : libavif.replaceFirst("^libavif", "imagifyavif");
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
