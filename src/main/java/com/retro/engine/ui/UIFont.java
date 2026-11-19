package com.retro.engine.ui;

import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTTVertex;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.lwjgl.stb.STBTruetype.*;

/**
 * Editor UI typeface: scans the host OS font folders for a clean sans-serif system font and bakes it into a
 * signed-distance-field atlas, so glyphs stay sharp at any panel scale, window size, or display density.
 * When no usable font is found the editor keeps the built-in bitmap font.
 */
public final class UIFont {

    /** Em height at the 1280x720 reference layout for text size 1.0 (scales with the window like all NDC layout). */
    public static final float BASE_EM_PIXELS = 14.0f;
    private static final float REFERENCE_HALF_HEIGHT = 360.0f;
    /** NDC-Y em height per unit of text size. */
    static final float EM_NDC_PER_SIZE = BASE_EM_PIXELS / REFERENCE_HALF_HEIGHT;

    // SDF bake: glyphs rendered at a large em with a distance ramp wide enough for clean minification
    private static final float BAKE_EM_PIXELS = 48.0f;
    private static final int SDF_PADDING = 6;
    private static final byte SDF_ON_EDGE = (byte) 128;
    private static final float SDF_DIST_SCALE = 128.0f / SDF_PADDING;
    private static final int ATLAS_WIDTH = 1024;
    private static final int ATLAS_GAP = 1;

    private static final String[] MAC_DIRS = { "/System/Library/Fonts/" };
    private static final String[] MAC_FONTS = { "HelveticaNeue.ttc", "Helvetica.ttc", "SFNS.ttf", "Arial.ttf", "Geneva.ttf" };
    private static final String[] WINDOWS_FONTS = { "arial.ttf", "segoeui.ttf", "tahoma.ttf", "verdana.ttf" };
    private static final String[] LINUX_DIRS = { "/usr/share/fonts/", "/usr/local/share/fonts/" };
    private static final String[] LINUX_FONTS = { "LiberationSans-Regular.ttf", "DejaVuSans.ttf", "NotoSans-Regular.ttf", "Ubuntu-R.ttf", "FreeSans.ttf" };
    private static final int SCAN_DEPTH = 4;

    /** Codepoints baked beyond printable ASCII and Latin-1. */
    private static final int[] EXTRA_CODEPOINTS = { 0x2022, 0x2026, 0x2013, 0x2014, 0x2018, 0x2019, 0x201C, 0x201D, 0x2190, 0x2191, 0x2192, 0x2193, 0x2318, 0x21E7, 0x2325, 0x232B };

    /**
     * Glyph quad in em units (y down from the baseline, SDF padding included) and its atlas UVs. The texel rows it
     * occupies in this font's own block ({@code blockY0..blockY1}) let {@link #relocate} re-point the UVs when the block
     * is packed into a shared atlas with other fonts.
     */
    public static final class Glyph {
        public final float advance;
        public final float x0, y0, x1, y1;
        public final float u0, u1;
        public float v0, v1;
        public final boolean hasQuad;
        final int blockY0, blockY1;

        Glyph(float advance, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, boolean hasQuad,
              int blockY0, int blockY1) {
            this.advance = advance;
            this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
            this.u0 = u0; this.v0 = v0; this.u1 = u1; this.v1 = v1;
            this.hasQuad = hasQuad;
            this.blockY0 = blockY0;
            this.blockY1 = blockY1;
        }
    }

    private static volatile UIFont active = null;
    private static boolean loadAttempted = false;

    public final String sourcePath;
    public final int atlasWidth, atlasHeight;
    /** This font's own block: tightly packed RGBA8, every channel holding the distance value. Kept for atlas repacking. */
    private final byte[] atlasPixels;
    /** Cap height and digit advance in em units. */
    public final float capHeight;
    public final float digitAdvance;
    private final Glyph[] latin = new Glyph[256];
    private final Map<Integer, Glyph> extended = new HashMap<>();

    private UIFont(String sourcePath, int atlasWidth, int atlasHeight, byte[] atlasPixels, float capHeight, float digitAdvance) {
        this.sourcePath = sourcePath;
        this.atlasWidth = atlasWidth;
        this.atlasHeight = atlasHeight;
        this.atlasPixels = atlasPixels;
        this.capHeight = capHeight;
        this.digitAdvance = digitAdvance;
    }

    /** The font text is laid out with, or null while the bitmap fallback is in use. */
    public static UIFont active() { return active; }

