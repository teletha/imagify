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
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * Writes {@link BufferedImage} to various destinations with format control.
 *
 * <p>Supported destinations: {@code byte[]}, {@code Path}, {@code OutputStream}.</p>
 *
 * <p>Format is determined by explicit {@link ImageFormat} parameter or
 * inferred from the destination path extension.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * byte[] png = ImageWriter.toBytes(image, ImageFormat.PNG);
 * ImageWriter.toFile(image, Path.of("out.png"));
 * ImageWriter.toStream(image, ImageFormat.JPEG, outputStream);
 * }</pre>
 */
public final class ImageWriter {

    private ImageWriter() {}

    /**
     * Encodes an image to a byte array in the specified format.
     */
    public static byte[] toBytes(BufferedImage image, ImageFormat format) throws IOException {
        return toBytes(image, format, format.getDefaultQuality());
    }

    /**
     * Encodes an image to a byte array with quality control.
     */
    public static byte[] toBytes(BufferedImage image, ImageFormat format, double quality) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        toStream(image, format, quality, baos);
        return baos.toByteArray();
    }

    /**
     * Writes an image to a file path. Format is inferred from extension.
     */
    public static void toFile(BufferedImage image, Path path) throws IOException {
        ImageFormat format = ImageFormat.fromPath(path);
        toFile(image, format, path);
    }

    /**
     * Writes an image to a file path with explicit format.
     */
    public static void toFile(BufferedImage image, ImageFormat format, Path path) throws IOException {
        ImageOutputStream stream = ImageIO.createImageOutputStream(path.toFile());
        try {
            writeToStream(image, format, format.getDefaultQuality(), stream);
        } finally {
            try { stream.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Writes an image to an OutputStream with default quality.
     */
    public static void toStream(BufferedImage image, ImageFormat format, OutputStream out) throws IOException {
        toStream(image, format, format.getDefaultQuality(), out);
    }

    /**
     * Writes an image to an OutputStream with quality control.
     */
    public static void toStream(BufferedImage image, ImageFormat format, double quality, OutputStream out) throws IOException {
        ImageOutputStream stream = ImageIO.createImageOutputStream(out);
        try {
            writeToStream(image, format, quality, stream);
        } finally {
            try { stream.close(); } catch (IOException ignored) {}
        }
    }

    private static void writeToStream(BufferedImage image, ImageFormat format, double quality, ImageOutputStream stream) throws IOException {
        if (format == ImageFormat.AVIF) {
            writeAvif(image, stream);
            return;
        }

        String fmtName = format == ImageFormat.JPEG ? "jpeg" : format.name().toLowerCase();
        Iterator<javax.imageio.ImageWriter> writers = ImageIO.getImageWritersByFormatName(fmtName);
        if (writers.hasNext()) {
            javax.imageio.ImageWriter writer = writers.next();
            try {
                writer.setOutput(stream);
                writer.write(image);
            } finally {
                writer.dispose();
            }
        } else {
            ImageIO.write(image, fmtName, stream);
        }
    }

    private static void writeAvif(BufferedImage image, ImageOutputStream stream) throws IOException {
        try {
            Class<?> clazz = Class.forName("imagify.avif.AvifImageWriter");
            Object writer = clazz.getDeclaredConstructor().newInstance();
            java.lang.reflect.Method writeMethod = clazz.getMethod("write", RenderedImage.class, ImageOutputStream.class);
            writeMethod.invoke(writer, image, stream);
        } catch (Exception e) {
            throw new IOException("Failed to write AVIF: " + e.getMessage(), e.getCause());
        }
    }
}
