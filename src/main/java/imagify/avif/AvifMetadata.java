/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.avif;

import imagify.avif.jna.AvifCodec;

import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import org.w3c.dom.Node;

/**
 * Read only {@link IIOMetadata} implementation holding the AVIF specific properties as well as
 * their translation into the {@code javax_imageio_1.0} standard format.
 */
final class AvifMetadata extends IIOMetadata {

    /** Name of the metadata format that exposes the AVIF specific properties. */
    static final String NATIVE_FORMAT = "imagify_avif_1.0";

    /**
     * The one standard metadata format, spelled out because {@link IIOMetadata} does not expose the
     * constant the other way round in every JDK.
     */
    static final String STANDARD_FORMAT = "javax_imageio_1.0";

    private final IIOMetadataNode nativeRoot;
    private final IIOMetadataNode standardRoot;

    AvifMetadata(AvifImageInfo info) {
        super(true, NATIVE_FORMAT, null, new String[] { STANDARD_FORMAT }, null);
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
        throw new UnsupportedOperationException("AVIF metadata is read only");
    }

    @Override
    public void reset() {
        throw new UnsupportedOperationException("AVIF metadata is read only");
    }

    private static IIOMetadataNode nativeNode(AvifImageInfo info) {
        IIOMetadataNode root = new IIOMetadataNode(NATIVE_FORMAT);
        root.setAttribute("width", Integer.toString(info.width()));
        root.setAttribute("height", Integer.toString(info.height()));
        root.setAttribute("depth", Integer.toString(info.depth()));
        root.setAttribute("yuvFormat", AvifCodec.pixelFormatName(info.yuvFormat()));
        root.setAttribute("yuvRange", AvifCodec.rangeName(info.yuvRange()));
        root.setAttribute("chromaSamplePosition", AvifCodec.chromaSamplePositionName(info.chromaSamplePosition()));
        root.setAttribute("colorPrimaries", AvifCodec.colorPrimariesName(info.colorPrimaries()));
        root.setAttribute("transferCharacteristics", AvifCodec.transferName(info.transferCharacteristics()));
        root.setAttribute("matrixCoefficients", AvifCodec.matrixCoefficientsName(info.matrixCoefficients()));
        root.setAttribute("hasAlpha", Boolean.toString(info.hasAlpha()));
        root.setAttribute("iccSize", Integer.toString(info.iccSize()));
        root.setAttribute("exifSize", Integer.toString(info.exifSize()));
        root.setAttribute("xmpSize", Integer.toString(info.xmpSize()));
        root.setAttribute("rotation", Integer.toString(info.rotationDegrees()));
        root.setAttribute("mirrored", Boolean.toString(info.mirrored()));
        return root;
    }

    private static IIOMetadataNode standardNode(AvifImageInfo info) {
        int channels = info.hasAlpha() ? 4 : 3;

        IIOMetadataNode dimension = new IIOMetadataNode("Dimension");
        dimension.setAttribute("pixelWidth", Integer.toString(info.width()));
        dimension.setAttribute("pixelHeight", Integer.toString(info.height()));
        dimension.setAttribute("horizontalPixelSize", "0");
        dimension.setAttribute("verticalPixelSize", "0");
        dimension.setAttribute("pixelAspectRatio", "1.0");

        IIOMetadataNode data = new IIOMetadataNode("Data");
        data.setAttribute("type", info.hasAlpha() ? "ARGB" : "RGB");
        data.setAttribute("sampleFormat", "unsignedIntegral");
        data.setAttribute("bitsPerSample", "8 8 8 8".substring(0, channels * 2 - 1));
        data.setAttribute("width", Integer.toString(info.width()));
        data.setAttribute("height", Integer.toString(info.height()));
        data.setAttribute("numberOfChannels", Integer.toString(channels));
        String[] names = { "R", "G", "B", "A" };
        for (int channel = 0; channel < channels; channel++) {
            data.setAttribute("channel" + channel,
                    "{name=\"" + names[channel] + "\", bitsPerSample=\"8\", sampleFormat=\"unsignedIntegral\"}");
        }

        IIOMetadataNode transparency = new IIOMetadataNode("Transparency");
        transparency.setAttribute("alpha", info.hasAlpha() ? "nonpremultiplied" : "none");

        IIOMetadataNode root = new IIOMetadataNode(STANDARD_FORMAT);
        root.appendChild(dimension);
        root.appendChild(data);
        root.appendChild(transparency);
        return root;
    }
}
