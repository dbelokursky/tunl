package com.vlessclient.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class AppIconAssetTest {

    private static final int[] PNG_SIZES = {16, 32, 64, 128, 256, 512, 1024};

    @Test
    void pngAssetsKeepConsistentOpticalMargins() throws IOException {
        for (int size : PNG_SIZES) {
            BufferedImage icon = readPng("/icons/app-icon-" + size + ".png");

            assertThat(icon.getWidth()).isEqualTo(size);
            assertThat(icon.getHeight()).isEqualTo(size);
            assertThat(alphaAt(icon, 0, 0)).isZero();
            assertThat(alphaAt(icon, size - 1, size - 1)).isZero();
            assertThat(alphaAt(icon, size / 2, size / 2)).isGreaterThan(240);

            PixelBounds body = opaqueBounds(icon, 128);
            int minimumMargin = Math.max(1, (int) Math.floor(size * 0.07));
            assertThat(body.left()).isGreaterThanOrEqualTo(minimumMargin);
            assertThat(body.top()).isGreaterThanOrEqualTo(minimumMargin);
            assertThat(size - 1 - body.right()).isGreaterThanOrEqualTo(minimumMargin);
            assertThat(size - 1 - body.bottom()).isGreaterThanOrEqualTo(minimumMargin);

            double opticalWidth = body.width() / (double) size;
            assertThat(opticalWidth).as("optical width at %d px", size).isBetween(0.74, 0.84);
        }
    }

    @Test
    void conveniencePngMatchesThe512PixelAsset() throws IOException {
        BufferedImage convenience = readPng("/icons/app-icon.png");
        BufferedImage canonical = readPng("/icons/app-icon-512.png");

        assertThat(convenience.getWidth()).isEqualTo(512);
        assertThat(convenience.getHeight()).isEqualTo(512);
        for (int y = 0; y < 512; y++) {
            for (int x = 0; x < 512; x++) {
                assertThat(convenience.getRGB(x, y)).isEqualTo(canonical.getRGB(x, y));
            }
        }
    }

    @Test
    void icoContainsAllWindowsResolutions() throws IOException {
        byte[] ico = readResource("/icons/app-icon.ico");
        ByteBuffer header = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);

        assertThat(header.getShort()).isZero();
        assertThat(header.getShort()).isEqualTo((short) 1);
        assertThat(header.getShort()).isEqualTo((short) 7);
    }

    @Test
    void icnsHasTheExpectedContainerHeader() throws IOException {
        byte[] icns = readResource("/icons/app-icon.icns");

        assertThat(new String(icns, 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("icns");
    }

    private static BufferedImage readPng(String resource) throws IOException {
        try (InputStream stream = AppIconAssetTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as(resource).isNotNull();
            BufferedImage image = ImageIO.read(stream);
            assertThat(image).as(resource).isNotNull();
            return image;
        }
    }

    private static byte[] readResource(String resource) throws IOException {
        try (InputStream stream = AppIconAssetTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as(resource).isNotNull();
            return stream.readAllBytes();
        }
    }

    private static int alphaAt(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) >>> 24;
    }

    private static PixelBounds opaqueBounds(BufferedImage image, int threshold) {
        int left = image.getWidth();
        int top = image.getHeight();
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (alphaAt(image, x, y) >= threshold) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        assertThat(right).isGreaterThanOrEqualTo(left);
        assertThat(bottom).isGreaterThanOrEqualTo(top);
        return new PixelBounds(left, top, right, bottom);
    }

    private record PixelBounds(int left, int top, int right, int bottom) {
        int width() {
            return right - left + 1;
        }
    }
}
