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

import imagify.webp.WebpCodec;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Reads images from various sources with automatic format detection.
 *
 * <p>Supported sources: {@code byte[]}, {@code Path}, {@code InputStream}.</p>
 *
 * <p>Format detection priority:
 * <ol>
 *   <li>Explicit {@link ImageFormat} parameter</li>
 *   <li>File extension</li>
 *   <li>Magic byte header</li>
 * </ol></p>
 *
 * <p>Returns a {@link FrameSequence} which contains one or more frames.
 * For single-image formats (JPEG, PNG, BMP), a single-frame sequence is returned.
 * For animated formats (GIF, animated WebP), all frames and their delays are
 * automatically extracted.</p>
 *
 * <p>GIF {@code delayTime} is automatically converted from centiseconds to
 * milliseconds.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * FrameSequence seq = ImageReader.read(path);
 * BufferedImage firstFrame = seq.toBufferedImage();
 * List<BufferedImage> allFrames = seq.frames();
 * int[] delays = seq.delaysMs();
 * }</pre>
 */
public final class ImageReader {

    private ImageReader() {}

    /**
     * Reads an image from a byte array with auto-detected format.
     *
     * @param data the encoded image data
     * @return a {@link FrameSequence} containing one or more frames
     * @throws IOException if the data cannot be read
     */
    public static FrameSequence read(byte[] data) throws IOException {
        return read(data, detectFormat(data));
    }

    /**
     * Reads an image from a byte array with explicit format.
     *
     * @param data   the encoded image data
     * @param format the format to use
     * @return a {@link FrameSequence} containing one or more frames
     * @throws IOException if the data cannot be read
     */
    public static FrameSequence read(byte[] data, ImageFormat format) throws IOException {
        return readArray(data, format);
    }

