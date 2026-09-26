/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.avif.jna;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.util.Vector;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the conversion between {@code BufferedImage} and the A, B, G, R byte order {@code libavif}
 * uses for {@code AVIF_RGB_FORMAT_ABGR}.
 *
 * <p>These are the only tests that run on every machine: they need no native library, and they
 * cover the one place where this library could silently swap a colour channel.
 */
class AbgrPixelsTest {

    @Test
    @DisplayName("a TYPE_4BYTE_ABGR image is copied without touching a single byte")
    void abgrIsCopiedVerbatim() {
        // A, B, G, R per pixel in memory, which is what getRGB() has to be read back through in
        // reverse. Two distinct colours so a channel swap cannot pass.
        byte[] bytes = {
                (byte) 0x11, 0x22, 0x33, 0x44,
                (byte) 0xff, (byte) 0xbb, (byte) 0xaa, (byte) 0x99
        };
        BufferedImage image = AbgrPixels.toBufferedImage(bytes, 2, 1);
        assertEquals(BufferedImage.TYPE_4BYTE_ABGR, image.getType());
        assertEquals(0x11_443322, image.getRGB(0, 0));
        assertEquals(0xff_99aabb, image.getRGB(1, 0));
        assertArrayEquals(bytes, AbgrPixels.toAbgrBytes(image, 0, 0, 2, 1, 1, 1));
    }

