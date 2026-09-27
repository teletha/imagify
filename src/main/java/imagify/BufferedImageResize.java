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
 * Supports resampling algorithms: BILINEAR, CATROM, MITCHELL, LANCZOS3, HQX (xBRZ).
 */
public final class BufferedImageResize {

    private BufferedImageResize() {}

    public static BufferedImage resize(BufferedImage source, int targetW, int targetH, ResizeAlgorithm algorithm) {
        if (targetW <= 0 || targetH <= 0) {
            throw new IllegalArgumentException("Target dimensions must be positive");
        }
        BufferedImage src = ensureARGB(source);

        if (algorithm == ResizeAlgorithm.NEAREST) {
            return resizeNearest(src, targetW, targetH);
        } else if (algorithm == ResizeAlgorithm.AREA) {
            return resizeArea(src, targetW, targetH);
        } else if (algorithm == ResizeAlgorithm.BILINEAR) {
            return resizeBilinear(src, targetW, targetH);
        } else if (algorithm == ResizeAlgorithm.HQX) {
            return XbrzScale.resize(src, targetW, targetH);
        } else {
            return resizeKernel(src, targetW, targetH, algorithm);
        }
    }

    /** Nearest neighbour - picks the closest source pixel. */
    private static BufferedImage resizeNearest(BufferedImage src, int targetW, int targetH) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        if (srcW == targetW && srcH == targetH) return copyImage(src);

        double xRatio = (double) srcW / targetW;
        double yRatio = (double) srcH / targetH;
        int[] srcPixels = getRGBArray(src);
        int[] dstPixels = new int[targetW * targetH];

