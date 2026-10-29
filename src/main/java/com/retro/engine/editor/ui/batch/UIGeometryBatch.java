package com.retro.engine.editor.ui.batch;

import java.util.List;

/**
 * UIGeometryBatch provides high-performance 2D UI vertex batching routines
 * for quads, borders, lines, circles, arrowheads, and system-font (SDF atlas) text with a bitmap fallback.
 * The static routines are stateless: every piece of mutable state (vertices, window metrics, panel mapping, clip
 * rect, text scratch) lives in the {@link Buffer} instance they are given, so separate buffers can be packed and
 * measured concurrently. A single Buffer is not thread-safe. The active {@link UIFont} is read-only once published.
 * Target JDK: JDK 25 / macOS (Zero-allocation primitive buffer support)
 */
public class UIGeometryBatch {

    /** Floats per vertex: xy = NDC position, rgba = color, uv = font atlas coordinate (NO_UV for solid fills). */
    public static final int VERTEX_STRIDE = 8;
    /** UV sentinel marking untextured geometry; the UI shaders skip the atlas below -1. */
    public static final float NO_UV = -2.0f;
    /** UV offset marking camera preview geometry; the UI shaders sample the preview target at uv - offset above 1.5. */
    public static final float PREVIEW_UV_OFFSET = 2.0f;

    public static class Buffer {
        private float[] data;
        private int size;

        // Panel mapping: when active, every emitted triangle is transformed from a panel's design-space
        // NDC (its original fixed layout) into its docked rect, then clipped to that rect on the CPU.
        private boolean mapped = false;
        private boolean transformSuspended = false;
        private float mapSx = 1.0f, mapSy = 1.0f, mapTx = 0.0f, mapTy = 0.0f;
        private float clipX0, clipY0, clipX1, clipY1;
        private float savedClipY0, savedClipY1;
        private float textScale = 1.0f;

        // Window metrics: pixel-height / pixel-width keeps rounded corners circular; sizes snap text to pixels
        private float pixelAspect = 720.0f / 1280.0f;
        private float windowWidth = 1280.0f, windowHeight = 720.0f;
        private final StringBuilder truncScratch = new StringBuilder(128);

        // Triangle assembly + Sutherland-Hodgman scratch (VERTEX_STRIDE floats per vertex, zero per-frame allocation)
        private final float[] tri = new float[3 * VERTEX_STRIDE];
        private int triVerts = 0;
        private final float[] polyA = new float[16 * VERTEX_STRIDE];
        private final float[] polyB = new float[16 * VERTEX_STRIDE];

        public Buffer() {
            this(16384);
        }

        public Buffer(int initialCapacity) {
            this.data = new float[initialCapacity];
            this.size = 0;
        }

        public void reset() {
            this.size = 0;
        }

        /** Window size in pixels that this buffer's NDC geometry is displayed at. */
        public void setWindowSize(int width, int height) {
            if (width > 0 && height > 0) {
                pixelAspect = (float) height / (float) width;
                windowWidth = width;
                windowHeight = height;
            }
        }

        public float getWindowWidth() { return windowWidth; }
        public float getWindowHeight() { return windowHeight; }

        public void ensureCapacity(int minCapacity) {
            if (minCapacity > data.length) {
                int newCap = Math.max(data.length * 2, minCapacity);
                float[] newData = new float[newCap];
                System.arraycopy(data, 0, newData, 0, size);
                data = newData;
            }
        }

        public void add(float val) {
            ensureCapacity(size + 1);
            data[size++] = val;
        }

        public float[] getArray() {
            return data;
        }

        public int getSize() {
            return size;
        }

        public float[] toFloatArray() {
            float[] copy = new float[size];
            System.arraycopy(data, 0, copy, 0, size);
            return copy;
        }

        /**
         * Maps design-space rect [dx0,dx1]x[dy0,dy1] onto screen rect [sx0,sx1]x[sy0,sy1] and clips all
         * subsequent geometry to the screen rect, until {@link #clearPanelMapping()}.
         */
        public void setPanelMapping(float dx0, float dy0, float dx1, float dy1, float sx0, float sy0, float sx1, float sy1) {
            this.mapSx = (sx1 - sx0) / (dx1 - dx0);
            this.mapSy = (sy1 - sy0) / (dy1 - dy0);
            this.mapTx = sx0 - dx0 * mapSx;
            this.mapTy = sy0 - dy0 * mapSy;
            this.clipX0 = sx0; this.clipY0 = sy0; this.clipX1 = sx1; this.clipY1 = sy1;
            // Glyphs scale uniformly (never stretched) and never grow past their authored size
            this.textScale = Math.max(0.35f, Math.min(1.0f, Math.min(mapSx, mapSy)));
            this.mapped = true;
            this.transformSuspended = false;
            this.triVerts = 0;
        }

        public void clearPanelMapping() {
            this.mapped = false;
            this.transformSuspended = false;
            this.triVerts = 0;
            this.mapSx = 1.0f; this.mapSy = 1.0f; this.mapTx = 0.0f; this.mapTy = 0.0f;
            this.textScale = 1.0f;
        }

