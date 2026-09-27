
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

        require("net.java.dev.jna", "jna");
        require("dev.matrixlab.webp4j", "webp4j-core");
        require("com.github.weisj", "jsvg");
        require("com.github.teletha", "antibug").atTest();

        describe("""
                AVIF encoding and decoding for Java, backed by libavif.

                Also provides an `ImageIO` plug-in for ICO files, one for WebP
                backed by libwebp, and a read-only `ImageIO` plug-in that
                rasterises SVG through JSVG.

                ## Loading

                The native `libavif` shared library is bundled for Windows, macOS
                and Linux on x64 and arm64. It is unpacked automatically on first
                use, so no installation is required.

                    -Dimagify.avif.bundled=false   # ignore the bundled library,
                                                    # use a system libavif instead

                The native `libwebp` library is bundled the same way, inside
                `webp4j`.

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

                ## ICO

                    BufferedImage image = ImageIO.read(new File("app.ico"));
                    ImageIO.write(image, "ico", new File("out.ico"));

                The writer stores a 32-bit BGRA bitmap with an AND mask derived
                from the alpha channel, so transparency is preserved. The reader
                decodes the entry closest to 256x256.

                ## WebP

                    BufferedImage image = ImageIO.read(new File("photo.webp"));
                    ImageIO.write(image, "webp", new File("out.webp"));

                    byte[] webp = WebpCodec.encode(image, 80, false);
                    // quality 0 (smallest) to 100, ignored when lossless
                    List<BufferedImage> frames =
                        WebpCodec.decodeAnimation(animated);

                Lossless `VP8L` output is selected by choosing the
                `WebP Lossless` compression type of the write parameter, or by
                passing `true` to `WebpCodec.encode`. An animated file is read
                as one image per frame, each already composited onto the canvas.

                ## SVG

                    BufferedImage image = ImageIO.read(new File("icon.svg"));

                SVG is read only. It is rasterised with JSVG at the size declared
                by the document, so resizing is a separate step.

                ## Header-only read

                    AvifImageInfo info = AvifCodec.readHeader(avif);
                    WebpImageInfo info = WebpCodec.readHeader(webp);

                ## Availability

                    if (!AvifCodec.isAvailable()) {
                        System.err.println(AvifCodec.getUnavailableReason());
                    }
                    if (!WebpCodec.isAvailable()) {
                        System.err.println(WebpCodec.getUnavailableReason());
                    }

                ## Version

                Supported `libavif` versions: `1.0.0` to `1.4.x`.
                """);
    }
}