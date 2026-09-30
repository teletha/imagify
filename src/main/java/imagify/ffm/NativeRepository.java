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

import static java.lang.System.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

/**
 * Resolves the shared library that backs an AVIF, WebP or JPEG codec, fetching it from a GitHub
 * release the first time it is needed.
 *
 * <p>The libraries do not ship inside the jar. Each of the three formats is published as its own
 * release, one release per native version, with the six files of the six supported platforms
 * attached. The runtime only ever wants one of the six, so fetching on first use is the whole point
 * of the arrangement: what a user downloads is the file for their platform, not a jar with fifty
 * megabytes of libraries for platforms they will never run on.
 *
 * <p>The tag that names the release is the only thing the jar knows about a library. It is written
 * into {@code imagify/<format>/native/native.properties} beside the codec that needs it, and the
 * natives workflow that builds and publishes the libraries reads the very same file, so the two
 * cannot drift apart. The tag is immutable: publishing the same tag twice is refused on purpose,
 * because a client caches what it downloaded for a tag and would have no way to learn that the file
 * changed. A rebuild of the same native version is published under the same tag plus a dash suffix
 * instead, and the suffix is added to {@code native.properties} in the same commit.
 *
 * <p>Resolution never throws and never blocks a codec. Every way of giving up records a reason,
 * {@link #lastReason(NativeCodec)} reports it and returns {@code null}, and the codec falls back to
 * a library installed on the system or reports the reason. The cache deliberately has no checksum
 * verification: a release is immutable, so a file that sits where the cache expects it is known to
 * be exactly what was published. Delete the cache directory to drop it and fetch again.
 */
public final class NativeRepository {

    /** Names the system property that points at the download cache. */
    public static final String PROPERTY_CACHE = "imagify.native.cache";

    /** Names the system property that turns downloads off ({@code false} disables them). */
    public static final String PROPERTY_DOWNLOAD = "imagify.native.download";

    /** Names the system property that overrides where the releases are served from. */
    public static final String PROPERTY_URL = "imagify.native.url";

    /** The releases of this repository, which is where a default resolution fetches from. */
    public static final String DEFAULT_URL = "https://github.com/teletha/imagify/releases/download";

    /**
     * One codec with a managed native library, which is everything the resolution below needs to
     * know about a format:
     *
     * <dl>
     * <dt>{@code format}</dt><dd>the cache directory and the properties resource the release tag
     * lives in</dd>
     * <dt>{@code prefix}</dt><dd>the release assets are named
     * {@code <prefix><platform>-<cpu>.<ext>}, which is also the name the natives workflow gives the
     * files it publishes, so a name in a bug report says which build it came from</dd>
     * <dt>{@code property}</dt><dd>the system properties of the codec all start with this, so
     * {@code -Dimagify.webp.library=/path/to.so} names a library explicitly and
     * {@code -Dimagify.webp.bundled=false} turns the managed library off</dd>
     * </dl>
     */
    public enum NativeCodec {
        WEBP("webp", "imagifywebp-", "imagify.webp"),
        JPEGLI("jpeg", "libjpegli-", "imagify.jpeg"),
        AVIF("avif", "imagifyavif-", "imagify.avif");

        private final String format;
        private final String prefix;
        private final String property;

        NativeCodec(String format, String prefix, String property) {
            this.format = format;
            this.prefix = prefix;
            this.property = property;
        }

        /** @return the segment the cache and the properties are filed under */
        public String format() {
            return format;
        }

        /** @return the prefix of the release asset file names */
        public String prefix() {
            return prefix;
        }

        /** @return the prefix of this codec's system properties */
        public String property() {
            return property;
        }
    }

    private static final Logger log = getLogger(NativeRepository.class.getName());

    private static final Map<NativeCodec, String> REASONS = new EnumMap<>(NativeCodec.class);

    private NativeRepository() {
        // utility class
    }

    /**
     * Resolves the managed library of a codec, downloading it when it is not in the cache yet.
     *
     * <p>The resolution order is: an explicitly named library wins over everything; then the cache,
     * keyed by (codec, tag, file name); then a download from {@value #DEFAULT_URL}
     * {@code /<tag>/<file>}. Anything that gives up leaves a reason {@link #lastReason(NativeCodec)}
     * reports, and the caller is free to fall back to a library the system provides.
     *
     * @param codec the format whose library is wanted
     * @return the absolute path of the library, or {@code null} when it could not be resolved, in
     *         which case {@link #lastReason(NativeCodec)} says why
     */
    public static Path resolve(NativeCodec codec) {
        Path path = resolveManaged(codec);
        if (path != null) {
            synchronized (REASONS) {
                REASONS.remove(codec);
            }
        }
        return path;
    }

