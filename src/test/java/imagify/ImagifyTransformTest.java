/*
 * Copyright (C) 2026 Nameless Production Committee
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests the transforms {@link Imagify} adds on top of {@link BufferedImageResize}: the aspect ratio
 * preserving resizes, the padding, and the crop, rotate and flip.
 *
 * <p>
 * Most of the tests run against a single frame, but a transform has to reach every frame of a
 * sequence while leaving its timing alone, and that is checked against a real animated GIF because
 * there is no other way to get a {@link FrameSequence} with more than one frame.
 */
class ImagifyTransformTest {

    // -------------------------------------------------------------------------------- fixture

    /** @return a {@code width} by {@code height} frame whose colour tells which frame it is */
    private static BufferedImage frame(int index, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color((index + 1) * 40, 10 * index, 200 - 30 * index));
            graphics.fillRect(0, 0, width, height);
            // A mark in a different corner per frame, so a transform that shifted the frames
            // relative to each other would show up as a misaligned result.
            graphics.setColor(Color.WHITE);
            graphics.fillRect(index * 3, index * 2, 3, 3);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static BufferedImage solid(int width, int height, int argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    /**
     * Writes an animated GIF, which is the one sequence format {@link Imagify#read(byte[])} can
     * always read back without any native library.
     *
     * <p>
     * The frame delays go in through the standard metadata as a {@code GraphicControlExtension},
     * which is the only public way to set them, and it is also exactly what
     * {@link imagify.ImageReader} looks for when it reads the animation back.
     */
    private static byte[] animatedGif(int frameCount, int width, int height, int[] delaysMs) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersBySuffix("gif").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setDestinationType(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (int index = 0; index < frameCount; index++) {
                BufferedImage frame = frame(index, width, height);
                IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(frame), param);
                applyDelay(metadata, delaysMs[index] / 10);
                writer.writeToSequence(new IIOImage(frame, null, metadata), param);
            }
            writer.endWriteSequence();
        }
        writer.dispose();
        return bytes.toByteArray();
    }

    /** Adds the graphic control extension that carries a GIF frame's delay, in centiseconds. */
    private static void applyDelay(IIOMetadata metadata, int centiseconds) throws IOException {
        String format = metadata.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
        IIOMetadataNode control = new IIOMetadataNode("GraphicControlExtension");
        control.setAttribute("disposalMethod", "none");
        control.setAttribute("userInputFlag", "FALSE");
        control.setAttribute("transparentColorFlag", "FALSE");
        control.setAttribute("delayTime", Integer.toString(centiseconds));
        control.setAttribute("transparentColorIndex", "0");
        root.appendChild(control);
        metadata.setFromTree(format, root);
    }

    private static Imagify single(int width, int height) {
        return Imagify.read(writePng(solid(width, height, 0xff3366cc)));
    }

    private static byte[] writePng(BufferedImage image) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    // ------------------------------------------------------------------------- resizing family

    @Nested
    @DisplayName("resizeToFit")
    class ResizeToFit {

        @Test
        @DisplayName("shrinks the longest edge to the given size")
        void shrinksTheLongestEdge() {
            BufferedImage wide = single(400, 100).resizeToFit(50).toBufferedImage();
            assertEquals(50, wide.getWidth());
            assertEquals(13, wide.getHeight(), "100 by 400 into 50 is 50 by 12.5, rounded");

            BufferedImage tall = single(100, 400).resizeToFit(50).toBufferedImage();
            assertEquals(13, tall.getWidth());
            assertEquals(50, tall.getHeight());
        }

        @Test
        @DisplayName("scales a small image up, the way the standalone helper does")
        void scalesUp() {
            BufferedImage grown = single(20, 10).resizeToFit(100).toBufferedImage();
            assertEquals(100, grown.getWidth());
            assertEquals(50, grown.getHeight());
        }

        @Test
        @DisplayName("refuses a size that is not positive")
        void refusesNonPositive() {
            assertThrows(IllegalArgumentException.class, () -> single(10, 10).resizeToFit(0));
            assertThrows(IllegalArgumentException.class, () -> single(10, 10).resizeToFit(-1));
        }
    }

    @Nested
    @DisplayName("resizeInside")
    class ResizeInside {

        @Test
        @DisplayName("fits inside the box and stays smaller on at least one edge")
        void fitsInside() {
            BufferedImage fitted = single(400, 100).resizeInside(100, 100).toBufferedImage();
            assertEquals(100, fitted.getWidth());
            assertEquals(25, fitted.getHeight());
            assertTrue(fitted.getWidth() <= 100 && fitted.getHeight() <= 100);
        }

        @Test
        @DisplayName("is bound by the box in whichever direction is tighter")
        void boundByTheTighterSide() {
            BufferedImage fitted = single(100, 400).resizeInside(100, 100).toBufferedImage();
            assertEquals(25, fitted.getWidth());
            assertEquals(100, fitted.getHeight());
        }

        @Test
        @DisplayName("leaves an image that already fits alone instead of scaling it up")
        void leavesSmallImagesAlone() {
            BufferedImage unchanged = single(30, 20).resizeInside(100, 100).toBufferedImage();
            assertEquals(30, unchanged.getWidth());
            assertEquals(20, unchanged.getHeight());
        }

        @Test
        @DisplayName("refuses a box that is not positive")
        void refusesNonPositive() {
            assertThrows(IllegalArgumentException.class, () -> single(10, 10).resizeInside(0, 5));
            assertThrows(IllegalArgumentException.class, () -> single(10, 10).resizeInside(5, -1));
        }
    }

    @Nested
    @DisplayName("resizeToFill")
    class ResizeToFill {

        @Test
        @DisplayName("is exactly the requested size, cropping what does not fit")
        void isExactlyTheRequestedSize() {
            BufferedImage filled = single(400, 100).resizeToFill(100, 100).toBufferedImage();
            assertEquals(100, filled.getWidth());
            assertEquals(100, filled.getHeight());
        }

        @Test
        @DisplayName("keeps the middle of the picture rather than a corner")
        void keepsTheMiddle() {
            // A 400 by 100 image with a 40 by 20 mark at its centre, boxed down to 100 by 100: the
            // mark has to survive at the middle of the result, which a top left crop would lose.
            BufferedImage source = new BufferedImage(400, 100, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = source.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(180, 40, 40, 20);
            graphics.dispose();

            BufferedImage filled = Imagify.read(writePng(source)).resizeToFill(100, 100).toBufferedImage();
            assertEquals(0xffffffff, filled.getRGB(50, 50) & 0xffffffff, "the centre mark was cropped away");
        }

        @Test
        @DisplayName("keeps the corners of a square picture, since nothing has to go")
        void keepsEverythingWhenTheShapeMatches() {
            BufferedImage filled = single(100, 100).resizeToFill(60, 60).toBufferedImage();
            assertEquals(60, filled.getWidth());
            assertEquals(60, filled.getHeight());
            assertEquals(0xff3366cc, filled.getRGB(0, 0));
            assertEquals(0xff3366cc, filled.getRGB(59, 59));
        }

        @Test
        @DisplayName("refuses a size that is not positive")
        void refusesNonPositive() {
            assertThrows(IllegalArgumentException.class, () -> single(10, 10).resizeToFill(0, 5));
            assertThrows(IllegalArgumentException.class, () -> single(10, 10).resizeToFill(5, 0));
        }
    }

    @Nested
    @DisplayName("padTo")
    class PadTo {

        @Test
        @DisplayName("makes a canvas of exactly the requested size")
        void makesTheRequestedCanvas() {
            BufferedImage padded = single(400, 100).padTo(500, 200).toBufferedImage();
            assertEquals(500, padded.getWidth());
            assertEquals(200, padded.getHeight());
        }

        @Test
        @DisplayName("centres the picture and leaves the rest transparent")
        void centresAndLeavesTransparent() {
            BufferedImage padded = single(400, 100).padTo(500, 200).toBufferedImage();
            // The picture sits at (50, 50), so the centre is picture and the corner is padding.
            assertEquals(0xff3366cc, padded.getRGB(250, 100), "the centre should be the picture");
            assertEquals(0, padded.getRGB(0, 0) >>> 24, "the corner should be transparent");
            assertEquals(0, padded.getRGB(499, 199) >>> 24, "the far corner should be transparent");
        }

        @Test
        @DisplayName("fills the padding with the colour it is given")
        void fillsWithTheGivenColour() {
            BufferedImage padded = single(400, 100).padTo(500, 200, Color.WHITE).toBufferedImage();
            assertEquals(0xffffffff, padded.getRGB(0, 0) & 0xffffffff);
            assertEquals(0xffffffff, padded.getRGB(499, 199) & 0xffffffff);
            assertEquals(0xff3366cc, padded.getRGB(250, 100));
        }

        @Test
        @DisplayName("refuses a size that is not positive")
        void refusesNonPositive() {
            assertThrows(IllegalArgumentException.class, () -> single(10, 10).padTo(0, 5));
            assertThrows(IllegalArgumentException.class, () -> single(10, 10).padTo(5, 0));
        }
    }

    // -------------------------------------------------------------- crop, rotate and flip, fluent

    @Nested
    @DisplayName("crop, rotate and flip")
    class Rearranging {

        @Test
        @DisplayName("crop takes the same rectangle out of every frame")
        void cropEveryFrame() throws IOException {
            Imagify image = Imagify.read(animatedGif(3, 40, 20, new int[] {100, 200, 300})).crop(10, 5, 20, 8);
            assertEquals(3, image.frameCount());
            for (BufferedImage frame : image.get().frames()) {
                assertEquals(20, frame.getWidth());
                assertEquals(8, frame.getHeight());
            }
        }

        @Test
        @DisplayName("rotate turns every frame and swaps the axes on a right angle")
        void rotateEveryFrame() throws IOException {
            Imagify image = Imagify.read(animatedGif(3, 40, 20, new int[] {100, 200, 300})).rotate(90);
            for (BufferedImage frame : image.get().frames()) {
                assertEquals(20, frame.getWidth());
                assertEquals(40, frame.getHeight());
            }
        }

        @Test
        @DisplayName("flip leaves the size alone and mirrors the pixels")
        void flipEveryFrame() throws IOException {
            Imagify image = Imagify.read(animatedGif(2, 40, 20, new int[] {100, 200})).flipHorizontal();
            assertEquals(2, image.frameCount());
            for (BufferedImage frame : image.get().frames()) {
                assertEquals(40, frame.getWidth());
                assertEquals(20, frame.getHeight());
            }
        }

        @Test
        @DisplayName("a transform leaves the timing of the sequence alone")
        void keepsTheTiming() throws IOException {
            int[] delays = {100, 200, 300};
            FrameSequence cropped = Imagify.read(animatedGif(3, 40, 20, delays)).crop(0, 0, 10, 10).get();
            assertArrayEquals(delays, cropped.delaysMs());
            assertEquals(0, cropped.loopCount());

            // Chaining must not disturb the timing either.
            FrameSequence chain = Imagify.read(animatedGif(3, 40, 20, delays)).rotate(90).flipVertical().padTo(60, 60, Color.BLACK).get();
            assertArrayEquals(delays, chain.delaysMs());
            assertEquals(3, chain.frameCount());
        }

        @Test
        @DisplayName("a transform returns the same object, so calls can be chained")
        void chains() {
            Imagify image = single(40, 20);
            assertSame(image, image.crop(0, 0, 30, 10));
            assertSame(image, image.rotate(90));
            assertSame(image, image.flipHorizontal());
            assertSame(image, image.resizeToFit(10));
        }
    }

    // ------------------------------------------------------------------------------ composition

    @Test
    @DisplayName("transforms can be chained into a pipeline that writes a file")
    void chainedIntoAWrite(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws IOException {
        java.nio.file.Path out = directory.resolve("chained.png");
        Imagify.read(writePng(solid(400, 100, 0xff3366cc)))
                .crop(50, 0, 200, 100)
                .rotate(90)
                .resizeToFill(80, 40)
                .padTo(100, 100, Color.WHITE)
                .flipVertical()
                .writeTo(out);
        assertTrue(java.nio.file.Files.size(out) > 0);
        BufferedImage written = Imagify.read(out).toBufferedImage();
        assertEquals(100, written.getWidth());
        assertEquals(100, written.getHeight());
    }

    @Test
    @DisplayName("resizing a sequence keeps every frame the same size")
    void resizingASequenceKeepsFramesAligned() throws IOException {
        Imagify image = Imagify.read(animatedGif(4, 40, 20, new int[] {50, 50, 50, 50})).resizeToFill(16, 16);
        int width = image.toBufferedImage().getWidth();
        int height = image.toBufferedImage().getHeight();
        for (BufferedImage frame : image.get().frames()) {
            assertEquals(width, frame.getWidth(), "the frames drifted apart in width");
            assertEquals(height, frame.getHeight(), "the frames drifted apart in height");
        }
    }
}
