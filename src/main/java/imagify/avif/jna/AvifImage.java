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
 * A decoded or to be encoded AVIF image: {@code avifImage}.
 *
 * <p>All C enumerations are mapped to {@code int}, which is how {@code libavif} declares them
 * ({@code typedef enum { ... } avifPixelFormat;} and friends).
 */
public class AvifImage extends Structure {

    /** Image width in pixels. */
    public int width;
    /** Image height in pixels. */
    public int height;
    /** Bits per channel; all planes share this depth. */
    public int depth;
    /** An {@code AVIF_PIXEL_FORMAT_*} value. */
    public int yuvFormat;
    /** An {@code AVIF_RANGE_*} value. */
    public int yuvRange;
    /** An {@code AVIF_CHROMA_SAMPLE_POSITION_*} value. */
    public int yuvChromaSamplePosition;
    /** The three YUV planes. */
    public Pointer[] yuvPlanes = new Pointer[AvifLibrary.AVIF_PLANE_COUNT_YUV];
    /** Row stride of each YUV plane in bytes. */
    public int[] yuvRowBytes = new int[AvifLibrary.AVIF_PLANE_COUNT_YUV];
    /** An {@code avifBool} telling whether {@link #yuvPlanes} has to be freed by us. */
    public int imageOwnsYUVPlanes;
    /** The alpha plane, or {@code null}. */
    public Pointer alphaPlane;
    /** Row stride of the alpha plane in bytes. */
    public int alphaRowBytes;
    /** An {@code avifBool} telling whether {@link #alphaPlane} has to be freed by us. */
    public int imageOwnsAlphaPlane;
    /** An {@code avifBool} telling whether the alpha channel is already premultiplied. */
    public int alphaPremultiplied;
    /** Embedded ICC profile, if any. */
    public AvifRWData icc = new AvifRWData();
    /** An {@code AVIF_COLOR_PRIMARIES_*} value. */
    public int colorPrimaries;
    /** An {@code AVIF_TRANSFER_CHARACTERISTICS_*} value. */
    public int transferCharacteristics;
    /** An {@code AVIF_MATRIX_COEFFICIENTS_*} value. */
    public int matrixCoefficients;
    /** Content light level information. */
    public ContentLightLevelInformationBox clli = new ContentLightLevelInformationBox();
    /** An {@code AVIF_TRANSFORM_*} bit set. */
    public int transformFlags;
    /** Pixel aspect ratio. */
    public PixelAspectRatioBox pasp = new PixelAspectRatioBox();
    /** Clean aperture (cropping) rectangle. */
    public CleanApertureBox clap = new CleanApertureBox();
    /** Rotation transform. */
    public ImageRotation irot = new ImageRotation();
    /** Mirror transform. */
    public ImageMirror imir = new ImageMirror();
    /** Embedded Exif payload, if any. */
    public AvifRWData exif = new AvifRWData();
    /** Embedded XMP payload, if any. */
    public AvifRWData xmp = new AvifRWData();

    public AvifImage() {
        super();
    }

    public AvifImage(Pointer peer) {
        super(peer);
    }

    @Override
    protected List<String> getFieldOrder() {
        return List.of(
                "width", "height", "depth",
                "yuvFormat", "yuvRange", "yuvChromaSamplePosition",
                "yuvPlanes", "yuvRowBytes", "imageOwnsYUVPlanes",
                "alphaPlane", "alphaRowBytes", "imageOwnsAlphaPlane", "alphaPremultiplied",
                "icc",
                "colorPrimaries", "transferCharacteristics", "matrixCoefficients",
                "clli", "transformFlags", "pasp", "clap", "irot", "imir",
                "exif", "xmp");
    }

    /**
     * @return {@code true} when this image carries an alpha channel
     */
    public boolean hasAlpha() {
        read();
        return alphaPlane != null;
    }

    /**
     * View on an {@code avifImage} that is owned by somebody else, for example
     * {@code avifDecoder.image}. JNA shares the parent's native memory, so the fields can be read
     * and written directly once the parent structure has been read.
     */
    public static class ByReference extends AvifImage implements Structure.ByReference {

        public ByReference() {
            super();
        }

        public ByReference(Pointer peer) {
            super(peer);
        }
    }

    /** {@code avifImageRotation}: a rotation of {@code angle * 90} degrees counter clockwise. */
    public static class ImageRotation extends Structure {

        /** Legal values are 0 to 3. */
        public byte angle;

        public ImageRotation() {
            super();
        }

        public ImageRotation(Pointer peer) {
            super(peer);
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("angle");
        }
    }

    /** {@code avifImageMirror}: the axis an image is mirrored along. */
    public static class ImageMirror extends Structure {

        /** {@code 0} means the vertical axis, {@code 1} the horizontal one. */
        public byte axis;

        public ImageMirror() {
            super();
        }

        public ImageMirror(Pointer peer) {
            super(peer);
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("axis");
        }
    }

    /** {@code avifContentLightLevelInformationBox} (the {@code clli} box). */
    public static class ContentLightLevelInformationBox extends Structure {

        public short maxCLL;
        public short maxPALL;

        public ContentLightLevelInformationBox() {
            super();
        }

        public ContentLightLevelInformationBox(Pointer peer) {
            super(peer);
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("maxCLL", "maxPALL");
        }
    }

    /** {@code avifPixelAspectRatioBox} (the {@code pasp} box). */
    public static class PixelAspectRatioBox extends Structure {

        public int hSpacing;
        public int vSpacing;

        public PixelAspectRatioBox() {
            super();
        }

        public PixelAspectRatioBox(Pointer peer) {
            super(peer);
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("hSpacing", "vSpacing");
        }
    }

    /** {@code avifCleanApertureBox} (the {@code clap} box). */
    public static class CleanApertureBox extends Structure {

        public int widthN;
        public int widthD;
        public int heightN;
        public int heightD;
        public int horizOffN;
        public int horizOffD;
        public int vertOffN;
        public int vertOffD;

        public CleanApertureBox() {
            super();
        }

        public CleanApertureBox(Pointer peer) {
            super(peer);
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("widthN", "widthD", "heightN", "heightD", "horizOffN", "horizOffD", "vertOffN", "vertOffD");
        }
    }
}
