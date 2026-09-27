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

    CATROM(2) {
        @Override
        protected double kernel(double x) {
            x = Math.abs(x);
            if (x < 1.0) return 1.5 * x * x * x - 2.5 * x * x + 1.0;
            if (x < 2.0) return -0.5 * x * x * x + 2.5 * x * x - 4.0 * x + 2.0;
            return 0.0;
        }
    },

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

    LANCZOS2(2) {
        @Override
        protected double kernel(double x) {
            if (x == 0.0) return 1.0;
            x = Math.abs(x);
            if (x >= 2.0) return 0.0;
            return Math.sin(Math.PI * x) * Math.sin(Math.PI * x / 2.0) / (Math.PI * x * (Math.PI * x / 2.0));
        }
    },

    LANCZOS3(3) {
        @Override
        protected double kernel(double x) {
            if (x == 0.0) return 1.0;
            x = Math.abs(x);
            if (x >= 3.0) return 0.0;
            return Math.sin(Math.PI * x) * Math.sin(Math.PI * x / 3.0) / (Math.PI * x * (Math.PI * x / 3.0));
        }
    },

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

    protected abstract double kernel(double x);

    public int getSupport() {
        return support;
    }
}
