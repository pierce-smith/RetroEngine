package com.retro.engine.editor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;

/**
 * Icon files for exports. Two jobs:
 * <ul>
 *   <li>The packaged editor has no source tree next to it, so the default icons are read from the engine jar
 *       ({@code /icons/icon.(icns|ico|png)}) and cached under {@code ~/.retroengine/cache/icons/}.</li>
 *   <li>Windows and Linux take the window and taskbar icon from the running game ({@code glfwSetWindowIcon}), not from
 *       the executable, so each export also gets a PNG of its own icon ({@link #WINDOW_ICON_FILE} in Game_Data).</li>
 * </ul>
 */
public final class GameIcon {

    /** Written into Game_Data/ for Windows and Linux; WindowHost loads it before the engine's own icon. */
    public static final String WINDOW_ICON_FILE = "icon.png";

    static final String RESOURCE_DIR = "/icons/icon.";

    private GameIcon() {}

    /** The engine's own icon in {@code extension} format, as a file, or null when the build has none. */
    static File bundledIcon(String extension) {
        try (InputStream in = GameIcon.class.getResourceAsStream(RESOURCE_DIR + extension)) {
            if (in == null) return null;
            byte[] bytes = in.readAllBytes();
            File out = new File(System.getProperty("user.home"), ".retroengine/cache/icons/icon." + extension);
            if (!out.isFile() || !Arrays.equals(Files.readAllBytes(out.toPath()), bytes)) {
                Files.createDirectories(out.getParentFile().toPath());
                Files.write(out.toPath(), bytes);
            }
            return out;
        } catch (IOException e) {
            System.err.println("[Exporter] Could not extract the default icon: " + e.getMessage());
            return null;
        }
    }

    /**
     * PNG bytes for the window icon of a game whose app icon is {@code icon} (.png or .ico; null means the engine
     * default). Null when an .ico holds no image this can decode; the game then shows the engine icon.
     */
    static byte[] windowIconPng(File icon) throws IOException {
        if (icon == null) {
            try (InputStream in = GameIcon.class.getResourceAsStream(RESOURCE_DIR + "png")) {
                return in != null ? in.readAllBytes() : null;
            }
        }
        byte[] bytes = Files.readAllBytes(icon.toPath());
        String name = icon.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) return bytes;
        if (name.endsWith(".ico")) return pngFromIco(bytes);
        return null;
    }

    /** The largest image in an .ico, as PNG. Handles PNG entries and 24/32-bit BMP entries; null for anything else. */
    static byte[] pngFromIco(byte[] ico) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);
        if (ico.length < 6 || b.getShort(0) != 0 || b.getShort(2) != 1) throw new IOException("not an .ico file");
        int count = b.getShort(4) & 0xFFFF;
        int bestArea = -1, bestOffset = 0, bestSize = 0;
        for (int i = 0; i < count; i++) {
            int e = 6 + 16 * i;
            if (e + 16 > ico.length) throw new IOException("truncated .ico directory");
            int w = ico[e] & 0xFF, h = ico[e + 1] & 0xFF;
            int area = (w == 0 ? 256 : w) * (h == 0 ? 256 : h);
            int size = b.getInt(e + 8), offset = b.getInt(e + 12);
            if (offset < 0 || size <= 0 || (long) offset + size > ico.length) continue;
            if (area > bestArea) { bestArea = area; bestOffset = offset; bestSize = size; }
        }
        if (bestArea < 0) throw new IOException(".ico has no images");
        byte[] entry = Arrays.copyOfRange(ico, bestOffset, bestOffset + bestSize);
        if (entry.length >= 8 && (entry[0] & 0xFF) == 0x89 && entry[1] == 'P' && entry[2] == 'N' && entry[3] == 'G') return entry;
        BufferedImage image = decodeDib(entry);
        if (image == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** An .ico BMP entry: BITMAPINFOHEADER, bottom-up pixels at double height, then a 1-bit AND mask. */
    private static BufferedImage decodeDib(byte[] dib) {
        if (dib.length < 40) return null;
        ByteBuffer b = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN);
        int headerSize = b.getInt(0), width = b.getInt(4), height = b.getInt(8) / 2;
        int bpp = b.getShort(14) & 0xFFFF, compression = b.getInt(16);
        if (width <= 0 || height <= 0 || width > 1024 || height > 1024 || compression != 0 || (bpp != 32 && bpp != 24)) return null;
        int stride = ((width * bpp + 31) / 32) * 4;
        int maskStride = ((width + 31) / 32) * 4;
        int pixels = headerSize, mask = pixels + stride * height;
        if (mask > dib.length) return null;
        boolean hasMask = mask + maskStride * height <= dib.length;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            int row = pixels + (height - 1 - y) * stride;
            int maskRow = mask + (height - 1 - y) * maskStride;
            for (int x = 0; x < width; x++) {
                int p = row + x * (bpp / 8);
                int blue = dib[p] & 0xFF, green = dib[p + 1] & 0xFF, red = dib[p + 2] & 0xFF;
                int alpha;
                if (bpp == 32) {
                    alpha = dib[p + 3] & 0xFF;
                } else {
                    boolean transparent = hasMask && (dib[maskRow + x / 8] & (0x80 >> (x % 8))) != 0;
                    alpha = transparent ? 0 : 0xFF;
                }
                image.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
            }
        }
        return image;
    }
}
