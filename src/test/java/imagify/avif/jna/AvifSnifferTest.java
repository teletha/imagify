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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the pure Java {@code ftyp} sniffer and the version parser.
 *
 * <p>The sniffer decides whether {@code ImageIO} hands a file to the AVIF reader. It deliberately
 * does not need the native library, so that AVIF files are still recognised, and their metadata can
 * still be inspected, on a machine that cannot decode them.
 */
class AvifSnifferTest {

    @Test
    @DisplayName("a major brand of avif is accepted")
    void majorBrand() {
        assertTrue(AvifCodec.isAvif(ftyp("avif", "mif1", "miaf")));
    }

    @Test
    @DisplayName("avif as the only compatible brand is accepted")
    void compatibleBrand() {
        // This is what a muxed HEIF/AVIF file looks like, and what libavif itself writes.
        assertTrue(AvifCodec.isAvif(ftyp("mif1", "miaf", "avif")));
    }

    @Test
    @DisplayName("avis, the image sequence brand, is accepted")
    void sequenceBrand() {
        assertTrue(AvifCodec.isAvif(ftyp("avis", "avif", "avis")));
    }

    @Test
    @DisplayName("another HEIF brand is rejected")
    void otherFormat() {
        assertFalse(AvifCodec.isAvif(ftyp("heic", "mif1", "heic")));
        assertFalse(AvifCodec.isAvif(ftyp("crx ", "isom", "crx ")));
        assertFalse(AvifCodec.isAvif(ftyp("mif1", "mif1", "heix")));
    }

    @Test
    @DisplayName("a brand that only looks similar is rejected")
    void nearMiss() {
        // Otherwise every HEIF file would be handed to the AVIF reader and fail to decode.
        assertFalse(AvifCodec.isAvif(ftyp("mavi", "mif1", "mif1")));
        assertFalse(AvifCodec.isAvif(ftyp("mif1", "mif1", "avii")));
        assertFalse(AvifCodec.isAvif(ftyp("mif1", "mif1", "avfi")));
    }

    @Test
    @DisplayName("a brand that is not four bytes long cannot be found by stepping four bytes")
    void oddLengthBrand() {
        // The minor version is three bytes here, which shifts the real avif brand one byte to the
        // left of where the sniffer looks. Reading it anyway would mean accepting a file that
        // cannot be decoded, so the sniffer has to walk the box in four byte steps and stay inside.
        assertFalse(AvifCodec.isAvif(oddLengthMinorVersion()));
    }

    @Test
    @DisplayName("a box that is not an ftyp box is rejected")
    void notFtyp() {
        assertFalse(AvifCodec.isAvif(ascii("abcd000000180000004d4f6f7600000000")));
    }

    @Test
    @DisplayName("a truncated header is rejected instead of being read out of bounds")
    void truncated() {
        assertFalse(AvifCodec.isAvif(null));
        assertFalse(AvifCodec.isAvif(new byte[0]));
        assertFalse(AvifCodec.isAvif(ascii("ftyp")));
        assertFalse(AvifCodec.isAvif(ascii("0000000c66747970")));

        // The 64 bit size form needs 16 bytes, and 12 are not enough to hold it.
        assertFalse(AvifCodec.isAvif(ascii("000000010000000000000000")));
        // A valid ftyp carries a minor version, so the brand list has to be at least two entries.
        assertTrue(AvifCodec.isAvif(extendedFtyp("avif", "mif1")));
        assertFalse(AvifCodec.isAvif(extendedFtyp("avif")));

        // An extended size box that stops before the major brand is rejected too.
        assertFalse(AvifCodec.isAvif(ascii("0000000100000000000000146674797061766966")));
    }

    @Test
    @DisplayName("a box size of 0 means the box runs to the end of the file")
    void sizeZero() {
        byte[] data = ftyp("avif", "mif1", "miaf");
        data[3] = 0;
        assertTrue(AvifCodec.isAvif(data));
    }

    @Test
    @DisplayName("a box size below its own header is rejected")
    void nonsenseBoxSize() {
        byte[] data = ftyp("avif", "mif1", "miaf");
        data[3] = 4;
        assertFalse(AvifCodec.isAvif(data));
    }

