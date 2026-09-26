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
 * Encoder state: {@code avifEncoder}.
 *
 * <p>{@code libavif} 1.1.0 appended {@code headerFormat} after {@code csOptions} and 1.2.0 appended
 * {@code qualityGainMap} after that. Neither member is declared here; all members that are actually
 * configured have identical offsets in every 1.x release.
 */
public class AvifEncoder extends Structure {

    /** An {@code avifCodecChoice} value. */
    public int codecChoice;
    /** Number of threads used for encoding. */
    public int maxThreads;
    /** Codec specific speed, 0 (slowest, best) to 10 (fastest, worst). */
    public int speed;
    /** Distance between two key frames, in frames. */
    public int keyframeInterval;
    /** Timescale of the media in Hz; must be non zero. */
    public long timescale;
    /** Number of times an image sequence is repeated. */
    public int repetitionCount;
    /** Number of extra layers to encode; 0 for a plain still image. */
    public int extraLayerCount;
    /** Overall quality, 0 (worst) to 100 (lossless), or {@link AvifLibrary#AVIF_QUALITY_DEFAULT}. */
    public int quality;
    /** Quality of the alpha plane, same range as {@link #quality}. */
    public int qualityAlpha;
    /** @deprecated since libavif 1.0, use {@link #quality} instead. */
    @Deprecated
    public int minQuantizer;
    /** @deprecated since libavif 1.0, use {@link #quality} instead. */
    @Deprecated
    public int maxQuantizer;
    /** @deprecated since libavif 1.0, use {@link #qualityAlpha} instead. */
    @Deprecated
    public int minQuantizerAlpha;
    /** @deprecated since libavif 1.0, use {@link #qualityAlpha} instead. */
    @Deprecated
    public int maxQuantizerAlpha;
    /** Base 2 logarithm of the number of tile rows. */
    public int tileRowsLog2;
    /** Base 2 logarithm of the number of tile columns. */
    public int tileColsLog2;
    /** An {@code avifBool}; when set, tiling is derived from the image size. */
    public int autoTiling;
    /** Fractional scaling factors. */
    public ScalingMode scalingMode = new ScalingMode();
    /** I/O statistics. */
    public AvifIOStats ioStats = new AvifIOStats();
    /** Error message buffer. */
    public AvifDiagnostics diag = new AvifDiagnostics();
    /** Opaque codec state owned by {@code libavif}. */
    public Pointer data;
    /** Opaque codec specific options owned by {@code libavif}. */
    public Pointer csOptions;

    public AvifEncoder() {
        super();
    }

    public AvifEncoder(Pointer peer) {
        super(peer);
    }

    @Override
    protected List<String> getFieldOrder() {
        return List.of(
                "codecChoice", "maxThreads", "speed", "keyframeInterval", "timescale",
                "repetitionCount", "extraLayerCount",
                "quality", "qualityAlpha",
                "minQuantizer", "maxQuantizer", "minQuantizerAlpha", "maxQuantizerAlpha",
                "tileRowsLog2", "tileColsLog2", "autoTiling", "scalingMode",
                "ioStats", "diag", "data", "csOptions");
    }

    /** {@code avifFraction}: a {@code n / d} ratio. */
    public static class Fraction extends Structure {

        public int n;
        public int d;

        public Fraction() {
            super();
        }

        public Fraction(int n, int d) {
            super();
            this.n = n;
            this.d = d;
        }

        public Fraction(Pointer peer) {
            super(peer);
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("n", "d");
        }
    }

    /** {@code avifScalingMode}: independent horizontal and vertical scaling fractions. */
    public static class ScalingMode extends Structure {

        public Fraction horizontal = new Fraction(1, 1);
        public Fraction vertical = new Fraction(1, 1);

        public ScalingMode() {
            super();
        }

        public ScalingMode(Pointer peer) {
            super(peer);
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("horizontal", "vertical");
        }
    }
}
