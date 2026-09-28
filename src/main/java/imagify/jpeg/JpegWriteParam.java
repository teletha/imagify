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

import imagify.ImageFormat;
import imagify.ImageFormat.Jpeg.Subsampling;
import imagify.jpeg.jna.JpegliLibrary;

import java.util.Locale;

/**
 * The write parameters of {@link JpegImageWriter}, which carry the two settings a JPEG has and an
 * {@link javax.imageio.ImageWriteParam} has nowhere to put.
 *
 * <p>{@link javax.imageio.ImageWriteParam} offers a quality and a compression type, and a JPEG's
 * colour-difference resolution and entropy coder tables are neither of those. They are the same two
 * settings {@link imagify.ImageFormat.Jpeg} carries, so a caller driving {@code ImageIO} directly
 * can ask for exactly what a caller of {@link imagify.ImageWriter} gets, rather than the two paths
 * being able to produce different files for the same request.
 *
 * <p>Every reader of these values has to cast, because {@code getDefaultWriteParam()} answers
 * {@code ImageWriteParam}. That is the same bargain the JDK's own
 * {@link javax.imageio.plugins.jpeg.JPEGImageWriteParam} makes, and the cast is the only way
 * {@code ImageWriteParam} can be extended at all: {@code canWriteCompressed},
 * {@code compressionTypes} and {@code compressionType} are all {@code protected} with no setters.
 */
public class JpegWriteParam extends javax.imageio.ImageWriteParam {

    private Subsampling subsampling = ImageFormat.Jpeg.DEFAULT_SUBSAMPLING;
    private boolean optimizeHuffmanTables = ImageFormat.Jpeg.DEFAULT_OPTIMIZE_HUFFMAN_TABLES;

    /**
     * @param locale the locale for message formatting
     */
    public JpegWriteParam(Locale locale) {
        super(locale);
        canWriteCompressed = true;
        compressionTypes = new String[] { JpegImageWriterSpi.COMPRESSION_TYPE };
        compressionType = JpegImageWriterSpi.COMPRESSION_TYPE;
        // The runtime's ImageWriteParam defaults compressionMode to MODE_COPY_FROM_METADATA, but
        // callers of getDefaultWriteParam() expect MODE_DEFAULT, so choose it explicitly. On this
        // build of ImageWriteParam switching to MODE_EXPLICIT clears compressionType, so restore it
        // in setCompressionMode below.
        compressionMode = MODE_DEFAULT;
        compressionQuality = JpegliLibrary.DEFAULT_QUALITY / 100f;
    }

    /**
     * @param subsampling how finely the two colour-difference channels are to be stored
     * @throws IllegalArgumentException if {@code subsampling} is {@code null}
     */
    public void setSubsampling(Subsampling subsampling) {
        if (subsampling == null) {
            throw new IllegalArgumentException("the subsampling cannot be null");
        }
        this.subsampling = subsampling;
    }

    /**
     * @return how finely the two colour-difference channels are stored, 4:2:0 unless asked otherwise
     */
    public Subsampling getSubsampling() {
        return subsampling;
    }

    /**
     * @param optimizeHuffmanTables whether the entropy coder tables are to be computed from the
     *            image rather than taken from the standard set
     */
    public void setOptimizeHuffmanTables(boolean optimizeHuffmanTables) {
        this.optimizeHuffmanTables = optimizeHuffmanTables;
    }

    /**
     * @return whether the entropy coder tables are computed from the image, which they are not
     *         unless a caller asks for the extra work
     */
    public boolean getOptimizeHuffmanTables() {
        return optimizeHuffmanTables;
    }

    @Override
    public float getCompressionQuality() {
        // The runtime's own getter throws unless the mode is MODE_EXPLICIT, and the mode cannot be
        // anything else until it has been switched, which on this build also clears the compression
        // type. The result is that the constructor's default is stored and unreachable: a caller who
        // wants to know what quality this provider writes at has to destroy the type to ask. Reading
        // the field directly answers MODE_DEFAULT with the same value the writer uses, because
        // qualityOf() takes its default from JpegliLibrary rather than from the field.
        if (compressionMode != MODE_EXPLICIT) {
            return compressionQuality;
        }
        return super.getCompressionQuality();
    }

    @Override
    public String getCompressionType() {
        // The runtime's ImageWriteParam.getCompressionType() additionally requires the mode to be
        // MODE_EXPLICIT, which is a stricter check than "a compression type has been chosen". Here
        // the type is chosen in the constructor, so report it whenever compression is supported and
        // a type has been set regardless of the mode.
        if (!canWriteCompressed()) {
            throw new UnsupportedOperationException("Compression not supported.");
        }
        if (compressionType == null) {
            throw new IllegalStateException("No compression type set!");
        }
        return compressionType;
    }

    @Override
    public void setCompressionMode(int mode) {
        super.setCompressionMode(mode);
        if (mode == MODE_EXPLICIT && compressionType == null) {
            compressionType = JpegImageWriterSpi.COMPRESSION_TYPE;
        }
    }
}
