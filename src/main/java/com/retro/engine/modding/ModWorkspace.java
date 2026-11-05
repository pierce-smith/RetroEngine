package com.retro.engine.modding;

import com.retro.engine.assets.AssetDatabase;
import com.retro.engine.core.ProjectWorkspace;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.serialization.SceneSerializer;
import com.retro.engine.editor.EditorWorkspace;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Creation Kit mode: the editor opens a vanilla project with one mod (plus its dependencies) loaded as the active
 * authoring target. Vanilla scene files are read-only: saving the open vanilla scene writes the difference between
 * "vanilla + every other patch" and what is on screen to {@code <mod>/Patches/<Scene>.editor.rpatch} instead.
 * Scenes saved inside the mod folder are ordinary mod-owned .rscene files.
 *
 * <p>Scene / spawn events are not posted while authoring, so what is on screen (and saved) is pure patch content
 * rather than the effect of mod scripts.
 */
public final class ModWorkspace {

    public static final String EDITOR_PATCH_SUFFIX = ".editor.rpatch";

    private static volatile ModManifest activeMod;
    private static volatile File projectDir;

    private ModWorkspace() {}

    public static boolean isActive() { return activeMod != null; }
    public static ModManifest activeMod() { return activeMod; }

    /** The project a mod folder belongs to: {@code <Project>/Mods/<ModFolder>}, or null for any other layout. */
    public static File projectOf(File modFolder) {
        File mods = modFolder.getAbsoluteFile().getParentFile();
        if (mods == null || !ModLoader.MODS_DIR.equals(mods.getName())) return null;
        File project = mods.getParentFile();
        return (project != null && ProjectWorkspace.isProjectDirectory(project)) ? project : null;
    }

    /** Opens {@code modFolder}'s project in Creation Kit mode with that mod as the authoring target. */
    public static void open(EditorWorkspace workspace, File modFolder) throws IOException {
        File folder = modFolder.getAbsoluteFile();
        File project = projectOf(folder);
        if (project == null) throw new IOException("A mod workspace must be a folder inside <Project>/Mods/: " + folder);
        ModManifest manifest = ModManifest.read(folder);

        ModLoader.setWorkspaceModFilter(manifest.id);
        ModLoader.setEditorModsEnabled(true);
        activeMod = manifest;
        projectDir = project;
        try {
            ProjectWorkspace.openProject(workspace, project);
        } catch (IOException e) {
            close();
            throw e;
        }
        ModLoader loader = ModLoader.active();
        if (loader == null || loader.loadedMods().stream().noneMatch(m -> m.id.equals(manifest.id))) {
            System.err.println("[ModWorkspace] '" + manifest.id + "' did not load (see errors above); scene saves stay disabled.");
        }
        System.out.println("[ModWorkspace] Creation Kit mode: authoring " + manifest + " on " + project.getName()
                + ". Saving the vanilla scene writes an .rpatch delta into " + folder.getName() + "/.");
    }

    /** Leaves Creation Kit mode and reopens the project with vanilla content only. */
    public static void close(EditorWorkspace workspace) throws IOException {
        File project = projectDir;
        close();
        if (workspace != null && project != null) ProjectWorkspace.openProject(workspace, project);
    }

    private static void close() {
        activeMod = null;
        projectDir = null;
        ModLoader.setWorkspaceModFilter(null);
        ModLoader.setEditorModsEnabled(false);
    }

    /** Called when the project is closed from the editor (switch project): drops the authoring target. */
    public static void onProjectClosed() {
        if (activeMod != null) close();
    }

