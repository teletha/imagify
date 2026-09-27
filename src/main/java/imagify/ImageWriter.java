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

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
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
 * <p>Quality is a {@code 0.0} to {@code 1.0} value that is handed to the encoder through the
 * compression quality of an {@link ImageWriteParam}, so a smaller number means a smaller file and
 * more loss. Formats without a quality axis, such as GIF and BMP, ignore it.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * byte[] png = ImageWriter.toBytes(image, ImageFormat.PNG);
 * byte[] jpg = ImageWriter.toBytes(image, ImageFormat.JPEG, 0.6);
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
     *
     * @param quality {@code 0.0} (smallest) to {@code 1.0} (largest)
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
     * Writes an image to a file path. Format is inferred from extension.
     *
     * @param quality {@code 0.0} (smallest) to {@code 1.0} (largest)
     */
    public static void toFile(BufferedImage image, Path path, double quality) throws IOException {
        ImageFormat format = ImageFormat.fromPath(path);
        toFile(image, format, quality, path);
    }

    /**
     * Writes an image to a file path with explicit format.
     */
    public static void toFile(BufferedImage image, ImageFormat format, Path path) throws IOException {
        toFile(image, format, format.getDefaultQuality(), path);
    }

    /**
     * Writes an image to a file path with explicit format and quality.
     *
     * @param quality {@code 0.0} (smallest) to {@code 1.0} (largest)
     */
    public static void toFile(BufferedImage image, ImageFormat format, double quality, Path path) throws IOException {
        ImageOutputStream stream = ImageIO.createImageOutputStream(path.toFile());
        try {
            writeToStream(image, format, quality, stream);
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
     *
     * @param quality {@code 0.0} (smallest) to {@code 1.0} (largest)
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
        checkQuality(quality);

        String formatName = format.getFormatName();
        Iterator<javax.imageio.ImageWriter> writers = ImageIO.getImageWritersByFormatName(formatName);
        if (!writers.hasNext()) {
            ImageIO.write(image, formatName, stream);
            return;
        }

        javax.imageio.ImageWriter writer = writers.next();
        try {
            writer.setOutput(stream);
            ImageWriteParam param = writeParam(writer, quality);
            if (param == null) {
                writer.write(image);
            } else {
                writer.write(null, new IIOImage(image, null, null), param);
            }
        } finally {
            writer.dispose();
        }
    }

    /**
     * Builds the write parameters that carry {@code quality} to the encoder.
     *
     * @return the parameters, or {@code null} when the writer has no quality axis
     */
    private static ImageWriteParam writeParam(javax.imageio.ImageWriter writer, double quality) {
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param == null || !param.canWriteCompressed()) return null;
        try {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality((float) quality);
        } catch (IllegalStateException | UnsupportedOperationException e) {
            // The GIF and BMP writers of the JDK advertise compression types yet refuse
            // MODE_EXPLICIT, and a writer without a quality axis has nothing to be told anyway.
            // Both are written with their own defaults, which is what ImageIO.write would do.
            return null;
        }
        return param;
    }

    private static void checkQuality(double quality) {
        // Written as a negated range test so that NaN, which compares false against everything,
        // is rejected as well.
        if (!(quality >= 0.0 && quality <= 1.0)) {
            throw new IllegalArgumentException("the quality must be between 0.0 and 1.0, got " + quality);
        }
    }
}