    /**
     * Reads an image from a file path with auto-detected format.
     *
     * @param path the file path
     * @return a {@link FrameSequence} containing one or more frames
     * @throws IOException if the file cannot be read
     */
    public static FrameSequence read(Path path) throws IOException {
        ImageFormat format = ImageFormat.fromPath(path);
        ImageInputStream stream = ImageIO.createImageInputStream(path.toFile());
        try {
            return readFromStream(stream, format);
        } finally {
            try { stream.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Reads an image from a file path with explicit format.
     *
     * @param path   the file path
     * @param format the format to use
     * @return a {@link FrameSequence} containing one or more frames
     * @throws IOException if the file cannot be read
     */
    public static FrameSequence read(Path path, ImageFormat format) throws IOException {
        ImageInputStream stream = ImageIO.createImageInputStream(path.toFile());
        try {
            return readFromStream(stream, format);
        } finally {
            try { stream.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Reads an image from an InputStream with auto-detected format.
     *
     * @param in the input stream
     * @return a {@link FrameSequence} containing one or more frames
     * @throws IOException if the data cannot be read
     */
    public static FrameSequence read(InputStream in) throws IOException {
        byte[] data = in.readAllBytes();
        return read(data);
    }

    /**
     * Reads an image from an InputStream with explicit format.
     *
     * @param in     the input stream
     * @param format the format to use
     * @return a {@link FrameSequence} containing one or more frames
     * @throws IOException if the data cannot be read
     */
    public static FrameSequence read(InputStream in, ImageFormat format) throws IOException {
        return readArray(in.readAllBytes(), format);
    }

    private static FrameSequence readArray(byte[] data, ImageFormat format) throws IOException {
        ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data));
        if (stream == null) {
            throw new IOException("no ImageInputStream provider accepted the encoded data");
        }
        try {
            return readFromStream(stream, format);
        } finally {
            try { stream.close(); } catch (IOException ignored) {}
        }
    }

    private static FrameSequence readFromStream(ImageInputStream stream, ImageFormat format)
            throws IOException {
        Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReadersByFormatName(format.getFormatName());
        if (!readers.hasNext()) {
            throw new IOException("no ImageReader for format: " + format.getFormatName());
        }
        javax.imageio.ImageReader reader = readers.next();
        try {
            reader.setInput(stream, false, true);
            int numFrames;
            try {
                numFrames = reader.getNumImages(true);
            } catch (Exception e) {
                // Reader doesn't support multi-frame; treat as single image
                BufferedImage frame = reader.read(0);
                return new FrameSequence(List.of(frame), new int[]{1000}, 0);
            }
            var frames = new ArrayList<BufferedImage>(numFrames);
            int[] delaysMs = new int[numFrames];
            for (int i = 0; i < numFrames; i++) {
                frames.add(reader.read(i));
                delaysMs[i] = readFrameDelay(reader.getImageMetadata(i));
            }
            int loopCount = readLoopCount(format, reader.getStreamMetadata());
            return new FrameSequence(frames, delaysMs, loopCount);
        } finally {
            reader.dispose();
        }
    }

    private static int readFrameDelay(IIOMetadata metadata) {
        if (metadata == null) return 1000;
        for (String name : metadata.getMetadataFormatNames()) {
            var node = metadata.getAsTree(name);
            int delay = extractDelay(node);
            if (delay > 0) return delay;
        }
        return 1000;
    }

    private static int extractDelay(org.w3c.dom.Node node) {
        if (node == null) return 0;
        if ("GraphicControlExtension".equals(node.getNodeName())) {
            var attr = node.getAttributes().getNamedItem("delayTime");
            if (attr != null) {
                try {
                    // GIF delayTime is in centiseconds (1/100 second); convert to ms
                    return Integer.parseInt(attr.getNodeValue()) * 10;
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        // The standard metadata format has no notion of a frame being shown for a while, so a format
        // whose reader has no GIF style control extension publishes the duration under this name
        // instead. An animated AVIF is read this way.
        var duration = node.getAttributes().getNamedItem("durationMs");
        if (duration != null) {
            try {
                return Integer.parseInt(duration.getNodeValue());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        for (int i = 0; i < node.getChildNodes().getLength(); i++) {
            int delay = extractDelay(node.getChildNodes().item(i));
            if (delay > 0) return delay;
        }
        return 0;
    }

    private static int readLoopCount(ImageFormat format, IIOMetadata streamMetadata) {
        if (streamMetadata == null) return 0;
        // A reader that reports the repetition count directly wins over digging it out of the GIF
        // application extension below, which is the only place it is recorded for GIF itself.
        int declared = readDeclaredAttribute(streamMetadata, "repetitionCount");
        if (declared > 0) return declared;
        if (!format.equals(ImageFormat.GIF)) return 0;
        try {
            var node = streamMetadata.getAsTree(streamMetadata.getNativeMetadataFormatName());
            var nodeList = node.getChildNodes();
            for (int i = 0; i < nodeList.getLength(); i++) {
                var child = nodeList.item(i);
                if ("ApplicationExtensions".equals(child.getNodeName())) {
                    var children = child.getChildNodes();
                    for (int j = 0; j < children.getLength(); j++) {
                        var appExt = children.item(j);
                        if ("ApplicationExtension".equals(appExt.getNodeName())) {
                            var attrs = appExt.getAttributes();
                            var appId = attrs.getNamedItem("applicationID");
                            var authCode = attrs.getNamedItem("authenticationCode");
                            if (appId != null && "NETSCAPE".equals(appId.getNodeValue())
                                    && authCode != null && "2.0".equals(authCode.getNodeValue())) {
                                var userData = ((IIOMetadataNode) appExt).getUserObject();
                                if (userData instanceof byte[] bytes && bytes.length >= 3 && bytes[0] == 1) {
                                    return (bytes[1] & 0xFF) | ((bytes[2] & 0xFF) << 8);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /**
     * Reads an integer attribute off the root of whichever metadata format declares it, so that a
     * reader can publish a property the standard format has no place for.
     *
     * @return the value, or 0 when no format carries the attribute
     */
    private static int readDeclaredAttribute(IIOMetadata metadata, String attribute) {
        try {
            for (String name : metadata.getMetadataFormatNames()) {
                var attr = metadata.getAsTree(name).getAttributes().getNamedItem(attribute);
                if (attr != null) {
                    return Integer.parseInt(attr.getNodeValue());
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /**
     * Reads as many leading bytes of a WebP file as are needed to tell a lossless one from a lossy
     * one, which is more than the twelve that name the format: the chunk holding the image bitstream
     * follows the container header, and in an animation it follows the animation control chunk as
     * well.
     */
    private static final int DETECTION_LENGTH = WebpCodec.losslessHeaderLength();

    private static ImageFormat detectFormat(byte[] data) {
        // The bytes decide which format this is, and for a format that comes in more than one
        // flavour, which flavour: a WebP file read as the lossy one and written back out would lose
        // every pixel the lossless bitstream was there to keep.
        ImageFormat format = ImageFormat.detect(Arrays.copyOf(data, Math.min(data.length, DETECTION_LENGTH)));
        if (format != null) return format;
        return ImageFormat.PNG;
    }
}
