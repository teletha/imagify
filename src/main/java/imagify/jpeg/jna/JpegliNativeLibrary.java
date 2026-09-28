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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

import static java.lang.System.getLogger;

/**
 * Locates the jpegli based shared library that ships inside this jar and unpacks it so that the
 * platform dynamic linker can load it.
 *
 * <p>A shared library cannot be mapped straight out of a jar, so the resource is copied to a
 * temporary directory and loaded from there by its absolute path, which is the same trick
 * {@code imagify.avif.jna.AvifNativeLibrary} uses for {@code libavif} and which is where the layout
 * below comes from.
 *
 * <p>The bundled binaries live under {@value #RESOURCE_ROOT} and are named after the platform they
 * were built for:
 *
 * <pre>
 * libjpegli-windows-x64.dll
 * libjpegli-windows-arm64.dll
 * libjpegli-linux-x64.so
 * libjpegli-linux-arm64.so
 * libjpegli-macos-x64.dylib
 * libjpegli-macos-arm64.dylib
 * </pre>
 *
 * <p>See {@code src/main/native/jpegli/CMakeLists.txt} for how they are built. The short version is that
 * they are statically linked against jpegli and highway, so that they have no further dependencies
 * at all: on Windows the loader resolves the dependencies of a {@code LoadLibrary}ed module against
 * the directory of the executable and against {@code PATH} only, never against the directory of the
 * module itself.
 *
 * <p>Nothing here ever throws. A platform without a bundled library, a missing resource and a full
 * temporary directory all simply mean "not bundled", which leaves {@link JpegliCodec} free to fall
 * back to a library installed on the system.
 */
final class JpegliNativeLibrary {

    private static final Logger log = getLogger(JpegliNativeLibrary.class.getName());

    /** Classpath directory that holds the per platform shared libraries. */
    static final String RESOURCE_ROOT = "/imagify/jpeg/native/";

    /**
     * Set this system property to {@code false} to ignore the bundled library and always look for a
     * jpegli based one installed on the system.
     */
    static final String BUNDLED_PROPERTY = "imagify.jpeg.bundled";

    private static final Object LOCK = new Object();

    private static volatile boolean resolved;
    private static volatile Path extracted;

    private JpegliNativeLibrary() {
        // utility class
    }

    /**
     * Unpacks the bundled shared library for the current platform.
     *
     * @return the absolute path of the unpacked library, or {@code null} when this platform has no
     * bundled library, the bundled library is disabled or it could not be unpacked
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
                log.log(Level.DEBUG, "cannot unpack the bundled jpegli library", t);
            }
            resolved = true;
            return extracted;
        }
    }

    private static Path unpack() {
        if (!Boolean.parseBoolean(System.getProperty(BUNDLED_PROPERTY, "true"))) {
            log.log(Level.DEBUG, "the bundled jpegli library is disabled by -D{0}=false", BUNDLED_PROPERTY);
            return null;
        }
        String osName = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");
        String platform = platform(osName);
        String cpu = cpu(arch);
        if (platform == null || cpu == null) {
            log.log(Level.DEBUG, "no bundled jpegli library for {0}/{1}", osName, arch);
            return null;
        }
        return unpack(resourceNameOf(platform, cpu), fileName(platform));
    }

    /**
     * Copies a classpath resource below {@value #RESOURCE_ROOT} to a temporary directory.
     *
     * @param resource the file name of the resource
     * @param fileName the name to give the copy, which is what the dynamic linker is asked for
     * @return the absolute path of the copy, or {@code null} when there is no such resource or it
     * could not be written
     */
    static Path unpack(String resource, String fileName) {
        try (InputStream in = JpegliNativeLibrary.class.getResourceAsStream(RESOURCE_ROOT + resource)) {
            if (in == null) {
                log.log(Level.DEBUG, "the bundled jpegli library {0} is not in this jar", RESOURCE_ROOT + resource);
                return null;
            }
            Path directory = Files.createTempDirectory("imagify-jpeg-");
            Path file = directory.resolve(fileName);
            try (OutputStream out = Files.newOutputStream(file)) {
                in.transferTo(out);
            }
            deleteOnExit(directory);
            log.log(Level.DEBUG, "unpacked the bundled jpegli library to {0}", file);
            return file.toAbsolutePath();
        } catch (IOException e) {
            log.log(Level.DEBUG, "cannot unpack the bundled jpegli library " + resource, e);
            return null;
        }
    }

    /**
     * Returns the name of the classpath resource that holds the shared library for the given
     * platform, for example {@code libjpegli-linux-x64.so}.
     *
     * @param osName the value of the {@code os.name} system property
     * @param arch the value of the {@code os.arch} system property
     * @return the resource name, or {@code null} when the platform is not supported
     */
    static String resourceName(String osName, String arch) {
        String platform = platform(osName);
        String cpu = cpu(arch);
        return platform == null || cpu == null ? null : resourceNameOf(platform, cpu);
    }

    /**
     * Returns the name of the classpath resource for an already resolved platform.
     *
     * @param platform {@code windows}, {@code macos} or {@code linux}
     * @param cpu {@code x64} or {@code arm64}
     * @return the resource name
     */
    private static String resourceNameOf(String platform, String cpu) {
        return "libjpegli-" + platform + "-" + cpu + extension(platform);
    }

    /**
     * Returns the short platform key used in resource names.
     *
     * @param osName the value of the {@code os.name} system property
     * @return {@code windows}, {@code macos}, {@code linux}, or {@code null} when unrecognised
     */
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

    /**
     * Returns the short CPU key used in resource names.
     *
     * @param arch the value of the {@code os.arch} system property
     * @return {@code x64}, {@code arm64}, or {@code null} when unrecognised
     */
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

    /**
     * Returns the file name the library is unpacked as, which is the name the platform dynamic
     * linker knows it by.
     *
     * @param platform {@code windows}, {@code macos} or {@code linux}
     * @return the plain file name
     */
    static String fileName(String platform) {
        return "windows".equals(platform) ? "jpegli" + extension(platform) : "libjpegli" + extension(platform);
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
                // The library is still mapped at this point, so on Windows this normally fails.
                // Leaving the temporary directory behind is the lesser evil.
                log.log(Level.DEBUG, "cannot delete " + directory, e);
            }
        }, "imagify-jpeg-cleanup"));
    }
}
