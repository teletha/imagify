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

import java.io.Serial;

/**
 * Signals that an AVIF operation failed.
 *
 * <p>The most common causes are a missing or too old {@code libavif} native library and malformed
 * input.
 */
public class AvifException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception carrying only the detail message, for a failure libavif reported in text
     * and nothing else.
     *
     * @param message the detail message
     */
    public AvifException(String message) {
        super(message);
    }

    /**
     * Creates an exception wrapping the failure it was given, for an error raised inside this
     * library rather than reported by libavif.
     *
     * @param message the detail message
     * @param cause the underlying failure
     */
    public AvifException(String message, Throwable cause) {
        super(message, cause);
    }
}
