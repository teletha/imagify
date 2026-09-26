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

/**
 * High-quality {@link BufferedImage} resizing utility.
 *
 * <p>Wraps {@link BufferedImageResize} with a cleaner API that supports
 * both direct dimension specification and percentage-based scaling.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * // Resize to exact dimensions
 * BufferedImage resized = ImageResizer.resize(original, 800, 600);
 *
 * // Resize by percentage
 * BufferedImage half = ImageResizer.resize(original, 0.5);
 *
 * // Resize with specific algorithm
 * BufferedImage sharp = ImageResizer.resize(original, 800, 600, ResizeAlgorithm.LANCZOS3);
 * }</pre>
 */
public final class ImageResizer {

    private ImageResizer() {}

    /**
     * Resizes the image to the specified dimensions using the default algorithm (BILINEAR).
     *
     * @param source the source image
     * @param targetW target width
     * @param targetH target height
     * @return the resized image
     */
    public static BufferedImage resize(BufferedImage source, int targetW, int targetH) {
        return resize(source, targetW, targetH, ResizeAlgorithm.BILINEAR);
    }

    /**
     * Resizes the image to the specified dimensions using the specified algorithm.
     *
     * @param source the source image
     * @param targetW target width
     * @param targetH target height
     * @param algorithm the resampling algorithm
     * @return the resized image
     */
    public static BufferedImage resize(BufferedImage source, int targetW, int targetH, ResizeAlgorithm algorithm) {
        return BufferedImageResize.resize(source, targetW, targetH, algorithm);
    }

    /**
     * Resizes the image by the specified scale factor (both dimensions).
     * Uses BILINEAR by default.
     *
     * @param source the source image
     * @param scale the scale factor (0.1 - 10.0)
     * @return the resized image
     */
    public static BufferedImage resize(BufferedImage source, double scale) {
        return resize(source, scale, ResizeAlgorithm.BILINEAR);
    }

    /**
     * Resizes the image by the specified scale factor using the specified algorithm.
     *
     * @param source the source image
     * @param scale the scale factor (0.1 - 10.0)
     * @param algorithm the resampling algorithm
     * @return the resized image
     * @throws IllegalArgumentException if scale is not positive
     */
    public static BufferedImage resize(BufferedImage source, double scale, ResizeAlgorithm algorithm) {
        if (scale <= 0) {
            throw new IllegalArgumentException("Scale must be positive, got: " + scale);
        }
        int targetW = (int) Math.round(source.getWidth() * scale);
        int targetH = (int) Math.round(source.getHeight() * scale);
        return resize(source, targetW, targetH, algorithm);
    }

    /**
     * Returns the longest edge resized to the specified maximum, maintaining aspect ratio.
     * Uses BILINEAR by default.
     *
     * @param source the source image
     * @param maxDimension the maximum dimension for the longest edge
     * @return the resized image maintaining aspect ratio
     */
    public static BufferedImage resizeToFit(BufferedImage source, int maxDimension) {
        return resizeToFit(source, maxDimension, ResizeAlgorithm.BILINEAR);
    }

    /**
     * Returns the longest edge resized to the specified maximum, maintaining aspect ratio.
     *
     * @param source the source image
     * @param maxDimension the maximum dimension for the longest edge
     * @param algorithm the resampling algorithm
     * @return the resized image maintaining aspect ratio
     */
    public static BufferedImage resizeToFit(BufferedImage source, int maxDimension, ResizeAlgorithm algorithm) {
        int width = source.getWidth();
        int height = source.getHeight();
        double scale = Math.min((double) maxDimension / width, (double) maxDimension / height);
        return resize(source, scale, algorithm);
    }
}
