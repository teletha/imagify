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

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Reads {@link BufferedImage} from various sources with automatic format detection.
 *
 * <p>Supported sources: {@code byte[]}, {@code Path}, {@code InputStream}.</p>
 *
 * <p>Format detection priority:
 * <ol>
 *   <li>Explicit {@link ImageFormat} parameter</li>
 *   <li>File extension</li>
 *   <li>Magic byte header</li>
 * </ol></p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * BufferedImage img = ImageReader.read(path);
 * BufferedImage img = ImageReader.read(bytes, ImageFormat.PNG);
 * BufferedImage img = ImageReader.read(inputStream);
 * }</pre>
 */
public final class ImageReader {

    private ImageReader() {}

    /**
     * Reads an image from a byte array with auto-detected format.
     */
    public static BufferedImage read(byte[] data) throws IOException {
        return read(data, detectFormat(data));
    }

    /**
     * Reads an image from a byte array with explicit format.
     */
    public static BufferedImage read(byte[] data, ImageFormat format) throws IOException {
        return readArray(data, format);
    }

    /**
     * Reads an image that has already been held in memory.
     *
     * <p>ImageIO has no ImageInputStream provider for a byte array, so the array is wrapped in a
     * stream here. Handing the array to {@code createImageInputStream} directly yields a null
     * stream, and closing that is a NullPointerException rather than an image.
     */
    private static BufferedImage readArray(byte[] data, ImageFormat format) throws IOException {
        ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data));
        if (stream == null) {
            throw new IOException("no ImageInputStream provider accepted the encoded data");
        }
        try {
            return readFromStream(stream, format);
        } finally {
            try { stream.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Reads an image from a file path with auto-detected format.
     */
    public static BufferedImage read(Path path) throws IOException {
        ImageFormat format = ImageFormat.fromPath(path);
        ImageInputStream stream = ImageIO.createImageInputStream(path.toFile());
        try {
            return readFromStream(stream, format);
        } finally {
            try { stream.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Reads an image from a file path with explicit format.
     */
    public static BufferedImage read(Path path, ImageFormat format) throws IOException {
        ImageInputStream stream = ImageIO.createImageInputStream(path.toFile());
        try {
            return readFromStream(stream, format);
        } finally {
            try { stream.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Reads an image from an InputStream with auto-detected format.
     */
    public static BufferedImage read(InputStream in) throws IOException {
        byte[] data = in.readAllBytes();
        return read(data);
    }

    /**
     * Reads an image from an InputStream with explicit format.
     */
    public static BufferedImage read(InputStream in, ImageFormat format) throws IOException {
        return readArray(in.readAllBytes(), format);
    }

    private static ImageFormat detectFormat(byte[] data) {
        int headerLen = Math.min(data.length, 12);
        ImageFormat format = ImageFormat.detect(Arrays.copyOf(data, headerLen));
        if (format != null) return format;
        return ImageFormat.PNG;
    }

    private static BufferedImage readFromStream(ImageInputStream stream, ImageFormat format) throws IOException {
        if (format == ImageFormat.AVIF) {
            return readAvif(stream);
        }

        String fmtName = format == ImageFormat.JPEG ? "jpeg" : format.name().toLowerCase();
        Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReadersByFormatName(fmtName);
        if (readers.hasNext()) {
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }

        stream.reset();
        return ImageIO.read(stream);
    }

    private static BufferedImage readAvif(ImageInputStream stream) throws IOException {
        try {
            Class<?> clazz = Class.forName("imagify.avif.AvifImageReader");
            Object reader = clazz.getDeclaredConstructor().newInstance();
            java.lang.reflect.Method readMethod = clazz.getMethod("read", ImageInputStream.class);
            return (BufferedImage) readMethod.invoke(reader, stream);
        } catch (Exception e) {
            throw new IOException("Failed to read AVIF: " + e.getMessage(), e.getCause());
        }
    }
}
