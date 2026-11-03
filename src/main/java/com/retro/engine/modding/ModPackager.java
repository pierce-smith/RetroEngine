package com.retro.engine.modding;

import com.retro.engine.assets.RpackCooker;
import com.retro.engine.scripting.EntityScript;

import org.codehaus.janino.SimpleCompiler;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/**
 * "File > Package .rmod": validates a mod and writes {@code <mod_id>-<version>.rmod}, ready to drop into a
 * game's {@code Game_Data/Mods/} or upload to a mod site.
 * <ol>
 *   <li>Manifest: parsed and checked; required dependencies must be installed next to it for validation.</li>
 *   <li>Scripts: {@code script_sources} compile on the dependency class loader chain; the entry point must extend
 *       ModScript; every other .java file in the mod (EntityScripts) must compile against that chain too.</li>
 *   <li>Patches: every .rpatch must parse.</li>
 *   <li>Cook: meshes and textures get {@code .rpack} sidecars (AssetStreamer's fast path); raw files stay because
 *       scenes and rigged meshes reference them.</li>
 *   <li>Manifest regenerated in canonical form (comments removed), then everything is zipped.</li>
 * </ol>
 */
public final class ModPackager {

    private static final Set<String> MESH_EXT = Set.of("obj", "fbx", "gltf", "glb");
    private static final Set<String> TEXTURE_EXT = Set.of("png", "jpg", "jpeg", "tga");

    private ModPackager() {}

