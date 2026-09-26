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

import java.awt.image.BufferedImage;

/**
 * High-quality {@link BufferedImage} resizing utility.
 * Supports resampling algorithms: BILINEAR, CATROM, MITCHELL, LANCZOS3, HQX (xBRZ).
 *
 * <p>Usage example:
 * <pre>{@code
 * BufferedImage resized = BufferedImageResize.resize(original, 800, 600, ResizeAlgorithm.LANCZOS3);
 * }</pre>
 */
public final class BufferedImageResize {

    private BufferedImageResize() {
    }

    /**
     * Resizes the source image to the specified dimensions using the given algorithm.
     *
     * @param source the source image (any {@link BufferedImage} type)
     * @param targetW target width (must be positive)
     * @param targetH target height (must be positive)
     * @param algorithm the resampling algorithm to use
     * @return the resized image
     */
    public static BufferedImage resize(BufferedImage source, int targetW, int targetH, ResizeAlgorithm algorithm) {
        if (targetW <= 0 || targetH <= 0) {
            throw new IllegalArgumentException("Target dimensions must be positive");
        }
        BufferedImage src = ensureARGB(source);

        if (algorithm == ResizeAlgorithm.BILINEAR) {
            return resizeBilinear(src, targetW, targetH);
        } else if (algorithm == ResizeAlgorithm.HQX) {
            return XbrzScale.resize(src, targetW, targetH);
        } else {
            return resizeKernel(src, targetW, targetH, algorithm);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // BILINEAR interpolation
    // ═══════════════════════════════════════════════════════════════════

    private static BufferedImage resizeBilinear(BufferedImage src, int targetW, int targetH) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        if (srcW == targetW && srcH == targetH) return copyImage(src);

        double xRatio = (double) srcW / targetW;
        double yRatio = (double) srcH / targetH;
        int[] srcPixels = getRGBArray(src);
        int[] dstPixels = new int[targetW * targetH];

        for (int y = 0; y < targetH; y++) {
            double srcY = clamp((y + 0.5) * yRatio - 0.5, 0, srcH - 1);
            int y0 = (int) Math.floor(srcY);
            int y1 = Math.min(srcH - 1, y0 + 1);
            double dy = srcY - y0;
            double invDy = 1.0 - dy;

            for (int x = 0; x < targetW; x++) {
                double srcX = clamp((x + 0.5) * xRatio - 0.5, 0, srcW - 1);
                int x0 = (int) Math.floor(srcX);
                int x1 = Math.min(srcW - 1, x0 + 1);
                double dx = srcX - x0;
                double invDx = 1.0 - dx;

                int tl = srcPixels[y0 * srcW + x0];
                int tr = srcPixels[y0 * srcW + x1];
                int bl = srcPixels[y1 * srcW + x0];
                int br = srcPixels[y1 * srcW + x1];

                int r = blend(invDx * invDy, (tl >> 16) & 0xFF, invDx * dy, (tr >> 16) & 0xFF, dx * invDy, (bl >> 16) & 0xFF, dx * dy, (br >> 16) & 0xFF);
                int g = blend(invDx * invDy, (tl >> 8) & 0xFF, invDx * dy, (tr >> 8) & 0xFF, dx * invDy, (bl >> 8) & 0xFF, dx * dy, (br >> 8) & 0xFF);
                int b = blend(invDx * invDy, tl & 0xFF, invDx * dy, tr & 0xFF, dx * invDy, bl & 0xFF, dx * dy, br & 0xFF);
                dstPixels[y * targetW + x] = (0xFF << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return makeImage(dstPixels, targetW, targetH);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Kernel-based resampling (CATROM, MITCHELL, LANCZOS3)
    // ═══════════════════════════════════════════════════════════════════

    private static BufferedImage resizeKernel(BufferedImage src, int targetW, int targetH, ResizeAlgorithm algorithm) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        if (srcW == targetW && srcH == targetH) return copyImage(src);

        int support = algorithm.getSupport();
        double xScale = (double) srcW / targetW;
        double yScale = (double) srcH / targetH;
        int[] srcPixels = getRGBArray(src);

        double[][] xWts = new double[targetW][];
        int[][] xOffs = new int[targetW][];
        for (int tx = 0; tx < targetW; tx++) {
            double sx = (tx + 0.5) * xScale - 0.5;
            int x0 = Math.max(0, (int) Math.floor(sx - support));
            int x1 = Math.min(srcW - 1, (int) Math.ceil(sx + support));
            xOffs[tx] = new int[x1 - x0 + 1];
            xWts[tx] = new double[x1 - x0 + 1];
            for (int i = 0; i < xOffs[tx].length; i++) {
                xOffs[tx][i] = x0 + i;
                xWts[tx][i] = algorithm.kernel(((x0 + i) - sx) / xScale);
            }
        }

        double[][] yWts = new double[targetH][];
        int[][] yOffs = new int[targetH][];
        for (int ty = 0; ty < targetH; ty++) {
            double sy = (ty + 0.5) * yScale - 0.5;
            int y0 = Math.max(0, (int) Math.floor(sy - support));
            int y1 = Math.min(srcH - 1, (int) Math.ceil(sy + support));
            yOffs[ty] = new int[y1 - y0 + 1];
            yWts[ty] = new double[y1 - y0 + 1];
            for (int i = 0; i < yOffs[ty].length; i++) {
                yOffs[ty][i] = y0 + i;
                yWts[ty][i] = algorithm.kernel(((y0 + i) - sy) / yScale);
            }
        }

        int[][] tmpR = new int[srcH][targetW];
        int[][] tmpG = new int[srcH][targetW];
        int[][] tmpB = new int[srcH][targetW];

        for (int sy = 0; sy < srcH; sy++) {
            int rowOff = sy * srcW;
            for (int tx = 0; tx < targetW; tx++) {
                double r = 0, g = 0, b = 0, tw = 0;
                for (int i = 0; i < xOffs[tx].length; i++) {
                    int px = srcPixels[rowOff + xOffs[tx][i]];
                    double w = xWts[tx][i];
                    r += ((px >> 16) & 0xFF) * w;
                    g += ((px >> 8) & 0xFF) * w;
                    b += (px & 0xFF) * w;
                    tw += w;
                }
                if (tw > 0) {
                    tmpR[sy][tx] = (int) Math.round(r / tw);
                    tmpG[sy][tx] = (int) Math.round(g / tw);
                    tmpB[sy][tx] = (int) Math.round(b / tw);
                }
            }
        }

        int[] dstPixels = new int[targetW * targetH];
        for (int ty = 0; ty < targetH; ty++) {
            for (int tx = 0; tx < targetW; tx++) {
                double r = 0, g = 0, b = 0, tw = 0;
                for (int i = 0; i < yOffs[ty].length; i++) {
                    int sy = yOffs[ty][i];
                    double w = yWts[ty][i];
                    r += tmpR[sy][tx] * w;
                    g += tmpG[sy][tx] * w;
                    b += tmpB[sy][tx] * w;
                    tw += w;
                }
                if (tw > 0) {
                    dstPixels[ty * targetW + tx] = (0xFF << 24) | (clamp((int) Math.round(r / tw)) << 16) | (clamp((int) Math
                            .round(g / tw)) << 8) | clamp((int) Math.round(b / tw));
                }
            }
        }
        return makeImage(dstPixels, targetW, targetH);
    }

    // ═══════════════════════════════════════════════════════════════════
    // HQX / xBRZ upscaling (delegates to XbrzScale)
    // ═══════════════════════════════════════════════════════════════════

    private static BufferedImage resizeHQX(BufferedImage src, int targetW, int targetH) {
        return XbrzScale.resize(src, targetW, targetH);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════

    private static int blend(double w0, int c0, double w1, int c1, double w2, int c2, double w3, int c3) {
        return clamp((int) ((w0 * c0 + w1 * c1 + w2 * c2 + w3 * c3) + 0.5));
    }

    private static int clamp(double v, double min, double max) {
        return (int) Math.max(min, Math.min(max, v));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static int[] getRGBArray(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] pixels = new int[w * h];
        img.getRGB(0, 0, w, h, pixels, 0, w);
        return pixels;
    }

    private static BufferedImage makeImage(int[] pixels, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, pixels, 0, w);
        return img;
    }

    private static BufferedImage copyImage(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), src.getType());
        copy.getGraphics().drawImage(src, 0, 0, null);
        return copy;
    }

    private static BufferedImage ensureARGB(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_ARGB) return img;
        BufferedImage converted = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        converted.getGraphics().drawImage(img, 0, 0, null);
        return converted;
    }
}