        /**
         * Narrows the active clip rect to design-space Y band [dy0, dy1] (intersected with the panel rect) until
         * {@link #popClipY()}. Clipping only exists while a panel mapping is active, so this is a no-op otherwise.
         */
        public void pushClipY(float dy0, float dy1) {
            savedClipY0 = clipY0; savedClipY1 = clipY1;
            if (!mapped) return;
            float a = mapY(dy0), b = mapY(dy1);
            clipY0 = Math.max(clipY0, Math.min(a, b));
            clipY1 = Math.min(clipY1, Math.max(a, b));
        }

        public void popClipY() {
            clipY0 = savedClipY0; clipY1 = savedClipY1;
        }

        public boolean isMapped() { return mapped; }
        public float mapX(float x) { return mapped ? x * mapSx + mapTx : x; }
        public float mapY(float y) { return mapped ? y * mapSy + mapTy : y; }
        public float getTextScale() { return mapped ? textScale : 1.0f; }

        /** Ratio of current-space X units to Y units that yields equal on-screen pixel lengths. */
        float pixelSquareXPerY() {
            float base = pixelAspect;
            return mapped && !transformSuspended ? base * (mapSy / mapSx) : base;
        }

        /** Converts a screen-space text width into the current (possibly mapped) drawing space. */
        float screenToLocalWidth(float w) {
            return mapped && !transformSuspended && mapSx > 0.0f ? w / mapSx : w;
        }

        void suspendTransform(boolean suspended) { this.transformSuspended = suspended; }

        void stageVertex(float x, float y, float r, float g, float b, float a, float u, float v) {
            int o = triVerts * VERTEX_STRIDE;
            if (!transformSuspended) {
                x = x * mapSx + mapTx;
                y = y * mapSy + mapTy;
            }
            tri[o] = x; tri[o + 1] = y; tri[o + 2] = r; tri[o + 3] = g; tri[o + 4] = b; tri[o + 5] = a; tri[o + 6] = u; tri[o + 7] = v;
            if (++triVerts == 3) {
                triVerts = 0;
                emitClippedTriangle();
            }
        }

        private void emitRaw(float[] src, int vertIndex) {
            int o = vertIndex * VERTEX_STRIDE;
            ensureCapacity(size + VERTEX_STRIDE);
            System.arraycopy(src, o, data, size, VERTEX_STRIDE);
            size += VERTEX_STRIDE;
        }

        private void emitClippedTriangle() {
            float x0 = tri[0], y0 = tri[1];
            float x1 = tri[VERTEX_STRIDE], y1 = tri[VERTEX_STRIDE + 1];
            float x2 = tri[2 * VERTEX_STRIDE], y2 = tri[2 * VERTEX_STRIDE + 1];

            // Trivial reject: entirely beyond one clip edge
            if ((x0 < clipX0 && x1 < clipX0 && x2 < clipX0) || (x0 > clipX1 && x1 > clipX1 && x2 > clipX1) ||
                (y0 < clipY0 && y1 < clipY0 && y2 < clipY0) || (y0 > clipY1 && y1 > clipY1 && y2 > clipY1)) {
                return;
            }
            // Trivial accept: entirely inside (the common case for panel content)
            if (x0 >= clipX0 && x0 <= clipX1 && x1 >= clipX0 && x1 <= clipX1 && x2 >= clipX0 && x2 <= clipX1 &&
                y0 >= clipY0 && y0 <= clipY1 && y1 >= clipY0 && y1 <= clipY1 && y2 >= clipY0 && y2 <= clipY1) {
                emitRaw(tri, 0); emitRaw(tri, 1); emitRaw(tri, 2);
                return;
            }

            System.arraycopy(tri, 0, polyA, 0, 3 * VERTEX_STRIDE);
            int n = 3;
            n = clipEdge(polyA, n, polyB, 0, clipX0, false);
            n = clipEdge(polyB, n, polyA, 0, clipX1, true);
            n = clipEdge(polyA, n, polyB, 1, clipY0, false);
            n = clipEdge(polyB, n, polyA, 1, clipY1, true);
            for (int i = 1; i + 1 < n; i++) {
                emitRaw(polyA, 0); emitRaw(polyA, i); emitRaw(polyA, i + 1);
            }
        }

        /** Clips polygon in src against one axis-aligned edge (axis 0 = x, 1 = y), interpolating all attributes. */
        private static int clipEdge(float[] src, int n, float[] dst, int axis, float bound, boolean keepBelow) {
            int out = 0;
            if (n == 0) return 0;
            for (int i = 0; i < n; i++) {
                int cur = i * VERTEX_STRIDE, prev = ((i + n - 1) % n) * VERTEX_STRIDE;
                float cv = src[cur + axis], pv = src[prev + axis];
                boolean cIn = keepBelow ? cv <= bound : cv >= bound;
                boolean pIn = keepBelow ? pv <= bound : pv >= bound;
                if (cIn != pIn) {
                    float t = (bound - pv) / (cv - pv);
                    int o = out * VERTEX_STRIDE;
                    for (int k = 0; k < VERTEX_STRIDE; k++) dst[o + k] = src[prev + k] + t * (src[cur + k] - src[prev + k]);
                    out++;
                }
                if (cIn) {
                    System.arraycopy(src, cur, dst, out * VERTEX_STRIDE, VERTEX_STRIDE);
                    out++;
                }
            }
            return out;
        }
    }

