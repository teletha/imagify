/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.ico;

import javax.imageio.ImageWriteParam;
import java.util.Locale;

/**
 * Write parameter for {@link IcoImageWriter}.
 */
public class IcoWriteParam extends ImageWriteParam {

    IcoWriteParam(Locale locale) {
        super(locale);
    }
}
