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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.ImageOutputStream;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import imagify.avif.AvifException;
import imagify.avif.ffm.AvifCodec;
import imagify.avif.ffm.AvifConstants;
import imagify.jpeg.JpegException;
import imagify.jpeg.jna.JpegliCodec;
import imagify.jpeg.jna.JpegliLibrary;
import imagify.webp.WebpException;
import imagify.webp.WebpImageWriterSpi;
import imagify.webp.ffm.WebpCodec;

/**
 * Writes images to various destinations with format control.
 *
 * <p>Supported destinations: {@code byte[]}, {@code Path}, {@code OutputStream}.</p>
 *
 * <p>Format is determined by explicit {@link ImageFormat} parameter or
 * inferred from the destination path extension.</p>
 *
 * <p>Quality is a {@code 0.0} to {@code 1.0} value that is handed to the encoder through the
 * compression quality of an {@link ImageWriteParam}, so a smaller number means a smaller file and
 * more loss. Formats without a quality axis ignore it: GIF and BMP because nothing in them can
 * lose anything, and PNG because nothing in it ever does. A PNG's effort is a setting of the format
 * instead, {@link ImageFormat.Png#compressionLevel(int)}, and asking for a high quality on one
 * would only have asked the compressor to work less.</p>
 *
 * <p>WebP is written in whichever of its two flavours the format asks for:
 * {@link ImageFormat#WEBP} produces the lossy {@code VP8} bitstream and
 * {@link ImageFormat.Webp#lossless()} the lossless {@code VP8L} one, for a still image as well as
 * for an animation. The quality is ignored by the lossless one, exactly as {@code libwebp} ignores
 * it.</p>
 *
 * <p>Settings a format carries rather than a write: the WebP encoder effort
 * ({@link ImageFormat.Webp#compressionMethod(int)}), the JPEG colour-difference resolution
 * ({@link ImageFormat.Jpeg#subsampling(ImageFormat.Jpeg.Subsampling)}) and entropy coder tables
 * ({@link ImageFormat.Jpeg#optimizeHuffmanTables(boolean)}), the PNG deflate effort
 * ({@link ImageFormat.Png#compressionLevel(int)}), and the AVIF encoder speed
 * ({@link ImageFormat.Avif#speed(int)}) and alpha quality
 * ({@link ImageFormat.Avif#alphaQuality(int)}). They are handed to the encoder whatever the output
 * is, and the WebP one is handed to the codec for a still image as well as for an animation
 * whenever it is not left at its default. An {@link ImageWriteParam} has room for a quality and a
 * compression type and for nothing else, so the JPEG subsampling travels as image metadata and the
 * encoder settings of the other two are handed to their codecs directly: an AVIF still image is
 * encoded through {@link AvifCodec} rather than through the ImageIO plug-in that wraps the same
 * codec.</p>
 *
 * <p>{@link FrameSequence} handling: if the sequence has more than one frame and the target
 * format supports animation, the frames are encoded as an animation. Otherwise the first
 * frame is written as a still image.</p>
 *
 * <p>Several images at a time: {@link #toBytes(List, ImageFormat, double)} and
 * {@link #toFiles(List, ImageFormat, double, List)} encode a list of images across every core of
 * the machine, which for the codecs in this library is the only way one of them uses more
 * than one.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * byte[] png = ImageWriter.toBytes(image, ImageFormat.PNG);
 * byte[] jpg = ImageWriter.toBytes(image, ImageFormat.JPEG, 0.6);
 * ImageWriter.toFile(image, Path.of("out.png"));
 * ImageWriter.toStream(image, ImageFormat.JPEG, outputStream);
 * List&lt;byte[]&gt; many = ImageWriter.toBytes(images, ImageFormat.WEBP, 0.8);
 * }</pre>
 */
public final class ImageWriter {

    private ImageWriter() {
    }

    // ---------------------------------------------------------------- FrameSequence

