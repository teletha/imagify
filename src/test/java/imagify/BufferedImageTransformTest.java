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

import java.awt.Point;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link BufferedImageTransform}: cropping, rotating and flipping.
 *
 * <p>The fixtures give every pixel of the source a colour of its own, so a transform that quietly
 * interpolated or shifted by half a pixel would move those colours somewhere else and show up as a
 * mismatch. The right angles are checked pixel for pixel, which is only possible because they are
 * supposed to be lossless.
 */
class BufferedImageTransformTest {

    // ------------------------------------------------------------------------------- fixture

    /**
     * @return a {@code width} by {@code height} image in which no two pixels share a colour, so that
     *         any transform that does not map each pixel to itself exactly is visible
     */
    private static BufferedImage labelled(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xff000000 | (x << 16) | (y << 8) | ((x * 3 + y * 5) & 0xff));
            }
        }
        return image;
    }

    /** Fails unless every pixel of {@code actual} is the pixel of {@code expected} at that position. */
    private static void assertSamePixels(BufferedImage expected, BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth(), "width");
        assertEquals(expected.getHeight(), actual.getHeight(), "height");
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                assertEquals(expected.getRGB(x, y), actual.getRGB(x, y),
                        "pixel " + x + "," + y);
            }
        }
    }

    // ---------------------------------------------------------------------------------- crop

    @Nested
    @DisplayName("crop")
    class Crop {

        @Test
        @DisplayName("takes out the requested region and nothing else")
        void takesTheRegion() {
            BufferedImage source = labelled(6, 4);
            BufferedImage cropped = BufferedImageTransform.crop(source, 2, 1, 3, 2);
            assertEquals(3, cropped.getWidth());
            assertEquals(2, cropped.getHeight());
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 3; x++) {
                    assertEquals(source.getRGB(2 + x, 1 + y), cropped.getRGB(x, y), "pixel " + x + "," + y);
                }
            }
        }

        @Test
        @DisplayName("copies the region instead of viewing it, so the parent is not kept alive")
        void copiesRatherThanViews() {
            BufferedImage source = labelled(6, 4);
            BufferedImage cropped = BufferedImageTransform.crop(source, 0, 0, 2, 2);
            // getSubimage() would hand back a view that shares the parent raster and reports a
            // TYPE_CUSTOM image, neither of which an encoder here is willing to take.
            assertEquals(BufferedImage.TYPE_INT_ARGB, cropped.getType());
            assertNotSame(source.getRaster(), cropped.getRaster());
            // Writing through the copy must leave the source alone.
            cropped.setRGB(0, 0, 0xff00ff00);
            assertNotEquals(0xff00ff00, source.getRGB(0, 0));
        }

        @Test
        @DisplayName("keeps a partly transparent pixel at exactly its own alpha")
        void keepsAlpha() {
            BufferedImage source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
            source.setRGB(0, 0, 0x80ff0000);
            source.setRGB(1, 0, 0x00ffffff);
            BufferedImage cropped = BufferedImageTransform.crop(source, 0, 0, 2, 1);
            // Blending instead of replacing would send the half transparent pixel through
            // premultiplied space and round it, which is what this pins down.
            assertEquals(0x80ff0000, cropped.getRGB(0, 0));
            assertEquals(0x00ffffff, cropped.getRGB(1, 0));
        }

        @Test
        @DisplayName("accepts an image whose type has no name, such as a decoded AVIF frame")
        void acceptsCustomType() {
            // A frame that came out of a native decoder is a BufferedImage.TYPE_CUSTOM, which the
            // int type constants cannot name, so this stands in for one.
            ComponentColorModel model = new ComponentColorModel(
                    ColorSpace.getInstance(ColorSpace.CS_sRGB), true, false,
                    Transparency.TRANSLUCENT, DataBuffer.TYPE_BYTE);
            BufferedImage custom = new BufferedImage(model,
                    Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, 2, 2, 4, new Point(0, 0)),
                    false, null);
            assertEquals(BufferedImage.TYPE_CUSTOM, custom.getType());
            custom.setRGB(0, 0, 0xffff0000);
            custom.setRGB(1, 1, 0xff00ff00);

            BufferedImage cropped = BufferedImageTransform.crop(custom, 1, 1, 1, 1);
            assertEquals(1, cropped.getWidth());
            assertEquals(0xff00ff00, cropped.getRGB(0, 0));
            assertEquals(BufferedImage.TYPE_INT_ARGB, cropped.getType());
        }

        @Test
        @DisplayName("refuses a region that is empty or reaches outside the image")
        void refusesImpossibleRegions() {
            BufferedImage source = labelled(6, 4);
            assertThrows(IllegalArgumentException.class,
                    () -> BufferedImageTransform.crop(source, 0, 0, 0, 2), "zero width");
            assertThrows(IllegalArgumentException.class,
                    () -> BufferedImageTransform.crop(source, 0, 0, 2, -1), "negative height");
            assertThrows(IllegalArgumentException.class,
                    () -> BufferedImageTransform.crop(source, -1, 0, 2, 2), "negative x");
            assertThrows(IllegalArgumentException.class,
                    () -> BufferedImageTransform.crop(source, 5, 0, 2, 2), "past the right edge");
            assertThrows(IllegalArgumentException.class,
                    () -> BufferedImageTransform.crop(source, 0, 3, 2, 2), "past the bottom edge");
            assertThrows(IllegalArgumentException.class,
                    () -> BufferedImageTransform.crop(null, 0, 0, 1, 1), "no image");
        }
    }

    // -------------------------------------------------------------------------------- rotate

    @Nested
    @DisplayName("rotate")
    class Rotate {

        @Test
        @DisplayName("by a right angle moves whole pixels, so nothing is lost")
        void rightAnglesAreLossless() {
            // A 4 by 3 image, so that a wrong transpose cannot hide behind a square.
            BufferedImage source = labelled(4, 3);
            BufferedImage quarter = BufferedImageTransform.rotate(source, 90);
            BufferedImage half = BufferedImageTransform.rotate(source, 180);
            BufferedImage three = BufferedImageTransform.rotate(source, 270);

            // 90 degrees clockwise: the left column becomes the top row.
            assertEquals(3, quarter.getWidth());
            assertEquals(4, quarter.getHeight());
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 3; x++) {
                    assertEquals(source.getRGB(y, 2 - x), quarter.getRGB(x, y), "90 degrees at " + x + "," + y);
                }
            }
            // 180 degrees: everything turns back on itself.
            assertEquals(4, half.getWidth());
            assertEquals(3, half.getHeight());
            for (int y = 0; y < 3; y++) {
                for (int x = 0; x < 4; x++) {
                    assertEquals(source.getRGB(3 - x, 2 - y), half.getRGB(x, y), "180 degrees at " + x + "," + y);
                }
            }
            // 270 degrees clockwise is 90 degrees the other way.
            assertEquals(3, three.getWidth());
            assertEquals(4, three.getHeight());
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 3; x++) {
                    assertEquals(source.getRGB(3 - y, x), three.getRGB(x, y), "270 degrees at " + x + "," + y);
                }
            }
        }

        @Test
        @DisplayName("by no angle at all changes nothing")
        void zeroIsIdentity() {
            BufferedImage source = labelled(4, 3);
            assertSamePixels(source, BufferedImageTransform.rotate(source, 0));
            // A full turn is the same as none, whichever way the multiples are worked out.
            assertSamePixels(source, BufferedImageTransform.rotate(source, 360));
            assertSamePixels(source, BufferedImageTransform.rotate(source, -360));
        }

        @Test
        @DisplayName("four quarter turns land back on the original")
        void fourQuarterTurnsAreIdentity() {
            BufferedImage source = labelled(5, 3);
            BufferedImage turned = source;
            for (int i = 0; i < 4; i++) {
                turned = BufferedImageTransform.rotate(turned, 90);
            }
            assertSamePixels(source, turned);
        }

        @Test
        @DisplayName("by an angle that is nearly, but not exactly, a right angle is resampled")
        void nearlyRightAnglesAreResampled() {
            BufferedImage source = labelled(4, 3);
            // 89.9999 degrees cannot be done by moving pixels, so it keeps the canvas and interpolates.
            BufferedImage turned = BufferedImageTransform.rotate(source, 89.9999);
            assertEquals(4, turned.getWidth());
            assertEquals(3, turned.getHeight());
        }

        @Test
        @DisplayName("by an arbitrary angle keeps the canvas and clips the corners")
        void arbitraryAnglesKeepTheCanvas() {
            BufferedImage source = labelled(8, 6);
            for (double degrees : new double[] { 45, 30, -45, 12.5, 180.5 }) {
                BufferedImage turned = BufferedImageTransform.rotate(source, degrees);
                assertEquals(8, turned.getWidth(), "width at " + degrees);
                assertEquals(6, turned.getHeight(), "height at " + degrees);
            }
        }

        @Test
        @DisplayName("by an arbitrary angle really does resample")
        void arbitraryAnglesInterpolate() {
            BufferedImage source = labelled(9, 9);
            BufferedImage turned = BufferedImageTransform.rotate(source, 45);
            boolean anyChange = false;
            for (int y = 0; y < 9 && !anyChange; y++) {
                for (int x = 0; x < 9; x++) {
                    if (source.getRGB(x, y) != turned.getRGB(x, y)) {
                        anyChange = true;
                        break;
                    }
                }
            }
            assertTrue(anyChange, "rotating by 45 degrees left every pixel where it was");
        }

        @Test
        @DisplayName("resamples with the requested algorithm where Java2D has one")
        void honoursTheAlgorithm() {
            BufferedImage source = labelled(16, 16);
            BufferedImage nearest = BufferedImageTransform.rotate(source, 45, ResizeAlgorithm.NEAREST);
            BufferedImage bilinear = BufferedImageTransform.rotate(source, 45, ResizeAlgorithm.BILINEAR);
            assertNotSame(nearest, bilinear);
            // Nearest neighbour copies whole pixels, so where the source covers the canvas the output
            // is always opaque. The corners of a turned image are not covered, and stay as the
            // cleared canvas, so only the middle is checked.
            for (int y = 5; y < 11; y++) {
                for (int x = 5; x < 11; x++) {
                    assertEquals(0xff, nearest.getRGB(x, y) >>> 24,
                            "nearest invented a new colour at " + x + "," + y);
                }
            }
            assertEquals(0, nearest.getRGB(0, 0) >>> 24, "the uncovered corner should be transparent");
            // An algorithm Java2D cannot express still resamples rather than refusing to work.
            assertDoesNotThrow(() -> BufferedImageTransform.rotate(source, 45, ResizeAlgorithm.LANCZOS3));
        }

        @Test
        @DisplayName("refuses an angle that is not a finite number")
        void refusesNonFiniteAngles() {
            BufferedImage source = labelled(4, 3);
            assertThrows(IllegalArgumentException.class, () -> BufferedImageTransform.rotate(source, Double.NaN));
            assertThrows(IllegalArgumentException.class,
                    () -> BufferedImageTransform.rotate(source, Double.POSITIVE_INFINITY));
            assertThrows(IllegalArgumentException.class, () -> BufferedImageTransform.rotate(null, 90));
            assertThrows(IllegalArgumentException.class,
                    () -> BufferedImageTransform.rotate(source, 45, null));
        }
    }

    // ---------------------------------------------------------------------------------- flip

    @Nested
    @DisplayName("flip")
    class Flip {

        @Test
        @DisplayName("horizontally mirrors each row")
        void horizontal() {
            BufferedImage source = labelled(4, 3);
            BufferedImage flipped = BufferedImageTransform.flipHorizontal(source);
            assertEquals(4, flipped.getWidth());
            assertEquals(3, flipped.getHeight());
            for (int y = 0; y < 3; y++) {
                for (int x = 0; x < 4; x++) {
                    assertEquals(source.getRGB(3 - x, y), flipped.getRGB(x, y), "pixel " + x + "," + y);
                }
            }
        }

        @Test
        @DisplayName("vertically mirrors each column")
        void vertical() {
            BufferedImage source = labelled(4, 3);
            BufferedImage flipped = BufferedImageTransform.flipVertical(source);
            for (int y = 0; y < 3; y++) {
                for (int x = 0; x < 4; x++) {
                    assertEquals(source.getRGB(x, 2 - y), flipped.getRGB(x, y), "pixel " + x + "," + y);
                }
            }
        }

        @Test
        @DisplayName("twice is the image it started from")
        void twiceIsIdentity() {
            BufferedImage source = labelled(4, 3);
            assertSamePixels(source, BufferedImageTransform.flipHorizontal(
                    BufferedImageTransform.flipHorizontal(source)));
            assertSamePixels(source, BufferedImageTransform.flipVertical(
                    BufferedImageTransform.flipVertical(source)));
        }

        @Test
        @DisplayName("both ways at once is the same as a half turn")
        void bothWaysIsAHalfTurn() {
            BufferedImage source = labelled(4, 3);
            assertSamePixels(BufferedImageTransform.rotate(source, 180),
                    BufferedImageTransform.flipVertical(BufferedImageTransform.flipHorizontal(source)));
        }

        @Test
        @DisplayName("keeps a partly transparent pixel at exactly its own alpha")
        void keepsAlpha() {
            BufferedImage source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
            source.setRGB(0, 0, 0x80ff0000);
            source.setRGB(1, 0, 0x00ffffff);
            BufferedImage flipped = BufferedImageTransform.flipHorizontal(source);
            assertEquals(0x80ff0000, flipped.getRGB(1, 0));
            // The colour of a fully transparent pixel does not survive the trip through premultiplied
            // space, which is the one thing Java2D cannot be asked to keep, and nobody can see it
            // anyway. That it is still fully transparent is what matters.
            assertEquals(0, flipped.getRGB(0, 0) >>> 24);
        }

        @Test
        @DisplayName("refuses to work on nothing")
        void refusesNothing() {
            assertThrows(IllegalArgumentException.class, () -> BufferedImageTransform.flipHorizontal(null));
            assertThrows(IllegalArgumentException.class, () -> BufferedImageTransform.flipVertical(null));
        }
    }
}