    private static final long[] FONT = new long[128];
    static {
        FONT['A'] = 0x1c223e2222L; FONT['B'] = 0x3c223c223cL; FONT['C'] = 0x1c2220221cL;
        FONT['D'] = 0x3824242438L; FONT['E'] = 0x3e203c203eL; FONT['F'] = 0x3e203c2020L;
        FONT['G'] = 0x1c202e221eL; FONT['H'] = 0x22223e2222L; FONT['I'] = 0x1c0808081cL;
        FONT['J'] = 0x0e04042418L; FONT['K'] = 0x2224382422L; FONT['L'] = 0x202020203eL;
        FONT['M'] = 0x22362a2222L; FONT['N'] = 0x22322a2622L; FONT['O'] = 0x1c2222221cL;
        FONT['P'] = 0x3c223c2020L; FONT['Q'] = 0x1c22222a1cL; FONT['R'] = 0x3c223c2422L;
        FONT['S'] = 0x1e201c023cL; FONT['T'] = 0x3e08080808L; FONT['U'] = 0x222222221cL;
        FONT['V'] = 0x2222221408L; FONT['W'] = 0x22222a3622L; FONT['X'] = 0x2214081422L;
        FONT['Y'] = 0x2214080808L; FONT['Z'] = 0x3e0408103eL;
        FONT['0'] = 0x1c262a321cL; FONT['1'] = 0x081808081cL; FONT['2'] = 0x1c021c203eL;
        FONT['3'] = 0x3e040e023cL; FONT['4'] = 0x24243e0404L; FONT['5'] = 0x3e203c023cL;
        FONT['6'] = 0x1c203c221cL; FONT['7'] = 0x3e02040810L; FONT['8'] = 0x1c221c221cL;
        FONT['9'] = 0x1c221e021cL;
        FONT[':'] = 0x0008000800L; FONT['-'] = 0x00003e0000L; FONT['.'] = 0x0000000008L;
        FONT['['] = 0x1c1010101cL; FONT[']'] = 0x1c0404041cL; FONT['('] = 0x0810101008L;
        FONT[')'] = 0x1008080810L; FONT['_'] = 0x000000003eL; FONT['/'] = 0x0204081020L;
        FONT['>'] = 0x2010081020L; FONT['<'] = 0x0810201008L; FONT['='] = 0x003e003e00L;
        FONT['|'] = 0x0808080808L; FONT['%'] = 0x2408102024L; FONT['~'] = 0x000a150a00L;
        FONT['+'] = 0x08083e0808L; FONT[','] = 0x0000000810L; FONT['!'] = 0x0808080008L;
        FONT['?'] = 0x1c22040008L; FONT['\''] = 0x0808000000L; FONT['*'] = 0x002a1c2a00L;
        FONT['&'] = 0x18241a241aL;
    }

    // Mac modifier symbols and other glyphs outside the 7-bit table
    private static final long GLYPH_COMMAND = 0x363e143e36L;   // ⌘
    private static final long GLYPH_SHIFT = 0x081436141cL;     // ⇧
    private static final long GLYPH_OPTION = 0x3610080406L;    // ⌥
    private static final long GLYPH_DELETE = 0x0e122a120eL;    // ⌫
    private static final long GLYPH_BULLET = 0x00081c0800L;    // •
    private static final long GLYPH_ELLIPSIS = 0x000000002aL;  // …

    private static long glyph(char c) {
        if (c >= 'a' && c <= 'z') c = (char) (c - 32);
        if (c < FONT.length) return FONT[c];
        return switch (c) {
            case '⌘' -> GLYPH_COMMAND;
            case '⇧' -> GLYPH_SHIFT;
            case '⌥' -> GLYPH_OPTION;
            case '⌫' -> GLYPH_DELETE;
            case '•' -> GLYPH_BULLET;
            case '…' -> GLYPH_ELLIPSIS;
            default -> 0L;
        };
    }

    // Legacy bitmap metrics: cap height and advance per unit of text size (old text vertically centers on this cap)
    private static final float BITMAP_CAP_NDC = 0.015f;
    private static final float BITMAP_ADVANCE_NDC = 0.012f;

    /** Snaps an NDC coordinate to the nearest window pixel boundary along an axis spanning {@code pixels}. */
    private static float snapToPixel(float ndc, float pixels) {
        float half = pixels * 0.5f;
        return Math.round(ndc * half) / half;
    }

    // --- Packed 0xRRGGBB color overloads ---
    private static float red(int rgb) { return ((rgb >> 16) & 0xFF) / 255.0f; }
    private static float green(int rgb) { return ((rgb >> 8) & 0xFF) / 255.0f; }
    private static float blue(int rgb) { return (rgb & 0xFF) / 255.0f; }

