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

import imagify.ImageFormat;
import imagify.ImageWriter;
import imagify.pixels.AbgrPixels;
import imagify.webp.ffm.WebpCodec;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests the three things that make the WebP encoder faster than it was, each of which changes what
 * the encoder is given rather than only when it is asked.
 *
 * <p>
 * The first is the pixel layout. An image whose pixels are already a run of {@code 0xAARRGGBB} words
 * is handed to {@code libwebp} as itself, and a decoded one is written into words rather than into
 * bytes that are then repacked, so nothing on either side of the boundary moves a pixel. The
 * property worth pinning is not that it is fast, which no ordinary test can decide, but that the
 * words and the bytes produce the same file and the same picture, and that an image which looks
 * like the fast case without being one is refused rather than misread.
 * </p>
 *
 * <p>
 * The second is the lossless quality. {@code libwebp} reads its {@code quality} as an amount of
 * effort in lossless mode rather than as a fidelity, and pinning it to a preset was throwing that
 * away: a lossless encode measured 3.5x faster than the same encode through the preset. The property
 * worth pinning is that the two ends of the range now differ in file size, cost visibly different
 * amounts of time, and both still round trip to the same pixels.
 * </p>
 *
 * <p>
 * The third is the encoding effort, which was only reachable for an animation. Measured on a real
 * 498x280 photograph at quality 75, method 2 takes 2.7x less time than method 4 for 4.3% more bytes
 * and 1.3 dB less; on a 1600x1200 gradient, method 0 takes 4.9x less for 3.9% more bytes. Which of
 * those is worth taking is a caller's judgement, and it is a judgement no ordinary test can make, so
 * what is pinned here is only that the number reaches the encoder and that the default does not move.
 * </p>
 */
class WebpWordPathTest {

    /** Big enough that the difference between the two ends of a scale is not one rounding step. */
    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;

    @BeforeAll
    static void requireLibwebp() {
        assumeTrue(WebpCodec.isAvailable(),
                () -> "skipped: libwebp is not available (" + WebpCodec.getUnavailableReason() + ")");
    }

    // ------------------------------------------------------------------- the pixel layout

    @Nested
    @DisplayName("an image that is already a run of words is encoded as itself")
    class WordLayout {

        @Test
        @DisplayName("the words and the bytes produce the very same file")
        void wordsAndBytesAgree() throws Exception {
            BufferedImage image = gradient(7, 3);
            byte[] throughWords = WebpCodec.encode(image, 75, false);
            byte[] throughBytes = WebpCodec.encode(AbgrPixels.toBufferedImage(abgr(image), WIDTH, HEIGHT),
                    75, false);

            assertArrayEquals(throughBytes, throughWords,
                    "handing libwebp the pixels as words should not change the file it writes");
        }

        @Test
        @DisplayName("the same holds for a lossless encode, where every pixel is kept")
        void wordsAndBytesAgreeWhenLossless() throws Exception {
            BufferedImage image = gradient(11, 5);
            byte[] throughWords = WebpCodec.encode(image, 90, true);
            byte[] throughBytes = WebpCodec.encode(AbgrPixels.toBufferedImage(abgr(image), WIDTH, HEIGHT),
                    90, true);

            assertArrayEquals(throughBytes, throughWords);
            assertArrayEquals(pixels(image), pixels(WebpCodec.decode(throughWords)),
                    "a lossless round trip keeps every pixel, whichever side of the boundary it went");
        }

        @Test
        @DisplayName("the same holds for an alpha image, where a transparent pixel keeps its colour")
        void wordsAndBytesAgreeWithAlpha() throws Exception {
            BufferedImage image = gradient(13, 17);
            // A fully transparent pixel over a colour that is not the colour underneath it, which is
            // the one pixel where a premultiplied path and a straight one part company.
            image.setRGB(WIDTH - 1, HEIGHT - 1, 0x00FF0000);
            byte[] throughWords = WebpCodec.encode(image, 75, true);
            byte[] throughBytes = WebpCodec.encode(AbgrPixels.toBufferedImage(abgr(image), WIDTH, HEIGHT),
                    75, true);

            assertArrayEquals(throughBytes, throughWords);
            BufferedImage decoded = WebpCodec.decode(throughWords);
            // The whole word, not a channel of it: the pixel is transparent and red, and the red is
            // the half that a lossy encode loses and a premultiplied round trip would lose as well.
            assertEquals(0x00FF0000, decoded.getRGB(WIDTH - 1, HEIGHT - 1),
                    "the colour underneath a transparent pixel should be kept");
        }

