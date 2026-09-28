/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.jpeg;

/**
 * Properties of the image contained in a JPEG file, as reported by its frame header.
 *
 * <p>A JPEG says all of this before a single pixel is decoded, so this is what a caller reads when
 * it only wants to know how large a file is.
 *
 * @param width image width in pixels
 * @param height image height in pixels
 * @param components the number of colour channels, which is 1 for a greyscale file and 3 for
 *            everything this library writes
 * @param greyscale whether the file has no colour channels at all
 * @param progressive whether the file is coded in several passes rather than in one
 * @param horizontalFactor the luma sampling factor along the width, which is 1 unless the file
 *            subsamples its colour channels
 * @param verticalFactor the luma sampling factor along the height
 * @param densityUnit how {@link #horizontalDensityDpi()} and {@link #verticalDensityDpi()} are to
 *            be read: 0
 *            when they are only an aspect ratio, 1 for dots per inch and 2 for dots per centimetre
 * @param horizontalDensity the horizontal density, in the unit just named
 * @param verticalDensity the vertical density, in the unit just named
 * @param precision bits per channel, which is 8 for every file jpegli can decode
 */
public record JpegImageInfo(int width, int height, int components, boolean greyscale, boolean progressive, int horizontalFactor, int verticalFactor, int densityUnit, int horizontalDensity, int verticalDensity, int precision) {

    /** The density is not a resolution but a ratio between the two axes. */
    public static final int DENSITY_UNIT_ASPECT_RATIO = 0;

    /** The density is in dots per inch. */
    public static final int DENSITY_UNIT_DOTS_PER_INCH = 1;

    /** The density is in dots per centimetre. */
    public static final int DENSITY_UNIT_DOTS_PER_CENTIMETRE = 2;

    /**
     * Returns the density in dots per inch, which is what the
     * {@code pixelWidth}/{@code pixelHeight}
     * of an ImageIO standard metadata tree is measured in.
     *
     * <p>A file that names dots per centimetre is converted, and one that only names an aspect
     * ratio
     * answers 0, because the standard metadata format has no unit to put the ratio in. The JDK
     * reader does the same, so a file read either way describes itself the same.
     *
     * @return {@link #horizontalDensity()} converted to dots per inch, or 0 when there is no
     *         density
     */
    public int horizontalDensityDpi() {
        return switch (densityUnit) {
        case DENSITY_UNIT_DOTS_PER_INCH -> horizontalDensity;
        case DENSITY_UNIT_DOTS_PER_CENTIMETRE -> Math.round(horizontalDensity * 2.54f);
        default -> 0;
        };
    }

    /**
     * Returns the vertical density in dots per inch.
     *
     * @return {@link #verticalDensity()} converted to dots per inch, or 0 when there is no density
     * @see #horizontalDensityDpi()
     */
    public int verticalDensityDpi() {
        return switch (densityUnit) {
        case DENSITY_UNIT_DOTS_PER_INCH -> verticalDensity;
        case DENSITY_UNIT_DOTS_PER_CENTIMETRE -> Math.round(verticalDensity * 2.54f);
        default -> 0;
        };
    }
}
