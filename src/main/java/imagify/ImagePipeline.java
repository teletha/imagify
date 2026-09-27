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

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;

/**
 * Fluent pipeline API for image processing.
 *
 * <p>Supports chaining: {@code read → resize → write}.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * // Read → Resize → Write to file
 * ImagePipeline
 *     .read(path)
 *     .resize(800, 600, ResizeAlgorithm.LANCZOS3)
 *     .writeTo(path);
 *
 * // Read → Resize → Write to bytes
 * byte[] png = ImagePipeline
 *     .read(bytes)
 *     .resize(0.5)
 *     .writeToBytes(ImageFormat.PNG);
 *
 * // Read → Resize → Write to stream
 * ImagePipeline
 *     .read(inputStream)
 *     .resize(1024, 768)
 *     .writeTo(outputStream, ImageFormat.JPEG);
 *
 * // Get intermediate result
 * BufferedImage result = ImagePipeline
 *     .read(path)
 *     .resize(50, 50, ResizeAlgorithm.CATROM)
 *     .get();
 * }</pre>
 */
public final class ImagePipeline {

    private static final class PipelineException extends RuntimeException {
        PipelineException(String message, Throwable cause) { super(message, cause); }
    }

    private BufferedImage image;
    private ImageFormat format;

    private ImagePipeline() {}

    // ═══════════════════════════════════════════════════
    //  Read phase
    // ═══════════════════════════════════════════════════

    /**
     * Starts a pipeline by reading from a byte array.
     * Format is auto-detected from magic bytes.
     */
    public static ImagePipeline read(byte[] data) {
        ImagePipeline pipe = new ImagePipeline();
        try { pipe.image = ImageReader.read(data); }
        catch (IOException e) { throw new PipelineException("Failed to read image", e); }
        return pipe;
    }

    /**
     * Starts a pipeline by reading from a byte array with explicit format.
     */
    public static ImagePipeline read(byte[] data, ImageFormat format) {
        ImagePipeline pipe = new ImagePipeline();
        try { pipe.image = ImageReader.read(data, format); pipe.format = format; }
        catch (IOException e) { throw new PipelineException("Failed to read image", e); }
        return pipe;
    }

    /**
     * Starts a pipeline by reading from a file path.
     * Format is auto-detected from extension or header.
     */
    public static ImagePipeline read(Path path) {
        ImagePipeline pipe = new ImagePipeline();
        try { pipe.image = ImageReader.read(path); }
        catch (IOException e) { throw new PipelineException("Failed to read image", e); }
        return pipe;
    }

    /**
     * Starts a pipeline by reading from a file path with explicit format.
     */
    public static ImagePipeline read(Path path, ImageFormat format) {
        ImagePipeline pipe = new ImagePipeline();
        try { pipe.image = ImageReader.read(path, format); pipe.format = format; }
        catch (IOException e) { throw new PipelineException("Failed to read image", e); }
        return pipe;
    }

    /**
     * Starts a pipeline by reading from an InputStream.
     * Format is auto-detected from magic bytes.
     */
    public static ImagePipeline read(InputStream in) {
        ImagePipeline pipe = new ImagePipeline();
        try { pipe.image = ImageReader.read(in); }
        catch (IOException e) { throw new PipelineException("Failed to read image", e); }
        return pipe;
    }

    /**
     * Starts a pipeline by reading from an InputStream with explicit format.
     */
    public static ImagePipeline read(InputStream in, ImageFormat format) {
        ImagePipeline pipe = new ImagePipeline();
        try { pipe.image = ImageReader.read(in, format); pipe.format = format; }
        catch (IOException e) { throw new PipelineException("Failed to read image", e); }
        return pipe;
    }

    // ═══════════════════════════════════════════════════
    //  Transform phase
    // ═══════════════════════════════════════════════════

    /**
     * Resizes to exact dimensions using the default algorithm (BILINEAR).
     */
    public ImagePipeline resize(int targetW, int targetH) {
        return resize(targetW, targetH, ResizeAlgorithm.BILINEAR);
    }

    /**
     * Resizes to exact dimensions with the specified algorithm.
     */
    public ImagePipeline resize(int targetW, int targetH, ResizeAlgorithm algorithm) {
        this.image = BufferedImageResize.resize(image, targetW, targetH, algorithm);
        return this;
    }

