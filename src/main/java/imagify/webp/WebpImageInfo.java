/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.webp;

/**
 * Properties of the image contained in a WebP file, as reported by the container headers.
 *
 * @param width canvas width in pixels
 * @param height canvas height in pixels
 * @param hasAlpha whether the bitstream carries a non premultiplied alpha channel
 * @param hasAnimation whether the file holds an animation rather than a single still frame
 * @param format one of {@link WebpCodec#FORMAT_VP8},
 *        {@link WebpCodec#FORMAT_VP8L} or
 *        {@link WebpCodec#FORMAT_VP8X}
 * @param frameCount number of frames, 1 for a still image and the frame count for an animation
 * @param loopCount how often the animation repeats, 0 meaning forever, only set for an animation
 */
public record WebpImageInfo(
        int width,
        int height,
        boolean hasAlpha,
        boolean hasAnimation,
        int format,
        int frameCount,
        int loopCount) {
}
