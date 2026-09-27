/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.svg;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.ViewBox;

import javax.imageio.IIOException;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;

/**
 * {@link ImageReader} for {@code .svg} files, backed by <a href="https://github.com/weisJ/jsvg">JSVG</a>.
 *
 * <p>An SVG file always produces exactly one rasterized image at its
 * natural dimensions.
 *
 * <p>Instances are stateful and not thread safe.
 */
public class SvgImageReader extends ImageReader {

    private static final List<Integer> TYPES = List.of(
            BufferedImage.TYPE_4BYTE_ABGR,
            BufferedImage.TYPE_INT_ARGB,
            BufferedImage.TYPE_INT_RGB);

    private ImageInputStream stream;
    private int width;
    private int height;

    SvgImageReader(ImageReaderSpi originatingProvider) {
        super(originatingProvider);
    }

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        stream = (ImageInputStream) getInput();
    }

    @Override
    public void reset() {
        super.reset();
        stream = null;
    }

    @Override
    public int getNumImages(boolean allowSearch) throws IIOException {
        verifyInput();
        return 1;
    }

    @Override
    public int getWidth(int imageIndex) throws IIOException {
        return verifyIndex(imageIndex)[0];
    }

    @Override
    public int getHeight(int imageIndex) throws IIOException {
        return verifyIndex(imageIndex)[1];
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IIOException {
        verifyIndex(imageIndex);
        return types();
    }

    @Override
    public IIOMetadata getStreamMetadata() { return null; }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) {
        return null;
    }

    @Override
    public BufferedImage read(int imageIndex, javax.imageio.ImageReadParam param) throws IIOException {
        verifyIndex(imageIndex);
        try {
            return rasterize();
        } catch (IOException e) {
            throw new IIOException("cannot read the SVG image: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ internals

    private void verifyInput() throws IIOException {
        if (stream == null) {
            throw new IIOException("no input has been set");
        }
    }

    private int[] verifyIndex(int imageIndex) throws IIOException {
        verifyInput();
        if (imageIndex != 0) {
            throw new IIOException("image index " + imageIndex + " out of range (0..0)");
        }
        return new int[] { width, height };
    }

    private Iterator<ImageTypeSpecifier> types() {
        List<ImageTypeSpecifier> list = new ArrayList<>();
        for (int t : TYPES) {
            list.add(ImageTypeSpecifier.createFromBufferedImageType(t));
        }
        return list.iterator();
    }

    private BufferedImage rasterize() throws IOException {
        stream.seek(0);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = stream.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        byte[] bytes = baos.toByteArray();
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("imagify-svg", ".svg");
        try {
            java.nio.file.Files.write(tmp, bytes);
            SVGLoader loader = new SVGLoader();
            SVGDocument document = loader.load(tmp.toUri().toURL());
            if (document == null) {
                throw new IIOException("the SVG could not be parsed");
            }
            float w = document.size().width;
            float h = document.size().height;
            width = (int) Math.ceil(w);
            height = (int) Math.ceil(h);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
            Graphics2D g = image.createGraphics();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL,
                        java.awt.RenderingHints.VALUE_STROKE_PURE);
                document.render(null, g, new ViewBox(0, 0, width, height));
            } finally {
                g.dispose();
            }
            return image;
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }
}
