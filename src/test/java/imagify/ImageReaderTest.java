/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the byte array and {@link InputStream} entry points of {@link ImageReader}.
 *
 * <p>These used to fail for every format: {@code ImageIO} has no {@code ImageInputStream}
 * provider for a {@code byte[]}, so passing the array straight through produced a {@code null}
 * stream and then a {@link NullPointerException} on close. The array has to be wrapped in a
 * stream, and the wrapping is the whole point of these tests.
 */
class ImageReaderTest {

    @Test
    @DisplayName("a byte array is read back through the explicit format overload")
    void readsFromByteArray() throws IOException {
        byte[] png = write(sample(), ImageFormat.PNG);
        BufferedImage back = ImageReader.read(png, ImageFormat.PNG).toBufferedImage();
        assertEquals(20, back.getWidth());
        assertEquals(10, back.getHeight());
    }

    @Test
    @DisplayName("a byte array is read back with the format detected from the header")
    void detectsFromByteArray() throws IOException {
        byte[] png = write(sample(), ImageFormat.PNG);
        assertEquals(20, ImageReader.read(png).toBufferedImage().getWidth());
    }

    @Test
    @DisplayName("an InputStream reaches the same result as the equivalent byte array")
    void readsFromInputStream() throws IOException {
        byte[] png = write(sample(), ImageFormat.PNG);
        assertEquals(20, ImageReader.read(new ByteArrayInputStream(png), ImageFormat.PNG).toBufferedImage().getWidth());
        assertEquals(20, ImageReader.read(new ByteArrayInputStream(png)).toBufferedImage().getWidth());
    }

    @Test
    @DisplayName("an empty array is reported rather than crashing")
    void rejectsEmptyData() {
        assertThrows(IOException.class, () -> ImageReader.read(new byte[0], ImageFormat.PNG));
    }

    @Test
    @DisplayName("GIF frames smaller than the logical screen are composited onto it")
    void compositesGifFramesOntoTheLogicalScreen() throws IOException {
        // A GIF frame is a rectangle placed at an offset on the logical screen, and a reader that
        // hands out one frame at a time answers with each frame's own rectangle. On this 4x4 screen
        // the two frames are 2x2, so they have to be drawn onto the screen to become the animation.
        FrameSequence sequence = ImageReader.read(offsetGif());

        assertEquals(2, sequence.frameCount());
        for (BufferedImage frame : sequence.frames()) {
            assertEquals(4, frame.getWidth(), "every frame should be the width of the logical screen");
            assertEquals(4, frame.getHeight(), "every frame should be the height of the logical screen");
        }
        assertEquals(0xFFFF0000, sequence.frames().get(0).getRGB(0, 0), "the first frame is red where it was drawn");
        assertEquals(0, sequence.frames().get(0).getRGB(2, 2), "and untouched where it was not");
        assertEquals(0xFFFF0000, sequence.frames().get(1).getRGB(0, 0), "the second frame keeps what came before it");
        assertEquals(0xFF0000FF, sequence.frames().get(1).getRGB(2, 2), "and adds its own rectangle");
    }

    private static BufferedImage sample() {
        BufferedImage image = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
        image.setRGB(3, 4, 0x336699);
        return image;
    }

    private static byte[] write(BufferedImage image, ImageFormat format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter.toStream(image, format, out);
        return out.toByteArray();
    }

    /**
     * Writes a 4x4 GIF holding a red 2x2 frame at (0, 0) and a blue one at (2, 2).
     *
     * <p>The frame offsets are not something the JDK writer sets on its own, so each frame carries
     * them in the native metadata the GIF writer reads: the {@code ImageDescriptor} holds the
     * rectangle, which is also what makes the two frames smaller than the screen.
     */
    private static byte[] offsetGif() throws IOException {
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersBySuffix("gif").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setDestinationType(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            IIOMetadata streamMetadata = writer.getDefaultStreamMetadata(param);
            String streamFormat = streamMetadata.getNativeMetadataFormatName();
            IIOMetadataNode stream = (IIOMetadataNode) streamMetadata.getAsTree(streamFormat);
            IIOMetadataNode screen = gifChild(stream, "LogicalScreenDescriptor");
            screen.setAttribute("logicalScreenWidth", "4");
            screen.setAttribute("logicalScreenHeight", "4");
            streamMetadata.setFromTree(streamFormat, stream);

            writer.prepareWriteSequence(streamMetadata);
            writeGifFrame(writer, param, 0, 0, 2, 2, 0xFFFF0000);
            writeGifFrame(writer, param, 2, 2, 2, 2, 0xFF0000FF);
            writer.endWriteSequence();
        }
        writer.dispose();
        return bytes.toByteArray();
    }

    private static void writeGifFrame(javax.imageio.ImageWriter writer, ImageWriteParam param, int x, int y,
            int width, int height, int argb) throws IOException {
        BufferedImage frame = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                frame.setRGB(column, row, argb);
            }
        }
        IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(frame), param);
        String format = metadata.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
        IIOMetadataNode control = new IIOMetadataNode("GraphicControlExtension");
        control.setAttribute("disposalMethod", "none");
        control.setAttribute("userInputFlag", "FALSE");
        control.setAttribute("transparentColorFlag", "FALSE");
        control.setAttribute("delayTime", "10");
        control.setAttribute("transparentColorIndex", "0");
        root.appendChild(control);
        IIOMetadataNode descriptor = gifChild(root, "ImageDescriptor");
        descriptor.setAttribute("imageLeftPosition", Integer.toString(x));
        descriptor.setAttribute("imageTopPosition", Integer.toString(y));
        descriptor.setAttribute("imageWidth", Integer.toString(width));
        descriptor.setAttribute("imageHeight", Integer.toString(height));
        descriptor.setAttribute("interlaceFlag", "FALSE");
        metadata.setFromTree(format, root);
        writer.writeToSequence(new IIOImage(frame, null, metadata), param);
    }

    /** @return the child element with the given name, adding one when the metadata has none */
    private static IIOMetadataNode gifChild(IIOMetadataNode parent, String name) {
        for (int i = 0; i < parent.getLength(); i++) {
            IIOMetadataNode node = (IIOMetadataNode) parent.item(i);
            if (name.equals(node.getNodeName())) {
                return node;
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        parent.appendChild(node);
        return node;
    }
}
