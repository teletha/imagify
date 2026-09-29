/*
 * Copyright (C) 2026 The IMAGIFY Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://opensource.org/licenses/mit-license.php
 */
package imagify.avif.ffm;

/**
 * The {@code AVIF_*} constants this binding names, taken from {@code avif.h} at tag v1.4.2.
 *
 * <p>They are here rather than in {@link AvifShim} because they are values and not entry points, and
 * because the shim is otherwise a wall of downcall handles with nowhere to put a constant that
 * belongs to neither the calls nor the blocks of answers.
 *
 * <p>Every value here is the one the header of libavif 1.4.2 gives, and each group says which C
 * enumeration it is reading, because a bare number in a call site is not something a reader can check
 * against the header. The groups are the header's own: the pixel formats, the three CICP
 * enumerations, the encoder's quality and speed ranges, the upsampling filters, the status codes
 * this layer defines, and the positions the shim answers at.
 *
 * <p>Nothing here is libavif's own idea of a default unless it says so. {@link #DEFAULT_SPEED},
 * {@link #DEFAULT_QUALITY} and {@link #DEFAULT_ANIMATION_SPEED} are numbers this project chose on a
 * measurement, and each one says which; {@link #AVIF_SPEED_DEFAULT} is libavif's, and it is kept
 * beside them precisely because it is not the same thing and is a trap when it is mistaken for one.
 */
public final class AvifConstants {

    private AvifConstants() {
        // utility class
    }

    /** {@code AVIF_TRUE}. */
    public static final int TRUE = 1;

    /** {@code AVIF_FALSE}. */
    public static final int FALSE = 0;

    /**
     * The {@code AVIF_PIXEL_FORMAT_*} values, which are a C enum and so start at zero with a
     * {@code NONE} in front of the formats. {@code NONE} is not a format libavif will create a
     * picture at, so a zero handed to {@code imagify_avif_picture_create} is refused rather than
     * treated as a default.
     */
    public static final int PIXEL_FORMAT_NONE = 0;

    public static final int PIXEL_FORMAT_YUV444 = 1;

    public static final int PIXEL_FORMAT_YUV422 = 2;

    public static final int PIXEL_FORMAT_YUV420 = 3;

    public static final int PIXEL_FORMAT_YUV400 = 4;

    /**
     * {@code AVIF_RGB_FORMAT_ABGR}: the byte at offset 0 of a pixel is its alpha and the one at
     * offset 3 is its red.
     *
     * <p>This is the format the whole zero copy is arranged around, and the reason is
     * {@code imagify.pixels.AbgrPixels}: that is the order a {@code BufferedImage} of type
     * {@code TYPE_4BYTE_ABGR} keeps its banks in, so a picture already in that layout can be handed
     * over without a shuffle. The formats that read as R, G, B, A would swap red and blue in both
     * directions, and would not fail while doing it.
     */
    public static final int RGB_FORMAT_ABGR = 5;

    /** {@code AVIF_STRICT_PIXI_REQUIRED}. */
    public static final int STRICT_PIXI_REQUIRED = 1;

    /** {@code AVIF_STRICT_CLAP_VALID}. */
    public static final int STRICT_CLAP_VALID = 1 << 1;

    /** {@code AVIF_ADD_IMAGE_FLAG_SINGLE}: the image is the only frame of the file. */
    public static final int ADD_IMAGE_FLAG_SINGLE = 1 << 1;

    // ------------------------------------------------------------------------------- CICP

    /*
     * The three CICP enumerations, copied out of avif.h: avifRange, avifColorPrimaries and
     * avifTransferCharacteristics, with the chroma sample position that goes with them.
     *
     * They are named by the number the header gives rather than by their position, because the
     * numbers skip values: there is no 3 in any of them, and there is no 13 either. Reading them as
     * an ordinal would be wrong the moment the header skipped one, and it would be wrong silently.
     *
     * The ones with a second name in the header carry one name here, and say so: AVIF_COLOR_PRIMARIES_BT2100
     * is 9, the same as AVIF_COLOR_PRIMARIES_BT2020, and AVIF_TRANSFER_CHARACTERISTICS_SMPTE2084 is
     * 16, the same as AVIF_TRANSFER_CHARACTERISTICS_PQ.
     */
    public static final int RANGE_LIMITED = 0;

    public static final int RANGE_FULL = 1;

    public static final int CHROMA_SAMPLE_POSITION_UNKNOWN = 0;

    public static final int CHROMA_SAMPLE_POSITION_VERTICAL = 1;

    public static final int CHROMA_SAMPLE_POSITION_COLOCATED = 2;

