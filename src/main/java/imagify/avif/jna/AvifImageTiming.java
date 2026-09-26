/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.avif.jna;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.util.List;

/**
 * Timing information of a single frame of an image sequence: {@code avifImageTiming}.
 */
public class AvifImageTiming extends Structure {

    /** Timescale of the media in Hz. */
    public long timescale;
    /** Presentation timestamp in seconds. */
    public double pts;
    /** Presentation timestamp in {@link #timescale} units. */
    public long ptsInTimescales;
    /** Frame duration in seconds. */
    public double duration;
    /** Frame duration in {@link #timescale} units. */
    public long durationInTimescales;

    public AvifImageTiming() {
        super();
    }

    public AvifImageTiming(Pointer peer) {
        super(peer);
    }

    @Override
    protected List<String> getFieldOrder() {
        return List.of("timescale", "pts", "ptsInTimescales", "duration", "durationInTimescales");
    }
}
