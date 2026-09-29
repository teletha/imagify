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

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

import imagify.webp.ffm.WebpCodec;

/**
 * {@link ImageReader} for WebP images, backed by a bundled {@code libwebp}.
 *
 * <p>A still image is reported as one image. An animation is reported as one image per frame, with
 * every frame already composited onto the canvas, so frame {@code n} is a full size picture rather
 * than the sub rectangle the file actually stores. An animation therefore needs the whole file
 * decoded before its frame count is known, which is why the count is cached after the first call.
 *
 * <p>The pixels are decoded into a standard {@link BufferedImage} type and then converted to the
 * requested one when the caller asked for something else.
 *
 * <p>Of the {@link ImageReadParam} settings, the source region, the source sub sampling factors and
 * the destination are honoured. Band selection and the sub sampling offsets are not; the reader
 * produces all four channels and starts every sub sampling block at its own top left corner.
 *
 * <p>See {@link WebpCodec} for how the native library is reached. When it is missing, this reader
 * still accepts its input, but any request that needs {@code libwebp} fails with an
 * {@link IIOException} that spells out what went wrong.
 *
 * <p>Instances are stateful and, as mandated by {@link ImageReader}, not thread safe.
 */
public class WebpImageReader extends ImageReader {

    /** The image index of the only frame a still image has. */
    public static final int IMAGE_INDEX = 0;

    /** The image types this reader can produce, most useful first. */
    private static final List<Integer> TYPES = List
            .of(BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE, BufferedImage.TYPE_4BYTE_ABGR, BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_3BYTE_BGR);

    private ImageInputStream stream;

    private long start = -1;

    private byte[] encoded;

    private DecodedWebp decoded;

    private WebpImageInfo info;

    private int[] delaysMs;

