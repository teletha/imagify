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
 * I/O statistics collected while decoding or encoding: {@code avifIOStats}.
 */
public class AvifIOStats extends Structure {

    public long colorOBUSize;
    public long alphaOBUSize;

    public AvifIOStats() {
        super();
    }

    public AvifIOStats(Pointer peer) {
        super(peer);
    }

    @Override
    protected List<String> getFieldOrder() {
        return List.of("colorOBUSize", "alphaOBUSize");
    }
}