    @Test
    @DisplayName("a box size beyond the sniffed bytes still finds the brand")
    void boxSizeBeyondInput() {
        // ImageIO only ever sniffs the first few dozen bytes, so the brand has to be found even
        // though the declared box size claims there is much more data.
        byte[] data = ftyp("mif1", "mif1", "avif");
        data[3] = (byte) 0xf0;
        assertTrue(AvifCodec.isAvif(data));
    }

    @Test
    @DisplayName("the version parser keeps major and minor and ignores the rest")
    void versionParsing() {
        assertEquals(100, AvifCodec.parseVersion("1.0.0"));
        assertEquals(100, AvifCodec.parseVersion("1.0.3"));
        assertEquals(101, AvifCodec.parseVersion("1.1.0"));
        assertEquals(102, AvifCodec.parseVersion("1.2.4"));
        assertEquals(103, AvifCodec.parseVersion("1.3.0"));
        assertEquals(104, AvifCodec.parseVersion("1.4.2"));
        assertEquals(100, AvifCodec.parseVersion("1.0.0 (a1b2c3d)"));
        assertEquals(103, AvifCodec.parseVersion("1.3.0-rc1"));
        assertEquals(200, AvifCodec.parseVersion("2.0.0"));
        assertEquals(9, AvifCodec.parseVersion("0.9.0"));
    }

    @Test
    @DisplayName("a version string without a number is rejected")
    void unparsableVersion() {
        assertThrows(IllegalStateException.class, () -> AvifCodec.parseVersion("unknown"));
        assertThrows(IllegalStateException.class, () -> AvifCodec.parseVersion(""));
    }

    @Test
    @DisplayName("the advertised range is the one the bindings were verified against")
    void supportedRange() {
        // The JNA structures were compared against avif.h 1.0.3 through 1.4.2. Anything outside
        // that window may have moved a member, so it is refused rather than risking corruption.
        assertTrue(AvifCodec.parseVersion("1.0.0") >= 100, "1.0.0 must be accepted");
        assertTrue(AvifCodec.parseVersion("1.4.9") <= 104, "1.4.x must be accepted");
        assertFalse(AvifCodec.parseVersion("1.5.0") <= 104, "1.5 must be refused");
        assertFalse(AvifCodec.parseVersion("0.11.0") >= 100, "0.x must be refused");
        assertEquals("1.0 - 1.4", AvifCodec.supportedVersions());
    }

    // ------------------------------------------------------------------------------- helpers

    /**
     * @param brands the major brand followed by the minor version and the compatible brands
     * @return a well formed 32 bit size {@code ftyp} box
     */
    private static byte[] ftyp(String... brands) {
        int size = 8 + brands.length * 4;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUInt32(out, size);
        writeAscii(out, "ftyp");
        for (String brand : brands) {
            writeAscii(out, brand);
        }
        return out.toByteArray();
    }

    /**
     * @param brands the major brand followed by the minor version and the compatible brands
     * @return a well formed {@code ftyp} box that uses the 64 bit extended size form
     */
    private static byte[] extendedFtyp(String... brands) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUInt32(out, 1);
        writeAscii(out, "ftyp");
        writeUInt32(out, 0);
        writeUInt32(out, 24 + brands.length * 4);
        for (String brand : brands) {
            writeAscii(out, brand);
        }
        return out.toByteArray();
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * @return an {@code ftyp} box with a three byte minor version, which is malformed and shifts
     *         every later brand one byte out of a four byte grid
     */
    private static byte[] oddLengthMinorVersion() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUInt32(out, 8 + 4 + 3 + 4);
        writeAscii(out, "ftyp");
        writeAscii(out, "mif1");
        out.writeBytes(ascii("mif"));
        writeAscii(out, "avif");
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) {
        if (text.length() != 4) {
            throw new IllegalArgumentException("a brand is four characters long, got: " + text);
        }
        out.writeBytes(ascii(text));
    }

    private static void writeUInt32(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }
}