    @Test
    @DisplayName("a TYPE_4BYTE_ABGR image round trips through the byte conversion unchanged")
    void abgrRoundTrip() {
        BufferedImage source = new BufferedImage(7, 5, BufferedImage.TYPE_4BYTE_ABGR);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                source.setRGB(x, y, 0x20304050 + x * 0x010101 + y * 0x0101);
            }
        }
        byte[] bytes = AbgrPixels.toAbgrBytes(source, 0, 0, source.getWidth(), source.getHeight(), 1, 1);
        BufferedImage copy = AbgrPixels.toBufferedImage(bytes, source.getWidth(), source.getHeight());
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                assertEquals(source.getRGB(x, y), copy.getRGB(x, y), "pixel " + x + "," + y);
            }
        }
    }

    @Test
    @DisplayName("the in-memory order is the reverse of the getRGB() integer order")
    void channelOrder() {
        // A TYPE_4BYTE_ABGR raster declares band offsets of 3, 2, 1, 0, so its banks are A, B, G, R
        // while getDataElements() reports the same sample as R, G, B, A. Writing the 32 bit integer
        // through in reverse is the mistake this pins down.
        BufferedImage source = new BufferedImage(1, 1, BufferedImage.TYPE_4BYTE_ABGR);
        source.setRGB(0, 0, 0x11223344);
        assertArrayEquals(new byte[] { 0x11, 0x44, 0x33, 0x22 },
                AbgrPixels.toAbgrBytes(source, 0, 0, 1, 1, 1, 1));
    }

    @Test
    @DisplayName("a packed int image is converted, which un-premultiplies")
    void intArgb() {
        BufferedImage source = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0x80ff0000);
        byte[] bytes = AbgrPixels.toAbgrBytes(source, 0, 0, 1, 1, 1, 1);
        assertArrayEquals(new byte[] { (byte) 0x80, 0x00, 0x00, (byte) 0xff }, bytes);

        BufferedImage copy = AbgrPixels.toBufferedImage(bytes, 1, 1);
        assertEquals(0x80ff0000, copy.getRGB(0, 0));
        assertFalse(copy.getColorModel().isAlphaPremultiplied());
    }

    @Test
    @DisplayName("a premultiplied image keeps its un-premultiplied colour")
    void premultiplied() {
        BufferedImage source = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB_PRE);
        // Fully opaque red is the only value that survives premultiplication without rounding.
        source.setRGB(0, 0, 0xffff0000);
        byte[] bytes = AbgrPixels.toAbgrBytes(source, 0, 0, 1, 1, 1, 1);
        assertArrayEquals(new byte[] { (byte) 0xff, 0x00, 0x00, (byte) 0xff }, bytes);

        BufferedImage converted = AbgrPixels.toAbgr(source);
        assertEquals(BufferedImage.TYPE_4BYTE_ABGR, converted.getType());
        assertFalse(converted.getColorModel().isAlphaPremultiplied());
        assertEquals(0xffff0000, converted.getRGB(0, 0));
    }

    @Test
    @DisplayName("an already converted image is returned as is")
    void toAbgrIsIdempotent() {
        BufferedImage source = new BufferedImage(3, 2, BufferedImage.TYPE_4BYTE_ABGR);
        assertSame(source, AbgrPixels.toAbgr(source));
    }

    @Test
    @DisplayName("a RenderedImage that is not a BufferedImage goes through its color model")
    void renderedImage() {
        // Wrapping hides the BufferedImage type, which forces the generic path. Reading the raster
        // directly would return band samples here instead of ARGB, in R, G, B, A order at that.
        BufferedImage content = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        content.setRGB(0, 0, 0x11223344);
        content.setRGB(1, 0, 0xff00ff00);

        RenderedImage source = wrap(content);
        assertArrayEquals(new byte[] { 0x11, 0x44, 0x33, 0x22, (byte) 0xff, 0x00, (byte) 0xff, 0x00 },
                AbgrPixels.toAbgrBytes(source, 0, 0, 2, 1, 1, 1));
        assertEquals(BufferedImage.TYPE_4BYTE_ABGR, AbgrPixels.toAbgr(source).getType());
    }

    @Test
    @DisplayName("a RenderedImage whose origin is not zero is read at the right offset")
    void translatedRenderedImage() {
        // Every pixel carries a distinct red value, so a one pixel slip shows up immediately.
        BufferedImage content = new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 4; x++) {
                content.setRGB(x, y, 0xff000000 | (x + 4 * y) * 0x00010000);
            }
        }
        RenderedImage source = wrap(content, 2, 1);
        assertEquals(2, source.getMinX());
        assertEquals(1, source.getMinY());
        // The origin relabels the raster, so the wrapper's (2, 1) is the content's (0, 0) and the
        // wrapper's (4, 1) is the content's (2, 0). Both would read the wrong pixels if the origin
        // were dropped, because every pixel carries a distinct red value.
        assertArrayEquals(
                AbgrPixels.toAbgrBytes(content, 0, 0, 2, 1, 1, 1),
                AbgrPixels.toAbgrBytes(source, 2, 1, 2, 1, 1, 1));
        assertArrayEquals(
                AbgrPixels.toAbgrBytes(content, 2, 0, 2, 1, 1, 1),
                AbgrPixels.toAbgrBytes(source, 4, 1, 2, 1, 1, 1));
    }

    @Test
    @DisplayName("a source region is copied row by row")
    void region() {
        BufferedImage source = new BufferedImage(4, 4, BufferedImage.TYPE_4BYTE_ABGR);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                source.setRGB(x, y, 0xff000000 | x * 0x00010000 | y * 0x00000100);
            }
        }
        BufferedImage region = AbgrPixels.toBufferedImage(
                AbgrPixels.toAbgrBytes(source, 1, 2, 2, 2, 1, 1), 2, 2);
        assertEquals(source.getRGB(1, 2), region.getRGB(0, 0));
        assertEquals(source.getRGB(2, 2), region.getRGB(1, 0));
        assertEquals(source.getRGB(1, 3), region.getRGB(0, 1));
        assertEquals(source.getRGB(2, 3), region.getRGB(1, 1));
    }

    @Test
    @DisplayName("sub sampling takes the top left pixel of each block, as ImageIO specifies")
    void subSampling() {
        BufferedImage source = new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 4; x++) {
                source.setRGB(x, y, 0xff000000 | (x + y * 4) * 0x00010000);
            }
        }
        BufferedImage sampled = AbgrPixels.toBufferedImage(
                AbgrPixels.toAbgrBytes(source, 0, 0, 2, 1, 2, 2), 2, 1);
        // Every column of the 2x2 block carries a distinct red value, so the choice is observable.
        assertEquals(source.getRGB(0, 0), sampled.getRGB(0, 0));
        assertEquals(source.getRGB(2, 0), sampled.getRGB(1, 0));
    }

    @Test
    @DisplayName("a partially covered sub sampling block is clipped, not rejected")
    void subSamplingAtTheRightEdge() {
        // Two destination pixels at 2x1 sub sampling need four columns; only three exist. ImageIO
        // treats the trailing block as complete rather than as an error, so neither does this.
        BufferedImage source = new BufferedImage(3, 1, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 3; x++) {
            source.setRGB(x, 0, 0xff000000 | (x + 1) * 0x00010000);
        }
        BufferedImage sampled = AbgrPixels.toBufferedImage(
                AbgrPixels.toAbgrBytes(source, 0, 0, 2, 1, 2, 1), 2, 1);
        assertEquals(source.getRGB(0, 0), sampled.getRGB(0, 0));
        assertEquals(source.getRGB(2, 0), sampled.getRGB(1, 0));
    }

    @Test
    @DisplayName("a short pixel buffer is rejected with the expected size")
    void shortBuffer() {
        assertThrows(IllegalArgumentException.class, () -> AbgrPixels.toBufferedImage(new byte[15], 2, 2));
        // A longer buffer is fine: libavif allocates whole pages.
        assertEquals(2, AbgrPixels.toBufferedImage(new byte[64], 2, 2).getWidth());
    }

    @Test
    @DisplayName("a non positive sub sampling factor is rejected")
    void badSubSampling() {
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_4BYTE_ABGR);
        assertThrows(IllegalArgumentException.class,
                () -> AbgrPixels.toAbgrBytes(source, 0, 0, 2, 2, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> AbgrPixels.toAbgrBytes(source, 0, 0, 2, 2, 1, -1));
    }

    @Test
    @DisplayName("a view into another image's buffer falls back to the generic path")
    void subImageIsNotCopiedVerbatim() {
        // A child raster has a non zero offset into the parent buffer, so a blind row copy would
        // pick up the wrong row. isPacked() rejects the offset, which keeps that honest.
        BufferedImage parent = new BufferedImage(8, 8, BufferedImage.TYPE_4BYTE_ABGR);
        Graphics2D graphics = parent.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 4, 4);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(4, 0, 4, 4);
        graphics.dispose();

        WritableRaster sub = parent.getRaster().createWritableChild(4, 0, 4, 4, 0, 0, null);
        BufferedImage view = new BufferedImage(parent.getColorModel(), sub, false, null);

        BufferedImage copy = AbgrPixels.toBufferedImage(
                AbgrPixels.toAbgrBytes(view, 0, 0, 4, 4, 1, 1), 4, 4);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                assertEquals(parent.getRGB(4 + x, y), copy.getRGB(x, y), "pixel " + x + "," + y);
            }
        }
    }

    @Test
    @DisplayName("a grey image is expanded to colour instead of being copied verbatim")
    void byteGrayIsNotCopiedVerbatim() {
        // TYPE_BYTE_GRAY is a single bank of bytes too, so only the declared image type tells the
        // two apart. Copying it verbatim would turn grey levels into a red and blue tint.
        BufferedImage source = new BufferedImage(2, 1, BufferedImage.TYPE_BYTE_GRAY);
        source.getRaster().setPixel(0, 0, new int[] { 0xff });
        source.getRaster().setPixel(1, 0, new int[] { 0x00 });

        byte[] bytes = AbgrPixels.toAbgrBytes(source, 0, 0, 2, 1, 1, 1);
        assertArrayEquals(new byte[] {
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, 0x00, 0x00, 0x00
        }, bytes);
    }

    // ------------------------------------------------------------------------------- helpers

    /**
     * @return {@code image} behind the plain {@link RenderedImage} interface, so that the generic
     *         conversion path is taken
     */
    private static RenderedImage wrap(BufferedImage image) {
        return wrap(image, 0, 0);
    }

    /**
     * @param minX the reported image origin on x
     * @param minY the reported image origin on y
     * @return {@code image} behind the plain {@link RenderedImage} interface
     */
    private static RenderedImage wrap(BufferedImage image, int minX, int minY) {
        return new FakeRenderedImage(image, minX, minY);
    }

    /**
     * A {@link RenderedImage} that is not a {@link BufferedImage}, with a reportable origin.
     *
     * <p>{@code AbgrPixels} keys its fast path off {@code BufferedImage#getType()}, so hiding the
     * image behind the interface is the only way to reach the generic conversion. Reporting a non
     * zero origin as well is what exercises the translation logic, and it is exactly what the
     * contract of {@link RenderedImage#getData()} requires: the returned raster is in image
     * coordinates, so its {@code minX} is the image's {@code minX} and not {@code 0}.
     */
    private record FakeRenderedImage(BufferedImage image, int minX, int minY) implements RenderedImage {

        @Override
        public Vector<RenderedImage> getSources() {
            return new Vector<>();
        }

        @Override
        public Object getProperty(String name) {
            return Image.UndefinedProperty;
        }

        @Override
        public String[] getPropertyNames() {
            return null;
        }

        @Override
        public ColorModel getColorModel() {
            return image.getColorModel();
        }

        @Override
        public SampleModel getSampleModel() {
            return image.getRaster().getSampleModel();
        }

        @Override
        public int getWidth() {
            return image.getWidth();
        }

        @Override
        public int getHeight() {
            return image.getHeight();
        }

        @Override
        public int getMinX() {
            return minX;
        }

        @Override
        public int getMinY() {
            return minY;
        }

        @Override
        public int getNumXTiles() {
            return 1;
        }

        @Override
        public int getNumYTiles() {
            return 1;
        }

        @Override
        public int getMinTileX() {
            return 0;
        }

        @Override
        public int getMinTileY() {
            return 0;
        }

        @Override
        public int getTileWidth() {
            return image.getWidth();
        }

        @Override
        public int getTileHeight() {
            return image.getHeight();
        }

        @Override
        public int getTileGridXOffset() {
            return minX;
        }

        @Override
        public int getTileGridYOffset() {
            return minY;
        }

        @Override
        public Raster getTile(int tileX, int tileY) {
            return getData();
        }

        @Override
        public Raster getData() {
            // A raster in image coordinates: the whole content, relocated to the declared origin.
            // createChild() rather than createTranslatedChild(), because only the former pins the
            // covered region to (0, 0) and so keeps the samples where the new origin says they are.
            return image.getRaster().createChild(0, 0, image.getWidth(), image.getHeight(), minX, minY, null);
        }

        @Override
        public Raster getData(Rectangle area) {
            return getData().createChild(area.x, area.y, area.width, area.height, 0, 0, null);
        }

        @Override
        public WritableRaster copyData(WritableRaster out) {
            // Not on the conversion path, but the interface demands it. BufferedImage.copyData()
            // shifts the destination back to the source origin for the same reason.
            image.copyData(out.createWritableTranslatedChild(-minX, -minY));
            return out;
        }
    }
}
