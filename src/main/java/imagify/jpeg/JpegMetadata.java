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

import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;

import org.w3c.dom.Node;

/**
 * Read only {@link IIOMetadata} holding the properties a JPEG frame header states, as well as their
 * translation into the {@code javax_imageio_1.0} standard format.
 *
 * <p>The native format carries everything the header says, which is more than the standard format
 * has room for: whether the file is progressive, how finely its colour-difference channels are
 * stored, and how many bits each channel is. None of that is in {@code javax_imageio_1.0}, so a
 * caller that wants it reads it from here.
 *
 * <p>The standard tree reports the same geometry and resolution the JDK's own JPEG reader reports, so
 * a caller that only asks "how big is this and at what resolution" gets the same answer whichever
 * provider served the file.
 */
final class JpegMetadata extends IIOMetadata {

    /** Name of the metadata format that exposes the JPEG frame header properties. */
    static final String NATIVE_FORMAT = "imagify_jpeg_1.0";

    /**
     * The one standard metadata format, spelled out because {@link IIOMetadata} does not expose the
     * constant the other way round in every JDK.
     */
    static final String STANDARD_FORMAT = "javax_imageio_1.0";

    /** Marker segment name of a baseline sequential frame header, which is what this library writes. */
    private static final String SOF0 = "SOF0";

    /** Marker segment name of a progressive frame header. */
    private static final String SOF2 = "SOF2";

    private final IIOMetadataNode nativeRoot;
    private final IIOMetadataNode standardRoot;