    public static void addQuad(Buffer buffer, float x1, float y1, float x2, float y2, int rgb, float a) {
        addQuad(buffer, x1, y1, x2, y2, red(rgb), green(rgb), blue(rgb), a);
    }

    public static void addFrameBorder(Buffer buffer, float x1, float y1, float x2, float y2, float t, int rgb, float a) {
        addFrameBorder(buffer, x1, y1, x2, y2, t, red(rgb), green(rgb), blue(rgb), a);
    }

    public static void addTriangle(Buffer buffer, float x1, float y1, float x2, float y2, float x3, float y3, int rgb, float a) {
        addTriangle(buffer, x1, y1, x2, y2, x3, y3, red(rgb), green(rgb), blue(rgb), a);
    }

    public static void addBoundedText(Buffer buffer, CharSequence text, float startX, float startY, float maxX, float size, int rgb, float a) {
        addBoundedText(buffer, text, startX, startY, maxX, size, red(rgb), green(rgb), blue(rgb), a);
    }

    public static void addLineSegment(Buffer buffer, float x1, float y1, float x2, float y2, float thickness, int rgb, float a) {
        addLineSegment(buffer, x1, y1, x2, y2, thickness, red(rgb), green(rgb), blue(rgb), a);
    }

    public static void addArrowhead(Buffer buffer, float tipX, float tipY, float dirX, float dirY, float length, float width, int rgb, float a) {
        addArrowhead(buffer, tipX, tipY, dirX, dirY, length, width, red(rgb), green(rgb), blue(rgb), a);
    }

    // --- Rounded shapes ---
    private static final int CORNER_SEGMENTS = 5;
    private static final float[] CORNER_COS = new float[CORNER_SEGMENTS + 1];
    private static final float[] CORNER_SIN = new float[CORNER_SEGMENTS + 1];
    static {
        for (int i = 0; i <= CORNER_SEGMENTS; i++) {
            double t = (Math.PI * 0.5) * i / CORNER_SEGMENTS;
            CORNER_COS[i] = (float) Math.cos(t);
            CORNER_SIN[i] = (float) Math.sin(t);
        }
    }

    /**
     * Filled rounded rectangle. {@code radius} is in Y units of the current drawing space; the X radius
     * is derived so corners stay circular on screen, even inside a non-uniformly mapped dock panel.
     * A radius of half the height yields a pill.
     */
    public static void addRoundedQuad(Buffer buffer, float x1, float y1, float x2, float y2, float radius, int rgb, float a) {
        float r = red(rgb), g = green(rgb), b = blue(rgb);
        float w = x2 - x1, h = y2 - y1;
        if (w <= 0.0f || h <= 0.0f) return;
        float ry = Math.min(radius, h * 0.5f);
        float rx = Math.min(ry * buffer.pixelSquareXPerY(), w * 0.5f);
        if (rx <= 0.0f || ry <= 0.0f) {
            addQuad(buffer, x1, y1, x2, y2, r, g, b, a);
            return;
        }
        // Center column plus left/right strips between the corners
        addQuad(buffer, x1 + rx, y1, x2 - rx, y2, r, g, b, a);
        if (h > 2.0f * ry) {
            addQuad(buffer, x1, y1 + ry, x1 + rx, y2 - ry, r, g, b, a);
            addQuad(buffer, x2 - rx, y1 + ry, x2, y2 - ry, r, g, b, a);
        }
        addCorner(buffer, x2 - rx, y2 - ry, rx, ry, 1.0f, 1.0f, r, g, b, a);
        addCorner(buffer, x1 + rx, y2 - ry, rx, ry, -1.0f, 1.0f, r, g, b, a);
        addCorner(buffer, x1 + rx, y1 + ry, rx, ry, -1.0f, -1.0f, r, g, b, a);
        addCorner(buffer, x2 - rx, y1 + ry, rx, ry, 1.0f, -1.0f, r, g, b, a);
    }

    private static void addCorner(Buffer buffer, float cx, float cy, float rx, float ry, float sx, float sy, float r, float g, float b, float a) {
        for (int i = 0; i < CORNER_SEGMENTS; i++) {
            addTriangle(buffer, cx, cy,
                    cx + sx * rx * CORNER_COS[i], cy + sy * ry * CORNER_SIN[i],
                    cx + sx * rx * CORNER_COS[i + 1], cy + sy * ry * CORNER_SIN[i + 1],
                    r, g, b, a);
        }
    }

    /** Rounded card with a hairline border: border-colored shape with the fill inset on top. */
    public static void addRoundedCard(Buffer buffer, float x1, float y1, float x2, float y2, float radius, int fillRgb, int borderRgb, float a) {
        float ty = 0.0025f;
        float tx = ty * buffer.pixelSquareXPerY();
        addRoundedQuad(buffer, x1, y1, x2, y2, radius, borderRgb, a);
        addRoundedQuad(buffer, x1 + tx, y1 + ty, x2 - tx, y2 - ty, Math.max(0.0f, radius - ty), fillRgb, a);
    }

