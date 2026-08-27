// Generates the Tunl app icon at multiple resolutions using AWT.
// Run with: java scripts/GenerateAppIcon.java
//
// The source artwork stays separate from generated application resources so
// every PNG, ICNS and ICO can be reproduced from the same master image.

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

public final class GenerateAppIcon {

    private static final Path SOURCE = Path.of("assets/app-icon-artwork.png");
    private static final Path OUTPUT = Path.of("src/main/resources/icons");
    private static final int[] PNG_SIZES = {16, 32, 64, 128, 256, 512, 1024};
    private static final int[] ICO_SIZES = {16, 24, 32, 48, 64, 128, 256};

    private GenerateAppIcon() {
    }

    public static void main(String[] args) throws Exception {
        BufferedImage artwork = ImageIO.read(SOURCE.toFile());
        if (artwork == null) {
            throw new IllegalStateException("Cannot decode source artwork: " + SOURCE);
        }
        PixelBounds artworkBounds = opaqueBounds(artwork, 128);
        Files.createDirectories(OUTPUT);

        for (int size : PNG_SIZES) {
            ImageIO.write(render(artwork, artworkBounds, size), "png",
                    OUTPUT.resolve("app-icon-" + size + ".png").toFile());
        }
        ImageIO.write(render(artwork, artworkBounds, 512), "png",
                OUTPUT.resolve("app-icon.png").toFile());
        System.out.println("Generated " + (PNG_SIZES.length + 1)
                + " PNGs in " + OUTPUT.toAbsolutePath());

        buildIcns(OUTPUT);
        buildIco(artwork, artworkBounds, OUTPUT.resolve("app-icon.ico"));
    }

    /**
     * Normalizes the complete source icon so its visible bounds occupy 80% of
     * the canvas. The source already contains its frame, so no second container
     * is added here.
     */
    private static BufferedImage render(
            BufferedImage artwork, PixelBounds artworkBounds, int size) {
        BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        applyQuality(g);

        // At 16 px, an 80% vector bound rounds outward to 14 physical pixels
        // (87.5% of the canvas), so use a slightly smaller construction grid.
        double opticalScale = size == 16 ? 0.75 : 0.80;
        double scale = Math.min(
                size * opticalScale / artworkBounds.width(),
                size * opticalScale / artworkBounds.height());
        double x = size * 0.5 - artworkBounds.centerX() * scale;
        double y = size * 0.5 - artworkBounds.centerY() * scale;
        AffineTransform transform = AffineTransform.getTranslateInstance(x, y);
        transform.scale(scale, scale);
        g.drawImage(artwork, transform, null);

        g.dispose();
        return result;
    }

    private static void applyQuality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE);
    }

    /**
     * Assembles an Apple iconset and asks iconutil to compile the ICNS used by
     * jpackage. The step is skipped when iconutil is unavailable.
     */
    private static void buildIcns(Path output) throws Exception {
        if (!commandExists("iconutil")) {
            System.out.println("iconutil is unavailable; app-icon.icns was not regenerated");
            return;
        }

        Path iconset = output.resolve("AppIcon.iconset");
        deleteRecursive(iconset);
        Files.createDirectories(iconset);

        String[][] mapping = {
                {"16", "icon_16x16.png"},
                {"32", "icon_16x16@2x.png"},
                {"32", "icon_32x32.png"},
                {"64", "icon_32x32@2x.png"},
                {"128", "icon_128x128.png"},
                {"256", "icon_128x128@2x.png"},
                {"256", "icon_256x256.png"},
                {"512", "icon_256x256@2x.png"},
                {"512", "icon_512x512.png"},
                {"1024", "icon_512x512@2x.png"},
        };
        for (String[] entry : mapping) {
            Files.copy(output.resolve("app-icon-" + entry[0] + ".png"),
                    iconset.resolve(entry[1]));
        }

        Path icns = output.resolve("app-icon.icns");
        Process process = new ProcessBuilder(
                "iconutil", "-c", "icns", iconset.toString(), "-o", icns.toString())
                .inheritIO()
                .start();
        int exit = process.waitFor();
        deleteRecursive(iconset);
        if (exit != 0) {
            throw new IllegalStateException("iconutil exited with code " + exit);
        }
        System.out.println("Generated " + icns.toAbsolutePath());
    }

    /** Writes a multi-resolution ICO containing PNG-compressed images. */
    private static void buildIco(
            BufferedImage artwork, PixelBounds artworkBounds, Path ico) throws Exception {
        List<byte[]> images = new ArrayList<>();
        for (int size : ICO_SIZES) {
            ByteArrayOutputStream image = new ByteArrayOutputStream();
            ImageIO.write(render(artwork, artworkBounds, size), "png", image);
            images.add(image.toByteArray());
        }

        try (OutputStream file = Files.newOutputStream(ico);
             DataOutputStream out = new DataOutputStream(file)) {
            writeLittleEndianShort(out, 0);
            writeLittleEndianShort(out, 1);
            writeLittleEndianShort(out, images.size());

            int offset = 6 + images.size() * 16;
            for (int index = 0; index < images.size(); index++) {
                int size = ICO_SIZES[index];
                out.writeByte(size == 256 ? 0 : size);
                out.writeByte(size == 256 ? 0 : size);
                out.writeByte(0);
                out.writeByte(0);
                writeLittleEndianShort(out, 1);
                writeLittleEndianShort(out, 32);
                writeLittleEndianInt(out, images.get(index).length);
                writeLittleEndianInt(out, offset);
                offset += images.get(index).length;
            }
            for (byte[] image : images) {
                out.write(image);
            }
        }
        System.out.println("Generated " + ico.toAbsolutePath());
    }

    private static PixelBounds opaqueBounds(BufferedImage image, int alphaThreshold) {
        int left = image.getWidth();
        int top = image.getHeight();
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) >= alphaThreshold) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        if (right < left || bottom < top) {
            throw new IllegalArgumentException("Source artwork contains no visible pixels");
        }
        return new PixelBounds(left, top, right, bottom);
    }

    private record PixelBounds(int left, int top, int right, int bottom) {
        int width() {
            return right - left + 1;
        }

        int height() {
            return bottom - top + 1;
        }

        double centerX() {
            return (left + right + 1) * 0.5;
        }

        double centerY() {
            return (top + bottom + 1) * 0.5;
        }
    }

    private static boolean commandExists(String command) {
        try {
            Process process = new ProcessBuilder("which", command).start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeLittleEndianShort(DataOutputStream out, int value) throws Exception {
        out.writeByte(value & 0xff);
        out.writeByte((value >>> 8) & 0xff);
    }

    private static void writeLittleEndianInt(DataOutputStream out, int value) throws Exception {
        out.writeByte(value & 0xff);
        out.writeByte((value >>> 8) & 0xff);
        out.writeByte((value >>> 16) & 0xff);
        out.writeByte((value >>> 24) & 0xff);
    }

    private static void deleteRecursive(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var entries = Files.walk(path)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.delete(entry);
                } catch (Exception e) {
                    throw new IllegalStateException("Cannot delete " + entry, e);
                }
            });
        }
    }
}