        for (int y = 0; y < targetH; y++) {
            int srcY = (int) Math.floor((y + 0.5) * yRatio - 0.5);
            srcY = Math.max(0, Math.min(srcH - 1, srcY));
            for (int x = 0; x < targetW; x++) {
                int srcX = (int) Math.floor((x + 0.5) * xRatio - 0.5);
                srcX = Math.max(0, Math.min(srcW - 1, srcX));
                dstPixels[y * targetW + x] = srcPixels[srcY * srcW + srcX];
            }
        }
        return makeImage(dstPixels, targetW, targetH);
    }

    /** Area averaging (box filter) - each output pixel is the average of the corresponding source region. */
    private static BufferedImage resizeArea(BufferedImage src, int targetW, int targetH) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        if (srcW == targetW && srcH == targetH) return copyImage(src);

        double xRatio = (double) srcW / targetW;
        double yRatio = (double) srcH / targetH;
        int[] srcPixels = getRGBArray(src);
        int[] dstPixels = new int[targetW * targetH];

        for (int y = 0; y < targetH; y++) {
            double sy0 = y * yRatio;
            double sy1 = (y + 1) * yRatio;
            int y0 = (int) Math.floor(sy0);
            int y1 = (int) Math.ceil(sy1);
            y0 = Math.max(0, Math.min(srcH - 1, y0));
            y1 = Math.max(0, Math.min(srcH, y1));

            for (int x = 0; x < targetW; x++) {
                double sx0 = x * xRatio;
                double sx1 = (x + 1) * xRatio;
                int x0 = (int) Math.floor(sx0);
                int x1 = (int) Math.ceil(sx1);
                x0 = Math.max(0, Math.min(srcW - 1, x0));
                x1 = Math.max(0, Math.min(srcW, x1));

                long a = 0, r = 0, g = 0, b = 0;
                int count = 0;
                for (int sy = y0; sy < y1; sy++) {
                    int rowOff = sy * srcW;
                    for (int sx = x0; sx < x1; sx++) {
                        int px = srcPixels[rowOff + sx];
                        a += (px >>> 24) & 0xFF;
                        r += (px >> 16) & 0xFF;
                        g += (px >> 8) & 0xFF;
                        b += px & 0xFF;
                        count++;
                    }
                }
                if (count > 0) {
                    dstPixels[y * targetW + x] = (clamp((int) Math.round(a / (double) count)) << 24)
                            | (clamp((int) Math.round(r / (double) count)) << 16)
                            | (clamp((int) Math.round(g / (double) count)) << 8)
                            | clamp((int) Math.round(b / (double) count));
                }
            }
        }
        return makeImage(dstPixels, targetW, targetH);
    }

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

                int a = blend(invDx * invDy, (tl >>> 24) & 0xFF, invDx * dy, (tr >>> 24) & 0xFF,
                        dx * invDy, (bl >>> 24) & 0xFF, dx * dy, (br >>> 24) & 0xFF);
                int r = blend(invDx * invDy, (tl >> 16) & 0xFF, invDx * dy, (tr >> 16) & 0xFF,
                        dx * invDy, (bl >> 16) & 0xFF, dx * dy, (br >> 16) & 0xFF);
                int g = blend(invDx * invDy, (tl >> 8) & 0xFF, invDx * dy, (tr >> 8) & 0xFF,
                        dx * invDy, (bl >> 8) & 0xFF, dx * dy, (br >> 8) & 0xFF);
                int b = blend(invDx * invDy, tl & 0xFF, invDx * dy, tr & 0xFF,
                        dx * invDy, bl & 0xFF, dx * dy, br & 0xFF);
                dstPixels[y * targetW + x] = (clamp(a) << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return makeImage(dstPixels, targetW, targetH);
    }

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

        int[][] tmpA = new int[srcH][targetW];
        int[][] tmpR = new int[srcH][targetW];
        int[][] tmpG = new int[srcH][targetW];
        int[][] tmpB = new int[srcH][targetW];

        for (int sy = 0; sy < srcH; sy++) {
            int rowOff = sy * srcW;
            for (int tx = 0; tx < targetW; tx++) {
                double a = 0, r = 0, g = 0, b = 0, tw = 0;
                for (int i = 0; i < xOffs[tx].length; i++) {
                    int px = srcPixels[rowOff + xOffs[tx][i]];
                    double w = xWts[tx][i];
                    a += ((px >>> 24) & 0xFF) * w;
                    r += ((px >> 16) & 0xFF) * w;
                    g += ((px >> 8) & 0xFF) * w;
                    b += (px & 0xFF) * w;
                    tw += w;
                }
                if (tw > 0) {
                    tmpA[sy][tx] = (int) Math.round(a / tw);
                    tmpR[sy][tx] = (int) Math.round(r / tw);
                    tmpG[sy][tx] = (int) Math.round(g / tw);
                    tmpB[sy][tx] = (int) Math.round(b / tw);
                }
            }
        }

        int[] dstPixels = new int[targetW * targetH];
        for (int ty = 0; ty < targetH; ty++) {
            for (int tx = 0; tx < targetW; tx++) {
                double a = 0, r = 0, g = 0, b = 0, tw = 0;
                for (int i = 0; i < yOffs[ty].length; i++) {
                    int sy = yOffs[ty][i];
                    double w = yWts[ty][i];
                    a += tmpA[sy][tx] * w;
                    r += tmpR[sy][tx] * w;
                    g += tmpG[sy][tx] * w;
                    b += tmpB[sy][tx] * w;
                    tw += w;
                }
                if (tw > 0) {
                    dstPixels[ty * targetW + tx] = (clamp((int) Math.round(a / tw)) << 24)
                            | (clamp((int) Math.round(r / tw)) << 16)
                            | (clamp((int) Math.round(g / tw)) << 8)
                            | clamp((int) Math.round(b / tw));
                }
            }
        }
        return makeImage(dstPixels, targetW, targetH);
    }

    private static int blend(double w0, int c0, double w1, int c1, double w2, int c2, double w3, int c3) {
        return clamp((int) ((w0 * c0 + w1 * c1 + w2 * c2 + w3 * c3) + 0.5));
    }

    private static int clamp(double v, double min, double max) { return (int) Math.max(min, Math.min(max, v)); }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

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
