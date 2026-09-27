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

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;

/**
 * {@link ImageReader} for {@code .ico} files, implemented in pure Java.
 *
 * <p>An ICO container holds one entry per image size, so {@code read} takes the index
 * of the entry to decode. Paletted, 16-bit and 32-bit bitmaps are supported, and the
 * AND mask is folded into the alpha channel so transparency is preserved.
 *
 * <p>Instances are stateful and not thread safe.
 */
public class IcoImageReader extends ImageReader {

    private static final List<Integer> TYPES = List.of(
            BufferedImage.TYPE_4BYTE_ABGR,
            BufferedImage.TYPE_INT_ARGB,
            BufferedImage.TYPE_INT_ARGB_PRE,
            BufferedImage.TYPE_INT_RGB);

    // Field offsets inside the int[] produced by parseDirectory(), which holds
    // width, height, bitCount, planes, byteSize and dataOffset in that order.
    private static final int WIDTH = 0;
    private static final int HEIGHT = 1;
    private static final int BIT_COUNT = 2;
    private static final int OFFSET = 5;

    private ImageInputStream stream;
    private int[][] directory;

    public IcoImageReader(ImageReaderSpi originatingProvider) {
        super(originatingProvider);
    }

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        stream = (ImageInputStream) getInput();
        directory = null;
    }

    @Override
    public void reset() {
        super.reset();
        stream = null;
        directory = null;
    }

    @Override
    public int getNumImages(boolean allowSearch) throws IIOException {
        verifyInput();
        if (directory == null) {
            try { directory = parseDirectory(); }
            catch (IOException e) { throw new IIOException(e.getMessage(), e); }
        }
        return directory.length;
    }

    @Override
    public int getWidth(int imageIndex) throws IIOException {
        return requireIndex(imageIndex)[WIDTH];
    }

    @Override
    public int getHeight(int imageIndex) throws IIOException {
        return requireIndex(imageIndex)[HEIGHT];
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IIOException {
        requireIndex(imageIndex);
        return types();
    }

    @Override
    public IIOMetadata getStreamMetadata() { return null; }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) {
        return null;
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IIOException {
        int[] entry = requireIndex(imageIndex);
        int w = entry[WIDTH];
        int h = entry[HEIGHT];
        try {
            return decodeImage(w, h, entry[BIT_COUNT], entry[OFFSET]);
        } catch (IOException e) {
            throw new IIOException("cannot read the ICO image: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ internals

    private void verifyInput() throws IIOException {
        if (stream == null) {
            throw new IIOException("no input has been set");
        }
    }

    private int[][] parseDirectory() throws IOException {
        stream.seek(4);
        stream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
        int count = stream.readUnsignedShort();
        int[][] dir = new int[count][];
        for (int i = 0; i < count; i++) {
            int w = stream.readUnsignedByte();
            int h = stream.readUnsignedByte();
            int colors = stream.readUnsignedByte();
            stream.skipBytes(1); // reserved
            int planes = stream.readUnsignedShort();
            int bitCount = stream.readUnsignedShort();
            int size = stream.readInt();
            long offset = stream.readInt() & 0xFFFF_FFFFL;
            dir[i] = new int[] { w == 0 ? 256 : w, h == 0 ? 256 : h, bitCount, planes,
                    size, (int) offset };
        }
        return dir;
    }

    private int[] requireIndex(int imageIndex) throws IIOException {
        verifyInput();
        if (directory == null) {
            try { directory = parseDirectory(); } catch (IOException e) { throw new IIOException(e.getMessage(), e); }
        }
        if (imageIndex < 0 || imageIndex >= directory.length) {
            throw new IIOException("image index " + imageIndex + " out of range (0.." + (directory.length - 1) + ")");
        }
        return directory[imageIndex];
    }

    private Iterator<ImageTypeSpecifier> types() {
        List<ImageTypeSpecifier> list = new ArrayList<>();
        for (int t : TYPES) {
            list.add(ImageTypeSpecifier.createFromBufferedImageType(t));
        }
        return list.iterator();
    }

    private BufferedImage decodeImage(int w, int h, int entryBitCount, int entryOffset)
            throws IOException {
        stream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
        stream.seek(entryOffset);

        long headerSize = stream.readInt(); // BITMAPINFOHEADER size (40), or 12 / 108 / 124
        stream.readInt();                  // biWidth, the directory entry already has it
        // The declared height is twice the image height because the DIB stacks the
        // colour rows on top of the AND mask. The directory entry already carries
        // the real height, so this value is not used.
        stream.readInt();
        stream.readUnsignedShort();        // biPlanes
        int biBitCount = stream.readUnsignedShort();
        int biCompression = stream.readInt();
        stream.skipBytes(12);              // biSizeImage, biXPelsPerMeter, biYPelsPerMeter
        int biClrUsed = stream.readInt();
        stream.skipBytes(4);               // biClrImportant
        if (biCompression == 3) {          // BI_BITFIELDS stores three extra masks
            stream.skipBytes(12);
        }
        if (headerSize > 40) {             // V4 / V5 headers extend the fixed part
            stream.skipBytes((int) (headerSize - 40));
        }

        int bpp = biBitCount > 0 ? biBitCount : entryBitCount;
        if (bpp <= 0) {
            throw new IOException("the ICO entry does not declare a bit depth");
        }

        // Palettes follow the header for images of 8 bits or fewer.
        int[] palette = null;
        if (bpp <= 8) {
            int entries = biClrUsed > 0 ? biClrUsed : (bpp == 8 ? 256 : 1 << bpp);
            byte[] raw = new byte[entries * 4];
            stream.readFully(raw, 0, raw.length);
            palette = new int[entries];
            for (int i = 0; i < entries; i++) {
                palette[i] = ((raw[i * 4 + 2] & 0xFF) << 16)
                        | ((raw[i * 4 + 1] & 0xFF) << 8)
                        | (raw[i * 4] & 0xFF)
                        | 0xFF000000;
            }
        }

        int colorRowBytes = ((w * bpp + 31) / 32) * 4;
        int andRowBytes = ((w + 31) / 32) * 4;
        int colorDataSize = colorRowBytes * h;
        int andDataSize = andRowBytes * h;

        byte[] pixels = new byte[colorDataSize];
        stream.readFully(pixels, 0, colorDataSize);

        byte[] andMask = new byte[andDataSize];
        stream.readFully(andMask, 0, andDataSize);

        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int bytesPerPixel = bpp / 8;
        for (int y = 0; y < h; y++) {
            int srcY = h - 1 - y; // a DIB is stored bottom-up
            for (int x = 0; x < w; x++) {
                int off = srcY * colorRowBytes + x * bytesPerPixel;
                int argb;
                switch (bpp) {
                    case 32:
                        // a 32-bit DIB stores [B, G, R, A]
                        argb = ((pixels[off + 3] & 0xFF) << 24)
                                | ((pixels[off + 2] & 0xFF) << 16)
                                | ((pixels[off + 1] & 0xFF) << 8)
                                | (pixels[off] & 0xFF);
                        break;
                    case 24:
                        argb = 0xFF000000
                                | ((pixels[off + 2] & 0xFF) << 16)
                                | ((pixels[off + 1] & 0xFF) << 8)
                                | (pixels[off] & 0xFF);
                        break;
                    case 16: { // X1R5G5B5
                        int v = (pixels[off] & 0xFF) | ((pixels[off + 1] & 0xFF) << 8);
                        argb = 0xFF000000
                                | ((((v >>> 10) & 0x1F) * 255 / 31) << 16)
                                | ((((v >>> 5) & 0x1F) * 255 / 31) << 8)
                                | ((v & 0x1F) * 255 / 31);
                        break;
                    }
                    default:
                        argb = palette[pixels[off] & 0xFF];
                }
                if (getAndBit(andMask, andRowBytes, x, srcY) == 1) {
                    argb &= 0x00FFFFFF; // the AND mask marks the pixel as transparent
                }
                // setRGB takes one packed ARGB int, so no per-band lookup is needed.
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    private static int getAndBit(byte[] andMask, int andRowBytes, int x, int y) {
        int byteIdx = y * andRowBytes + (x >>> 3);
        int bit = x & 7;
        // AND mask: MSB-first within each byte; 1 = transparent
        return (andMask[byteIdx] >>> (7 - bit)) & 1;
    }
}