    /** X extent that matches {@code height} on screen in the buffer's current drawing space (for squares, circles, knobs). */
    public static float squareWidth(Buffer buffer, float height) {
        return height * buffer.pixelSquareXPerY();
    }

    public static void addPill(Buffer buffer, float x1, float y1, float x2, float y2, int rgb, float a) {
        addRoundedQuad(buffer, x1, y1, x2, y2, (y2 - y1) * 0.5f, rgb, a);
    }

    // --- Text measurement & truncation ---
    /** Screen-space NDC-X width of one em unit at {@code size} (already including the panel text scale). */
    private static float emScreenWidth(Buffer buffer, float size) {
        return size * buffer.getTextScale() * UIFont.EM_NDC_PER_SIZE * buffer.pixelAspect;
    }

    /** Width of {@code len} average (digit-width) glyphs at {@code size}, in the buffer's current drawing space. */
    public static float textWidth(Buffer buffer, int len, float size) {
        if (len <= 0) return 0.0f;
        UIFont font = UIFont.active();
        float screenW = font != null
                ? len * font.digitAdvance * emScreenWidth(buffer, size)
                : len * size * BITMAP_ADVANCE_NDC * buffer.getTextScale();
        return buffer.screenToLocalWidth(screenW);
    }

    /** Text size whose em box is {@code emPixels} window pixels tall on {@code buffer}'s window. */
    public static float textSizeForEmPixels(Buffer buffer, float emPixels) {
        return (emPixels * 2.0f / buffer.windowHeight) / UIFont.EM_NDC_PER_SIZE;
    }

    /** Unmapped (screen-space) width of {@code text} at {@code size}, ignoring any panel mapping on {@code buffer}. */
    public static float screenTextWidth(Buffer buffer, CharSequence text, float size) {
        if (text == null) return 0.0f;
        UIFont font = UIFont.active();
        return font != null
                ? font.measure(text, 0, text.length()) * size * UIFont.EM_NDC_PER_SIZE * buffer.pixelAspect
                : text.length() * size * BITMAP_ADVANCE_NDC;
    }

    public static float textWidth(Buffer buffer, CharSequence text, float size) {
        if (text == null) return 0.0f;
        UIFont font = UIFont.active();
        if (font == null) return textWidth(buffer, text.length(), size);
        return buffer.screenToLocalWidth(font.measure(text, 0, text.length()) * emScreenWidth(buffer, size));
    }

    /** Text right-aligned so it ends at {@code rightX}. */
    public static void addRightAlignedText(Buffer buffer, CharSequence text, float rightX, float startY, float size, int rgb, float a) {
        if (text == null) return;
        float x = rightX - textWidth(buffer, text, size);
        addBoundedText(buffer, text, x, startY, rightX + 0.001f, size, rgb, a);
    }

    /** Text clipped to [startX, maxX], ending in an ellipsis instead of a hard cut when it doesn't fit. */
    public static void addTruncatedText(Buffer buffer, CharSequence text, float startX, float startY, float maxX, float size, int rgb, float a) {
        if (text == null) return;
        UIFont font = UIFont.active();
        if (font != null) {
            float em = buffer.screenToLocalWidth(emScreenWidth(buffer, size));
            float avail = em > 0.0f ? (maxX - startX) / em : Float.MAX_VALUE;
            if (font.measure(text, 0, text.length()) <= avail) {
                addBoundedText(buffer, text, startX, startY, maxX + 0.001f, size, rgb, a);
                return;
            }
            float budget = avail - font.advanceOf('…');
            int fit = 0;
            float used = 0.0f;
            while (fit < text.length() && used + font.advanceOf(text.charAt(fit)) <= budget) {
                used += font.advanceOf(text.charAt(fit++));
            }
            StringBuilder truncScratch = buffer.truncScratch;
            truncScratch.setLength(0);
            truncScratch.append(text, 0, fit).append('…');
            addBoundedText(buffer, truncScratch, startX, startY, maxX + 0.001f, size, rgb, a);
            return;
        }
        float perChar = textWidth(buffer, 1, size);
        int fit = perChar > 0.0f ? (int) ((maxX - startX) / perChar) : text.length();
        if (text.length() <= fit) {
            addBoundedText(buffer, text, startX, startY, maxX + 0.001f, size, rgb, a);
            return;
        }
        if (fit <= 0) return;
        StringBuilder truncScratch = buffer.truncScratch;
        truncScratch.setLength(0);
        truncScratch.append(text, 0, Math.max(0, fit - 1)).append('…');
        addBoundedText(buffer, truncScratch, startX, startY, maxX + 0.001f, size, rgb, a);
    }

    // --- Primitive Buffer Overloads (Zero Allocation) ---
    public static void addVert(Buffer buffer, float x, float y, float r, float g, float b, float a) {
        addVert(buffer, x, y, r, g, b, a, NO_UV, NO_UV);
    }

