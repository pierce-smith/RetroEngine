package com.retro.engine.modding;

import com.retro.engine.api.Experimental;
import com.retro.engine.api.PublicApi;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Priority-stacked overlay of mod asset folders on top of the project's base {@code Assets/} directory.
 *
 * <pre>
 *   Mods/SuperWeapons/Assets/   load_priority 200   first lookup, wins every conflict
 *   Mods/HDTextures/Assets/     load_priority 100
 *   Assets/                     vanilla base        fallback
 * </pre>
 *
 * Asset paths in scenes and prefabs resolve to vanilla files (by UUID, then path) as usual;
 * {@link #resolveOverride} then swaps in the highest mod layer that ships a file at the same
 * {@code Assets/}-relative path. Vanilla scenes therefore pick up modded textures, meshes and scripts without being
 * edited. Mods address their own new files with {@code mod://<mod_id>/<path inside the mod folder>}.
 *
 * <p>Immutable once built by {@link ModLoader}; lookups are plain file-existence checks with no caching, so assets
 * a mod adds while the game runs are seen on the next scene load.
 */
@PublicApi
public final class ModVirtualFileSystem {

    public static final String MOD_SCHEME = "mod://";

    /** One mounted overlay root of one mod. */
    public record Layer(String modId, int priority, File root) {}

    private final Path baseAssets;
    private final List<Layer> topDown;       // highest priority first
    private final Map<String, File> modRoots; // mod_id -> mod folder

    ModVirtualFileSystem(File baseAssetsDir, List<Layer> bottomUpLayers, Map<String, File> modRoots) {
        this.baseAssets = baseAssetsDir.getAbsoluteFile().toPath().normalize();
        List<Layer> reversed = new ArrayList<>(bottomUpLayers);
        Collections.reverse(reversed);
        this.topDown = Collections.unmodifiableList(reversed);
        this.modRoots = Collections.unmodifiableMap(new LinkedHashMap<>(modRoots));
    }

    static ModVirtualFileSystem empty(File baseAssetsDir) {
        return new ModVirtualFileSystem(baseAssetsDir, List.of(), Map.of());
    }

    public boolean hasOverlays() {
        return !topDown.isEmpty();
    }

    /** Mounted layers, highest priority first. */
    @Experimental
    public List<Layer> layers() {
        return topDown;
    }

    public File baseAssetsDir() {
        return baseAssets.toFile();
    }

    /**
     * Final on-disk path for an asset reference: {@code mod://} URIs map into their mod folder; paths inside the base
     * Assets/ folder (absolute, or relative like {@code Assets/Textures/Wall.png} / {@code Textures/Wall.png}) are
     * replaced by the top-most overlay copy. Anything else, or a path no mod overrides, is returned unchanged.
     */
    public String resolveOverride(String path) {
        if (path == null || path.isEmpty()) return path;
        if (path.startsWith(MOD_SCHEME)) {
            File f = resolveModUri(path);
            return f != null ? f.getAbsolutePath() : path;
        }
        if (topDown.isEmpty()) return path;
        String rel = assetsRelative(path);
        if (rel == null) return path;
        File f = findInOverlays(rel);
        return f != null ? f.getAbsolutePath() : path;
    }

    /** Top-most overlay file for an Assets/-relative path ("Textures/Wall.png"), or null if no mod ships one. */
    public File findInOverlays(String assetsRelativePath) {
        String rel = assetsRelativePath.replace('\\', '/');
        for (Layer layer : topDown) {
            File f = new File(layer.root(), rel);
            if (f.isFile()) return f;
        }
        return null;
    }

    /** {@code mod://<mod_id>/<path>} to a file inside that mod's folder; null for unknown mods or escaping paths. */
    public File resolveModUri(String uri) {
        String rest = uri.substring(MOD_SCHEME.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) return null;
        File root = modRoots.get(rest.substring(0, slash));
        if (root == null) return null;
        try {
            File f = new File(root, rest.substring(slash + 1)).getCanonicalFile();
            return f.toPath().startsWith(root.getCanonicalFile().toPath()) ? f : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Assets/-relative form of {@code path} (no leading "Assets/"), or null when it is not inside the base Assets/. */
    private String assetsRelative(String path) {
        String p = path.replace('\\', '/');
        File f = new File(p);
        if (f.isAbsolute()) {
            Path abs = f.toPath().normalize();
            return abs.startsWith(baseAssets) ? baseAssets.relativize(abs).toString().replace('\\', '/') : null;
        }
        while (p.startsWith("./")) p = p.substring(2);
        if (p.startsWith("Assets/")) p = p.substring("Assets/".length());
        return (p.startsWith("../") || p.isEmpty()) ? null : p;
    }
}