    /**
     * @param originatingProvider the provider that created this reader
     */
    public WebpImageReader(ImageReaderSpi originatingProvider) {
        super(originatingProvider);
    }

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        stream = (ImageInputStream) getInput();
        forget();
    }

    @Override
    public void reset() {
        super.reset();
        stream = null;
        forget();
    }

    /** Drops what is remembered about the current input, so that a new one is read afresh. */
    private void forget() {
        start = -1;
        encoded = null;
        decoded = null;
        info = null;
        delaysMs = null;
    }

    @Override
    public int getNumImages(boolean allowSearch) throws IIOException {
        checkIndex(IMAGE_INDEX);
        return checkInfo().frameCount();
    }

    @Override
    public int getWidth(int imageIndex) throws IIOException {
        return checkIndex(imageIndex).width();
    }

    @Override
    public int getHeight(int imageIndex) throws IIOException {
        return checkIndex(imageIndex).height();
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IIOException {
        checkIndex(imageIndex);
        return types();
    }

    @Override
    public IIOMetadata getStreamMetadata() throws IIOException {
        checkInput();
        // The loop count belongs to the file rather than to any one frame, and the frame timing
        // that
        // goes with it is not what a caller of stream metadata is after. Reporting it here is what
        // lets a generic reader find it, which is the same route an animated AVIF takes.
        WebpImageInfo image = checkInfo();
        return new WebpMetadata(image, 0, image.loopCount());
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) throws IIOException {
        WebpImageInfo image = checkIndex(imageIndex);
        return new WebpMetadata(image, durationMsOf(image, imageIndex), image.loopCount());
    }

    /**
     * @return how long the frame is shown, or 0 when the file has no meaningful timing. A still
     *         image is not a one frame animation, so it gets no duration.
     */
    private int durationMsOf(WebpImageInfo image, int imageIndex) throws IIOException {
        if (!image.hasAnimation()) {
            return 0;
        }
        int[] timings = delays();
        return imageIndex < timings.length ? timings[imageIndex] : 0;
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex, String metadataFormat, Set<String> extraMetadataFormats) throws IIOException {
        WebpImageInfo image = checkIndex(imageIndex);
        WebpMetadata metadata = new WebpMetadata(image, durationMsOf(image, imageIndex), image.loopCount());
        // Fail fast, with a useful message, on an unsupported format name.
        metadata.getAsTree(metadataFormat);
        return metadata;
    }

    /**
     * Reports the frame timings of an animation.
     *
     * @return how long each frame is shown in milliseconds, or {@code null} for a still image
     * @throws IIOException when the input is not set or the animation cannot be decoded
     */
    public int[] getFrameDelays() throws IIOException {
        WebpImageInfo image = checkIndex(IMAGE_INDEX);
        if (!image.hasAnimation()) {
            return null;
        }
        return delays();
    }

    @Override
    public boolean canReadRaster() {
        // Reading a raw Raster would bypass the colour conversion that makes WebP usable from Java.
        return false;
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IIOException {
        WebpImageInfo image = checkIndex(imageIndex);
        checkBands(param);
        Sample sample = sampleOf(image, param);
        BufferedImage target = targetOf(param, sample);
        transfer(decode(imageIndex), sample, param, target);
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
            return subX == 1 && subY == 1 && x == 0 && y == 0 && width == sourceWidth && height == sourceHeight;
        }
    }

    private static Sample sampleOf(WebpImageInfo image, ImageReadParam param) throws IIOException {
        if (param == null) {
            return new Sample(0, 0, image.width(), image.height(), 1, 1);
        }
        // The setters never let these drop below 1, but a hand written subclass might.
        int subX = Math.max(1, param.getSourceXSubsampling());
        int subY = Math.max(1, param.getSourceYSubsampling());
        Rectangle region = param.getSourceRegion();
        int x = region == null ? 0 : region.x;
        int y = region == null ? 0 : region.y;
        int width = region == null ? image.width() : region.width;
        int height = region == null ? image.height() : region.height;
        if (x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > image.width() || y + height > image.height()) {
            throw new IIOException("the source region " + region + " does not fit in the " + image.width() + "x" + image
                    .height() + " image");
        }
        return new Sample(x, y, divideUp(width, subX), divideUp(height, subY), subX, subY);
    }

    private static BufferedImage targetOf(ImageReadParam param, Sample sample) throws IIOException {
        if (param == null) {
            return new BufferedImage(sample.width(), sample.height(), BufferedImage.TYPE_INT_ARGB);
        }
        BufferedImage destination = param.getDestination();
        if (destination == null) {
            ImageTypeSpecifier requested = param.getDestinationType();
            int type = BufferedImage.TYPE_INT_ARGB;
            if (requested != null) {
                type = requested.getBufferedImageType();
                if (!TYPES.contains(type)) {
                    throw new IIOException("this reader cannot produce the destination image type: " + requested);
                }
            }
            return new BufferedImage(sample.width(), sample.height(), type);
        }
        if (sample.width() > destination.getWidth() || sample.height() > destination.getHeight()) {
            throw new IIOException("the destination image is " + destination.getWidth() + "x" + destination
                    .getHeight() + " but the requested region is " + sample.width() + "x" + sample.height());
        }
        return destination;
    }

    private static void checkBands(ImageReadParam param) throws IIOException {
        if (param == null) {
            return;
        }
        if (param.getSourceBands() != null && !isEveryBand(param.getSourceBands(), 4)) {
            throw new IIOException("source band selection is not supported: " + Arrays.toString(param.getSourceBands()));
        }
        if (param.getDestinationBands() != null && !isEveryBand(param.getDestinationBands(), 4)) {
            throw new IIOException("destination band selection is not supported: " + Arrays.toString(param.getDestinationBands()));
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

    private static void transfer(BufferedImage source, Sample sample, ImageReadParam param, BufferedImage target) throws IIOException {
        Point offset = param == null ? null : param.getDestinationOffset();
        int atX = offset == null ? 0 : offset.x;
        int atY = offset == null ? 0 : offset.y;
        if (atX < 0 || atY < 0 || atX + sample.width() > target.getWidth() || atY + sample.height() > target.getHeight()) {
            throw new IIOException("the destination offset " + offset + " does not fit in the " + target.getWidth() + "x" + target
                    .getHeight() + " destination image");
        }
        Raster raster = source.getData();
        ColorModel model = target.getColorModel();
        if (sample.isWholeImage(source.getWidth(), source
                .getHeight()) && atX == 0 && atY == 0 && model != null && isWholeImage(target) && model.isCompatibleRaster(raster)) {
            // Same memory layout, so the rows can be handed over without touching a single pixel.
            target.getRaster().setRect(0, 0, raster);
            return;
        }
        int stride = sample.width() * sample.subX();
        int rows = sample.height() * sample.subY();
        int[] pixels = new int[stride * rows];
        source.getRGB(sample.x(), sample.y(), stride, rows, pixels, 0, stride);
        target.setRGB(atX, atY, sample.width(), sample
                .height(), divideDown(pixels, sample.width(), sample.height(), sample.subX(), sample.subY()), 0, sample.width());
    }

    /**
     * @return whether the image covers its raster from the origin, rather than being a view that is
     *         translated into a larger buffer, whose {@code setRect(0, 0, ...)} would land in the
     *         wrong place
     */
    private static boolean isWholeImage(BufferedImage image) {
        Raster raster = image.getRaster();
        return raster.getMinX() == 0 && raster.getMinY() == 0 && raster.getWidth() == image.getWidth() && raster.getHeight() == image
                .getHeight();
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

    private WebpImageInfo checkInfo() throws IIOException {
        checkInput();
        if (info == null) {
            // One pass over the file, kept, because every frame, every duration and the loop count
            // all come out of it. Decoding per frame instead would walk an animation once per
            // frame,
            // which for n frames is n walks rather than one.
            try {
                decoded = WebpCodec.decodeFile(encoded());
            } catch (WebpException e) {
                throw new IIOException("cannot read the WebP file: " + e.getMessage(), e);
            }
            info = decoded.info();
            delaysMs = decoded.delaysMs();
        }
        return info;
    }

    private WebpImageInfo checkIndex(int imageIndex) throws IIOException {
        WebpImageInfo image = checkInfo();
        if (imageIndex < 0 || imageIndex >= image.frameCount()) {
            throw new IndexOutOfBoundsException("image index " + imageIndex + " is out of bounds: " + (image.hasAnimation()
                    ? "the WebP animation holds " + image.frameCount() + " frames"
                    : "a WebP still image holds exactly one image"));
        }
        return image;
    }

    /**
     * @return the whole file, read from the position the input was set at
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
                throw new IIOException("cannot read the WebP input", e);
            }
        }
        return encoded;
    }

    /**
     * @return the length of the input in bytes, or a negative number when the input will not say
     */
    private long length() {
        try {
            // A cached file stream answers -1 rather than failing, which is what the contract
            // allows,
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

    private BufferedImage decode(int imageIndex) throws IIOException {
        WebpImageInfo image = checkInfo();
        try {
            // The frames were composited onto the canvas by the single pass in checkInfo(), so the
            // one that was asked for is simply the one at that position.
            return decoded.frames().get(imageIndex);
        } catch (IndexOutOfBoundsException e) {
            throw new IIOException("image index " + imageIndex + " is out of bounds: " + (image.hasAnimation()
                    ? "the WebP animation holds " + image.frameCount() + " frames"
                    : "a WebP still image holds exactly one image"), e);
        }
    }

    private int[] delays() throws IIOException {
        // checkInfo() fills this in as a side effect of the one pass, so this is only reached when
        // the timing is wanted without the properties, which cannot happen but is cheap to honour.
        checkInfo();
        return delaysMs == null ? new int[0] : delaysMs;
    }
}
