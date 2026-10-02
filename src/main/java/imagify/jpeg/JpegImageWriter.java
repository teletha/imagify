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

import java.awt.image.RenderedImage;
import java.io.IOException;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;

import imagify.jpeg.ffm.JpegliCodec;
import imagify.jpeg.ffm.JpegliLibrary;

/**
 * {@link ImageWriter} that encodes images as JPEG using
 * <a href="https://github.com/google/jpegli">jpegli</a>.
 *
 * <p>Any non empty {@link RenderedImage} can be encoded. Images whose layout the encoder cannot
 * consume directly are converted to non premultiplied
 * {@link java.awt.image.BufferedImage#TYPE_4BYTE_ABGR} first, so colour and alpha are preserved
 * rather than composited over an opaque background. The alpha byte is then discarded, because a JPEG
 * cannot store one; an image with transparency is written against whatever colour sits behind it,
 * and a caller that needs the picture has to flatten it first.
 *
 * <p>The quality comes from the compression quality of the {@link ImageWriteParam}, which maps the
 * {@code javax.imageio} range of {@link JpegImageWriterSpi#MINIMUM_QUALITY} to
 * {@link JpegImageWriterSpi#MAXIMUM_QUALITY} onto the encoder's range of 1 to 100. A quality of 0 is
 * raised to 1 rather than refused, which is what the JDK's own JPEG writer does with it, so a caller
 * asking for the smallest possible file is not answered with an exception.
 *
 * <p>The two settings a JPEG has and an {@link ImageWriteParam} has nowhere for are on
 * {@link JpegWriteParam}, and default to what {@link imagify.ImageFormat.Jpeg} names: 4:2:0 with
 * the standard entropy coder tables.
 *
 * <p>Instances are stateful and, as mandated by {@link ImageWriter}, not thread safe.
 */
public class JpegImageWriter extends ImageWriter {

    private ImageOutputStream output;
    private boolean wrote;

    /**
     * Creates a writer for the {@code ImageIO} plug-in registry to hand the frames to.
     *
     * @param originatingProvider the provider that created this writer
     */
    public JpegImageWriter(ImageWriterSpi originatingProvider) {
        super(originatingProvider);
    }

    @Override
    public void setOutput(Object output) {
        super.setOutput(output);
        this.output = (ImageOutputStream) output;
        this.wrote = false;
    }

    @Override
    public void reset() {
        super.reset();
        output = null;
        wrote = false;
    }

    @Override
    public ImageWriteParam getDefaultWriteParam() {
        return new JpegWriteParam(getLocale());
    }

    // ------------------------------------------------------------------------------ metadata

    @Override
    public IIOMetadata getDefaultStreamMetadata(ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier imageType, ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata convertStreamMetadata(IIOMetadata streamMetadata, ImageWriteParam param) {
        return null;
    }

    @Override
    public IIOMetadata convertImageMetadata(IIOMetadata imageMetadata, ImageTypeSpecifier imageType,
            ImageWriteParam param) {
        return null;
    }

    // -------------------------------------------------------------------------------- writing

    @Override
    public boolean canWriteSequence() {
        // Each image is written as a self contained JPEG file, so a caller may append several.
        return true;
    }

    @Override
    public void prepareWriteSequence(IIOMetadata streamMetadata) throws IIOException {
        if (streamMetadata != null) {
            throw new IIOException("a JPEG file has no stream metadata");
        }
        if (output == null) {
            throw new IIOException("no output has been set");
        }
        wrote = false;
    }

    @Override
    public void writeToSequence(IIOImage image, ImageWriteParam param) throws IIOException {
        write(null, image, param);
    }

    @Override
    public void endWriteSequence() throws IIOException {
        if (!wrote) {
            throw new IIOException("no image was written");
        }
        try {
            output.flush();
        } catch (IOException e) {
            throw new IIOException("cannot flush the JPEG output", e);
        }
    }

    @Override
    public void write(IIOMetadata streamMetadata, IIOImage image, ImageWriteParam param) throws IIOException {
        if (output == null) {
            throw new IIOException("no output has been set");
        }
        if (streamMetadata != null) {
            throw new IIOException("a JPEG file has no stream metadata");
        }
        if (image != null && image.hasRaster()) {
            throw new IIOException("this writer encodes images, not rasters");
        }
        // A JpegWriteParam is what getDefaultWriteParam() hands out and therefore the only way the
        // settings below can have been changed, so anything else leaves the defaults in place.
        JpegWriteParam settings = param instanceof JpegWriteParam jpeg ? jpeg : new JpegWriteParam(getLocale());
        byte[] encoded = encode(renderedImageOf(image), qualityOf(param), settings);
        try {
            output.write(encoded);
            output.flush();
        } catch (IOException e) {
            throw new IIOException("cannot write the JPEG image", e);
        }
        wrote = true;
    }

    // ------------------------------------------------------------------------------ internals

    private static RenderedImage renderedImageOf(IIOImage image) throws IIOException {
        if (image == null) {
            throw new IIOException("no image to write");
        }
        RenderedImage source = image.getRenderedImage();
        if (source == null) {
            throw new IIOException("the image has no rendered image");
        }
        if (source.getWidth() <= 0 || source.getHeight() <= 0) {
            throw new IIOException("cannot write a " + source.getWidth() + "x" + source.getHeight() + " image");
        }
        return source;
    }

    private static byte[] encode(RenderedImage source, int quality, JpegWriteParam settings)
            throws IIOException {
        try {
            return JpegliCodec.encode(source, quality, settings.getSubsampling().samp,
                    settings.getOptimizeHuffmanTables());
        } catch (JpegException e) {
            throw new IIOException("cannot encode the image as JPEG: " + e.getMessage(), e);
        }
    }

    /**
     * @param param the write parameters, may be {@code null}
     * @return the encoder quality, 1 (smallest) to 100 (most detail)
     * @throws IIOException when the parameters are not supported
     */
    private static int qualityOf(ImageWriteParam param) throws IIOException {
        if (param == null || param.getCompressionMode() != ImageWriteParam.MODE_EXPLICIT) {
            // An unset compression quality means the encoder's own choice, which is the one the
            // format names rather than the 0.5 the JDK plug-ins happen to default to.
            return JpegliLibrary.DEFAULT_QUALITY;
        }
        float quality = param.getCompressionQuality();
        if (quality < JpegImageWriterSpi.MINIMUM_QUALITY || quality > JpegImageWriterSpi.MAXIMUM_QUALITY) {
            // The JDK setter already rejects this, so only a hand written ImageWriteParam can get here.
            throw new IIOException("the compression quality must be between "
                    + JpegImageWriterSpi.MINIMUM_QUALITY + " and " + JpegImageWriterSpi.MAXIMUM_QUALITY
                    + ", got " + quality);
        }
        // A quality of 0 is a legal compression quality and means the smallest file, which the
        // encoder expresses as 1. Rounding is what turns 0.85 into 85.
        return Math.max(JpegliLibrary.IMAGIFY_JPEG_MIN_QUALITY,
                Math.round(quality * JpegliLibrary.IMAGIFY_JPEG_MAX_QUALITY));
    }

}
