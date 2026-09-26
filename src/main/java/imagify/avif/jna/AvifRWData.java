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
 * Writable byte buffer owned by {@code libavif}: {@code avifRWData}.
 *
 * <p>{@code size} is a C {@code size_t}, therefore it is declared as a Java {@code long}
 * (64 bit) rather than as {@link com.sun.jna.NativeLong}: {@code size_t} is 64 bit wide on every
 * 64 bit platform, including Windows, where C {@code long} is only 32 bit wide.
 */
public class AvifRWData extends Structure {

    public Pointer data;
    public long size;

    public AvifRWData() {
        super();
    }

    public AvifRWData(Pointer peer) {
        super(peer);
    }

    @Override
    protected List<String> getFieldOrder() {
        return List.of("data", "size");
    }

    /**
     * Copies the first {@code size} bytes of the native buffer into a new array.
     *
     * @return the encoded bytes, or {@code null} if the buffer is empty
     */
    public byte[] toByteArray() {
        read();
        if (data == null || size <= 0) {
            return null;
        }
        return data.getByteArray(0, (int) size);
    }
}
