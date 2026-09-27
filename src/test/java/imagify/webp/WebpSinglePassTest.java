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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that a WebP file is walked once, not once per question asked of it.
 *
 * <p>An animated WebP used to be decoded in full for every single frame a caller asked for, so
 * reading an animation of n frames walked the file n times. Nothing about the result was wrong,
 * which is why no ordinary test noticed: it was only slow, quadratically so. The one pass that
 * produces the frames is therefore kept, and the property worth pinning is that the frames handed
 * out still are the frames that one pass produced.
 */
class WebpSinglePassTest {

    /** The real 58 frame animation in the test resources, which is what the cost was measured on. */
    private static final Path ANIME = Path.of("src/test/resources", "anime webp", "220354.webp");

    /**
     * How many times the cost of one frame reading may be paid to read a whole animation. The point
     * of the test is the gap between walking the file once and walking it per frame, which is large
     * enough that an ordinary machine being busy cannot close it: reading all frames costs a few
     * times one frame, and cost a frame per frame before.
     */
    private static final int ALLOWED_MULTIPLE_OF_ONE_FRAME = 20;

    @BeforeAll
    static void requireLibwebp() {
        assumeTrue(WebpCodec.isAvailable(),
                () -> "skipped: libwebp is not available (" + WebpCodec.getUnavailableReason() + ")");
    }

    // ------------------------------------------------------------------------------- the pass

    @Test
    @DisplayName("decodeFile hands back the properties, the frames and the timing together")
    void decodeFileIsSelfConsistent() throws Exception {
        byte[] encoded = anime();
        WebpCodec.DecodedWebp decoded = WebpCodec.decodeFile(encoded);

        assertEquals(58, decoded.frames().size(), "frame count");
        assertEquals(decoded.frames().size(), decoded.info().frameCount(),
                "the properties should agree with the frames they came with");
        assertEquals(58, decoded.delaysMs().length, "one duration per frame");
        for (BufferedImage frame : decoded.frames()) {
            assertEquals(498, frame.getWidth());
            assertEquals(280, frame.getHeight());
        }
    }

    @Test
    @DisplayName("decodeFile agrees with readHeader, which is the same pass for a still image")
    void decodeFileAgreesWithReadHeader() throws Exception {
        assertEquals(WebpCodec.readHeader(anime()), WebpCodec.decodeFile(anime()).info());
    }

    @Test
    @DisplayName("decodeFile still leaves a still image without a timing, because it has none")
    void aStillHasNoTiming() throws Exception {
        WebpCodec.DecodedWebp decoded = WebpCodec.decodeFile(WebpCodec.encode(frame(0), 80, false));
        assertEquals(1, decoded.frames().size());
        assertNull(decoded.delaysMs(), "a still image is not a one frame animation");
        assertFalse(decoded.info().hasAnimation());
    }

    // ------------------------------------------------------------------- the frames handed out

    @Test
    @DisplayName("every frame the reader hands out is the one the single pass produced")
    void everyFrameComesFromTheOnePass() throws Exception {
        byte[] encoded = anime();
        List<BufferedImage> expected = WebpCodec.decodeFile(encoded).frames();

        WebpImageReader reader = reader(encoded);
        assertEquals(expected.size(), reader.getNumImages(true), "frame count");
        for (int index = 0; index < expected.size(); index++) {
            BufferedImage wanted = expected.get(index);
            BufferedImage got = reader.read(index);
            assertEquals(wanted.getWidth(), got.getWidth(), "frame " + index + " width");
            assertEquals(wanted.getHeight(), got.getHeight(), "frame " + index + " height");
            // Lossy, so the frames are compared as whole images rather than pixel by pixel: a
            // different code path cannot reproduce the same compressed frame's bytes exactly.
            assertArrayEquals(pixels(wanted), pixels(got), "frame " + index + " differs");
        }
    }

    @Test
    @DisplayName("the frames are the real, different frames, not one frame handed out n times")
    void theFramesAreDistinct() throws Exception {
        WebpImageReader reader = reader(anime());
        Set<Integer> seen = new HashSet<>();
        for (int index = 0; index < reader.getNumImages(true); index++) {
            seen.add(java.util.Arrays.hashCode(pixels(reader.read(index))));
        }
        assertTrue(seen.size() > 1,
                "every frame hashed the same, so the reader is serving one frame over and over");
    }

    @Test
    @DisplayName("the order frames are asked for in does not change which frame comes back")
    void theOrderFramesAreAskedForDoesNotMatter() throws Exception {
        byte[] encoded = anime();
        int last = WebpCodec.decodeFile(encoded).frames().size() - 1;

        WebpImageReader forward = reader(encoded);
        int[] firstInOrder = pixels(forward.read(0));
        int[] lastInOrder = pixels(forward.read(last));

        WebpImageReader backward = reader(encoded);
        int[] lastOutOfOrder = pixels(backward.read(last));
        int[] firstAfterwards = pixels(backward.read(0));

        assertArrayEquals(firstInOrder, firstAfterwards,
                "frame 0 changed because frame " + last + " was read first");
        assertArrayEquals(lastInOrder, lastOutOfOrder,
                "frame " + last + " changed because frame 0 was read first");
    }

    // -------------------------------------------------------------------------- the cost of it

    @Test
    @DisplayName("reading every frame does not cost one whole file walk per frame")
    void readingEveryFrameIsNotQuadratic() throws Exception {
        byte[] encoded = anime();
        // Both measurements are warmed up first, so neither is charged for class loading or for the
        // interpreter that the first run through a new path always pays.
        readEveryFrame(encoded);
        readOneFrame(encoded, 0);

        long oneFrame = Math.max(1, millis(() -> readOneFrame(encoded, 0)));
        long everyFrame = millis(() -> readEveryFrame(encoded));

        assertTrue(everyFrame < oneFrame * ALLOWED_MULTIPLE_OF_ONE_FRAME, () -> "reading all "
                + 58 + " frames took " + everyFrame + " ms against " + oneFrame
                + " ms for a single frame, so the file is still being walked per frame");
    }

    // ---------------------------------------------------------------------------------- helpers

    private static byte[] anime() throws IOException {
        assumeTrue(Files.isRegularFile(ANIME), "skipped: the test animation is not in the resources");
        return Files.readAllBytes(ANIME);
    }

    private static WebpImageReader reader(byte[] encoded) throws IOException {
        WebpImageReader reader = new WebpImageReader(new WebpImageReaderSpi());
        reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(encoded)));
        return reader;
    }

    private static BufferedImage frame(int index) {
        BufferedImage image = new BufferedImage(16, 12, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 12; y++)
            for (int x = 0; x < 16; x++)
                image.setRGB(x, y, (10 + 8 * index) << 24 | 0x20 << 16 | 0x30 << 8 | 0x40);
        return image;
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    private static void readOneFrame(byte[] encoded, int index) {
        quietly(() -> reader(encoded).read(index));
    }

    private static void readEveryFrame(byte[] encoded) {
        quietly(() -> {
            WebpImageReader reader = reader(encoded);
            int checksum = 0;
            for (int i = 0; i < reader.getNumImages(true); i++) {
                checksum ^= pixels(reader.read(i))[0];
            }
            assertNotEquals(0, checksum, "the frames should not all be blank");
        });
    }

    private static void quietly(IOAction action) {
        try {
            action.run();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static long millis(Runnable action) {
        long from = System.nanoTime();
        action.run();
        return (System.nanoTime() - from) / 1_000_000L;
    }

    @FunctionalInterface
    private interface IOAction {
        void run() throws IOException;
    }
}
