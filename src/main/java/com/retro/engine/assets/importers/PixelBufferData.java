package com.retro.engine.assets.importers;

/**
 * Container for decoded 2D RGBA pixel image data.
 * Fully compatible with TextureImporter, RpackCooker, and AssetStreamer.
 */
public class PixelBufferData {

    public static final PixelBufferData EMPTY_FALLBACK = new PixelBufferData(
            "EMPTY_FALLBACK", "", 1, 1, 4, new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255}
    );

    public final String name;
    public final String path;
    public final int width;
    public final int height;
    public final int channels;
    public final byte[] pixels;

    public PixelBufferData(String name, String path, int width, int height, int channels, byte[] pixels) {
        this.name = (name != null) ? name : "UNNAMED";
        this.path = (path != null) ? path : "";
        this.width = width;
        this.height = height;
        this.channels = channels;
        this.pixels = pixels;
    }

    public PixelBufferData(byte[] pixels, int width, int height) {
        this("UNNAMED", "", width, height, 4, pixels);
    }

    public String name() { return name; }
    public String path() { return path; }
    public String filePath() { return path; }
    public int width() { return width; }
    public int height() { return height; }
    public int channels() { return channels; }
    public byte[] pixels() { return pixels; }

    @Override
    public String toString() {
        return String.format("PixelBufferData('%s', %dx%d, %d ch)", name, width, height, channels);
    }
}