    /** Packages {@code modFolder} into {@code outputDir}; {@code progress} receives (0..1, status). Returns the .rmod. */
    public static File packageMod(File modFolder, File outputDir, BiConsumer<Float, String> progress) throws Exception {
        File folder = modFolder.getAbsoluteFile();
        progress.accept(0.05f, "VALIDATING MANIFEST");
        ModManifest mod = ModManifest.read(folder);
        File project = ModWorkspace.projectOf(folder);

        progress.accept(0.15f, "COMPILING SCRIPTS");
        validateScripts(mod, project);

        progress.accept(0.30f, "VALIDATING PATCHES");
        int patches = validatePatches(mod);

        progress.accept(0.40f, "STAGING FILES");
        File staging = new File(outputDir, "." + mod.id + "-staging");
        ModArchive.deleteRecursively(staging.toPath());
        copyMod(folder.toPath(), staging.toPath());

        progress.accept(0.55f, "COOKING ASSETS");
        int cooked = cookSidecars(staging.toPath());

        progress.accept(0.85f, "WRITING MANIFEST");
        Files.writeString(new File(staging, ModManifest.FILE_NAME).toPath(), MiniJson.write(canonicalManifest(mod)), StandardCharsets.UTF_8);

        progress.accept(0.92f, "PACKAGING .RMOD");
        File out = new File(outputDir, mod.id + "-" + mod.version + ModArchive.EXTENSION);
        ModArchive.write(staging, out, rel -> true);
        ModArchive.deleteRecursively(staging.toPath());

        System.out.println("[ModPackager] " + mod + ": " + patches + " patch(es), " + cooked + " cooked asset(s) -> " + out);
        progress.accept(1.0f, "MOD PACKAGE READY: " + out.getAbsolutePath());
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------------------------------

    private static void validateScripts(ModManifest mod, File project) throws Exception {
        // Dependencies (installed in the same project) compile first, in load order
        ClassLoader chain = EntityScript.class.getClassLoader();
        if (!mod.dependencies.isEmpty()) {
            Map<String, ModManifest> installed = new HashMap<>();
            if (project != null) for (ModManifest m : ModLoader.discoverInstalled(project)) installed.put(m.id, m);
            installed.put(mod.id, mod);
            for (ModManifest.Dependency d : mod.dependencies) {
                if (!d.optional() && !installed.containsKey(d.modId())) {
                    throw new IOException("required dependency '" + d.modId() + "' is not installed in this project's Mods/ folder, "
                            + "so the mod cannot be validated");
                }
            }
            for (ModManifest m : ModLoader.resolveLoadOrder(new ArrayList<>(installed.values()))) {
                if (m.id.equals(mod.id)) break;
                if (!isDependency(mod, m.id, installed)) continue;
                for (String src : m.scriptSources) chain = ModLoader.compile(m, src, chain);
            }
        }

        for (String src : mod.scriptSources) {
            try {
                chain = ModLoader.compile(mod, src, chain);
            } catch (Exception e) {
                throw new IOException(src + ": " + e.getMessage());
            }
        }
        if (!mod.entryPoint.isEmpty()) {
            Class<?> entry;
            try {
                entry = Class.forName(mod.entryPoint, false, chain);
            } catch (ClassNotFoundException e) {
                throw new IOException("entry_point class " + mod.entryPoint + " is not defined in script_sources");
            }
            if (!ModScript.class.isAssignableFrom(entry)) throw new IOException("entry_point " + mod.entryPoint + " does not extend ModScript");
        }

        // Every other .java file is an EntityScript compiled on demand at runtime: catch syntax errors now
        Set<Path> sources = new java.util.HashSet<>();
        for (String src : mod.scriptSources) sources.add(mod.resolve(src).toPath());
        try (Stream<Path> walk = Files.walk(mod.root.toPath())) {
            for (Path p : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".java"))::iterator) {
                if (sources.contains(p.toFile().getCanonicalFile().toPath()) || isHidden(mod.root.toPath(), p)) continue;
                SimpleCompiler compiler = new SimpleCompiler();
                compiler.setParentClassLoader(chain);
                try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    compiler.cook(p.toString(), r);
                } catch (Exception e) {
                    throw new IOException(mod.root.toPath().relativize(p) + ": " + e.getMessage());
                }
            }
        }
    }

    private static boolean isDependency(ModManifest mod, String candidate, Map<String, ModManifest> installed) {
        java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ModManifest.Dependency d : mod.dependencies) todo.add(d.modId());
        while (!todo.isEmpty()) {
            String id = todo.poll();
            if (!seen.add(id)) continue;
            if (id.equals(candidate)) return true;
            ModManifest m = installed.get(id);
            if (m != null) for (ModManifest.Dependency d : m.dependencies) todo.add(d.modId());
        }
        return false;
    }

    private static int validatePatches(ModManifest mod) throws IOException {
        int count = 0;
        for (String entry : mod.patches) {
            File f = mod.resolve(entry);
            if (!f.exists()) continue;
            try (Stream<Path> walk = Files.walk(f.toPath())) {
                for (Path p : (Iterable<Path>) walk.filter(x -> x.toString().toLowerCase(Locale.ROOT).endsWith(SceneDeltaPatcher.EXTENSION))::iterator) {
                    SceneDeltaPatcher.parse(p.toFile(), mod.id);
                    count++;
                }
            }
        }
        return count;
    }

    // ------------------------------------------------------------------------------------------
    // Staging and cooking
    // ------------------------------------------------------------------------------------------

    private static void copyMod(Path src, Path dst) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (isHidden(src, p)) continue;
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".tmp") || name.endsWith(".bak") || name.endsWith(".rpack")) continue;
                Path t = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static int cookSidecars(Path root) throws IOException {
        List<Path> raw = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(raw::add);
        }
        int cooked = 0;
        for (Path p : raw) {
            String name = p.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
            String out = p + ".rpack";
            boolean ok = false;
            if (MESH_EXT.contains(ext)) ok = RpackCooker.cookMeshToRpack(p.toString(), out);
            else if (TEXTURE_EXT.contains(ext)) ok = RpackCooker.cookTextureToRpack(p.toString(), out);
            if (ok) cooked++;
        }
        return cooked;
    }

    private static boolean isHidden(Path root, Path p) {
        for (Path part : root.relativize(p)) {
            if (part.toString().startsWith(".")) return true;
        }
        return false;
    }

    private static Map<String, Object> canonicalManifest(ModManifest m) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("mod_id", m.id);
        json.put("name", m.name);
        json.put("version", m.version);
        json.put("author", m.author);
        if (!m.targetEngine.isEmpty()) json.put("target_engine", m.targetEngine);
        json.put("load_priority", m.loadPriority);
        List<Object> deps = new ArrayList<>();
        for (ModManifest.Dependency d : m.dependencies) {
            if (d.minVersion().isEmpty() && !d.optional()) {
                deps.add(d.modId());
            } else {
                Map<String, Object> dm = new LinkedHashMap<>();
                dm.put("mod_id", d.modId());
                if (!d.minVersion().isEmpty()) dm.put("version", d.minVersion());
                if (d.optional()) dm.put("optional", true);
                deps.add(dm);
            }
        }
        json.put("dependencies", deps);
        if (!m.entryPoint.isEmpty()) json.put("entry_point", m.entryPoint);
        json.put("script_sources", m.scriptSources);
        json.put("asset_overlays", m.assetOverlays);
        json.put("patches", m.patches);
        json.put("shaders", m.shaders);
        return json;
    }
}
