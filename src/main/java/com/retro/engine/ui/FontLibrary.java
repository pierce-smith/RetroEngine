package com.retro.engine.ui;

import com.retro.engine.graphics.IRenderBackend;
import com.retro.engine.scripting.ScriptEngine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fonts for game UI text. Text with no font set uses the engine's bundled default, TeX Gyre Heros (a Helvetica design
 * under the GUST Font License; see NOTICE.txt), so games look the same on every OS; the editor's own UI keeps the
 * system font. Project fonts: {@link #get} returns a font once it is ready; the first request bakes the file on a
 * virtual thread, and until it is packed the text draws in the default font. {@link #syncAtlas} (render thread, once per
 * frame before UI layout) stacks every ready font under the default UI font in one shared SDF atlas, so the backends
 * keep sampling a single texture. Fonts stay loaded for the session.
 */
public final class FontLibrary {

    /** The bundled default game font, a classpath resource. */
    public static final String DEFAULT_GAME_FONT = "/fonts/texgyreheros-regular.otf";

    /** Tallest shared atlas; a font that would grow past it is refused (with a warning) and keeps the default font. */
    static final int MAX_ATLAS_HEIGHT = 8192;

    private static final class Entry {
        final Path path;
        volatile UIFont baked;
        volatile boolean failed;
        boolean placed;

        Entry(Path path) { this.path = path; }
    }

    private static final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private static final List<Entry> placedOrder = new ArrayList<>();
    private static UIFont baseFont;
    private static Entry defaultEntry;
    private static long atlasHandle;
    private static int atlasVersion;

    private FontLibrary() {}

    /**
     * Called once the editor UI font's atlas is bound: {@code font} (null when the bitmap fallback is in use) and the
     * texture handle bound for it (0 when none). Later repacks replace that texture.
     */
    public static synchronized void initBase(UIFont font, long handle) {
        baseFont = font;
        atlasHandle = handle;
    }

    /**
     * {@link #initBase}, then bakes the bundled default game font and packs it right away (render thread, before the
     * first frame), so game text never starts in a different font. If it can't load, game text uses the system font.
     */
    public static synchronized void initBase(UIFont font, long handle, IRenderBackend rhi) {
        initBase(font, handle);
        if (defaultEntry != null) return;
        Entry e = new Entry(Path.of(DEFAULT_GAME_FONT));
        UIFont baked = UIFont.bakeResource(DEFAULT_GAME_FONT);
        if (baked == null) e.failed = true;
        else e.baked = baked;
        defaultEntry = e;
        entries.put(DEFAULT_GAME_FONT, e);
        syncAtlas(rhi);
    }

    /**
     * The font to draw game text with: the font at {@code path} (absolute, or relative to the project folder or its
     * Assets folder) once it is ready, else the bundled default game font (while it loads, or for good if the file is
     * missing or broken, which is reported once). Empty or null asks for the default. Null means even the default
     * failed to load: use the system font.
     */
    public static UIFont get(String path) {
        UIFont font = loaded(path);
        if (font != null) return font;
        Entry d = defaultEntry;
        return d != null && d.placed ? d.baked : null;
    }

    /** The font at {@code path} if it is packed and ready, else null (and its loading starts). Empty or null: null. */
    static UIFont loaded(String path) {
        if (path == null || path.isEmpty()) return null;
        Entry e = entries.get(path);
        if (e == null) {
            Path resolved = resolve(path);
            Entry fresh = new Entry(resolved);
            e = entries.putIfAbsent(path, fresh);
            if (e == null) {
                e = fresh;
                Entry loading = fresh;
                Thread.ofVirtual().name("font-bake").start(() -> {
                    UIFont font = UIFont.bakeFile(loading.path);
                    if (font == null) loading.failed = true;
                    else loading.baked = font;
                });
            }
        }
        return e.placed ? e.baked : null;
    }

    /**
     * Packs fonts that finished baking into a new shared atlas and binds it. Render thread only, before this frame's UI
     * is laid out, so every glyph emitted this frame uses the new UVs.
     */
    public static synchronized void syncAtlas(IRenderBackend rhi) {
        if (rhi == null) return;
        List<Entry> ready = null;
        for (Entry e : entries.values()) {
            if (!e.placed && !e.failed && e.baked != null) {
                if (ready == null) ready = new ArrayList<>();
                ready.add(e);
            }
        }
        if (ready == null) return;

        int width = UIFont.atlasWidth();
        int height = baseFont != null ? baseFont.atlasHeight : 0;
        for (Entry e : placedOrder) height += e.baked.atlasHeight;
        List<Entry> adding = new ArrayList<>();
        for (Entry e : ready) {
            if (height + e.baked.atlasHeight > MAX_ATLAS_HEIGHT) {
                e.failed = true;
                System.err.println("[FontLibrary] WARNING: Too many fonts in use; " + e.path.getFileName() + " keeps the default font.");
                continue;
            }
            height += e.baked.atlasHeight;
            adding.add(e);
        }
        if (adding.isEmpty()) return;

        List<UIFont> fonts = new ArrayList<>();
        if (baseFont != null) fonts.add(baseFont);
        for (Entry e : placedOrder) fonts.add(e.baked);
        for (Entry e : adding) fonts.add(e.baked);

        byte[] pixels = new byte[width * height * 4];
        int[] offsets = new int[fonts.size()];
        int y = 0;
        for (int i = 0; i < fonts.size(); i++) {
            UIFont f = fonts.get(i);
            byte[] block = f.getAtlasPixels();
            System.arraycopy(block, 0, pixels, y * width * 4, Math.min(block.length, f.atlasHeight * width * 4));
            offsets[i] = y;
            y += f.atlasHeight;
        }

        long handle = rhi.registerTexture("editor://ui_font_atlas#" + (++atlasVersion), pixels, width, height);
        if (handle == 0L || !rhi.setUiFontAtlas(handle)) {
            if (handle != 0L) rhi.unregisterTexture(handle);
            for (Entry e : adding) e.failed = true;
            System.err.println("[FontLibrary] WARNING: The backend could not bind the font atlas; project fonts use the default font.");
            return;
        }
        for (int i = 0; i < fonts.size(); i++) fonts.get(i).relocate(offsets[i], height);
        if (atlasHandle != 0L) rhi.unregisterTexture(atlasHandle);
        atlasHandle = handle;
        for (Entry e : adding) {
            e.placed = true;
            placedOrder.add(e);
            System.out.println("[FontLibrary] Loaded font " + e.path);
        }
    }

    private static Path resolve(String path) {
        Path p = Path.of(path);
        if (p.isAbsolute()) return p;
        String assetsRoot = ScriptEngine.getProjectAssetsRoot();
        if (assetsRoot != null) {
            Path assets = Path.of(assetsRoot);
            Path underAssets = assets.resolve(path);
            if (java.nio.file.Files.exists(underAssets)) return underAssets;
            Path project = assets.getParent();
            if (project != null) {
                Path underProject = project.resolve(path);
                if (java.nio.file.Files.exists(underProject)) return underProject;
            }
            return underAssets;
        }
        return p.toAbsolutePath();
    }
}
