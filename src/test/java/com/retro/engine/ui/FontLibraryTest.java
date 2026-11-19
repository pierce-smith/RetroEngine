package com.retro.engine.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.retro.engine.graphics.BufferDesc;
import com.retro.engine.graphics.GraphicsAPI;
import com.retro.engine.graphics.IRenderBackend;
import com.retro.engine.graphics.RenderQueue;
import com.retro.engine.graphics.TextureDesc;
import com.retro.engine.graphics.ViewportSpec;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Project fonts load in the background and are packed under the default font in one shared atlas. */
class FontLibraryTest {

    /** Records texture registrations and which one is bound as the UI font atlas. */
    private static final class AtlasBackend implements IRenderBackend {
        final List<int[]> registered = new ArrayList<>(); // {handle, width, height}
        final List<Long> released = new ArrayList<>();
        long bound;
        long next = 100;

        @Override public long registerTexture(String key, byte[] pixels, int w, int h) {
            assertEquals(w * h * 4, pixels.length, "tightly packed RGBA8");
            long handle = next++;
            registered.add(new int[] { (int) handle, w, h });
            return handle;
        }
        @Override public void unregisterTexture(long handle) { released.add(handle); }
        @Override public boolean setUiFontAtlas(long handle) { bound = handle; return true; }

        @Override public boolean initialize(ViewportSpec windowInfo) { return true; }
        @Override public void setCameraTransform(float panX, float panY, float zoom, float pitch, float yaw) {}
        @Override public void updateGridMesh(float[] vertices, int count) {}
        @Override public void updateUiMesh(float[] vertices, int count) {}
        @Override public void clearSceneQueue() {}
        @Override public void submitRenderQueue(RenderQueue queue) {}
        @Override public void beginFrame() {}
        @Override public void endFrame() {}
        @Override public void present() {}
        @Override public void shutdown() {}
        @Override public long getOffscreenViewportTexture() { return 0L; }
        @Override public void resizeViewport(int width, int height) {}
        @Override public long createBuffer(BufferDesc createInfo) { return 0L; }
        @Override public void destroyBuffer(long handle) {}
        @Override public long createTexture(TextureDesc createInfo) { return 0L; }
        @Override public void destroyTexture(long handle) {}
        @Override public GraphicsAPI getAPI() { return GraphicsAPI.VULKAN; }
        @Override public String getDeviceName() { return "test"; }
    }

    /** A TrueType font the OS ships, or null: none is bundled with the engine. */
    private static Path systemFont() {
        String[] candidates = {
            "/System/Library/Fonts/Supplemental/Arial.ttf", "/Library/Fonts/Arial.ttf",
            "C:\\Windows\\Fonts\\arial.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/TTF/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"
        };
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.isReadable(p)) return p;
        }
        return null;
    }

    private static UIFont awaitPacked(String path, AtlasBackend rhi) throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            UIFont f = FontLibrary.loaded(path);
            if (f != null) return f;
            FontLibrary.syncAtlas(rhi);
            Thread.sleep(10);
        }
        return FontLibrary.loaded(path);
    }

    @Test
    void aMissingFontKeepsTheDefaultAndNeverPacks() throws InterruptedException {
        AtlasBackend rhi = new AtlasBackend();
        String path = "/definitely/not/here/Missing.ttf";
        assertNull(FontLibrary.loaded(path));
        Thread.sleep(50);
        FontLibrary.syncAtlas(rhi);
        assertNull(FontLibrary.loaded(path));
        assertTrue(rhi.registered.isEmpty());
        assertEquals(FontLibrary.get(""), FontLibrary.get(path), "a missing font draws in the default font");
    }

    @Test
    void theBundledDefaultFontBakes() {
        UIFont heros = UIFont.bakeResource(FontLibrary.DEFAULT_GAME_FONT);
        assertNotNull(heros, "TeX Gyre Heros ships in the engine and stb_truetype reads its CFF outlines");
        UIFont.Glyph a = heros.glyph('a');
        assertNotNull(a);
        assertTrue(a.hasQuad && a.advance > 0.4f && a.advance < 0.7f, "a Helvetica-width lowercase a");
        assertNotNull(heros.glyph('é'), "Latin-1 accents are covered");
    }

    /** Inside/outside runs along the middle row of a glyph's SDF block: e.g. "OIOIO" for a ring. */
    private static String middleRowRuns(UIFont font, char c) {
        UIFont.Glyph g = font.glyph(c);
        byte[] px = font.getAtlasPixels();
        int x0 = Math.round(g.u0 * font.atlasWidth), x1 = Math.round(g.u1 * font.atlasWidth);
        int row = Math.round((g.v0 + g.v1) * 0.5f * font.atlasHeight);
        StringBuilder runs = new StringBuilder();
        for (int x = x0; x < x1; x++) {
            char state = (px[(row * font.atlasWidth + x) * 4] & 0xFF) >= 128 ? 'I' : 'O';
            if (runs.isEmpty() || runs.charAt(runs.length() - 1) != state) runs.append(state);
        }
        return runs.toString();
    }

    @Test
    void curvedLettersFromCubicOutlinesAreSolid() {
        // Heros is a CFF-outline OpenType font; stb's own SDF builder dropped its curves, leaving O, D, S broken
        UIFont heros = UIFont.bakeResource(FontLibrary.DEFAULT_GAME_FONT);
        assertNotNull(heros);
        assertEquals("OIOIO", middleRowRuns(heros, 'O'), "O is a closed ring");
        assertEquals("OIOIO", middleRowRuns(heros, 'D'), "D has its stem and its bowl");
        assertEquals("OIO", middleRowRuns(heros, 'S'), "S crosses the middle as one stroke");
        assertEquals("OIO", middleRowRuns(heros, 'I'), "a straight stem");
    }

    @Test
    void aProjectFontIsPackedIntoOneSharedAtlas() throws InterruptedException {
        Path ttf = systemFont();
        assumeTrue(ttf != null, "no TrueType font on this machine");
        AtlasBackend rhi = new AtlasBackend();
        long baseHandle = 7;
        UIFont base = UIFont.bakeFile(ttf);
        assertNotNull(base);
        FontLibrary.initBase(base, baseHandle);

        UIFont font = awaitPacked(ttf.toString(), rhi);
        assertNotNull(font, "the font loads within a few frames");
        assertEquals(1, rhi.registered.size());
        int[] atlas = rhi.registered.get(0);
        assertEquals(atlas[0], rhi.bound, "the new atlas is bound");
        assertEquals(base.atlasHeight + font.atlasHeight, atlas[2], "the project font is stacked under the default one");
        assertEquals(List.of(baseHandle), rhi.released, "the old atlas is released");

        // The default font's glyphs now sit in the top part of the taller texture, the project font's in the bottom part
        UIFont.Glyph baseA = base.glyph('A'), fontA = font.glyph('A');
        assertTrue(baseA.v1 <= (float) base.atlasHeight / atlas[2] + 1e-6f);
        assertTrue(fontA.v0 >= (float) base.atlasHeight / atlas[2] - 1e-6f);
        assertNotEquals(baseA.v0, fontA.v0);

        FontLibrary.syncAtlas(rhi);
        assertEquals(1, rhi.registered.size(), "nothing new, no repack");
    }
}
