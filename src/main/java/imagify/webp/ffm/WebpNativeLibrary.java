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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Linker;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

/**
 * Locates the {@code libwebp} based shared library that ships inside this jar and unpacks it so
 * that the platform dynamic linker can load it.
 *
 * <p>A shared library cannot be mapped straight out of a jar, so the resource is copied to a
 * temporary directory and loaded from there by its absolute path using {@link System#load(String)},
 * which is then visible to {@link Linker#nativeLinker()}.
 *
 * <p>The bundled binaries live under {@value #RESOURCE_ROOT} and are named after the platform they
 * were built for.
 *
 * <p>See {@code src/main/native/webp/CMakeLists.txt} for how they are built.
 */
final class WebpNativeLibrary {

    private static final Logger log = getLogger(WebpNativeLibrary.class.getName());

    /** Classpath directory that holds the per platform shared libraries. */
    static final String RESOURCE_ROOT = "/imagify/webp/native/";

    /**
     * Set this system property to {@code false} to ignore the bundled library and always look for a
     * {@code libwebp} based one installed on the system.
     */
    static final String BUNDLED_PROPERTY = "imagify.webp.bundled";

    private static final Object LOCK = new Object();

    private static volatile boolean resolved;

    private static volatile Path extracted;

    private WebpNativeLibrary() {
        // utility class
    }

    /**
     * Unpacks the bundled shared library for the current platform.
     *
     * @return the absolute path of the unpacked library, or {@code null} when this platform has no
     *         bundled library, the bundled library is disabled or it could not be unpacked
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
            try {
                extracted = unpack();
            } catch (Throwable t) {
                log.log(Level.DEBUG, "cannot unpack the bundled WebP library", t);
            }
            resolved = true;
            return extracted;
        }
    }

    private static Path unpack() {
        if (!Boolean.parseBoolean(System.getProperty(BUNDLED_PROPERTY, "true"))) {
            log.log(Level.DEBUG, "the bundled WebP library is disabled by -D{0}=false", BUNDLED_PROPERTY);
            return null;
        }
        String osName = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");
        String platform = platform(osName);
        String cpu = cpu(arch);
        if (platform == null || cpu == null) {
            log.log(Level.DEBUG, "no bundled WebP library for {0}/{1}", osName, arch);
            return null;
        }
        return unpack(resourceNameOf(platform, cpu), fileName(platform));
    }

    static Path unpack(String resource, String fileName) {
        try (InputStream in = WebpNativeLibrary.class.getResourceAsStream(RESOURCE_ROOT + resource)) {
            if (in == null) {
                log.log(Level.DEBUG, "the bundled WebP library {0} is not in this jar", RESOURCE_ROOT + resource);
                return null;
            }
            Path directory = Files.createTempDirectory("imagify-webp-");
            Path file = directory.resolve(fileName);
            try (OutputStream out = Files.newOutputStream(file)) {
                in.transferTo(out);
            }
            deleteOnExit(directory);
            log.log(Level.DEBUG, "unpacked the bundled WebP library to {0}", file);
            return file.toAbsolutePath();
        } catch (IOException e) {
            log.log(Level.DEBUG, "cannot unpack the bundled WebP library " + resource, e);
            return null;
        }
    }

    static String resourceName(String osName, String arch) {
        String platform = platform(osName);
        String cpu = cpu(arch);
        return platform == null || cpu == null ? null : resourceNameOf(platform, cpu);
    }

    private static String resourceNameOf(String platform, String cpu) {
        return "imagifywebp-" + platform + "-" + cpu + extension(platform);
    }

    static String platform(String osName) {
        if (osName == null) {
            return null;
        }
        String name = osName.toLowerCase(Locale.ROOT);
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

    static String cpu(String arch) {
        if (arch == null) {
            return null;
        }
        return switch (arch.toLowerCase(Locale.ROOT)) {
        case "amd64", "x86_64", "x64" -> "x64";
        case "aarch64", "arm64" -> "arm64";
        default -> null;
        };
    }

    private static String extension(String platform) {
        return switch (platform) {
        case "windows" -> ".dll";
        case "macos" -> ".dylib";
        default -> ".so";
        };
    }

    static String fileName(String platform) {
        return "windows".equals(platform) ? "imagifywebp" + extension(platform) : "libimagifywebp" + extension(platform);
    }

    private static void deleteOnExit(Path directory) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                        Files.deleteIfExists(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                log.log(Level.DEBUG, "cannot delete " + directory, e);
            }
        }, "imagify-webp-cleanup"));
    }
}
