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
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates sprite sheets from multiple image frames.
 *
 * <p>A sprite sheet is a single {@link BufferedImage} containing
 * multiple frames arranged in a grid pattern.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * // From file paths
 * BufferedImage sheet = SpriteSheet.of()
 *     .addFrames(paths)
 *     .columns(4)
 *     .padding(2)
 *     .toImage();
 *
 * // From BufferedImages with uniform size
 * BufferedImage sheet = SpriteSheet.of()
 *     .addFrames(images)
 *     .uniformSize(64, 64)
 *     .spacing(4)
 *     .toImage();
 *
 * // Just resize a single image into a grid of frames
 * BufferedImage sheet = SpriteSheet.of()
 *     .addFrame(singleImage)
 *     .grid(3, 4)  // 3 columns, 4 rows
 *     .toImage();
 * }</pre>
 */
public final class SpriteSheet {

    private final List<BufferedImage> frames;
    private int columns;
    private int rows;
    private int frameWidth;
    private int frameHeight;
    private int padding;
    private int spacing;
    private boolean uniformSize;
    private Integer targetWidth;
    private Integer targetHeight;

    private SpriteSheet() {
        this.frames = new ArrayList<>();
        this.columns = 0;
        this.rows = 0;
        this.padding = 0;
        this.spacing = 0;
        this.uniformSize = false;
    }

    // ═══════════════════════════════════════════════════
    //  Builder API
    // ═══════════════════════════════════════════════════

    /**
     * Creates a new builder instance.
     */
    public static SpriteSheet of() {
        return new SpriteSheet();
    }

    /**
     * Adds frames from a list of file paths. Auto-reads using ImageReader.
     */
    public SpriteSheet addFrames(List<Path> paths) {
        for (Path p : paths) {
            try {
                frames.add(ImageReader.read(p).toBufferedImage());
            } catch (IOException e) {
                throw new RuntimeException("Failed to read: " + p, e);
            }
        }
        return this;
    }

    /**
     * Adds a single frame from a file path.
     */
    public SpriteSheet addFrame(Path path) {
        try {
            frames.add(ImageReader.read(path).toBufferedImage());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read: " + path, e);
        }
        return this;
    }

    /**
     * Adds frames from BufferedImages.
     */
    public SpriteSheet addImages(List<BufferedImage> images) {
        frames.addAll(images);
        return this;
    }

    /**
     * Adds a single BufferedImage frame.
     */
    public SpriteSheet addFrame(BufferedImage image) {
        frames.add(image);
        return this;
    }

    /**
     * Adds frames from byte arrays (auto-detected format).
     */
    public SpriteSheet addFrameBytes(byte[]... datas) {
        for (byte[] data : datas) {
            try {
                frames.add(ImageReader.read(data).toBufferedImage());
            } catch (IOException e) {
                throw new RuntimeException("Failed to decode frame", e);
            }
        }
        return this;
    }

    // ═══════════════════════════════════════════════════
    //  Layout configuration
    // ═══════════════════════════════════════════════════

    /**
     * Sets the number of columns in the sprite grid.
     * Rows are auto-calculated from frame count.
     */
    public SpriteSheet columns(int cols) {
        this.columns = cols;
        return this;
    }

    /**
     * Sets the number of rows in the sprite grid.
     * Columns are auto-calculated from frame count.
     */
    public SpriteSheet rows(int rows) {
        this.rows = rows;
        return this;
    }

    /**
     * Sets the grid dimensions directly.
     */
    public SpriteSheet grid(int cols, int rows) {
        this.columns = cols;
        this.rows = rows;
        return this;
    }

    /**
     * Forces all frames to a uniform size by resizing.
     * Required when frames have different sizes.
     */
    public SpriteSheet uniformSize(int width, int height) {
        this.uniformSize = true;
        this.targetWidth = width;
        this.targetHeight = height;
        return this;
    }

    /**
     * Sets padding around the entire sprite sheet.
     */
    public SpriteSheet padding(int pixels) {
        this.padding = pixels;
        return this;
    }

    /**
     * Sets spacing between frames.
     */
    public SpriteSheet spacing(int pixels) {
        this.spacing = pixels;
        return this;
    }

    // ═══════════════════════════════════════════════════
    //  Build
    // ═══════════════════════════════════════════════════

    /**
     * Builds the sprite sheet {@link BufferedImage}.
     *
     * @return the sprite sheet image (TYPE_INT_ARGB)
     * @throws IllegalStateException if no frames were added
     */
    public BufferedImage toImage() {
        if (frames.isEmpty()) {
            throw new IllegalStateException("No frames added");
        }

        // Determine frame dimensions
        int fw = frameWidth;
        int fh = frameHeight;

        if (uniformSize && targetWidth != null && targetHeight != null) {
            fw = targetWidth;
            fh = targetHeight;
        } else if (!uniformSize) {
            // Use the largest frame as reference
            for (BufferedImage f : frames) {
                if (fw < f.getWidth()) fw = f.getWidth();
                if (fh < f.getHeight()) fh = f.getHeight();
            }
        }

        // Calculate grid dimensions
        if (columns <= 0 && rows <= 0) {
            // Default: single row
            columns = frames.size();
        }
        if (rows <= 0) {
            rows = (int) Math.ceil((double) frames.size() / columns);
        }
        if (columns <= 0) {
            columns = (int) Math.ceil((double) frames.size() / rows);
        }

        // Calculate output dimensions
        int totalW = padding * 2 + columns * fw + (columns - 1) * spacing;
        int totalH = padding * 2 + rows * fh + (rows - 1) * spacing;

        BufferedImage sheet = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_ARGB);

        // Draw frames
        for (int i = 0; i < frames.size(); i++) {
            int col = i % columns;
            int row = i / columns;
            int x = padding + col * (fw + spacing);
            int y = padding + row * (fh + spacing);

            BufferedImage frame = frames.get(i);
            if (uniformSize && targetWidth != null && targetHeight != null) {
                // Resize frame to uniform size
                frame = BufferedImageResize.resize(frame, fw, fh, ResizeAlgorithm.BILINEAR);
            }
            sheet.getGraphics().drawImage(frame, x, y, null);
        }

        return sheet;
    }

    /**
     * Returns the number of frames in the sprite sheet.
     */
    public int frameCount() {
        return frames.size();
    }

    /**
     * Returns the calculated frame width.
     */
    public int getFrameWidth() {
        return frameWidth;
    }

    /**
     * Returns the calculated frame height.
     */
    public int getFrameHeight() {
        return frameHeight;
    }
}
