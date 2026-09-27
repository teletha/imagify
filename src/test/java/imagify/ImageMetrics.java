/*
 * Copyright (C) 2026 The IMAGIFY Development Team
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
 * The image quality measures the comparison reports are built on.
 *
 * <p>Shared so that the format report and the resize report mean the same thing by PSNR and by
 * SSIM. A reader comparing a number in one report against the same number in the other should
 * not have to wonder whether it was computed differently.</p>
 */
public final class ImageMetrics {

    private ImageMetrics() {
    }

    /** Reads an image into one packed ARGB int per pixel, which is layout independent. */
    public static int[] argb(BufferedImage image) {
        int[] pixels = new int[image.getWidth() * image.getHeight()];
        int i = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                pixels[i++] = image.getRGB(x, y);
            }
        }
        return pixels;
    }

    public static boolean isOpaque(int[] pixels) {
        for (int pixel : pixels) {
            if ((pixel >>> 24) != 0xFF) {
                return false;
            }
        }
        return true;
    }

    /** Peak signal to noise ratio over the colour channels, in decibels. */
    public static double psnr(int[] reference, int[] actual) {
        if (reference.length != actual.length) {
            throw new IllegalArgumentException("cannot compare " + actual.length + " pixels against " + reference.length);
        }
        double squared = 0;
        for (int i = 0; i < reference.length; i++) {
            int a = reference[i];
            int b = actual[i];
            int dr = ((a >>> 16) & 0xFF) - ((b >>> 16) & 0xFF);
            int dg = ((a >>> 8) & 0xFF) - ((b >>> 8) & 0xFF);
            int db = (a & 0xFF) - (b & 0xFF);
            squared += (double) dr * dr + (double) dg * dg + (double) db * db;
        }
        double mse = squared / (reference.length * 3);
        return mse == 0 ? 99 : 10 * Math.log10(255.0 * 255.0 / mse);
    }

    /** Mean structural similarity over luma, averaged over the usual 8x8 windows. */
    public static double ssim(int[] reference, int[] actual, int width, int height) {
        final int window = 8;
        if (reference.length != actual.length) {
            throw new IllegalArgumentException("cannot compare " + actual.length + " pixels against " + reference.length);
        }
        if (width < window || height < window) {
            return 1;
        }
        double[] referenceLuma = luma(reference);
        double[] actualLuma = luma(actual);
        final double c1 = square(0.01 * 255);
        final double c2 = square(0.03 * 255);
        final int n = window * window;

        double total = 0;
        int windows = 0;
        for (int y = 0; y + window <= height; y++) {
            for (int x = 0; x + window <= width; x++) {
                double sumA = 0;
                double sumB = 0;
                for (int j = 0; j < window; j++) {
                    int row = (y + j) * width + x;
                    for (int i = 0; i < window; i++) {
                        sumA += referenceLuma[row + i];
                        sumB += actualLuma[row + i];
                    }
                }
                double meanA = sumA / n;
                double meanB = sumB / n;

                double varianceA = 0;
                double varianceB = 0;
                double covariance = 0;
                for (int j = 0; j < window; j++) {
                    int row = (y + j) * width + x;
                    for (int i = 0; i < window; i++) {
                        double da = referenceLuma[row + i] - meanA;
                        double db = actualLuma[row + i] - meanB;
                        varianceA += da * da;
                        varianceB += db * db;
                        covariance += da * db;
                    }
                }
                varianceA /= n - 1;
                varianceB /= n - 1;
                covariance /= n - 1;

                total += ((2 * meanA * meanB + c1) * (2 * covariance + c2)) / ((meanA * meanA + meanB * meanB + c1) * (varianceA + varianceB + c2));
                windows++;
            }
        }
        return total / windows;
    }

    /**
     * Mean gradient magnitude of luma, which says how much edge the image still has.
     *
     * <p>Unlike PSNR and SSIM this needs no reference image, so it is not subject to whatever
     * reference the comparison happens to use. It is what separates a result that is merely
     * faithful from one that is faithful <em>and</em> sharp: a blur and a ringing overshoot can
     * score alike on PSNR and not at all alike on this.</p>
     */
    public static double gradient(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < 3 || height < 3) {
            return 0;
        }
        double[] values = luma(argb(image));
        double total = 0;
        int count = 0;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int i = y * width + x;
                double gx = values[i + 1] - values[i - 1];
                double gy = values[i + width] - values[i - width];
                total += Math.sqrt(gx * gx + gy * gy);
                count++;
            }
        }
        return count == 0 ? 0 : total / count;
    }

    private static double[] luma(int[] pixels) {
        double[] out = new double[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            out[i] = 0.299 * ((pixel >>> 16) & 0xFF) + 0.587 * ((pixel >>> 8) & 0xFF) + 0.114 * (pixel & 0xFF);
        }
        return out;
    }

    private static double square(double value) {
        return value * value;
    }
}
