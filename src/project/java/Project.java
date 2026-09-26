
/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          https://opensource.org/licenses/MIT
 */
import static bee.api.License.*;

public class Project extends bee.api.Project {
    {
        product("com.github.teletha", "imagify", "0.1");
        license(MIT);
        versionControlSystem("https://github.com/teletha/imagify");

        require("com.github.teletha", "sinobu");
        require("com.github.teletha", "psychopath");
        require("net.java.dev.jna", "jna");
        require("dev.matrixlab.webp4j", "webp4j-core");
        require("com.github.teletha", "antibug").atTest();
    }
}