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
 * Read only view on a block of bytes: {@code avifROData}.
 *
 * @see AvifRWData
 */
public class AvifROData extends Structure {

    public Pointer data;
    public long size;

    public AvifROData() {
        super();
    }

    public AvifROData(Pointer peer) {
        super(peer);
    }

    @Override
    protected List<String> getFieldOrder() {
        return List.of("data", "size");
    }
}