    /**
     * Encodes a {@link FrameSequence} to a byte array in the specified format.
     *
     * <p>If the sequence has multiple frames and the format supports animation,
     * the frames are encoded as an animation. Otherwise the first frame is
     * written as a still image.</p>
     *
     * @param frames the frame sequence
     * @param format the output format
     * @return the encoded bytes
     * @throws IOException if the image cannot be encoded
     */
    public static byte[] toBytes(FrameSequence frames, ImageFormat format) throws IOException {
        return toBytes(frames, format, format.getDefaultQuality());
    }

    /**
     * Encodes a {@link FrameSequence} to a byte array.
     *
     * <p>If the sequence has multiple frames and the format supports animation,
     * the frames are encoded as an animation. Otherwise the first frame is
     * written as a still image.</p>
     *
     * @param frames the frame sequence
     * @param format the output format
     * @param quality {@code 0.0} (smallest) to {@code 1.0} (largest)
     * @return the encoded bytes
     * @throws IOException if the image cannot be encoded
     */
    public static byte[] toBytes(FrameSequence frames, ImageFormat format, double quality) throws IOException {
        if (frames.frameCount() > 1 && format.supportsAnimation()) {
            return encodeAnimation(frames, format, quality);
        }
        return toBytes(frames.toBufferedImage(), format, quality);
    }

    /**
     * Writes a {@link FrameSequence} to a file path with explicit format.
     *
     * <p>If the sequence has multiple frames and the format supports animation,
     * the frames are encoded as an animation. Otherwise the first frame is
     * written as a still image.</p>
     *
     * @param frames the frame sequence
     * @param format the output format
     * @param path the output file path
     * @throws IOException if the image cannot be encoded or written
     */
    public static void toFile(FrameSequence frames, ImageFormat format, Path path) throws IOException {
        toFile(frames, format, 0.80, path);
    }

    /**
     * Writes a {@link FrameSequence} to a file path with explicit format and quality.
     *
     * <p>If the sequence has multiple frames and the format supports animation,
     * the frames are encoded as an animation. Otherwise the first frame is
     * written as a still image.</p>
     *
     * @param frames the frame sequence
     * @param format the output format
     * @param quality {@code 0.0} (smallest) to {@code 1.0} (largest)
     * @param path the output file path
     * @throws IOException if the image cannot be encoded or written
     */
    public static void toFile(FrameSequence frames, ImageFormat format, double quality, Path path) throws IOException {
        byte[] bytes = toBytes(frames, format, quality);
        Files.write(path, bytes);
    }

    /**
     * Writes a {@link FrameSequence} to a file path (extension determines format).
     *
     * <p>If the sequence has multiple frames and the format supports animation,
     * the frames are encoded as an animation. Otherwise the first frame is
     * written as a still image.</p>
     *
     * @param frames the frame sequence
     * @param path the output file path
     * @throws IOException if the image cannot be encoded or written
     */
    public static void toFile(FrameSequence frames, Path path) throws IOException {
        toFile(frames, path, 0.80);
    }

    /**
     * Writes a {@link FrameSequence} to a file path with quality control
     * (extension determines format).
     *
     * <p>If the sequence has multiple frames and the format supports animation,
     * the frames are encoded as an animation. Otherwise the first frame is
     * written as a still image.</p>
     *
     * @param frames the frame sequence
     * @param path the output file path
     * @param quality {@code 0.0} (smallest) to {@code 1.0} (largest)
     * @throws IOException if the image cannot be encoded or written
     */
    public static void toFile(FrameSequence frames, Path path, double quality) throws IOException {
        ImageFormat format = ImageFormat.fromPath(path);
        toFile(frames, format, quality, path);
    }

    // ------------------------------------------------------------------ single image

    /**
     * Writes a single image to a file path. Format is inferred from extension.
     */
    public static void toFile(BufferedImage image, Path path) throws IOException {
        ImageFormat format = ImageFormat.fromPath(path);
        toFile(image, format, path);
    }

