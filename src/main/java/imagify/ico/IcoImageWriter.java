/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.ico;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;

import javax.imageio.IIOException;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;

/**
 * {@link ImageWriter} that encodes images as {@code .ico}.
 *
 * <p>Writing is implemented in pure Java. The writer stores a single image at its
 * native size as a 32-bit BGRA bitmap with an AND mask derived from the alpha channel,
 * so transparency is preserved.
 *
 * <p>Instances are stateful and not thread safe.
 */
public class IcoImageWriter extends ImageWriter {

    private ImageOutputStream stream;
    private boolean wrote;

    public IcoImageWriter(ImageWriterSpi originatingProvider) {
        super(originatingProvider);
    }

    @Override
    public void setOutput(Object output) {
        super.setOutput(output);
        this.stream = (ImageOutputStream) output;
        this.wrote = false;
    }

    @Override
    public void reset() {
        super.reset();
        stream = null;
        wrote = false;
    }

    @Override
    public ImageWriteParam getDefaultWriteParam() {
        return new IcoWriteParam(getLocale());
    }

    @Override
    public void write(IIOMetadata streamMetadata,
                      javax.imageio.IIOImage image,
                      ImageWriteParam param) throws IIOException {
        if (stream == null) {
            throw new IIOException("no output has been set");
        }
        if (streamMetadata != null) {
            throw new IIOException("an ICO file has no stream metadata");
        }
        if (image == null) {
            throw new IIOException("no image to write");
        }
        BufferedImage source;
        RenderedImage rendered = image.getRenderedImage();
        if (rendered != null) {
            source = toBufferedImage(rendered);
        } else if (image.hasRaster()) {
            source = toBufferedImage(image.getRaster());
        } else {
            throw new IIOException("the image has neither a rendered image nor a raster");
        }
        byte[] ico;
        try {
            ico = encode(source);
        } catch (IIOException e) {
            throw e; // the encoder already explains what is wrong
        } catch (IOException e) {
            throw new IIOException("cannot encode the image as ICO", e);
        }
        try {
            stream.write(ico);
            stream.flush();
        } catch (IOException e) {
            throw new IIOException("cannot write the ICO image", e);
        }
        wrote = true;
    }

    @Override
    public boolean canWriteSequence() { return false; }

    @Override
    public boolean canWriteRasters() { return false; }

