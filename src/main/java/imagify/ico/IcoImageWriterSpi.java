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

import java.awt.image.RenderedImage;
import java.util.Locale;

import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;

/**
 * Registers {@link IcoImageWriter} with {@code ImageIO}.
 */
public class IcoImageWriterSpi extends ImageWriterSpi {

    /** Version of this service provider implementation. */
    public static final String VERSION = "0.1";

    private static final String[] NAMES = { "ICO", "ico" };
    private static final String[] SUFFIXES = { "ico" };
    private static final String[] MIME_TYPES = { "image/x-icon", "image/vnd.microsoft.icon" };
    private static final String CLASS_NAME = "imagify.ico.IcoImageWriter";
    private static final String VENDOR_NAME = "https://github.com/teletha/imagify";

    /**
     * {@code ImageIO} builds the provider reflectively, so it needs a public no-argument
     * constructor; the accepted output types are copied in here rather than assigned directly, so
     * that a caller editing the inherited field cannot reach the shared constant through it.
     */
    public IcoImageWriterSpi() {
        outputTypes = STANDARD_OUTPUT_TYPE.clone();
    }

    @Override
    public boolean canEncodeImage(RenderedImage image) {
        return image != null && image.getWidth() > 0 && image.getHeight() > 0;
    }

    @Override
    public boolean canEncodeImage(ImageTypeSpecifier imageType) {
        return imageType != null;
    }

    @Override
    public ImageWriter createWriterInstance(Object extension) {
        return new IcoImageWriter(this);
    }

    @Override public String[] getFormatNames() { return NAMES.clone(); }
    @Override public String[] getFileSuffixes() { return SUFFIXES.clone(); }
    @Override public String[] getMIMETypes() { return MIME_TYPES.clone(); }
    @Override public String getPluginClassName() { return CLASS_NAME; }

    /**
     * The writer class behind this provider, so that a caller holding the provider can reach the
     * class itself without going through {@code ImageIO} a second time.
     *
     * @return the writer this provider creates
     */
    public Class<IcoImageWriter> getWriterClass() { return IcoImageWriter.class; }

    @Override public boolean isStandardStreamMetadataFormatSupported() { return false; }
    @Override public String getNativeStreamMetadataFormatName() { return null; }
    @Override public boolean isStandardImageMetadataFormatSupported() { return false; }
    @Override public String getNativeImageMetadataFormatName() { return null; }
    @Override public String getVendorName() { return VENDOR_NAME; }
    @Override public String getVersion() { return VERSION; }
    @Override public String getDescription(Locale locale) { return "ICO writer"; }
    @Override public String toString() { return String.format(Locale.ROOT, "%s %s", NAMES[0], VERSION); }
}
