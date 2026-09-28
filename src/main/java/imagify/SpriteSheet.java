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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates sprite sheets from multiple image frames.
 *
 * <p>
 * A sprite sheet is a single {@link BufferedImage} containing multiple frames arranged in a grid.
 * This class only holds the layout configuration; the same configuration is shared by
 * {@link #layout()} (where does every frame go) and {@link #toImage()} (draw them there), so the
 * coordinates handed to a stylesheet or an atlas file can never disagree with the picture.
 * </p>
 *
 * <p>
 * Every frame is placed in a cell. Unless {@link #cell(int, int, Fit)} says otherwise the cell is
 * as
 * large as the largest frame and smaller frames are centred in it untouched.
 * </p>
 *
 * <p>
 * Usage:
 * </p>
 * <pre>{@code
 * // From file paths
 * BufferedImage sheet = SpriteSheet.of()
 *     .addFrames(paths)
 *     .columns(4)
 *     .padding(2)
 *     .toImage();
 *
 * // Frames of different shapes into 64x64 cells, cropping the overflow
 * BufferedImage sheet = SpriteSheet.of()
 *     .addImages(images)
 *     .cell(64, 64, Fit.FILL)
 *     .spacing(4)
 *     .background(Color.WHITE)
 *     .toImage();
 *
 * // Every frame of an animation, and where each of them ended up
 * SpriteSheet sheet = SpriteSheet.of().addFrames(Imagify.read(gif).get()).columns(8);
 * SpriteSheet.Layout layout = sheet.layout();
 * BufferedImage image = sheet.toImage();
 * }</pre>
 */
public final class SpriteSheet {

    /**
     * How a frame is made to fit its cell. The names follow the resizing methods of
     * {@link Imagify}.
     */
    public enum Fit {
        /** Stretches to the cell exactly, like {@link Imagify#resize(int, int)}. */
        STRETCH,

        /**
         * Keeps the aspect ratio and scales down when the frame does not fit; a frame that already
         * fits is left at its own size. Either way it is centred, so this is what
         * {@link Imagify#padTo(int, int)} does per cell.
         */
        INSIDE,

        /**
         * Keeps the aspect ratio and crops the overflow, like
         * {@link Imagify#resizeToFill(int, int)}.
         */
        FILL
    }

    /**
     * Where one frame's cell lies in the sheet.
     *
     * @param x left edge in pixels
     * @param y top edge in pixels
     * @param width cell width in pixels
     * @param height cell height in pixels
     */
    public record Cell(int x, int y, int width, int height) {
    }

    /**
     * The resolved geometry of a sheet.
     *
     * @param columns number of columns
     * @param rows number of rows
     * @param cellWidth width of every cell
     * @param cellHeight height of every cell
     * @param width width of the whole sheet
     * @param height height of the whole sheet
     * @param cells one entry per frame, in the order the frames were added
     */
    public record Layout(int columns, int rows, int cellWidth, int cellHeight, int width, int height, List<Cell> cells) {
    }

    private final List<BufferedImage> frames = new ArrayList<>();

    private int columns;

    private int rows;

    /** {@code 0} means as large as the largest frame. */
    private int cellWidth;

    private int cellHeight;

    private Fit fit = Fit.INSIDE;

    private ResizeAlgorithm algorithm = ResizeAlgorithm.DEFAULT;

    private Color background;

    private int padding;

    private int spacing;

    private SpriteSheet() {
    }

    // ═══════════════════════════════════════════════════
    // Builder API
    // ═══════════════════════════════════════════════════

    /**
     * Creates a new builder instance.
     */
    public static SpriteSheet create() {
        return new SpriteSheet();
    }

    /**
     * Adds frames from a list of file paths. Auto-reads using ImageReader.
     *
     * <p>
     * Only the first frame of each file is taken; use {@link #addFrames(FrameSequence)} to add
     * every
     * frame of an animation.
     * </p>
     */
    public SpriteSheet addFrames(List<Path> paths) {
        for (Path p : paths) {
            addFrame(p);
        }
        return this;
    }

    /**
     * Adds every frame of a sequence, e.g. the frames of an animated GIF. The timing of the
     * sequence is not part of a sheet and is dropped.
     */
    public SpriteSheet addFrames(FrameSequence sequence) {
        frames.addAll(sequence.frames());
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
    // Layout configuration
    // ═══════════════════════════════════════════════════

    /**
     * Sets the number of columns in the sprite grid.
     * Rows are auto-calculated from frame count.
     */
    public SpriteSheet columns(int cols) {
        this.columns = requirePositive("columns", cols);
        return this;
    }

    /**
     * Sets the number of rows in the sprite grid.
     * Columns are auto-calculated from frame count.
     */
    public SpriteSheet rows(int rows) {
        this.rows = requirePositive("rows", rows);
        return this;
    }

    /**
     * Sets the grid dimensions directly. The grid must have room for every frame, which is checked
     * when the sheet is laid out.
     */
    public SpriteSheet grid(int cols, int rows) {
        this.columns = requirePositive("columns", cols);
        this.rows = requirePositive("rows", rows);
        return this;
    }

    /**
     * Gives every cell the same size and makes each frame fit it with {@link Fit#INSIDE}.
     */
    public SpriteSheet cell(int width, int height) {
        return cell(width, height, Fit.INSIDE);
    }

    /**
     * Gives every cell the same size and makes each frame fit it the given way.
     */
    public SpriteSheet cell(int width, int height, Fit fit) {
        this.cellWidth = requirePositive("cell width", width);
        this.cellHeight = requirePositive("cell height", height);
        this.fit = java.util.Objects.requireNonNull(fit, "fit");
        return this;
    }

    /**
     * Sets the algorithm used when a frame has to be scaled.
     */
    public SpriteSheet algorithm(ResizeAlgorithm algorithm) {
        this.algorithm = java.util.Objects.requireNonNull(algorithm, "algorithm");
        return this;
    }

    /**
     * Sets what the whole sheet, gaps and padding included, is filled with before the frames are
     * drawn. {@code null}, the default, keeps it transparent.
     */
    public SpriteSheet background(Color color) {
        this.background = color;
        return this;
    }

    /**
     * Sets padding around the entire sprite sheet.
     */
    public SpriteSheet padding(int pixels) {
        this.padding = requireNotNegative("padding", pixels);
        return this;
    }

    /**
     * Sets spacing between frames.
     */
    public SpriteSheet spacing(int pixels) {
        this.spacing = requireNotNegative("spacing", pixels);
        return this;
    }

    // ═══════════════════════════════════════════════════
    // Build
    // ═══════════════════════════════════════════════════

    /**
     * Works out where every frame goes without drawing anything. Nothing on this builder is
     * changed by the call, so it can be repeated after more frames were added.
     *
     * @return the geometry {@link #toImage()} draws to
     * @throws IllegalStateException if no frames were added
     * @throws IllegalArgumentException if the grid has no room for every frame
     */
    public Layout layout() {
        if (frames.isEmpty()) {
            throw new IllegalStateException("No frames added");
        }
        int count = frames.size();

        // grid
        int cols = columns;
        int rws = rows;
        if (cols <= 0 && rws <= 0) {
            cols = count; // default: a single row
        }
        if (rws <= 0) {
            rws = ceilDiv(count, cols);
        } else if (cols <= 0) {
            cols = ceilDiv(count, rws);
        }
        if ((long) cols * rws < count) {
            throw new IllegalArgumentException("a " + cols + "x" + rws + " grid has no room for " + count + " frames");
        }

        // cell
        int cw = cellWidth;
        int ch = cellHeight;
        if (cw <= 0) {
            for (BufferedImage frame : frames) {
                cw = Math.max(cw, frame.getWidth());
                ch = Math.max(ch, frame.getHeight());
            }
        }

        // placement
        var cells = new ArrayList<Cell>(count);
        for (int i = 0; i < count; i++) {
            int x = padding + (i % cols) * (cw + spacing);
            int y = padding + (i / cols) * (ch + spacing);
            cells.add(new Cell(x, y, cw, ch));
        }
        int totalW = padding * 2 + cols * cw + (cols - 1) * spacing;
        int totalH = padding * 2 + rws * ch + (rws - 1) * spacing;
        return new Layout(cols, rws, cw, ch, totalW, totalH, List.copyOf(cells));
    }

    /**
     * Builds the sprite sheet {@link BufferedImage}.
     *
     * @return the sprite sheet image (TYPE_INT_ARGB)
     * @throws IllegalStateException if no frames were added
     * @throws IllegalArgumentException if the grid has no room for every frame
     */
    public BufferedImage toImage() {
        Layout layout = layout();
        BufferedImage sheet = new BufferedImage(layout.width(), layout.height(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = sheet.createGraphics();
        try {
            if (background != null) {
                graphics.setColor(background);
                graphics.fillRect(0, 0, layout.width(), layout.height());
            }
            for (int i = 0; i < frames.size(); i++) {
                Cell cell = layout.cells().get(i);
                BufferedImage frame = fit(frames.get(i), cell.width(), cell.height());
                int x = cell.x() + (cell.width() - frame.getWidth()) / 2;
                int y = cell.y() + (cell.height() - frame.getHeight()) / 2;
                graphics.drawImage(frame, x, y, null);
            }
        } finally {
            graphics.dispose();
        }
        return sheet;
    }

    /**
     * Builds the sheet and continues in an {@link Imagify} pipeline, e.g. to resize or write it.
     */
    public Imagify toImagify() {
        return Imagify.read(toImage());
    }

    /**
     * Returns the number of frames in the sprite sheet.
     */
    public int frameCount() {
        return frames.size();
    }

    // ═══════════════════════════════════════════════════
    // Internals
    // ═══════════════════════════════════════════════════

    /**
     * Makes a frame fit a cell. The result is never larger than the cell; it is smaller only for
     * {@link Fit#INSIDE}, where the caller centres it.
     */
    private BufferedImage fit(BufferedImage frame, int cw, int ch) {
        int w = frame.getWidth();
        int h = frame.getHeight();
        switch (fit) {
        case STRETCH:
            return w == cw && h == ch ? frame : BufferedImageResize.resize(frame, cw, ch, algorithm);

        case INSIDE: {
            if (w <= cw && h <= ch) {
                return frame;
            }
            double scale = Math.min((double) cw / w, (double) ch / h);
            int scaledW = Math.min(cw, Math.max(1, (int) Math.round(w * scale)));
            int scaledH = Math.min(ch, Math.max(1, (int) Math.round(h * scale)));
            return BufferedImageResize.resize(frame, scaledW, scaledH, algorithm);
        }

        case FILL: {
            double scale = Math.max((double) cw / w, (double) ch / h);
            // Rounding up, and never below the cell, is what keeps the crop inside the image.
            int scaledW = Math.max(cw, (int) Math.ceil(w * scale));
            int scaledH = Math.max(ch, (int) Math.ceil(h * scale));
            BufferedImage scaled = scaledW == w && scaledH == h ? frame : BufferedImageResize.resize(frame, scaledW, scaledH, algorithm);
            return BufferedImageTransform.crop(scaled, (scaledW - cw) / 2, (scaledH - ch) / 2, cw, ch);
        }

        default:
            throw new AssertionError(fit);
        }
    }

    private static int ceilDiv(int dividend, int divisor) {
        return (dividend + divisor - 1) / divisor;
    }

    private static int requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive, got " + value);
        }
        return value;
    }

    private static int requireNotNegative(String name, int value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative, got " + value);
        }
        return value;
    }
}