    @Override
    public IIOMetadata convertStreamMetadata(IIOMetadata inData, ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata convertImageMetadata(IIOMetadata inData,
                                             ImageTypeSpecifier imageType,
                                             ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata getDefaultStreamMetadata(ImageWriteParam param) { return null; }

    @Override
    public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier imageType,
                                                 ImageWriteParam param) { return null; }

    // ------------------------------------------------------------------ internals

    private static BufferedImage toBufferedImage(RenderedImage src) {
        BufferedImage view = asBufferedImage(src);
        if (view.getType() == BufferedImage.TYPE_INT_ARGB) {
            return view;
        }
        BufferedImage conv = new BufferedImage(
                view.getWidth(), view.getHeight(), BufferedImage.TYPE_INT_ARGB);
        // Reading through getRGB and writing through setRGB keeps the channel
        // order correct regardless of the sample layout of the source raster.
        for (int y = 0; y < view.getHeight(); y++) {
            for (int x = 0; x < view.getWidth(); x++) {
                conv.setRGB(x, y, view.getRGB(x, y));
            }
        }
        return conv;
    }

    /** Wraps any {@link RenderedImage} so that its pixels can be read with {@code getRGB}. */
    private static BufferedImage asBufferedImage(RenderedImage src) {
        if (src instanceof BufferedImage) {
            return (BufferedImage) src;
        }
        Raster data = src.getData();
        WritableRaster writable;
        if (data instanceof WritableRaster) {
            writable = (WritableRaster) data;
        } else {
            writable = (WritableRaster) data.createCompatibleWritableRaster(
                    data.getWidth(), data.getHeight());
            writable.setRect(data);
        }
        // Normalise the origin so the caller can index from (0, 0).
        if (writable.getMinX() != 0 || writable.getMinY() != 0) {
            writable = (WritableRaster) writable.createTranslatedChild(
                    -writable.getMinX(), -writable.getMinY());
        }
        return new BufferedImage(src.getColorModel(), writable, false, null);
    }

    private static BufferedImage toBufferedImage(Raster raster) {
        if (raster instanceof WritableRaster) {
            return toBufferedImage((RenderedImage) raster);
        }
        WritableRaster writable = (WritableRaster) raster.createCompatibleWritableRaster(
                raster.getWidth(), raster.getHeight());
        writable.setRect(raster);
        return toBufferedImage((RenderedImage) writable);
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        int w = image.getWidth();
        int h = image.getHeight();
        if (w <= 0 || h <= 0) {
            throw new IIOException("cannot write a " + w + "x" + h + " image");
        }
        if (w > 256 || h > 256) {
            // The directory entry stores each side in a single byte, where 0
            // stands for 256, so there is no way to express anything larger.
            throw new IIOException("an ICO entry cannot be larger than 256x256, got "
                    + w + "x" + h);
        }

        byte[] colorData = new byte[w * h * 4];
        int andRowStride = ((w + 31) / 32) * 4;
        byte[] andMask = new byte[andRowStride * h];

        // A DIB is stored bottom-up, so the first row in the file is the bottom one.
        for (int y = 0; y < h; y++) {
            int srcY = h - 1 - y;
            for (int x = 0; x < w; x++) {
                // getRGB yields one packed ARGB int, which avoids relying on the
                // per-band sample order of the underlying raster.
                int argb = image.getRGB(x, srcY);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                int off = (y * w + x) * 4;
                // a 32-bit BMP stores [B, G, R, A]
                colorData[off]     = (byte) b;
                colorData[off + 1] = (byte) g;
                colorData[off + 2] = (byte) r;
                colorData[off + 3] = (byte) a;
                // AND mask: transparent if alpha < 128
                int andByte = y * andRowStride + (x >>> 3);
                int andBit = x & 7;
                if (a < 128) {
                    andMask[andByte] |= (0x80 >>> andBit);
                }
            }
        }

        int dirSize = 6 + 16; // header + one ICONDIRENTRY
        int colorRowBytes = w * 4;
        int colorDataSize = colorRowBytes * h;
        int andRowBytes = ((w + 31) / 32) * 4;
        int andDataSize = andRowBytes * h;
        int imageDataSize = 40 + colorDataSize + andDataSize;
        int totalSize = dirSize + imageDataSize;
        byte[] out = new byte[totalSize];

        // --- ICO header (6 bytes) ---
        out[0] = 0; out[1] = 0; // reserved
        out[2] = 1; out[3] = 0; // type = 1 (ICO)
        out[4] = 1; out[5] = 0; // count = 1

        // --- ICONDIRENTRY (16 bytes) ---
        out[6]  = (byte) w;                       // width
        out[7]  = (byte) h;                       // height
        out[8]  = 0;                              // colors (>=8bpp)
        out[9]  = 0;                              // reserved
        out[10] = 1; out[11] = 0;                 // planes
        out[12] = 32; out[13] = 0;                // bitcount = 32
        out[14] = (byte) (imageDataSize & 0xFF);
        out[15] = (byte) ((imageDataSize >>> 8) & 0xFF);
        out[16] = (byte) ((imageDataSize >>> 16) & 0xFF);
        out[17] = (byte) ((imageDataSize >>> 24) & 0xFF);
        out[18] = (byte) (dirSize & 0xFF);        // offset
        out[19] = (byte) ((dirSize >>> 8) & 0xFF);
        out[20] = (byte) ((dirSize >>> 16) & 0xFF);
        out[21] = (byte) ((dirSize >>> 24) & 0xFF);

        // --- BITMAPINFOHEADER (40 bytes) at offset 22 ---
        int hdrOff = 22;
        // biSize
        out[hdrOff] = 40; out[hdrOff+1] = 0; out[hdrOff+2] = 0; out[hdrOff+3] = 0;
        // biWidth
        out[hdrOff+4] = (byte) w; out[hdrOff+5] = 0; out[hdrOff+6] = 0; out[hdrOff+7] = 0;
        // biHeight: an ICO DIB holds the colour rows and the AND mask stacked
        // vertically, so the declared height is twice the image height.
        int dibHeight = h * 2;
        out[hdrOff+8] = (byte) dibHeight; out[hdrOff+9] = (byte) (dibHeight >>> 8);
        out[hdrOff+10] = (byte) (dibHeight >>> 16); out[hdrOff+11] = (byte) (dibHeight >>> 24);
        // biPlanes, biBitCount
        out[hdrOff+12] = 1; out[hdrOff+13] = 0;
        out[hdrOff+14] = 32; out[hdrOff+15] = 0;
        // biCompression = BI_RGB
        out[hdrOff+16] = 0; out[hdrOff+17] = 0; out[hdrOff+18] = 0; out[hdrOff+19] = 0;
        // biSizeImage: colour rows plus the AND mask
        int cs = colorDataSize + andDataSize;
        out[hdrOff+20] = (byte) (cs & 0xFF); out[hdrOff+21] = (byte) ((cs >>> 8) & 0xFF); out[hdrOff+22] = (byte) ((cs >>> 16) & 0xFF); out[hdrOff+23] = (byte) ((cs >>> 24) & 0xFF);
        // biXPelsPerMeter = 0
        out[hdrOff+24] = 0; out[hdrOff+25] = 0; out[hdrOff+26] = 0; out[hdrOff+27] = 0;
        // biYPelsPerMeter = 0
        out[hdrOff+28] = 0; out[hdrOff+29] = 0; out[hdrOff+30] = 0; out[hdrOff+31] = 0;
        // biClrUsed = 0
        out[hdrOff+32] = 0; out[hdrOff+33] = 0; out[hdrOff+34] = 0; out[hdrOff+35] = 0;
        // biClrImportant = 0
        out[hdrOff+36] = 0; out[hdrOff+37] = 0; out[hdrOff+38] = 0; out[hdrOff+39] = 0;

        // --- pixel data ---
        System.arraycopy(colorData, 0, out, hdrOff + 40, colorDataSize);
        // --- AND mask ---
        System.arraycopy(andMask, 0, out, hdrOff + 40 + colorDataSize, andDataSize);

        return out;
    }
}
