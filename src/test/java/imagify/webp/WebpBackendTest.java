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

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import imagify.webp.WebpCodec.Backend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests that the two WebP backends are interchangeable, and that the choice between them is a
 * switch.
 *
 * <p>
 * The promise of shipping both is that a file written through one is read by the other, and that a
 * program naming either of them gets WebP. Neither shows up in a test that goes through
 * {@link WebpCodec} alone, because that picks one backend on first use and keeps it for the life of
 * the JVM: a single run sees one of them, whichever the property named. So the backends are driven
 * directly here and are asked to agree, which is the part that would rot, and the facade is
 * checked against whichever one it actually did pick.
 * </p>
 *
 * <p>
 * The backends are named in full rather than imported. Two classes of the same simple name cannot
 * be imported into one file, and saying {@code ffm()} or {@code webp4j()} at every call site is
 * what keeps a test from quietly measuring the wrong one.
 * </p>
 */
class WebpBackendTest {

    private static final int WIDTH = 96;
    private static final int HEIGHT = 64;

    /** {@code RIFF}, a four byte size, {@code WEBP} and a lossy {@code VP8 } chunk name. */
    private static final byte[] RIFF = {
            'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' ' };

    // ------------------------------------------------------------------------------- naming

    @Nested
    @DisplayName("naming a backend")
    class Naming {

        @Test
        @DisplayName("the property value is either backend, in any case and with room around it")
        void namesAreForgiving() {
            assertSame(Backend.FFM, Backend.of("ffm"));
            assertSame(Backend.FFM, Backend.of("FFM"));
            assertSame(Backend.FFM, Backend.of("  Ffm  "));
            assertSame(Backend.WEBP4J, Backend.of("webp4j"));
            assertSame(Backend.WEBP4J, Backend.of("WEBP4J"));
            assertSame(Backend.WEBP4J, Backend.of(" WebP4j "));
        }

        @Test
        @DisplayName("anything that names neither backend is not one, and the default takes over")
        void anythingElseIsNotABackend() {
            assertNull(Backend.of(null));
            assertNull(Backend.of(""));
            assertNull(Backend.of("   "));
            assertNull(Backend.of("jna"));
            assertNull(Backend.of("webp"));
        }

        @Test
        @DisplayName("every backend is named by the value that selects it")
        void titlesRoundTrip() {
            for (Backend backend : Backend.values()) {
                assertSame(backend, Backend.of(backend.title()),
                        backend + " should be selectable by its own name");
            }
            assertEquals("ffm", Backend.FFM.title());
            assertEquals("webp4j", Backend.WEBP4J.title());
        }

        @Test
        @DisplayName("the property is the documented one, so the flag in a script is the flag in here")
        void thePropertyIsTheDocumentedOne() {
            assertEquals("imagify.webp.backend", WebpCodec.BACKEND_PROPERTY);
        }
    }

    // ------------------------------------------------------------------------- the switch itself

    @Nested
    @DisplayName("the backend in use")
    class Selected {

        @Test
        @DisplayName("is one of the two, and the same one however often it is asked for")
        void isStable() {
            Backend first = WebpCodec.backend();
            assertNotNull(first);
            assertSame(first, WebpCodec.backend());
            assertSame(first, WebpCodec.backend());
        }

        @Test
        @DisplayName("is the default unless the property asks for the other one")
        void defaultsToFfm() {
            String asked = System.getProperty(WebpCodec.BACKEND_PROPERTY);
            assumeTrue(asked == null || asked.isBlank(),
                    () -> "skipped: this run asked for '" + asked + "'");
            assertSame(Backend.FFM, WebpCodec.backend());
        }

        @Test
        @DisplayName("can be used whenever the facade says it can")
        void selectedBackendIsUsable() {
            assumeTrue(WebpCodec.isAvailable(),
                    () -> "skipped: no backend can load its library ("
                            + WebpCodec.getUnavailableReason() + ")");
            assertTrue(WebpCodec.backend().isAvailable(),
                    "the backend in use should be one that works, since one of them does");
        }

        @Test
        @DisplayName("is reported the same way by the facade and by the backend itself")
        void facadeAgreesWithTheBackend() {
            assertEquals(WebpCodec.backend().isAvailable(), WebpCodec.isAvailable());
            if (WebpCodec.isAvailable()) {
                assertNull(WebpCodec.getUnavailableReason());
            } else {
                assertNotNull(WebpCodec.getUnavailableReason(), "an unusable codec has to say why");
            }
        }

        @Test
        @DisplayName("is what the facade actually goes through, rather than around")
        void theFacadeReachesTheBackendItNamed() throws Exception {
            assumeTrue(WebpCodec.isAvailable(), () -> "skipped: no backend can load its library");
            byte[] encoded = WebpCodec.encode(frame(0), 80, false);
            WebpImageInfo direct = switch (WebpCodec.backend()) {
                case FFM -> imagify.webp.ffm.WebpCodec.readHeader(encoded);
                case WEBP4J -> imagify.webp.webp4j.WebpCodec.readHeader(encoded);
            };
            assertEquals(direct, WebpCodec.readHeader(encoded));
        }
    }

    // -------------------------------------------------------------------------- the two backends

    @Nested
    @DisplayName("the two backends")
    class Both {

        @Test
        @DisplayName("read the same still image as each other, whatever bitstream it holds")
        void readEachOthersStills() throws Exception {
            assumeTrue(allAvailable(), () -> "skipped: " + missing());
            for (byte[] encoded : new byte[][] {lossy(), lossless()}) {
                String what = WebpCodec.formatName(imagify.webp.ffm.WebpCodec.readHeader(encoded).format());
                assertEquals(imagify.webp.ffm.WebpCodec.readHeader(encoded),
                        imagify.webp.webp4j.WebpCodec.readHeader(encoded),
                        "the two should report the same properties for a " + what + " file");
                assertEquals(imagify.webp.ffm.WebpCodec.decode(encoded).getWidth(),
                        imagify.webp.webp4j.WebpCodec.decode(encoded).getWidth());
                assertEquals(imagify.webp.ffm.WebpCodec.decode(encoded).getHeight(),
                        imagify.webp.webp4j.WebpCodec.decode(encoded).getHeight());
                assertEquals(imagify.webp.ffm.WebpCodec.isLossless(encoded),
                        imagify.webp.webp4j.WebpCodec.isLossless(encoded));
            }
        }

        @Test
        @DisplayName("read the same animation as each other, down to the timing")
        void readEachOthersAnimations() throws Exception {
            assumeTrue(allAvailable(), () -> "skipped: " + missing());
            byte[] encoded = animated();

            assertEquals(3, imagify.webp.ffm.WebpCodec.readHeader(encoded).frameCount());
            assertEquals(imagify.webp.ffm.WebpCodec.readHeader(encoded),
                    imagify.webp.webp4j.WebpCodec.readHeader(encoded));
            assertEquals(3, imagify.webp.ffm.WebpCodec.decodeAnimation(encoded).size());
            assertEquals(3, imagify.webp.webp4j.WebpCodec.decodeAnimation(encoded).size());
            assertArrayEquals(imagify.webp.ffm.WebpCodec.readAnimationTiming(encoded)[0],
                    imagify.webp.webp4j.WebpCodec.readAnimationTiming(encoded)[0]);
            assertArrayEquals(imagify.webp.ffm.WebpCodec.readAnimationTiming(encoded)[1],
                    imagify.webp.webp4j.WebpCodec.readAnimationTiming(encoded)[1]);
            assertEquals(3, imagify.webp.ffm.WebpCodec.decodeFile(encoded).frames().size());
            assertEquals(3, imagify.webp.webp4j.WebpCodec.decodeFile(encoded).frames().size());
        }

        @Test
        @DisplayName("decode each other's frames into nearly the same pixels")
        void decodeEachOthersFrames() throws Exception {
            assumeTrue(allAvailable(), () -> "skipped: " + missing());
            byte[] throughFfm = imagify.webp.ffm.WebpCodec.encode(frame(0), 80, false);
            BufferedImage expected = imagify.webp.ffm.WebpCodec.decode(throughFfm);
            BufferedImage throughWebp4j = imagify.webp.webp4j.WebpCodec.decode(throughFfm);

            assertEquals(expected.getWidth(), throughWebp4j.getWidth());
            assertEquals(expected.getHeight(), throughWebp4j.getHeight());
            for (int y = 0; y < expected.getHeight(); y++) {
                for (int x = 0; x < expected.getWidth(); x++) {
                    int difference = distance(expected.getRGB(x, y), throughWebp4j.getRGB(x, y));
                    assertTrue(difference <= 24, "pixel " + x + "," + y + " differs by " + difference
                            + ", which is more than a lossy decode of the same file should cost");
                }
            }
        }

        @Test
        @DisplayName("write a file the ImageIO plug-in reads, whichever one wrote it")
        void writeThroughTheFacade() throws Exception {
            assumeTrue(allAvailable(), () -> "skipped: " + missing());
            byte[] throughFfm = animated();
            byte[] throughWebp4j = imagify.webp.webp4j.WebpCodec
                    .encodeAnimation(frames(), delays(), 80, false, 0, 2);

            for (byte[] encoded : new byte[][] {throughFfm, throughWebp4j}) {
                assertEquals(3, WebpCodec.readHeader(encoded).frameCount());
                assertEquals(3, WebpCodec.decodeAnimation(encoded).size());
                assertEquals(3, WebpCodec.decodeFile(encoded).frames().size());
            }
        }

        @Test
        @DisplayName("agree on the container questions, which need no library at all")
        void agreeOnTheContainer() {
            assertEquals(imagify.webp.ffm.WebpCodec.headerLength(),
                    imagify.webp.webp4j.WebpCodec.headerLength());
            assertEquals(imagify.webp.ffm.WebpCodec.losslessHeaderLength(),
                    imagify.webp.webp4j.WebpCodec.losslessHeaderLength());
            assertEquals(WebpCodec.headerLength(), imagify.webp.ffm.WebpCodec.headerLength());
            assertTrue(imagify.webp.ffm.WebpCodec.isWebP(RIFF), "a RIFF/WEBP header is one");
            assertTrue(imagify.webp.webp4j.WebpCodec.isWebP(RIFF), "a RIFF/WEBP header is one");
            byte[] notWebP = "not a webp file at all".getBytes();
            assertFalse(imagify.webp.ffm.WebpCodec.isWebP(notWebP));
            assertFalse(imagify.webp.webp4j.WebpCodec.isWebP(notWebP));
            assertEquals(imagify.webp.ffm.WebpCodec.formatName(1),
                    imagify.webp.webp4j.WebpCodec.formatName(1));
        }

        @Test
        @DisplayName("carry the same constants, because the caller reads them off the facade")
        void agreeOnTheConstants() {
            assertEquals(imagify.webp.ffm.WebpCodec.DEFAULT_QUALITY,
                    imagify.webp.webp4j.WebpCodec.DEFAULT_QUALITY);
            assertEquals(imagify.webp.ffm.WebpCodec.MIN_QUALITY,
                    imagify.webp.webp4j.WebpCodec.MIN_QUALITY);
            assertEquals(imagify.webp.ffm.WebpCodec.MAX_QUALITY,
                    imagify.webp.webp4j.WebpCodec.MAX_QUALITY);
            assertEquals(imagify.webp.ffm.WebpCodec.MIN_METHOD,
                    imagify.webp.webp4j.WebpCodec.MIN_METHOD);
            assertEquals(imagify.webp.ffm.WebpCodec.MAX_METHOD,
                    imagify.webp.webp4j.WebpCodec.MAX_METHOD);
            assertEquals(imagify.webp.ffm.WebpCodec.DEFAULT_COMPRESSION_METHOD,
                    imagify.webp.webp4j.WebpCodec.DEFAULT_COMPRESSION_METHOD);
            assertEquals(imagify.webp.ffm.WebpCodec.FORMAT_VP8,
                    imagify.webp.webp4j.WebpCodec.FORMAT_VP8);
            assertEquals(imagify.webp.ffm.WebpCodec.FORMAT_VP8L,
                    imagify.webp.webp4j.WebpCodec.FORMAT_VP8L);
            assertEquals(imagify.webp.ffm.WebpCodec.FORMAT_VP8X,
                    imagify.webp.webp4j.WebpCodec.FORMAT_VP8X);

            // The facade publishes the same numbers, because the format and the writer above it
            // read them off the facade and never learn which backend is behind it.
            assertEquals(imagify.webp.ffm.WebpCodec.DEFAULT_QUALITY, WebpCodec.DEFAULT_QUALITY);
            assertEquals(imagify.webp.ffm.WebpCodec.MIN_QUALITY, WebpCodec.MIN_QUALITY);
            assertEquals(imagify.webp.ffm.WebpCodec.MAX_QUALITY, WebpCodec.MAX_QUALITY);
            assertEquals(imagify.webp.ffm.WebpCodec.MIN_METHOD, WebpCodec.MIN_METHOD);
            assertEquals(imagify.webp.ffm.WebpCodec.MAX_METHOD, WebpCodec.MAX_METHOD);
            assertEquals(imagify.webp.ffm.WebpCodec.DEFAULT_COMPRESSION_METHOD,
                    WebpCodec.DEFAULT_COMPRESSION_METHOD);
            assertEquals(imagify.webp.ffm.WebpCodec.FORMAT_VP8, WebpCodec.FORMAT_VP8);
            assertEquals(imagify.webp.ffm.WebpCodec.FORMAT_VP8L, WebpCodec.FORMAT_VP8L);
            assertEquals(imagify.webp.ffm.WebpCodec.FORMAT_VP8X, WebpCodec.FORMAT_VP8X);
        }

        @Test
        @DisplayName("webp4j is given an effort it cannot use for a still image, and encodes anyway")
        void webp4jEncodesRatherThanRefusesAnEffortItCannotHonour() throws Exception {
            assumeTrue(allAvailable(), () -> "skipped: " + missing());
            // webp4j's still image entry point takes quality, lossless and threading and nothing
            // else, so it cannot be told to try harder or less hard. Refusing would make
            // ImageFormat.Webp.compressionMethod unusable on that backend, which is a worse answer
            // than a file that is a little larger than asked for and a line in the log.
            byte[] encoded = imagify.webp.webp4j.WebpCodec.encode(frame(0), 80, false, 0);

            assertEquals(WebpCodec.FORMAT_VP8,
                    imagify.webp.webp4j.WebpCodec.readHeader(encoded).format());
            assertEquals(WIDTH, imagify.webp.webp4j.WebpCodec.decode(encoded).getWidth());
            assertEquals(HEIGHT, imagify.webp.webp4j.WebpCodec.decode(encoded).getHeight());
        }
    }

    // ---------------------------------------------------------------------------------- helpers

    private static boolean allAvailable() {
        return Backend.FFM.isAvailable() && Backend.WEBP4J.isAvailable();
    }

    private static String missing() {
        if (!Backend.FFM.isAvailable()) {
            return "the FFM backend is not available (" + Backend.FFM.getUnavailableReason() + ")";
        }
        return "the webp4j backend is not available (" + Backend.WEBP4J.getUnavailableReason() + ")";
    }

    private static int distance(int one, int other) {
        return Math.abs(((one >> 16) & 0xFF) - ((other >> 16) & 0xFF))
                + Math.abs(((one >> 8) & 0xFF) - ((other >> 8) & 0xFF))
                + Math.abs((one & 0xFF) - (other & 0xFF));
    }

    private static byte[] lossy() throws Exception {
        return imagify.webp.ffm.WebpCodec.encode(frame(0), 80, false);
    }

    private static byte[] lossless() throws Exception {
        return imagify.webp.ffm.WebpCodec.encode(frame(0), 75, true);
    }

    private static byte[] animated() throws Exception {
        return imagify.webp.ffm.WebpCodec.encodeAnimation(frames(), delays(), 80, false, 0, 2);
    }

    private static int[] delays() {
        return new int[] { 80, 80, 80 };
    }

    private static List<BufferedImage> frames() {
        List<BufferedImage> frames = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            frames.add(frame(i));
        }
        return frames;
    }

    private static BufferedImage frame(int index) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                image.setRGB(x, y, (255 << 24)
                        | ((x * 3 + index * 40) & 0xFF) << 16
                        | ((y * 5) & 0xFF) << 8
                        | ((x + y + index * 30) & 0xFF));
            }
        }
        return image;
    }
}