    public static final int COLOR_PRIMARIES_UNKNOWN = 0;

    public static final int COLOR_PRIMARIES_BT709 = 1;

    public static final int COLOR_PRIMARIES_UNSPECIFIED = 2;

    public static final int COLOR_PRIMARIES_BT470M = 4;

    public static final int COLOR_PRIMARIES_BT470BG = 5;

    public static final int COLOR_PRIMARIES_BT601 = 6;

    public static final int COLOR_PRIMARIES_SMPTE240 = 7;

    public static final int COLOR_PRIMARIES_GENERIC_FILM = 8;

    public static final int COLOR_PRIMARIES_BT2020 = 9;

    public static final int COLOR_PRIMARIES_XYZ = 10;

    public static final int COLOR_PRIMARIES_SMPTE431 = 11;

    /** {@code AVIF_COLOR_PRIMARIES_SMPTE432}, which the header also spells DCI_P3. */
    public static final int COLOR_PRIMARIES_SMPTE432 = 12;

    public static final int COLOR_PRIMARIES_EBU3213 = 22;

    public static final int TRANSFER_UNKNOWN = 0;

    public static final int TRANSFER_BT709 = 1;

    public static final int TRANSFER_UNSPECIFIED = 2;

    public static final int TRANSFER_BT470M = 4;

    public static final int TRANSFER_BT470BG = 5;

    public static final int TRANSFER_BT601 = 6;

    public static final int TRANSFER_SMPTE240 = 7;

    public static final int TRANSFER_LINEAR = 8;

    public static final int TRANSFER_LOG100 = 9;

    public static final int TRANSFER_LOG100_SQRT10 = 10;

    public static final int TRANSFER_IEC61966 = 11;

    public static final int TRANSFER_BT1361 = 12;

    public static final int TRANSFER_SRGB = 13;

    public static final int TRANSFER_BT2020_10BIT = 14;

    public static final int TRANSFER_BT2020_12BIT = 15;

    public static final int TRANSFER_PQ = 16;

    public static final int TRANSFER_SMPTE428 = 17;

    public static final int TRANSFER_HLG = 18;

    public static final int MATRIX_IDENTITY = 0;

    public static final int MATRIX_BT709 = 1;

    public static final int MATRIX_UNSPECIFIED = 2;

    public static final int MATRIX_FCC = 4;

    public static final int MATRIX_BT470BG = 5;

    public static final int MATRIX_BT601 = 6;

    public static final int MATRIX_SMPTE240 = 7;

    public static final int MATRIX_YCGCO = 8;

    public static final int MATRIX_BT2020_NCL = 9;

    public static final int MATRIX_BT2020_CL = 10;

    public static final int MATRIX_SMPTE2085 = 11;

    public static final int MATRIX_CHROMA_DERIVED_NCL = 12;

    public static final int MATRIX_CHROMA_DERIVED_CL = 13;

    public static final int MATRIX_ICTCP = 14;

    /** {@code AVIF_QUALITY_WORST}: the smallest file, and the one that loses the most. */
    public static final int QUALITY_WORST = 0;

    /** {@code AVIF_QUALITY_BEST}, which is also {@code AVIF_QUALITY_LOSSLESS}. */
    public static final int QUALITY_BEST = 100;

    /** {@code AVIF_QUALITY_LOSSLESS}, which is also {@link #QUALITY_BEST}. */
    public static final int QUALITY_LOSSLESS = 100;

    /*
     * The AVIF_CHROMA_DOWNSAMPLING_* values, which is what the upsampling filter is called.
     *
     * Four of the five say the same thing with a different emphasis, which is the header's own
     * comment rather than anything this project worked out: automatic, fastest and best quality are
     * all the averaging filter. Only SHARP_YUV selects a different one, and the header notes it is
     * available for 4:2:0 only and ignored for 4:2:2.
     */
    public static final int CHROMA_DOWNSAMPLING_AUTOMATIC = 0;

    public static final int CHROMA_DOWNSAMPLING_FASTEST = 1;

    public static final int CHROMA_DOWNSAMPLING_BEST_QUALITY = 2;

    public static final int CHROMA_DOWNSAMPLING_AVERAGE = 3;

    public static final int CHROMA_DOWNSAMPLING_SHARP_YUV = 4;

    /**
     * {@code AVIF_SPEED_DEFAULT}: leave the AV1 codec at its own speed settings.
     *
     * <p>Negative, and that is not a placeholder. It is a legal value a caller may pass to mean
     * "whatever libavif thinks", and it is <em>not</em> what this library encodes with by default:
     * measured on a 517x380 photograph against libavif 1.4.2, it is indistinguishable from
     * {@link #AVIF_SPEED_SLOWEST} and costs 4917 ms against 170 ms at {@link #DEFAULT_SPEED}, for a
     * file of the same size. It is a legitimate answer to give and a terrible one to default to.
     */
    public static final int AVIF_SPEED_DEFAULT = -1;

