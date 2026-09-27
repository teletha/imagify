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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

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
 * Imagify
 *     .read(path)
 *     .resize(800, 600, ResizeAlgorithm.LANCZOS3)
 *     .writeTo(path);
 *
 * // Read → Resize → Write to bytes
 * byte[] png = Imagify
 *     .read(bytes)
 *     .resize(0.5)
 *     .writeToBytes(ImageFormat.PNG);
 *
 * // Keep the aspect ratio, cropping whatever overflows the box
 * Imagify.read(path).resizeToFill(1200, 630).writeTo(hero);
 *
 * // Keep the aspect ratio, padding instead of cropping
 * Imagify.read(path).padTo(1200, 630, Color.WHITE).writeTo(hero);
 *
 * // Take the poster frame of an animation
 * Imagify.read(gif).toStillImage().writeTo(poster);
 *
 * // Get the first frame as a BufferedImage
 * BufferedImage img = Imagify.read(path).toBufferedImage();
 * }</pre>
 *
 * <p>Every transform applies to all frames of a sequence and leaves its timing alone. The resizing
 * methods come in pairs that differ in what they do with a shape that does not match the target:
 * {@link #resize} stretches to fit exactly, {@link #resizeToFit} and {@link #resizeInside} keep the
 * aspect ratio and leave the result smaller than the target, {@link #resizeToFill} keeps it exact by
 * cropping the overflow, and {@link #padTo} keeps it exact by adding the missing area.</p>
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
        return mapFrames(frame -> BufferedImageResize.resize(frame, targetW, targetH, algorithm));
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
        BufferedImage first = frameSequence.toBufferedImage();
        return resize((int) Math.round(first.getWidth() * scale),
                      (int) Math.round(first.getHeight() * scale), algorithm);
    }

    /**
     * Resizes so that the longest edge becomes {@code maxDimension}, keeping the aspect ratio.
     *
     * <p>The result is at most {@code maxDimension} on both edges. A source smaller than
     * {@code maxDimension} is scaled up, which is what the single argument
     * {@link ImageResizer#resizeToFit(BufferedImage, int)} does as well; use {@link #resize} when
     * only shrinking is wanted.</p>
     */
    public Imagify resizeToFit(int maxDimension) {
        return resizeToFit(maxDimension, ResizeAlgorithm.BILINEAR);
    }

    /**
     * Resizes so that the longest edge becomes {@code maxDimension}, keeping the aspect ratio.
     *
     * @see #resizeToFit(int)
     */
    public Imagify resizeToFit(int maxDimension, ResizeAlgorithm algorithm) {
        if (maxDimension <= 0) {
            throw new IllegalArgumentException("the longest edge must be positive, got " + maxDimension);
        }
        BufferedImage first = frameSequence.toBufferedImage();
        double scale = Math.min((double) maxDimension / first.getWidth(),
                                (double) maxDimension / first.getHeight());
        return resize(scale, algorithm);
    }

    /**
     * Scales down to the largest size that fits inside the given box, keeping the aspect ratio.
     *
     * <p>Unlike {@link #resizeToFill(int, int)} nothing is cropped and no padding is added, so the
     * result is smaller than the box on at least one edge whenever the source is not exactly the
     * shape of the box. A source already smaller than the box is left at its own size rather than
     * scaled up.</p>
     */
    public Imagify resizeInside(int targetW, int targetH) {
        return resizeInside(targetW, targetH, ResizeAlgorithm.BILINEAR);
    }

    /**
     * Scales down to the largest size that fits inside the given box, keeping the aspect ratio.
     *
     * @see #resizeInside(int, int)
     */
    public Imagify resizeInside(int targetW, int targetH, ResizeAlgorithm algorithm) {
        checkSize(targetW, targetH);
        BufferedImage first = frameSequence.toBufferedImage();
        if (first.getWidth() <= targetW && first.getHeight() <= targetH) {
            return this;
        }
        double scale = Math.min((double) targetW / first.getWidth(),
                                (double) targetH / first.getHeight());
        return resize(scale, algorithm);
    }

    /**
     * Scales and centre crops so the result is exactly the given size, filling the box completely.
     *
     * <p>The aspect ratio is not preserved: whatever the crop takes off is lost. This is the shape
     * most callers mean by "make it 800 by 600" and is the counterpart of {@link #padTo(int, int)},
     * which keeps the aspect ratio and adds the missing area instead.</p>
     */
    public Imagify resizeToFill(int targetW, int targetH) {
        return resizeToFill(targetW, targetH, ResizeAlgorithm.BILINEAR);
    }

    /**
     * Scales and centre crops so the result is exactly the given size, filling the box completely.
     *
     * @see #resizeToFill(int, int)
     */
    public Imagify resizeToFill(int targetW, int targetH, ResizeAlgorithm algorithm) {
        checkSize(targetW, targetH);
        BufferedImage first = frameSequence.toBufferedImage();
        double scale = Math.max((double) targetW / first.getWidth(),
                                (double) targetH / first.getHeight());
        // Rounding the scaled size up is what guarantees the crop below can never reach past the edge:
        // rounding to nearest would leave an image one pixel short on the axis that just matched.
        int scaledW = (int) Math.ceil(first.getWidth() * scale);
        int scaledH = (int) Math.ceil(first.getHeight() * scale);
        resize(scaledW, scaledH, algorithm);
        return crop((scaledW - targetW) / 2, (scaledH - targetH) / 2, targetW, targetH);
    }

    /**
     * Scales to fit inside the given box and centres the result on a canvas of exactly that size.
     *
     * <p>The area the image does not cover is left fully transparent, since the canvas is
     * {@link BufferedImage#TYPE_INT_ARGB}. Pass a colour to {@link #padTo(int, int, Color)} to fill
     * it instead.</p>
     */
    public Imagify padTo(int targetW, int targetH) {
        return padTo(targetW, targetH, null);
    }

    /**
     * Scales to fit inside the given box and centres the result on a canvas of exactly that size.
     *
     * @param background what to fill the uncovered area with, {@code null} meaning transparent
     * @see #padTo(int, int)
     */
    public Imagify padTo(int targetW, int targetH, Color background) {
        checkSize(targetW, targetH);
        resizeInside(targetW, targetH);
        int width = frameSequence.toBufferedImage().getWidth();
        int height = frameSequence.toBufferedImage().getHeight();
        int atX = (targetW - width) / 2;
        int atY = (targetH - height) / 2;
        Color fill = background == null ? new Color(0, 0, 0, 0) : background;
        return mapFrames(frame -> {
            BufferedImage canvas = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = canvas.createGraphics();
            try {
                graphics.setComposite(AlphaComposite.Src);
                graphics.setColor(fill);
                graphics.fillRect(0, 0, targetW, targetH);
                graphics.drawImage(frame, atX, atY, null);
            } finally {
                graphics.dispose();
            }
            return canvas;
        });
    }

    /**
     * Cuts the same rectangle out of every frame.
     *
     * <p>Each frame is cropped into an image of its own rather than being viewed, so a sequence
     * survives the cut without every frame pinning a copy of the whole original.</p>
     */
    public Imagify crop(int x, int y, int width, int height) {
        return mapFrames(frame -> BufferedImageTransform.crop(frame, x, y, width, height));
    }

    /**
     * Rotates every frame clockwise, resampling bilinearly unless the angle is a multiple of 90.
     *
     * @see BufferedImageTransform#rotate(BufferedImage, double)
     */
    public Imagify rotate(double degrees) {
        return rotate(degrees, ResizeAlgorithm.BILINEAR);
    }

    /**
     * Rotates every frame clockwise.
     *
     * @see BufferedImageTransform#rotate(BufferedImage, double, ResizeAlgorithm)
     */
    public Imagify rotate(double degrees, ResizeAlgorithm algorithm) {
        return mapFrames(frame -> BufferedImageTransform.rotate(frame, degrees, algorithm));
    }

    /**
     * Mirrors every frame left to right.
     */
    public Imagify flipHorizontal() {
        return mapFrames(BufferedImageTransform::flipHorizontal);
    }

    /**
     * Mirrors every frame top to bottom.
     */
    public Imagify flipVertical() {
        return mapFrames(BufferedImageTransform::flipVertical);
    }

    /**
     * Applies an operation to every frame, keeping the timing of the sequence untouched.
     */
    private Imagify mapFrames(UnaryOperator<BufferedImage> operation) {
        var frames = new ArrayList<BufferedImage>(frameSequence.frameCount());
        for (BufferedImage frame : frameSequence.frames()) {
            frames.add(operation.apply(frame));
        }
        this.frameSequence = new FrameSequence(frames, frameSequence.delaysMs(), frameSequence.loopCount());
        return this;
    }

    private static void checkSize(int targetW, int targetH) {
        if (targetW <= 0 || targetH <= 0) {
            throw new IllegalArgumentException(
                    "the target size must be positive, got " + targetW + "x" + targetH);
        }
    }

    /**
     * Drops every frame except the first one, turning an animation into a still image.
     *
     * <p>Without this, writing an animation to a format that supports animation
     * produces an animation even when only one frame is wanted. Chaining this
     * first forces the result down to a single frame.</p>
     *
     * <p>Usage:</p>
     * <pre>{@code
     * // Take the poster frame of an animated GIF
     * Imagify.read(path).toStillImage().writeTo(posterPath);
     * }</pre>
     *
     * @return this pipeline for chaining
     */
    public Imagify toStillImage() {
        this.frameSequence = this.frameSequence.firstFrameOnly();
        return this;
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