    /**
     * @param codec the format whose last resolution failed, or succeeded
     * @return why the last {@link #resolve(NativeCodec)} of the codec gave up, or {@code null} when
     *         it found a library
     */
    public static String lastReason(NativeCodec codec) {
        synchronized (REASONS) {
            return REASONS.get(codec);
        }
    }

    private static Path resolveManaged(NativeCodec codec) {
        // An explicitly named library is the most specific instruction there is, so it wins over
        // everything including the disable switch: naming a file is stronger than asking for the
        // default search path.
        String explicit = System.getProperty(codec.property() + ".library");
        if (explicit != null && !explicit.isBlank()) {
            Path path = Path.of(explicit).toAbsolutePath();
            if (Files.isRegularFile(path)) {
                return path;
            }
            return refuse(codec, "-D" + codec.property() + ".library=" + explicit
                    + " names a file that does not exist");
        }
        if (!Boolean.parseBoolean(System.getProperty(codec.property() + ".bundled", "true"))) {
            return refuse(codec, "the managed " + codec.property() + " library is turned off by -D"
                    + codec.property() + ".bundled=false");
        }
        String osName = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");
        String platform = platform(osName);
        String cpu = cpu(arch);
        if (platform == null || cpu == null) {
            return refuse(codec, "there is no library for " + osName + " on " + arch + ": imagify "
                    + "publishes Windows, macOS and Linux on x64 and arm64");
        }
        String asset = assetNameOf(codec, platform, cpu);
        String tag = tag(codec);
        if (tag == null) {
            return refuse(codec, "imagify/" + codec.format() + "/native/native.properties names no "
                    + "release, so there is nothing to fetch for " + codec.format());
        }
        Path file = cacheFile(codec, tag, asset);
        if (Files.isRegularFile(file)) {
            log.log(Level.DEBUG, "using the cached {0}", file);
            return file;
        }
        if (!Boolean.parseBoolean(System.getProperty(PROPERTY_DOWNLOAD, "true"))) {
            return refuse(codec, "downloads are turned off by -D" + PROPERTY_DOWNLOAD + "=false and "
                    + asset + " is not in the cache " + file.getParent());
        }
        return download(codec, file, tag, asset);
    }

