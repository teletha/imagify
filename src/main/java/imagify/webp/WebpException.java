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

import java.io.Serial;

/**
 * Signals that a WebP operation failed.
 *
 * <p>The most common causes are a missing or unloadable {@code libwebp} native library, which this
 * jar fetches on first use, and malformed input.
 */
public class WebpException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception carrying only the detail message, for a failure libwebp reported in text
     * and nothing else.
     *
     * @param message the detail message
     */
    public WebpException(String message) {
        super(message);
    }

    /**
     * Creates an exception wrapping the failure it was given, for an error raised while fetching or
     * loading the shared library rather than reported by it.
     *
     * @param message the detail message
     * @param cause the underlying failure
     */
    public WebpException(String message, Throwable cause) {
        super(message, cause);
    }
}
