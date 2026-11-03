package com.retro.engine.modding;

import com.retro.engine.ecs.ComponentSchema;
import com.retro.engine.ecs.Entity;
import com.retro.engine.modding.events.EntitySpawnEvent;
import com.retro.engine.modding.events.ModsLoadedEvent;
import com.retro.engine.modding.events.SceneLoadEvent;
import com.retro.engine.scripting.EntityScript;
import com.retro.engine.scripting.ScriptEngine;

import org.codehaus.janino.SimpleCompiler;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Discovers, orders, compiles and initializes the mods of one project, and owns the hooks the engine calls into.
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li>{@link #discoverMods}: every {@code <project>/Mods/<Folder>/mod.json} is parsed into a {@link ModManifest}.</li>
 *   <li>{@link #resolveLoadOrder}: topological sort over {@code dependencies}, ties broken by ascending
 *       {@code load_priority} then mod_id. Missing / too-old required dependencies and cycles disable the
 *       affected mods (and their dependents) with a console diagnostic.</li>
 *   <li>Janino build: each mod's {@code script_sources} compile in order on one class loader chain spanning every
 *       mod in load order, so a mod sees its dependencies' classes. EntityScripts compiled by ScriptEngine use the
 *       end of that chain as their parent, so they can reference mod components.</li>
 *   <li>VFS mount ({@link ModVirtualFileSystem}): each mod's {@code asset_overlays}, later mods on top.</li>
 *   <li>Each {@code entry_point} {@link ModScript} runs {@code onInitialize}; then {@link ModsLoadedEvent}.</li>
 *   <li>{@code .rpatch} files under each mod's {@code patches} paths are parsed and indexed by target scene.</li>
 * </ol>
 * A mod that fails to compile or initialize is unloaded (its handlers and components are removed) along with every
 * mod that requires it; the rest keep loading.
 *
 * <h2>Where mods are active</h2>
 * Standalone runtime builds always load {@code Game_Data/Mods/} (unless started with {@code --no-mods}). The editor
 * loads a project's {@code Mods/} only when started with {@code --mods}, and then refuses to save scenes so patched
 * content is never baked into vanilla {@code .rscene} files.
 */
public final class ModLoader {

    public static final String MODS_DIR = "Mods";

    private static volatile ModLoader active;
    private static volatile boolean editorModsEnabled = false;
    private static volatile boolean runtimeModsEnabled = true;

    private final File projectDir;
    private final ModEventBus eventBus = new ModEventBus();
    private final List<ModManifest> loaded = new ArrayList<>();
    private final Map<String, ModScript> entryPoints = new LinkedHashMap<>();
    private final Map<String, List<SceneDeltaPatcher.Patch>> patchesByScene = new HashMap<>();
    private ModVirtualFileSystem vfs;
    private ClassLoader scriptClassLoader = EntityScript.class.getClassLoader();
    private SceneDeltaPatcher patcher;

    // Reused across posts: scene loads and prefab spawns happen on the main thread
    private final EntitySpawnEvent spawnEvent = new EntitySpawnEvent();
    private final SceneLoadEvent sceneLoadEvent = new SceneLoadEvent();

    private ModLoader(File projectDir) {
        this.projectDir = projectDir.getAbsoluteFile();
        this.vfs = ModVirtualFileSystem.empty(new File(this.projectDir, "Assets"));
        this.patcher = new SceneDeltaPatcher(vfs);
    }

    // ------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------

    public static void setEditorModsEnabled(boolean enabled) { editorModsEnabled = enabled; }
    public static boolean isEditorModsEnabled() { return editorModsEnabled; }
    public static void setRuntimeModsEnabled(boolean enabled) { runtimeModsEnabled = enabled; }
    public static boolean isRuntimeModsEnabled() { return runtimeModsEnabled; }

    /** The loaded mod set, or null when no mods are active. */
    public static ModLoader active() { return active; }

    /** True when at least one mod is loaded. */
    public static boolean isActive() {
        ModLoader l = active;
        return l != null && !l.loaded.isEmpty();
    }

    // Mod folders mounted on top of <project>/Mods/ (AssetDatabase.mountModVFS); kept across reloads of one project
    private static final List<File> extraModRoots = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile File extraRootsProject;
    // Creation Kit mode: load only this mod and its dependencies (null = every installed mod)
    private static volatile String workspaceModId;

    /** Restricts loading to one mod and its transitive dependencies (Creation Kit mode); null loads every mod. */
    public static void setWorkspaceModFilter(String modId) { workspaceModId = modId; }
    public static String getWorkspaceModFilter() { return workspaceModId; }

    /**
     * Unloads any previous mod set, then loads {@code <projectDir>/Mods/}: mod folders, {@code .rmod} packages
     * (extracted to a per-user cache, see {@link ModArchive#cacheDirFor}) and any folders mounted with {@link #mountModFolder}. Leaves no loader active
     * when nothing loads. Never throws: problems are reported per mod on the console.
     */
    public static synchronized ModLoader loadForProject(File projectDir) {
        unloadActive();
        File project = projectDir.getAbsoluteFile();
        if (!project.equals(extraRootsProject)) {
            extraModRoots.clear();
            extraRootsProject = project;
        }

        ModLoader loader = new ModLoader(project);
        List<ModManifest> discovered = loader.discoverMods(new File(project, MODS_DIR));
        discovered = filterToWorkspace(discovered);
        if (discovered.isEmpty()) return null;
        loader.load(discovered);
        if (loader.loaded.isEmpty()) {
            loader.shutdown();
            return null;
        }
        active = loader;
        // Cached EntityScript classes were compiled against the previous parent loader
        ScriptEngine.clearCache();
        ModShaders.apply(loader);
        return loader;
    }

    /**
     * Mounts an extra mod folder (a folder with mod.json, or a folder of mod folders) for {@code projectDir} and
     * reloads the mod set so it joins the VFS stack in load order. Backs {@code AssetDatabase.mountModVFS}.
     */
    public static synchronized ModLoader mountModFolder(File folder, File projectDir) {
        File project = projectDir.getAbsoluteFile();
        if (!project.equals(extraRootsProject)) {
            extraModRoots.clear();
            extraRootsProject = project;
        }
        File abs = folder.getAbsoluteFile();
        if (!extraModRoots.contains(abs)) extraModRoots.add(abs);
        return loadForProject(project);
    }

    private static List<ModManifest> filterToWorkspace(List<ModManifest> mods) {
        String target = workspaceModId;
        if (target == null) return mods;
        Map<String, ModManifest> byId = new HashMap<>();
        for (ModManifest m : mods) byId.put(m.id, m);
        Set<String> keep = new HashSet<>();
        java.util.ArrayDeque<String> todo = new java.util.ArrayDeque<>(List.of(target));
        while (!todo.isEmpty()) {
            String id = todo.poll();
            ModManifest m = byId.get(id);
            if (m == null || !keep.add(id)) continue;
            for (ModManifest.Dependency d : m.dependencies) todo.add(d.modId());
        }
        List<ModManifest> out = new ArrayList<>();
        for (ModManifest m : mods) if (keep.contains(m.id)) out.add(m);
        return out;
    }

    /** Runs every onShutdown, drops mod components and handlers, and restores the vanilla script class loader. */
    public static synchronized void unloadActive() {
        ModLoader l = active;
        active = null;
        if (l != null) {
            l.shutdown();
            ScriptEngine.clearCache();
            ModShaders.revert();
            System.out.println("[ModLoader] Unloaded " + l.loaded.size() + " mod(s).");
        }
    }

    private void shutdown() {
        List<String> ids = new ArrayList<>(entryPoints.keySet());
        Collections.reverse(ids);
        for (String id : ids) {
            ModScript script = entryPoints.get(id);
            try {
                script.onModDisable();
            } catch (Throwable t) {
                System.err.println("[ModLoader] " + id + " onModDisable failed: " + t);
            }
            try {
                script.onShutdown();
            } catch (Throwable t) {
                System.err.println("[ModLoader] " + id + " onShutdown failed: " + t);
            }
        }
        entryPoints.clear();
        eventBus.clear();
        ComponentSchema.unregisterModComponents();
    }

    // ------------------------------------------------------------------------------------------
    // 1. Discovery
    // ------------------------------------------------------------------------------------------

    /**
     * Parses every mod in {@code modsDir} (folders with mod.json in name order, then {@code .rmod} packages) followed by
     * mounted extra folders. Folders starting with '.' are skipped. Duplicate mod_ids keep the first one found.
     */
    public List<ModManifest> discoverMods(File modsDir) {
        List<File> roots = new ArrayList<>();
        File[] entries = modsDir.isDirectory() ? modsDir.listFiles() : null;
        if (entries != null) {
            java.util.Arrays.sort(entries, Comparator.comparing(File::getName));
            for (File f : entries) {
                if (f.getName().startsWith(".")) continue;
                if (f.isDirectory() && new File(f, ModManifest.FILE_NAME).isFile()) roots.add(f);
            }
            for (File f : entries) {
                if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(ModArchive.EXTENSION)) {
                    try {
                        roots.add(ModArchive.extractCached(f, new File(ModArchive.cacheDirFor(modsDir), "rmod")));
                    } catch (IOException e) {
                        System.err.println("[ModLoader] Skipping " + f.getName() + ": " + e.getMessage());
                    }
                }
            }
        }
        for (File extra : extraModRoots) {
            if (new File(extra, ModManifest.FILE_NAME).isFile()) {
                roots.add(extra);
            } else {
                File[] sub = extra.listFiles(f -> f.isDirectory() && !f.getName().startsWith(".") && new File(f, ModManifest.FILE_NAME).isFile());
                if (sub != null) {
                    java.util.Arrays.sort(sub, Comparator.comparing(File::getName));
                    roots.addAll(List.of(sub));
                }
            }
        }

        List<ModManifest> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (File folder : roots) {
            try {
                ModManifest m = ModManifest.read(folder);
                if (!ids.add(m.id)) {
                    System.err.println("[ModLoader] Skipping " + folder.getName() + ": mod_id '" + m.id + "' is already used by another mod.");
                    continue;
                }
                if (!m.targetEngine.isEmpty() && ModManifest.compareVersions(m.targetEngine, com.retro.engine.core.ProjectWorkspace.ENGINE_VERSION) != 0) {
                    System.out.println("[ModLoader] " + m + " targets engine " + m.targetEngine + " (running "
                            + com.retro.engine.core.ProjectWorkspace.ENGINE_VERSION + "); loading anyway.");
                }
                out.add(m);
            } catch (IOException e) {
                System.err.println("[ModLoader] Skipping " + folder.getName() + ": " + e.getMessage());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // 2. Load order
    // ------------------------------------------------------------------------------------------

    /**
     * Dependency-respecting order (dependencies first). Among mods whose dependencies are satisfied, lower
     * {@code load_priority} loads first, so higher-priority mods sit higher in the VFS and patch later.
     */
    public static List<ModManifest> resolveLoadOrder(List<ModManifest> mods) {
        Map<String, ModManifest> byId = new LinkedHashMap<>();
        for (ModManifest m : mods) byId.put(m.id, m);

        // Drop mods whose required dependencies are missing or too old; repeat until stable (removals cascade)
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ModManifest m : new ArrayList<>(byId.values())) {
                String problem = dependencyProblem(m, byId);
                if (problem != null) {
                    System.err.println("[ModLoader] Disabling " + m + ": " + problem);
                    byId.remove(m.id);
                    changed = true;
                }
            }
        }

        // Kahn's algorithm; only dependencies that are present (required, or optional and installed) are edges
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (ModManifest m : byId.values()) {
            indegree.putIfAbsent(m.id, 0);
            for (ModManifest.Dependency d : m.dependencies) {
                if (!byId.containsKey(d.modId())) continue;
                indegree.merge(m.id, 1, Integer::sum);
                dependents.computeIfAbsent(d.modId(), k -> new ArrayList<>()).add(m.id);
            }
        }
        PriorityQueue<ModManifest> ready = new PriorityQueue<>(
                Comparator.comparingInt((ModManifest m) -> m.loadPriority).thenComparing(m -> m.id));
        for (ModManifest m : byId.values()) {
            if (indegree.get(m.id) == 0) ready.add(m);
        }
        List<ModManifest> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            ModManifest m = ready.poll();
            order.add(m);
            for (String dep : dependents.getOrDefault(m.id, List.of())) {
                if (indegree.merge(dep, -1, Integer::sum) == 0) ready.add(byId.get(dep));
            }
        }
        if (order.size() < byId.size()) {
            for (ModManifest m : byId.values()) {
                if (!order.contains(m)) System.err.println("[ModLoader] Disabling " + m + ": dependency cycle");
            }
        }
        return order;
    }

    private static String dependencyProblem(ModManifest m, Map<String, ModManifest> available) {
        for (ModManifest.Dependency d : m.dependencies) {
            if (d.modId().equals(m.id)) return "depends on itself";
            ModManifest dep = available.get(d.modId());
            if (dep == null) {
                if (!d.optional()) return "missing required dependency '" + d.modId() + "'";
                continue;
            }
            if (!d.minVersion().isEmpty() && ModManifest.compareVersions(dep.version, d.minVersion()) < 0) {
                if (!d.optional()) return "requires " + d.modId() + " >= " + d.minVersion() + " (found " + dep.version + ")";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // 3-6. Build, mount, initialize, index patches
    // ------------------------------------------------------------------------------------------

    private void load(List<ModManifest> discovered) {
        List<ModManifest> order = resolveLoadOrder(discovered);
        Set<String> failed = new HashSet<>();

        // Janino build along one class loader chain
        ClassLoader chain = EntityScript.class.getClassLoader();
        Map<String, Class<?>> entryClasses = new HashMap<>();
        List<ModManifest> compiled = new ArrayList<>();
        for (ModManifest m : order) {
            String blocked = failedRequiredDependency(m, failed);
            if (blocked != null) {
                failed.add(m.id);
                System.err.println("[ModLoader] Disabling " + m + ": required dependency '" + blocked + "' failed to load");
                continue;
            }
            try {
                ClassLoader next = chain;
                for (String src : m.scriptSources) next = compile(m, src, next);
                if (!m.entryPoint.isEmpty()) {
                    Class<?> entry = Class.forName(m.entryPoint, true, next);
                    if (!ModScript.class.isAssignableFrom(entry)) {
                        throw new IOException("entry_point " + m.entryPoint + " does not extend " + ModScript.class.getName());
                    }
                    entryClasses.put(m.id, entry);
                }
                chain = next;
                compiled.add(m);
            } catch (Exception e) {
                failed.add(m.id);
                System.err.println("[ModLoader] Disabling " + m + ": script build failed: " + e.getMessage());
            }
        }

        // Mount so onInitialize can already resolve mod:// URIs and overlays
        rebuildVfs(compiled);

        for (ModManifest m : compiled) {
            String blocked = failedRequiredDependency(m, failed);
            if (blocked != null) {
                failed.add(m.id);
                System.err.println("[ModLoader] Disabling " + m + ": required dependency '" + blocked + "' failed to initialize");
                continue;
            }
            Class<?> entry = entryClasses.get(m.id);
            if (entry != null && !initialize(m, entry)) {
                failed.add(m.id);
                continue;
            }
            loaded.add(m);
        }
        if (loaded.size() != compiled.size()) rebuildVfs(loaded);
        scriptClassLoader = chain;

        for (ModManifest m : loaded) indexPatches(m);

        StringBuilder summary = new StringBuilder("[ModLoader] Loaded ").append(loaded.size()).append(" mod(s)");
        if (!failed.isEmpty()) summary.append(", ").append(failed.size()).append(" disabled");
        summary.append(": ");
        for (int i = 0; i < loaded.size(); i++) summary.append(i > 0 ? " -> " : "").append(loaded.get(i));
        System.out.println(summary);

        for (Map.Entry<String, ModScript> e : entryPoints.entrySet()) {
            try {
                e.getValue().onModEnable();
            } catch (Throwable t) {
                System.err.println("[ModLoader] " + e.getKey() + " onModEnable failed: " + t);
            }
        }
        if (!loaded.isEmpty()) eventBus.post(new ModsLoadedEvent(Collections.unmodifiableList(new ArrayList<>(loaded))));
    }

    private static String failedRequiredDependency(ModManifest m, Set<String> failed) {
        for (ModManifest.Dependency d : m.dependencies) {
            if (!d.optional() && failed.contains(d.modId())) return d.modId();
        }
        return null;
    }

    static ClassLoader compile(ModManifest m, String source, ClassLoader parent) throws Exception {
        File file = m.resolve(source);
        if (!file.isFile()) throw new IOException("script source not found: " + source);
        SimpleCompiler compiler = new SimpleCompiler();
        compiler.setParentClassLoader(parent);
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            compiler.cook(file.getAbsolutePath(), reader);
        }
        return compiler.getClassLoader();
    }

    private boolean initialize(ModManifest m, Class<?> entry) {
        ModContext ctx = new ModContext(m, eventBus, this);
        ModContext.setCurrent(ctx);
        try {
            ModScript script = (ModScript) entry.getDeclaredConstructor().newInstance();
            script.attach(ctx);
            script.onInitialize(ctx);
            entryPoints.put(m.id, script);
            return true;
        } catch (Throwable t) {
            Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
            System.err.println("[ModLoader] Disabling " + m + ": onInitialize failed: " + cause);
            cause.printStackTrace();
            eventBus.unregisterOwner(m.id);
            ComponentSchema.unregisterModComponents(m.id);
            return false;
        } finally {
            ModContext.setCurrent(null);
        }
    }

    private void rebuildVfs(List<ModManifest> mods) {
        List<ModVirtualFileSystem.Layer> layers = new ArrayList<>();
        Map<String, File> roots = new LinkedHashMap<>();
        for (ModManifest m : mods) {
            roots.put(m.id, m.root);
            for (String overlay : m.assetOverlays) {
                try {
                    File dir = m.resolve(overlay);
                    if (dir.isDirectory()) layers.add(new ModVirtualFileSystem.Layer(m.id, m.loadPriority, dir));
                } catch (IOException e) {
                    System.err.println("[ModLoader] " + m + ": ignoring asset overlay '" + overlay + "': " + e.getMessage());
                }
            }
        }
        vfs = new ModVirtualFileSystem(new File(projectDir, "Assets"), layers, roots);
        patcher = new SceneDeltaPatcher(vfs);
    }

    private void indexPatches(ModManifest m) {
        for (String entry : m.patches) {
            File f;
            try {
                f = m.resolve(entry);
            } catch (IOException e) {
                System.err.println("[ModLoader] " + m + ": ignoring patch path '" + entry + "': " + e.getMessage());
                continue;
            }
            List<Path> files = new ArrayList<>();
            if (f.isFile()) {
                files.add(f.toPath());
            } else if (f.isDirectory()) {
                try (Stream<Path> walk = Files.walk(f.toPath())) {
                    walk.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(SceneDeltaPatcher.EXTENSION))
                        .sorted()
                        .forEach(files::add);
                } catch (IOException e) {
                    System.err.println("[ModLoader] " + m + ": could not scan " + entry + ": " + e.getMessage());
                }
            }
            // Creation Kit output (*.editor.rpatch) replays after the mod's hand-written patches it was diffed against
            files.sort(Comparator.comparing((Path p) -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ModWorkspace.EDITOR_PATCH_SUFFIX)));
            for (Path p : files) {
                try {
                    SceneDeltaPatcher.Patch patch = SceneDeltaPatcher.parse(p.toFile(), m.id);
                    patchesByScene.computeIfAbsent(patch.targetScene().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(patch);
                } catch (IOException e) {
                    System.err.println("[ModLoader] " + m + ": skipping patch: " + e.getMessage());
                }
            }
        }
    }

    /** Re-reads every loaded mod's .rpatch files (after Creation Kit mode wrote one). */
    synchronized void reindexPatches() {
        patchesByScene.clear();
        for (ModManifest m : loaded) indexPatches(m);
    }

    /**
     * Applies the scene's patches to {@code entities} in load order, skipping {@code excluded} (Creation Kit mode
     * diffs against this baseline). No events are posted.
     */
    void applyPatches(String scenePath, List<Entity> entities, File excluded) {
        Path skip = (excluded != null) ? excluded.getAbsoluteFile().toPath().normalize() : null;
        for (SceneDeltaPatcher.Patch patch : patchesFor(scenePath)) {
            if (skip != null && patch.source().getAbsoluteFile().toPath().normalize().equals(skip)) continue;
            patcher.apply(patch, entities);
        }
    }

    /** Manifests of every mod installed for {@code projectDir} (no compilation, no load-order filtering). */
    public static List<ModManifest> discoverInstalled(File projectDir) {
        File project = projectDir.getAbsoluteFile();
        return new ModLoader(project).discoverMods(new File(project, MODS_DIR));
    }

    // ------------------------------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------------------------------

    public ModEventBus eventBus() { return eventBus; }
    public ModVirtualFileSystem vfs() { return vfs; }
    public List<ModManifest> loadedMods() { return Collections.unmodifiableList(loaded); }
    public File projectDir() { return projectDir; }

    /** Parsed patches targeting {@code scenePath} (project-relative or absolute), in application order. */
    public List<SceneDeltaPatcher.Patch> patchesFor(String scenePath) {
        String key = projectRelativeScene(scenePath);
        return key == null ? List.of() : patchesByScene.getOrDefault(key.toLowerCase(Locale.ROOT), List.of());
    }

    String projectRelativeScene(String scenePath) {
        if (scenePath == null || scenePath.isBlank()) return null;
        File f = new File(scenePath);
        if (f.isAbsolute()) {
            Path abs = f.toPath().normalize();
            Path root = projectDir.toPath().normalize();
            if (!abs.startsWith(root)) return null;
            return SceneDeltaPatcher.normalizeScenePath(root.relativize(abs).toString());
        }
        return SceneDeltaPatcher.normalizeScenePath(scenePath);
    }

    // ------------------------------------------------------------------------------------------
    // Engine hooks (static, no-ops when no mods are active)
    // ------------------------------------------------------------------------------------------

    /** VFS override for an asset path read from a scene or prefab; returns {@code path} when nothing overrides it. */
    public static String resolveAssetOverride(String path) {
        ModLoader l = active;
        return (l == null) ? path : l.vfs.resolveOverride(path);
    }

    /**
     * Full resolution for asset paths authored by mods (patches, scripts, RenderPassEvent draws): {@code mod://} URIs,
     * then overlay copies, then the base project Assets/ folder for {@code Assets/...} or Assets-relative paths.
     * Absolute or unresolvable paths are returned unchanged.
     */
    public static String resolveAssetPath(String path) {
        ModLoader l = active;
        if (l == null || path == null) return path;
        String s = path.trim();
        String resolved = l.vfs.resolveOverride(s);
        if (!resolved.equals(s) || new File(s).isAbsolute() || s.startsWith(ModVirtualFileSystem.MOD_SCHEME)) return resolved;
        String rel = s.replace('\\', '/');
        if (rel.startsWith("Assets/")) rel = rel.substring("Assets/".length());
        File base = new File(l.vfs.baseAssetsDir(), rel);
        return base.isFile() ? base.getAbsolutePath() : s;
    }

    // Reused event instances for per-frame / per-contact hooks (main thread only)
    private final com.retro.engine.modding.events.RenderPassEvent renderPassEvent = new com.retro.engine.modding.events.RenderPassEvent();
    private final com.retro.engine.modding.events.CollisionEvent collisionEvent = new com.retro.engine.modding.events.CollisionEvent();
    private final com.retro.engine.modding.events.TriggerEvent triggerEvent = new com.retro.engine.modding.events.TriggerEvent();

    /** SceneRenderPipeline, once per frame before sorting. Allocation-free; a no-op without subscribers. */
    public static void onRenderPass(com.retro.engine.graphics.SceneRenderPipeline pipeline, com.retro.engine.assets.AssetStreamer streamer,
                                    com.retro.engine.graphics.RenderQueue queue, com.retro.engine.graphics.SceneLights lights,
                                    com.retro.engine.graphics.RenderEnvironment environment, boolean standalone) {
        ModLoader l = active;
        if (l == null || !l.eventBus.hasSubscribers(com.retro.engine.modding.events.RenderPassEvent.class)) return;
        com.retro.engine.modding.events.RenderPassEvent e = l.renderPassEvent;
        e.queue = queue;
        e.lights = lights;
        e.environment = environment;
        e.standalone = standalone;
        e.bind(pipeline, streamer);
        l.eventBus.post(e);
        e.bind(null, null);
        e.queue = null;
        e.lights = null;
        e.environment = null;
    }

    /**
     * NarrowphaseSolver, per solid contact before separation. Returns the restitution to apply, or NaN when a mod
     * canceled the contact (the solver then skips separation and contact recording). Allocation-free.
     */
    public static float onCollision(Entity a, Entity b, float normalX, float normalY, float normalZ, float penetration, float restitution) {
        ModLoader l = active;
        if (l == null || !l.eventBus.hasSubscribers(com.retro.engine.modding.events.CollisionEvent.class)) return restitution;
        com.retro.engine.modding.events.CollisionEvent e = l.collisionEvent;
        e.entityA = a;
        e.entityB = b;
        e.normalX = normalX; e.normalY = normalY; e.normalZ = normalZ;
        e.penetration = penetration;
        e.restitution = restitution;
        boolean canceled = l.eventBus.post(e);
        e.entityA = e.entityB = null;
        return canceled ? Float.NaN : Math.max(0.0f, e.restitution);
    }

    /** PhysicsSystem, once per trigger pair and phase. Allocation-free; a no-op without subscribers. */
    public static void onTrigger(com.retro.engine.modding.events.TriggerEvent.Phase phase, Entity a, Entity b) {
        ModLoader l = active;
        if (l == null || !l.eventBus.hasSubscribers(com.retro.engine.modding.events.TriggerEvent.class)) return;
        boolean triggerFirst = hasTriggerCollider(a) || !hasTriggerCollider(b);
        com.retro.engine.modding.events.TriggerEvent e = l.triggerEvent;
        e.phase = phase;
        e.trigger = triggerFirst ? a : b;
        e.other = triggerFirst ? b : a;
        l.eventBus.post(e);
        e.trigger = e.other = null;
    }

    private static boolean hasTriggerCollider(Entity e) {
        com.retro.engine.ecs.components.BoxCollider box = e.getComponent(com.retro.engine.ecs.components.BoxCollider.class);
        if (box != null && box.isTrigger) return true;
        com.retro.engine.ecs.components.SphereCollider sphere = e.getComponent(com.retro.engine.ecs.components.SphereCollider.class);
        if (sphere != null && sphere.isTrigger) return true;
        com.retro.engine.ecs.components.CapsuleCollider capsule = e.getComponent(com.retro.engine.ecs.components.CapsuleCollider.class);
        return capsule != null && capsule.isTrigger;
    }

    /** Parent class loader for EntityScript compilation: the mod chain while mods are loaded. */
    public static ClassLoader scriptParentClassLoader() {
        ModLoader l = active;
        return (l != null) ? l.scriptClassLoader : EntityScript.class.getClassLoader();
    }

    /**
     * Called by SceneWorld right after a scene file is deserialized: applies its .rpatch deltas in load order, posts
     * {@link SceneLoadEvent}, then one {@link EntitySpawnEvent} per entity (canceled entities are removed with their
     * descendants). Mutates {@code entities} in place. Returns the number of patches applied.
     */
    public static int onSceneDeserialized(String scenePath, List<Entity> entities) {
        ModLoader l = active;
        if (l == null || entities == null) return 0;

        List<SceneDeltaPatcher.Patch> patches = l.patchesFor(scenePath);
        for (SceneDeltaPatcher.Patch patch : patches) {
            SceneDeltaPatcher.Result r = l.patcher.apply(patch, entities);
            System.out.println("[ModLoader] Applied " + patch.modId() + "/" + patch.source().getName() + " to " + patch.targetScene()
                    + " (" + r.applied() + " ops" + (r.failed() > 0 ? ", " + r.failed() + " failed" : "") + ")");
        }

        // Creation Kit mode shows pure patch content: mod scripts do not get to rewrite the scene being authored
        if (workspaceModId != null) return patches.size();

        SceneLoadEvent sle = l.sceneLoadEvent;
        sle.scenePath = l.projectRelativeScene(scenePath);
        sle.entities = entities;
        sle.patchesApplied = patches.size();
        l.eventBus.post(sle);
        sle.entities = null;

        l.postSpawnEvents(entities, EntitySpawnEvent.Source.SCENE_LOAD, sle.scenePath, null);
        return patches.size();
    }

    /**
     * Called by SceneWorld after a prefab's entities joined the scene. Canceled entities are passed to
     * {@code remover} (SceneWorld::deleteEntity) and dropped from {@code spawned}.
     */
    public static void onPrefabSpawned(String prefabPath, List<Entity> spawned, java.util.function.Consumer<Entity> remover) {
        ModLoader l = active;
        if (l == null || spawned == null || spawned.isEmpty()) return;
        l.postSpawnEvents(spawned, EntitySpawnEvent.Source.PREFAB, prefabPath, remover);
    }

    /** SceneWorld.spawnEntity: posts EntitySpawnEvent (source SCRIPT); a canceled entity is removed from {@code spawned}. */
    public static void onScriptSpawned(List<Entity> spawned) {
        ModLoader l = active;
        if (l == null || spawned == null || spawned.isEmpty()) return;
        l.postSpawnEvents(spawned, EntitySpawnEvent.Source.SCRIPT, null, null);
    }

    private void postSpawnEvents(List<Entity> entities, EntitySpawnEvent.Source source, String origin, java.util.function.Consumer<Entity> remover) {
        if (workspaceModId != null || !eventBus.hasSubscribers(EntitySpawnEvent.class)) return;
        List<Entity> canceled = null;
        EntitySpawnEvent e = spawnEvent;
        // Snapshot size: subscribers may append entities, which are not re-announced
        int count = entities.size();
        for (int i = 0; i < count && i < entities.size(); i++) {
            e.entity = entities.get(i);
            e.source = source;
            e.origin = origin;
            if (eventBus.post(e)) {
                if (canceled == null) canceled = new ArrayList<>();
                canceled.add(e.entity);
            }
        }
        e.entity = null;
        if (canceled == null) return;

        Set<String> doomed = new HashSet<>();
        for (Entity c : canceled) collectSubtree(c, entities, doomed);
        if (remover != null) {
            for (Entity c : canceled) remover.accept(c);
            entities.removeIf(x -> x.id != null && doomed.contains(x.id));
        } else {
            for (Entity c : canceled) {
                if (c.parentId == null || c.parentId.isEmpty()) continue;
                for (Entity p : entities) {
                    if (c.parentId.equalsIgnoreCase(p.id)) p.removeChildId(c.id);
                }
            }
            entities.removeIf(x -> x.id != null && doomed.contains(x.id));
        }
    }

    private static void collectSubtree(Entity root, List<Entity> entities, Set<String> out) {
        if (root.id == null || !out.add(root.id)) return;
        for (Entity e : entities) {
            if (root.id.equals(e.parentId)) collectSubtree(e, entities, out);
        }
    }
}
