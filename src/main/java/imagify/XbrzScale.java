/*
 * Copyright (C) 2026 Nameless Production Committee
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify;

import java.awt.image.BufferedImage;

/**
 * xBRZ ("Scale by Rules") upscaling implementation based on Zenju's
 * algorithm and the <a href="https://github.com/stanio/xbrz-java">xbrz-java</a> port.
 */
final class XbrzScale {

    private static final double DEFAULT_EQUAL_COLOR_TOLERANCE = 30.0;
    private static final double DEFAULT_CENTER_DIRECTION_BIAS = 4.0;
    private static final double DEFAULT_DOMINANT_DIRECTION_THRESHOLD = 3.6;
    private static final double DEFAULT_STEEP_DIRECTION_THRESHOLD = 2.2;

    private XbrzScale() {}

    static BufferedImage resize(BufferedImage src, int targetW, int targetH) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        int scale = targetW / srcW;

        if (scale < 2 || scale > 6 || srcW * scale != targetW || srcH * scale != targetH) {
            throw new IllegalArgumentException(
                "xBRZ requires target dimensions to be exact integer multiples (2x-6x) of source. " +
                "Got: " + srcW + "x" + srcH + " -> " + targetW + "x" + targetH);
        }

        int[] srcPixels = new int[srcW * srcH];
        src.getRGB(0, 0, srcW, srcH, srcPixels, 0, srcW);

        int[] dstPixels = new int[targetW * targetH];
        new XbrzScaler(scale).scaleImage(srcPixels, dstPixels, srcW, srcH);

