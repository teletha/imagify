/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.avif.jna;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.util.List;

/**
 * A block of interleaved RGB(A) pixels handed to, or received from, {@code libavif}:
 * {@code avifRGBImage}.
 */
public class AvifRGBImage extends Structure {

    /** Image width in pixels, must match the associated {@link AvifImage}. */
    public int width;
    /** Image height in pixels, must match the associated {@link AvifImage}. */
    public int height;
    /** Bits per channel; 8, 10, 12 or 16. */
    public int depth;
    /** An {@code AVIF_RGB_FORMAT_*} value. */
    public int format;
    /** An {@code AVIF_CHROMA_UPSAMPLING_*} value. */
    public int chromaUpsampling;
    /** An {@code AVIF_CHROMA_DOWNSAMPLING_*} value. */
    public int chromaDownsampling;
    /** An {@code avifBool}; when set, libyuv is bypassed. */
    public int avoidLibYUV;
    /** An {@code avifBool}; when set, alpha bearing formats are treated as opaque. */
    public int ignoreAlpha;
    /** An {@code avifBool}; when set, the colours are already premultiplied. */
    public int alphaPremultiplied;
    /** An {@code avifBool}; when set, 16 bit samples are half floats. */
    public int isFloat;
    /** Number of threads used for YUV to RGB conversion; 0 or less means one. */
    public int maxThreads;
    /** The pixel buffer. */
    public Pointer pixels;
    /** Row stride in bytes. */
    public int rowBytes;

    public AvifRGBImage() {
        super();
    }

    public AvifRGBImage(Pointer peer) {
        super(peer);
    }

    @Override
    protected List<String> getFieldOrder() {
        return List.of(
                "width", "height", "depth", "format",
                "chromaUpsampling", "chromaDownsampling",
                "avoidLibYUV", "ignoreAlpha", "alphaPremultiplied", "isFloat", "maxThreads",
                "pixels", "rowBytes");
    }

    /**
     * @return the total number of bytes described by {@link #rowBytes} and {@link #height}
     */
    public int bufferSize() {
        return rowBytes * height;
    }

    /**
     * @return a Java array holding a copy of the {@link #bufferSize()} pixel bytes
     */
    public byte[] getPixels() {
        read();
        if (pixels == null) {
            return null;
        }
        return pixels.getByteArray(0, bufferSize());
    }
}
