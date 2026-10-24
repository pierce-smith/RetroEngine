package com.retro.engine.editor.ui.batch;

import java.util.List;

/**
 * UIGeometryBatch provides high-performance 2D UI vertex batching routines
 * for quads, borders, lines, circles, arrowheads, and bitmap font text.
 * Target JDK: JDK 25 / macOS (Zero-allocation primitive buffer support)
 */
public class UIGeometryBatch {

    public static class Buffer {
        private float[] data;
        private int size;

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
    }

    // --- Primitive Buffer Overloads (Zero Allocation) ---
    public static void addVert(Buffer buffer, float x, float y, float r, float g, float b, float a) {
        buffer.add(x); buffer.add(y);
        buffer.add(r); buffer.add(g); buffer.add(b); buffer.add(a);
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
        float charW = size * 0.010f;
        float charH = size * 0.018f;
        float pixelW = charW / 6.0f;
        float pixelH = charH / 6.0f;

        int len = text.length();
        for (int i = 0; i < len; i++) {
            float cx = startX + i * (charW + pixelW * 1.2f);
            if (cx + charW > maxX) break;

            char c = text.charAt(i);
            if (c >= 'a' && c <= 'z') {
                c = (char) (c - 32);
            }
            if (c >= 0 && c < FONT.length) {
                long mask = FONT[c];
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
    }

    // --- Backward Compatible List<Float> Overloads ---
    public static void addVert(List<Float> verts, float x, float y, float r, float g, float b, float a) {
        verts.add(x); verts.add(y);
        verts.add(r); verts.add(g); verts.add(b); verts.add(a);
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

            char c = text.charAt(i);
            if (c >= 'a' && c <= 'z') {
                c = (char) (c - 32);
            }
            if (c >= 0 && c < FONT.length) {
                long mask = FONT[c];
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
}
