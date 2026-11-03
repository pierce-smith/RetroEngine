package com.retro.engine.modding;

import com.retro.engine.graphics.GraphicsAPI;
import com.retro.engine.graphics.ShaderSources;
import com.retro.engine.graphics.vulkan.VulkanShaderCompiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Mod shader overrides. Each mod's {@code shaders} roots (default {@code Shaders/}) mirror the engine's
 * {@code /shaders/} folder:
 * <pre>
 *   Shaders/vulkan/blinn_phong.frag      GLSL source; compiled to SPIR-V with glslc on a background thread
 *   Shaders/vulkan/blinn_phong.frag.spv  or ship the SPIR-V directly (used when newer than the source)
 *   Shaders/metal/blinn_phong.metal      MSL source; compiled by the Metal driver when pipelines rebuild
 * </pre>
 * Higher-priority mods win per file. After compilation the active backend rebuilds its pipelines at the next frame
 * boundary (the editor's shader hot-reload path). If a mod shader fails to compile, or the rebuilt pipeline is
 * rejected, the backend keeps or falls back to the engine's built-in shader ({@link ShaderSources#readBuiltin}).
 * Overrides must keep the engine's vertex layout, uniform / push-constant blocks and entry points.
 */
public final class ModShaders {

    private static volatile GraphicsAPI backend = GraphicsAPI.UNKNOWN;
    private static volatile Consumer<List<String>> reloadSink;
    // Shader resource names (e.g. "blinn_phong.frag.spv") the current overlays replace, for reverting on unload
    private static volatile List<String> overridden = List.of();

    private ModShaders() {}

    /** Called once by EngineContext after the render backend is up. */
    public static void bind(GraphicsAPI api, Consumer<List<String>> pipelineReloadRequest) {
        backend = (api != null) ? api : GraphicsAPI.UNKNOWN;
        reloadSink = pipelineReloadRequest;
    }

    /** Builds the overlay stack for {@code loader}'s mods and, off the main thread, compiles GLSL and requests a rebuild. */
    static void apply(ModLoader loader) {
        List<File> bottomUp = new ArrayList<>();
        List<File[]> glslJobs = new ArrayList<>(); // {source, target}
        Set<String> names = new LinkedHashSet<>();
        File cacheRoot = new File(ModArchive.cacheDirFor(new File(loader.projectDir(), ModLoader.MODS_DIR)), "shaders");

        for (ModManifest m : loader.loadedMods()) {
            File compiled = new File(cacheRoot, m.id);
            boolean any = false;
            for (String entry : m.shaders) {
                File root;
                try {
                    root = m.resolve(entry);
                } catch (IOException e) {
                    System.err.println("[ModShaders] " + m + ": ignoring shader root '" + entry + "': " + e.getMessage());
                    continue;
                }
                if (!root.isDirectory()) continue;
                any = true;
                bottomUp.add(root);
                collect(root, compiled, glslJobs, names);
            }
            // Compiled SPIR-V sits above the mod's own folder so a fresh compile beats a stale shipped .spv
            if (any) bottomUp.add(compiled);
        }
        // Empty Shaders/ folders (the mod template creates them) must not trigger a pipeline rebuild
        if (bottomUp.isEmpty() || names.isEmpty()) return;

        List<File> topDown = new ArrayList<>(bottomUp);
        java.util.Collections.reverse(topDown);
        List<String> changed = new ArrayList<>(names);
        overridden = changed;

        Thread.ofVirtual().name("mod-shader-build").start(() -> {
            if (backend == GraphicsAPI.VULKAN) {
                for (File[] job : glslJobs) {
                    if (!job[1].isFile() || job[1].lastModified() < job[0].lastModified()) {
                        if (!VulkanShaderCompiler.compileTo(job[0], job[1])) {
                            System.err.println("[ModShaders] " + job[0].getName() + " did not compile; the built-in shader stays active for it.");
                        }
                    }
                }
            }
            ShaderSources.setModOverlays(topDown);
            System.out.println("[ModShaders] " + changed.size() + " shader override(s) mounted; rebuilding pipelines.");
            requestReload(changed);
        });
    }

    /** Removes every mod shader override and rebuilds the pipelines from the built-in shaders. */
    static void revert() {
        List<String> previous = overridden;
        overridden = List.of();
        ShaderSources.setModOverlays(List.of());
        if (!previous.isEmpty()) requestReload(previous);
    }

    private static void requestReload(List<String> names) {
        Consumer<List<String>> sink = reloadSink;
        if (sink != null) sink.accept(names);
    }

    private static void collect(File root, File compiledRoot, List<File[]> glslJobs, Set<String> names) {
        Path base = root.toPath();
        try (Stream<Path> walk = Files.walk(base)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String rel = base.relativize(p).toString().replace('\\', '/');
                String lower = rel.toLowerCase(Locale.ROOT);
                String name = p.getFileName().toString();
                if (lower.endsWith(".vert") || lower.endsWith(".frag")) {
                    glslJobs.add(new File[] { p.toFile(), new File(compiledRoot, rel + ".spv") });
                    names.add(name + ".spv");
                } else if (lower.endsWith(".spv") || lower.endsWith(".metal")) {
                    names.add(name);
                }
            }
        } catch (IOException e) {
            System.err.println("[ModShaders] Could not scan " + root + ": " + e.getMessage());
        }
    }
}
