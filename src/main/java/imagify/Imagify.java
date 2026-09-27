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
import java.util.ArrayList;
import java.util.List;

/**
 * Fluent pipeline API for image processing.
 *
 * <p>Supports chaining: {@code read → resize → write}.</p>
 *
 * <p>{@link ImageReader#read} always returns a {@link FrameSequence}, which may
 * contain a single frame (for JPEG, PNG, etc.) or multiple frames (for animated
 * formats like GIF and animated WebP).</p>
 *
 * <p>Writing automatically detects whether the data is animated and the target
 * format supports animation: if both conditions are met, the output is an
 * animated image; otherwise the first frame is written as a still image.</p>
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
 * // Get the first frame as a BufferedImage
 * BufferedImage img = ImagePipeline.read(path).toBufferedImage();
 * }</pre>
 */
public final class Imagify {

    private static final class PipelineException extends RuntimeException {
        PipelineException(String message, Throwable cause) { super(message, cause); }
    }

    private FrameSequence frameSequence;

    private Imagify() {}

    // ═══════════════════════════════════════════════════
    //  Read phase
    // ═══════════════════════════════════════════════════

    /**
     * Starts a pipeline by reading from a byte array.
     * Format is auto-detected from magic bytes.
     *
     * @param data the encoded image data
     * @return this pipeline for chaining
     */
    public static Imagify read(byte[] data) {
        Imagify pipe = new Imagify();
        try { pipe.frameSequence = ImageReader.read(data); }
        catch (IOException e) { throw new PipelineException("Failed to read image", e); }
        return pipe;
    }

    /**
     * Starts a pipeline by reading from a file path.
     * Format is auto-detected from extension or header.
     *
     * @param path the file path
     * @return this pipeline for chaining
     */
    public static Imagify read(Path path) {
        Imagify pipe = new Imagify();
        try { pipe.frameSequence = ImageReader.read(path); }
        catch (IOException e) { throw new PipelineException("Failed to read image", e); }
        return pipe;
    }

    /**
     * Starts a pipeline by reading from an InputStream.
     * Format is auto-detected from magic bytes.
     *
     * @param in the input stream
     * @return this pipeline for chaining
     */
    public static Imagify read(InputStream in) {
        Imagify pipe = new Imagify();
        try { pipe.frameSequence = ImageReader.read(in); }
        catch (IOException e) { throw new PipelineException("Failed to read image", e); }
        return pipe;
    }

    // ═══════════════════════════════════════════════════
    //  Transform phase
    // ═══════════════════════════════════════════════════

    /**
     * Resizes all frames to exact dimensions.
     */
    public Imagify resize(int targetW, int targetH) {
        return resize(targetW, targetH, ResizeAlgorithm.BILINEAR);
    }

    /**
     * Resizes all frames to exact dimensions using the specified algorithm.
     */
    public Imagify resize(int targetW, int targetH, ResizeAlgorithm algorithm) {
        this.frameSequence = resizeFrameSequence(this.frameSequence, targetW, targetH, algorithm);
        return this;
    }

    private static FrameSequence resizeFrameSequence(FrameSequence seq, int targetW, int targetH, ResizeAlgorithm algorithm) {
        var frames = new ArrayList<BufferedImage>(seq.frameCount());
        for (BufferedImage frame : seq.frames()) {
            frames.add(BufferedImageResize.resize(frame, targetW, targetH, algorithm));
        }
        return new FrameSequence(frames, seq.delaysMs(), seq.loopCount());
    }

    /**
     * Resizes by scale factor.
     */
    public Imagify resize(double scale) {
        return resize(scale, ResizeAlgorithm.BILINEAR);
    }

    /**
     * Resizes by scale factor using the specified algorithm.
     */
    public Imagify resize(double scale, ResizeAlgorithm algorithm) {
        return resize((int) Math.round(frameSequence.toBufferedImage().getWidth() * scale),
                      (int) Math.round(frameSequence.toBufferedImage().getHeight() * scale),
                      algorithm);
    }

    // ═══════════════════════════════════════════════════
    //  Write phase
    // ═══════════════════════════════════════════════════

