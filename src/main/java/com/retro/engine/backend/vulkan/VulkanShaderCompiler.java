package com.retro.engine.backend.vulkan;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Editor shader hot reload: recompiles an edited GLSL source into the {@code <name>.spv} next to it with glslc, the
 * same layout the compileVulkanShaders Gradle task produces. Runs on a virtual thread; the new .spv is moved into place
 * atomically, so the asset watcher sees one complete write and triggers the pipeline rebuild. A failed compile leaves
 * the previous .spv (and therefore the live pipelines) untouched.
 */
final class VulkanShaderCompiler {

    private static final Object COMPILE_LOCK = new Object();
    private static volatile boolean glslcMissingReported = false;

    private VulkanShaderCompiler() {}

    static void compileAsync(String glslPath) {
        Thread.ofVirtual().name("glslc-hot-reload").start(() -> compile(new File(glslPath)));
    }

    private static void compile(File source) {
        if (!source.isFile()) return;
        String glslc = findGlslc();
        if (glslc == null) {
            if (!glslcMissingReported) {
                glslcMissingReported = true;
                System.err.println("[VulkanShaderCompiler] glslc not found (VULKAN_SDK or PATH); run ./gradlew compileVulkanShaders "
                        + "after editing GLSL and the rebuilt .spv files will hot reload.");
            }
            return;
        }

        Path target = new File(source.getPath() + ".spv").toPath();
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        // Serialized so two quick saves never interleave writes to the same temp file
        synchronized (COMPILE_LOCK) {
            try {
                Process process = new ProcessBuilder(glslc, source.getAbsolutePath(), "-o", temp.toString())
                        .redirectErrorStream(true)
                        .start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (process.waitFor() != 0) {
                    System.err.println("[VulkanShaderCompiler] " + source.getName() + " failed to compile; keeping the previous SPIR-V.\n" + output);
                    Files.deleteIfExists(temp);
                    return;
                }
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                System.out.println("[VulkanShaderCompiler] Compiled " + source.getName() + " -> " + target.getFileName());
            } catch (IOException e) {
                System.err.println("[VulkanShaderCompiler] Could not run glslc for " + source.getName() + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Same search order as build.gradle.kts: the Vulkan SDK, common install locations, then PATH. */
    private static String findGlslc() {
        String sdk = System.getenv("VULKAN_SDK");
        if (sdk != null && !sdk.isEmpty()) {
            for (String candidate : new String[] { sdk + "/bin/glslc", sdk + "/bin/glslc.exe", sdk + "/Bin/glslc.exe" }) {
                if (new File(candidate).isFile()) return candidate;
            }
        }
        for (String candidate : new String[] { "/opt/homebrew/bin/glslc", "/usr/local/bin/glslc", "/usr/bin/glslc" }) {
            if (new File(candidate).isFile()) return candidate;
        }
        for (String command : new String[] { "glslc", "glslc.exe" }) {
            try {
                Process probe = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
                probe.getInputStream().readAllBytes();
                if (probe.waitFor() == 0) return command;
            } catch (IOException ignored) {
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}