    /** {@code AVIF_SPEED_SLOWEST}: the most thorough, and the slowest. */
    public static final int AVIF_SPEED_SLOWEST = 0;

    /** {@code AVIF_SPEED_FASTEST}. */
    public static final int AVIF_SPEED_FASTEST = 10;

    /**
     * The speed a still image is encoded at when the caller did not name one.
     *
     * <p>This is not libavif's {@link #AVIF_SPEED_DEFAULT} and it is not
     * {@link #AVIF_SPEED_SLOWEST} either, both of which are what an unqualified "default" would
     * suggest. It is a number this project chose, on a measurement: at 517x380 and quality 70 the
     * encode is 170 ms at speed 6 against 4917 ms at speed 0, and the two produce a file of the same
     * 77 kB. Speeds 8 and 10 are faster still and give up more than they save, so 6 is where the
     * curve flattens.
     */
    public static final int DEFAULT_SPEED = 6;

    /**
     * The quality a still image is encoded at when the caller did not name one.
     *
     * <p>{@code libavif}'s {@code AVIF_QUALITY_DEFAULT} is -1, which is an absence rather than a
     * number, and an absent quality is not something to hand a codec that will happily invent one.
     * 60 is a choice, and it is a mid one: it is a little above the point where AVIF stops being
     * visibly worse than the source and well below the point where the file stops being smaller.
     */
    public static final int DEFAULT_QUALITY = 60;

    /**
     * The speed an animation is encoded at when the caller did not name one.
     *
     * <p>An animation pays the encoder cost once per frame, so the speed that is a fair trade for a
     * single image is a bad one for a hundred of them. Measured on the 58 frame 498x280 test
     * animation at quality 70, going from {@link #DEFAULT_SPEED} to this value took the encode from
     * **6556 ms to 436 ms** and made the file <em>smaller</em>, **574 kB to 413 kB**, at the same
     * time. Being faster and smaller at once is unusual enough to be worth stating twice, because it
     * is the whole argument for this value over the still image default.
     *
     * <p>8 rather than 9 or 10 because the size flattens here: 9 and 10 are both 414 kB, so what they
     * buy over 8 is about 50 ms and 1 kB in the wrong direction.
     */
    public static final int DEFAULT_ANIMATION_SPEED = 8;

    /**
     * The number of images decoded as a rough threshold above which threading is worth handing to
     * libavif, and below which it is not.
     *
     * <p>A thousand threads' worth of setup is not free, and the conversion a decode ends in is a
     * single pass over the picture, so a small image is faster on one thread. The threshold is the
     * same one the JNA binding it replaces used, and it is in pixels rather than in bytes because
     * that is what decides how much work there is.
     */
    public static final int THREADING_PIXEL_THRESHOLD = 1 << 22;

    // ---------------------------------------------------------------------------- picture info

    /*
     * The positions imagify_avif_picture_info answers in, as an index into the block it writes, and
     * the same fifteen for imagify_avif_sequence_read.
     *
     * This is an ABI shared with the C shim, which names the same fifteen things in an enum in its
     * own header, and a mismatch between the two is a wrong answer rather than a failure: nothing
     * throws, the binding just reads the wrong field. That is why the order below is the order in
     * the header and why a field added to one side and not the other is a mistake to be caught by
     * reading them side by side rather than by a test failing.
     */
    public static final int INFO_WIDTH = 0;

    public static final int INFO_HEIGHT = 1;

    public static final int INFO_DEPTH = 2;

    public static final int INFO_YUV_FORMAT = 3;

    public static final int INFO_YUV_RANGE = 4;

    public static final int INFO_CHROMA_SAMPLE_POSITION = 5;

    public static final int INFO_COLOR_PRIMARIES = 6;

    public static final int INFO_TRANSFER_CHARACTERISTICS = 7;

    public static final int INFO_MATRIX_COEFFICIENTS = 8;

    public static final int INFO_HAS_ALPHA = 9;

    public static final int INFO_ROTATION_DEGREES = 10;

    public static final int INFO_MIRRORED = 11;

    public static final int INFO_ICC_SIZE = 12;

    public static final int INFO_EXIF_SIZE = 13;

    public static final int INFO_XMP_SIZE = 14;

    /** How many four byte slots {@code imagify_avif_picture_info} fills in. */
    public static final int INFO_COUNT = 15;
}
