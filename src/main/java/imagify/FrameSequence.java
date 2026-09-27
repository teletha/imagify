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
import java.util.List;

/**
 * A sequence of image frames with their display durations.
 *
 * <p>Used for animated formats such as GIF, animated WebP, and animated AVIF.
 * For single-image formats, this contains a single frame.</p>
 */
public class FrameSequence {

    /** The frames in display order. */
    private final List<BufferedImage> frames;
    /** How long each frame is shown, in milliseconds. */
    private final int[] delaysMs;
    /** How many times the animation repeats; 0 means forever. */
    private final int loopCount;

    /**
     * Creates a frame sequence.
     *
     * @param frames    the frames, all of the same size, at least one
     * @param delaysMs  how long each frame is shown in milliseconds, one entry per frame
     * @param loopCount how many times the animation repeats, 0 meaning forever
     */
    public FrameSequence(List<BufferedImage> frames, int[] delaysMs, int loopCount) {
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("frame sequence must have at least one frame");
        }
        if (delaysMs == null || delaysMs.length != frames.size()) {
            throw new IllegalArgumentException("expected one delay per frame: "
                    + frames.size() + " frames but "
                    + (delaysMs == null ? "no" : delaysMs.length + "") + " delays");
        }
        this.frames = List.copyOf(frames);
        this.delaysMs = delaysMs.clone();
        this.loopCount = loopCount;
    }

    /**
     * @return the frames in display order, never {@code null}
     */
    public List<BufferedImage> frames() {
        return frames;
    }

    /**
     * @return how long each frame is shown in milliseconds, one entry per frame
     */
    public int[] delaysMs() {
        return delaysMs.clone();
    }

    /**
     * @return how many times the animation repeats, 0 meaning forever
     */
    public int loopCount() {
        return loopCount;
    }

    /**
     * @return the number of frames
     */
    public int frameCount() {
        return frames.size();
    }

    /**
     * Returns the first frame as a {@link BufferedImage}.
     *
     * <p>Convenience method for when a single-image operation is needed,
     * such as writing to a format that does not support animation.</p>
     *
     * @return the first frame
     */
    public BufferedImage toBufferedImage() {
        return frames.get(0);
    }
}