    /**
     * Loads and bakes the system font once. Returns null (after logging a warning) when no font folder or
     * candidate file exists, or the file cannot be parsed; callers then keep the default bitmap font.
     */
    public static synchronized UIFont loadSystemFont() {
        if (loadAttempted) return null;
        loadAttempted = true;
        try {
            Path path = findSystemFont();
            if (path == null) return null;
            UIFont font = bake(path);
            if (font != null) {
                System.out.println("[UIFont] Loaded editor font " + path + " (" + font.atlasWidth + "x" + font.atlasHeight + " SDF atlas, "
                        + BASE_EM_PIXELS + "px base em).");
            }
            return font;
        } catch (Throwable t) {
            System.err.println("[UIFont] WARNING: System font loading failed (" + t + "); using the default bitmap font.");
            return null;
        }
    }

    /** Makes this font live for UI layout; call only after the GPU can sample its atlas. */
    public void activate() {
        active = this;
    }

    /**
     * Re-points every glyph's V coordinates at this font's block placed {@code yOffset} rows down a shared atlas
     * {@code totalHeight} rows tall (same {@link #atlasWidth}). Render thread only, between UI frames.
     */
    public void relocate(int yOffset, int totalHeight) {
        for (Glyph g : latin) relocate(g, yOffset, totalHeight);
        for (Glyph g : extended.values()) relocate(g, yOffset, totalHeight);
    }

    private static void relocate(Glyph g, int yOffset, int totalHeight) {
        if (g == null || !g.hasQuad) return;
        g.v0 = (float) (yOffset + g.blockY0) / totalHeight;
        g.v1 = (float) (yOffset + g.blockY1) / totalHeight;
    }

    /** Atlas width every baked font shares, so blocks stack into one texture. */
    public static int atlasWidth() { return ATLAS_WIDTH; }

    /**
     * Bakes a project font file (.ttf / .otf). Returns null, after logging why, when the file is missing or can't be
     * parsed. Safe to call from any thread.
     */
    public static UIFont bakeFile(Path path) {
        try {
            if (path == null || !Files.isReadable(path)) {
                System.err.println("[UIFont] WARNING: Font file not found: " + path);
                return null;
            }
            return bake(path);
        } catch (Throwable t) {
            System.err.println("[UIFont] WARNING: Could not load font " + path + " (" + t + ").");
            return null;
        }
    }

    public byte[] getAtlasPixels() { return atlasPixels; }

    /** Glyph for a character, or null when the font has none (callers fall back to the bitmap glyph). */
    public Glyph glyph(char c) {
        return c < latin.length ? latin[c] : extended.get((int) c);
    }

    /** Sum of advances in em units. */
    public float measure(CharSequence text, int start, int end) {
        float w = 0.0f;
        for (int i = start; i < end; i++) w += advanceOf(text.charAt(i));
        return w;
    }

    /** Advance in em units, including bitmap-fallback glyphs (7 cells of a 5-cell cap height). */
    public float advanceOf(char c) {
        Glyph g = glyph(c);
        return g != null ? g.advance : capHeight * 1.4f;
    }

    // ------------------------------------------------------------------------------------------
    // Font discovery
    // ------------------------------------------------------------------------------------------

