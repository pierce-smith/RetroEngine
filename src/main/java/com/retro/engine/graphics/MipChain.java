package com.retro.engine.graphics;

/**
 * A full mip chain for a tightly packed RGBA8 image, every level stored back to back in {@link #pixels}. Both backends
 * upload this same data, so a texture filters identically on Metal and Vulkan.
 */
public final class MipChain {

    /** Every level, largest first. */
    public final byte[] pixels;
    public final int[] widths, heights;
    /** Byte offset of each level inside {@link #pixels}. */
    public final int[] offsets;

    private MipChain(byte[] pixels, int[] widths, int[] heights, int[] offsets) {
        this.pixels = pixels;
        this.widths = widths;
        this.heights = heights;
        this.offsets = offsets;
    }

    public int levels() {
        return widths.length;
    }

    /** Number of levels down to 1x1, which is what Metal's mipmapped textures always allocate. */
    public static int levelCountFor(int width, int height) {
        return 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
    }

    public static MipChain generate(byte[] rgba, int width, int height) {
        int levels = levelCountFor(width, height);
        int[] widths = new int[levels], heights = new int[levels], offsets = new int[levels];
        long total = 0;
        for (int i = 0, w = width, h = height; i < levels; i++) {
            widths[i] = w;
            heights[i] = h;
            offsets[i] = (int) total;
            total += (long) w * h * 4L;
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
        }
        if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("Texture too large: " + width + "x" + height);

        byte[] pixels = new byte[(int) total];
        System.arraycopy(rgba, 0, pixels, 0, width * height * 4);
        for (int i = 1; i < levels; i++) {
            downsample(pixels, offsets[i - 1], widths[i - 1], heights[i - 1], offsets[i], widths[i], heights[i]);
        }
        return new MipChain(pixels, widths, heights, offsets);
    }

    /**
     * 2x2 box filter. Color is weighted by alpha, so the invisible texels around a cutout don't bleed a dark or colored
     * fringe into it. Odd edges clamp to the last row and column.
     */
    private static void downsample(byte[] p, int src, int srcW, int srcH, int dst, int dstW, int dstH) {
        for (int y = 0; y < dstH; y++) {
            int y0 = Math.min(y * 2, srcH - 1), y1 = Math.min(y * 2 + 1, srcH - 1);
            for (int x = 0; x < dstW; x++) {
                int x0 = Math.min(x * 2, srcW - 1), x1 = Math.min(x * 2 + 1, srcW - 1);
                int a = src + (y0 * srcW + x0) * 4, b = src + (y0 * srcW + x1) * 4;
                int c = src + (y1 * srcW + x0) * 4, d = src + (y1 * srcW + x1) * 4;
                int aa = p[a + 3] & 0xFF, ab = p[b + 3] & 0xFF, ac = p[c + 3] & 0xFF, ad = p[d + 3] & 0xFF;
                int alphaSum = aa + ab + ac + ad;
                int out = dst + (y * dstW + x) * 4;
                for (int ch = 0; ch < 3; ch++) {
                    int va = p[a + ch] & 0xFF, vb = p[b + ch] & 0xFF, vc = p[c + ch] & 0xFF, vd = p[d + ch] & 0xFF;
                    int value = alphaSum > 0
                            ? (va * aa + vb * ab + vc * ac + vd * ad + alphaSum / 2) / alphaSum
                            : (va + vb + vc + vd + 2) / 4;
                    p[out + ch] = (byte) value;
                }
                p[out + 3] = (byte) ((alphaSum + 2) / 4);
            }
        }
    }
}
