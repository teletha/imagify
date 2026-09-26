/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.avif.jna;

import com.sun.jna.Structure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the JNA structure layouts to the ones {@code include/avif/avif.h} declares.
 *
 * <p>A wrong member width shifts every member behind it and a wrong member order makes {@code libavif}
 * read the wrong field, and neither shows up as an exception: the pixel data simply comes out wrong.
 * Comparing the computed offsets against the ones worked out by hand from the header is therefore
 * the only cheap way to notice a mistake before it corrupts memory at run time.
 *
 * <p>Every expectation below was derived from {@code avif.h} 1.0.3 and stays valid for 1.1.0 through
 * 1.4.x, because {@code libavif} only ever appends members after the {@code "Version 1.0.0 ends
 * here."} marker. Structures that are shorter than the native ones are fine: only the identically
 * placed leading members are ever touched, and {@code libavif} allocates the full native size.
 *
 * <p>No native library is needed; JNA lays a {@link Structure} out from its field metadata alone.
 * The expectations assume a 64 bit JVM, because {@code size_t} and pointers are 8 bytes wide on both
 * LP64 and Windows LLP64 and JNA maps them to Java {@code long} and {@code Pointer}.
 */
class AvifLayoutTest {

    @Test
    @DisplayName("a 64 bit JVM is required, because size_t and pointers are mapped to long")
    void sixtyFourBit() {
        assumeTrue(com.sun.jna.Native.SIZE_T_SIZE == 8, "skipped: this is not a 64 bit JVM");
    }

    @Test
    @DisplayName("avifRWData and avifROData are {pointer, size_t}")
    void buffers() {
        // size_t is 8 bytes on Windows too, which is why these are Java longs and not NativeLong:
        // NativeLong would be 4 bytes there and shift nothing, silently truncating every size.
        assertLayout(AvifRWData.class, 16, 0, "data", 8, "size");
        assertLayout(AvifROData.class, 16, 0, "data", 8, "size");
    }

    @Test
    @DisplayName("avifIOStats is {size_t, size_t}")
    void ioStats() {
        assertLayout(AvifIOStats.class, 16, 0, "colorOBUSize", 8, "alphaOBUSize");
    }

    @Test
    @DisplayName("avifDiagnostics is {char error[256]}")
    void diagnostics() {
        assertLayout(AvifDiagnostics.class, 256, 0, "error");
    }

    @Test
    @DisplayName("avifImageTiming is {uint64, double, uint64, double, uint64}")
    void imageTiming() {
        assertLayout(AvifImageTiming.class, 40,
                0, "timescale",
                8, "pts",
                16, "ptsInTimescales",
                24, "duration",
                32, "durationInTimescales");
    }

    @Test
    @DisplayName("avifImage keeps the 1.0.0 member order")
    void image() {
        // The 3 pointer arrays and the 3 row byte arrays pack tightly: 3 * 8 + 3 * 4 = 36, so
        // imageOwnsYUVPlanes lands on offset 60, one padding byte behind yuvRowBytes[2].
        assertLayout(AvifImage.class, 200,
                0, "width",
                4, "height",
                8, "depth",
                12, "yuvFormat",
                16, "yuvRange",
                20, "yuvChromaSamplePosition",
                24, "yuvPlanes",
                48, "yuvRowBytes",
                60, "imageOwnsYUVPlanes",
                64, "alphaPlane",
                72, "alphaRowBytes",
                76, "imageOwnsAlphaPlane",
                80, "alphaPremultiplied",
                88, "icc",
                104, "colorPrimaries",
                108, "transferCharacteristics",
                112, "matrixCoefficients",
                116, "clli",
                120, "transformFlags",
                124, "pasp",
                132, "clap",
                164, "irot",
                165, "imir",
                // 3 padding bytes so that exif lands on an 8 byte boundary
                168, "exif",
                184, "xmp");
    }

    @Test
    @DisplayName("avifImage's nested property boxes match the C types")
    void imageBoxes() {
        assertLayout(AvifImage.ContentLightLevelInformationBox.class, 4, 0, "maxCLL", 2, "maxPALL");
        assertLayout(AvifImage.PixelAspectRatioBox.class, 8, 0, "hSpacing", 4, "vSpacing");
        assertLayout(AvifImage.CleanApertureBox.class, 32,
                0, "widthN", 4, "widthD", 8, "heightN", 12, "heightD",
                16, "horizOffN", 20, "horizOffD", 24, "vertOffN", 28, "vertOffD");
    }

    @Test
    @DisplayName("avifRGBImage keeps the 1.0.0 member order")
    void rgbImage() {
        assertLayout(AvifRGBImage.class, 64,
                0, "width",
                4, "height",
                8, "depth",
                12, "format",
                16, "chromaUpsampling",
                20, "chromaDownsampling",
                24, "avoidLibYUV",
                28, "ignoreAlpha",
                32, "alphaPremultiplied",
                36, "isFloat",
                40, "maxThreads",
                48, "pixels",
                56, "rowBytes");
    }

    @Test
    @DisplayName("avifDecoder keeps the 1.0.0 member order")
    void decoder() {
        // 1.1.0 appended imageSequenceTrackPresent and 1.3.0 imageContentToDecode after data, so the
        // native structure is 8 bytes longer than 432. Only the leading members are declared.
        assertLayout(AvifDecoder.class, 432,
                0, "codecChoice",
                4, "maxThreads",
                8, "requestedSource",
                12, "allowProgressive",
                16, "allowIncremental",
                20, "ignoreExif",
                24, "ignoreXMP",
                28, "imageSizeLimit",
                32, "imageDimensionLimit",
                36, "imageCountLimit",
                40, "strictFlags",
                48, "image",
                56, "imageIndex",
                60, "imageCount",
                64, "progressiveState",
                72, "imageTiming",
                112, "timescale",
                120, "duration",
                128, "durationInTimescales",
                136, "repetitionCount",
                140, "alphaPresent",
                144, "ioStats",
                160, "diag",
                416, "io",
                424, "data");
    }

    @Test
    @DisplayName("avifEncoder keeps the 1.0.0 member order")
    void encoder() {
        // timescale is a uint64_t in avifEncoder, which is what pushes repetitionCount to offset 24
        // and makes the whole structure 376 rather than 384 bytes. 1.1.0 appended headerFormat,
        // 1.2.0 qualityGainMap; both sit behind data and are not declared.
        assertLayout(AvifEncoder.class, 376,
                0, "codecChoice",
                4, "maxThreads",
                8, "speed",
                12, "keyframeInterval",
                16, "timescale",
                24, "repetitionCount",
                28, "extraLayerCount",
                32, "quality",
                36, "qualityAlpha",
                40, "minQuantizer",
                44, "maxQuantizer",
                48, "minQuantizerAlpha",
                52, "maxQuantizerAlpha",
                56, "tileRowsLog2",
                60, "tileColsLog2",
                64, "autoTiling",
                68, "scalingMode",
                88, "ioStats",
                104, "diag",
                360, "data",
                368, "csOptions");
    }

    @Test
    @DisplayName("avifScalingMode is two avifFraction values")
    void scalingMode() {
        assertLayout(AvifEncoder.ScalingMode.class, 16, 0, "horizontal", 8, "vertical");
        assertLayout(AvifEncoder.Fraction.class, 8, 0, "n", 4, "d");
    }

    @Test
    @DisplayName("a ByReference struct is a marker, so it must not add state")
    void byReference() {
        // JNA 5 turned Structure.ByReference into a plain marker interface. If it had a
        // superinterface with state the native size would differ from the value struct's.
        assertEquals(AvifImage.class, AvifImage.ByReference.class.getSuperclass());
    }

    // --------------------------------------------------------------------------------- helpers

    /**
     * Asserts the size of a structure and the offset of each of the named members.
     *
     * <p>Arguments after the expected size alternate between an expected byte offset and the name of
     * the member that sits there, in declaration order. Listing every declared member is the point:
     * a member that is added or renamed without updating this test fails here rather than at run
     * time in native code. Putting the offset first makes a listing read like the header does.
     *
     * @param type the structure class
     * @param expectedSize the size in bytes
     * @param offsetAndName alternating byte offsets and member names
     */
    private static void assertLayout(Class<? extends Structure> type, int expectedSize, Object... offsetAndName) {
        assertEquals(0, offsetAndName.length % 2, "offsets and names must alternate");
        assumeTrue(com.sun.jna.Native.SIZE_T_SIZE == 8, "skipped: this is not a 64 bit JVM");

        Structure structure = newStructure(type);
        // write() forces JNA to compute the layout; size() alone is not enough because the nested
        // structures are only sized as part of the same pass.
        structure.write();
        assertEquals(expectedSize, structure.size(), type.getSimpleName() + " has an unexpected size");

        Map<String, Integer> expected = new LinkedHashMap<>();
        for (int i = 0; i < offsetAndName.length; i += 2) {
            expected.put((String) offsetAndName[i + 1], (Integer) offsetAndName[i]);
        }
        Map<String, Integer> actual = offsets(structure);
        assertEquals(expected.keySet(), actual.keySet(),
                type.getSimpleName() + " declares a different set of members than the header does");
        for (Map.Entry<String, Integer> member : expected.entrySet()) {
            assertEquals(member.getValue(), actual.get(member.getKey()),
                    type.getSimpleName() + "." + member.getKey() + " sits at the wrong offset");
        }
    }

    private static Structure newStructure(Class<? extends Structure> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(type.getName() + " needs a public no-arg constructor", e);
        }
    }

    /**
     * @return the offset of every declared member, in declaration order
     */
    private static Map<String, Integer> offsets(Structure structure) {
        Map<String, Integer> offsets = new LinkedHashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Method fieldOffset = Structure.class.getDeclaredMethod("fieldOffset", String.class);
            fieldOffset.setAccessible(true);
            @SuppressWarnings("unchecked")
            Method getFields = Structure.class.getDeclaredMethod("getFields", boolean.class);
            getFields.setAccessible(true);
            for (java.lang.reflect.Field member : (java.util.List<java.lang.reflect.Field>) getFields
                    .invoke(structure, false)) {
                offsets.put(member.getName(), (Integer) fieldOffset.invoke(structure, member.getName()));
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot read the member offsets of " + structure.getClass(), e);
        }
        return offsets;
    }
}
