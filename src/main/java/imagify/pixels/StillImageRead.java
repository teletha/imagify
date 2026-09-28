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

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageTypeSpecifier;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.util.Arrays;
import java.util.List;

/**
 * Applies a {@link ImageReadParam} to an image that has already been decoded.
 *
 * <p>Every {@link javax.imageio.ImageReader} in this library decodes a whole image and then hands it
 * to this class, which is the only thing that knows how a caller narrows one: the source region, the
 * source sub sampling factors, the destination image and the destination offset. It lives here
 * because the JPEG and AVIF readers have exactly the same answer to all of it, and two copies of
 * that answer would eventually disagree about where a sub sampling block starts.
 *
 * <p>What is <em>not</em> honoured is band selection: a JPEG cannot carry an alpha channel and an
 * AVIF is decoded to four channels whatever the caller asked for, so narrowing the bands would
 * mean dropping samples the native decoder has already produced. A request that asks for it is
 * refused rather than quietly ignored.
 *
 * <p>The source is expected to be a {@link BufferedImage}, which is what both codecs return, and
 * the fast paths below all depend on that: an image of a different type is still converted, but
 * through the general {@code getRGB} / {@code setRGB} path.
 */
public final class StillImageRead {

    /** The image types {@link #targetOf} can build a destination in, most useful first. */
    public static final List<Integer> DEFAULT_TYPES = List.of(
            BufferedImage.TYPE_4BYTE_ABGR,
            BufferedImage.TYPE_INT_ARGB,
            BufferedImage.TYPE_INT_ARGB_PRE,
            BufferedImage.TYPE_INT_RGB,
            BufferedImage.TYPE_3BYTE_BGR);

    private StillImageRead() {
        // utility class
    }

    /**
     * The part of a decoded image that a read parameter selects.
     *
     * @param x left edge, in source pixels
     * @param y top edge, in source pixels
     * @param width sampled width, in destination pixels
     * @param height sampled height, in destination pixels
     * @param subX horizontal sub sampling factor
     * @param subY vertical sub sampling factor
     */
    public record Sample(int x, int y, int width, int height, int subX, int subY) {

        /**
         * @return whether this sample is the whole image with no sub sampling, which is the only
         *         case where the pixels can be handed over as they are
         */
        public boolean isWholeImage(int sourceWidth, int sourceHeight) {
            return subX == 1 && subY == 1 && x == 0 && y == 0
                    && width == sourceWidth && height == sourceHeight;
        }
    }

    /**
     * Works out which part of a decoded image the read parameters select.
     *
     * @param width the decoded image width
     * @param height the decoded image height
     * @param param the read parameters, may be {@code null}
     * @return the selected region
     * @throws IIOException when the source region does not fit in the image
     */
    public static Sample sampleOf(int width, int height, ImageReadParam param) throws IIOException {
        if (param == null) {
            return new Sample(0, 0, width, height, 1, 1);
        }
        // The setters never let these drop below 1, but a hand written subclass might.
        int subX = Math.max(1, param.getSourceXSubsampling());
        int subY = Math.max(1, param.getSourceYSubsampling());
        Rectangle region = param.getSourceRegion();
        int x = region == null ? 0 : region.x;
        int y = region == null ? 0 : region.y;
        int regionW = region == null ? width : region.width;
        int regionH = region == null ? height : region.height;
        if (x < 0 || y < 0 || regionW <= 0 || regionH <= 0
                || x + regionW > width || y + regionH > height) {
            throw new IIOException("the source region " + region + " does not fit in the "
                    + width + "x" + height + " image");
        }
        return new Sample(x, y, divideUp(regionW, subX), divideUp(regionH, subY), subX, subY);
    }