    /**
     * Writes a single image to a file path. Format is inferred from extension.
     *
     * @param quality {@code 0.0} (smallest) to {@code 1.0} (largest)
     */
    public static void toFile(BufferedImage image, Path path, double quality) throws IOException {
        ImageFormat format = ImageFormat.fromPath(path);
        toFile(image, format, quality, path);
    }

    /**
     * Writes a single image to a file path with explicit format.
     */
    public static void toFile(BufferedImage image, ImageFormat format, Path path) throws IOException {
        toFile(image, format, format.getDefaultQuality(), path);
    }

    /**
     * Writes a single image to a file path with explicit format and quality.
     *
     * @param quality {@code 0.0} (smallest) to {@code 1.0} (largest)
     */
    public static void toFile(BufferedImage image, ImageFormat format, double quality, Path path) throws IOException {
        ImageOutputStream stream = ImageIO.createImageOutputStream(path.toFile());
        try {
            writeToStream(image, format, quality, stream);
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Encodes a single image to a byte array in the specified format.
     */
    public static byte[] toBytes(BufferedImage image, ImageFormat format) throws IOException {
        return toBytes(image, format, format.getDefaultQuality());
    }

    /**
     * Encodes a single image to a byte array with quality control.
     *
     * @param quality {@code 0.0} (smallest) to {@code 1.0} (largest)
     */
    public static byte[] toBytes(BufferedImage image, ImageFormat format, double quality) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        toStream(image, format, quality, baos);
        return baos.toByteArray();
    }

    // ----------------------------------------------------------------------- batches

    /**
     * Encodes several images at once, across as many cores as are left to use.
     *
     * <p>Encoding one image is a single threaded job. The WebP encoder is the clearest case: the
     * one knob libwebp offers for using more than one core, {@code thread_level}, was measured on
     * 24 cores and made no image faster and most of them slower, because of the two passes of its
     * own that read the flag both split a frame in two rather than across the cores it has. So the
     * cores a machine has are idle for the length of an encode, and the only way to fill them is to
     * have more than one encode running. Measured here, twelve 1600x1200 WebP images go from 1435
     * ms
     * on one thread to 226 ms on twelve, which is 6.4x.</p>
     *
     * <p>The images are independent, so nothing about them is shared: each one is converted,
     * encoded
     * and collected on its own thread with its own arena and its own encoder configuration, and the
     * results come back in the order they were given. Nothing here is more than a loop over
     * {@link #toBytes(BufferedImage, ImageFormat, double)} spread over a pool, and a caller who
     * needs it is free to write the same thing; what is here is so that the ordering and the thread
     * count do not have to be reinvented, and so that the exception of one image is an exception
     * from the batch rather than a half-written set of files.</p>
     *
     * <p>One image on a one core machine is not worth a pool, and the pool size never exceeds the
     * number of images. Beyond that the useful number of threads is the number of images: more
     * threads than that is more threads than work, and a pool larger than the core count on a
     * machine that is also running something else is how a library makes another process
     * slower.</p>
     *
     * @param images the images to encode
     * @param format the output format
     * @param quality {@code 0.0} (smallest) to {@code 1.0} (largest)
     * @return the encoded bytes, in the order the images were given
     * @throws IOException if any image cannot be encoded
     * @throws IllegalArgumentException if {@code images} is null or holds a null
     */
    public static List<byte[]> toBytes(List<BufferedImage> images, ImageFormat format, double quality) throws IOException {
        return toBytes(images, format, quality, 0);
    }

    /**
     * Encodes several images at once, on a chosen number of threads.
     *
     * @param images the images to encode
     * @param format the output format
     * @param quality {@code 0.0} (smallest) to {@code 1.0} (largest)
     * @param threads how many encodes to run at once, or {@code 0} for one per core and never more
     *            than there are images
     * @return the encoded bytes, in the order the images were given
     * @throws IOException if any image cannot be encoded
     * @throws IllegalArgumentException if {@code images} is null or holds a null, or
     *             {@code threads} is negative
     */
    public static List<byte[]> toBytes(List<BufferedImage> images, ImageFormat format, double quality, int threads) throws IOException {
        if (images == null) {
            throw new IllegalArgumentException("no images to encode");
        }
        if (threads < 0) {
            throw new IllegalArgumentException("the thread count cannot be negative: " + threads);
        }
        for (BufferedImage image : images) {
            if (image == null) {
                throw new IllegalArgumentException("a batch cannot hold a null image");
            }
        }
        int count = images.size();
        if (count == 0) {
            return List.of();
        }
        int width = threads > 0 ? threads : Runtime.getRuntime().availableProcessors();
        // Never more threads than there is work for, and never more than there are cores even when
        // the caller asked for more, because a thread with nothing to do still has to be scheduled.
        int pool = Math.max(1, Math.min(Math.min(width, count), Runtime.getRuntime().availableProcessors()));
        if (pool == 1) {
            List<byte[]> serial = new ArrayList<>(count);
            for (BufferedImage image : images) {
                serial.add(toBytes(image, format, quality));
            }
            return serial;
        }
        List<Callable<byte[]>> work = new ArrayList<>(count);
        for (BufferedImage image : images) {
            work.add(() -> toBytes(image, format, quality));
        }
        ExecutorService executor = Executors.newFixedThreadPool(pool);
        try {
            List<byte[]> encoded = new ArrayList<>(count);
            // invokeAll waits for all of them and gives back the failures rather than throwing the
            // first one, so the exception that comes out is about the batch and not about whichever
            // image happened to be encoded first.
            for (Future<byte[]> result : executor.invokeAll(work)) {
                try {
                    encoded.add(result.get());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    throw as(cause);
                }
            }
            return encoded;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("the batch was interrupted", e);
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Writes several images to several files at once, on the same terms as
     * {@link #toBytes(List, ImageFormat, double, int)}.
     *
     * <p>Each file is written whole or not at all, and the writing happens after every encode has
     * succeeded, so a failure part way through leaves no half-written file behind. That is the
     * reason this is not a loop of {@link #toFile(BufferedImage, ImageFormat, double, Path)} over a
     * pool: the loop would have written the ones that worked before it knew about the one that
     * did not.</p>
     *
     * @param images the images to write
     * @param format the output format
     * @param quality {@code 0.0} (smallest) to {@code 1.0} (largest)
     * @param paths one path per image, in the same order
     * @throws IOException if any image cannot be encoded or written
     * @throws IllegalArgumentException if the two lists are of different lengths, or either is null
     *             or holds a null
     */
    public static void toFiles(List<BufferedImage> images, ImageFormat format, double quality, List<Path> paths) throws IOException {
        if (paths == null || paths.size() != (images == null ? -1 : images.size())) {
            throw new IllegalArgumentException("expected one path per image: " + (images == null ? "no"
                    : images.size()) + " images but " + (paths == null ? "no paths" : paths.size() + " paths"));
        }
        for (Path path : paths) {
            if (path == null) {
                throw new IllegalArgumentException("a batch cannot hold a null path");
            }
        }
        List<byte[]> encoded = toBytes(images, format, quality);
        for (int i = 0; i < encoded.size(); i++) {
            Files.write(paths.get(i), encoded.get(i));
        }
    }

    /**
     * @return the cause as the exception a caller of this class expects, keeping an IOException an
     *         IOException rather than wrapping everything in one
     */
    private static IOException as(Throwable cause) {
        return cause instanceof IOException io ? io : new IOException(cause);
    }

    /**
     * Writes a single image to an OutputStream with default quality.
     */
    public static void toStream(BufferedImage image, ImageFormat format, OutputStream out) throws IOException {
        toStream(image, format, format.getDefaultQuality(), out);
    }

    /**
     * Writes a single image to an OutputStream with quality control.
     *
     * @param quality {@code 0.0} (smallest) to {@code 1.0} (largest)
     */
    public static void toStream(BufferedImage image, ImageFormat format, double quality, OutputStream out) throws IOException {
        ImageOutputStream stream = ImageIO.createImageOutputStream(out);
        try {
            writeToStream(image, format, quality, stream);
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ----------------------------------------------------------------- animation

    /**
     * Encodes a {@link FrameSequence} as an animation for the specified format.
     * Only called when the format supports animation and the sequence has multiple frames.
     */
    private static byte[] encodeAnimation(FrameSequence frames, ImageFormat format, double quality) throws IOException {
        int encoderQuality = (int) Math.round(quality * 100);
        if (format instanceof ImageFormat.Avif avif) {
            try {
                return AvifCodec.encodeAnimation(frames.frames(), frames.delaysMs(), encoderQuality, frames
                        .loopCount(), avif.speed, avif.alphaQuality, avif.subsampling != null ? avif.subsampling.pixelFormat
                                : -1, avif.chromaDownsampling != null ? avif.chromaDownsampling.value : -1);
            } catch (AvifException e) {
                throw new IOException("failed to encode AVIF animation", e);
            }
        }
        if (format instanceof ImageFormat.Webp webp) {
            try {
                return WebpCodec.encodeAnimation(frames.frames(), frames.delaysMs(), encoderQuality, webp.lossless, frames
                        .loopCount(), webp.compressionMethod);
            } catch (WebpException e) {
                throw new IOException("failed to encode WebP animation", e);
            }
        }
        throw new IOException("animation encoding not supported for: " + format.name());
    }

    // ------------------------------------------------------------------ internal

    private static void writeToStream(BufferedImage image, ImageFormat format, double quality, ImageOutputStream stream)
            throws IOException {
        checkQuality(quality);

        if (format instanceof ImageFormat.Avif avif) {
            // An ImageWriteParam has room for a quality and a compression type and for nothing
            // else,
            // so the AVIF encoder settings the format carries cannot be handed to the plug-in. This
            // goes through the codec itself instead, which is also what the plug-in does, so a
            // plain
            // AVIF format is encoded exactly as it was before.
            stream.write(encodeAvif(image, avif, quality));
            return;
        }

        if (format instanceof ImageFormat.Webp webp && webp.compressionMethod != WebpCodec.DEFAULT_COMPRESSION_METHOD) {
            // The WebP encoder effort is the one setting of that format an ImageWriteParam has no
            // room for, so a caller who has named one cannot be given it through the plug-in. It
            // goes through the codec itself instead, which is what the plug-in does, so a format
            // that leaves the setting at its default still comes out of the plug-in exactly as it
            // did before, and only a format that has actually been given a number of its own takes
            // this path.
            stream.write(encodeWebp(image, webp, quality));
            return;
        }

        if (format instanceof ImageFormat.Jpeg jpeg && JpegliCodec.isAvailable()) {
            // The same reasoning as AVIF, and more of it: the subsampling and the entropy coder
            // tables are both off the write param, and the jpegli encoder is reached directly
            // rather than through whichever provider ImageIO hands back first, which for JPEG is
            // not
            // this library's at all. A plain JPEG format therefore comes out of jpegli rather than
            // out of com.sun.imageio, which is the point of shipping the library.
            stream.write(encodeJpeg(image, jpeg, quality));
            return;
        }

        String formatName = format.getFormatName();
        // getImageWriters(type, name) rather than getImageWritersByFormatName(name): the latter
        // answers with every provider registered under a name and never asks canEncodeImage, so its
        // order is registration order rather than suitability. A JPEG provider backed by jpegli is
        // exactly the provider that declines the image whenever its native library is missing, and
        // it
        // is registered before the JDK's own, so taking the first entry would replace a working
        // codec with one that throws. Filtering leaves the JDK's writer in place, which is what
        // happened before this jar claimed JPEG at all.
        Iterator<javax.imageio.ImageWriter> writers = ImageIO
                .getImageWriters(ImageTypeSpecifier.createFromRenderedImage(image), formatName);
        if (!writers.hasNext()) {
            ImageIO.write(image, formatName, stream);
            return;
        }

        javax.imageio.ImageWriter writer = writers.next();
        try {
            writer.setOutput(stream);
            ImageWriteParam param = writeParam(writer, format, quality);
            if (param == null) {
                writer.write(image);
            } else {
                writer.write(null, new IIOImage(image, null, jpegMetadata(writer, image, param, format)), param);
            }
        } finally {
            writer.dispose();
        }
    }

    /**
     * The image metadata a format asks for, or {@code null} when it asks for none.
     *
     * <p>
     * Only JPEG has anything to say here, and only when the jpegli codec is not the one writing it:
     * the colour-difference channels are laid down at the sampling rates its frame header names,
     * and
     * those are not an {@link ImageWriteParam} but image metadata. A JPEG written at
     * {@link ImageFormat.Jpeg#DEFAULT_SUBSAMPLING} says nothing, which is the point of answering
     * {@code null} for it: the write then carries no metadata at all, exactly as it did before, and
     * the file it produces is the file the writer would have written on its own.
     * </p>
     */
    private static IIOMetadata jpegMetadata(javax.imageio.ImageWriter writer, BufferedImage image, ImageWriteParam param, ImageFormat format)
            throws IOException {
        if (!(format instanceof ImageFormat.Jpeg jpeg) || jpeg.subsampling == ImageFormat.Jpeg.DEFAULT_SUBSAMPLING) {
            return null;
        }
        try {
            IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), param);
            String nativeFormat = metadata.getNativeMetadataFormatName();
            Node root = metadata.getAsTree(nativeFormat);
            NodeList components = ((Element) root).getElementsByTagName("componentSpec");
            for (int i = 0; i < components.getLength(); i++) {
                Element component = (Element) components.item(i);
                // Only the luma channel carries a sampling rate of its own, and it is the one the
                // frame header numbers 1. The two colour-difference channels follow it.
                boolean luma = "1".equals(component.getAttribute("componentId"));
                component.setAttribute("HsamplingFactor", String.valueOf(luma ? jpeg.subsampling.horizontalFactor : 1));
                component.setAttribute("VsamplingFactor", String.valueOf(luma ? jpeg.subsampling.verticalFactor : 1));
            }
            metadata.mergeTree(nativeFormat, root);
            return metadata;
        } catch (IIOInvalidTreeException | IllegalArgumentException e) {
            // A JPEG that quietly lost the colour detail it was asked for would be worse than one
            // that failed, so the caller is told the request cannot be honoured.
            throw new IOException("the JPEG writer would not accept " + jpeg.subsampling + " subsampling: " + e.getMessage(), e);
        }
    }

    private static byte[] encodeJpeg(BufferedImage image, ImageFormat.Jpeg jpeg, double quality) throws IOException {
        try {
            return JpegliCodec.encode(image, Math
                    .round((float) quality * JpegliLibrary.IMAGIFY_JPEG_MAX_QUALITY), jpeg.subsampling.samp, jpeg.optimizeHuffmanTables);
        } catch (JpegException e) {
            throw new IOException("failed to encode a JPEG image: " + e.getMessage(), e);
        }
    }

    private static byte[] encodeWebp(BufferedImage image, ImageFormat.Webp webp, double quality) throws IOException {
        try {
            // The 0.0 to 1.0 of javax.imageio mapped onto the 0 to 100 that libwebp expects, the
            // same
            // mapping WebpImageWriter makes, so a format that names a method of its own differs
            // from
            // one that does not only in the effort spent and not at all in the fidelity asked for.
            return WebpCodec.encode(image, Math.round((float) quality * WebpCodec.MAX_QUALITY), webp.lossless, webp.compressionMethod);
        } catch (WebpException e) {
            throw new IOException("failed to encode a WebP image: " + e.getMessage(), e);
        }
    }

    private static byte[] encodeAvif(BufferedImage image, ImageFormat.Avif avif, double quality) throws IOException {
        try {
            return AvifCodec.encode(image, (int) Math
                    .round(quality * AvifConstants.QUALITY_BEST), avif.speed, avif.alphaQuality, avif.subsampling != null
                            ? avif.subsampling.pixelFormat
                            : -1, avif.chromaDownsampling != null ? avif.chromaDownsampling.value : -1);
        } catch (AvifException e) {
            throw new IOException("failed to encode an AVIF image: " + e.getMessage(), e);
        }
    }

    private static ImageWriteParam writeParam(javax.imageio.ImageWriter writer, ImageFormat format, double quality) throws IOException {
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param == null || !param.canWriteCompressed()) return null;
        try {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(encoderQuality(format, quality));
        } catch (IllegalStateException | UnsupportedOperationException e) {
            return null;
        }
        if (format instanceof ImageFormat.Webp webp && webp.lossless) {
            // WebP has no separate switch for the two bitstreams, the lossy VP8 and the lossless
            // VP8L: the choice is made by the compression type, so that is where the flag the
            // format carries has to be handed over. It is set after the mode, because switching to
            // MODE_EXPLICIT clears the type again.
            try {
                param.setCompressionType(WebpImageWriterSpi.COMPRESSION_TYPE_LOSSLESS);
            } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
                // Silently writing a lossy file where a lossless one was asked for would be worse
                // than failing, so the caller is told the request cannot be honoured.
                throw new IOException("the WebP writer does not accept the " + WebpImageWriterSpi.COMPRESSION_TYPE_LOSSLESS + " compression type: " + e
                        .getMessage(), e);
            }
        }
        if (format instanceof ImageFormat.Jpeg jpeg && jpeg.optimizeHuffmanTables) {
            // The entropy coder tables are this one's own extension of the write param, so the
            // switch is only there to be asked for and the format is the only place a caller can.
            // This is the JDK writer's extension and is only ever reached when jpegli is absent, in
            // which case the JDK writer is the one that is going to honour it.
            if (param instanceof JPEGImageWriteParam jpegParam) {
                jpegParam.setOptimizeHuffmanTables(true);
            }
        }
        return param;
    }

    /**
     * The compression quality to hand the write param, which is the argument for every format
     * except one.
     *
     * <p>
     * A PNG's write param has nothing but a quality to give, and this library's use of it is
     * worse than nothing: the plug-in reads the quality as a deflate level, so the argument a
     * caller passes to say how much of the picture to keep decides instead how hard the compressor
     * works, and a request for the best quality on a lossless format is answered with a file many
     * times larger holding exactly the same pixels. The effort is therefore taken from
     * {@link ImageFormat.Png#compressionLevel}, which is where a caller can say it out loud, and
     * the
     * argument is ignored here just as it already was for GIF and BMP.
     * </p>
     *
     * <p>
     * The two are the same scale read in opposite directions, the plug-in counting deflate levels
     * down from {@code 0} while its quality counts up from {@code 0.0}, so the level a format names
     * is asked for as the quality that lands on it.
     * </p>
     */
    private static float encoderQuality(ImageFormat format, double quality) {
        if (format instanceof ImageFormat.Png png) {
            return 1.0f - (float) png.compressionLevel / ImageFormat.Png.MAX_COMPRESSION_LEVEL;
        }
        return (float) quality;
    }

    private static void checkQuality(double quality) {
        if (!(quality >= 0.0 && quality <= 1.0)) {
            throw new IllegalArgumentException("the quality must be between 0.0 and 1.0, got " + quality);
        }
    }
}
