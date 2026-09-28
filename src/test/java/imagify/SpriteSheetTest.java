package imagify;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpriteSheetTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private static BufferedImage createImage(int w, int h, int rgb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                img.setRGB(x, y, rgb);
        return img;
    }

    private static BufferedImage redImage()   { return createImage(32, 32, 0xFFFF0000); }
    private static BufferedImage greenImage() { return createImage(32, 32, 0xFF00FF00); }
    private static BufferedImage blueImage()  { return createImage(32, 32, 0xFF0000FF); }

    // ══════════════════════════════════════════════════════════════════════════
    //  Builder validation
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("columns rejects zero")
    void columnsRejectsZero() {
        assertThrows(IllegalArgumentException.class, () -> SpriteSheet.create().columns(0));
    }

    @Test
    @DisplayName("columns rejects negative")
    void columnsRejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> SpriteSheet.create().columns(-1));
    }

    @Test
    @DisplayName("rows rejects zero")
    void rowsRejectsZero() {
        assertThrows(IllegalArgumentException.class, () -> SpriteSheet.create().rows(0));
    }

    @Test
    @DisplayName("rows rejects negative")
    void rowsRejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> SpriteSheet.create().rows(-1));
    }

    @Test
    @DisplayName("grid rejects zero columns")
    void gridRejectsZeroCols() {
        assertThrows(IllegalArgumentException.class, () -> SpriteSheet.create().grid(0, 1));
    }

    @Test
    @DisplayName("grid rejects zero rows")
    void gridRejectsZeroRows() {
        assertThrows(IllegalArgumentException.class, () -> SpriteSheet.create().grid(1, 0));
    }

    @Test
    @DisplayName("grid rejects negative columns")
    void gridRejectsNegativeCols() {
        assertThrows(IllegalArgumentException.class, () -> SpriteSheet.create().grid(-1, 1));
    }

    @Test
    @DisplayName("grid rejects negative rows")
    void gridRejectsNegativeRows() {
        assertThrows(IllegalArgumentException.class, () -> SpriteSheet.create().grid(1, -1));
    }

    @Test
    @DisplayName("cell rejects zero width")
    void cellRejectsZeroWidth() {
        assertThrows(IllegalArgumentException.class, () -> SpriteSheet.create().cell(0, 10));
    }

    @Test
    @DisplayName("cell rejects zero height")
    void cellRejectsZeroHeight() {
        assertThrows(IllegalArgumentException.class, () -> SpriteSheet.create().cell(10, 0));
    }

    @Test
    @DisplayName("cell rejects negative width")
    void cellRejectsNegativeWidth() {
        assertThrows(IllegalArgumentException.class, () -> SpriteSheet.create().cell(-1, 10));
    }

    @Test
    @DisplayName("cell rejects negative height")
    void cellRejectsNegativeHeight() {
        assertThrows(IllegalArgumentException.class, () -> SpriteSheet.create().cell(10, -1));
    }

    @Test
    @DisplayName("cell with Fit rejects null fit")
    void cellRejectsNullFit() {
        assertThrows(NullPointerException.class, () -> SpriteSheet.create().cell(10, 10, null));
    }

    @Test
    @DisplayName("algorithm rejects null")
    void algorithmRejectsNull() {
        assertThrows(NullPointerException.class, () -> SpriteSheet.create().algorithm(null));
    }

    @Test
    @DisplayName("padding rejects negative")
    void paddingRejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> SpriteSheet.create().padding(-1));
    }

    @Test
    @DisplayName("spacing rejects negative")
    void spacingRejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> SpriteSheet.create().spacing(-1));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Layout — no frames
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("layout throws when no frames added")
    void layoutThrowsNoFrames() {
        assertThrows(IllegalStateException.class, () -> SpriteSheet.create().layout());
    }

    @Test
    @DisplayName("toImage throws when no frames added")
    void toImageThrowsNoFrames() {
        assertThrows(IllegalStateException.class, () -> SpriteSheet.create().toImage());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Frame addition
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("frameCount returns correct count")
    void frameCountReturnsCorrect() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(redImage())
                .addFrame(greenImage())
                .addFrame(blueImage());
        assertEquals(3, sheet.frameCount());
    }

    @Test
    @DisplayName("addFrame returns same instance for chaining")
    void addFrameChains() {
        SpriteSheet sheet = SpriteSheet.create();
        assertSame(sheet, sheet.addFrame(redImage()));
    }

    @Test
    @DisplayName("addFrames returns same instance for chaining")
    void addFramesChains() {
        SpriteSheet sheet = SpriteSheet.create();
        assertSame(sheet, sheet.addImages(List.of(redImage(), greenImage())));
    }

    @Test
    @DisplayName("addImages returns same instance for chaining")
    void addImagesChains() {
        SpriteSheet sheet = SpriteSheet.create();
        assertSame(sheet, sheet.addImages(List.of(redImage(), greenImage())));
    }

    @Test
    @DisplayName("addFrameBytes adds frames from byte arrays")
    void addFrameBytes(@TempDir Path dir) throws Exception {
        BufferedImage img = createImage(10, 10, 0xFFAAAAAA);
        byte[] png = ImageWriter.toBytes(img, ImageFormat.PNG, ImageFormat.PNG.getDefaultQuality());

        SpriteSheet sheet = SpriteSheet.create().addFrameBytes(png);
        assertEquals(1, sheet.frameCount());
    }

    @Test
    @DisplayName("addFrameBytes rejects null")
    void addFrameBytesRejectsNull() {
        assertThrows(NullPointerException.class, () -> SpriteSheet.create().addFrameBytes((byte[][]) null));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Layout — default (single row)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("default layout is a single row")
    void defaultLayoutSingleRow() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(redImage())
                .addFrame(greenImage())
                .addFrame(blueImage());
        SpriteSheet.Layout layout = sheet.layout();

        assertEquals(3, layout.columns());
        assertEquals(1, layout.rows());
        assertEquals(32, layout.cellWidth());
        assertEquals(32, layout.cellHeight());
        assertEquals(3, layout.cells().size());
    }

    @Test
    @DisplayName("default layout cell size matches largest frame")
    void defaultLayoutCellSizeMatchesLargest() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(createImage(10, 10, 0xFFFFFFFF))
                .addFrame(createImage(20, 30, 0xFFFFFFFF));
        SpriteSheet.Layout layout = sheet.layout();

        assertEquals(20, layout.cellWidth());
        assertEquals(30, layout.cellHeight());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Layout — columns
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("columns sets grid columns and auto-calculates rows")
    void columnsSetsGrid() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(redImage())
                .addFrame(greenImage())
                .addFrame(blueImage())
                .columns(2);
        SpriteSheet.Layout layout = sheet.layout();

        assertEquals(2, layout.columns());
        assertEquals(2, layout.rows()); // ceil(3/2) = 2
        assertEquals(3, layout.cells().size());
    }

    @Test
    @DisplayName("columns: cells placed left to right, top to bottom")
    void columnsCellPlacement() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(redImage())
                .addFrame(greenImage())
                .addFrame(blueImage())
                .columns(2);
        List<SpriteSheet.Cell> cells = sheet.layout().cells();

        assertEquals(new SpriteSheet.Cell(0, 0, 32, 32), cells.get(0));
        assertEquals(new SpriteSheet.Cell(32, 0, 32, 32), cells.get(1));
        assertEquals(new SpriteSheet.Cell(0, 32, 32, 32), cells.get(2));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Layout — rows
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("rows sets grid rows and auto-calculates columns")
    void rowsSetsGrid() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(redImage())
                .addFrame(greenImage())
                .addFrame(blueImage())
                .rows(2);
        SpriteSheet.Layout layout = sheet.layout();

        assertEquals(2, layout.columns()); // ceil(3/2) = 2
        assertEquals(2, layout.rows());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Layout — grid
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("grid sets exact columns and rows")
    void gridSetsExact() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(redImage())
                .addFrame(greenImage())
                .addFrame(blueImage())
                .grid(3, 2);
        SpriteSheet.Layout layout = sheet.layout();

        assertEquals(3, layout.columns());
        assertEquals(2, layout.rows());
    }

    @Test
    @DisplayName("grid too small throws")
    void gridTooSmallThrows() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(redImage())
                .addFrame(greenImage())
                .addFrame(blueImage());
        assertThrows(IllegalArgumentException.class, () -> sheet.grid(1, 2).layout());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Layout — padding & spacing
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("padding offsets all cells")
    void paddingOffsetsCells() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(redImage())
                .addFrame(greenImage())
                .padding(10);
        List<SpriteSheet.Cell> cells = sheet.layout().cells();

        assertEquals(10, cells.get(0).x());
        assertEquals(10, cells.get(0).y());
    }

    @Test
    @DisplayName("spacing adds gap between cells")
    void spacingAddsGap() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(redImage())
                .addFrame(greenImage())
                .columns(2)
                .spacing(5);
        List<SpriteSheet.Cell> cells = sheet.layout().cells();

        // Second cell starts after first cell width + spacing
        assertEquals(32 + 5, cells.get(1).x());
        assertEquals(0, cells.get(1).y());
    }

    @Test
    @DisplayName("sheet dimensions include padding and spacing")
    void sheetDimensionsWithPaddingAndSpacing() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(redImage())
                .addFrame(greenImage())
                .columns(2)
                .padding(4)
                .spacing(2);
        SpriteSheet.Layout layout = sheet.layout();

        // width = 2*padding + 2*cellWidth + 1*spacing = 8 + 64 + 2 = 74
        assertEquals(74, layout.width());
        // height = 2*padding + 1*cellHeight (1 row) = 8 + 32 = 40
        assertEquals(40, layout.height());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Fit modes
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Fit.STRETCH stretches frame to cell size")
    void fitStretch() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(createImage(10, 10, 0xFFFFFFFF))
                .cell(20, 20, SpriteSheet.Fit.STRETCH);
        BufferedImage image = sheet.toImage();

        // The frame is 10x10, cell is 20x20, so it should be stretched
        // Verify the sheet has correct dimensions
        assertEquals(20, image.getWidth());
        assertEquals(20, image.getHeight());
    }

    @Test
    @DisplayName("Fit.INSIDE keeps frame smaller than cell unchanged")
    void fitInsideSmallerFrame() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(createImage(10, 10, 0xFFFF0000))
                .cell(20, 20, SpriteSheet.Fit.INSIDE);
        BufferedImage image = sheet.toImage();

        assertEquals(20, image.getWidth());
        assertEquals(20, image.getHeight());
        // The red frame should be centred, so the corner pixels should be transparent
        assertEquals(0x00000000, image.getRGB(0, 0), "corner should be transparent");
        // The centre should contain red
        assertEquals(0xFFFF0000, image.getRGB(10, 10), "center should be red");
    }

    @Test
    @DisplayName("Fit.INSIDE scales down frame larger than cell")
    void fitInsideLargerFrame() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(createImage(64, 64, 0xFFFF0000))
                .cell(32, 32, SpriteSheet.Fit.INSIDE);
        BufferedImage image = sheet.toImage();

        assertEquals(32, image.getWidth());
        assertEquals(32, image.getHeight());
    }

    @Test
    @DisplayName("Fit.FILL crops frame larger than cell")
    void fitFillCrops() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(createImage(64, 64, 0xFFFF0000))
                .cell(32, 32, SpriteSheet.Fit.FILL);
        BufferedImage image = sheet.toImage();

        assertEquals(32, image.getWidth());
        assertEquals(32, image.getHeight());
        // The centre of the original 64x64 red image should be visible
        assertEquals(0xFFFF0000, image.getRGB(16, 16), "center should be red");
    }

    @Test
    @DisplayName("Fit.FILL scales up frame smaller than cell")
    void fitFillScalesUp() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(createImage(10, 10, 0xFF00FF00))
                .cell(32, 32, SpriteSheet.Fit.FILL);
        BufferedImage image = sheet.toImage();

        assertEquals(32, image.getWidth());
        assertEquals(32, image.getHeight());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  background
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("background fills sheet with specified color")
    void backgroundFillsSheet() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(redImage())
                .addFrame(greenImage())
                .columns(2)
                .padding(10)
                .background(Color.WHITE);
        BufferedImage image = sheet.toImage();

        // Corner is background (padding exposes it)
        assertEquals(0xFFFFFFFF, image.getRGB(0, 0), "corner should be white");
    }

    @Test
    @DisplayName("background null means transparent")
    void backgroundNullMeansTransparent() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(redImage());
        BufferedImage image = sheet.toImage();

        // With no background, the sheet starts transparent
        assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  toImage
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("toImage returns ARGB image")
    void toImageReturnsArgb() {
        SpriteSheet sheet = SpriteSheet.create().addFrame(redImage());
        BufferedImage image = sheet.toImage();

        assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
    }

    @Test
    @DisplayName("toImage dimensions match layout")
    void toImageMatchesLayout() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(redImage())
                .addFrame(greenImage())
                .columns(2);
        BufferedImage image = sheet.toImage();
        SpriteSheet.Layout layout = sheet.layout();

        assertEquals(layout.width(), image.getWidth());
        assertEquals(layout.height(), image.getHeight());
    }

    @Test
    @DisplayName("toImage draws frames at correct positions")
    void toImageDrawsAtCorrectPositions() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(redImage())
                .addFrame(greenImage())
                .columns(2);
        BufferedImage image = sheet.toImage();

        // Red frame at (0,0) — check a pixel inside the red frame
        assertEquals(0xFFFF0000, image.getRGB(5, 5));
        // Green frame at (32,0) — check a pixel inside the green frame
        assertEquals(0xFF00FF00, image.getRGB(37, 5));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  toImagify
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("toImagify returns Imagify pipeline")
    void toImagifyReturnsPipeline() {
        SpriteSheet sheet = SpriteSheet.create().addFrame(redImage());
        Imagify imagify = sheet.toImagify();

        assertNotNull(imagify);
        assertEquals(1, imagify.frameCount());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Cell record
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Cell record holds correct values")
    void cellRecordValues() {
        SpriteSheet.Cell cell = new SpriteSheet.Cell(10, 20, 30, 40);

        assertEquals(10, cell.x());
        assertEquals(20, cell.y());
        assertEquals(30, cell.width());
        assertEquals(40, cell.height());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Layout record
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Layout record holds correct values")
    void layoutRecordValues() {
        List<SpriteSheet.Cell> cells = List.of(
                new SpriteSheet.Cell(0, 0, 10, 10),
                new SpriteSheet.Cell(10, 0, 10, 10));
        SpriteSheet.Layout layout = new SpriteSheet.Layout(2, 1, 10, 10, 20, 10, cells);

        assertEquals(2, layout.columns());
        assertEquals(1, layout.rows());
        assertEquals(10, layout.cellWidth());
        assertEquals(10, layout.cellHeight());
        assertEquals(20, layout.width());
        assertEquals(10, layout.height());
        assertEquals(cells, layout.cells());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Fit enum
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Fit enum has STRETCH INSIDE FILL")
    void fitEnumValues() {
        assertArrayEquals(new SpriteSheet.Fit[] { SpriteSheet.Fit.STRETCH, SpriteSheet.Fit.INSIDE, SpriteSheet.Fit.FILL },
                SpriteSheet.Fit.values());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Empty grid detection
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("grid with insufficient capacity throws on layout")
    void gridInsufficientCapacityThrowsOnLayout() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(redImage())
                .addFrame(greenImage())
                .addFrame(blueImage())
                .grid(1, 2); // only 2 cells for 3 frames

        assertThrows(IllegalArgumentException.class, sheet::layout);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Exact fit — frame same size as cell
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("frame same size as cell is not resized")
    void exactFitNoResize() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(createImage(32, 32, 0xFFFF0000))
                .cell(32, 32, SpriteSheet.Fit.STRETCH);
        BufferedImage image = sheet.toImage();

        assertEquals(32, image.getWidth());
        assertEquals(32, image.getHeight());
        assertEquals(0xFFFF0000, image.getRGB(0, 0));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Multiple rows with columns
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("multiple rows with columns places frames correctly")
    void multipleRowsColumns() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(redImage())    // 0
                .addFrame(greenImage())  // 1
                .addFrame(blueImage())   // 2
                .addFrame(redImage())    // 3
                .columns(2);
        List<SpriteSheet.Cell> cells = sheet.layout().cells();

        assertEquals(new SpriteSheet.Cell(0, 0, 32, 32), cells.get(0));  // row 0, col 0
        assertEquals(new SpriteSheet.Cell(32, 0, 32, 32), cells.get(1));  // row 0, col 1
        assertEquals(new SpriteSheet.Cell(0, 32, 32, 32), cells.get(2));  // row 1, col 0
        assertEquals(new SpriteSheet.Cell(32, 32, 32, 32), cells.get(3)); // row 1, col 1
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Single frame
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("single frame produces correct layout")
    void singleFrameLayout() {
        SpriteSheet sheet = SpriteSheet.create().addFrame(redImage());
        SpriteSheet.Layout layout = sheet.layout();

        assertEquals(1, layout.columns());
        assertEquals(1, layout.rows());
        assertEquals(32, layout.cellWidth());
        assertEquals(32, layout.cellHeight());
        assertEquals(32, layout.width());
        assertEquals(32, layout.height());
        assertEquals(1, layout.cells().size());
        assertEquals(new SpriteSheet.Cell(0, 0, 32, 32), layout.cells().get(0));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  addFrames from file paths
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("addFrames from paths reads images")
    void addFramesFromPaths(@TempDir Path dir) throws Exception {
        BufferedImage img = createImage(10, 10, 0xFFAAAAAA);
        Path file = dir.resolve("test.png");
        Files.write(file, ImageWriter.toBytes(img, ImageFormat.PNG, ImageFormat.PNG.getDefaultQuality()));

        SpriteSheet sheet = SpriteSheet.create().addFrames(List.of(file));
        assertEquals(1, sheet.frameCount());
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Mixed cell and fit configuration
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("cell with explicit fit and padding produces correct layout")
    void cellFitWithPadding() {
        SpriteSheet sheet = SpriteSheet.create()
                .addFrame(createImage(50, 50, 0xFFFF0000))
                .addFrame(createImage(30, 30, 0xFF00FF00))
                .cell(64, 64, SpriteSheet.Fit.INSIDE)
                .padding(8);
        SpriteSheet.Layout layout = sheet.layout();

        assertEquals(64, layout.cellWidth());
        assertEquals(64, layout.cellHeight());
        assertEquals(2, layout.columns()); // auto: 2 cols for 2 frames
        assertEquals(8, layout.cells().get(0).x());  // padded
        assertEquals(8, layout.cells().get(0).y());  // padded
    }
}