    private static Path download(NativeCodec codec, Path target, String tag, String asset) {
        String base = System.getProperty(PROPERTY_URL, DEFAULT_URL);
        String url = base.replaceAll("/+$", "") + "/" + tag + "/" + asset;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        try {
            // GitHub answers a release asset request with a redirect to the storage host; NORMAL
            // follows it, and the redirect hand off keeps the bytes byte for byte, because the JDK's
            // client sends no Accept-Encoding header and does not decompress a response either.
            HttpResponse<InputStream> response = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofMinutes(2))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                return refuse(codec, "the release " + tag + " or its asset " + asset + " is not at "
                        + url + " (HTTP " + response.statusCode() + "). Publish the "
                        + codec.format() + "-natives release, or set -D" + PROPERTY_URL
                        + " to where it is mirrored.");
            }
            return write(codec, target, response.body());
        } catch (IOException e) {
            return refuse(codec, "cannot download " + url + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return refuse(codec, "the download of " + url + " was interrupted");
        }
    }

    private static Path write(NativeCodec codec, Path target, InputStream body) {
        try (InputStream in = body) {
            Files.createDirectories(target.getParent());
            // The file goes in under a sibling name and is moved onto the cache name, so a fetch
            // that fails halfway cannot leave a truncated file where the cache looks for it: the
            // cache name either does not exist or holds the whole response.
            Path partial = target.resolveSibling(target.getFileName() + ".part");
            try (OutputStream out = Files.newOutputStream(partial)) {
                in.transferTo(out);
            }
            try {
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(partial, target);
            } catch (IOException e) {
                // Two threads fetched the same file into the same cache, and the move that lost the
                // race finds the winner already on the name. The bytes are the same either way.
                if (!Files.isRegularFile(target)) {
                    throw e;
                }
                Files.deleteIfExists(partial);
            }
            log.log(Level.DEBUG, "fetched {0}", target);
            return target;
        } catch (IOException e) {
            return refuse(codec, "cannot write " + target + ": " + e.getMessage());
        }
    }

    /**
     * Reads the {@code tag} that names the release a format's libraries are published under.
     *
     * <p>The value lives in {@code imagify/<format>/native/native.properties} inside the jar, and
     * the natives workflow reads the same file when it publishes, so the tag the runtime fetches is
     * exactly the tag the workflow published.
     *
     * @param codec the format whose release is wanted
     * @return the tag, or {@code null} when the properties are missing or hold no tag
     */
    public static String tag(NativeCodec codec) {
        Properties properties = properties(codec);
        if (properties == null) {
            return null;
        }
        String tag = properties.getProperty("tag");
        if (tag == null) {
            return null;
        }
        tag = tag.trim();
        return tag.isEmpty() ? null : tag;
    }

    private static Properties properties(NativeCodec codec) {
        String resource = "/imagify/" + codec.format() + "/native/native.properties";
        try (InputStream in = NativeRepository.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The directory downloaded libraries are kept in.
     *
     * <p>It defaults to {@code ~/.imagify/natives} and is moved with
     * {@code -D}{@link #PROPERTY_CACHE}. The layout below the root is
     * {@code <format>/<tag>/<file name>}, so the key of the cache is the release tag and the file
     * name, and deleting the directory is the way to fetch everything again.
     *
     * @return the absolute cache directory
     */
    public static Path cacheDirectory() {
        String cache = System.getProperty(PROPERTY_CACHE);
        if (cache == null || cache.isBlank()) {
            String home = System.getProperty("user.home");
            String root = home == null || home.isBlank() ? System.getProperty("java.io.tmpdir") : home;
            cache = Path.of(root, ".imagify", "natives").toString();
        }
        return Path.of(cache).toAbsolutePath();
    }

    /**
     * The cache file a release asset is kept in.
     *
     * @param codec the format the asset belongs to
     * @param tag the release tag, as {@link #tag(NativeCodec)} reads it
     * @param asset the file name of the asset
     * @return {@link #cacheDirectory()}{@code /<format>/<tag>/<asset>}
     */
    public static Path cacheFile(NativeCodec codec, String tag, String asset) {
        return cacheDirectory().resolve(codec.format()).resolve(tag).resolve(asset);
    }

    /**
     * The file a release asset is published under, from its platform and CPU keys.
     *
     * <p>The keys are the values {@link #platform(String)} and {@link #cpu(String)} map the JVM's
     * own names to, which is what makes them stable across the spellings of a platform.
     *
     * @param codec the format the asset belongs to
     * @param platform the platform key
     * @param cpu the CPU key
     * @return the asset name, for example {@code imagifywebp-linux-x64.so}
     */
    private static String assetNameOf(NativeCodec codec, String platform, String cpu) {
        return codec.prefix() + platform + "-" + cpu + extension(platform);
    }

    /**
     * The file a release asset is published under, resolved from a running JVM's own names.
     *
     * @param codec the format the asset belongs to
     * @param osName the value of the {@code os.name} system property
     * @param arch the value of the {@code os.arch} system property
     * @return the asset name, or {@code null} when the platform is not one of the six
     */
    public static String assetName(NativeCodec codec, String osName, String arch) {
        String platform = platform(osName);
        String cpu = cpu(arch);
        return platform == null || cpu == null ? null : assetNameOf(codec, platform, cpu);
    }

    /**
     * @param osName the value of the {@code os.name} system property
     * @return {@code windows}, {@code macos} or {@code linux}, or {@code null} when unrecognised
     */
    public static String platform(String osName) {
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
     * @return {@code x64} or {@code arm64}, or {@code null} when unrecognised
     */
    public static String cpu(String arch) {
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
     * @param platform one of the values {@link #platform(String)} returns
     * @return the extension a shared library has on that platform
     */
    public static String extension(String platform) {
        return switch (platform) {
            case "windows" -> ".dll";
            case "macos" -> ".dylib";
            default -> ".so";
        };
    }

    private static Path refuse(NativeCodec codec, String reason) {
        synchronized (REASONS) {
            REASONS.put(codec, reason);
        }
        log.log(Level.DEBUG, "no {0} library: {1}", codec.format(), reason);
        return null;
    }
}