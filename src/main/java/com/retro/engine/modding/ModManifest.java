package com.retro.engine.modding;

import com.retro.engine.api.Internal;
import com.retro.engine.api.PublicApi;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parsed {@code Mods/<Folder>/mod.json}. Canonical keys follow the Volume 5 modding guide (snake_case); the camelCase
 * aliases in parentheses are accepted too.
 *
 * <pre>
 * {
 *   "mod_id": "example_mod",          (id)            required, [a-z0-9_.-]
 *   "name": "Example Mod",
 *   "version": "1.0.0",
 *   "author": "You",
 *   "target_engine": "1.0.0",         (targetEngine)  a different major version is refused; minor/patch differences are logged
 *   "load_priority": 100,             (priority, loadOrder)  higher loads later and wins VFS / patch conflicts
 *   "dependencies": ["core_lib", {"mod_id": "hd_textures", "version": "1.2.0", "optional": true}],
 *   "entry_point": "com.example.examplemod.ModScript",   (entryPoint)   optional ModScript subclass
 *   "script_sources": ["Scripts/ModScript.java"],          (scriptSources) compiled in this order
 *   "asset_overlays": ["Assets/"],                         (assetOverlays) VFS overlay roots, default Assets/
 *   "patches": ["Patches/"],                                .rpatch files or folders, default Patches/
 *   "shaders": ["Shaders/"]                                 shader override roots (vulkan/, metal/), default Shaders/
 * }
 * </pre>
 */
@PublicApi
public final class ModManifest {

    public static final String FILE_NAME = "mod.json";
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_.-]{1,64}");

    /** One dependency entry; {@code minVersion} is empty when any version satisfies it. */
    public record Dependency(String modId, String minVersion, boolean optional) {}

    public final File root;
    public final String id;
    public final String name;
    public final String version;
    public final String author;
    public final String targetEngine;
    public final int loadPriority;
    public final List<Dependency> dependencies;
    public final String entryPoint;
    public final List<String> scriptSources;
    public final List<String> assetOverlays;
    public final List<String> patches;
    public final List<String> shaders;

    private ModManifest(File root, Map<String, Object> json) throws IOException {
        this.root = root;
        this.id = MiniJson.string(json, "", "mod_id", "id").trim();
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IOException("mod_id \"" + id + "\" must be 1-64 chars of a-z, 0-9, '_', '.', '-'");
        }
        this.name = MiniJson.string(json, id, "name");
        this.version = MiniJson.string(json, "0.0.0", "version");
        this.author = MiniJson.string(json, "", "author");
        this.targetEngine = MiniJson.string(json, "", "target_engine", "targetEngine");
        this.loadPriority = MiniJson.integer(json, 0, "load_priority", "priority", "loadOrder");
        this.entryPoint = MiniJson.string(json, "", "entry_point", "entryPoint").trim();

        List<Dependency> deps = new ArrayList<>();
        for (Object d : MiniJson.list(json, "dependencies")) {
            if (d instanceof String s && !s.isBlank()) {
                deps.add(new Dependency(s.trim(), "", false));
            } else if (d instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dm = (Map<String, Object>) m;
                String depId = MiniJson.string(dm, "", "mod_id", "id").trim();
                if (depId.isEmpty()) throw new IOException("dependency entry without mod_id");
                deps.add(new Dependency(depId, MiniJson.string(dm, "", "version").trim(), MiniJson.bool(dm, false, "optional")));
            } else {
                throw new IOException("dependencies must be mod_id strings or {\"mod_id\": ...} objects");
            }
        }
        this.dependencies = Collections.unmodifiableList(deps);
        this.scriptSources = strings(json, List.of(), "script_sources", "scriptSources");
        this.assetOverlays = strings(json, List.of("Assets/"), "asset_overlays", "assetOverlays");
        this.patches = strings(json, List.of("Patches/"), "patches");
        this.shaders = strings(json, List.of("Shaders/"), "shaders");

        if (!entryPoint.isEmpty() && scriptSources.isEmpty()) {
            throw new IOException("entry_point is set but script_sources is empty");
        }
    }

    @Internal
    public static ModManifest read(File modRoot) throws IOException {
        File file = new File(modRoot, FILE_NAME);
        String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        try {
            return new ModManifest(modRoot.getAbsoluteFile(), MiniJson.parseObject(text));
        } catch (MiniJson.ParseException e) {
            throw new IOException(file + ": " + e.getMessage());
        }
    }

    /** Resolves a mod-relative path ("Scripts/Foo.java") against this mod's folder, refusing paths that escape it. */
    public File resolve(String relative) throws IOException {
        File f = new File(root, relative.replace('\\', '/')).getCanonicalFile();
        if (!f.toPath().startsWith(root.getCanonicalFile().toPath())) {
            throw new IOException("Path escapes mod folder: " + relative);
        }
        return f;
    }

    @Override
    public String toString() {
        return id + "@" + version;
    }

    private static List<String> strings(Map<String, Object> json, List<String> fallback, String... keys) {
        if (MiniJson.first(json, keys) == null) return fallback;
        List<String> out = new ArrayList<>();
        for (Object o : MiniJson.list(json, keys)) {
            if (o instanceof String s && !s.isBlank()) out.add(s.trim());
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Compares dotted numeric versions ("1.10.0" > "1.9.2"); non-numeric suffixes such as "-beta" are ignored.
     * Returns negative, zero or positive like {@link Comparable#compareTo}.
     */
    @Internal
    public static int compareVersions(String a, String b) {
        String[] pa = stripSuffix(a).split("\\."), pb = stripSuffix(b).split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int va = i < pa.length ? parseIntOrZero(pa[i]) : 0;
            int vb = i < pb.length ? parseIntOrZero(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static String stripSuffix(String v) {
        if (v == null) return "0";
        int dash = v.indexOf('-');
        return (dash >= 0 ? v.substring(0, dash) : v).trim();
    }

    private static int parseIntOrZero(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
