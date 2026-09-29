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

import java.awt.image.RenderedImage;
import java.io.IOException;
import java.util.Locale;

import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;

import imagify.avif.ffm.AvifCodec;
import imagify.avif.ffm.AvifConstants;

/**
 * {@link ImageWriter} that encodes images as AVIF using {@code libavif}.
 *
 * <p>Any non empty {@link RenderedImage} can be encoded. Images whose layout {@code libavif} cannot
 * consume directly are converted to non premultiplied
 * {@link java.awt.image.BufferedImage#TYPE_4BYTE_ABGR} first, so colour and alpha are preserved
 * rather than composited over an opaque background.
 *
 * <p>The encoder speed is fixed at {@link AvifConstants#DEFAULT_SPEED}. The quality comes from the
 * compression quality of the {@link ImageWriteParam}, which maps the {@code javax.imageio} range of
 * {@link AvifImageWriterSpi#MINIMUM_QUALITY} to {@link AvifImageWriterSpi#MAXIMUM_QUALITY} onto the
 * {@code libavif} range of {@code 0} to {@link AvifConstants#QUALITY_BEST}.
 *
 * <p>Instances are stateful and, as mandated by {@link ImageWriter}, not thread safe.
 */
public class AvifImageWriter extends ImageWriter {

    private ImageOutputStream output;
    private boolean wrote;

    /**
     * @param originatingProvider the provider that created this writer
     */
    public AvifImageWriter(ImageWriterSpi originatingProvider) {
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
        return new AvifWriteParam(getLocale());
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
        // Each image is written as a self contained AVIF file, so a caller may append several.
        return true;
    }

    @Override
    public void prepareWriteSequence(IIOMetadata streamMetadata) throws IIOException {
        if (streamMetadata != null) {
            throw new IIOException("an AVIF file has no stream metadata");
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
            throw new IIOException("cannot flush the AVIF output", e);
        }
    }

    @Override
    public void write(IIOMetadata streamMetadata, IIOImage image, ImageWriteParam param) throws IIOException {
        if (output == null) {
            throw new IIOException("no output has been set");
        }
        if (streamMetadata != null) {
            throw new IIOException("an AVIF file has no stream metadata");
        }
        if (image != null && image.hasRaster()) {
            throw new IIOException("this writer encodes images, not rasters");
        }
        byte[] encoded = encode(renderedImageOf(image), qualityOf(param));
        try {
            output.write(encoded);
            output.flush();
        } catch (IOException e) {
            throw new IIOException("cannot write the AVIF image", e);
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

    private static byte[] encode(RenderedImage source, int quality) throws IIOException {
        try {
            return AvifCodec.encode(source, quality, AvifConstants.DEFAULT_SPEED);
        } catch (AvifException e) {
            throw new IIOException("cannot encode the image as AVIF: " + e.getMessage(), e);
        }
    }

    /**
     * @param param the write parameters, may be {@code null}
     * @return {@code libavif} quality, 0 (worst) to {@link AvifConstants#QUALITY_BEST} (lossless)
     * @throws IIOException when the parameters are not supported
     */
    private static int qualityOf(ImageWriteParam param) throws IIOException {
        if (param == null || param.getCompressionMode() != ImageWriteParam.MODE_EXPLICIT) {
            return AvifConstants.DEFAULT_QUALITY;
        }
        float quality = param.getCompressionQuality();
        if (quality < AvifImageWriterSpi.MINIMUM_QUALITY || quality > AvifImageWriterSpi.MAXIMUM_QUALITY) {
            // The JDK setter already rejects this, so only a hand written ImageWriteParam can get here.
            throw new IIOException("the compression quality must be between "
                    + AvifImageWriterSpi.MINIMUM_QUALITY + " and " + AvifImageWriterSpi.MAXIMUM_QUALITY
                    + ", got " + quality);
        }
        return Math.round(quality * AvifConstants.QUALITY_BEST);
    }

    /**
     * The write parameters of this writer.
     *
     * <p>{@link ImageWriteParam} offers no public way to declare that a writer supports compression
     * or which compression type it uses: {@code canWriteCompressed()}, {@code compressionTypes} and
     * {@code compressionType} are all {@code protected} fields with no setters, and every accessor
     * refuses to answer unless they have been set first. Declaring them is therefore the only way to
     * make {@link ImageWriteParam#MODE_EXPLICIT} reachable for a caller at all, which is what every
     * writer in the JDK does from the inside as well.
     */
    private static final class AvifWriteParam extends ImageWriteParam {

        AvifWriteParam(Locale locale) {
            super(locale);
            canWriteCompressed = true;
            compressionTypes = new String[] { AvifImageWriterSpi.COMPRESSION_TYPE };
            compressionType = AvifImageWriterSpi.COMPRESSION_TYPE;
            // The runtime's ImageWriteParam defaults compressionMode to
            // MODE_COPY_FROM_METADATA, but callers of getDefaultWriteParam()
            // expect MODE_DEFAULT, so choose it explicitly. On this build of
            // ImageWriteParam switching to MODE_EXPLICIT clears compressionType,
            // so restore it in setCompressionMode below.
            compressionMode = MODE_DEFAULT;
            compressionQuality = 0.5f;
        }

        @Override
        public String getCompressionType() {
            // The runtime's ImageWriteParam.getCompressionType() additionally
            // requires the mode to be MODE_EXPLICIT, which is a stricter check
            // than "a compression type has been chosen". Here the type is
            // chosen in the constructor, so report it whenever compression is
            // supported and a type has been set regardless of the mode.
            if (!canWriteCompressed()) {
                throw new UnsupportedOperationException("Compression not supported.");
            }
            if (compressionType == null) {
                throw new IllegalStateException("No compression type set!");
            }
            return compressionType;
        }
        @Override
        public void setCompressionMode(int mode) {
            super.setCompressionMode(mode);
            if (mode == MODE_EXPLICIT && compressionType == null) {
                compressionType = AvifImageWriterSpi.COMPRESSION_TYPE;
            }
        }
    }
}
