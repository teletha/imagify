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

/**
 * High-quality image resampling algorithms for resizing.
 * Each algorithm provides a kernel function and a support radius.
 */
public enum ResizeAlgorithm {

    /** Nearest neighbour - preserves exact pixel values, essential for pixel art. */
    NEAREST(0) {
        @Override
        protected double kernel(double x) {
            return 0.0; // handled specially
        }
    },

    /** Box / area averaging - ideal for downscaling, no ringing. */
    AREA(0) {
        @Override
        protected double kernel(double x) {
            return 0.0; // handled specially
        }
    },

    /** Linear interpolation between the two nearest neighbours - the cheapest smooth filter. */
    BILINEAR(1) {
        @Override
        protected double kernel(double x) {
            x = Math.abs(x);
            return x < 1.0 ? 1.0 - x : 0.0;
        }
    },

    /** Bicubic B-spline (B=1, C=0) - smoother than Catmull-Rom. */
    BSPLINE(2) {
        @Override
        protected double kernel(double x) {
            x = Math.abs(x);
            if (x < 1.0) return (2.0 / 3.0) - x * x + 0.5 * x * x * x;
            if (x < 2.0) return (4.0 / 3.0) - 2.0 * x + x * x - (1.0 / 6.0) * x * x * x;
            return 0.0;
        }
    },

    /** Catmull-Rom bicubic (B=0, C=0.5) - sharper than Mitchell, at the price of some ringing. */
    CATROM(2) {
        @Override
        protected double kernel(double x) {
            x = Math.abs(x);
            if (x < 1.0) return 1.5 * x * x * x - 2.5 * x * x + 1.0;
            if (x < 2.0) return -0.5 * x * x * x + 2.5 * x * x - 4.0 * x + 2.0;
            return 0.0;
        }
    },

    /** Mitchell-Netravali bicubic (B=C=1/3) - the softest of the cubics, and free of ringing. */
    MITCHELL(2) {
        @Override
        protected double kernel(double x) {
            x = Math.abs(x);
            double B = 1.0 / 3.0;
            double C = 1.0 / 3.0;
            double x2 = x * x;
            double x3 = x2 * x;
            if (x < 1.0) {
                return ((12.0 - 9.0 * B - 6.0 * C) * x3 + (-18.0 + 12.0 * B + 6.0 * C) * x2 + (6.0 - 2.0 * B)) / 6.0;
            } else if (x < 2.0) {
                return ((-B - 6.0 * C) * x3 + (6.0 * B + 30.0 * C) * x2 + (-12.0 * B - 48.0 * C) * x + (8.0 * B + 24.0 * C)) / 6.0;
            }
            return 0.0;
        }
    },

    /** Gaussian blur kernel - smooth, no negative lobes. */
    GAUSSIAN(2) {
        @Override
        protected double kernel(double x) {
            x = Math.abs(x);
            if (x >= 2.0) return 0.0;
            // sigma chosen so kernel(2) ≈ 0
            return Math.exp(-2.0 * x * x);
        }
    },

    /** Lanczos with a two-lobe window - a sinc truncated at two samples on each side. */
    LANCZOS2(2) {
        @Override
        protected double kernel(double x) {
            if (x == 0.0) return 1.0;
            x = Math.abs(x);
            if (x >= 2.0) return 0.0;
            return Math.sin(Math.PI * x) * Math.sin(Math.PI * x / 2.0) / (Math.PI * x * (Math.PI * x / 2.0));
        }
    },

    /** Lanczos with a three-lobe window - the sharpest resampling filter here, and the ringiest. */
    LANCZOS3(3) {
        @Override
        protected double kernel(double x) {
            if (x == 0.0) return 1.0;
            x = Math.abs(x);
            if (x >= 3.0) return 0.0;
            return Math.sin(Math.PI * x) * Math.sin(Math.PI * x / 3.0) / (Math.PI * x * (Math.PI * x / 3.0));
        }
    },

    /**
     * xBRZ, which is an edge-aware upscale rather than a convolution: it finds similar neighbours
     * and blends them along their edge rather than averaging everything in reach. It is therefore
     * the algorithm to pick for pixel art, where a blur is a defect rather than a smoothness. The
     * kernel is unused, and the support is zero, because this one is not a convolution at all.
     */
    HQX(0) {
        @Override
        protected double kernel(double x) {
            return 0.0;
        }
    };

    /** The default algorithm. */
    public static final ResizeAlgorithm DEFAULT = CATROM;

    private final int support;

    ResizeAlgorithm(int support) {
        this.support = support;
    }

    /**
     * The resampling weight at {@code x} samples from the pixel being looked at, where {@code x} is
     * the distance already made positive. It is zero outside the support radius, so a resampler can
     * walk the window without testing the bound itself.
     *
     * <p>Override this to add a filter; the algorithms that are not convolutions return zero because
     * nothing ever calls them.
     *
     * @param x the distance from the sample point, in source pixels, absolute value
     * @return the weight to give that sample
     */
    protected abstract double kernel(double x);

    /**
     * How many source pixels on each side of a sample the kernel reaches, which is what decides how
     * wide the resampling window has to be.
     *
     * @return the support radius in source pixels
     */
    public int getSupport() {
        return support;
    }
}
