package com.retro.engine.ui;

import org.lwjgl.stb.STBTTFontinfo;
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

    /** Glyph quad in em units (y down from the baseline, SDF padding included) and its atlas UVs. */
    public static final class Glyph {
        public final float advance;
        public final float x0, y0, x1, y1;
        public final float u0, v0, u1, v1;
        public final boolean hasQuad;

        Glyph(float advance, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, boolean hasQuad) {
            this.advance = advance;
            this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
            this.u0 = u0; this.v0 = v0; this.u1 = u1; this.v1 = v1;
            this.hasQuad = hasQuad;
        }
    }

    private static volatile UIFont active = null;
    private static boolean loadAttempted = false;

    public final String sourcePath;
    public final int atlasWidth, atlasHeight;
    /** Tightly packed RGBA8; every channel holds the distance value. Dropped once uploaded. */
    private byte[] atlasPixels;
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
        atlasPixels = null;
        active = this;
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

    private static UIFont bake(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
        try (STBTTFontinfo info = STBTTFontinfo.malloc()) {
            data.put(bytes).flip();
            int offset = stbtt_GetFontOffsetForIndex(data, 0);
            if (offset < 0 || !stbtt_InitFont(info, data, offset)) {
                System.err.println("[UIFont] WARNING: Unsupported font file " + path + "; using the default bitmap font.");
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
                ByteBuffer bmp = stbtt_GetGlyphSDF(info, scale, gi, SDF_PADDING, SDF_ON_EDGE, SDF_DIST_SCALE, w, h, xo, yo);
                if (bmp != null) {
                    sdf = new byte[w[0] * h[0]];
                    bmp.get(0, sdf);
                    stbtt_FreeSDF(bmp);
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
            UIFont font = new UIFont(path.toString(), ATLAS_WIDTH, atlasH, rgba, capHeight, digitAdvance);
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
                            (float) (px[i] + b.w) / ATLAS_WIDTH, (float) (py[i] + b.h) / atlasH, true);
                } else {
                    g = new Glyph(b.advance, 0, 0, 0, 0, 0, 0, 0, 0, false);
                }
                if (b.codepoint < font.latin.length) font.latin[b.codepoint] = g;
                else font.extended.put(b.codepoint, g);
            }
            return font;
        } finally {
            MemoryUtil.memFree(data);
        }
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
