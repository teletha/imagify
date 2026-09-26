/*
 * Copyright (C) 2026 Nameless Production Committee
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.resize;

/**
 * High-quality image resampling algorithms for {@link java.awt.image.BufferedImage} resizing.
 * Each algorithm provides a kernel function and a support radius.
 */
public enum ResizeAlgorithm {

    /**
     * Bilinear interpolation.
     * Fast and simple, suitable for basic downscaling and upscaling.
     * Kernel support: 1 pixel.
     */
    BILINEAR(1) {
        @Override
        protected double kernel(double x) {
            x = Math.abs(x);
            return x < 1.0 ? 1.0 - x : 0.0;
        }
    },

    /**
     * Catmull-Rom (Catrom) cubic convolution interpolation.
     * Sharp results with good edge preservation.
     * Uses parameter a = -0.5 (standard Catmull-Rom spline).
     * Kernel support: 2 pixels.
     */
    CATROM(2) {
        @Override
        protected double kernel(double x) {
            x = Math.abs(x);
            if (x < 1.0) return 0.5 * (-x * x * x + 2.0 * x * x - 0.5 * x + 1.0);
            if (x < 2.0) return 0.5 * (x * x * x - 2.5 * x * x + 2.0 * x + 0.5);
            return 0.0;
        }
    },

    /**
     * Mitchell-Netravali (Mitchell) cubic filter.
     * Balanced between sharpness and smoothness, popular in image scaling.
     * Uses parameters B = 1/3, C = 1/3.
     * Kernel support: 2 pixels.
     */
    MITCHELL(2) {
        @Override
        protected double kernel(double x) {
            x = Math.abs(x);
            double B = 1.0 / 3.0;
            double C = 1.0 / 3.0;
            double x2 = x * x;
            double x3 = x2 * x;
            if (x < 1.0) {
                return ((12.0 - 9.0 * B - 6.0 * C) * x3
                        + (-18.0 + 12.0 * B + 6.0 * C) * x2
                        + (6.0 - 2.0 * B)) / 6.0;
            } else if (x < 2.0) {
                return ((-B - 6.0 * C) * x3
                        + (6.0 * B + 30.0 * C) * x2
                        + (-12.0 * B - 48.0 * C) * x
                        + (8.0 * B + 24.0 * C)) / 6.0;
            }
            return 0.0;
        }
    },

    /**
     * Lanczos-3 (Lanczos3) windowed sinc interpolation.
     * High-quality result with excellent frequency response.
     * Uses a sinc function windowed to a=3 lobes.
     * Kernel support: 3 pixels.
     */
    LANCZOS3(3) {
        @Override
        protected double kernel(double x) {
            if (x == 0.0) return 1.0;
            x = Math.abs(x);
            if (x >= 3.0) return 0.0;
            return Math.sin(Math.PI * x) * Math.sin(Math.PI * x / 3.0)
                    / (Math.PI * x * (Math.PI * x / 3.0));
        }
    },

    /**
     * HQX high-quality pixel-art upscaling.
     * Pattern-based edge-directed upscaling for integer scale factors (2x, 3x, 4x).
     * Kernel support: not applicable (uses pattern matching).
     */
    HQX(0) {
        @Override
        protected double kernel(double x) {
            return 0.0;
        }
    };

    private final int support;

    ResizeAlgorithm(int support) {
        this.support = support;
    }

    /**
     * Returns the kernel value at position x.
     *
     * @param x the distance from the sample point (normalized)
     * @return the kernel weight
     */
    protected abstract double kernel(double x);

    /**
     * Returns the support radius of this kernel (the number of pixels
     * on each side that contribute to the interpolation).
     *
     * @return the kernel support radius
     */
    public int getSupport() {
        return support;
    }
}
