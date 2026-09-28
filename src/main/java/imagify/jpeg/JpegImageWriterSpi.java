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

import imagify.jpeg.jna.JpegliCodec;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;
import java.awt.image.RenderedImage;
import java.util.Locale;

/**
 * Registers {@link JpegImageWriter} with {@code ImageIO}.
 *
 * <p>Encoding needs the native jpegli library. Unlike a format this library owns outright, JPEG is
 * one the JDK already writes: claiming it here without a working library would mean every
 * {@code ImageIO.write()} of a JPEG failed, so {@link #canEncodeImage(RenderedImage)} answers
 * {@code false} whenever the library is unavailable, which leaves the JDK's own writer to serve the
 * image. That makes this provider a replacement rather than an addition, and a JPEG written through
 * it comes out of jpegli rather than out of {@code com.sun.imageio}.
 */
public class JpegImageWriterSpi extends ImageWriterSpi {

    /** Version of this service provider implementation. */
    public static final String VERSION = "0.1";

    /** The one and only compression type this writer supports. */
    public static final String COMPRESSION_TYPE = "JPEG";

    /** Lowest accepted {@link javax.imageio.ImageWriteParam} compression quality. */
    public static final float MINIMUM_QUALITY = 0.0f;

    /** Highest accepted {@link javax.imageio.ImageWriteParam} compression quality. */
    public static final float MAXIMUM_QUALITY = 1.0f;

    private static final String[] NAMES = { "JPEG", "jpeg", "JPG", "jpg" };
    private static final String[] SUFFIXES = { "jpg", "jpeg", "jpe" };
    private static final String[] MIME_TYPES = { "image/jpeg" };
    private static final String CLASS_NAME = "imagify.jpeg.JpegImageWriter";
    private static final String VENDOR_NAME = "https://github.com/teletha/imagify";

    public JpegImageWriterSpi() {
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
                && JpegliCodec.getUnavailableReason() == null;
    }

    @Override
    public boolean canEncodeImage(ImageTypeSpecifier imageType) {
        return imageType != null && JpegliCodec.getUnavailableReason() == null;
    }

    @Override
    public ImageWriter createWriterInstance(Object extension) {
        return new JpegImageWriter(this);
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
    public Class<JpegImageWriter> getWriterClass() {
        return JpegImageWriter.class;
    }

    @Override
    public boolean isStandardStreamMetadataFormatSupported() {
        // A JPEG file is a single image with no stream level structure worth reporting.
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
        return "JPEG writer backed by jpegli";
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s %s", NAMES[0], VERSION);
    }
}