    /**
     * Resizes by scale factor using BILINEAR.
     */
    public ImagePipeline resize(double scale) {
        return resize(scale, ResizeAlgorithm.BILINEAR);
    }

    /**
     * Resizes by scale factor with the specified algorithm.
     */
    public ImagePipeline resize(double scale, ResizeAlgorithm algorithm) {
        int targetW = (int) Math.round(image.getWidth() * scale);
        int targetH = (int) Math.round(image.getHeight() * scale);
        this.image = BufferedImageResize.resize(image, targetW, targetH, algorithm);
        return this;
    }

    /**
     * Resizes so the longest edge fits within maxDimension, maintaining aspect ratio.
     */
    public ImagePipeline resizeToFit(int maxDimension) {
        return resizeToFit(maxDimension, ResizeAlgorithm.BILINEAR);
    }

    /**
     * Resizes so the longest edge fits within maxDimension with the specified algorithm.
     */
    public ImagePipeline resizeToFit(int maxDimension, ResizeAlgorithm algorithm) {
        int w = image.getWidth();
        int h = image.getHeight();
        double s = Math.min((double) maxDimension / w, (double) maxDimension / h);
        int targetW = (int) Math.round(w * s);
        int targetH = (int) Math.round(h * s);
        this.image = BufferedImageResize.resize(image, targetW, targetH, algorithm);
        return this;
    }

    // ═══════════════════════════════════════════════════
    //  Write phase
    // ═══════════════════════════════════════════════════

    /**
     * Writes the result to a file path with auto-detected format from extension.
     */
    public ImagePipeline writeTo(Path path) {
        try {
            ImageFormat fmt = ImageFormat.fromPath(path);
            ImageWriter.toFile(image, fmt, path);
        } catch (IOException e) { throw new PipelineException("Failed to write image", e); }
        return this;
    }

    /**
     * Writes the result to a file path with auto-detected format from extension and quality.
     *
     * @param quality {@code 0.0} (smallest) to {@code 1.0} (largest)
     */
    public ImagePipeline writeTo(Path path, double quality) {
        try {
            ImageFormat fmt = ImageFormat.fromPath(path);
            ImageWriter.toFile(image, fmt, quality, path);
        } catch (IOException e) { throw new PipelineException("Failed to write image", e); }
        return this;
    }

    /**
     * Writes the result to a file path with explicit format.
     */
    public ImagePipeline writeTo(Path path, ImageFormat format) {
        try { ImageWriter.toFile(image, format, path); }
        catch (IOException e) { throw new PipelineException("Failed to write image", e); }
        return this;
    }

    /**
     * Writes the result to a file path with explicit format and quality.
     *
     * @param quality {@code 0.0} (smallest) to {@code 1.0} (largest)
     */
    public ImagePipeline writeTo(Path path, ImageFormat format, double quality) {
        try { ImageWriter.toFile(image, format, quality, path); }
        catch (IOException e) { throw new PipelineException("Failed to write image", e); }
        return this;
    }

    /**
     * Writes the result to an OutputStream with default quality.
     * Format must be explicitly specified.
     */
    public ImagePipeline writeTo(OutputStream out, ImageFormat format) {
        try { ImageWriter.toStream(image, format, out); }
        catch (IOException e) { throw new PipelineException("Failed to write image", e); }
        return this;
    }

    /**
     * Writes the result to an OutputStream with quality control.
     */
    public ImagePipeline writeTo(OutputStream out, ImageFormat format, double quality) {
        try { ImageWriter.toStream(image, format, quality, out); }
        catch (IOException e) { throw new PipelineException("Failed to write image", e); }
        return this;
    }

    /**
     * Returns the result as a byte array.
     */
    public byte[] writeToBytes(ImageFormat format) {
        try { return ImageWriter.toBytes(image, format); }
        catch (IOException e) { throw new PipelineException("Failed to encode image", e); }
    }

    /**
     * Returns the result as a byte array with quality control.
     */
    public byte[] writeToBytes(ImageFormat format, double quality) {
        try { return ImageWriter.toBytes(image, format, quality); }
        catch (IOException e) { throw new PipelineException("Failed to encode image", e); }
    }

    /**
     * Returns the intermediate {@link BufferedImage} result.
     * Useful when further manual processing is needed after the pipeline.
     */
    public BufferedImage get() {
        return image;
    }
}
