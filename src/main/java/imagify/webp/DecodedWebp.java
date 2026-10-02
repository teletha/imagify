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

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * A WebP file read in a single pass, holding its properties and its frames together.
 *
 * <p>The two belong in one result because they cannot be had apart cheaply. An animation's frame
 * count and loop count live in the animation control chunk, which only the animation decoder
 * reads, and that decoder hands back every frame at once. Asking for the headers and then
 * decoding again therefore walks the same file twice, and asking for a frame on its own walks
 * it once more. One pass gives all of it.
 *
 * <p>It is shared by the WebP backends, so a file read through one of them and a file read
 * through another both answer with this and not with a type of their own.
 *
 * @param info the properties reported by the headers
 * @param frames the frames in presentation order, composited onto the canvas for an animation,
 *            and exactly one frame for a still image
 * @param delaysMs how long each frame is shown in milliseconds, parallel to {@code frames}, or
 *            {@code null} for a still image, which has no timing of its own
 */
public record DecodedWebp(WebpImageInfo info, List<BufferedImage> frames, int[] delaysMs) {

    /**
     * Takes copies of the two collections here, so that the record cannot be changed afterwards
     * through the list or the array the caller handed it.
     */
    public DecodedWebp {
        frames = List.copyOf(frames);
        delaysMs = delaysMs == null ? null : delaysMs.clone();
    }
}