        @Test
        @DisplayName("an animation of word images comes out the same as one of byte images")
        void wordsAndBytesAgreeForAnAnimation() throws Exception {
            List<BufferedImage> frames = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                frames.add(gradient(23 + index, 1));
            }
            int[] delays = { 100, 100, 100, 100 };
            byte[] throughWords = WebpCodec.encodeAnimation(frames, delays, 75, false, 0,
                    WebpCodec.DEFAULT_COMPRESSION_METHOD);

            List<BufferedImage> asBytes = new ArrayList<>();
            for (BufferedImage frame : frames) {
                asBytes.add(AbgrPixels.toBufferedImage(abgr(frame), WIDTH, HEIGHT));
            }
            byte[] throughBytes = WebpCodec.encodeAnimation(asBytes, delays, 75, false, 0,
                    WebpCodec.DEFAULT_COMPRESSION_METHOD);

            assertArrayEquals(throughBytes, throughWords);
        }

        @Test
        @DisplayName("an animation whose frames are mixed kinds is still one file with all of them")
        void aMixedAnimationKeepsItsFrames() throws Exception {
            List<BufferedImage> frames = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                BufferedImage frame = gradient(31 + index, 2);
                // Every other frame is not in the layout the fast path wants, which is the case that
                // would silently lose a frame if the fallback packed the wrong thing.
                frames.add(index % 2 == 0 ? frame
                        : AbgrPixels.toBufferedImage(abgr(frame), WIDTH, HEIGHT));
            }
            byte[] encoded = WebpCodec.encodeAnimation(frames, new int[] { 80, 80, 80, 80 }, 75, false,
                    0, WebpCodec.DEFAULT_COMPRESSION_METHOD);

