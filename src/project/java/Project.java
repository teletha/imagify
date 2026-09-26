
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

import javax.lang.model.SourceVersion;

public class Project extends bee.api.Project {
    {
        product("com.github.teletha", "imagify", ref("version.txt"));
        license(MIT);
        versionControlSystem("https://github.com/teletha/imagify");
        require(SourceVersion.latest(), SourceVersion.RELEASE_24);

        require("com.github.teletha", "sinobu");
        require("com.github.teletha", "psychopath");
        require("net.java.dev.jna", "jna");
        require("dev.matrixlab.webp4j", "webp4j-core");
        require("com.twelvemonkeys.imageio", "imageio-webp");
        require("com.twelvemonkeys.imageio", "common-image");
        require("com.github.teletha", "antibug").atTest();

        describe("""
                AVIF encoding and decoding for Java, backed by libavif.

                ## Loading

                The native `libavif` shared library is bundled for Windows, macOS
                and Linux on x64 and arm64. It is unpacked automatically on first
                use, so no installation is required.

                    -Dimagify.avif.bundled=false   # ignore the bundled library,
                                                    # use a system libavif instead

                ## Decode

                    byte[] avif = Files.readAllBytes(Path.of("photo.avif"));
                    DecodedImage result = AvifCodec.decode(avif);
                    BufferedImage image = result.image();
                    AvifImageInfo info = result.info();

                ## Encode

                    byte[] avif = AvifCodec.encode(image, 75, 4);
                    // quality 0 (smallest) to 100 (lossless)
                    // speed   0 (slowest) to 10 (fastest)
                    Files.write(Path.of("out.avif"), avif);

                ## ImageIO

                    BufferedImage image = ImageIO.read(new File("photo.avif"));
                    ImageIO.write(image, "avif", new File("out.avif"));

                ## Header-only read

                    AvifImageInfo info = AvifCodec.readHeader(avif);

                ## Availability

                    if (!AvifCodec.isAvailable()) {
                        System.err.println(AvifCodec.getUnavailableReason());
                    }

                ## Version

                Supported `libavif` versions: `1.0.0` to `1.4.x`.
                """);
    }
}