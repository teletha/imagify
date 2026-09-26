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

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Error message buffer filled in by {@code libavif}: {@code avifDiagnostics}.
 */
public class AvifDiagnostics extends Structure {

    /** Size of the native {@code char error[256]} member. */
    public static final int ERROR_MESSAGE_SIZE = 256;

    public byte[] error = new byte[ERROR_MESSAGE_SIZE];

    public AvifDiagnostics() {
        super();
    }

    public AvifDiagnostics(Pointer peer) {
        super(peer);
    }

    @Override
    protected List<String> getFieldOrder() {
        return List.of("error");
    }

    /**
     * @return the NUL terminated error message, or an empty string when no error was reported
     */
    public String getMessage() {
        read();
        int length = 0;
        while (length < error.length && error[length] != 0) {
            length++;
        }
        return new String(error, 0, length, StandardCharsets.UTF_8);
    }
}
