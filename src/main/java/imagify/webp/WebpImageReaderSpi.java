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

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Locale;

/**
 * Registers {@link WebpImageReader} with {@code ImageIO}.
 *
 * <p>Decoding needs the native {@code libwebp} library, which this jar bundles and unpacks on first
 * use. To keep {@code ImageIO} usable when that library is absent,
 * {@link #canDecodeInput(Object)} answers {@code false} in that case, which makes {@code ImageIO.read()}
 * fall through to the other providers instead of failing hard.
 */
public class WebpImageReaderSpi extends ImageReaderSpi {

    /** Version of this service provider implementation. */
    public static final String VERSION = "0.1";

    private static final String[] NAMES = { "webp", "WEBP", "WebP" };
    private static final String[] SUFFIXES = { "webp" };
    private static final String[] MIME_TYPES = { "image/webp" };
    private static final String CLASS_NAME = "imagify.webp.WebpImageReader";
    private static final String VENDOR_NAME = "https://github.com/teletha/imagify";

    /**
     * The sources this reader accepts.
     *
     * <p>A WebP file has to be read in one go, because the {@code libwebp} entry points take the
     * whole buffer, so a plain {@link InputStream} will not do: a seekable {@link ImageInputStream}
     * is the only accepted source. A {@link File} or {@link URL} reaches the reader through
     * {@code ImageIO.createImageInputStream()} and needs no entry of its own.
     */
    private static final Class<?>[] INPUT_TYPES = { ImageInputStream.class };

    public WebpImageReaderSpi() {
        // ImageReader.setInput() consults getInputTypes() to decide which sources it may hand over,
        // and the base class clones this field, so it has to be filled in even though every accessor
        // below is overridden. Leaving it null makes getInputTypes() itself throw.
        inputTypes = INPUT_TYPES.clone();
    }

    @Override
    public boolean canDecodeInput(Object source) {
        if (source == null || !WebpCodec.isAvailable()) {
            return false;
        }
        if (source instanceof ImageInputStream stream) {
            return canDecode(stream);
        }
        if (source instanceof byte[] bytes) {
            return WebpCodec.isWebP(bytes);
        }
        if (source instanceof InputStream stream) {
            return WebpCodec.isWebP(read(stream));
        }
        if (source instanceof File file) {
            return WebpCodec.isWebP(read(file));
        }
        if (source instanceof URL url) {
            try {
                return WebpCodec.isWebP(read(url.openStream()));
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * @param stream the stream to sniff
     * @return whether the stream starts with a {@code RIFF} container of a WebP file
     */
    private static boolean canDecode(ImageInputStream stream) {
        try {
            long mark = stream.getStreamPosition();
            byte[] head = new byte[WebpCodec.headerLength()];
            int read = 0;
            while (read < head.length) {
                int count = stream.read(head, read, head.length - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
            // The rest of head stays zero filled, which the marker check treats as a truncated file.
            stream.seek(mark);
            return WebpCodec.isWebP(head);
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] read(InputStream stream) {
        try (InputStream in = stream) {
            return in.readNBytes(WebpCodec.headerLength());
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static byte[] read(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return in.readNBytes(WebpCodec.headerLength());
        } catch (IOException e) {
            return new byte[0];
        }
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new WebpImageReader(this);
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
     * @return the reader this provider creates
     */
    public Class<WebpImageReader> getReaderClass() {
        return WebpImageReader.class;
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
        return true;
    }

    @Override
    public String getNativeImageMetadataFormatName() {
        return WebpMetadata.NATIVE_FORMAT;
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
        return "WebP reader backed by libwebp";
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s %s", NAMES[0], VERSION);
    }
}
