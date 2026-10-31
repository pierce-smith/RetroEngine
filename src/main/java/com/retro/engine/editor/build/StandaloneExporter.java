package com.retro.engine.editor.build;

import com.retro.engine.Main;
import com.retro.engine.assets.AssetDatabase;
import com.retro.engine.assets.cooker.RpackCookerCli;
import com.retro.engine.assets.importers.SkeletonImporter;
import com.retro.engine.core.ProjectWorkspace;
import com.retro.engine.core.ProjectWorkspace.ProjectSettings;
import com.retro.engine.scene.serialization.SceneSerializer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Cooks the active project and packages it as a self-contained native build.
 *
 * Staged app payload (becomes the launcher's $APPDIR):
 * <pre>
 *   retro-engine.jar + dependency jars (LWJGL JNI + target-OS native jars only)
 *   natives/                      MoltenVK (macOS)
 *   Game_Data/ProjectSettings.bin startup scene rewritten to the chosen scene
 *   Game_Data/Assets/             scenes (.rscene), scripts, materials, audio, raw sources
 *   Game_Data/.retro/             UUID registry, so scene asset references resolve unchanged
 *   Game_Data/Cooked/             .rpack packages produced by RpackCooker
 * </pre>
 *
 * When the target OS matches the host, {@code jpackage} produces the real native launcher (.exe / .app / ELF) and
 * embeds a jlinked runtime; on Linux an AppImage is also produced when {@code appimagetool} is on PATH. Cross-OS
 * exports jlink a runtime from the target platform's JDK jmods and use script launchers (a .app bundle on macOS),
 * since native launchers can only be built on their own OS.
 */
public final class StandaloneExporter {

    public enum Platform {
        WINDOWS("WINDOWS .EXE", "natives-windows"),
        MACOS("MACOS .APP", "natives-macos"),
        LINUX("LINUX ELF / APPIMAGE", "natives-linux");

        public final String label;
        final String nativesToken;

        Platform(String label, String nativesToken) {
            this.label = label;
            this.nativesToken = nativesToken;
        }

        public static Platform host() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) return WINDOWS;
            if (os.contains("mac")) return MACOS;
            return LINUX;
        }
    }

    public static final class Request {
        public final File projectDir;
        public final File exportDir;
        public final String executableName;
        public final Platform platform;
        /** Project-relative startup scene, e.g. "Assets/Scenes/Main.rscene". */
        public final String startupScene;
        /** Target platform JDK home (with jmods/); required only for cross-OS exports. */
        public final File targetJdk;

        public Request(File projectDir, File exportDir, String executableName, Platform platform, String startupScene, File targetJdk) {
            this.projectDir = projectDir;
            this.exportDir = exportDir;
            this.executableName = executableName;
            this.platform = platform;
            this.startupScene = startupScene;
            this.targetJdk = targetJdk;
        }
    }

    public interface Progress {
        void report(float fraction, String message);
    }

    public static final String GAME_DATA_DIR = "Game_Data";
    public static final String GAME_DATA_PROPERTY = "retro.gameData";
    private static final String ENGINE_JAR = "retro-engine.jar";

    private final Request req;
    private final Progress progress;
    private final boolean nativeHost;

    public StandaloneExporter(Request request, Progress progress) {
        this.req = request;
        this.progress = progress;
        this.nativeHost = request.platform == Platform.host();
    }

    public static boolean isValidExecutableName(String name) {
        return name != null && name.matches("[A-Za-z0-9][A-Za-z0-9 _.-]{0,63}") && !name.endsWith(".");
    }

    private void validate() throws IOException {
        if (!isValidExecutableName(req.executableName)) throw new IOException("Invalid executable name: " + req.executableName);
        if (!new File(req.projectDir, AssetDatabase.ASSETS_DIR).isDirectory()) throw new IOException("Project has no Assets/ folder: " + req.projectDir);
        if (!new File(req.projectDir, req.startupScene).isFile()) throw new IOException("Startup scene not found: " + req.startupScene);
    }

    /**
     * Writes Game_Data/ only (Assets/, .retro/ registry, ProjectSettings.bin, RpackCooker output) into
     * exportDir/Game_Data with no native packaging. Used by non-interactive/test exports.
     */
    public File exportGameDataOnly() throws IOException {
        validate();
        File gameData = new File(req.exportDir, GAME_DATA_DIR);
        deleteRecursively(gameData.toPath());
        stageGameData(gameData);
        progress.report(1.0f, "GAME DATA READY");
        return gameData;
    }

    private void stageGameData(File gameData) throws IOException {
        File srcAssets = new File(req.projectDir, AssetDatabase.ASSETS_DIR);
        File cookedDir = new File(gameData, AssetDatabase.COOKED_DIR);

        // Cook meshes/textures from the source project first so manifest UUIDs are preserved and the copy below
        // knows which raw files now have a package
        progress.report(0.05f, "COOKING ASSETS");
        int cooked = RpackCookerCli.cookDirectory(srcAssets.getAbsolutePath(), cookedDir.getAbsolutePath());
        System.out.println("[Exporter] Cooked " + cooked + " asset(s) into " + GAME_DATA_DIR + "/" + AssetDatabase.COOKED_DIR);

        progress.report(0.25f, "COPYING ASSETS");
        copyTree(srcAssets.toPath(), new File(gameData, AssetDatabase.ASSETS_DIR).toPath(),
            rel -> isEditorOnlyAsset(rel) || isReplacedByCookedPackage(srcAssets, cookedDir, rel));
        File internal = new File(req.projectDir, AssetDatabase.INTERNAL_DIR);
        if (internal.isDirectory()) copyTree(internal.toPath(), new File(gameData, AssetDatabase.INTERNAL_DIR).toPath());

        ProjectSettings settings = ProjectWorkspace.readSettings(req.projectDir);
        settings.startupScene = req.startupScene.replace('\\', '/');
        ProjectWorkspace.writeSettings(gameData, settings);
    }

    private static final Set<String> COOKED_MESH_EXTENSIONS = Set.of("obj", "fbx", "gltf", "glb");
    private static final Set<String> COOKED_TEXTURE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "tga");

    /**
     * True when a raw mesh/texture has a cooked .rpack, so the runtime never reads the source (the registry keeps its
     * UUID from the manifest). Rigged meshes stay: skeletons and clips are rebuilt from the raw file on scene load
     * and the .rpack only carries vertices.
     */
    private static boolean isReplacedByCookedPackage(File srcAssets, File cookedDir, String relPath) {
        String name = relPath.substring(relPath.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        boolean mesh = COOKED_MESH_EXTENSIONS.contains(ext);
        if (!mesh && !COOKED_TEXTURE_EXTENSIONS.contains(ext)) return false;

        File raw = new File(srcAssets, relPath);
        if (!RpackCookerCli.cookedPathFor(srcAssets, cookedDir, raw).isFile()) return false;
        if (mesh && !ext.equals("obj") && hasSkeletonOrClips(raw)) {
            System.out.println("[Exporter] Keeping rigged source mesh: " + relPath);
            return false;
        }
        return true;
    }

    private static boolean hasSkeletonOrClips(File meshFile) {
        try {
            SkeletonImporter.SkeletonImportResult r = SkeletonImporter.loadSkeletonAndAnimations(meshFile.getAbsolutePath());
            if (r == null) return false;
            return (r.skeleton() != null && r.skeleton().getBoneCount() > 0)
                || (r.animations() != null && !r.animations().isEmpty());
        } catch (Exception e) {
            return true; // Unknown: ship the source rather than risk losing animation data
        }
    }

    /** Runs the full pipeline; returns the path of the produced build. */
    public File export() throws IOException, InterruptedException {
        validate();
        if (!nativeHost && (req.targetJdk == null || !new File(req.targetJdk, "jmods").isDirectory())) {
            throw new IOException("Cross-platform export needs a " + req.platform.label + " JDK containing jmods/");
        }

        File work = new File(req.exportDir, "." + req.executableName + "-staging");
        deleteRecursively(work.toPath());
        File app = new File(work, "app");
        File gameData = new File(app, GAME_DATA_DIR);

        try {
            // 1-2. Game data: raw Assets/, UUID registry, rewritten settings, cooked .rpack packages
            stageGameData(gameData);

            // 3. Engine classes + dependency jars filtered to the target OS natives
            progress.report(0.40f, "STAGING RUNTIME LIBRARIES");
            stageClasspath(app);
            stageNativeLibraries(new File(app, "natives"));

            // 4. Native packaging
            progress.report(0.55f, nativeHost ? "PACKAGING NATIVE LAUNCHER (JPACKAGE)" : "LINKING EMBEDDED RUNTIME (JLINK)");
            File result = nativeHost ? packageWithJpackage(app) : packageCrossPlatform(work, app);
            progress.report(1.0f, "EXPORT COMPLETE");
            return result;
        } finally {
            deleteRecursively(work.toPath());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Classpath staging
    // ------------------------------------------------------------------------------------------

    private void stageClasspath(File app) throws IOException {
        String cp = System.getProperty("java.class.path", "");
        List<File> classDirs = new ArrayList<>();
        for (String entry : cp.split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            File f = new File(entry);
            if (f.isDirectory()) {
                classDirs.add(f);
            } else if (f.isFile() && f.getName().endsWith(".jar") && includeJarForTarget(f.getName())) {
                Files.copy(f.toPath(), new File(app, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (classDirs.isEmpty() && !new File(app, ENGINE_JAR).exists()) {
            // Editor itself launched from a jar: make sure an entry named retro-engine.jar exists for the launcher
            File self = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().getPath());
            if (self.isFile()) Files.copy(self.toPath(), new File(app, ENGINE_JAR).toPath(), StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        writeEngineJar(classDirs, new File(app, ENGINE_JAR));
    }

    /** Keeps platform-neutral jars and the target OS's native jars (all architectures; LWJGL picks at load time). */
    private boolean includeJarForTarget(String jarName) {
        int idx = jarName.indexOf("natives-");
        if (idx < 0) return true;
        return jarName.startsWith(req.platform.nativesToken, idx);
    }

    private void writeEngineJar(List<File> classDirs, File out) throws IOException {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, Main.class.getName());
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(out.toPath()), mf)) {
            HashSet<String> seen = new HashSet<>();
            for (File dir : classDirs) {
                Path root = dir.toPath();
                try (var stream = Files.walk(root)) {
                    for (Path p : (Iterable<Path>) stream.sorted()::iterator) {
                        if (!Files.isRegularFile(p)) continue;
                        String name = root.relativize(p).toString().replace('\\', '/');
                        if (name.equalsIgnoreCase("META-INF/MANIFEST.MF") || !seen.add(name)) continue;
                        jar.putNextEntry(new JarEntry(name));
                        Files.copy(p, jar);
                        jar.closeEntry();
                    }
                }
            }
        }
    }

    /** Vulkan portability layer for macOS; Windows/Linux use the system Vulkan loader installed with the GPU driver. */
    private void stageNativeLibraries(File nativesDir) throws IOException {
        if (req.platform != Platform.MACOS) return;
        File molten = findMoltenVk();
        if (molten == null) {
            System.err.println("[Exporter] libMoltenVK.dylib not found; the macOS build will only run on the Metal backend.");
            return;
        }
        nativesDir.mkdirs();
        Files.copy(molten.toPath(), new File(nativesDir, "libMoltenVK.dylib").toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static File findMoltenVk() {
        String prop = System.getProperty("org.lwjgl.vulkan.libname");
        if (prop != null && new File(prop).isFile()) return new File(prop);
        File local = new File(System.getProperty("user.dir"), "libs/macos/libMoltenVK.dylib");
        if (local.isFile()) return local;
        String sdk = System.getenv("VULKAN_SDK");
        if (sdk != null) {
            File f = new File(sdk, "lib/libMoltenVK.dylib");
            if (f.isFile()) return f;
        }
        return null;
    }

    private List<String> jvmOptions(String appDirToken) {
        List<String> opts = new ArrayList<>(List.of(
            "-Xms256m", "-Xmx512m",
            "-Dorg.lwjgl.system.stack.size=128",
            "--enable-native-access=ALL-UNNAMED",
            "-D" + GAME_DATA_PROPERTY + "=" + appDirToken + "/" + GAME_DATA_DIR));
        if (req.platform == Platform.MACOS) {
            opts.add("-XstartOnFirstThread");
            opts.add("-Dorg.lwjgl.vulkan.libname=" + appDirToken + "/natives/libMoltenVK.dylib");
        }
        return opts;
    }

    // ------------------------------------------------------------------------------------------
    // Host-native packaging (jpackage)
    // ------------------------------------------------------------------------------------------

    /**
     * Modules the runtime needs (jdeps over the engine plus its jars), plus jdk.zipfs, a FileSystem provider jdeps
     * cannot see. Janino compiles scripts in-process, so java.compiler is not required.
     */
    private static final String RUNTIME_MODULES = "java.base,java.desktop,java.logging,jdk.unsupported,jdk.zipfs";
    private static final String JLINK_SLIM_FLAGS = "--strip-debug --no-header-files --no-man-pages";

    private File packageWithJpackage(File app) throws IOException, InterruptedException {
        File jpackage = jdkTool("jpackage");
        req.exportDir.mkdirs();
        File target = switch (req.platform) {
            case MACOS -> new File(req.exportDir, req.executableName + ".app");
            default -> new File(req.exportDir, req.executableName);
        };
        deleteRecursively(target.toPath());

        List<String> cmd = new ArrayList<>(List.of(
            jpackage.getAbsolutePath(),
            "--type", "app-image",
            "--name", req.executableName,
            "--input", app.getAbsolutePath(),
            "--main-jar", ENGINE_JAR,
            "--main-class", Main.class.getName(),
            "--dest", req.exportDir.getAbsolutePath(),
            "--arguments", "--runtime",
            "--add-modules", RUNTIME_MODULES,
            // Replaces jpackage's default jlink options, so keep --strip-native-commands (the launcher needs no bin/java)
            "--jlink-options", JLINK_SLIM_FLAGS + " --strip-native-commands"));
        for (String opt : jvmOptions("$APPDIR")) {
            cmd.add("--java-options");
            cmd.add(opt);
        }
        if (req.platform == Platform.WINDOWS) cmd.add("--win-console");
        if (req.platform == Platform.MACOS) {
            cmd.add("--mac-package-identifier");
            cmd.add(bundleId(req.executableName));
        }
        run(cmd, null);

        if (req.platform == Platform.MACOS) finalizeMacBundle(target);

        if (req.platform == Platform.LINUX) {
            File appImage = tryBuildAppImage(target);
            if (appImage != null) return appImage;
        }
        return target;
    }

    /** Wraps a jpackage Linux app-image as an AppImage when appimagetool is available. */
    private File tryBuildAppImage(File appImageDir) throws IOException, InterruptedException {
        String tool = findOnPath("appimagetool");
        if (tool == null) {
            System.out.println("[Exporter] appimagetool not on PATH; shipping the ELF app-image folder only.");
            return null;
        }
        progress.report(0.90f, "BUILDING APPIMAGE");
        File appDir = new File(req.exportDir, req.executableName + ".AppDir");
        deleteRecursively(appDir.toPath());
        copyTree(appImageDir.toPath(), appDir.toPath());
        String exe = req.executableName;
        writeExecutable(new File(appDir, "AppRun"), "#!/bin/sh\nHERE=\"$(dirname \"$(readlink -f \"$0\")\")\"\nexec \"$HERE/bin/" + exe + "\" \"$@\"\n");
        Files.writeString(new File(appDir, exe + ".desktop").toPath(),
            "[Desktop Entry]\nType=Application\nName=" + exe + "\nExec=" + exe + "\nIcon=" + exe + "\nCategories=Game;\n");
        BufferedImage icon = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) icon.setRGB(x, y, 0xFF3B82F6);
        ImageIO.write(icon, "png", new File(appDir, exe + ".png"));

        String arch = System.getProperty("os.arch", "x86_64").replace("amd64", "x86_64").replace("aarch64", "arm64");
        File out = new File(req.exportDir, exe + "-" + arch + ".AppImage");
        List<String> cmd = List.of(tool, appDir.getAbsolutePath(), out.getAbsolutePath());
        try {
            run(cmd, null);
        } finally {
            deleteRecursively(appDir.toPath());
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Cross-OS packaging (jlink against target jmods + script launchers)
    // ------------------------------------------------------------------------------------------

    private File packageCrossPlatform(File work, File app) throws IOException, InterruptedException {
        File runtime = new File(work, "runtime");
        run(List.of(jdkTool("jlink").getAbsolutePath(),
            "--module-path", new File(req.targetJdk, "jmods").getAbsolutePath(),
            "--add-modules", RUNTIME_MODULES,
            "--strip-debug", "--no-header-files", "--no-man-pages",
            "--output", runtime.getAbsolutePath()), null);

        progress.report(0.85f, "ASSEMBLING " + req.platform.label);
        String exe = req.executableName;
        File target;
        switch (req.platform) {
            case MACOS -> {
                target = new File(req.exportDir, exe + ".app");
                deleteRecursively(target.toPath());
                File contents = new File(target, "Contents");
                copyTree(runtime.toPath(), new File(contents, "runtime").toPath());
                copyTree(app.toPath(), new File(contents, "app").toPath());
                Files.writeString(new File(contents, "Info.plist").toPath(), infoPlist(exe, null));
                writeExecutable(new File(contents, "MacOS/" + exe),
                    "#!/bin/sh\nCONTENTS=\"$(cd \"$(dirname \"$0\")/..\" && pwd)\"\nAPPDIR=\"$CONTENTS/app\"\n"
                        + "exec \"$CONTENTS/runtime/bin/java\" " + shellOptions("$APPDIR") + " -cp \"$APPDIR/*\" " + Main.class.getName() + " --runtime \"$@\"\n");
                if (Platform.host() == Platform.MACOS) adHocSign(target);
            }
            case LINUX -> {
                target = new File(req.exportDir, exe);
                deleteRecursively(target.toPath());
                copyTree(runtime.toPath(), new File(target, "runtime").toPath());
                copyTree(app.toPath(), new File(target, "app").toPath());
                writeExecutable(new File(target, exe),
                    "#!/bin/sh\nHERE=\"$(dirname \"$(readlink -f \"$0\")\")\"\nAPPDIR=\"$HERE/app\"\n"
                        + "exec \"$HERE/runtime/bin/java\" " + shellOptions("$APPDIR") + " -cp \"$APPDIR/*\" " + Main.class.getName() + " --runtime \"$@\"\n");
            }
            default -> {
                target = new File(req.exportDir, exe);
                deleteRecursively(target.toPath());
                copyTree(runtime.toPath(), new File(target, "runtime").toPath());
                copyTree(app.toPath(), new File(target, "app").toPath());
                StringBuilder opts = new StringBuilder();
                for (String o : jvmOptions("%APPDIR%")) opts.append('"').append(o).append("\" ");
                Files.writeString(new File(target, exe + ".bat").toPath(),
                    "@echo off\r\nset \"APPDIR=%~dp0app\"\r\n\"%~dp0runtime\\bin\\javaw.exe\" " + opts
                        + "-cp \"%APPDIR%\\*\" " + Main.class.getName() + " --runtime %*\r\n");
            }
        }
        return target;
    }

    private String shellOptions(String appDirVar) {
        StringBuilder sb = new StringBuilder();
        for (String o : jvmOptions(appDirVar)) sb.append('"').append(o).append("\" ");
        return sb.toString().trim();
    }

    private static String bundleId(String exe) {
        String id = exe.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return "com.retro.game." + (id.isEmpty() ? "app" : id);
    }

    /**
     * Minimal game Info.plist: no NS*UsageDescription, NSBonjourServices, NSLocalNetworkUsageDescription,
     * iCloud container or document-type keys, so macOS has nothing to prompt for on launch.
     */
    private static String infoPlist(String exe, String iconFile) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
            + "<plist version=\"1.0\"><dict>\n"
            + "  <key>CFBundleDevelopmentRegion</key><string>en</string>\n"
            + "  <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>\n"
            + "  <key>CFBundleName</key><string>" + exe + "</string>\n"
            + "  <key>CFBundleDisplayName</key><string>" + exe + "</string>\n"
            + "  <key>CFBundleExecutable</key><string>" + exe + "</string>\n"
            + "  <key>CFBundleIdentifier</key><string>" + bundleId(exe) + "</string>\n"
            + "  <key>CFBundlePackageType</key><string>APPL</string>\n"
            + "  <key>CFBundleShortVersionString</key><string>1.0</string>\n"
            + "  <key>CFBundleVersion</key><string>1</string>\n"
            + (iconFile != null ? "  <key>CFBundleIconFile</key><string>" + iconFile + "</string>\n" : "")
            + "  <key>LSApplicationCategoryType</key><string>public.app-category.games</string>\n"
            + "  <key>LSMinimumSystemVersion</key><string>13.0</string>\n"
            + "  <key>NSHighResolutionCapable</key><true/>\n"
            + "  <key>NSSupportsAutomaticGraphicsSwitching</key><true/>\n"
            + "  <key>NSPrincipalClass</key><string>NSApplication</string>\n"
            + "</dict></plist>\n";
    }

    /** Replaces jpackage's Info.plist with the clean one (keeping its icon) and re-signs, since the edit breaks its seal. */
    private void finalizeMacBundle(File app) throws IOException, InterruptedException {
        File contents = new File(app, "Contents");
        File[] icons = new File(contents, "Resources").listFiles((d, n) -> n.endsWith(".icns"));
        String icon = (icons != null && icons.length > 0) ? icons[0].getName() : null;
        Files.writeString(new File(contents, "Info.plist").toPath(), infoPlist(req.executableName, icon));
        adHocSign(app);
    }

    /**
     * Ad-hoc signature with no entitlements file: no com.apple.security.network.*, iCloud or app-group entitlements,
     * and arm64 Macs still accept the bundle as intact.
     */
    private void adHocSign(File app) throws IOException, InterruptedException {
        run(List.of("codesign", "--force", "--deep", "--sign", "-", app.getAbsolutePath()), null);
    }

    // ------------------------------------------------------------------------------------------
    // Process + file helpers
    // ------------------------------------------------------------------------------------------

    private static File jdkTool(String name) throws IOException {
        String suffix = Platform.host() == Platform.WINDOWS ? ".exe" : "";
        File tool = new File(System.getProperty("java.home"), "bin/" + name + suffix);
        if (!tool.isFile()) throw new IOException(name + " not found in " + System.getProperty("java.home") + " (a full JDK is required to export)");
        return tool;
    }

    private static String findOnPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            File f = new File(dir, exe);
            if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    private static void run(List<String> cmd, File cwd) throws IOException, InterruptedException {
        System.out.println("[Exporter] $ " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (cwd != null) pb.directory(cwd);
        Process proc = pb.start();
        StringBuilder tail = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                System.out.println("[Exporter]   " + line);
                tail.append(line).append('\n');
                if (tail.length() > 2000) tail.delete(0, tail.length() - 2000);
            }
        }
        int code = proc.waitFor();
        if (code != 0) throw new IOException(new File(cmd.get(0)).getName() + " failed (exit " + code + "): " + tail.toString().trim());
    }

    private static void writeExecutable(File file, String content) throws IOException {
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content);
        if (!file.setExecutable(true, false)) System.err.println("[Exporter] Could not mark executable: " + file);
    }

    /**
     * Assets/-relative paths the editor uses but a shipped game never loads: UI sound effects, Editor/ and Engine/
     * folders (editor icons and gizmo art live there), and in-flight temp/backup files.
     */
    static boolean isEditorOnlyAsset(String relPath) {
        String lower = relPath.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (SceneSerializer.isInternalEditorResource(lower)) return true;
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        return name.endsWith(".tmp") || name.endsWith("~") || name.endsWith(".bak") || name.endsWith(".swp");
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        copyTree(src, dst, rel -> false);
    }

    private static void copyTree(Path src, Path dst, Predicate<String> exclude) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String rel = src.relativize(dir).toString().replace('\\', '/');
                if (!rel.isEmpty() && exclude.test(rel + "/")) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(dst.resolve(rel));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                if (name.equals(".DS_Store") || name.equalsIgnoreCase("Thumbs.db")) return FileVisitResult.CONTINUE;
                if (exclude.test(src.relativize(file).toString().replace('\\', '/'))) {
                    System.out.println("[Exporter] Not shipping: " + src.relativize(file));
                    return FileVisitResult.CONTINUE;
                }
                Path out = dst.resolve(src.relativize(file).toString());
                Files.copy(file, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path p : (Iterable<Path>) stream.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }
}