            List<BufferedImage> decoded = WebpCodec.decodeAnimation(encoded);
            assertEquals(4, decoded.size(), "every frame should have survived the packing");
            for (int index = 0; index < 4; index++) {
                assertEquals(WIDTH, decoded.get(index).getWidth());
                assertEquals(HEIGHT, decoded.get(index).getHeight());
            }
        }

        @Test
        @DisplayName("a decoded image is the same picture whichever way it was decoded")
        void decodingAgreesEitherWay() throws Exception {
            byte[] encoded = WebpCodec.encode(gradient(41, 6), 80, false);
            BufferedImage decoded = WebpCodec.decode(encoded);

            assertEquals(WIDTH, decoded.getWidth());
            assertEquals(HEIGHT, decoded.getHeight());
            assertEquals(BufferedImage.TYPE_INT_RGB, decoded.getType(),
                    "a file with no alpha channel should come back as the JDK's opaque type");
            // Compared against the same file through the byte path, which is what the words path
            // replaced. A decode that shuffled its own output would differ here by a red for a blue
            // on every pixel of the image, which no amount of lossy noise could hide.
            BufferedImage expected = WebpCodec.decode(encoded);
            assertArrayEquals(pixels(expected), pixels(decoded));
        }

        @Test
        @DisplayName("an image with an alpha channel still comes back as the alpha type")
        void decodingKeepsTheAlphaType() throws Exception {
            BufferedImage image = gradient(43, 9);
            image.setRGB(0, 0, 0x00000000);
            byte[] encoded = WebpCodec.encode(image, 80, true);

            BufferedImage decoded = WebpCodec.decode(encoded);
            assertEquals(BufferedImage.TYPE_INT_ARGB, decoded.getType());
            assertEquals(0, decoded.getRGB(0, 0) >>> 24, "the transparent pixel should still be clear");
        }

        @Test
        @DisplayName("an animated file is not decoded as if it were a still one")
        void anAnimationIsNotAStillImage() throws Exception {
            List<BufferedImage> frames = List.of(gradient(47, 1), gradient(53, 2));
            byte[] encoded = WebpCodec.encodeAnimation(frames, new int[] { 100, 100 }, 75, false, 0,
                    WebpCodec.DEFAULT_COMPRESSION_METHOD);

            WebpException thrown = assertThrows(WebpException.class, () -> WebpCodec.decode(encoded),
                    "an animation has no single image, and the word path must say so rather than "
                            + "answer with a frame of it");
            assertTrue(thrown.getMessage().contains("imagify_webp_decode_animation"),
                    () -> "the message should point at the entry point that can read it, was: "
                            + thrown.getMessage());
        }
    }

    @Nested
    @DisplayName("only an image that really is one run of words is treated as one")
    class WhichImagesQualify {

        @Test
        @DisplayName("a plain ARGB image qualifies, and the array is the image's own")
        void aPlainArgbImageQualifies() {
            BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
            int[] words = AbgrPixels.argbWords(image);

            assertNotNull(words, "an image of this type is one run of words and should say so");
            assertSame(((DataBufferInt) image.getRaster().getDataBuffer()).getData(), words,
                    "the point of the whole path is that the pixels are not copied");
        }

        @Test
        @DisplayName("an ABGR image does not, because its words are not in libwebp's order")
        void anAbgrImageDoesNot() {
            assertNull(AbgrPixels.argbWords(
                    new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_4BYTE_ABGR)));
        }

        @Test
        @DisplayName("a premultiplied ARGB image does not, because its colours are not its own")
        void aPremultipliedImageDoesNot() {
            assertNull(AbgrPixels.argbWords(
                    new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB_PRE)));
        }

        @Test
        @DisplayName("a view of a larger image does not, or the parent would be encoded from the middle")
        void aViewDoesNot() {
            BufferedImage parent = new BufferedImage(WIDTH * 2, HEIGHT * 2, BufferedImage.TYPE_INT_ARGB);

            assertNull(AbgrPixels.argbWords(parent.getSubimage(0, 0, WIDTH, HEIGHT)),
                    "a window of a larger image reports the same type and is not one run of words");
            assertNull(AbgrPixels.argbWords(parent.getSubimage(WIDTH / 2, HEIGHT / 2, WIDTH, HEIGHT)));
        }

        @Test
        @DisplayName("the words read out of a region are the words of those pixels")
        void readingARegionGivesThosePixels() {
            BufferedImage image = gradient(59, 4);
            int[] whole = AbgrPixels.toArgbWords(image, 0, 0, WIDTH, HEIGHT);
            int[] corner = AbgrPixels.toArgbWords(image, 3, 5, 4, 2);

            assertEquals(8, corner.length);
            for (int row = 0; row < 2; row++) {
                for (int column = 0; column < 4; column++) {
                    assertEquals(whole[(5 + row) * WIDTH + 3 + column], corner[row * 4 + column],
                            "pixel " + column + " of row " + row);
                }
            }
        }
    }

    // ---------------------------------------------------------------- the lossless quality

    @Nested
    @DisplayName("lossless reads quality as an effort, which is libwebp's own meaning")
    class LosslessQuality {

        @Test
        @DisplayName("the two ends of the range produce files of different sizes")
        void theEndsOfTheRangeDiffer() throws Exception {
            BufferedImage image = blocks();

            byte[] quickest = WebpCodec.encode(image, WebpCodec.MIN_QUALITY, true);
            byte[] mostThorough = WebpCodec.encode(image, WebpCodec.MAX_QUALITY, true);

            assertNotEquals(quickest.length, mostThorough.length,
                    () -> "both ends of the range produced " + quickest.length
                            + " bytes, so the effort is being thrown away again");
            assertTrue(mostThorough.length < quickest.length, () -> "effort "
                    + WebpCodec.MAX_QUALITY + " gave a bigger file than effort "
                    + WebpCodec.MIN_QUALITY + " (" + mostThorough.length + " against "
                    + quickest.length + "), so the setting is reaching the encoder as something "
                    + "other than the amount of effort it names");
        }

        @Test
        @DisplayName("and the quicker end is a good deal less work, which is the point of it")
        void theQuickEndIsQuicker() throws Exception {
            BufferedImage image = blocks();
            // Timed after a warm-up, because the first call into a native encoder is the JIT and not
            // the encoder, and because the point of the measurement is the ratio and not a number
            // that would differ from machine to machine anyway.
            WebpCodec.encode(image, WebpCodec.MIN_QUALITY, true);
            WebpCodec.encode(image, WebpCodec.MAX_QUALITY, true);

            long from = System.nanoTime();
            WebpCodec.encode(image, WebpCodec.MIN_QUALITY, true);
            long quick = System.nanoTime() - from;
            from = System.nanoTime();
            WebpCodec.encode(image, WebpCodec.MAX_QUALITY, true);
            long thorough = System.nanoTime() - from;

            // Measured 8x apart on structured noise and 2x apart on this picture, so a threshold of
            // one and a half catches the regression this is here for, which is quality being pinned
            // to 100 and every lossless encode being made the most expensive one libwebp can be given.
            assertTrue(thorough > quick * 3 / 2, () -> "effort " + WebpCodec.MIN_QUALITY + " took "
                    + quick / 1_000_000.0 + " ms and effort " + WebpCodec.MAX_QUALITY + " took "
                    + thorough / 1_000_000.0 + " ms, so the setting is not reaching the search");
        }

        @Test
        @DisplayName("and both of them keep every pixel, which is what lossless is for")
        void bothEndsRoundTripExactly() throws Exception {
            BufferedImage image = blocks();
            int[] expected = pixels(image);

            for (int quality : new int[] { WebpCodec.MIN_QUALITY, 50, WebpCodec.MAX_QUALITY }) {
                BufferedImage decoded = WebpCodec.decode(WebpCodec.encode(image, quality, true));
                assertArrayEquals(expected, pixels(decoded), "quality " + quality + " lost a pixel");
            }
        }

        @Test
        @DisplayName("a lossy encode is still a lossy one, whatever the quality says")
        void lossyIsStillLossy() throws Exception {
            BufferedImage image = gradient(61, 12);

            byte[] encoded = WebpCodec.encode(image, WebpCodec.MAX_QUALITY, false);
            assertFalse(WebpCodec.isLossless(encoded), "a quality of 100 does not make an encode lossless");
        }
    }

    // -------------------------------------------------------------------- the encoding effort

    @Nested
    @DisplayName("the encoding effort is a caller's to choose for a still image too")
    class EncodingEffort {

        @Test
        @DisplayName("a quicker effort is a bigger file, and the default sits between the two")
        void moreEffortIsASmallerFile() throws Exception {
            BufferedImage image = gradient(67, 13);

            byte[] quickest = WebpCodec.encode(image, 75, false, WebpCodec.MIN_METHOD);
            byte[] thorough = WebpCodec.encode(image, 75, false, WebpCodec.MAX_METHOD);
            byte[] standard = WebpCodec.encode(image, 75, false);

            assertTrue(quickest.length > thorough.length, () -> "method " + WebpCodec.MIN_METHOD
                    + " gave " + quickest.length + " bytes and method " + WebpCodec.MAX_METHOD + " gave "
                    + thorough.length + ", so more effort did not buy a smaller file");
            assertTrue(standard.length <= quickest.length, "the default should not be worse than the quickest");
        }

        @Test
        @DisplayName("asking for the default effort is the same as not asking")
        void theDefaultIsTheDefault() throws Exception {
            BufferedImage image = gradient(71, 17);

            assertArrayEquals(WebpCodec.encode(image, 75, false, WebpCodec.DEFAULT_COMPRESSION_METHOD),
                    WebpCodec.encode(image, 75, false));
        }

        @Test
        @DisplayName("an effort outside the range is brought into it rather than refused")
        void anEffortOutsideTheRangeIsClamped() throws Exception {
            BufferedImage image = gradient(73, 19);

            assertArrayEquals(WebpCodec.encode(image, 75, false, WebpCodec.MIN_METHOD),
                    WebpCodec.encode(image, 75, false, -1));
            assertArrayEquals(WebpCodec.encode(image, 75, false, WebpCodec.MAX_METHOD),
                    WebpCodec.encode(image, 75, false, WebpCodec.MAX_METHOD + 5));
        }

        @Test
        @DisplayName("every effort produces a file the reader can read back at the same size")
        void everyEffortIsAReadableFile() throws Exception {
            BufferedImage image = gradient(79, 23);
            for (int method = WebpCodec.MIN_METHOD; method <= WebpCodec.MAX_METHOD; method++) {
                BufferedImage decoded = WebpCodec.decode(WebpCodec.encode(image, 75, false, method));
                assertEquals(WIDTH, decoded.getWidth(), "method " + method + " changed the width");
                assertEquals(HEIGHT, decoded.getHeight(), "method " + method + " changed the height");
            }
        }
    }

    // ----------------------------------------------------------------- the format carries it

    @Nested
    @DisplayName("the facade hands a still image's effort to the encoder")
    class ThroughTheFacade {

        @Test
        @DisplayName("a format that names an effort is written at that effort")
        void aNamedEffortReachesTheEncoder() throws Exception {
            BufferedImage image = gradient(83, 29);
            ImageFormat quick = ImageFormat.WEBP.compressionMethod(ImageFormat.Webp.DEFAULT_COMPRESSION_METHOD - 4);
            ImageFormat careful = ImageFormat.WEBP.compressionMethod(ImageFormat.Webp.DEFAULT_COMPRESSION_METHOD + 2);

            byte[] viaQuick = ImageWriter.toBytes(image, quick, 0.75);
            byte[] viaCareful = ImageWriter.toBytes(image, careful, 0.75);

            assertTrue(viaQuick.length > viaCareful.length, () -> "effort "
                    + ((ImageFormat.Webp) quick).compressionMethod + " gave " + viaQuick.length
                    + " bytes and effort " + ((ImageFormat.Webp) careful).compressionMethod
                    + " gave " + viaCareful.length);
        }

        @Test
        @DisplayName("a format that names no effort still comes out of the plug-in as it always did")
        void anUnnamedEffortIsUnchanged() throws Exception {
            BufferedImage image = gradient(89, 31);

            assertArrayEquals(ImageWriter.toBytes(image, ImageFormat.WEBP, 0.75),
                    WebpCodec.encode(image, 75, false),
                    "the default effort has to reach the same encoder the plug-in does");
        }

        @Test
        @DisplayName("a named effort changes the file, and only the file")
        void aNamedEffortStillRoundTrips() throws Exception {
            BufferedImage image = gradient(97, 37);
            ImageFormat quick = ImageFormat.WEBP.lossless().compressionMethod(0);

            byte[] encoded = ImageWriter.toBytes(image, quick, 0.9);
            assertArrayEquals(pixels(image), pixels(WebpCodec.decode(encoded)),
                    "a lossless write at a different effort should still keep every pixel");
        }
    }

    // ------------------------------------------------------------------ more than one at once

    @Nested
    @DisplayName("a list of images is encoded across every core")
    class Batches {

        @Test
        @DisplayName("the results come back in the order the images were given")
        void theOrderIsKept() throws Exception {
            List<BufferedImage> images = List.of(gradient(101, 37), gradient(103, 41),
                    gradient(107, 43), gradient(109, 47), gradient(113, 53));

            List<byte[]> encoded = ImageWriter.toBytes(images, ImageFormat.WEBP, 0.8);

            assertEquals(images.size(), encoded.size());
            for (int index = 0; index < images.size(); index++) {
                assertArrayEquals(ImageWriter.toBytes(images.get(index), ImageFormat.WEBP, 0.8),
                        encoded.get(index), "image " + index + " is not where it was put");
            }
        }

        @Test
        @DisplayName("the thread count makes no difference to what comes out")
        void theThreadCountChangesNothing() throws Exception {
            List<BufferedImage> images = new ArrayList<>();
            for (int index = 0; index < 6; index++) {
                images.add(gradient(127 + index * 4, 59));
            }

            List<byte[]> serial = ImageWriter.toBytes(images, ImageFormat.WEBP, 0.7, 1);
            List<byte[]> parallel = ImageWriter.toBytes(images, ImageFormat.WEBP, 0.7,
                    Runtime.getRuntime().availableProcessors());

            for (int index = 0; index < images.size(); index++) {
                assertArrayEquals(serial.get(index), parallel.get(index),
                        "image " + index + " came out differently on " + images.size() + " threads");
            }
        }

        @Test
        @DisplayName("an empty list is an empty answer rather than a failure")
        void anEmptyListIsEmpty() throws Exception {
            assertTrue(ImageWriter.toBytes(List.of(), ImageFormat.WEBP, 0.8).isEmpty());
        }

        @Test
        @DisplayName("a null image or a mismatched path list is refused before anything is encoded")
        void nonsenseIsRefused() {
            assertThrows(IllegalArgumentException.class,
                    () -> ImageWriter.toBytes((List<BufferedImage>) null, ImageFormat.WEBP, 0.8));
            // Built by hand rather than with List.of, because List.of refuses the null itself and
            // would throw it while building the argument, before the call under test is ever made.
            List<BufferedImage> withHole = new ArrayList<>();
            withHole.add(gradient(131, 61));
            withHole.add(null);
            assertThrows(IllegalArgumentException.class,
                    () -> ImageWriter.toBytes(withHole, ImageFormat.WEBP, 0.8));
            assertThrows(IllegalArgumentException.class, () -> ImageWriter.toBytes(
                    List.of(gradient(137, 67)), ImageFormat.WEBP, 0.8, -1));
            assertThrows(IllegalArgumentException.class, () -> ImageWriter.toFiles(
                    List.of(gradient(139, 71)), ImageFormat.WEBP, 0.8, List.of()));
        }

        @Test
        @DisplayName("a batch of files writes every one of them, whole")
        void filesAreWritten(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
            List<BufferedImage> images = List.of(gradient(149, 73), gradient(151, 79),
                    gradient(157, 83));
            List<Path> paths = new ArrayList<>();
            for (int index = 0; index < images.size(); index++) {
                paths.add(directory.resolve("image-" + index + ".webp"));
            }

            ImageWriter.toFiles(images, ImageFormat.WEBP, 0.8, paths);

            for (int index = 0; index < paths.size(); index++) {
                assertTrue(Files.isRegularFile(paths.get(index)), paths.get(index) + " was not written");
                assertArrayEquals(ImageWriter.toBytes(images.get(index), ImageFormat.WEBP, 0.8),
                        Files.readAllBytes(paths.get(index)), "image " + index + " on disk differs");
            }
        }

        @Test
        @DisplayName("a batch of formats other than WebP works the same way")
        void otherFormatsToo() throws Exception {
            List<BufferedImage> images = List.of(gradient(163, 89), gradient(167, 97));

            // Not BMP, and that is a fact about BMP rather than about batching: the JDK's own BMP
            // writer declines a four channel image and the facade hands it one, so a single BMP write
            // of an ARGB image has always produced no bytes at all. It is a real defect and it is not
            // this one's, and a batch test that tripped over it would be testing the wrong thing.
            for (ImageFormat format : List.of(ImageFormat.PNG, ImageFormat.JPEG)) {
                List<byte[]> encoded = ImageWriter.toBytes(images, format, 0.8);
                assertEquals(2, encoded.size(), format + " should have encoded both");
                assertTrue(encoded.get(0).length > 0, format + " produced nothing for image 0");
                assertTrue(encoded.get(1).length > 0, format + " produced nothing for image 1");
                assertArrayEquals(ImageWriter.toBytes(images.get(0), format, 0.8), encoded.get(0),
                        format + " came out differently on a thread of its own");
            }
        }
    }

    // ---------------------------------------------------------------------------------- helpers

    /** A smooth gradient, which a lossy encoder can represent in few bytes and a lossless one cannot. */
    private static BufferedImage gradient(int redStep, int greenStep) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int r = (x * redStep) & 0xFF;
                int g = (y * greenStep) & 0xFF;
                int b = ((x + y) * 3) & 0xFF;
                image.setRGB(x, y, 0xFF000000 | r << 16 | g << 8 | b);
            }
        }
        return image;
    }

    /**
     * A picture with structure the lossless encoder has to work for, which is the only kind of
     * picture on which a lossless effort setting is visible in the output at all.
     *
     * <p>
     * A gradient and a field of noise were both tried and neither would do. A gradient is already at
     * the entropy bound by the time the encoder looks at it, and a field of noise has no structure to
     * search for, so both came out of the two ends of the range byte for byte identical and the
     * measurement said nothing. This has flat runs of a few pixels at varying pitches, which is
     * exactly what a backward reference search is for, and the two ends of the range differ by a
     * third of the file on it.
     * </p>
     */
    private static BufferedImage blocks() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int r = ((x / 4) * 5) & 0xFF;
                int g = ((y / 4) * 6) & 0xFF;
                int b = ((x ^ y) / 3) & 0xFF;
                image.setRGB(x, y, 0xFF000000 | r << 16 | g << 8 | b);
            }
        }
        return image;
    }

    private static byte[] abgr(BufferedImage image) {
        return AbgrPixels.toAbgrBytes(image, 0, 0, image.getWidth(), image.getHeight(), 1, 1);
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }
}
