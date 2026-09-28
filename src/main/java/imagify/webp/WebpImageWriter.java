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

import imagify.webp.jna.WebpCodec;

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

/**
 * {@link ImageWriter} that encodes images as WebP using a bundled {@code libwebp}.
 *
 * <p>
 * Any non empty {@link RenderedImage} can be encoded. The quality comes from the compression
 * quality of the {@link ImageWriteParam}, whose {@code 0.0} to {@code 1.0} range is mapped onto the
 * {@code 0} to {@code 100} range that {@code libwebp} expects. Lossless {@code VP8L} is selected by
 * choosing the {@link WebpImageWriterSpi#COMPRESSION_TYPE_LOSSLESS} compression type, in which case
 * the quality is ignored, exactly as {@code libwebp} ignores it.
 *
 * <p>
 * Instances are stateful and, as mandated by {@link ImageWriter}, not thread safe.
 */
public class WebpImageWriter extends ImageWriter {

    private ImageOutputStream output;

    private boolean wrote;

    /**
     * @param originatingProvider the provider that created this writer
     */
    public WebpImageWriter(ImageWriterSpi originatingProvider) {
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
        return new WebpWriteParam(getLocale());
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
    public IIOMetadata convertImageMetadata(IIOMetadata imageMetadata, ImageTypeSpecifier imageType, ImageWriteParam param) {
        return null;
    }

    // -------------------------------------------------------------------------------- writing

    @Override
    public boolean canWriteSequence() {
        // Each image is written as a self contained WebP file, so a caller may append several.
        return true;
    }

    @Override
    public void prepareWriteSequence(IIOMetadata streamMetadata) throws IIOException {
        if (streamMetadata != null) {
            throw new IIOException("a WebP file has no stream metadata");
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
            throw new IIOException("cannot flush the WebP output", e);
        }
    }

    @Override
    public void write(IIOMetadata streamMetadata, IIOImage image, ImageWriteParam param) throws IIOException {
        if (output == null) {
            throw new IIOException("no output has been set");
        }
        if (streamMetadata != null) {
            throw new IIOException("a WebP file has no stream metadata");
        }
        if (image != null && image.hasRaster()) {
            throw new IIOException("this writer encodes images, not rasters");
        }
        byte[] encoded = encode(renderedImageOf(image), settingsOf(param));
        try {
            output.write(encoded);
            output.flush();
        } catch (IOException e) {
            throw new IIOException("cannot write the WebP image", e);
        }
        wrote = true;
    }

    // ---------------------------------------------------------------------------- internals

    /**
     * What the caller asked the encoder to do.
     *
     * @param quality {@code 0} to {@code 100}, ignored when {@code lossless} is set
     * @param lossless whether to store the pixels without loss
     */
    private record Settings(int quality, boolean lossless) {
    }

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

    private static byte[] encode(RenderedImage source, Settings settings) throws IIOException {
        try {
            return WebpCodec.encode(source, settings.quality(), settings.lossless());
        } catch (WebpException e) {
            throw new IIOException("cannot encode the image as WebP: " + e.getMessage(), e);
        }
    }

    /**
     * @param param the write parameters, may be {@code null}
     * @return the quality and the lossless flag to encode with
     * @throws IIOException when the parameters are not supported
     */
    private static Settings settingsOf(ImageWriteParam param) throws IIOException {
        if (param == null || param.getCompressionMode() != ImageWriteParam.MODE_EXPLICIT) {
            return new Settings(WebpCodec.DEFAULT_QUALITY, false);
        }
        float quality = param.getCompressionQuality();
        if (quality < WebpImageWriterSpi.MINIMUM_QUALITY || quality > WebpImageWriterSpi.MAXIMUM_QUALITY) {
            // The JDK setter already rejects this, so only a hand written ImageWriteParam can get
            // here.
            throw new IIOException("the compression quality must be between " + WebpImageWriterSpi.MINIMUM_QUALITY + " and " + WebpImageWriterSpi.MAXIMUM_QUALITY + ", got " + quality);
        }
        boolean lossless = WebpImageWriterSpi.COMPRESSION_TYPE_LOSSLESS.equals(typeOf(param));
        // The 0.0 to 1.0 of javax.imageio is mapped onto the 0 to 100 that libwebp expects.
        return new Settings(Math.round(quality * 100), lossless);
    }

    private static String typeOf(ImageWriteParam param) {
        try {
            return param.getCompressionType();
        } catch (IllegalStateException | UnsupportedOperationException e) {
            // ImageWriteParam only answers once a compression type has been chosen, which the JDK
            // default parameters never do, so fall back to the lossy default.
            return null;
        }
    }

    /**
     * The write parameters of this writer.
     *
     * <p>
     * {@link ImageWriteParam} offers no public way to declare that a writer supports compression
     * or which compression types it uses: {@code canWriteCompressed()}, {@code compressionTypes}
     * and
     * {@code compressionType} are all {@code protected} fields with no setters, and every accessor
     * refuses to answer unless they have been set first. Declaring them is therefore the only way
     * to
     * make {@link ImageWriteParam#MODE_EXPLICIT} reachable for a caller at all, which is what every
     * writer in the JDK does from the inside as well.
     */
    private static final class WebpWriteParam extends ImageWriteParam {

        private static final String[] TYPES = {WebpImageWriterSpi.COMPRESSION_TYPE, WebpImageWriterSpi.COMPRESSION_TYPE_LOSSLESS};

        WebpWriteParam(Locale locale) {
            super(locale);
            canWriteCompressed = true;
            compressionTypes = TYPES.clone();
            compressionType = TYPES[0];
            // The runtime's ImageWriteParam defaults compressionMode to
            // MODE_COPY_FROM_METADATA, but callers of getDefaultWriteParam()
            // expect MODE_DEFAULT, so choose it explicitly. On this build of
            // ImageWriteParam switching to MODE_EXPLICIT clears compressionType,
            // so restore it in setCompressionMode below.
            compressionMode = MODE_DEFAULT;
            compressionQuality = WebpCodec.DEFAULT_QUALITY / 100f;
        }

        @Override
        public String[] getCompressionTypes() {
            return compressionTypes == null ? null : compressionTypes.clone();
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
        public float getCompressionQuality() {
            // As with getCompressionType() above, the runtime's ImageWriteParam refuses to
            // report the quality outside MODE_EXPLICIT, even though the field is set in the
            // constructor and is what the writer would use. A caller that asks
            // getDefaultWriteParam() what the default quality is deserves an answer.
            return compressionQuality;
        }

        @Override
        public void setCompressionMode(int mode) {
            super.setCompressionMode(mode);
            if (mode == MODE_EXPLICIT && compressionType == null) {
                compressionType = TYPES[0];
            }
        }
    }
}