    /** Writes a new mod skeleton (mod.json, Assets/, Patches/, Scripts/, Shaders/) into {@code folder}. */
    public static void createMod(File folder, String modId, String name) throws IOException {
        if (new File(folder, ModManifest.FILE_NAME).exists()) throw new IOException("mod.json already exists in " + folder);
        for (String sub : new String[] { "Assets", "Patches", "Scripts", "Shaders/vulkan", "Shaders/metal" }) {
            Files.createDirectories(new File(folder, sub).toPath());
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("mod_id", modId);
        json.put("name", (name != null && !name.isBlank()) ? name : modId);
        json.put("version", "1.0.0");
        json.put("author", System.getProperty("user.name", ""));
        json.put("target_engine", ProjectWorkspace.ENGINE_VERSION);
        json.put("load_priority", 100);
        json.put("dependencies", List.of());
        json.put("asset_overlays", List.of("Assets/"));
        json.put("patches", List.of("Patches/"));
        json.put("shaders", List.of("Shaders/"));
        Files.writeString(new File(folder, ModManifest.FILE_NAME).toPath(), MiniJson.write(json), StandardCharsets.UTF_8);
        ModManifest.read(folder); // validates the id
    }

    // ------------------------------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------------------------------

    /**
     * SceneWorld.saveActiveScene in Creation Kit mode. Returns true when it handled the save (wrote a patch or refused),
     * false when {@code targetPath} lies inside the authoring mod's folder and should be saved as a normal .rscene.
     */
    public static boolean saveScene(SceneWorld world, String targetPath, AssetDatabase assetDb) {
        ModManifest mod = activeMod;
        ModLoader loader = ModLoader.active();
        if (mod == null) return false;
        Path target = new File(targetPath).getAbsoluteFile().toPath().normalize();
        if (target.startsWith(mod.root.toPath().normalize())) return false;

        String active = world.getActiveScenePath();
        Path activePath = (active != null) ? new File(active).getAbsoluteFile().toPath().normalize() : null;
        if (loader == null || !target.equals(activePath) || !target.toFile().isFile()) {
            System.err.println("[ModWorkspace] Vanilla data is read-only in a mod workspace. Save the open vanilla scene to write its "
                    + ".rpatch delta, or save new scenes inside Mods/" + mod.root.getName() + "/.");
            return true;
        }
        try {
            File written = writeScenePatch(loader, mod, target.toFile(), world.getSceneEntities(), assetDb);
            System.out.println(written != null
                    ? "[ModWorkspace] Saved scene delta -> " + mod.root.getCanonicalFile().toPath().relativize(written.getCanonicalFile().toPath())
                    : "[ModWorkspace] No differences from the vanilla scene; nothing to save.");
        } catch (Exception e) {
            System.err.println("[ModWorkspace] Could not write the scene delta: " + e);
        }
        return true;
    }

    /**
     * Diffs {@code current} against the scene as it loads without this mod's editor patch, then writes (or, with no
     * differences, deletes) that patch. Returns the written file, or null when there was nothing to write.
     */
    static File writeScenePatch(ModLoader loader, ModManifest mod, File sceneFile, List<Entity> current, AssetDatabase assetDb) throws Exception {
        String relScene = loader.projectRelativeScene(sceneFile.getAbsolutePath());
        if (relScene == null) throw new IOException("scene is outside the project: " + sceneFile);
        File patchFile = editorPatchFile(mod, relScene);

        SceneSerializer.SceneLoadData data = SceneSerializer.loadSceneData(sceneFile.getAbsolutePath(), assetDb);
        // A delta against an unreadable baseline would record the whole scene as mod content
        if (data.failed()) throw new IOException("the vanilla scene could not be read: " + data.error());
        List<Entity> baseline = data.entities();
        loader.applyPatches(sceneFile.getAbsolutePath(), baseline, patchFile);

        Map<String, Object> doc = new SceneDeltaWriter(mod, loader.vfs()).diff(relScene, baseline, current);
        List<?> ops = (List<?>) doc.get("operations");
        if (ops.isEmpty()) {
            Files.deleteIfExists(patchFile.toPath());
            loader.reindexPatches();
            return null;
        }
        Files.createDirectories(patchFile.getParentFile().toPath());
        Path temp = patchFile.toPath().resolveSibling(patchFile.getName() + ".tmp");
        Files.writeString(temp, MiniJson.write(doc), StandardCharsets.UTF_8);
        Files.move(temp, patchFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        loader.reindexPatches();
        return patchFile;
    }

    /** {@code <mod>/<first patches entry>/<Scenes_Main>.editor.rpatch} for {@code Assets/Scenes/Main.rscene}. */
    static File editorPatchFile(ModManifest mod, String relScene) throws IOException {
        String dir = mod.patches.isEmpty() ? "Patches/" : mod.patches.get(0);
        File patchesRoot = mod.resolve(dir);
        if (patchesRoot.getName().toLowerCase(Locale.ROOT).endsWith(SceneDeltaPatcher.EXTENSION)) patchesRoot = patchesRoot.getParentFile();
        String stem = relScene.startsWith("Assets/") ? relScene.substring("Assets/".length()) : relScene;
        if (stem.toLowerCase(Locale.ROOT).endsWith(".rscene")) stem = stem.substring(0, stem.length() - ".rscene".length());
        return new File(patchesRoot, stem.replace('/', '_') + EDITOR_PATCH_SUFFIX);
    }
}
