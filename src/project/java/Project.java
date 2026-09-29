
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
        require("com.github.weisj", "jsvg");
        require("com.github.teletha", "antibug").atTest();

        describe("""
                AVIF encoding and decoding for Java, backed by libavif.

                JPEG encoding and decoding for Java, backed by
                [jpegli](https://github.com/google/jpegli).

                Also provides an `ImageIO` plug-in for ICO files, one for WebP
                backed by libwebp, and a read-only `ImageIO` plug-in that
                rasterises SVG through JSVG.

                ## Loading

                The native `libavif` shared library is bundled for Windows, macOS
                and Linux on x64 and arm64. It is unpacked automatically on first
                use, so no installation is required.

                    -Dimagify.avif.bundled=false   # ignore the bundled library,
                                                    # use a system libavif instead

                The `jpegli` library is bundled and unpacked the same way.

                    -Dimagify.jpeg.bundled=false   # ignore the bundled library,
                                                    # use a system jpegli instead

                The native `libwebp` library is bundled the same way.

                    -Dimagify.webp.bundled=false  # ignore the bundled library,
                                                    # use a system libwebp instead

                Neither is required for JPEG to work. When one of them is
                missing for the running platform, the `ImageIO` plug-in steps
                aside and the JDK's own JPEG reader and writer take over, so
                `ImageIO.read()` of a JPEG never fails because of it.

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

                ## JPEG

                    BufferedImage image = ImageIO.read(new File("photo.jpg"));
                    ImageIO.write(image, "jpeg", new File("out.jpg"));

                    byte[] jpeg = JpegliCodec.encode(image, 85);
                    // quality 1 (smallest) to 100 (most detail)

                jpegli produces a smaller file than the JDK's encoder at the
                same visual quality, which is why it is here at all.

                A JPEG has two settings an `ImageWriteParam` has nowhere to
                put, and both are on the format rather than on a single write:

                    ImageFormat.JPEG.subsampling(Subsampling.S444)
                    ImageFormat.JPEG.optimizeHuffmanTables(true)
                    ImageWriter.toBytes(image, format, 0.9);

                `subsampling` is how finely the two colour-difference channels
                are stored, and it costs nothing in the quality argument.
                `optimizeHuffmanTables` computes the entropy coder tables from
                the image, which is a smaller file for the very same pixels.

                A caller driving `ImageIO` directly reaches both through
                `JpegWriteParam`, which is what `getDefaultWriteParam()`
                answers:

                    JpegWriteParam param = (JpegWriteParam)
                        writer.getDefaultWriteParam();
                    param.setSubsampling(Subsampling.S444);

                JPEG has no alpha channel. An encode discards the alpha byte,
                so a transparent image is written against whatever colour sits
                behind it, and a decode comes back fully opaque. A caller that
                needs the picture has to flatten the image first.

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

                The direct API lives in `imagify.webp.ffm.WebpCodec`, alongside
                the `imagify.webp` ImageIO plug-in it backs. Lossless `VP8L`
                output is selected by choosing the `WebP Lossless` compression
                type of the write parameter, or by passing `true` to
                `WebpCodec.encode`. An animated file is read as one image per
                frame, each already composited onto the canvas.

                The codec is bound with Java's own Foreign Function & Memory
                API, so on JDK 24 and newer a program using it from the class
                path is asked to allow native access. Nothing fails without it,
                but the JDK warns on every run and will block the call in a
                later release:

                    java --enable-native-access=ALL-UNNAMED -cp ... YourApp

                ## SVG

                    BufferedImage image = ImageIO.read(new File("icon.svg"));

                SVG is read only. It is rasterised with JSVG at the size declared
                by the document, so resizing is a separate step.

                ## Header-only read

                    AvifImageInfo info = AvifCodec.readHeader(avif);
                    WebpImageInfo info = WebpCodec.readHeader(webp);
                    JpegImageInfo info = JpegliCodec.readHeader(jpeg);

                ## Availability

                    if (!AvifCodec.isAvailable()) {
                        System.err.println(AvifCodec.getUnavailableReason());
                    }
                    if (!JpegliCodec.isAvailable()) {
                        System.err.println(JpegliCodec.getUnavailableReason());
                    }
                    if (!WebpCodec.isAvailable()) {
                        System.err.println(WebpCodec.getUnavailableReason());
                    }

                ## Version

                Supported `libavif` versions: `1.0.0` to `1.4.x`. `JpegliCodec`
                and `WebpCodec` bind a flat C ABI of their own rather than
                jpegli's or libwebp's, and refuse a library built against
                another revision of it.
                """);
    }
}