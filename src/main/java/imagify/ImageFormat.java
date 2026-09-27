/*
 * Copyright (C) 2026 Nameless Production Committee
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Supported image formats with auto-detection capabilities.
 *
 * <p>Each format provides file extension, MIME type, magic byte headers,
 * and whether alpha channel is supported.</p>
 *
 * <p>Auto-detection can be performed via:
 * <ul>
 *   <li>{@link #fromExtension(String)} - by file extension</li>
 *   <li>{@link #fromHeader(byte[])} - by magic byte header</li>
 *   <li>{@link #fromPath(Path)} - by file extension + header fallback</li>
 * </ul></p>
 */
public enum ImageFormat {

    /**
     * JPEG format (.jpg, .jpeg). Lossy compression. No alpha support.
     * Magic bytes: no reliable header (starts with 0xFF 0xD8).
     */
    JPEG("jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8}, false, 0.85),

    /**
     * PNG format (.png). Lossless compression. Alpha support.
     * Magic bytes: 0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A
     */
    PNG("png", "image/png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, true, 0.0),

    /**
     * GIF format (.gif). Lossless, indexed color. Alpha support (1-bit).
     * Magic bytes: "GIF87a" or "GIF89a"
     */
    GIF("gif", "image/gif", new byte[]{'G', 'I', 'F', '8', '7', 'a'}, true, 0.0),
    GIF89A("gif", "image/gif", new byte[]{'G', 'I', 'F', '8', '9', 'a'}, true, 0.0),

    /**
     * WebP format (.webp). Lossy/lossless. Alpha support.
     * Magic bytes: "RIFF...." + "WEBP"
     */
    WEBP("webp", "image/webp", new byte[]{'R', 'I', 'F', 'F'}, false, 0.80),

    /**
     * AVIF format (.avif). AV1-based. Alpha support.
     * Magic bytes: "ftyp" box with "avif" brand
     */
    AVIF("avif", "image/avif", null, true, 0.70),

    /**
     * BMP format (.bmp). Uncompressed. Alpha support varies.
     * Magic bytes: "BM"
     */
    BMP("bmp", "image/bmp", new byte[]{'B', 'M'}, false, 0.0);

    private final String extension;
    private final String mimeType;
    private final byte[] magicBytes;
    private final boolean supportsAlpha;
    private final double defaultQuality;

    ImageFormat(String extension, String mimeType, byte[] magicBytes, boolean supportsAlpha, double defaultQuality) {
        this.extension = extension;
        this.mimeType = mimeType;
        this.magicBytes = magicBytes;
        this.supportsAlpha = supportsAlpha;
        this.defaultQuality = defaultQuality;
    }

    /**
     * Returns the primary file extension for this format.
     */
    public String getExtension() { return extension; }

    /**
     * Returns the MIME type for this format.
     */
    public String getMimeType() { return mimeType; }

    /**
     * Returns whether this format supports alpha channels.
     */
    public boolean supportsAlpha() { return supportsAlpha; }

    /**
     * Returns the default quality value (0.0-1.0) for lossy formats.
     * Lossless formats return 0.0.
     */
    public double getDefaultQuality() { return defaultQuality; }

    /**
     * Returns the magic byte header for this format, or null if not applicable.
     */
    public byte[] getMagicBytes() { return magicBytes != null ? magicBytes.clone() : null; }

    /**
     * Returns the name {@code ImageIO} registers readers and writers under.
     *
     * <p>Every constant is named after its format in lower case, which is exactly the name the
     * {@code ImageIO} registry uses. {@link #GIF89A} is the one exception: GIF87a and GIF89a
     * differ only in a version number in the header and share a single encoder, so both answer
     * {@code "gif"} and writing one of them produces a plain GIF file.</p>
     */
    public String getFormatName() {
        return this == GIF89A ? GIF.extension : name().toLowerCase(Locale.ROOT);
    }

    /**
     * Resolves the format from a file extension.
     *
     * @param extension the file extension (e.g., "png", ".jpg")
     * @return the matching ImageFormat
     * @throws IllegalArgumentException if the extension is not recognized
     */
    public static ImageFormat fromExtension(String extension) {
        String ext = extension.toLowerCase().replaceFirst("^\\.", "");
        for (ImageFormat format : values()) {
            if (format.extension.equals(ext)) return format;
        }
        // Handle aliases
        switch (ext) {
            case "jpg": case "jpeg": return JPEG;
            case "gif87a": return GIF;
            case "gif89a": return GIF89A;
            default: throw new IllegalArgumentException("Unknown extension: ." + ext);
        }
    }

    /**
     * Resolves the format by inspecting the magic byte header of the data.
     *
     * @param header the first few bytes of the file/data
     * @return the matching ImageFormat
     * @throws IllegalArgumentException if the header does not match any known format
     */
    public static ImageFormat fromHeader(byte[] header) {
        if (header == null || header.length == 0)
            throw new IllegalArgumentException("Header cannot be null or empty");

        // Check JPEG first (0xFF 0xD8)
        if (header.length >= 2 && header[0] == (byte) 0xFF && header[1] == (byte) 0xD8)
            return JPEG;

        // Check PNG (89 50 4E 47 0D 0A 1A 0A)
        if (header.length >= 8 && header[0] == (byte) 0x89 && header[1] == 'P'
                && header[2] == 'N' && header[3] == 'G'
                && header[4] == 0x0D && header[5] == 0x0A && header[6] == 0x1A && header[7] == 0x0A)
            return PNG;

        // Check GIF
        if (header.length >= 6 && header[0] == 'G' && header[1] == 'I'
                && header[2] == 'F' && header[3] == '8'
                && (header[4] == '7' || header[4] == '9') && header[5] == 'a')
            return header[4] == '7' ? GIF : GIF89A;

        // Check WEBP (RIFF....WEBP) - need at least 12 bytes for full detection
        if (header.length >= 4 && header[0] == 'R' && header[1] == 'I'
                && header[2] == 'F' && header[3] == 'F') {
            // Need to check WEBP at offset 8-11
            if (header.length >= 12) {
                if (header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P')
                    return WEBP;
            }
            // Partial match, assume WEBP if RIFF
            return WEBP;
        }

        // Check BMP (BM)
        if (header.length >= 2 && header[0] == 'B' && header[1] == 'M')
            return BMP;

        // Check AVIF. An ISO base media file opens with a big endian box size, so the "ftyp"
        // signature sits at offset 4 rather than 0 and AVIF has no offset 0 signature, which is
        // why it carries no magicBytes. The major brand that follows names the format: "avif" for
        // a still image and "avis" for a sequence. Only the major brand is readable here, since
        // the caller passes just a header; a file whose major brand is something else and that
        // merely lists avif among its compatible brands is not detected here.
        if (header.length >= 12 && header[4] == 'f' && header[5] == 't'
                && header[6] == 'y' && header[7] == 'p'
                && header[8] == 'a' && header[9] == 'v'
                && header[10] == 'i' && (header[11] == 'f' || header[11] == 's'))
            return AVIF;

        throw new IllegalArgumentException("Unknown file format header");
    }

    /**
     * Resolves the format from a file path (extension-based), with header fallback.
     *
     * @param path the file path
     * @return the detected ImageFormat
     * @throws IOException if the file cannot be read for header detection
     */
    public static ImageFormat fromPath(Path path) throws IOException {
        String ext = getExtension(path);
        try {
            return fromExtension(ext);
        } catch (IllegalArgumentException e) {
            // Fall back to header detection
            java.io.InputStream in = java.nio.file.Files.newInputStream(path);
            byte[] header = new byte[12];
            int bytesRead = in.read(header);
            in.close();
            if (bytesRead > 0) {
                return fromHeader(Arrays.copyOf(header, bytesRead));
            }
            throw e;
        }
    }

    /**
     * Attempts to detect the format by checking magic bytes.
     * Returns null if detection fails (not an error, just unknown).
     */
    public static ImageFormat detect(byte[] header) {
        try {
            return fromHeader(header);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String getExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    @Override
    public String toString() {
        return extension.toUpperCase();
    }
}
