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
 * <p>The most common causes are a missing or unloadable {@code libwebp} native library, which
 * {@code webp4j} bundles and unpacks on first use, and malformed input.
 */
public class WebpException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message the detail message
     */
    public WebpException(String message) {
        super(message);
    }

    /**
     * @param message the detail message
     * @param cause the underlying failure
     */
    public WebpException(String message, Throwable cause) {
        super(message, cause);
    }
}
