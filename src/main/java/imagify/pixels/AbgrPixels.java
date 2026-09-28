/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.pixels;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;

/**
 * Moves pixels between {@code BufferedImage} and the tightly packed {@code A, B, G, R} byte order
 * this library's native codecs want on both sides of the boundary.
 *
 * <p>That byte order is what {@code libavif} calls {@code AVIF_RGB_FORMAT_ABGR}, what the jpegli
 * shim in {@code src/main/native/imagify_jpegli.c} is given, and what a
 * {@link BufferedImage#TYPE_4BYTE_ABGR} raster holds in memory, so an image of that exact type can
 * be handed to and from the native code without touching a single byte. The correspondence is worth
 * stating because it is easy to assume the opposite: the raster declares band offsets of
 * {@code 3, 2, 1, 0}, so the byte at offset 0 of a pixel really is its alpha, while
 * {@link Raster#getDataElements} reports the very same sample as {@code R, G, B, A}. Reading that
 * array as if it were the in-memory layout swaps red and blue in both directions without ever
 * failing, and {@link #toBufferedImage} and {@link #toAbgrBytes} are therefore the only two places
 * allowed to touch raw bytes.
 *
 * <p>It lives outside the codec packages so that both {@link imagify.avif.jna.AvifCodec} and
 * {@link imagify.jpeg.jna.JpegliCodec} share one conversion rather than keeping two that are only
 * ever going to disagree.
 *
 * <p>Every other layout is converted through the source {@link ColorModel}, which always yields
 * unassociated alpha, so that no premultiplication ever happens.
 */
public final class AbgrPixels {

    private AbgrPixels() {
        // utility class
    }

