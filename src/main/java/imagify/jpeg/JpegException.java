/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.jpeg;

import java.io.Serial;

/**
 * Signals that a JPEG operation failed.
 *
 * <p>The most common causes are a missing or incompatible jpegli native library and malformed
 * input.
 *
 * <p>Every message this carries comes either from the C shim in {@code src/main/native} or from
 * {@link #JpegException(String, Throwable)} wrapping whatever the loader reported while loading the
 * shared library, so the text is a description rather than a stable identifier. Nothing should parse
 * it.
 */
public class JpegException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message the detail message
     */
    public JpegException(String message) {
        super(message);
    }

    /**
     * @param message the detail message
     * @param cause the underlying failure
     */
    public JpegException(String message, Throwable cause) {
        super(message, cause);
    }
}
