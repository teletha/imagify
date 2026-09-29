<p align="center">
    <a href="https://docs.oracle.com/en/java/javase/24/"><img src="https://img.shields.io/badge/Java-Release%2024-green"/></a>
    <span>&nbsp;</span>
    <a href="https://jitpack.io/#teletha/imagify"><img src="https://img.shields.io/jitpack/v/github/teletha/imagify?label=Repository&color=green"></a>
    <span>&nbsp;</span>
    <a href="https://teletha.github.io/imagify"><img src="https://img.shields.io/website.svg?down_color=red&down_message=CLOSE&label=Official%20Site&up_color=green&up_message=OPEN&url=https%3A%2F%2Fteletha.github.io%2Fimagify"></a>
</p>

## Summary
Imagify reads, transforms and writes images through one small fluent API.
AVIF, WebP and JPEG are encoded and decoded by bundled native libraries;
PNG, GIF, BMP and ICO go through the JDK's image I/O; SVG can be read.

    Imagify.read(input)
           .resize(800, 600)
           .writeTo(output);

## Quick start

A pipeline is read, transform, write, and every step returns the same
pipeline so calls chain:

    Imagify
        .read(Path.of("photo.jpg"))
        .resizeToFill(1200, 630)
        .writeTo(Path.of("hero.avif"));

`ImageReader` and `ImageWriter` are the lower level entry points. They
answer decoded frames and encoded bytes rather than a pipeline:

    FrameSequence frames = ImageReader.read(bytes);
    byte[] png = ImageWriter.toBytes(image, ImageFormat.PNG, 0.9);

Everything is also registered as an `ImageIO` plug-in, so the ordinary
`ImageIO.read` and `ImageIO.write` work with the format names:

    BufferedImage image = ImageIO.read(new File("photo.avif"));
    ImageIO.write(image, "webp", new File("out.webp"));

## Read

`Imagify.read` accepts a `Path`, a `byte[]`, an `InputStream` or an
already decoded `BufferedImage`. The format is detected from the header,
so the caller does not name it. Passing several sources, or adding more
with `add`, makes each one a frame of a single sequence:

    Imagify.read(Path.of("frame-1.png"), Path.of("frame-2.png"));

`ImageReader.read` returns the decoded `FrameSequence` directly, which
carries the frames, how long each is shown and how often the sequence
repeats:

    FrameSequence frames = ImageReader.read(Path.of("animation.gif"));
    List<BufferedImage> images = frames.frames();
    int[] delays = frames.delaysMs();

Reading only the header is cheaper than decoding when the size is all
that is wanted:

    AvifImageInfo info = AvifCodec.readHeader(avif);
    WebpImageInfo info = WebpCodec.readHeader(webp);
    JpegImageInfo info = JpegliCodec.readHeader(jpeg);

## Transform

Every transform applies to every frame of a sequence and leaves its
timing alone. The resizing methods differ in what they do with a source
whose shape is not the target's:

    .resize(800, 600)                 // stretch to exactly 800x600
    .resize(0.5)                      // scale both edges by a factor
    .resizeToFit(1920)                // longest edge 1920, ratio kept
    .resizeInside(800, 600)           // fit inside, never enlarge
    .resizeToFill(800, 600)           // fill the box, crop the overflow
    .padTo(800, 600)                  // fill the box, add transparent area
    .padTo(800, 600, Color.WHITE);    // ... or add a colour

Each takes a `ResizeAlgorithm` when the default is not what is wanted:

    .resize(800, 600, ResizeAlgorithm.BILINEAR);

The rest are `crop`, `rotate`, `flipHorizontal` and `flipVertical`, plus
`map` for an operation of your own:

    .crop(10, 10, 400, 300)
    .rotate(90)
    .flipHorizontal()
    .map(image -> myOperation(image));

Two steps change what the sequence is rather than what a frame looks
like:

    .toStillImage()          // keep only the first frame
    .asSpriteSheet(8)        // lay every frame out in a grid of 8 columns
    .asAnimation(8, 4, 100)  // cut that grid back into 100 ms frames

