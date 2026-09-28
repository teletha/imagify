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
 * Registers {@link JpegImageReader} with {@code ImageIO}.
 *
 * <p>Decoding needs the native jpegli library. Unlike a format this library owns outright, JPEG is
 * one the JDK already reads: claiming it here without a working library would mean every
 * {@code ImageIO.read()} of a JPEG failed, so {@link #canDecodeInput(Object)} answers {@code false}
 * whenever the library is unavailable, which leaves the JDK's own reader to serve the file. That
 * makes this provider a replacement rather than an addition, and a JPEG decoded through it goes
 * through jpegli rather than through {@code com.sun.imageio}.
 */
public class JpegImageReaderSpi extends ImageReaderSpi {

    /** Version of this service provider implementation. */
    public static final String VERSION = "0.1";

    /**
     * The two bytes every JPEG file starts with, the start of image marker.
     *
     * <p>A JPEG has no other signature worth trusting: the marker that follows may be an
     * application segment holding a thumbnail or a colour profile rather than a frame header, and a
     * file may carry any amount of padding before the first real one. The two byte test is
     * therefore all the JDK's own reader does too, and this provider gets no more selective than the
     * one it stands in for.
     */
    private static final int SNIFF_LENGTH = 2;

    private static final String[] NAMES = { "JPEG", "jpeg", "JPG", "jpg" };
    private static final String[] SUFFIXES = { "jpg", "jpeg", "jpe" };
    private static final String[] MIME_TYPES = { "image/jpeg" };
    private static final String CLASS_NAME = "imagify.jpeg.JpegImageReader";
    private static final String VENDOR_NAME = "https://github.com/teletha/imagify";

    /**
     * The sources this reader accepts.
     *
     * <p>Every source an {@link ImageInputStream} can be made from is listed, because JPEG is a
     * format callers hand to {@code ImageIO} in every shape it accepts: a file, a URL, a byte array,
     * a plain stream and the stream itself. A {@link File} or {@link URL} reaches the reader through
     * {@code ImageIO.createImageInputStream()} and needs no entry of its own.
     */
    private static final Class<?>[] INPUT_TYPES = {
            ImageInputStream.class, File.class, InputStream.class, URL.class };

    public JpegImageReaderSpi() {
        // ImageReader.setInput() consults getInputTypes() to decide which sources it may hand over,
        // and the base class clones this field, so it has to be filled in even though every
        // accessor below is overridden. Leaving it null makes getInputTypes() itself throw.
        inputTypes = INPUT_TYPES.clone();
    }

    @Override
    public boolean canDecodeInput(Object source) {
        if (source == null || JpegliCodec.getUnavailableReason() != null) {
            return false;
        }
        if (source instanceof ImageInputStream stream) {
            return canDecode(stream);
        }
        if (source instanceof byte[] bytes) {
            return isJpeg(bytes, 0);
        }
        if (source instanceof File file) {
            return isJpeg(read(file), 0);
        }
        if (source instanceof URL url) {
            try {
                return isJpeg(read(url.openStream()), 0);
            } catch (IOException e) {
                return false;
            }
        }
        if (source instanceof InputStream stream) {
            return isJpeg(read(stream), 0);
        }
        return false;
    }

    /**
     * @param stream the stream to sniff
     * @return whether the stream starts with a start of image marker
     */
    private static boolean canDecode(ImageInputStream stream) {
        try {
            long mark = stream.getStreamPosition();
            byte[] head = new byte[SNIFF_LENGTH];
            int read = 0;
            while (read < SNIFF_LENGTH) {
                int count = stream.read(head, read, SNIFF_LENGTH - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
            // The rest of head stays zero filled, which a one byte file fails the test on.
            stream.seek(mark);
            return isJpeg(head, 0);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * @param data the leading bytes of a file
     * @param offset where in them the file starts
     * @return whether they start with a start of image marker
     */
    static boolean isJpeg(byte[] data, int offset) {
        return data != null && data.length - offset >= SNIFF_LENGTH
                && (data[offset] & 0xFF) == 0xFF && (data[offset + 1] & 0xFF) == 0xD8;
    }

    private static byte[] read(InputStream stream) {
        try (InputStream in = stream) {
            return in.readNBytes(SNIFF_LENGTH);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static byte[] read(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return in.readNBytes(SNIFF_LENGTH);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new JpegImageReader(this);
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
    public Class<JpegImageReader> getReaderClass() {
        return JpegImageReader.class;
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
        return true;
    }

    @Override
    public String getNativeImageMetadataFormatName() {
        return JpegMetadata.NATIVE_FORMAT;
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
        return "JPEG reader backed by jpegli";
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s %s", NAMES[0], VERSION);
    }
}