        BufferedImage dst = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
        dst.setRGB(0, 0, targetW, targetH, dstPixels, 0, targetW);
        return dst;
    }

    private static class XbrzScaler {
        final int scale, scaleM1, scaleM2, scaleM3, scaleD2, scaleD2P1, scaleD2P2;
        final double equalColorTolerance = DEFAULT_EQUAL_COLOR_TOLERANCE;
        final double centerDirectionBias = DEFAULT_CENTER_DIRECTION_BIAS;
        final double dominantDirectionThreshold = DEFAULT_DOMINANT_DIRECTION_THRESHOLD;
        final double steepDirectionThreshold = DEFAULT_STEEP_DIRECTION_THRESHOLD;
        final ScalerDelegate scaler;

        int[] src;
        int[] dst;
        int srcW, srcH;
        byte[] preProcBuf;
        Kernel4x4 ker4;
        Kernel3x3 ker3;
        OutputMatrix out;
        BlendResult res;

        XbrzScaler(int scale) {
            this.scale = scale;
            this.scaleM1 = scale - 1;
            this.scaleM2 = scale - 2;
            this.scaleM3 = scale - 3;
            this.scaleD2 = scale / 2;
            this.scaleD2P1 = scale / 2 + 1;
            this.scaleD2P2 = scale / 2 + 2;
            this.scaler = ScalerDelegate.forFactor(scale);
        }

        void scaleImage(int[] src, int[] trg, int srcW, int srcH) {
            this.src = src; this.dst = trg; this.srcW = srcW; this.srcH = srcH;
            if (srcW <= 0 || srcH <= 0) return;

            preProcBuf = new byte[srcW];
            ker4 = new Kernel4x4(src, srcW, srcH);
            ker3 = new Kernel3x3(ker4);
            out = new OutputMatrix(scale, trg, srcW * scale);
            res = BlendResult.instance();

            ker4.positionY(-1);
            preProcBuf[0] = BlendInfo.clearAddTopL(preProcessCorners(ker4, res));

            for (int x = 0; x < srcW; x++) {
                ker4.shift(); ker4.readDhlp(x);
                byte blend = preProcessCorners(ker4, res);
                BlendInfo.addTopR(preProcBuf, x, res.blend_j);
                if (x + 1 < srcW) preProcBuf[x + 1] = BlendInfo.clearAddTopL(res.blend_k);
            }

            for (int y = 0; y < srcH; y++) {
                out.positionY(y);
                ker4.positionY(y);

                byte blend_xy1;
                {
                    blend_xy1 = BlendInfo.clearAddTopL(preProcessCorners(ker4, res));
                    BlendInfo.addBottomL(preProcBuf, 0, res.blend_g);
                }

                for (int x = 0; x < srcW; x++, out.incrementX()) {
                    ker4.shift(); ker4.readDhlp(x);

                    byte blend_xy = preProcBuf[x];
                    {
                        blend_xy = BlendInfo.addBottomR(blend_xy, preProcessCorners(ker4, res));
                        blend_xy1 = BlendInfo.addTopR(blend_xy1, res.blend_j);
                        preProcBuf[x] = blend_xy1;
                        if (x + 1 < srcW) {
                            blend_xy1 = BlendInfo.clearAddTopL(res.blend_k);
                            BlendInfo.addBottomL(preProcBuf, x + 1, res.blend_g);
                        }
                    }

                    out.fillBlock(ker4.f());

                    if (BlendInfo.blendingNeeded(blend_xy)) {
                        blendPixel(RotationDegree.ROT_0, ker3, out, blend_xy);
                        blendPixel(RotationDegree.ROT_90, ker3, out, blend_xy);
                        blendPixel(RotationDegree.ROT_180, ker3, out, blend_xy);
                        blendPixel(RotationDegree.ROT_270, ker3, out, blend_xy);
                    }
                }
            }
        }

        private void blendPixel(RotationDegree rotDeg, Kernel3x3 ker, OutputMatrix out, byte blendInfo) {
            byte blend = BlendInfo.rotate(blendInfo, rotDeg);
            if (BlendInfo.getBottomR(blend) >= BlendType.BLEND_NORMAL) {
                ker.rotDeg(rotDeg);

                int e = ker.e(), f = ker.f(), h = ker.h();
                int g = ker.g(), c = ker.c(), i = ker.i();

                boolean doLineBlend;
                if (BlendInfo.getBottomR(blend) >= BlendType.BLEND_DOMINANT)
                    doLineBlend = true;
                else if (BlendInfo.getTopR(blend) != BlendType.BLEND_NONE && !eq(e, g))
                    doLineBlend = false;
                else if (BlendInfo.getBottomL(blend) != BlendType.BLEND_NONE && !eq(e, c))
                    doLineBlend = false;
                else if (!eq(e, i) && eq(g, h) && eq(h, i) && eq(i, f) && eq(f, c))
                    doLineBlend = false;
                else
                    doLineBlend = true;

                int px = dist(e, f) <= dist(e, h) ? f : h;

                if (doLineBlend) {
                    double fg = dist(f, g);
                    double hc = dist(h, c);
                    boolean haveShallow = steepDirectionThreshold * fg <= hc && e != g && ker.d() != g;
                    boolean haveSteep = steepDirectionThreshold * hc <= fg && e != c && ker.b() != c;

                    if (haveShallow) {
                        if (haveSteep) scaler.blendLineSteepAndShallow(px, out);
                        else scaler.blendLineShallow(px, out);
                    } else {
                        if (haveSteep) scaler.blendLineSteep(px, out);
                        else scaler.blendLineDiagonal(px, out);
                    }
                } else {
                    scaler.blendCorner(px, out);
                }
            }
        }

        private double dist(int pix1, int pix2) {
            int r1 = (pix1 >> 16) & 0xFF, g1 = (pix1 >> 8) & 0xFF, b1 = pix1 & 0xFF;
            int r2 = (pix2 >> 16) & 0xFF, g2 = (pix2 >> 8) & 0xFF, b2 = pix2 & 0xFF;
            double y1 = 0.299 * r1 + 0.587 * g1 + 0.114 * b1;
            double y2 = 0.299 * r2 + 0.587 * g2 + 0.114 * b2;
            double cb1 = 128 - 0.168736 * r1 - 0.331264 * g1 + 0.5 * b1;
            double cb2 = 128 - 0.168736 * r2 - 0.331264 * g2 + 0.5 * b2;
            double cr1 = 128 + 0.5 * r1 - 0.418688 * g1 - 0.081312 * b1;
            double cr2 = 128 + 0.5 * r2 - 0.418688 * g2 - 0.081312 * b2;
            return (y1 - y2) * (y1 - y2) + (cb1 - cb2) * (cb1 - cb2) + (cr1 - cr2) * (cr1 - cr2);
        }
        private boolean eq(int pix1, int pix2) { return dist(pix1, pix2) < equalColorTolerance; }

        private byte preProcessCorners(Kernel4x4 ker, BlendResult result) {
            result.reset();
            if ((ker.f() == ker.g() && ker.j() == ker.k()) || (ker.f() == ker.j() && ker.g() == ker.k()))
                return BlendType.BLEND_NONE;

            double jg = dist(ker.i(), ker.f()) + dist(ker.f(), ker.c()) + dist(ker.n(), ker.k()) + dist(ker.k(), ker.h())
                    + centerDirectionBias * dist(ker.j(), ker.g());
            double fk = dist(ker.e(), ker.j()) + dist(ker.j(), ker.o()) + dist(ker.b(), ker.g()) + dist(ker.g(), ker.l())
                    + centerDirectionBias * dist(ker.f(), ker.k());

            if (jg < fk) {
                boolean dominant = dominantDirectionThreshold * jg < fk;
                if (ker.f() != ker.g() && ker.f() != ker.j())
                    result.blend_f = dominant ? BlendType.BLEND_DOMINANT : BlendType.BLEND_NORMAL;
                if (ker.k() != ker.j() && ker.k() != ker.g())
                    result.blend_k = dominant ? BlendType.BLEND_DOMINANT : BlendType.BLEND_NORMAL;
            } else if (fk < jg) {
                boolean dominant = dominantDirectionThreshold * fk < jg;
                if (ker.j() != ker.f() && ker.j() != ker.k())
                    result.blend_j = dominant ? BlendType.BLEND_DOMINANT : BlendType.BLEND_NORMAL;
                if (ker.g() != ker.f() && ker.g() != ker.k())
                    result.blend_g = dominant ? BlendType.BLEND_DOMINANT : BlendType.BLEND_NORMAL;
            }
            return BlendType.BLEND_NONE;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Types
    // ═══════════════════════════════════════════════════════════════

    static final class BlendType {
        static final byte BLEND_NONE = 0;
        static final byte BLEND_NORMAL = 1;
        static final byte BLEND_DOMINANT = 2;
    }

    static final class BlendResult {
        byte blend_f, blend_g, blend_j, blend_k;
        private static final ThreadLocal<BlendResult> INSTANCE = ThreadLocal.withInitial(BlendResult::new);
        static BlendResult instance() { return INSTANCE.get(); }
        void reset() { blend_f = blend_g = blend_j = blend_k = BlendType.BLEND_NONE; }
    }

    static final class BlendInfo {
        static byte rotate(byte b, RotationDegree rotDeg) {
            switch (rotDeg.id) {
                case 90:  return (byte) (((b << 2) & 0xFF) | ((b & 0xFF) >> 6));
                case 180: return (byte) (((b << 4) & 0xFF) | ((b & 0xFF) >> 4));
                case 270: return (byte) (((b << 6) & 0xFF) | ((b & 0xFF) >> 2));
                default:  return b;
            }
        }
        static boolean blendingNeeded(byte b) { return b != BlendType.BLEND_NONE; }
        static byte getTopR(byte b) { return (byte) (0x3 & (b >> 2)); }
        static byte getBottomR(byte b) { return (byte) (0x3 & (b >> 4)); }
        static byte getBottomL(byte b) { return (byte) (0x3 & (b >> 6)); }
        static byte clearAddTopL(byte bt) { return bt; }
        static byte addTopR(byte b, byte bt) { return (byte) (b | (bt << 2)); }
        static byte addBottomR(byte b, byte bt) { return (byte) (b | (bt << 4)); }
        static void clearAddTopL(byte[] buf, int i, byte bt) { buf[i] = bt; }
        static void addTopR(byte[] buf, int i, byte bt) { buf[i] |= bt << 2; }
        static void addBottomL(byte[] buf, int i, byte bt) { buf[i] |= bt << 6; }
    }

    static final class RotationDegree {
        static final RotationDegree ROT_0 = new RotationDegree(0);
        static final RotationDegree ROT_90 = new RotationDegree(90);
        static final RotationDegree ROT_180 = new RotationDegree(180);
        static final RotationDegree ROT_270 = new RotationDegree(270);
        final int id;
        RotationDegree(int id) { this.id = id; }
    }

    static final class Kernel4x4 {
        int a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p;
        private final int[] src;
        private final int srcW, srcH;
        private int s_m1, s_0, s_p1, s_p2;

        Kernel4x4(int[] src, int srcW, int srcH) { this.src = src; this.srcW = srcW; this.srcH = srcH; }
        void positionY(int y) {
            s_m1 = srcW * clamp(y - 1, 0, srcH - 1);
            s_0  = srcW * clamp(y,     0, srcH - 1);
            s_p1 = srcW * clamp(y + 1, 0, srcH - 1);
            s_p2 = srcW * clamp(y + 2, 0, srcH - 1);
            readDhlp(-4); a = d; e = h; i = l; m = p;
            readDhlp(-3); b = d; f = h; j = l; n = p;
            readDhlp(-2); c = d; g = h; k = l; o = p;
            readDhlp(-1);
        }
        void shift() {
            a = b; e = f; i = j; m = n;
            b = c; f = g; j = k; n = o;
            c = d; g = h; k = l; o = p;
        }
        void readDhlp(int x) {
            int xc = clamp(x + 2, 0, srcW - 1);
            d = src[s_m1 + xc]; h = src[s_0 + xc]; l = src[s_p1 + xc]; p = src[s_p2 + xc];
        }
        int f() { return f; }
        int e() { return e; }
        int d() { return d; }
        int b() { return b; }
        int g() { return g; }
        int c() { return c; }
        int i() { return i; }
        int j() { return j; }
        int k() { return k; }
        int h() { return h; }
        int n() { return n; }
        int o() { return o; }
        int l() { return l; }
        int m() { return m; }
        int p() { return p; }
        int a() { return a; }
        private static int clamp(int v, int lo, int hi) {
            return (v < lo) ? lo : (v > hi) ? hi : v;
        }
    }

    static final class Kernel3x3 {
        private final Kernel4x4 ker4;
        private RotationDegree rotDeg;

        Kernel3x3(Kernel4x4 ker4) { this.ker4 = ker4; this.rotDeg = RotationDegree.ROT_0; }
        void rotDeg(RotationDegree deg) { this.rotDeg = deg; }
        int e() { return ker4.e(); }
        int f() { switch (rotDeg.id) { default: return ker4.g(); case 90: return ker4.b(); case 180: return ker4.e(); case 270: return ker4.j(); } }
        int g() { switch (rotDeg.id) { default: return ker4.i(); case 90: return ker4.k(); case 180: return ker4.c(); case 270: return ker4.a(); } }
        int h() { switch (rotDeg.id) { default: return ker4.j(); case 90: return ker4.g(); case 180: return ker4.b(); case 270: return ker4.e(); } }
        int d() { switch (rotDeg.id) { default: return ker4.e(); case 90: return ker4.j(); case 180: return ker4.g(); case 270: return ker4.b(); } }
        int a() { switch (rotDeg.id) { default: return ker4.a(); case 90: return ker4.i(); case 180: return ker4.k(); case 270: return ker4.c(); } }
        int b() { switch (rotDeg.id) { default: return ker4.b(); case 90: return ker4.e(); case 180: return ker4.j(); case 270: return ker4.g(); } }
        int c() { switch (rotDeg.id) { default: return ker4.c(); case 90: return ker4.a(); case 180: return ker4.i(); case 270: return ker4.k(); } }
        int i() { switch (rotDeg.id) { default: return ker4.k(); case 90: return ker4.c(); case 180: return ker4.a(); case 270: return ker4.i(); } }
    }

    static final class OutputMatrix {
        private final int[] trg;
        private final int outRowStride;
        private final int scale;
        private int posY, posX;

        OutputMatrix(int scale, int[] trg, int outRowStride) {
            this.scale = scale; this.trg = trg; this.outRowStride = outRowStride;
        }
        void positionY(int y) { this.posY = y * scale; this.posX = 0; }
        void incrementX() { this.posX++; }
        void fillBlock(int center) {
            int base = posY * outRowStride + posX;
            for (int dy = 0; dy < scale; dy++)
                for (int dx = 0; dx < scale; dx++)
                    trg[(base + dy * outRowStride + dx)] = center;
        }
        void set(int x, int y, int color) {
            int base = (posY + y) * outRowStride + posX + x;
            if (base >= 0 && base < trg.length) trg[base] = color;
        }
        void rotDeg(RotationDegree deg) {}
    }

    interface ScalerDelegate {
        void blendLineShallow(int col, OutputMatrix out);
        void blendLineSteep(int col, OutputMatrix out);
        void blendLineSteepAndShallow(int col, OutputMatrix out);
        void blendLineDiagonal(int col, OutputMatrix out);
        void blendCorner(int col, OutputMatrix out);

        static ScalerDelegate forFactor(int factor) {
            switch (factor) {
                case 2: return new Scaler2x();
                case 3: return new Scaler3x();
                case 4: return new Scaler4x();
                case 5: return new Scaler5x();
                case 6: return new Scaler6x();
                default: throw new IllegalArgumentException("Invalid scale factor: " + factor);
            }
        }
    }

    static class Scaler2x implements ScalerDelegate {
        final int sM1 = 1;
        public void blendLineShallow(int col, OutputMatrix out) {
            out.set(sM1, 0, colorGrad(1, 4, col));
            out.set(sM1, 1, colorGrad(3, 4, col));
        }
        public void blendLineSteep(int col, OutputMatrix out) {
            out.set(0, sM1, colorGrad(1, 4, col));
            out.set(1, sM1, colorGrad(3, 4, col));
        }
        public void blendLineSteepAndShallow(int col, OutputMatrix out) {
            out.set(1, 0, colorGrad(1, 4, col));
            out.set(0, 1, colorGrad(1, 4, col));
            out.set(1, 1, colorGrad(5, 6, col));
        }
        public void blendLineDiagonal(int col, OutputMatrix out) {
            out.set(1, 1, colorGrad(1, 2, col));
        }
        public void blendCorner(int col, OutputMatrix out) {
            out.set(1, 1, colorGrad(21, 100, col));
        }
    }

    static class Scaler3x implements ScalerDelegate {
        final int sM1 = 2, sM2 = 1;
        public void blendLineShallow(int col, OutputMatrix out) {
            out.set(sM1, 0, colorGrad(1, 4, col));
            out.set(sM2, 2, colorGrad(1, 4, col));
            out.set(sM1, 1, colorGrad(3, 4, col));
            out.set(sM1, 2, col);
        }
        public void blendLineSteep(int col, OutputMatrix out) {
            out.set(0, sM1, colorGrad(1, 4, col));
            out.set(2, sM2, colorGrad(1, 4, col));
            out.set(1, sM1, colorGrad(3, 4, col));
            out.set(2, sM1, col);
        }
        public void blendLineSteepAndShallow(int col, OutputMatrix out) {
            out.set(2, 0, colorGrad(1, 4, col));
            out.set(0, 2, colorGrad(1, 4, col));
            out.set(2, 1, colorGrad(3, 4, col));
            out.set(1, 2, colorGrad(3, 4, col));
            out.set(2, 2, col);
        }
        public void blendLineDiagonal(int col, OutputMatrix out) {
            out.set(1, 2, colorGrad(1, 8, col));
            out.set(2, 1, colorGrad(1, 8, col));
            out.set(2, 2, colorGrad(7, 8, col));
        }
        public void blendCorner(int col, OutputMatrix out) {
            out.set(2, 2, colorGrad(45, 100, col));
        }
    }

    static class Scaler4x implements ScalerDelegate {
        final int sM1 = 3, sM2 = 2;
        public void blendLineShallow(int col, OutputMatrix out) {
            out.set(sM1, 0, colorGrad(1, 4, col));
            out.set(sM2, 2, colorGrad(1, 4, col));
            out.set(sM1, 1, colorGrad(3, 4, col));
            out.set(sM2, 3, colorGrad(3, 4, col));
            out.set(sM1, 2, col); out.set(sM1, 3, col);
        }
        public void blendLineSteep(int col, OutputMatrix out) {
            out.set(0, sM1, colorGrad(1, 4, col));
            out.set(2, sM2, colorGrad(1, 4, col));
            out.set(1, sM1, colorGrad(3, 4, col));
            out.set(3, sM2, colorGrad(3, 4, col));
            out.set(2, sM1, col); out.set(3, sM1, col);
        }
        public void blendLineSteepAndShallow(int col, OutputMatrix out) {
            out.set(3, 1, colorGrad(3, 4, col));
            out.set(1, 3, colorGrad(3, 4, col));
            out.set(3, 0, colorGrad(1, 4, col));
            out.set(0, 3, colorGrad(1, 4, col));
            out.set(2, 2, colorGrad(1, 3, col));
            out.set(3, 3, col); out.set(3, 2, col); out.set(2, 3, col);
        }
        public void blendLineDiagonal(int col, OutputMatrix out) {
            out.set(sM1, sM2, colorGrad(1, 2, col));
            out.set(sM2, sM2 + 1, colorGrad(1, 2, col));
            out.set(sM1, sM1, col);
        }
        public void blendCorner(int col, OutputMatrix out) {
            out.set(3, 3, colorGrad(68, 100, col));
            out.set(3, 2, colorGrad(9, 100, col));
            out.set(2, 3, colorGrad(9, 100, col));
        }
    }

    static class Scaler5x implements ScalerDelegate {
        final int sM1 = 4, sM2 = 3, sM3 = 2;
        public void blendLineShallow(int col, OutputMatrix out) {
            out.set(sM1, 0, colorGrad(1, 4, col));
            out.set(sM2, 2, colorGrad(1, 4, col));
            out.set(sM3, 4, colorGrad(1, 4, col));
            out.set(sM1, 1, colorGrad(3, 4, col));
            out.set(sM2, 3, colorGrad(3, 4, col));
            for (int i = 2; i <= 4; i++) out.set(sM1, i, col);
            out.set(sM2, 4, col);
        }
        public void blendLineSteep(int col, OutputMatrix out) {
            out.set(0, sM1, colorGrad(1, 4, col));
            out.set(2, sM2, colorGrad(1, 4, col));
            out.set(4, sM3, colorGrad(1, 4, col));
            out.set(1, sM1, colorGrad(3, 4, col));
            out.set(3, sM2, colorGrad(3, 4, col));
            for (int i = 2; i <= 4; i++) out.set(i, sM1, col);
            out.set(4, sM2, col);
        }
        public void blendLineSteepAndShallow(int col, OutputMatrix out) {
            out.set(0, sM1, colorGrad(1, 4, col));
            out.set(2, sM2, colorGrad(1, 4, col));
            out.set(1, sM1, colorGrad(3, 4, col));
            out.set(3, sM2, colorGrad(3, 4, col));
            out.set(3, 3, colorGrad(2, 3, col));
            for (int i = 2; i <= 4; i++) { out.set(i, sM1, col); out.set(sM1, i, col); }
            out.set(sM1, 2, col); out.set(sM1, 3, col);
        }
        public void blendLineDiagonal(int col, OutputMatrix out) {
            out.set(sM1, sM2, colorGrad(1, 8, col));
            out.set(sM2, sM2 + 1, colorGrad(1, 8, col));
            out.set(sM3, sM2 + 2, colorGrad(1, 8, col));
            out.set(4, 3, colorGrad(7, 8, col));
            out.set(3, 4, colorGrad(7, 8, col));
            out.set(4, 4, col);
        }
        public void blendCorner(int col, OutputMatrix out) {
            out.set(4, 4, colorGrad(86, 100, col));
            out.set(4, 3, colorGrad(23, 100, col));
            out.set(3, 4, colorGrad(23, 100, col));
        }
    }

    static class Scaler6x implements ScalerDelegate {
        final int sM1 = 5, sM2 = 4, sM3 = 3;
        public void blendLineShallow(int col, OutputMatrix out) {
            out.set(sM1, 0, colorGrad(1, 4, col));
            out.set(sM2, 2, colorGrad(1, 4, col));
            out.set(sM3, 4, colorGrad(1, 4, col));
            out.set(sM1, 1, colorGrad(3, 4, col));
            out.set(sM2, 3, colorGrad(3, 4, col));
            out.set(sM3, 5, colorGrad(3, 4, col));
            for (int i = 2; i <= 5; i++) out.set(sM1, i, col);
            out.set(sM2, 4, col); out.set(sM2, 5, col);
        }
        public void blendLineSteep(int col, OutputMatrix out) {
            out.set(0, sM1, colorGrad(1, 4, col));
            out.set(2, sM2, colorGrad(1, 4, col));
            out.set(4, sM3, colorGrad(1, 4, col));
            out.set(1, sM1, colorGrad(3, 4, col));
            out.set(3, sM2, colorGrad(3, 4, col));
            out.set(5, sM3, colorGrad(3, 4, col));
            for (int i = 2; i <= 5; i++) out.set(i, sM1, col);
            out.set(4, sM2, col); out.set(5, sM2, col);
        }
        public void blendLineSteepAndShallow(int col, OutputMatrix out) {
            out.set(0, sM1, colorGrad(1, 4, col));
            out.set(2, sM2, colorGrad(1, 4, col));
            out.set(1, sM1, colorGrad(3, 4, col));
            out.set(3, sM2, colorGrad(3, 4, col));
            out.set(sM1, 0, colorGrad(1, 4, col));
            out.set(sM2, 2, colorGrad(1, 4, col));
            out.set(sM1, 1, colorGrad(3, 4, col));
            out.set(sM2, 3, colorGrad(3, 4, col));
            for (int i = 2; i <= 5; i++) { out.set(i, sM1, col); out.set(sM1, i, col); }
            out.set(4, sM2, col); out.set(5, sM2, col);
            out.set(sM1, 2, col); out.set(sM1, 3, col);
        }
        public void blendLineDiagonal(int col, OutputMatrix out) {
            out.set(sM1, sM2, colorGrad(1, 2, col));
            out.set(sM2, sM2 + 1, colorGrad(1, 2, col));
            out.set(sM3, sM2 + 2, colorGrad(1, 2, col));
            out.set(sM2, sM1, col); out.set(sM1, sM1, col); out.set(sM1, sM2, col);
        }
        public void blendCorner(int col, OutputMatrix out) {
            out.set(5, 5, colorGrad(97, 100, col));
            out.set(4, 5, colorGrad(42, 100, col));
            out.set(5, 4, colorGrad(42, 100, col));
            out.set(5, 3, colorGrad(6, 100, col));
            out.set(3, 5, colorGrad(6, 100, col));
        }
    }

    static int colorGrad(int M, int N, int center) {
        int r = getRed(center), g = getGreen(center), b = getBlue(center);
        return makePixel(calcColor(M, N, r, r), calcColor(M, N, g, g), calcColor(M, N, b, b));
    }
    static int calcColor(int M, int N, int front, int back) {
        return (front * M + back * (N - M)) / N;
    }
    static int makePixel(int r, int g, int b) {
        return (0xFF << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }
    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
    static int getRed(int pix) { return (pix >> 16) & 0xFF; }
    static int getGreen(int pix) { return (pix >> 8) & 0xFF; }
    static int getBlue(int pix) { return pix & 0xFF; }
}