## Write

`writeTo(Path)` takes the format from the file extension.
`writeToBytes` and `writeTo(OutputStream, ...)` are the same without a
file. A quality of `0.0` (smallest) to `1.0` (largest) can be given, and
so can the format:

    .writeToBytes(ImageFormat.AVIF)
    .writeToBytes(ImageFormat.WEBP, 0.8)
    .writeTo(path, ImageFormat.PNG);

Writing is automatic about animation: a sequence with more than one frame
written to a format that supports animation becomes one, and anything
else becomes its first frame.

    Imagify.read(gif).writeTo(Path.of("animation.webp"));
    Imagify.read(gif).toStillImage().writeTo(Path.of("poster.jpg"));

To encode many images at once, across every core:

    List<byte[]> encoded = ImageWriter.toBytes(images, ImageFormat.WEBP, 0.8);
    ImageWriter.toFiles(images, ImageFormat.WEBP, 0.8, paths);
    // both take a thread count, and neither changes what comes out

## Format settings

The quality argument is the one setting every format shares. The rest
belong to a format and are set on the format value, which is immutable,
so asking for one hands back a new format.

### AVIF

    ImageFormat.AVIF.speed(8)          // 0 (slowest, best) to 10 (fastest)
    ImageFormat.AVIF.alphaQuality(90)  // 0 to 100, 100 keeps every value
    ImageFormat.AVIF.subsampling(ImageFormat.Avif.Subsampling.YUV420)
    ImageFormat.AVIF.chromaDownsampling(ImageFormat.Avif.ChromaDownsampling.SHARP_YUV)

A quality of 100 is lossless. Speed trades time for bytes: on a 517x380
photograph at quality 60, speed 6 takes 142 ms and speed 8 takes 47 ms.
A still image is YUV444 unless asked otherwise, an animation YUV420.

### WebP

    ImageFormat.WEBP.lossless()        // VP8L, pixel for pixel
    ImageFormat.WEBP.compressionMethod(2)

In lossless mode the quality is read as an amount of effort rather than
a fidelity, so 0 is quick and 100 is thorough and neither loses a pixel.
The compression method is libwebp's `method`, 0 to 6; method 2 is 2.7x
quicker than the default 4 for 4.3% more bytes.

### JPEG

    ImageFormat.JPEG.subsampling(ImageFormat.Jpeg.Subsampling.S444)
    ImageFormat.JPEG.optimizeHuffmanTables(true)

`subsampling` is how finely the two colour-difference channels are
stored, and it costs nothing in the quality argument.
`optimizeHuffmanTables` computes the entropy coder tables from the image,
which is a smaller file for the very same pixels.

A JPEG has no alpha channel: an encode discards the alpha byte, and a
decode comes back fully opaque, so flatten the image first if that
matters.

A caller driving `ImageIO` directly reaches the same two settings
through the write parameter:

    JpegWriteParam param = (JpegWriteParam) writer.getDefaultWriteParam();
    param.setSubsampling(Subsampling.S444);

### PNG

    ImageFormat.PNG.compressionLevel(9)  // 0 (quick) to 9 (smallest)

### ICO and SVG

ICO is read and written through `ImageIO`, and the 32-bit alpha of a
file is preserved. SVG is read only: it is rasterised with JSVG at the
size the document declares, so resizing is a separate step.

## Native libraries

AVIF, WebP and JPEG are bound with Java's own Foreign Function &amp;
Memory API (JEP 454). Their shared libraries are bundled for Windows,
macOS and Linux on x64 and arm64, and unpacked automatically on first
use, so nothing has to be installed and no third party jar has to be
declared.

    -Dimagify.avif.bundled=false   # use a system libavif instead
    -Dimagify.jpeg.bundled=false   # use a system jpegli instead
    -Dimagify.webp.bundled=false   # use a system libwebp instead

Nothing is fatal about a missing library. The `ImageIO` plug-in for the
format steps aside, a JPEG falls back to the JDK's own reader and writer,
and a call that needs the codec directly fails with a reason:

    if (!AvifCodec.isAvailable()) {
        System.err.println(AvifCodec.getUnavailableReason());
    }