    public static void addVert(Buffer buffer, float x, float y, float r, float g, float b, float a, float u, float v) {
        if (buffer.mapped) {
            buffer.stageVertex(x, y, r, g, b, a, u, v);
            return;
        }
        buffer.ensureCapacity(buffer.size + VERTEX_STRIDE);
        float[] d = buffer.data;
        int o = buffer.size;
        d[o] = x; d[o + 1] = y; d[o + 2] = r; d[o + 3] = g; d[o + 4] = b; d[o + 5] = a; d[o + 6] = u; d[o + 7] = v;
        buffer.size = o + VERTEX_STRIDE;
    }

    /** Quad sampling the font atlas over [u0,u1]x[v0,v1]; v0 maps to the top edge (y2). */
    private static void addGlyphQuad(Buffer buffer, float x1, float y1, float x2, float y2, float u0, float v0, float u1, float v1,
                                     float r, float g, float b, float a) {
        addVert(buffer, x1, y1, r, g, b, a, u0, v1); addVert(buffer, x2, y1, r, g, b, a, u1, v1); addVert(buffer, x1, y2, r, g, b, a, u0, v0);
        addVert(buffer, x2, y1, r, g, b, a, u1, v1); addVert(buffer, x2, y2, r, g, b, a, u1, v0); addVert(buffer, x1, y2, r, g, b, a, u0, v0);
    }

    /** Quad showing the backend's camera preview render target; the top edge (y2) maps to the image's top row. */
    public static void addCameraPreviewQuad(Buffer buffer, float x1, float y1, float x2, float y2, float a) {
        float u0 = PREVIEW_UV_OFFSET, u1 = PREVIEW_UV_OFFSET + 1.0f;
        addGlyphQuad(buffer, x1, y1, x2, y2, u0, u0, u1, u1, 1.0f, 1.0f, 1.0f, a);
    }

    public static void addQuad(Buffer buffer, float x1, float y1, float x2, float y2, float r, float g, float b, float a) {
        addVert(buffer, x1, y1, r, g, b, a); addVert(buffer, x2, y1, r, g, b, a); addVert(buffer, x1, y2, r, g, b, a);
        addVert(buffer, x2, y1, r, g, b, a); addVert(buffer, x2, y2, r, g, b, a); addVert(buffer, x1, y2, r, g, b, a);
    }

    public static void addFrameBorder(Buffer buffer, float x1, float y1, float x2, float y2, float t, float r, float g, float b, float a) {
        addQuad(buffer, x1, y2 - t, x2, y2, r, g, b, a);
        addQuad(buffer, x1, y1, x2, y1 + t, r, g, b, a);
        addQuad(buffer, x1, y1, x1 + t, y2, r, g, b, a);
        addQuad(buffer, x2 - t, y1, x2, y2, r, g, b, a);
    }

    public static void addTriangle(Buffer buffer, float x1, float y1, float x2, float y2, float x3, float y3, float r, float g, float b, float a) {
        addVert(buffer, x1, y1, r, g, b, a);
        addVert(buffer, x2, y2, r, g, b, a);
        addVert(buffer, x3, y3, r, g, b, a);
    }

