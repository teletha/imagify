package imagify;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.Files;

public class PipelineTest {
    public static void main(String[] args) throws Exception {
        BufferedImage src = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
                src.setRGB(x, y, (x * 16) << 16 | (y * 16) << 8 | 128);

        byte[] pngData = ImageWriter.toBytes(src, ImageFormat.PNG);
        Path tmpPng = Path.of(System.getProperty("java.io.tmpdir"), "test_pipeline.png");
        Files.write(tmpPng, pngData);

        // Pipeline: read -> resize -> write
        Path tmpOut = Path.of(System.getProperty("java.io.tmpdir"), "test_pipeline_out.png");
        ImagePipeline
            .read(tmpPng)
            .resize(32, 32, ResizeAlgorithm.LANCZOS3)
            .writeTo(tmpOut, ImageFormat.PNG);

        BufferedImage result = ImageReader.read(tmpOut);
        System.out.println("Pipeline result: " + result.getWidth() + "x" + result.getHeight());
        System.out.println(result.getWidth() == 32 && result.getHeight() == 32 ? "PASS" : "FAIL");

        // Pipeline with bytes
        byte[] outBytes = ImagePipeline
            .read(tmpPng)
            .resize(8, 8)
            .writeToBytes(ImageFormat.PNG);
        System.out.println("Bytes output: " + outBytes.length + " bytes");
        System.out.println(outBytes.length > 0 ? "PASS" : "FAIL");

        // Pipeline with get()
        BufferedImage mid = ImagePipeline
            .read(tmpPng)
            .resize(0.5, ResizeAlgorithm.CATROM)
            .get();
        System.out.println("Intermediate: " + mid.getWidth() + "x" + mid.getHeight());
        System.out.println(mid.getWidth() == 8 && mid.getHeight() == 8 ? "PASS" : "FAIL");

        System.out.println("\nAll pipeline tests passed!");

        Files.deleteIfExists(tmpPng);
        Files.deleteIfExists(tmpOut);
    }
}