    /**
     * Wraps tightly packed A, B, G, R bytes into a new image.
     *
     * @param abgr at least {@code width * height * 4} bytes in A, B, G, R order
     * @param width image width
     * @param height image height
     * @return a {@link BufferedImage#TYPE_4BYTE_ABGR} image
     * @throws IllegalArgumentException when {@code abgr} is too short
     */
    public static BufferedImage toBufferedImage(byte[] abgr, int width, int height) {
        int expected = bytes(width, height);
        if (abgr.length < expected) {
            throw new IllegalArgumentException("expected " + expected
                    + " bytes for a " + width + "x" + height + " image but got " + abgr.length);
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
        if (isPacked(image, expected)) {
            System.arraycopy(abgr, 0, ((DataBufferByte) image.getRaster().getDataBuffer()).getData(), 0, expected);
        } else {
            // Unreachable for a freshly created image, but keeps the method correct should the JDK
            // ever change the layout of TYPE_4BYTE_ABGR.
            image.getRaster().setDataElements(0, 0, width, height, abgr);
        }
        return image;
    }

    /**
     * Extracts a sub-sampled region of an image as tightly packed A, B, G, R bytes.
     *
     * <p>Sub sampling takes the top left pixel of each {@code subX} by {@code subY} block, which is
     * what {@link javax.imageio.ImageReadParam#getSourceXSubsampling()} specifies.
     *
     * @param source the image to read
     * @param x left edge of the region, in image coordinates
     * @param y top edge of the region, in image coordinates
     * @param width region width, in destination pixels
     * @param height region height, in destination pixels
     * @param subX horizontal sub sampling factor, at least 1
     * @param subY vertical sub sampling factor, at least 1
     * @return {@code width * height * 4} bytes in A, B, G, R order
     * @throws IllegalArgumentException when a sub sampling factor is not positive
     */
    public static byte[] toAbgrBytes(RenderedImage source, int x, int y, int width, int height, int subX, int subY) {
        if (subX < 1 || subY < 1) {
            throw new IllegalArgumentException("sub sampling factors must be positive: " + subX + "x" + subY);
        }
        if (subX == 1 && subY == 1
                && source instanceof BufferedImage image
                && image.getType() == BufferedImage.TYPE_4BYTE_ABGR
                && isPacked(image, bytes(source.getWidth(), source.getHeight()))) {
            return copyRows(image.getRaster(), x, y, width, height);
        }
        int regionWidth = width * subX;
        int regionHeight = height * subY;
        int[] region = new int[regionWidth * regionHeight];
        getArgb(source, x, y, regionWidth, regionHeight, region);

        byte[] abgr = new byte[bytes(width, height)];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int pixel = region[row * subY * regionWidth + column * subX];
                int target = (row * width + column) * 4;
                abgr[target] = (byte) (pixel >>> 24);
                abgr[target + 1] = (byte) pixel;
                abgr[target + 2] = (byte) (pixel >> 8);
                abgr[target + 3] = (byte) (pixel >> 16);
            }
        }
        return abgr;
    }

    /**
     * Converts an image to a {@link BufferedImage#TYPE_4BYTE_ABGR} image of its own size.
     *
     * @param source the image to convert
     * @return {@code source} itself when it is already in that layout, a new image otherwise
     */
    static BufferedImage toAbgr(RenderedImage source) {
        if (source instanceof BufferedImage image
                && image.getType() == BufferedImage.TYPE_4BYTE_ABGR
                && isPacked(image, bytes(source.getWidth(), source.getHeight()))) {
            return image;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
        int[] argb = new int[Math.multiplyExact(width, height)];
        getArgb(source, source.getMinX(), source.getMinY(), width, height, argb);
        target.setRGB(0, 0, width, height, argb, 0, width);
        return target;
    }

    /**
     * Reads a region as sRGB, non premultiplied {@code A, R, G, B} integers.
     *
     * <p>A {@link BufferedImage} is asked through {@code getRGB()}, which is the one conversion every
     * implementation agrees on: it un-premultiplies, it reads in image rather than raster
     * coordinates, and it clips a request that reaches past the edge instead of throwing. The raster
     * of a {@link BufferedImage} is no substitute, because a view onto a window of a larger image
     * reports bounds that describe the larger image.
     *
     * <p>Any other {@link RenderedImage} is read through its {@link ColorModel} one sample at a time.
     * That is the only safe way to handle an arbitrary implementation: reading its raster directly
     * would hand back whatever band order and transfer function it happens to use. Out of range
     * pixels are clipped as well, which is what a partially covered sub sampling block at the right
     * or bottom edge of an image needs.
     *
     * @param source the image to read
     * @param x left edge of the region, in image coordinates
     * @param y top edge of the region, in image coordinates
     * @param width region width, at least 0
     * @param height region height, at least 0
     * @param argb a destination of at least {@code width * height} integers, where out of range
     *        pixels keep whatever the caller put there
     */
    private static void getArgb(RenderedImage source, int x, int y, int width, int height, int[] argb) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (source instanceof BufferedImage image) {
            // getRGB() rejects a request that reaches past the edge rather than trimming it, so the
            // overlap is worked out first and then scattered into place.
            int left = Math.max(x, image.getMinX());
            int top = Math.max(y, image.getMinY());
            int right = Math.min(x + width, image.getMinX() + image.getWidth());
            int bottom = Math.min(y + height, image.getMinY() + image.getHeight());
            if (left >= right || top >= bottom) {
                return;
            }
            int clippedWidth = right - left;
            int clippedHeight = bottom - top;
            int[] clipped = new int[clippedWidth * clippedHeight];
            image.getRGB(left, top, clippedWidth, clippedHeight, clipped, 0, clippedWidth);
            for (int row = 0; row < clippedHeight; row++) {
                System.arraycopy(clipped, row * clippedWidth,
                        argb, (top - y + row) * width + left - x, clippedWidth);
            }
            return;
        }
        Raster raster = source.getData();
        ColorModel model = source.getColorModel();
        if (model == null) {
            model = ColorModel.getRGBdefault();
        }
        // A RenderedImage's raster is specified to be in the image's own coordinate system, so image
        // coordinates address it directly. The raster's origin is only needed to know how far the
        // region may reach.
        int minX = raster.getMinX();
        int minY = raster.getMinY();
        int left = Math.max(x, minX);
        int top = Math.max(y, minY);
        int right = Math.min(x + width, minX + raster.getWidth());
        int bottom = Math.min(y + height, minY + raster.getHeight());
        for (int row = Math.max(0, top - y); row < Math.min(height, bottom - y); row++) {
            for (int column = Math.max(0, left - x); column < Math.min(width, right - x); column++) {
                argb[row * width + column] = model.getRGB(raster.getDataElements(left + column, top + row, null));
            }
        }
    }

    /**
     * @return the in-memory A, B, G, R bytes of a region, one unbreakable row per row
     */
    private static byte[] copyRows(Raster raster, int x, int y, int width, int height) {
        DataBufferByte buffer = (DataBufferByte) raster.getDataBuffer();
        byte[] all = buffer.getData();
        int stride = raster.getWidth() * 4;
        byte[] abgr = new byte[bytes(width, height)];
        for (int row = 0; row < height; row++) {
            System.arraycopy(all, buffer.getOffset() + (y + row) * stride + x * 4,
                    abgr, row * width * 4, width * 4);
        }
        return abgr;
    }

    /**
     * @return whether the image keeps all of its samples in one block of A, B, G, R bytes that
     *         starts at the beginning of its data buffer
     *
     * <p>The size comparison is the load bearing part. A raster says nothing about where inside its
     * data buffer its own first pixel lives, so a view onto a window of a larger image looks
     * exactly like a whole image until the buffer size gives it away; the image type alone is not
     * enough, because the view inherits the parent's colour model and reports as
     * {@link BufferedImage#TYPE_4BYTE_ABGR}.
     */
    private static boolean isPacked(BufferedImage image, int expected) {
        Raster raster = image.getRaster();
        if (!(raster.getDataBuffer() instanceof DataBufferByte buffer)
                || buffer.getNumBanks() != 1
                || buffer.getOffset() != 0
                || buffer.getSize() < expected) {
            return false;
        }
        return buffer.getSize() == raster.getWidth() * raster.getHeight() * 4
                && buffer.getData().length >= expected;
    }

    private static int bytes(int width, int height) {
        return Math.multiplyExact(Math.multiplyExact(width, height), 4);
    }
}
