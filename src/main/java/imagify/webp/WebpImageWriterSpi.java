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

import java.awt.image.RenderedImage;
import java.util.Locale;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;

import imagify.webp.ffm.WebpCodec;

/**
 * Registers {@link WebpImageWriter} with {@code ImageIO}.
 *
 * <p>Encoding needs the native {@code libwebp} library, which this jar bundles and unpacks on first
 * use. To keep {@code ImageIO} usable when that library is absent,
 * {@link #canEncodeImage(RenderedImage)} answers {@code false} in that case, which makes
 * {@code ImageIO.write()} return {@code false} instead of failing hard.
 */
public class WebpImageWriterSpi extends ImageWriterSpi {

    /** Version of this service provider implementation. */
    public static final String VERSION = "0.1";

    /** Lossy {@code VP8} compression, selected by setting the compression type. */
    public static final String COMPRESSION_TYPE = "WebP";

    /** Lossless {@code VP8L} compression, selected by setting the compression type. */
    public static final String COMPRESSION_TYPE_LOSSLESS = "WebP Lossless";

    /** Lowest accepted {@link javax.imageio.ImageWriteParam} compression quality. */
    public static final float MINIMUM_QUALITY = 0.0f;

    /** Highest accepted {@link javax.imageio.ImageWriteParam} compression quality. */
    public static final float MAXIMUM_QUALITY = 1.0f;

    private static final String[] NAMES = { "webp", "WEBP", "WebP" };
    private static final String[] SUFFIXES = { "webp" };
    private static final String[] MIME_TYPES = { "image/webp" };
    private static final String CLASS_NAME = "imagify.webp.WebpImageWriter";
    private static final String VENDOR_NAME = "https://github.com/teletha/imagify";

    public WebpImageWriterSpi() {
        // ImageWriter.setOutput() consults getOutputTypes() to decide which destinations it may hand
        // over, and the base class clones this field, so it has to be filled in even though every
        // accessor below is overridden.
        outputTypes = STANDARD_OUTPUT_TYPE.clone();
    }

    @Override
    public boolean canEncodeImage(RenderedImage image) {
        return image != null
                && image.getWidth() > 0
                && image.getHeight() > 0
                && WebpCodec.isAvailable();
    }

    @Override
    public boolean canEncodeImage(ImageTypeSpecifier imageType) {
        return imageType != null && WebpCodec.isAvailable();
    }

    @Override
    public ImageWriter createWriterInstance(Object extension) {
        return new WebpImageWriter(this);
    }

    @Override
    public String[] getFormatNames() {
        return NAMES.clone();
    }

    @Override
    public String[] getFileSuffixes() {
        return SUFFIXES.clone();
    }

    @Override
    public String[] getMIMETypes() {
        return MIME_TYPES.clone();
    }

    @Override
    public String getPluginClassName() {
        return CLASS_NAME;
    }

    /**
     * @return the writer this provider creates
     */
    public Class<WebpImageWriter> getWriterClass() {
        return WebpImageWriter.class;
    }

    @Override
    public boolean isStandardStreamMetadataFormatSupported() {
        // A WebP file is a single image with no stream level structure worth reporting.
        return false;
    }

    @Override
    public String getNativeStreamMetadataFormatName() {
        return null;
    }

    @Override
    public boolean isStandardImageMetadataFormatSupported() {
        // Reading metadata back is possible, but writing none is what this writer advertises.
        return false;
    }

    @Override
    public String getNativeImageMetadataFormatName() {
        return null;
    }

    @Override
    public String getVendorName() {
        return VENDOR_NAME;
    }

    @Override
    public String getVersion() {
        return VERSION;
    }

    @Override
    public String getDescription(Locale locale) {
        return "WebP writer backed by libwebp";
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s %s", NAMES[0], VERSION);
    }
}
