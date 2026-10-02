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
import java.util.Locale;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;

import imagify.avif.ffm.AvifCodec;

/**
 * Registers {@link AvifImageWriter} with {@code ImageIO}.
 *
 * <p>Encoding needs the native {@code libavif} library. To keep {@code ImageIO} usable when that
 * library is absent, {@link #canEncodeImage(RenderedImage)} answers {@code false} in that case, which
 * makes {@code ImageIO.write()} return {@code false} instead of failing hard.
 */
public class AvifImageWriterSpi extends ImageWriterSpi {

    /** Version of this service provider implementation. */
    public static final String VERSION = "0.1";

    /** The one and only compression type this writer supports. */
    public static final String COMPRESSION_TYPE = "AVIF";

    /** Lowest accepted {@link javax.imageio.ImageWriteParam} compression quality. */
    public static final float MINIMUM_QUALITY = 0.0f;

    /** Highest accepted {@link javax.imageio.ImageWriteParam} compression quality. */
    public static final float MAXIMUM_QUALITY = 1.0f;

    private static final String[] NAMES = { "AVIF", "avif" };
    private static final String[] SUFFIXES = { "avif" };
    private static final String[] MIME_TYPES = { "image/avif" };
    private static final String CLASS_NAME = "imagify.avif.AvifImageWriter";
    private static final String VENDOR_NAME = "https://github.com/teletha/imagify";

    /**
     * Creates the provider for {@code ImageIO}, which instantiates it reflectively and therefore
     * needs a public no-argument constructor here rather than a factory.
     */
    public AvifImageWriterSpi() {
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
                && AvifCodec.getUnavailableReason() == null;
    }

    @Override
    public boolean canEncodeImage(ImageTypeSpecifier imageType) {
        return imageType != null && AvifCodec.getUnavailableReason() == null;
    }

    @Override
    public ImageWriter createWriterInstance(Object extension) {
        return new AvifImageWriter(this);
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
     * The writer class behind this provider, so that a caller holding the provider can reach the
     * class itself without going through {@code ImageIO} a second time.
     *
     * @return the writer this provider creates
     */
    public Class<AvifImageWriter> getWriterClass() {
        return AvifImageWriter.class;
    }

    @Override
    public boolean isStandardStreamMetadataFormatSupported() {
        // An AVIF file is a single image with no stream level structure worth reporting.
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
        return "AVIF writer backed by libavif";
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s %s", NAMES[0], VERSION);
    }
}