    /**
     * Builds the image the caller asked to be handed, which is either the destination they supplied
     * or a new one of the requested type.
     *
     * @param param the read parameters, may be {@code null}
     * @param sample the selected region
     * @param types the image types that can be built, for an
     *        {@link ImageReadParam#getDestinationType()} request
     * @return the destination, with nothing written into it yet
     * @throws IIOException when a requested type cannot be built or the destination is too small
     */
    public static BufferedImage targetOf(ImageReadParam param, Sample sample, List<Integer> types)
            throws IIOException {
        if (param == null) {
            return new BufferedImage(sample.width(), sample.height(), BufferedImage.TYPE_4BYTE_ABGR);
        }
        BufferedImage destination = param.getDestination();
        if (destination == null) {
            ImageTypeSpecifier requested = param.getDestinationType();
            int type = BufferedImage.TYPE_4BYTE_ABGR;
            if (requested != null) {
                type = requested.getBufferedImageType();
                if (!types.contains(type)) {
                    throw new IIOException("this reader cannot produce the destination image type: " + requested);
                }
            }
            return new BufferedImage(sample.width(), sample.height(), type);
        }
        if (sample.width() > destination.getWidth() || sample.height() > destination.getHeight()) {
            throw new IIOException("the destination image is " + destination.getWidth() + "x"
                    + destination.getHeight() + " but the requested region is " + sample.width() + "x"
                    + sample.height());
        }
        return destination;
    }

    /**
     * Refuses a band selection that would drop or reorder samples.
     *
     * @param param the read parameters, may be {@code null}
     * @param channels the number of channels the decoder produces
     * @throws IIOException when the selection is not every channel in order
     */
    public static void checkBands(ImageReadParam param, int channels) throws IIOException {
        if (param == null) {
            return;
        }
        if (param.getSourceBands() != null && !isEveryBand(param.getSourceBands(), channels)) {
            throw new IIOException("source band selection is not supported: "
                    + Arrays.toString(param.getSourceBands()));
        }
        if (param.getDestinationBands() != null && !isEveryBand(param.getDestinationBands(), channels)) {
            throw new IIOException("destination band selection is not supported: "
                    + Arrays.toString(param.getDestinationBands()));
        }
    }

    private static boolean isEveryBand(int[] bands, int count) {
        if (bands.length != count) {
            return false;
        }
        for (int band = 0; band < count; band++) {
            if (bands[band] != band) {
                return false;
            }
        }
        return true;
    }

    /**
     * Copies the selected part of a decoded image into the destination.
     *
     * @param source the decoded image
     * @param sample the selected region
     * @param param the read parameters, may be {@code null}
     * @param target the destination, as handed back by {@link #targetOf}
     * @throws IIOException when the destination offset does not fit in the destination image
     */
    public static void transfer(BufferedImage source, Sample sample, ImageReadParam param, BufferedImage target)
            throws IIOException {
        Point offset = param == null ? null : param.getDestinationOffset();
        int atX = offset == null ? 0 : offset.x;
        int atY = offset == null ? 0 : offset.y;
        if (atX < 0 || atY < 0
                || atX + sample.width() > target.getWidth()
                || atY + sample.height() > target.getHeight()) {
            throw new IIOException("the destination offset " + offset + " does not fit in the "
                    + target.getWidth() + "x" + target.getHeight() + " destination image");
        }
        Raster raster = source.getData();
        ColorModel model = target.getColorModel();
        if (sample.isWholeImage(source.getWidth(), source.getHeight())
                && atX == 0 && atY == 0
                && model != null
                && model.isCompatibleRaster(raster)) {
            // Same memory layout, so the rows can be handed over without touching a single pixel.
            target.getRaster().setRect(0, 0, raster);
            return;
        }
        int stride = sample.width() * sample.subX();
        int rows = sample.height() * sample.subY();
        int[] pixels = new int[stride * rows];
        source.getRGB(sample.x(), sample.y(), stride, rows, pixels, 0, stride);
        target.setRGB(atX, atY, sample.width(), sample.height(),
                divideDown(pixels, sample.width(), sample.height(), sample.subX(), sample.subY()),
                0, sample.width());
    }

    /**
     * Keeps the top left pixel of every {@code subX} by {@code subY} block, which is what
     * {@link ImageReadParam#getSourceXSubsampling()} specifies.
     */
    private static int[] divideDown(int[] pixels, int width, int height, int subX, int subY) {
        if (subX == 1 && subY == 1) {
            return pixels;
        }
        int stride = width * subX;
        int[] out = new int[width * height];
        for (int row = 0; row < height; row++) {
            System.arraycopy(pixels, row * subY * stride, out, row * width, width);
        }
        return out;
    }

    private static int divideUp(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }
}
