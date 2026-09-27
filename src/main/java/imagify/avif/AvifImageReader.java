/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.avif;

import imagify.avif.jna.AvifAnimationDecoder;
import imagify.avif.jna.AvifCodec;

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * {@link ImageReader} for AVIF images, backed by {@code libavif}.
 *
 * <p>An AVIF file holds either a single still image or an image sequence, and this reader reports
 * whichever it finds: {@link #getNumImages(boolean)} answers 1 for a still and the frame count for an
 * animation, and {@link #read(int, ImageReadParam)} decodes the frame asked for. The pixels arrive as
 * a {@link BufferedImage#TYPE_4BYTE_ABGR} image, whose banks are the very {@code A, B, G, R} layout
 * {@code libavif} fills in for {@code AVIF_RGB_FORMAT_ABGR}, and are then converted to the requested
 * type when the caller asked for something else.
 *
 * <p>How long each frame is shown, and how often the sequence repeats, are reported through
 * {@link #getImageMetadata(int)} and {@link #getStreamMetadata()} rather than through the standard
 * metadata format, which has no notion of animation.
 *
 * <p>Of the {@link ImageReadParam} settings, the source region, the source sub sampling factors and
 * the destination are honoured. Band selection and the sub sampling offsets are not; the reader
 * produces all four channels and starts every sub sampling block at its own top left corner.
 *
 * <p>See {@link AvifCodec} for how the native library is located. When it is missing, this reader
 * still accepts its input, but any request that needs {@code libavif} fails with an
 * {@link IIOException} that spells out what went wrong.
 *
 * <p>Instances are stateful and, as mandated by {@link ImageReader}, not thread safe.
 */
public class AvifImageReader extends ImageReader {

    /** The index of the first frame, which for a still image is the only one. */
    public static final int IMAGE_INDEX = 0;

    /** The image types this reader can produce, most useful first. */
    private static final List<Integer> TYPES = List.of(
            BufferedImage.TYPE_4BYTE_ABGR,
            BufferedImage.TYPE_INT_ARGB,
            BufferedImage.TYPE_INT_ARGB_PRE,
            BufferedImage.TYPE_INT_RGB,
            BufferedImage.TYPE_3BYTE_BGR);

    private ImageInputStream stream;
    private long start = -1;
    private byte[] encoded;
    private AvifAnimationDecoder animation;

    /**
     * @param originatingProvider the provider that created this reader
     */
    public AvifImageReader(ImageReaderSpi originatingProvider) {
        super(originatingProvider);
    }

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        closeAnimation();
        stream = (ImageInputStream) getInput();
        start = -1;
        encoded = null;
    }

    @Override
    public void reset() {
        super.reset();
        closeAnimation();
        stream = null;
        start = -1;
        encoded = null;
    }

    @Override
    public int getNumImages(boolean allowSearch) throws IIOException {
        checkInput();
        return animation().frameCount();
    }

    @Override
    public int getWidth(int imageIndex) throws IIOException {
        return size(imageIndex, true);
    }

    @Override
    public int getHeight(int imageIndex) throws IIOException {
        return size(imageIndex, false);
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IIOException {
        checkIndex(imageIndex);
        return types();
    }

    @Override
    public IIOMetadata getStreamMetadata() throws IIOException {
        checkInput();
        // The loop count belongs to the file rather than to any one frame, and the frame timing that
        // goes with it is not what a caller of stream metadata is after.
        AvifAnimationDecoder decoder = animation();
        return new AvifMetadata(decoder.info(), 0, decoder.loopCount());
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) throws IIOException {
        return metadataOf(imageIndex);
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex, String metadataFormat, Set<String> extraMetadataFormats)
            throws IIOException {
        AvifMetadata metadata = (AvifMetadata) metadataOf(imageIndex);
        // Fail fast, with a useful message, on an unsupported format name.
        metadata.getAsTree(metadataFormat);
        return metadata;
    }

    @Override
    public boolean canReadRaster() {
        // Reading a raw Raster would bypass the colour conversion that makes AVIF usable from Java.
        return false;
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IIOException {
        checkIndex(imageIndex);
        checkBands(param);
        BufferedImage source = frame(imageIndex);
        Sample sample = sampleOf(source.getWidth(), source.getHeight(), param);
        BufferedImage target = targetOf(param, sample);
        transfer(source, sample, param, target);
        return target;
    }

    // ------------------------------------------------------------------------- read parameters

    /**
     * The part of the decoded image that {@link ImageReadParam} selects.
     *
     * @param x left edge, in source pixels
     * @param y top edge, in source pixels
     * @param width sampled width, in destination pixels
     * @param height sampled height, in destination pixels
     * @param subX horizontal sub sampling factor
     * @param subY vertical sub sampling factor
     */
    private record Sample(int x, int y, int width, int height, int subX, int subY) {

        boolean isWholeImage(int sourceWidth, int sourceHeight) {
            return subX == 1 && subY == 1 && x == 0 && y == 0
                    && width == sourceWidth && height == sourceHeight;
        }
    }

    private static Sample sampleOf(int width, int height, ImageReadParam param) throws IIOException {
        if (param == null) {
            return new Sample(0, 0, width, height, 1, 1);
        }
        // The setters never let these drop below 1, but a hand written subclass might.
        int subX = Math.max(1, param.getSourceXSubsampling());
        int subY = Math.max(1, param.getSourceYSubsampling());
        Rectangle region = param.getSourceRegion();
        int x = region == null ? 0 : region.x;
        int y = region == null ? 0 : region.y;
        int regionW = region == null ? width : region.width;
        int regionH = region == null ? height : region.height;
        if (x < 0 || y < 0 || regionW <= 0 || regionH <= 0
                || x + regionW > width || y + regionH > height) {
            throw new IIOException("the source region " + region + " does not fit in the "
                    + width + "x" + height + " image");
        }
        return new Sample(x, y, divideUp(regionW, subX), divideUp(regionH, subY), subX, subY);
    }

    private static BufferedImage targetOf(ImageReadParam param, Sample sample) throws IIOException {
        if (param == null) {
            return new BufferedImage(sample.width(), sample.height(), BufferedImage.TYPE_4BYTE_ABGR);
        }
        BufferedImage destination = param.getDestination();
        if (destination == null) {
            ImageTypeSpecifier requested = param.getDestinationType();
            int type = BufferedImage.TYPE_4BYTE_ABGR;
            if (requested != null) {
                type = requested.getBufferedImageType();
                if (!TYPES.contains(type)) {
                    throw new IIOException("this reader cannot produce the destination image type: " + requested);
                }
            }
            return new BufferedImage(sample.width(), sample.height(), type);
        }
        if (sample.width() > destination.getWidth() || sample.height() > destination.getHeight()) {
            throw new IIOException("the destination image is " + destination.getWidth() + "x"
                    + destination.getHeight() + " but the requested region is " + sample.width() + "x"
                    + sample.height());
        }
        return destination;
    }

    private static void checkBands(ImageReadParam param) throws IIOException {
        if (param == null) {
            return;
        }
        if (param.getSourceBands() != null && !isEveryBand(param.getSourceBands(), 4)) {
            throw new IIOException("source band selection is not supported: "
                    + Arrays.toString(param.getSourceBands()));
        }
        if (param.getDestinationBands() != null && !isEveryBand(param.getDestinationBands(), 4)) {
            throw new IIOException("destination band selection is not supported: "
                    + Arrays.toString(param.getDestinationBands()));
        }
    }

    private static boolean isEveryBand(int[] bands, int count) {
        if (bands.length != count) {
            return false;
        }
        for (int band = 0; band < count; band++) {
            if (bands[band] != band) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------ pixel transfer

    private static void transfer(BufferedImage source, Sample sample, ImageReadParam param, BufferedImage target)
            throws IIOException {
        Point offset = param == null ? null : param.getDestinationOffset();
        int atX = offset == null ? 0 : offset.x;
        int atY = offset == null ? 0 : offset.y;
        if (atX < 0 || atY < 0
                || atX + sample.width() > target.getWidth()
                || atY + sample.height() > target.getHeight()) {
            throw new IIOException("the destination offset " + offset + " does not fit in the "
                    + target.getWidth() + "x" + target.getHeight() + " destination image");
        }
        Raster raster = source.getData();
        ColorModel model = target.getColorModel();
        if (sample.isWholeImage(source.getWidth(), source.getHeight())
                && atX == 0 && atY == 0
                && model != null
                && model.isCompatibleRaster(raster)) {
            // Same memory layout, so the rows can be handed over without touching a single pixel.
            target.getRaster().setRect(0, 0, raster);
            return;
        }
        int stride = sample.width() * sample.subX();
        int rows = sample.height() * sample.subY();
        int[] pixels = new int[stride * rows];
        source.getRGB(sample.x(), sample.y(), stride, rows, pixels, 0, stride);
        target.setRGB(atX, atY, sample.width(), sample.height(),
                divideDown(pixels, sample.width(), sample.height(), sample.subX(), sample.subY()),
                0, sample.width());
    }

    /**
     * Keeps the top left pixel of every {@code subX} by {@code subY} block, which is what
     * {@link ImageReadParam#getSourceXSubsampling()} specifies.
     */
    private static int[] divideDown(int[] pixels, int width, int height, int subX, int subY) {
        if (subX == 1 && subY == 1) {
            return pixels;
        }
        int stride = width * subX;
        int[] out = new int[width * height];
        for (int row = 0; row < height; row++) {
            System.arraycopy(pixels, row * subY * stride, out, row * width, width);
        }
        return out;
    }

    private static int divideUp(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    // -------------------------------------------------------------------------------- internals

    private static Iterator<ImageTypeSpecifier> types() {
        return TYPES.stream().map(ImageTypeSpecifier::createFromBufferedImageType).iterator();
    }

    private void checkInput() throws IIOException {
        if (stream == null) {
            throw new IIOException("no input has been set");
        }
    }

    /**
     * The decoder over the whole file, opened on first use and kept until the input is replaced.
     */
    private AvifAnimationDecoder animation() throws IIOException {
        if (animation == null) {
            try {
                animation = AvifAnimationDecoder.open(encoded());
            } catch (AvifException e) {
                throw new IIOException("cannot read the AVIF file: " + e.getMessage(), e);
            }
        }
        return animation;
    }

    private void closeAnimation() {
        if (animation != null) {
            animation.close();
            animation = null;
        }
    }

    private BufferedImage frame(int imageIndex) throws IIOException {
        try {
            return animation().frame(imageIndex);
        } catch (AvifException e) {
            throw new IIOException("cannot decode frame " + imageIndex + " of the AVIF file: "
                    + e.getMessage(), e);
        }
    }

    private int size(int imageIndex, boolean width) throws IIOException {
        checkIndex(imageIndex);
        try {
            AvifAnimationDecoder decoder = animation();
            return width ? decoder.width(imageIndex) : decoder.height(imageIndex);
        } catch (AvifException e) {
            throw new IIOException("cannot read the size of frame " + imageIndex + ": "
                    + e.getMessage(), e);
        }
    }

    private IIOMetadata metadataOf(int imageIndex) throws IIOException {
        checkIndex(imageIndex);
        try {
            AvifAnimationDecoder decoder = animation();
            // A file with a single frame is a still image, not a one frame animation, so there is
            // nothing to say about how long it is shown and no duration is published.
            int durationMs = decoder.frameCount() > 1 ? decoder.durationMs(imageIndex) : 0;
            return new AvifMetadata(decoder.info(), durationMs, decoder.loopCount());
        } catch (AvifException e) {
            throw new IIOException("cannot read the metadata of frame " + imageIndex + ": "
                    + e.getMessage(), e);
        }
    }

    /**
     * @param imageIndex the frame the caller asked for
     * @throws IIOException when there is no input, or when the index names no frame of the file
     */
    private void checkIndex(int imageIndex) throws IIOException {
        checkInput();
        int frameCount = animation().frameCount();
        if (imageIndex < 0 || imageIndex >= frameCount) {
            throw new IndexOutOfBoundsException("image index " + imageIndex
                    + " is out of bounds: the AVIF file holds " + frameCount + " frame(s)");
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
                throw new IIOException("cannot read the AVIF input", e);
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