    /**
     * @param info the properties of the frame header
     */
    JpegMetadata(JpegImageInfo info) {
        // The two extra format arrays go together: IIOMetadata rejects a non-null name array with a
        // null class name array. The names are only informational, so a null entry names the
        // default implementation, which is the right answer for the standard format here.
        super(true, NATIVE_FORMAT, null, new String[] { STANDARD_FORMAT }, new String[] { null });
        this.nativeRoot = nativeNode(info);
        this.standardRoot = standardNode(info);
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Node getAsTree(String format) {
        String name = format == null ? getNativeMetadataFormatName() : format;
        return switch (name) {
            case NATIVE_FORMAT -> nativeRoot;
            case STANDARD_FORMAT -> standardRoot;
            default -> throw new IllegalArgumentException("unknown metadata format: " + format);
        };
    }

    @Override
    public void mergeTree(String format, Node node) {
        throw new UnsupportedOperationException("JPEG metadata is read only");
    }

    @Override
    public void reset() {
        throw new UnsupportedOperationException("JPEG metadata is read only");
    }

    private static IIOMetadataNode nativeNode(JpegImageInfo info) {
        IIOMetadataNode root = new IIOMetadataNode(NATIVE_FORMAT);
        root.setAttribute("width", Integer.toString(info.width()));
        root.setAttribute("height", Integer.toString(info.height()));
        root.setAttribute("numberOfChannels", Integer.toString(info.components()));
        root.setAttribute("dataPrecision", Integer.toString(info.precision()));
        root.setAttribute("progressive", Boolean.toString(info.progressive()));
        root.setAttribute("greyscale", Boolean.toString(info.greyscale()));
        root.setAttribute("subsampling", subsamplingName(info));
        root.setAttribute("horizontalSamplingFactor", Integer.toString(info.horizontalFactor()));
        root.setAttribute("verticalSamplingFactor", Integer.toString(info.verticalFactor()));
        root.setAttribute("densityUnit", densityUnitName(info.densityUnit()));
        root.setAttribute("horizontalPixelDensity", Integer.toString(info.horizontalDensity()));
        root.setAttribute("verticalPixelDensity", Integer.toString(info.verticalDensity()));
        return root;
    }

    private static IIOMetadataNode standardNode(JpegImageInfo info) {
        IIOMetadataNode imageSize = new IIOMetadataNode("ImageSize");
        imageSize.setAttribute("width", Integer.toString(info.width()));
        imageSize.setAttribute("height", Integer.toString(info.height()));

        // The standard format measures a pixel in millimetres, while a JPEG states its resolution in
        // dots per inch or per centimetre. A file with no resolution at all leaves both at 0, which
        // is how the format says "unknown" rather than "zero sized".
        int horizontalDpi = info.horizontalDensityDpi();
        int verticalDpi = info.verticalDensityDpi();
        IIOMetadataNode pixelSize = new IIOMetadataNode("PixelSize");
        pixelSize.setAttribute("horizontalPixelSize", millimetres(horizontalDpi));
        pixelSize.setAttribute("verticalPixelSize", millimetres(verticalDpi));
        pixelSize.setAttribute("pixelAspectRatio", aspectRatio(horizontalDpi, verticalDpi));

        IIOMetadataNode dimension = new IIOMetadataNode("Dimension");
        dimension.appendChild(imageSize);
        dimension.appendChild(pixelSize);

        IIOMetadataNode data = new IIOMetadataNode("Data");
        data.setAttribute("type", info.greyscale() ? "Grayscale" : "YCbCr");
        data.setAttribute("sampleFormat", "unsignedIntegral");
        data.setAttribute("bitsPerSample", Integer.toString(info.precision()));
        data.setAttribute("width", Integer.toString(info.width()));
        data.setAttribute("height", Integer.toString(info.height()));
        data.setAttribute("numberOfChannels", Integer.toString(info.components()));

        IIOMetadataNode transparency = new IIOMetadataNode("Transparency");
        // A JPEG has no alpha channel at all, which is different from having one that is opaque.
        transparency.setAttribute("alpha", "none");

        IIOMetadataNode sequence = new IIOMetadataNode("MarkerSequence");
        IIOMetadataNode segment = new IIOMetadataNode("MarkerSegment");
        segment.setAttribute("MarkerTag", info.progressive() ? SOF2 : SOF0);
        segment.setAttribute("Length", Integer.toString(8 + 3 * info.components()));
        sequence.appendChild(segment);

        IIOMetadataNode root = new IIOMetadataNode(STANDARD_FORMAT);
        root.appendChild(dimension);
        root.appendChild(data);
        root.appendChild(transparency);
        root.appendChild(sequence);
        return root;
    }

    /**
     * @param dpi the resolution in dots per inch
     * @return the size of one pixel in millimetres, or {@code "0"} when the resolution is unknown
     */
    private static String millimetres(int dpi) {
        return dpi > 0 ? String.valueOf(25.4f / dpi) : "0";
    }

    /**
     * @param horizontalDpi the horizontal resolution in dots per inch
     * @param verticalDpi the vertical resolution in dots per inch
     * @return the ratio between the two pixel sizes, or {@code "1.0"} when either is unknown
     */
    private static String aspectRatio(int horizontalDpi, int verticalDpi) {
        if (horizontalDpi <= 0 || verticalDpi <= 0) {
            return "1.0";
        }
        return String.valueOf((float) verticalDpi / horizontalDpi);
    }

    /**
     * The sampling factors of a frame header, spelled the way they are usually written down.
     *
     * @param info the frame header properties
     * @return for example {@code "4:2:0"}, or {@code "unknown"} for a factor outside 1 and 2
     */
    static String subsamplingName(JpegImageInfo info) {
        if (info.components() == 1) {
            // A greyscale file has no colour-difference channels to subsample, so the question does
            // not arise rather than having an answer of its own.
            return "none";
        }
        int horizontal = info.horizontalFactor();
        int vertical = info.verticalFactor();
        if (horizontal < 1 || horizontal > 2 || vertical < 1 || vertical > 2) {
            return "unknown";
        }
        return (4 / horizontal) + ":" + 4 / vertical + ":" + 4;
    }

    /**
     * @param densityUnit one of the {@code JpegImageInfo.DENSITY_UNIT_*} values
     * @return the symbolic name, which is what a caller reads rather than a bare number
     */
    static String densityUnitName(int densityUnit) {
        return switch (densityUnit) {
            case JpegImageInfo.DENSITY_UNIT_ASPECT_RATIO -> "none";
            case JpegImageInfo.DENSITY_UNIT_DOTS_PER_INCH -> "dotsPerInch";
            case JpegImageInfo.DENSITY_UNIT_DOTS_PER_CENTIMETRE -> "dotsPerCentimetre";
            default -> "Unknown(" + densityUnit + ")";
        };
    }
}