Where a system library is used, put it on the platform's search path, or
on `java.library.path`.

Because the binding is `java.lang.foreign`, a program on JDK 24 or newer
that uses it from the class path is asked to allow native access. Nothing
fails without it, but the JDK warns on every run and will block the call
in a later release:

    java --enable-native-access=ALL-UNNAMED -cp ... YourApp

## Version

Supported `libavif` versions: `1.0.0` to `1.4.x`. `JpegliCodec` and
`WebpCodec` bind a flat C ABI of their own rather than jpegli's or
libwebp's, and refuse a library built against another revision of it.
<p align="right"><a href="#top">back to top</a></p>






## Prerequisites
Imagify runs on all major operating systems and requires only [Java version 24](https://docs.oracle.com/en/java/javase/24/) or later to run.
To check, please run `java -version` on your terminal.
<p align="right"><a href="#top">back to top</a></p>

## Install
For any code snippet below, please substitute the version given with the version of Imagify you wish to use.
#### [Maven](https://maven.apache.org/)
Add JitPack repository at the end of repositories element in your build.xml:
```xml
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>
```
Add it into in the dependencies element like so:
```xml
<dependency>
    <groupId>com.github.teletha</groupId>
    <artifactId>imagify</artifactId>
    <version>1.1.0</version>
</dependency>
```
#### [Gradle](https://gradle.org/)
Add JitPack repository at the end of repositories in your build.gradle:
```gradle
repositories {
    maven { url "https://jitpack.io" }
}
```
Add it into the dependencies section like so:
```gradle
dependencies {
    implementation 'com.github.teletha:imagify:1.1.0'
}
```
#### [SBT](https://www.scala-sbt.org/)
Add JitPack repository at the end of resolvers in your build.sbt:
```scala
resolvers += "jitpack" at "https://jitpack.io"
```
Add it into the libraryDependencies section like so:
```scala
libraryDependencies += "com.github.teletha" % "imagify" % "1.1.0"
```
#### [Leiningen](https://leiningen.org/)
Add JitPack repository at the end of repositories in your project().clj:
```clj
:repositories [["jitpack" "https://jitpack.io"]]
```
Add it into the dependencies section like so:
```clj
:dependencies [[com.github.teletha/imagify "1.1.0"]]
```
#### [Bee](https://teletha.github.io/bee)
Add it into your project definition class like so:
```java
require("com.github.teletha", "imagify", "1.1.0");
```
<p align="right"><a href="#top">back to top</a></p>


## Contributing
Contributions are what make the open source community such an amazing place to learn, inspire, and create. Any contributions you make are **greatly appreciated**.
If you have a suggestion that would make this better, please fork the repo and create a pull request. You can also simply open an issue with the tag "enhancement".
Don't forget to give the project a star! Thanks again!

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

The overwhelming majority of changes to this project don't add new features at all. Optimizations, tests, documentation, refactorings -- these are all part of making this product meet the highest standards of code quality and usability.
Contributing improvements in these areas is much easier, and much less of a hassle, than contributing code for new features.

### Bug Reports
If you come across a bug, please file a bug report. Warning us of a bug is possibly the most valuable contribution you can make to Imagify.
If you encounter a bug that hasn't already been filed, [please file a report](https://github.com/teletha/imagify/issues/new) with an [SSCCE](http://sscce.org/) demonstrating the bug.
If you think something might be a bug, but you're not sure, ask on StackOverflow or on [imagify-discuss](https://github.com/teletha/imagify/discussions).
<p align="right"><a href="#top">back to top</a></p>


## Dependency
Imagify depends on the following products on runtime.
* [jsvg-2.2.0](https://mvnrepository.com/artifact/com.github.weisj/jsvg/2.2.0)
<p align="right"><a href="#top">back to top</a></p>


## License
Copyright (C) 2026 The IMAGIFY Development Team

MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
<p align="right"><a href="#top">back to top</a></p>