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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Locale;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

/**
 * Registers {@link IcoImageReader} with {@code ImageIO}.
 */
public class IcoImageReaderSpi extends ImageReaderSpi {

    /** Version of this service provider implementation. */
    public static final String VERSION = "0.1";

    private static final String[] NAMES = { "ICO", "ico" };
    private static final String[] SUFFIXES = { "ico" };
    private static final String[] MIME_TYPES = { "image/x-icon", "image/vnd.microsoft.icon" };
    private static final String CLASS_NAME = "imagify.ico.IcoImageReader";
    private static final String VENDOR_NAME = "https://github.com/teletha/imagify";
    private static final Class<?>[] INPUT_TYPES = { ImageInputStream.class };

    /**
     * {@code ImageIO} builds the provider reflectively, so it needs a public no-argument
     * constructor; the accepted input types are copied in here rather than assigned directly, so
     * that a caller editing the inherited field cannot reach the shared constant through it.
     */
    public IcoImageReaderSpi() {
        inputTypes = INPUT_TYPES.clone();
    }

    @Override
    public boolean canDecodeInput(Object source) {
        if (source == null) {
            return false;
        }
        if (source instanceof ImageInputStream stream) {
            return canDecode(stream);
        }
        if (source instanceof byte[] bytes) {
            return bytes.length >= 6
                    && bytes[0] == 0 && bytes[1] == 0
                    && bytes[2] == 1 && bytes[3] == 0;
        }
        byte[] head;
        if (source instanceof InputStream stream) {
            head = readHead(stream);
        } else if (source instanceof File file) {
            head = readHead(file);
        } else if (source instanceof URL url) {
            try {
                head = readHead(url.openStream());
            } catch (IOException e) {
                return false;
            }
        } else {
            return false;
        }
        return head.length >= 6
                && head[0] == 0 && head[1] == 0
                && head[2] == 1 && head[3] == 0;
    }

    private static boolean canDecode(ImageInputStream stream) {
        try {
            long mark = stream.getStreamPosition();
            int r1 = stream.readShort(); // reserved
            int type = stream.readUnsignedShort(); // type = 1 for ICO
            int count = stream.readUnsignedShort();
            stream.seek(mark);
            return r1 == 0 && type == 1 && count > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] readHead(InputStream stream) {
        try (InputStream in = stream) {
            byte[] b = new byte[6];
            int n = in.read(b);
            return n == 6 ? b : new byte[0];
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static byte[] readHead(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] b = new byte[6];
            int n = in.read(b);
            return n == 6 ? b : new byte[0];
        } catch (IOException e) {
            return new byte[0];
        }
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new IcoImageReader(this);
    }

    @Override public String[] getFormatNames() { return NAMES.clone(); }
    @Override public String[] getFileSuffixes() { return SUFFIXES.clone(); }
    @Override public String[] getMIMETypes() { return MIME_TYPES.clone(); }
    @Override public String getPluginClassName() { return CLASS_NAME; }

    /**
     * The reader class behind this provider, so that a caller holding the provider can reach the
     * class itself without going through {@code ImageIO} a second time.
     *
     * @return the reader this provider creates
     */
    public Class<IcoImageReader> getReaderClass() { return IcoImageReader.class; }

    @Override public boolean isStandardStreamMetadataFormatSupported() { return false; }
    @Override public String getNativeStreamMetadataFormatName() { return null; }
    @Override public boolean isStandardImageMetadataFormatSupported() { return false; }
    @Override public String getNativeImageMetadataFormatName() { return null; }
    @Override public String getVendorName() { return VENDOR_NAME; }
    @Override public String getVersion() { return VERSION; }
    @Override public String getDescription(Locale locale) { return "ICO reader"; }
    @Override public String toString() { return String.format(Locale.ROOT, "%s %s", NAMES[0], VERSION); }
}
