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
import com.sun.jna.PointerType;
import com.sun.jna.Structure;

import java.util.List;

/**
 * Decoder state: {@code avifDecoder}.
 *
 * <p>{@code libavif} 1.1.0 appended {@code imageSequenceTrackPresent} after {@code data} and
 * 1.2.0 appended {@code imageContentToDecode} after that. Neither member is declared here, so the
 * structure is slightly smaller than the native one; that is harmless because only the leading
 * members - whose offsets are identical in every 1.x release - are ever touched.
 */
public class AvifDecoder extends Structure {

    /** An {@code avifCodecChoice} value. */
    public int codecChoice;
    /** Number of threads used for decoding; must be set before {@code avifDecoderParse}. */
    public int maxThreads;
    /** An {@code avifDecoderSource} value. */
    public int requestedSource;
    /** An {@code avifBool}. */
    public int allowProgressive;
    /** An {@code avifBool}. */
    public int allowIncremental;
    /** An {@code avifBool}; when set, the Exif payload is not extracted. */
    public int ignoreExif;
    /** An {@code avifBool}; when set, the XMP payload is not extracted. */
    public int ignoreXMP;
    /** Maximum number of pixels to decode; 0 means unlimited. */
    public int imageSizeLimit;
    /** Maximum width or height to decode; 0 means unlimited. */
    public int imageDimensionLimit;
    /** Maximum number of images to decode; 0 means unlimited. */
    public int imageCountLimit;
    /** An {@code AVIF_STRICT_*} bit set. */
    public int strictFlags;
    /** The image the decoder currently points at. */
    public AvifImage.ByReference image = new AvifImage.ByReference();
    /** Zero based index of the current image. */
    public int imageIndex;
    /** Total number of images in the file; 1 for still images. */
    public int imageCount;
    /** An {@code avifProgressiveState} value. */
    public int progressiveState;
    /** Timing of the current image. */
    public AvifImageTiming imageTiming = new AvifImageTiming();
    /** Timescale of the media in Hz. */
    public long timescale;
    /** Duration of one playback in seconds. */
    public double duration;
    /** Duration of one playback in {@link #timescale} units. */
    public long durationInTimescales;
    /** Number of repetitions of the sequence. */
    public int repetitionCount;
    /** An {@code avifBool}; output field telling whether an alpha channel was decoded. */
    public int alphaPresent;
    /** I/O statistics. */
    public AvifIOStats ioStats = new AvifIOStats();
    /** Error message buffer. */
    public AvifDiagnostics diag = new AvifDiagnostics();
    /** The reader currently in use. */
    public AvifIO io = new AvifIO();
    /** Opaque codec state owned by {@code libavif}. */
    public Pointer data;

    public AvifDecoder() {
        super();
    }

    public AvifDecoder(Pointer peer) {
        super(peer);
    }

    @Override
    protected List<String> getFieldOrder() {
        return List.of(
                "codecChoice", "maxThreads", "requestedSource",
                "allowProgressive", "allowIncremental", "ignoreExif", "ignoreXMP",
                "imageSizeLimit", "imageDimensionLimit", "imageCountLimit", "strictFlags",
                "image", "imageIndex", "imageCount", "progressiveState",
                "imageTiming", "timescale", "duration", "durationInTimescales", "repetitionCount",
                "alphaPresent", "ioStats", "diag", "io", "data");
    }

    /** Opaque handle for {@code avifIO}; its layout is private to {@code libavif}. */
    public static class AvifIO extends PointerType {
    }
}
