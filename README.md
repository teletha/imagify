<p align="center">
    <a href="https://docs.oracle.com/en/java/javase/24/"><img src="https://img.shields.io/badge/Java-Release%2024-green"/></a>
    <span>&nbsp;</span>
    <a href="https://jitpack.io/#teletha/imagify"><img src="https://img.shields.io/jitpack/v/github/teletha/imagify?label=Repository&color=green"></a>
    <span>&nbsp;</span>
    <a href="https://teletha.github.io/imagify"><img src="https://img.shields.io/website.svg?down_color=red&down_message=CLOSE&label=Official%20Site&up_color=green&up_message=OPEN&url=https%3A%2F%2Fteletha.github.io%2Fimagify"></a>
</p>

## Summary
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
    <version>1.0.3</version>
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
    implementation 'com.github.teletha:imagify:1.0.3'
}
```
#### [SBT](https://www.scala-sbt.org/)
Add JitPack repository at the end of resolvers in your build.sbt:
```scala
resolvers += "jitpack" at "https://jitpack.io"
```
Add it into the libraryDependencies section like so:
```scala
libraryDependencies += "com.github.teletha" % "imagify" % "1.0.3"
```
#### [Leiningen](https://leiningen.org/)
Add JitPack repository at the end of repositories in your project().clj:
```clj
:repositories [["jitpack" "https://jitpack.io"]]
```
Add it into the dependencies section like so:
```clj
:dependencies [[com.github.teletha/imagify "1.0.3"]]
```
#### [Bee](https://teletha.github.io/bee)
Add it into your project definition class like so:
```java
require("com.github.teletha", "imagify", "1.0.3");
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
* [jna-5.19.1](https://mvnrepository.com/artifact/net.java.dev.jna/jna/5.19.1)
* [webp4j-core-2.5.0](https://mvnrepository.com/artifact/dev.matrixlab.webp4j/webp4j-core/2.5.0)
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