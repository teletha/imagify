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

import imagify.webp.ffm.WebpCodec;

import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import org.w3c.dom.Node;

/**
 * Read only {@link IIOMetadata} holding the WebP specific properties as well as their translation
 * into the {@code javax_imageio_1.0} standard format.
 */
final class WebpMetadata extends IIOMetadata {

    /** Name of the metadata format that exposes the WebP specific properties. */
    static final String NATIVE_FORMAT = "imagify_webp_1.0";

    /**
     * How long a frame is shown, in milliseconds.
     *
     * <p>The standard metadata format has no notion of a frame being shown for a while, and neither
     * does WebP's own container in a way a reader can pick up generically, so the duration is
     * published under this name. {@link imagify.ImageReader} looks for exactly this attribute, which
     * is how an animated WebP and an animated AVIF both report their timing.
     */
    static final String DURATION_MS = "durationMs";

    /**
     * How often the sequence repeats, where 0 means forever. This belongs to the file rather than to
     * any one frame, so it is what {@code getStreamMetadata()} publishes.
     */
    static final String REPETITION_COUNT = "repetitionCount";

    /**
     * The one standard metadata format, spelled out because {@link IIOMetadata} does not expose the
     * constant the other way round in every JDK.
     */
    static final String STANDARD_FORMAT = "javax_imageio_1.0";

    private final IIOMetadataNode nativeRoot;
    private final IIOMetadataNode standardRoot;

    /**
     * @param info           the properties of the file, which every frame shares
     * @param durationMs     how long the frame this metadata describes is shown, or 0 for a frame
     *                       with no meaningful timing
     * @param repetitionCount how often the file repeats, where 0 means forever
     */
    WebpMetadata(WebpImageInfo info, int durationMs, int repetitionCount) {
        // The two extra format arrays go together: IIOMetadata rejects a non-null name array with a
        // null class name array. The names are only informational, so a null entry names the
        // default implementation, which is the right answer for the standard format here.
        super(true, NATIVE_FORMAT, null, new String[] { STANDARD_FORMAT }, new String[] { null });
        this.nativeRoot = nativeNode(info, durationMs, repetitionCount);
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
        throw new UnsupportedOperationException("WebP metadata is read only");
    }

    @Override
    public void reset() {
        throw new UnsupportedOperationException("WebP metadata is read only");
    }

    private static IIOMetadataNode nativeNode(WebpImageInfo info, int durationMs, int repetitionCount) {
        IIOMetadataNode root = new IIOMetadataNode(NATIVE_FORMAT);
        root.setAttribute("width", Integer.toString(info.width()));
        root.setAttribute("height", Integer.toString(info.height()));
        root.setAttribute("hasAlpha", Boolean.toString(info.hasAlpha()));
        root.setAttribute("hasAnimation", Boolean.toString(info.hasAnimation()));
        root.setAttribute("format", WebpCodec.formatName(info.format()));
        root.setAttribute("frameCount", Integer.toString(info.frameCount()));
        if (info.hasAnimation()) {
            // Kept under its own name as well, because a caller holding the image metadata of a
            // frame is the only place some of them look, and it costs one attribute to be found
            // either way.
            root.setAttribute("loopCount", Integer.toString(repetitionCount));
        }
        // Only a frame that is actually shown for a while gets a duration. Leaving it off for a
        // still keeps a reader that looks for it from mistaking "no duration" for "shown for no time
        // at all", and a repetition count of 0 already means the one true answer: forever.
        if (durationMs > 0) {
            root.setAttribute(DURATION_MS, Integer.toString(durationMs));
        }
        root.setAttribute(REPETITION_COUNT, Integer.toString(repetitionCount));
        return root;
    }

    private static IIOMetadataNode standardNode(WebpImageInfo info) {
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
