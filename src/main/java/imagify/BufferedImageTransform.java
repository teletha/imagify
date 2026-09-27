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
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * Pixel rearrangement operations on a single {@link BufferedImage}: cropping, rotating and flipping.
 *
 * <p>Every method returns a new image of {@link BufferedImage#TYPE_INT_ARGB} and leaves the source
 * untouched. That is the same normalisation {@link BufferedImageResize} applies, so the result of one
 * of these can be handed straight to the other, and a frame of a {@link FrameSequence} can be moved
 * around without its neighbours noticing a difference.
 *
 * <p>Rotating by a multiple of 90 degrees moves whole pixels and is therefore exact. Any other angle
 * has to resample, and of the {@link ResizeAlgorithm}s only {@link ResizeAlgorithm#NEAREST} and
 * {@link ResizeAlgorithm#BILINEAR} have an equivalent in Java2D, so the remaining algorithms resample
 * bilinearly instead. Angles outside a multiple of 90 degrees keep the original canvas and clip the
 * corners, which is what rotating a photograph usually means; the right angles swap the width and
 * the height instead.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * BufferedImage icon = BufferedImageTransform.crop(sheet, 0, 0, 32, 32);
 * BufferedImage upright = BufferedImageTransform.rotate(photo, -90);
 * BufferedImage mirrored = BufferedImageTransform.flipHorizontal(portrait);
 * }</pre>
 */
public final class BufferedImageTransform {

    private BufferedImageTransform() {}

    // --------------------------------------------------------------------------------- crop

    /**
     * Extracts a rectangle as a new image.
     *
     * <p>The region is copied rather than viewed, so the result shares no raster with the source.
     * That matters here: {@link BufferedImage#getSubimage} hands back a view onto the parent, which
     * keeps the whole parent alive and leaves the copy off the plain image types the encoders expect.
     *
     * @param source the image to cut from
     * @param x      left edge, in source pixels
     * @param y      top edge, in source pixels
     * @param width  width of the region, in pixels
     * @param height height of the region, in pixels
     * @return the extracted region
     * @throws IllegalArgumentException when the region is empty or reaches outside the image
     */
    public static BufferedImage crop(BufferedImage source, int x, int y, int width, int height) {
        require(source, "no image to crop");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "the crop size must be positive, got " + width + "x" + height);
        }
        if (x < 0 || y < 0 || x + width > source.getWidth() || y + height > source.getHeight()) {
            throw new IllegalArgumentException("the crop region at " + x + "," + y + " of "
                    + width + "x" + height + " does not fit in the "
                    + source.getWidth() + "x" + source.getHeight() + " image");
        }
        return draw(source, width, height,
                AffineTransform.getTranslateInstance(-x, -y),
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
    }

    // ------------------------------------------------------------------------------- rotate

    /**
     * Rotates an image, resampling bilinearly when the angle is not a multiple of 90 degrees.
     *
     * @param source  the image to rotate
     * @param degrees the angle, clockwise, any finite value
     * @return the rotated image
     * @throws IllegalArgumentException when the source is missing or the angle is not finite
     */
    public static BufferedImage rotate(BufferedImage source, double degrees) {
        return rotate(source, degrees, ResizeAlgorithm.BILINEAR);
    }

    /**
     * Rotates an image.
     *
     * @param source    the image to rotate
     * @param degrees   the angle, clockwise, any finite value
     * @param algorithm how to resample, honoured only for angles that are not a multiple of 90 degrees
     * @return the rotated image
     * @throws IllegalArgumentException when the source or the algorithm is missing, or the angle is
     *                                  not finite
     */
    public static BufferedImage rotate(BufferedImage source, double degrees, ResizeAlgorithm algorithm) {
        require(source, "no image to rotate");
        if (algorithm == null) {
            throw new IllegalArgumentException("no resampling algorithm");
        }
        if (!Double.isFinite(degrees)) {
            throw new IllegalArgumentException("the angle must be a finite number, got " + degrees);
        }

        int width = source.getWidth();
        int height = source.getHeight();
        int quarter = quarterTurns(degrees);
        if (quarter < 0) {
            // An angle that is not a whole quarter turn has to resample, and the only two ways of
            // doing that Java2D offers are nearest neighbour and bilinear. Anything else falls back to
            // bilinear rather than pretending to be a kernel it is not.
            return draw(source, width, height,
                    AffineTransform.getRotateInstance(Math.toRadians(degrees), width / 2.0, height / 2.0),
                    algorithm == ResizeAlgorithm.NEAREST
                            ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                            : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        boolean swapsAxes = quarter % 2 != 0;
        return draw(source, swapsAxes ? height : width, swapsAxes ? width : height,
                rightAngle(quarter, width, height),
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
    }

    /**
     * @return the angle as a whole number of clockwise quarter turns, or {@code -1} when it is not
     *         one. The tolerance absorbs the rounding of expressions such as {@code 90 * 3 / 3.0} that
     *         should land on a right angle but arrive a few bits away from it.
     */
    private static int quarterTurns(double degrees) {
        double turns = degrees / 90.0;
        double rounded = Math.rint(turns);
        if (Math.abs(turns - rounded) > 1e-9) {
            return -1;
        }
        return (int) Math.floorMod((long) rounded, 4L);
    }

    /**
     * Rotates about the origin and then shifts by a whole number of pixels, so that every destination
     * pixel sits exactly on a source pixel and the right angle stays lossless. Rotating about the
     * centre instead would leave an even sided image off by half a pixel.
     */
    private static AffineTransform rightAngle(int quarter, int width, int height) {
        AffineTransform transform = AffineTransform.getRotateInstance(Math.toRadians(quarter * 90.0));
        // Where the rotation leaves the source rectangle, and the whole pixel shift that brings it
        // back onto the canvas: 90 degrees swaps the axes and lands in the second quadrant, 180 keeps
        // them and lands in the third, 270 swaps them and lands in the fourth.
        int dx = switch (quarter) {
            case 1 -> height;
            case 2 -> width;
            default -> 0;
        };
        int dy = switch (quarter) {
            case 2 -> height;
            case 3 -> width;
            default -> 0;
        };
        transform.preConcatenate(AffineTransform.getTranslateInstance(dx, dy));
        return transform;
    }

    // --------------------------------------------------------------------------------- flip

    /**
     * Mirrors an image left to right.
     *
     * @param source the image to mirror
     * @return the mirrored image
     * @throws IllegalArgumentException when the source is missing
     */
    public static BufferedImage flipHorizontal(BufferedImage source) {
        return flip(source, true);
    }

    /**
     * Mirrors an image top to bottom.
     *
     * @param source the image to mirror
     * @return the mirrored image
     * @throws IllegalArgumentException when the source is missing
     */
    public static BufferedImage flipVertical(BufferedImage source) {
        return flip(source, false);
    }

    private static BufferedImage flip(BufferedImage source, boolean horizontal) {
        require(source, "no image to flip");
        int width = source.getWidth();
        int height = source.getHeight();
        AffineTransform transform = horizontal
                ? AffineTransform.getScaleInstance(-1, 1)
                : AffineTransform.getScaleInstance(1, -1);
        // Mirroring about the origin lands the image in the negative quadrant, so it has to be
        // shifted back by its own size. The shift has to come after the mirror, which is what
        // preConcatenate() means: the mirror is applied to the point first, the shift to the result.
        transform.preConcatenate(horizontal
                ? AffineTransform.getTranslateInstance(width, 0)
                : AffineTransform.getTranslateInstance(0, height));
        return draw(source, width, height, transform,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
    }

    // ------------------------------------------------------------------------- internals

    /**
     * Draws the whole of {@code source} into a fresh canvas of the given size, through
     * {@code transform}.
     *
     * <p>{@link AlphaComposite#Src} replaces the destination outright rather than blending over it,
     * which is the right operator for rearranging pixels: blending would round a partly transparent
     * pixel on its way through premultiplied space, and it would leave the areas the source does not
     * cover as a blend with nothing rather than as the cleared canvas.
     *
     * <p>The transform goes on the {@link Graphics2D} rather than into
     * {@link Graphics2D#drawImage(java.awt.Image, java.awt.geom.AffineTransform, java.awt.image.ImageObserver)},
     * because that overload quietly draws nothing at all when the transform turns the image by an
     * angle, which is precisely the case this class exists for.
     */
    private static BufferedImage draw(BufferedImage source, int width, int height,
            AffineTransform transform, Object interpolation) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            graphics.transform(transform);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static void require(BufferedImage source, String message) {
        if (source == null) {
            throw new IllegalArgumentException(message);
        }
    }
}
