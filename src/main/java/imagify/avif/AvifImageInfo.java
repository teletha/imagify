/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.avif;

/**
 * Properties of the image contained in an AVIF file, as reported by the container headers.
 *
 * @param width image width in pixels
 * @param height image height in pixels
 * @param depth bits per channel, one of 8, 10 or 12
 * @param yuvFormat an {@code AVIF_PIXEL_FORMAT_*} value
 * @param yuvRange an {@code AVIF_RANGE_*} value
 * @param chromaSamplePosition an {@code AVIF_CHROMA_SAMPLE_POSITION_*} value
 * @param colorPrimaries an {@code AVIF_COLOR_PRIMARIES_*} value
 * @param transferCharacteristics an {@code AVIF_TRANSFER_CHARACTERISTICS_*} value
 * @param matrixCoefficients an {@code AVIF_MATRIX_COEFFICIENTS_*} value
 * @param hasAlpha whether the image carries a non premultiplied alpha channel
 * @param iccSize size of the embedded ICC profile in bytes, 0 when there is none
 * @param exifSize size of the embedded Exif payload in bytes, 0 when there is none
 * @param xmpSize size of the embedded XMP payload in bytes, 0 when there is none
 * @param rotationDegrees the {@code irot} transform in degrees (0, 90, 180 or 270)
 * @param mirrored whether the {@code imir} transform is present
 */
public record AvifImageInfo(
        int width,
        int height,
        int depth,
        int yuvFormat,
        int yuvRange,
        int chromaSamplePosition,
        int colorPrimaries,
        int transferCharacteristics,
        int matrixCoefficients,
        boolean hasAlpha,
        int iccSize,
        int exifSize,
        int xmpSize,
        int rotationDegrees,
        boolean mirrored) {
}
