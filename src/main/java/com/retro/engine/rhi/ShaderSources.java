package com.retro.engine.rhi;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;

/**
 * Resolves backend shader resources ({@code /shaders/vulkan/*.spv}, {@code /shaders/metal/*.metal}, ...).
 *
 * Builds read them from the classpath only. The editor additionally binds an on-disk source root that mirrors the
 * classpath {@code /shaders/} folder (the engine checkout's {@code shaders/} or {@code src/main/resources/shaders/});
 * files found there win over the classpath copy, so a pipeline rebuilt after an edit picks up the new source without
 * a Gradle resource sync.
 *
 * Mods add a third layer on top ({@link #setModOverlays}): folders mirroring {@code /shaders/} (for example
 * {@code Mods/x/Shaders/vulkan/blinn_phong.frag.spv}). Backends that fail to build a pipeline from a mod override
 * ask for {@link #readBuiltin} and rebuild with the engine's own shader instead.
 */
public final class ShaderSources {

    private static final String CLASSPATH_ROOT = "/shaders/";
    private static final String[] SOURCE_ROOT_CANDIDATES = { "shaders", "src/main/resources/shaders" };

    private static volatile File sourceRoot = null;
    // Mod shader override roots, highest priority first; each mirrors the classpath /shaders/ folder
    private static volatile List<File> modOverlays = List.of();

    private ShaderSources() {}

    /** First existing shader source folder under the working directory, or null (e.g. a standalone build). */
    public static File locateSourceRoot() {
        for (String candidate : SOURCE_ROOT_CANDIDATES) {
            File dir = new File(candidate).getAbsoluteFile();
            if (dir.isDirectory()) return dir;
        }
        return null;
    }

    /** Binds (or with null unbinds) the on-disk override root mirroring the classpath {@code /shaders/} folder. */
    public static void setSourceRoot(File root) {
        sourceRoot = (root != null && root.isDirectory()) ? root.getAbsoluteFile() : null;
    }

    public static File getSourceRoot() {
        return sourceRoot;
    }

    /** True for shader sources and binaries the backends consume or compile from. */
    public static boolean isShaderFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".vert") || lower.endsWith(".frag") || lower.endsWith(".metal") || lower.endsWith(".spv");
    }

    /** Installs mod shader override roots, highest priority first (empty list removes them). */
    public static void setModOverlays(List<File> topDownRoots) {
        modOverlays = (topDownRoots != null) ? List.copyOf(topDownRoots) : List.of();
    }

    /** The mod override file that {@link #read} would return for {@code resourcePath}, or null. */
    public static File modOverrideFor(String resourcePath) {
        if (resourcePath == null || !resourcePath.startsWith(CLASSPATH_ROOT)) return null;
        String rel = resourcePath.substring(CLASSPATH_ROOT.length());
        for (File root : modOverlays) {
            File f = new File(root, rel);
            if (f.isFile()) return f;
        }
        return null;
    }

    public static boolean isModOverridden(String resourcePath) {
        return modOverrideFor(resourcePath) != null;
    }

    /** Bytes of a {@code /shaders/...} resource: a mod override, else the source-root copy when bound, else the classpath. */
    public static byte[] read(String resourcePath) {
        if (resourcePath == null) return null;
        File override = modOverrideFor(resourcePath);
        if (override != null) {
            try {
                return Files.readAllBytes(override.toPath());
            } catch (IOException e) {
                System.err.println("[ShaderSources] Failed to read mod shader " + override + ", using the built-in one: " + e.getMessage());
            }
        }
        return readBuiltin(resourcePath);
    }

    /** {@link #read} without mod overrides: the engine's own shader (source root, then classpath). */
    public static byte[] readBuiltin(String resourcePath) {
        if (resourcePath == null) return null;
        File root = sourceRoot;
        if (root != null && resourcePath.startsWith(CLASSPATH_ROOT)) {
            File onDisk = new File(root, resourcePath.substring(CLASSPATH_ROOT.length()));
            if (onDisk.isFile()) {
                try {
                    return Files.readAllBytes(onDisk.toPath());
                } catch (IOException e) {
                    System.err.println("[ShaderSources] Failed to read " + onDisk + ", falling back to the classpath: " + e.getMessage());
                }
            }
        }
        try (InputStream in = ShaderSources.class.getResourceAsStream(resourcePath)) {
            return in != null ? in.readAllBytes() : null;
        } catch (IOException e) {
            System.err.println("[ShaderSources] Failed to read " + resourcePath + ": " + e.getMessage());
            return null;
        }
    }
}