    public static void addLineSegment(Buffer buffer, float x1, float y1, float x2, float y2, float thickness, float r, float g, float b, float a) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len == 0.0f) return;

        float nx = -dy / len * (thickness * 0.5f);
        float ny = dx / len * (thickness * 0.5f);

        addVert(buffer, x1 + nx, y1 + ny, r, g, b, a);
        addVert(buffer, x2 + nx, y2 + ny, r, g, b, a);
        addVert(buffer, x1 - nx, y1 - ny, r, g, b, a);

        addVert(buffer, x2 + nx, y2 + ny, r, g, b, a);
        addVert(buffer, x2 - nx, y2 - ny, r, g, b, a);
        addVert(buffer, x1 - nx, y1 - ny, r, g, b, a);
    }

    public static void addArrowhead(Buffer buffer, float tipX, float tipY, float dirX, float dirY, float length, float width, float r, float g, float b, float a) {
        float len = (float) Math.sqrt(dirX * dirX + dirY * dirY);
        if (len == 0.0f) return;
        float ux = dirX / len;
        float uy = dirY / len;

        float perpX = -uy * width;
        float perpY = ux * width;

        float baseX = tipX - ux * length;
        float baseY = tipY - uy * length;

        addTriangle(buffer, tipX, tipY, baseX + perpX, baseY + perpY, baseX - perpX, baseY - perpY, r, g, b, a);
    }

    public static void addSquareEnd(Buffer buffer, float centerX, float centerY, float size, float r, float g, float b, float a) {
        float h = size * 0.5f;
        addQuad(buffer, centerX - h, centerY - h, centerX + h, centerY + h, r, g, b, a);
    }

    public static void addArcRing(Buffer buffer, float cx, float cy, float rx, float ry, float startAngle, float endAngle, float thickness, float r, float g, float b, float a) {
        int segments = 24;
        float startRad = (float) Math.toRadians(startAngle);
        float endRad = (float) Math.toRadians(endAngle);
        float step = (endRad - startRad) / segments;

        float prevX = cx + rx * (float) Math.cos(startRad);
        float prevY = cy + ry * (float) Math.sin(startRad);

        for (int i = 1; i <= segments; i++) {
            float angle = startRad + i * step;
            float currX = cx + rx * (float) Math.cos(angle);
            float currY = cy + ry * (float) Math.sin(angle);

            addLineSegment(buffer, prevX, prevY, currX, currY, thickness, r, g, b, a);
            prevX = currX;
            prevY = currY;
        }
    }

    public static void addBoundedText(Buffer buffer, String text, float startX, float startY, float maxX, float size, float r, float g, float b, float a) {
        addBoundedText(buffer, (CharSequence) text, startX, startY, maxX, size, r, g, b, a);
    }

    public static void addBoundedText(Buffer buffer, CharSequence text, float startX, float startY, float maxX, float size, float r, float g, float b, float a) {
        if (text == null) return;
        if (buffer.mapped) {
            // Lay glyphs out in screen space at a uniform scale so docked text is never stretched;
            // the anchor and right bound still follow the panel mapping, and clipping stays active
            float sStartX = buffer.mapX(startX), sStartY = buffer.mapY(startY), sMaxX = buffer.mapX(maxX);
            buffer.suspendTransform(true);
            try {
                emitText(buffer, text, sStartX, sStartY, sMaxX, size * buffer.textScale, r, g, b, a);
            } finally {
                buffer.suspendTransform(false);
            }
            return;
        }
        emitText(buffer, text, startX, startY, maxX, size, r, g, b, a);
    }

    private static void emitText(Buffer buffer, CharSequence text, float startX, float startY, float maxX, float size, float r, float g, float b, float a) {
        UIFont font = UIFont.active();
        if (font != null) {
            emitFontText(buffer, font, text, startX, startY, maxX, size, r, g, b, a);
            return;
        }
        float charW = size * 0.010f;
        float charH = size * 0.018f;
        float pixelW = charW / 6.0f;
        float pixelH = charH / 6.0f;

        int len = text.length();
        for (int i = 0; i < len; i++) {
            float cx = startX + i * (charW + pixelW * 1.2f);
            if (cx + charW > maxX) break;

            long mask = glyph(text.charAt(i));
            if (mask != 0L) {
                for (int row = 0; row < 5; row++) {
                    for (int col = 0; col < 6; col++) {
                        int shift = (4 - row) * 8 + (5 - col);
                        if (((mask >> shift) & 1L) != 0L) {
                            float px1 = cx + col * pixelW;
                            float py1 = startY - row * pixelH;
                            addQuad(buffer, px1, py1 - pixelH, px1 + pixelW, py1, r, g, b, a);
                        }
                    }
                }
            }
        }
    }

    /**
     * System-font text in screen space. The cap height is centered where the legacy bitmap cap sat, so existing
     * layouts keep their vertical alignment; the baseline and pen origin snap to window pixels for crisp edges.
     */
    private static void emitFontText(Buffer buffer, UIFont font, CharSequence text, float startX, float startY, float maxX, float size,
                                     float r, float g, float b, float a) {
        float pixelAspect = buffer.pixelAspect;
        float emY = size * UIFont.EM_NDC_PER_SIZE;
        float emX = emY * pixelAspect;
        float capY = font.capHeight * emY;
        float baseline = snapToPixel(startY - BITMAP_CAP_NDC * size * 0.5f - capY * 0.5f, buffer.windowHeight);
        float penX = snapToPixel(startX, buffer.windowWidth);
        float limit = maxX + 1e-5f;

        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            UIFont.Glyph gl = font.glyph(c);
            if (gl == null) {
                // Glyph missing from the system font: draw the bitmap glyph at the font's cap height
                float cellH = capY / 5.0f, cellW = cellH * pixelAspect;
                float adv = font.advanceOf(c) * emX;
                if (penX + adv > limit) break;
                long mask = glyph(c);
                if (mask != 0L) {
                    float top = baseline + capY;
                    for (int row = 0; row < 5; row++) {
                        for (int col = 0; col < 6; col++) {
                            if (((mask >> ((4 - row) * 8 + (5 - col))) & 1L) != 0L) {
                                float px1 = penX + col * cellW;
                                float py1 = top - row * cellH;
                                addQuad(buffer, px1, py1 - cellH, px1 + cellW, py1, r, g, b, a);
                            }
                        }
                    }
                }
                penX += adv;
                continue;
            }
            float adv = gl.advance * emX;
            if (penX + adv > limit) break;
            if (gl.hasQuad) {
                addGlyphQuad(buffer, penX + gl.x0 * emX, baseline - gl.y1 * emY, penX + gl.x1 * emX, baseline - gl.y0 * emY,
                        gl.u0, gl.v0, gl.u1, gl.v1, r, g, b, a);
            }
            penX += adv;
        }
    }

    // --- Backward Compatible List<Float> Overloads ---
    public static void addVert(List<Float> verts, float x, float y, float r, float g, float b, float a) {
        verts.add(x); verts.add(y);
        verts.add(r); verts.add(g); verts.add(b); verts.add(a);
        verts.add(NO_UV); verts.add(NO_UV);
    }

    public static void addQuad(List<Float> verts, float x1, float y1, float x2, float y2, float r, float g, float b, float a) {
        addVert(verts, x1, y1, r, g, b, a); addVert(verts, x2, y1, r, g, b, a); addVert(verts, x1, y2, r, g, b, a);
        addVert(verts, x2, y1, r, g, b, a); addVert(verts, x2, y2, r, g, b, a); addVert(verts, x1, y2, r, g, b, a);
    }

    public static void addFrameBorder(List<Float> verts, float x1, float y1, float x2, float y2, float t, float r, float g, float b, float a) {
        addQuad(verts, x1, y2 - t, x2, y2, r, g, b, a);
        addQuad(verts, x1, y1, x2, y1 + t, r, g, b, a);
        addQuad(verts, x1, y1, x1 + t, y2, r, g, b, a);
        addQuad(verts, x2 - t, y1, x2, y2, r, g, b, a);
    }

    public static void addTriangle(List<Float> verts, float x1, float y1, float x2, float y2, float x3, float y3, float r, float g, float b, float a) {
        addVert(verts, x1, y1, r, g, b, a);
        addVert(verts, x2, y2, r, g, b, a);
        addVert(verts, x3, y3, r, g, b, a);
    }

    public static void addLineSegment(List<Float> verts, float x1, float y1, float x2, float y2, float thickness, float r, float g, float b, float a) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len == 0.0f) return;

        float nx = -dy / len * (thickness * 0.5f);
        float ny = dx / len * (thickness * 0.5f);

        addVert(verts, x1 + nx, y1 + ny, r, g, b, a);
        addVert(verts, x2 + nx, y2 + ny, r, g, b, a);
        addVert(verts, x1 - nx, y1 - ny, r, g, b, a);

        addVert(verts, x2 + nx, y2 + ny, r, g, b, a);
        addVert(verts, x2 - nx, y2 - ny, r, g, b, a);
        addVert(verts, x1 - nx, y1 - ny, r, g, b, a);
    }

    public static void addArrowhead(List<Float> verts, float tipX, float tipY, float dirX, float dirY, float length, float width, float r, float g, float b, float a) {
        float len = (float) Math.sqrt(dirX * dirX + dirY * dirY);
        if (len == 0.0f) return;
        float ux = dirX / len;
        float uy = dirY / len;

        float perpX = -uy * width;
        float perpY = ux * width;

        float baseX = tipX - ux * length;
        float baseY = tipY - uy * length;

        addTriangle(verts, tipX, tipY, baseX + perpX, baseY + perpY, baseX - perpX, baseY - perpY, r, g, b, a);
    }

    public static void addSquareEnd(List<Float> verts, float centerX, float centerY, float size, float r, float g, float b, float a) {
        float h = size * 0.5f;
        addQuad(verts, centerX - h, centerY - h, centerX + h, centerY + h, r, g, b, a);
    }

    public static void addArcRing(List<Float> verts, float cx, float cy, float rx, float ry, float startAngle, float endAngle, float thickness, float r, float g, float b, float a) {
        int segments = 24;
        float startRad = (float) Math.toRadians(startAngle);
        float endRad = (float) Math.toRadians(endAngle);
        float step = (endRad - startRad) / segments;

        float prevX = cx + rx * (float) Math.cos(startRad);
        float prevY = cy + ry * (float) Math.sin(startRad);

        for (int i = 1; i <= segments; i++) {
            float angle = startRad + i * step;
            float currX = cx + rx * (float) Math.cos(angle);
            float currY = cy + ry * (float) Math.sin(angle);

            addLineSegment(verts, prevX, prevY, currX, currY, thickness, r, g, b, a);
            prevX = currX;
            prevY = currY;
        }
    }

    public static void addBoundedText(List<Float> verts, String text, float startX, float startY, float maxX, float size, float r, float g, float b, float a) {
        addBoundedText(verts, (CharSequence) text, startX, startY, maxX, size, r, g, b, a);
    }

    public static void addBoundedText(List<Float> verts, CharSequence text, float startX, float startY, float maxX, float size, float r, float g, float b, float a) {
        if (text == null) return;
        float charW = size * 0.010f;
        float charH = size * 0.018f;
        float pixelW = charW / 6.0f;
        float pixelH = charH / 6.0f;

        int len = text.length();
        for (int i = 0; i < len; i++) {
            float cx = startX + i * (charW + pixelW * 1.2f);
            if (cx + charW > maxX) break;

            long mask = glyph(text.charAt(i));
            if (mask != 0L) {
                for (int row = 0; row < 5; row++) {
                    for (int col = 0; col < 6; col++) {
                        int shift = (4 - row) * 8 + (5 - col);
                        if (((mask >> shift) & 1L) != 0L) {
                            float px1 = cx + col * pixelW;
                            float py1 = startY - row * pixelH;
                            addQuad(verts, px1, py1 - pixelH, px1 + pixelW, py1, r, g, b, a);
                        }
                    }
                }
            }
        }
    }
}
