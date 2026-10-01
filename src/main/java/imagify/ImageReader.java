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

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;

import org.w3c.dom.Node;

import imagify.jpeg.JpegImageReader;
import imagify.jpeg.JpegImageReaderSpi;
import imagify.jpeg.ffm.JpegliCodec;
import imagify.webp.ffm.WebpCodec;

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
        if (format instanceof ImageFormat.Jpeg && JpegliCodec.isAvailable()) {
            // JPEG is the one format the JDK already has a reader for, so the provider this library
            // registers is not the first one ImageIO answers with. Asking for ours by class is the
            // only way to be sure a JPEG decoded here went through jpegli rather than through
            // com.sun.imageio by accident.
            return readJpeg(stream);
        }
        // getImageReadersByFormatName answers with every provider registered under a name and never
        // asks canDecodeInput, so its order is registration order rather than suitability. A JPEG
        // provider backed by jpegli is exactly the provider that declines the file whenever its
        // native library is missing, and it is registered before the JDK's own, so taking the first
        // entry would replace a working decoder with one that throws. Asking each of them leaves the
        // JDK's reader in place, which is what happened before this jar claimed JPEG at all.
        Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReadersByFormatName(format.getFormatName());
        javax.imageio.ImageReader reader = firstDecoding(readers, stream);
        if (reader == null) {
            throw new IOException("no ImageReader for format: " + format.getFormatName());
        }
        try {
            reader.setInput(stream, false, false);
            int numFrames;
            try {
                numFrames = reader.getNumImages(true);
            } catch (Exception e) {
                // Reader doesn't support multi-frame; treat as single image
                BufferedImage frame = reader.read(0);
                return new FrameSequence(List.of(frame), new int[]{1000}, 0);
            }
            if (format == ImageFormat.GIF) {
                // A GIF frame is a rectangle placed at an offset on the logical screen and drawn over
                // whatever the frames before it left there, so the frames a reader hands out one at a
                // time are not all the same size and are not the animation. Composite them.
                return readGif(reader, numFrames);
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

    /**
     * Takes the first reader that accepts the file, and disposes of the ones that do not.
     *
     * <p>{@code ImageIO} has no {@code getImageReaders(Object, String)}, so there is nothing that
     * filters the providers by {@code canDecodeInput} for a stream that has not been decoded yet; the
     * question has to be put to them directly.
     *
     * @param readers the providers registered for a format name, consumed by this method
     * @param stream the encoded file every provider is asked about
     * @return a reader that accepts {@code stream}, or {@code null} when none of them does
     */
    private static javax.imageio.ImageReader firstDecoding(
            Iterator<javax.imageio.ImageReader> readers, ImageInputStream stream) {
        while (readers.hasNext()) {
            javax.imageio.ImageReader reader = readers.next();
            try {
                if (reader.getOriginatingProvider().canDecodeInput(stream)) {
                    return reader;
                }
            } catch (IOException e) {
                // A provider that cannot even look at the file is not one to hand it to.
            }
            reader.dispose();
        }
        return null;
    }

    /**
     * Reads a JPEG through jpegli, asking this library's own provider for the reader.
     *
     * <p>A JPEG is one image with no timing and no repetition count, so the sequence handed back is
     * the single frame with the default delay, which is what every other single image format here
     * answers with too.
     *
     * @param stream the encoded JPEG
     * @return the one frame of the file
     * @throws IOException when the file cannot be read
     */
    private static FrameSequence readJpeg(ImageInputStream stream) throws IOException {
        javax.imageio.ImageReader reader = new JpegImageReader(
                new JpegImageReaderSpi());
        try {
            reader.setInput(stream, false, true);
            return new FrameSequence(List.of(reader.read(0)), new int[] {1000}, 0);
        } finally {
            reader.dispose();
        }
    }

    /**
     * Assembles the frames of an animated GIF onto its logical screen.
     *
     * <p>A GIF frame is a rectangle placed at an offset on a canvas of the size the file declares,
     * and it is composited over whatever the frames before it left there. A reader that hands out
     * one frame at a time answers with each frame's own rectangle, which is a different size per
     * frame and is not an animation any encoder here accepts. This runs the compositing the format
     * describes, so every frame comes back the size of the logical screen.
     *
     * @param reader a reader already positioned on the file
     * @param numFrames how many frames the reader reports
     * @return the composited frames, their delays and the loop count
     * @throws IOException when a frame cannot be read
     */
    private static FrameSequence readGif(javax.imageio.ImageReader reader, int numFrames)
            throws IOException {
        List<BufferedImage> raw = new ArrayList<>(numFrames);
        List<IIOMetadata> metadata = new ArrayList<>(numFrames);
        for (int i = 0; i < numFrames; i++) {
            raw.add(reader.read(i));
            metadata.add(reader.getImageMetadata(i));
        }
        BufferedImage canvas = new BufferedImage(gifCanvasSize(reader, raw, metadata, true),
                gifCanvasSize(reader, raw, metadata, false), BufferedImage.TYPE_INT_ARGB);
        List<BufferedImage> frames = new ArrayList<>(numFrames);
        int[] delaysMs = new int[numFrames];
        Rectangle previous = null;
        String previousDisposal = "none";
        BufferedImage previousSnapshot = null;
        for (int i = 0; i < numFrames; i++) {
            BufferedImage frame = raw.get(i);
            String disposal = gifValue(metadata.get(i), "GraphicControlExtension", "disposalMethod", "none");
            int atX = gifNumber(metadata.get(i), "ImageDescriptor", "imageLeftPosition", 0);
            int atY = gifNumber(metadata.get(i), "ImageDescriptor", "imageTopPosition", 0);

            // The disposal the previous frame asked for is applied before this frame is drawn.
            if (previous != null) {
                if ("restoreToBackgroundColor".equals(previousDisposal)) {
                    clear(canvas, previous);
                } else if ("restoreToPrevious".equals(previousDisposal) && previousSnapshot != null) {
                    canvas = copy(previousSnapshot);
                }
            }
            BufferedImage snapshot = "restoreToPrevious".equals(disposal) ? copy(canvas) : null;
            Graphics2D graphics = canvas.createGraphics();
            try {
                graphics.drawImage(frame, atX, atY, null);
            } finally {
                graphics.dispose();
            }
            frames.add(copy(canvas));
            delaysMs[i] = readFrameDelay(metadata.get(i));
            previous = new Rectangle(atX, atY, frame.getWidth(), frame.getHeight());
            previousDisposal = disposal;
            previousSnapshot = snapshot;
        }
        return new FrameSequence(frames, delaysMs, readLoopCount(ImageFormat.GIF, reader.getStreamMetadata()));
    }

    /**
     * @param width whether the width is wanted, in which case the height is answered
     * @return the logical screen size, or the smallest canvas the frames fit in when the file does
     *         not declare one this reader can see
     */
    private static int gifCanvasSize(javax.imageio.ImageReader reader, List<BufferedImage> raw,
            List<IIOMetadata> metadata, boolean width) {
        String attribute = width ? "logicalScreenWidth" : "logicalScreenHeight";
        int declared = gifNumber(streamMetadata(reader), "LogicalScreenDescriptor", attribute, 0);
        int needed = 1;
        for (int i = 0; i < raw.size(); i++) {
            BufferedImage frame = raw.get(i);
            String position = width ? "imageLeftPosition" : "imageTopPosition";
            int at = gifNumber(metadata.get(i), "ImageDescriptor", position, 0);
            needed = Math.max(needed, at + (width ? frame.getWidth() : frame.getHeight()));
        }
        return Math.max(declared, needed);
    }

    private static IIOMetadata streamMetadata(javax.imageio.ImageReader reader) {
        try {
            return reader.getStreamMetadata();
        } catch (IOException e) {
            return null;
        }
    }

    /** Reads an integer attribute of the first element with the given name, or the fallback. */
    private static int gifNumber(IIOMetadata metadata, String element, String attribute, int fallback) {
        String value = gifValue(metadata, element, attribute, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Reads a string attribute of the first element with the given name, or the fallback. */
    private static String gifValue(IIOMetadata metadata, String element, String attribute, String fallback) {
        if (metadata == null) {
            return fallback;
        }
        for (String format : metadata.getMetadataFormatNames()) {
            try {
                Node found = firstNode(metadata.getAsTree(format), element);
                if (found != null) {
                    Node value = found.getAttributes().getNamedItem(attribute);
                    if (value != null) {
                        return value.getNodeValue();
                    }
                }
            } catch (Exception ignored) {
                // A metadata format that cannot be described is not one to read a value from.
            }
        }
        return fallback;
    }

    /** @return the first node named {@code name} at or below {@code node}, or {@code null} */
    private static Node firstNode(Node node, String name) {
        if (node == null) {
            return null;
        }
        if (name.equals(node.getNodeName())) {
            return node;
        }
        for (int i = 0; i < node.getChildNodes().getLength(); i++) {
            Node found = firstNode(node.getChildNodes().item(i), name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Erases a rectangle, which is what a GIF asks for with the restore-to-background disposal. */
    private static void clear(BufferedImage image, Rectangle area) {
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Clear);
            graphics.fillRect(area.x, area.y, area.width, area.height);
        } finally {
            graphics.dispose();
        }
    }

    /** A copy, because a composited frame is a snapshot and the canvas goes on changing. */
    private static BufferedImage copy(BufferedImage image) {
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = copy.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return copy;
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