    /**
     * Writes the result to a file path with auto-detected format from extension.
     *
     * <p>Automatic animation: if the data has multiple frames and the target
     * format supports animation, it is written as an animated image;
     * otherwise the first frame is written as a still image.</p>
     */
    public Imagify writeTo(Path path) {
        try {
            ImageFormat fmt = ImageFormat.fromPath(path);
            ImageWriter.toFile(frameSequence, fmt, path);
        } catch (IOException e) { throw new PipelineException("Failed to write image", e); }
        return this;
    }

    /**
     * Writes the result to a file path with auto-detected format and quality.
     *
     * <p>Automatic animation: if the data has multiple frames and the target
     * format supports animation, it is written as an animated image;
     * otherwise the first frame is written as a still image.</p>
     *
     * @param quality {@code 0.0} (smallest) to {@code 1.0} (largest)
     */
    public Imagify writeTo(Path path, double quality) {
        try {
            ImageFormat fmt = ImageFormat.fromPath(path);
            ImageWriter.toFile(frameSequence, fmt, quality, path);
        } catch (IOException e) { throw new PipelineException("Failed to write image", e); }
        return this;
    }

    /**
     * Writes the result to a file path with explicit format.
     *
     * <p>Automatic animation: if the data has multiple frames and the format
     * supports animation, it is written as an animated image;
     * otherwise the first frame is written as a still image.</p>
     */
    public Imagify writeTo(Path path, ImageFormat format) {
        try { ImageWriter.toFile(frameSequence, format, path); }
        catch (IOException e) { throw new PipelineException("Failed to write image", e); }
        return this;
    }

    /**
     * Writes the result to a file path with explicit format and quality.
     *
     * <p>Automatic animation: if the data has multiple frames and the format
     * supports animation, it is written as an animated image;
     * otherwise the first frame is written as a still image.</p>
     *
     * @param quality {@code 0.0} (smallest) to {@code 1.0} (largest)
     */
    public Imagify writeTo(Path path, ImageFormat format, double quality) {
        try { ImageWriter.toFile(frameSequence, format, quality, path); }
        catch (IOException e) { throw new PipelineException("Failed to write image", e); }
        return this;
    }

    /**
     * Writes the result to an OutputStream with explicit format.
     *
     * <p>If the data has multiple frames and the format supports animation,
     * the animation is encoded; otherwise the first frame is written as a still image.</p>
     */
    public Imagify writeTo(OutputStream out, ImageFormat format) {
        try {
            byte[] bytes = ImageWriter.toBytes(frameSequence, format);
            out.write(bytes);
        } catch (IOException e) { throw new PipelineException("Failed to write image", e); }
        return this;
    }

    /**
     * Returns the result as a byte array.
     *
     * <p>Automatic animation: if the data has multiple frames and the format
     * supports animation, the animation bytes are returned; otherwise the
     * first frame is returned as a still image.</p>
     */
    public byte[] writeToBytes(ImageFormat format) {
        try { return ImageWriter.toBytes(frameSequence, format); }
        catch (IOException e) { throw new PipelineException("Failed to encode image", e); }
    }

    /**
     * Returns the result as a byte array with quality control.
     *
     * <p>Automatic animation: if the data has multiple frames and the format
     * supports animation, the animation bytes are returned; otherwise the
     * first frame is returned as a still image.</p>
     *
     * @param quality {@code 0.0} (smallest) to {@code 1.0} (largest)
     */
    public byte[] writeToBytes(ImageFormat format, double quality) {
        try { return ImageWriter.toBytes(frameSequence, format, quality); }
        catch (IOException e) { throw new PipelineException("Failed to encode image", e); }
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Returns the first frame as a {@link BufferedImage}.
     *
     * @return the first frame
     */
    public BufferedImage toBufferedImage() {
        return frameSequence.toBufferedImage();
    }

    /**
     * Returns the underlying {@link FrameSequence}.
     *
     * @return the frame sequence
     */
    public FrameSequence get() {
        return frameSequence;
    }

    /**
     * @return the number of frames
     */
    public int frameCount() {
        return frameSequence.frameCount();
    }
}