    private static Path findSystemFont() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] dirs;
        String[] candidates;
        if (os.contains("mac")) {
            dirs = MAC_DIRS;
            candidates = MAC_FONTS;
        } else if (os.contains("win")) {
            String winDir = System.getenv("WINDIR");
            dirs = new String[] { (winDir != null && !winDir.isEmpty() ? winDir : "C:\\Windows") + "\\Fonts\\" };
            candidates = WINDOWS_FONTS;
        } else {
            dirs = LINUX_DIRS;
            candidates = LINUX_FONTS;
        }

        Map<String, Path> found = new HashMap<>();
        boolean anyDir = false;
        for (String dir : dirs) {
            Path root = Paths.get(dir);
            if (!Files.isDirectory(root)) {
                System.err.println("[UIFont] WARNING: System font folder not found: " + dir);
                continue;
            }
            anyDir = true;
            try (Stream<Path> files = Files.walk(root, SCAN_DEPTH)) {
                files.filter(Files::isRegularFile)
                     .forEach(p -> found.putIfAbsent(p.getFileName().toString().toLowerCase(Locale.ROOT), p));
            } catch (IOException | SecurityException | java.io.UncheckedIOException e) {
                System.err.println("[UIFont] WARNING: Could not scan " + dir + " (" + e.getMessage() + ").");
            }
        }

        for (String name : candidates) {
            Path p = found.get(name.toLowerCase(Locale.ROOT));
            if (p != null && Files.isReadable(p)) return p;
        }
        System.err.println("[UIFont] WARNING: " + (anyDir ? "None of " + String.join(", ", candidates) + " found" : "No system font folder available")
                + "; using the default bitmap font.");
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // SDF atlas bake
    // ------------------------------------------------------------------------------------------

    private record Baked(int codepoint, float advance, int w, int h, int xoff, int yoff, byte[] sdf) {}

    /**
     * Bakes a font bundled in the engine's resources (e.g. {@code /fonts/texgyreheros-regular.otf}). Returns null,
     * after logging why, when the resource is missing or can't be parsed.
     */
    public static UIFont bakeResource(String resource) {
        try (var in = UIFont.class.getResourceAsStream(resource)) {
            if (in == null) {
                System.err.println("[UIFont] WARNING: Engine build has no " + resource + ".");
                return null;
            }
            return bake(in.readAllBytes(), resource);
        } catch (Throwable t) {
            System.err.println("[UIFont] WARNING: Could not load font " + resource + " (" + t + ").");
            return null;
        }
    }

    private static UIFont bake(Path path) throws IOException {
        return bake(Files.readAllBytes(path), path.toString());
    }

    private static UIFont bake(byte[] bytes, String sourceName) {
        ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
        try (STBTTFontinfo info = STBTTFontinfo.malloc()) {
            data.put(bytes).flip();
            int offset = stbtt_GetFontOffsetForIndex(data, 0);
            if (offset < 0 || !stbtt_InitFont(info, data, offset)) {
                System.err.println("[UIFont] WARNING: Unsupported font file " + sourceName + "; using the default font.");
                return null;
            }

            float scale = stbtt_ScaleForMappingEmToPixels(info, BAKE_EM_PIXELS);
            float emPerUnit = scale / BAKE_EM_PIXELS;

            int[] cps = collectCodepoints();
            Baked[] baked = new Baked[cps.length];
            int count = 0;
            int[] adv = new int[1], lsb = new int[1];
            int[] w = new int[1], h = new int[1], xo = new int[1], yo = new int[1];
            for (int cp : cps) {
                int gi = stbtt_FindGlyphIndex(info, cp);
                if (gi == 0 && cp != ' ') continue;
                stbtt_GetGlyphHMetrics(info, gi, adv, lsb);
                byte[] sdf = null;
                if (hasCubicCurves(info, gi)) {
                    // stb's SDF builder skips cubic segments (CFF / PostScript-outline .otf fonts), which leaves curved
                    // letters broken, so those glyphs go through a supersampled bitmap and a distance transform instead
                    sdf = rasterizedSdf(info, scale, gi, w, h, xo, yo);
                } else {
                    ByteBuffer bmp = stbtt_GetGlyphSDF(info, scale, gi, SDF_PADDING, SDF_ON_EDGE, SDF_DIST_SCALE, w, h, xo, yo);
                    if (bmp != null) {
                        sdf = new byte[w[0] * h[0]];
                        bmp.get(0, sdf);
                        stbtt_FreeSDF(bmp);
                    }
                }
                baked[count++] = new Baked(cp, adv[0] * emPerUnit, sdf != null ? w[0] : 0, sdf != null ? h[0] : 0, xo[0], yo[0], sdf);
            }

            int gH = stbtt_FindGlyphIndex(info, 'H');
            int[] bx0 = new int[1], by0 = new int[1], bx1 = new int[1], by1 = new int[1];
            float capHeight = (gH != 0 && stbtt_GetGlyphBox(info, gH, bx0, by0, bx1, by1)) ? by1[0] * emPerUnit : 0.7f;

            // Shelf-pack into a fixed-width atlas
            int[] px = new int[count], py = new int[count];
            int x = ATLAS_GAP, y = ATLAS_GAP, shelf = 0;
            for (int i = 0; i < count; i++) {
                Baked b = baked[i];
                if (b.sdf == null) continue;
                if (x + b.w + ATLAS_GAP > ATLAS_WIDTH) {
                    x = ATLAS_GAP;
                    y += shelf + ATLAS_GAP;
                    shelf = 0;
                }
                px[i] = x; py[i] = y;
                x += b.w + ATLAS_GAP;
                shelf = Math.max(shelf, b.h);
            }
            int atlasH = Integer.highestOneBit(Math.max(1, y + shelf + ATLAS_GAP) - 1) << 1;
            byte[] rgba = new byte[ATLAS_WIDTH * atlasH * 4];

            float digitAdvance = 0.55f;
            for (int i = 0; i < count; i++) {
                if (baked[i].codepoint == '0') digitAdvance = baked[i].advance;
            }
            UIFont font = new UIFont(sourceName, ATLAS_WIDTH, atlasH, rgba, capHeight, digitAdvance);
            for (int i = 0; i < count; i++) {
                Baked b = baked[i];
                Glyph g;
                if (b.sdf != null) {
                    for (int row = 0; row < b.h; row++) {
                        int dst = ((py[i] + row) * ATLAS_WIDTH + px[i]) * 4;
                        for (int col = 0; col < b.w; col++, dst += 4) {
                            byte v = b.sdf[row * b.w + col];
                            rgba[dst] = v; rgba[dst + 1] = v; rgba[dst + 2] = v; rgba[dst + 3] = v;
                        }
                    }
                    g = new Glyph(b.advance,
                            b.xoff / BAKE_EM_PIXELS, b.yoff / BAKE_EM_PIXELS,
                            (b.xoff + b.w) / BAKE_EM_PIXELS, (b.yoff + b.h) / BAKE_EM_PIXELS,
                            (float) px[i] / ATLAS_WIDTH, (float) py[i] / atlasH,
                            (float) (px[i] + b.w) / ATLAS_WIDTH, (float) (py[i] + b.h) / atlasH, true,
                            py[i], py[i] + b.h);
                } else {
                    g = new Glyph(b.advance, 0, 0, 0, 0, 0, 0, 0, 0, false, 0, 0);
                }
                if (b.codepoint < font.latin.length) font.latin[b.codepoint] = g;
                else font.extended.put(b.codepoint, g);
            }
            return font;
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    /** True when the glyph's outline has cubic Bezier segments, which only CFF-flavoured OpenType fonts use. */
    private static boolean hasCubicCurves(STBTTFontinfo info, int glyph) {
        STBTTVertex.Buffer shape = stbtt_GetGlyphShape(info, glyph);
        if (shape == null) return false;
        try {
            for (int i = 0; i < shape.limit(); i++) {
                if (shape.get(i).type() == STBTT_vcubic) return true;
            }
            return false;
        } finally {
            // Unchecked: an outline-less glyph (space) comes back as an empty buffer, which the checked call rejects
            nstbtt_FreeShape(info.address(), shape.address());
        }
    }

    /** Supersampling factor for {@link #rasterizedSdf}. */
    private static final int SDF_SUPERSAMPLE = 4;

    /**
     * The same SDF {@code stbtt_GetGlyphSDF} would produce (box, offsets, 128 on the edge, {@link #SDF_DIST_SCALE} per
     * pixel, inside brighter), built from a {@link #SDF_SUPERSAMPLE}x antialiased rasterization, which handles every
     * outline type, and an exact Euclidean distance transform. Null for glyphs with no outline (spaces).
     */
    static byte[] rasterizedSdf(STBTTFontinfo info, float scale, int glyph, int[] outW, int[] outH, int[] outXOff, int[] outYOff) {
        int[] x0 = new int[1], y0 = new int[1], x1 = new int[1], y1 = new int[1];
        stbtt_GetGlyphBitmapBox(info, glyph, scale, scale, x0, y0, x1, y1);
        if (x1[0] <= x0[0] || y1[0] <= y0[0]) return null;
        int w = x1[0] - x0[0] + 2 * SDF_PADDING, h = y1[0] - y0[0] + 2 * SDF_PADDING;
        int gx = x0[0] - SDF_PADDING, gy = y0[0] - SDF_PADDING; // low-res grid origin in glyph pixels

        // Hi-res canvas covering exactly the low-res grid, glyph drawn at its own bitmap box inside it
        final int k = SDF_SUPERSAMPLE;
        int hw = w * k, hh = h * k;
        float hiScale = scale * k;
        int[] hx0 = new int[1], hy0 = new int[1], hx1 = new int[1], hy1 = new int[1];
        stbtt_GetGlyphBitmapBox(info, glyph, hiScale, hiScale, hx0, hy0, hx1, hy1);
        int ox = hx0[0] - gx * k, oy = hy0[0] - gy * k;
        int gw = Math.min(hx1[0] - hx0[0], hw - ox), gh = Math.min(hy1[0] - hy0[0], hh - oy);
        byte[] coverage = new byte[hw * hh];
        if (ox >= 0 && oy >= 0 && gw > 0 && gh > 0) {
            ByteBuffer canvas = MemoryUtil.memCalloc(hw * hh);
            try {
                stbtt_MakeGlyphBitmap(info, canvas.slice(oy * hw + ox, hw * hh - (oy * hw + ox)), gw, gh, hw, hiScale, hiScale, glyph);
                canvas.get(0, coverage);
            } finally {
                MemoryUtil.memFree(canvas);
            }
        }

        // Squared distances (hi-res pixels) to the nearest inside pixel and to the nearest outside pixel
        double[] toInside = new double[hw * hh], toOutside = new double[hw * hh];
        for (int i = 0; i < coverage.length; i++) {
            boolean inside = (coverage[i] & 0xFF) >= 128;
            toInside[i] = inside ? 0.0 : EDT_INF;
            toOutside[i] = inside ? EDT_INF : 0.0;
        }
        distanceTransform(toInside, hw, hh);
        distanceTransform(toOutside, hw, hh);

        // Each low-res texel averages the signed distance over its k x k block; half a hi-res pixel puts the edge between pixels
        byte[] sdf = new byte[w * h];
        float perLowPixel = 1.0f / k;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float sum = 0.0f;
                for (int sy = 0; sy < k; sy++) {
                    int row = (y * k + sy) * hw + x * k;
                    for (int sx = 0; sx < k; sx++) {
                        int i = row + sx;
                        // Inside pixels: distance to the nearest outside pixel; outside pixels: minus the distance inwards
                        float d = toOutside[i] > 0.0 ? (float) Math.sqrt(toOutside[i]) - 0.5f : -((float) Math.sqrt(toInside[i]) - 0.5f);
                        sum += d;
                    }
                }
                float dist = sum / (k * k) * perLowPixel;
                int v = Math.round((SDF_ON_EDGE & 0xFF) + dist * SDF_DIST_SCALE);
                sdf[y * w + x] = (byte) Math.max(0, Math.min(255, v));
            }
        }
        outW[0] = w; outH[0] = h; outXOff[0] = gx; outYOff[0] = gy;
        return sdf;
    }

    /**
     * Stand-in for "no such pixel" in the distance transform: far above any real squared distance on a glyph canvas
     * (under 10^7) yet small enough that doubles keep the q^2 terms exact next to it.
     */
    private static final double EDT_INF = 1e10;

    /** In-place exact squared Euclidean distance transform (Felzenszwalb and Huttenlocher), columns then rows. */
    private static void distanceTransform(double[] grid, int w, int h) {
        int n = Math.max(w, h);
        double[] f = new double[n], d = new double[n], z = new double[n + 1];
        int[] v = new int[n];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) f[y] = grid[y * w + x];
            transform1d(f, h, d, v, z);
            for (int y = 0; y < h; y++) grid[y * w + x] = d[y];
        }
        for (int y = 0; y < h; y++) {
            System.arraycopy(grid, y * w, f, 0, w);
            transform1d(f, w, d, v, z);
            System.arraycopy(d, 0, grid, y * w, w);
        }
    }

    /** One row: d[q] = min over p of (q - p)^2 + f[p], via the lower envelope of parabolas. */
    private static void transform1d(double[] f, int n, double[] d, int[] v, double[] z) {
        int k = 0;
        v[0] = 0;
        z[0] = Double.NEGATIVE_INFINITY;
        z[1] = Double.POSITIVE_INFINITY;
        for (int q = 1; q < n; q++) {
            double s = intersection(f, q, v[k]);
            while (s <= z[k]) {
                k--;
                s = intersection(f, q, v[k]);
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = Double.POSITIVE_INFINITY;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) k++;
            int p = v[k];
            d[q] = (double) (q - p) * (q - p) + f[p];
        }
    }

    private static double intersection(double[] f, int q, int p) {
        return ((f[q] + (double) q * q) - (f[p] + (double) p * p)) / (2.0 * (q - p));
    }

    private static int[] collectCodepoints() {
        int[] cps = new int[(127 - 32) + (256 - 160) + EXTRA_CODEPOINTS.length];
        int n = 0;
        for (int c = 32; c < 127; c++) cps[n++] = c;
        for (int c = 160; c < 256; c++) cps[n++] = c;
        for (int c : EXTRA_CODEPOINTS) cps[n++] = c;
        return cps;
    }
}
