/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.jpeg;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

import imagify.jpeg.jna.JpegliCodec;
import imagify.pixels.StillImageRead;

/**
 * {@link ImageReader} for JPEG images, backed by <a href="https://github.com/google/jpegli">jpegli</a>.
 *
 * <p>A JPEG file holds exactly one image, so {@link #getNumImages(boolean)} always answers 1 and
 * {@link #read(int, ImageReadParam)} decodes that one. The pixels arrive as a
 * {@link BufferedImage#TYPE_4BYTE_ABGR} image, whose banks are the very {@code A, B, G, R} layout the
 * jpegli shim fills in, and are then converted to the requested type when the caller asked for
 * something else.
 *
 * <p>A greyscale file is upsampled to the same three channel layout rather than handed back as
 * {@link BufferedImage#TYPE_BYTE_GRAY}, so that a caller sees one shape for every JPEG. The alpha
 * channel is fully opaque either way, because a JPEG cannot carry a real one.
 *
 * <p>Of the {@link ImageReadParam} settings, the source region, the source sub sampling factors and
 * the destination are honoured, by {@link StillImageRead} rather than here. Band selection and the
 * sub sampling offsets are not; the reader produces all four channels and starts every sub sampling
 * block at its own top left corner.
 *
 * <p>See {@link JpegliCodec} for how the native library is located. When it is missing, this reader
 * is not offered to {@code ImageIO} at all, which is what leaves the JDK's own JPEG reader in place
 * rather than failing every read.
 *
 * <p>Instances are stateful and, as mandated by {@link ImageReader}, not thread safe.
 */
public class JpegImageReader extends ImageReader {

    /** The index of the only frame a JPEG holds. */
    public static final int IMAGE_INDEX = 0;

    /** The number of channels the decoder produces, which is what a band selection is measured in. */
    private static final int CHANNELS = 4;

    /** The image types this reader can produce, most useful first. */
    private static final List<Integer> TYPES = StillImageRead.DEFAULT_TYPES;

    private ImageInputStream stream;
    private long start = -1;
    private byte[] encoded;

    /**
     * @param originatingProvider the provider that created this reader
     */
    public JpegImageReader(ImageReaderSpi originatingProvider) {
        super(originatingProvider);
    }

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        stream = (ImageInputStream) getInput();
        start = -1;
        encoded = null;
    }

    @Override
    public void reset() {
        super.reset();
        stream = null;
        start = -1;
        encoded = null;
    }

    @Override
    public int getNumImages(boolean allowSearch) throws IIOException {
        checkInput();
        return 1;
    }

    @Override
    public int getWidth(int imageIndex) throws IIOException {
        return header(imageIndex).width();
    }

    @Override
    public int getHeight(int imageIndex) throws IIOException {
        return header(imageIndex).height();
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IIOException {
        checkIndex(imageIndex);
        return TYPES.stream().map(ImageTypeSpecifier::createFromBufferedImageType).iterator();
    }

    @Override
    public IIOMetadata getStreamMetadata() throws IIOException {
        // A JPEG is a single image with no stream level structure, so what a caller asking about the
        // stream wants is the one image it holds.
        return getImageMetadata(IMAGE_INDEX);
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) throws IIOException {
        return new JpegMetadata(header(imageIndex));
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex, String metadataFormat, Set<String> extraMetadataFormats)
            throws IIOException {
        JpegMetadata metadata = (JpegMetadata) getImageMetadata(imageIndex);
        // Fail fast, with a useful message, on an unsupported format name.
        metadata.getAsTree(metadataFormat);
        return metadata;
    }

    @Override
    public boolean canReadRaster() {
        // Reading a raw Raster would bypass the colour conversion that makes JPEG usable from Java,
        // and the decoder is told to output RGB rather than the file's own colour space.
        return false;
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IIOException {
        checkIndex(imageIndex);
        StillImageRead.checkBands(param, CHANNELS);
        BufferedImage source = decode();
        StillImageRead.Sample sample = StillImageRead.sampleOf(source.getWidth(), source.getHeight(), param);
        BufferedImage target = StillImageRead.targetOf(param, sample, TYPES);
        StillImageRead.transfer(source, sample, param, target);
        return target;
    }

    // -------------------------------------------------------------------------------- internals

    private JpegImageInfo header(int imageIndex) throws IIOException {
        checkIndex(imageIndex);
        try {
            return JpegliCodec.readHeader(encoded());
        } catch (JpegException e) {
            throw new IIOException("cannot read the JPEG frame header: " + e.getMessage(), e);
        }
    }

    private BufferedImage decode() throws IIOException {
        try {
            return JpegliCodec.decode(encoded());
        } catch (JpegException e) {
            throw new IIOException("cannot decode the JPEG image: " + e.getMessage(), e);
        }
    }

    private void checkInput() throws IIOException {
        if (stream == null) {
            throw new IIOException("no input has been set");
        }
    }

    /**
     * @param imageIndex the image the caller asked for
     * @throws IIOException when there is no input, or when the index names no image of the file
     */
    private void checkIndex(int imageIndex) throws IIOException {
        checkInput();
        if (imageIndex != IMAGE_INDEX) {
            throw new IndexOutOfBoundsException("image index " + imageIndex
                    + " is out of bounds: a JPEG file holds exactly one image");
        }
    }

    /**
     * @return the whole input, read from the position the input was set at
     * @throws IIOException when the input cannot be read
     */
    private byte[] encoded() throws IIOException {
        if (encoded == null) {
            try {
                // setInput() cannot report a failure, because it may not throw IIOException, so the
                // position the input was set at is remembered the first time it is actually needed.
                if (start < 0) {
                    start = stream.getStreamPosition();
                }
                stream.seek(start);
                long length = length();
                encoded = length < 0 ? readToEnd() : readFully(length - start);
            } catch (IOException | OutOfMemoryError e) {
                throw new IIOException("cannot read the JPEG input", e);
            }
        }
        return encoded;
    }

    /**
     * @return the length of the input in bytes, or a negative number when the input will not say
     */
    private long length() {
        try {
            // A cached file stream answers -1 rather than failing, which is what the contract allows,
            // so anything below the start position counts as "unknown" too.
            long length = stream.length();
            return length < start ? -1 : length;
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * @param length the number of bytes left to read
     * @return those bytes, in one allocation because the length is known
     */
    private byte[] readFully(long length) throws IOException {
        if (length > Integer.MAX_VALUE - 8L) {
            throw new IOException("the input is too large to fit in memory: " + length + " bytes");
        }
        byte[] bytes = new byte[(int) length];
        stream.readFully(bytes);
        return bytes;
    }

    /**
     * @return every remaining byte, grown on demand because the length is not known
     */
    private byte[] readToEnd() throws IOException {
        byte[] bytes = new byte[8192];
        int size = 0;
        while (true) {
            if (size == bytes.length) {
                if (bytes.length > Integer.MAX_VALUE / 2) {
                    throw new IOException("the input is too large to fit in memory");
                }
                bytes = Arrays.copyOf(bytes, Math.multiplyExact(bytes.length, 2));
            }
            int count = stream.read(bytes, size, bytes.length - size);
            if (count < 0) {
                return Arrays.copyOf(bytes, size);
            }
            size += count;
        }
    }
}
