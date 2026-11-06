package com.retro.engine.editor;

import com.retro.engine.Main;
import com.retro.engine.assets.AssetDatabase;
import com.retro.engine.assets.RpackCookerCli;
import com.retro.engine.assets.SkeletonImporter;
import com.retro.engine.core.ProjectSettings;
import com.retro.engine.core.ProjectWorkspace;
import com.retro.engine.ecs.serialization.SceneSerializer;
import com.retro.engine.modding.ModLoader;

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
import javax.imageio.ImageIO;

/**
 * Cooks the active project and packages it as a self-contained native build.
 *
 * Staged app payload (becomes the launcher's $APPDIR):
 * <pre>
 *   retro-engine.jar + dependency jars (LWJGL JNI + target-OS native jars only; SDL only with controller haptics)
 *   natives/                      MoltenVK (macOS)
 *   Game_Data/ProjectSettings.bin startup scene rewritten to the chosen scene
 *   Game_Data/Assets/             scenes (.rscene), scripts, materials, audio, raw sources
 *   Game_Data/.retro/             UUID registry, so scene asset references resolve unchanged
 *   Game_Data/Cooked/             .rpack packages produced by RpackCooker
 * </pre>
 *
 * When the target OS matches the host, {@code jpackage} produces the real native launcher (.exe / .app / ELF) and
 * embeds a jlinked runtime; on Linux an AppImage is also produced when {@code appimagetool} is on PATH. Without
 * jpackage (cross-OS exports, or a packaged editor on a machine with no JDK) the runtime comes from the target
 * platform's jmods or the editor's bundled JRE. macOS and Linux then get script launchers (a .app bundle on macOS);
 * Windows always gets a real .exe, assembled from jpackage's prebuilt launcher (see {@link #windowsLauncher()}).
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

        /** Icon format jpackage (and the OS) expects for this target: .icns (macOS), .ico (Windows), .png (Linux). */
        public String iconExtension() {
            return switch (this) {
                case MACOS -> "icns";
                case WINDOWS -> "ico";
                case LINUX -> "png";
            };
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
        /** Ships SDL (bindings + natives, a few MB) for gamepad rumble; without it vibration calls are no-ops. */
        public final boolean includeHaptics;
        /** Optional application icon in {@link Platform#iconExtension()} format; null keeps the default icon. */
        public final File iconFile;

        public Request(File projectDir, File exportDir, String executableName, Platform platform, String startupScene,
                       File targetJdk, boolean includeHaptics) {
            this(projectDir, exportDir, executableName, platform, startupScene, targetJdk, includeHaptics, null);
        }

        public Request(File projectDir, File exportDir, String executableName, Platform platform, String startupScene,
                       File targetJdk, boolean includeHaptics, File iconFile) {
            this.iconFile = iconFile;
            this.projectDir = projectDir;
            this.exportDir = exportDir;
            this.executableName = executableName;
            this.platform = platform;
            this.startupScene = startupScene;
            this.targetJdk = targetJdk;
            this.includeHaptics = includeHaptics;
        }
    }

    public interface Progress {
        void report(float fraction, String message);
    }

    public static final String GAME_DATA_DIR = "Game_Data";
    public static final String GAME_DATA_PROPERTY = "retro.gameData";
    /** Set by the packaged editor's launcher: a jlinked JRE (with bin/java) that exported games can embed. */
    public static final String GAME_RUNTIME_PROPERTY = "retro.gameRuntime";
    /** Set by the packaged Windows editor's launcher: the jpackage launcher .exe that exported games are built from. */
    public static final String GAME_LAUNCHER_PROPERTY = "retro.gameLauncher";
    /** Optional override pointing at a full JDK whose jpackage/jlink the exporter should use. */
    public static final String JDK_PROPERTY = "retro.jdk";
    private static final String ENGINE_JAR = "retro-engine.jar";
    static final String LEGAL_NOTICES_FILE = "THIRD_PARTY_NOTICES.txt";
    private static final String SDL_JAR_PREFIX = "lwjgl-sdl";
    /** GUI-subsystem launcher in jdk.jpackage: no console window, reads app/<name>.cfg, starts runtime/bin/jli.dll. */
    private static final String WINDOWS_LAUNCHER_ENTRY = "classes/jdk/jpackage/internal/resources/jpackageapplauncherw.exe";
    private static final java.util.regex.Pattern HAPTICS_CALL = java.util.regex.Pattern.compile("\\b(vibrate|setVibration)\\s*\\(");

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

    /** Default application icon for {@code platform}: src/main/resources/icons/icon.(icns|ico|png). */
    public static String defaultIconPath(Platform platform) {
        return "src/main/resources/icons/icon." + platform.iconExtension();
    }

    /** {@link #defaultIconPath(Platform)} as a file, or null when it doesn't exist (keeps the built-in icon). */
    public static File defaultIconFile(Platform platform) {
        File f = new File(defaultIconPath(platform));
        return f.isFile() ? f : null;
    }

    /** True when {@code icon}'s extension is the format {@code platform} expects (see {@link Platform#iconExtension()}). */
    public static boolean isValidIconFor(File icon, Platform platform) {
        return icon != null && platform != null
            && icon.getName().toLowerCase(Locale.ROOT).endsWith("." + platform.iconExtension());
    }

    /** Copies the configured icon to {@code dest}; false when no icon was configured. */
    private boolean copyIcon(File dest) throws IOException {
        if (req.iconFile == null) return false;
        dest.getParentFile().mkdirs();
        Files.copy(req.iconFile.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    private void validate() throws IOException {
        if (!isValidExecutableName(req.executableName)) throw new IOException("Invalid game name: " + req.executableName);
        if (req.iconFile != null) {
            if (!req.iconFile.isFile()) throw new IOException("Application icon not found: " + req.iconFile);
            if (!isValidIconFor(req.iconFile, req.platform)) {
                throw new IOException(req.platform.label + " needs a ." + req.platform.iconExtension() + " icon: " + req.iconFile.getName());
            }
        }
        File assets = new File(req.projectDir, AssetDatabase.ASSETS_DIR);
        if (!assets.isDirectory()) throw new IOException("Project has no Assets/ folder: " + req.projectDir);
        if (!new File(req.projectDir, req.startupScene).isFile()) throw new IOException("Startup scene not found: " + req.startupScene);
        // The asset copy walks Assets/ while writing into the export folder; nesting them would copy the build into itself
        Path exportPath = req.exportDir.toPath().toAbsolutePath().normalize();
        if (exportPath.startsWith(assets.toPath().toAbsolutePath().normalize())) {
            throw new IOException("Choose an export folder outside the project's Assets/ folder: " + req.exportDir);
        }
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

        createModsFolder(gameData);
    }

    /**
     * Empty Game_Data/Mods/ that players drop mod folders or .rmod packages into; ModLoader scans it on every launch.
     * The project's own development Mods/ folder is deliberately not shipped.
     */
    private static void createModsFolder(File gameData) throws IOException {
        File mods = new File(gameData, ModLoader.MODS_DIR);
        if (!mods.isDirectory() && !mods.mkdirs()) throw new IOException("Could not create " + mods);
        Files.writeString(new File(mods, "README.txt").toPath(), String.join(System.lineSeparator(),
            "Install mods here.",
            "",
            "Each mod is either a folder containing mod.json, or a packaged <mod_id>-<version>.rmod file.",
            "Mods load in dependency order, then by load_priority (higher loads later and wins conflicts).",
            "Start the game with --no-mods to launch without any mods.",
            ""), StandardCharsets.UTF_8);
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
        if (!nativeHost && jmodsDir(req.targetJdk) == null) {
            throw new IOException("Cross-platform export needs a " + req.platform.label + " JDK containing jmods/, or its jmods "
                + "package (JDK 24+ builds such as Temurin ship jmods as a separate download)");
        }
        // The packaged editor's own runtime has no jpackage (a jlinked image cannot contain jdk.jpackage), so host
        // exports fall back to the JRE bundled with the editor when no full JDK is installed
        boolean useJpackage = nativeHost && findJdkTool("jpackage") != null;
        File bundledRuntime = nativeHost && !useJpackage ? bundledGameRuntime() : null;
        if (nativeHost && !useJpackage && bundledRuntime == null) {
            throw new IOException("No jpackage found and no bundled game runtime: install JDK 25 and set JAVA_HOME (or -D"
                + JDK_PROPERTY + "=<jdk>)");
        }
        if (!nativeHost) jdkTool("jlink");
        byte[] windowsLauncher = !useJpackage && req.platform == Platform.WINDOWS ? windowsLauncher() : null;
        if (!hasTargetNatives()) {
            throw new IOException("This editor build has no " + req.platform.label + " native libraries (packaged editors ship "
                + "their own OS's natives only). Export from the editor running on " + req.platform.label + " instead.");
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
            writeLegalNotices(app);

            // 4. Native packaging
            progress.report(0.55f, useJpackage ? "PACKAGING NATIVE LAUNCHER (JPACKAGE)"
                : bundledRuntime != null ? "EMBEDDING BUNDLED RUNTIME" : "LINKING EMBEDDED RUNTIME (JLINK)");
            File result = useJpackage ? packageWithJpackage(app) : packageWithoutJpackage(work, app, bundledRuntime, windowsLauncher);
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
        File engineJar = new File(app, ENGINE_JAR);
        if (classDirs.isEmpty()) {
            // Editor itself launched from a jar (packaged editor): make sure an entry named retro-engine.jar exists
            File self;
            try {
                self = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            } catch (java.net.URISyntaxException | NullPointerException | SecurityException e) {
                throw new IOException("Cannot locate the engine jar", e);
            }
            if (!isEngineJar(engineJar)) {
                if (!isEngineJar(self)) throw new IOException("The running engine is not a usable jar to ship: " + self);
                Files.copy(self.toPath(), engineJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
                if (!self.getName().equals(ENGINE_JAR)) new File(app, self.getName()).delete(); // copied above under its own name
            }
        } else {
            writeEngineJar(classDirs, engineJar);
        }
        if (!isEngineJar(engineJar)) throw new IOException("Staged " + ENGINE_JAR + " is missing or has no engine classes");
    }

    private static final String MAIN_CLASS_ENTRY = Main.class.getName().replace('.', '/') + ".class";

    /** True for a non-empty, readable jar that contains the engine's Main class. */
    private static boolean isEngineJar(File jar) {
        if (jar == null || !jar.isFile() || jar.length() == 0) return false;
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar)) {
            return jf.getEntry(MAIN_CLASS_ENTRY) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Keeps platform-neutral jars and the target OS's native jars (all architectures; LWJGL picks at load time). SDL
     * serves only gamepad rumble, so its jars are dropped when the build does not include haptics.
     */
    /** True when the editor's classpath has the target OS's LWJGL native jars to ship with the game. */
    private boolean hasTargetNatives() {
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            String name = new File(entry).getName();
            int idx = name.indexOf("natives-");
            if (idx >= 0 && name.startsWith(req.platform.nativesToken, idx)) return true;
        }
        return false;
    }

    private boolean includeJarForTarget(String jarName) {
        if (!req.includeHaptics && jarName.startsWith(SDL_JAR_PREFIX)) {
            System.out.println("[Exporter] Not shipping (no controller haptics): " + jarName);
            return false;
        }
        int idx = jarName.indexOf("natives-");
        if (idx < 0) return true;
        return jarName.startsWith(req.platform.nativesToken, idx);
    }

    /**
     * Jars the class/resource directories into {@code out}. Written to a temp file and moved into place only once it
     * holds the engine's Main class, so a failed or empty write never replaces a good jar.
     */
    private void writeEngineJar(List<File> classDirs, File out) throws IOException {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, Main.class.getName());
        Path temp = out.toPath().resolveSibling(out.getName() + ".tmp");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(temp), mf)) {
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
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        if (!isEngineJar(temp.toFile())) {
            Files.deleteIfExists(temp);
            throw new IOException("Engine classes not found on the classpath (" + classDirs + "); build the engine first");
        }
        Files.move(temp, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Every shipped game carries the engine and its dependencies, so it has to carry their notices and licenses too.
     * A missing resource fails the export rather than shipping a build without them.
     */
    static void writeLegalNotices(File app) throws IOException {
        StringBuilder text = new StringBuilder();
        for (String resource : List.of("/legal/NOTICE.txt", "/legal/LICENSE.txt")) {
            try (var in = StandaloneExporter.class.getResourceAsStream(resource)) {
                if (in == null) throw new IOException("Engine build has no " + resource + "; rebuild the engine");
                text.append(new String(in.readAllBytes(), StandardCharsets.UTF_8)).append(System.lineSeparator());
            }
        }
        Files.writeString(new File(app, LEGAL_NOTICES_FILE).toPath(), text, StandardCharsets.UTF_8);
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

    private List<String> jvmOptions(String appDirToken) throws IOException {
        int maxHeapMb = ProjectWorkspace.readSettings(req.projectDir).maxHeapMb;
        String logName = req.executableName.replace(' ', '_'); // no spaces: a jpackage option is one token
        List<String> opts = new ArrayList<>(List.of(
            "-Xms256m", "-Xmx" + maxHeapMb + "m",
            "-Dorg.lwjgl.system.stack.size=128",
            "--enable-native-access=ALL-UNNAMED",
            // No console when launched from Finder / Explorer: Main tees output into the OS log folder
            "-Dretro.packaged=true",
            "-D" + Main.LOG_NAME_PROPERTY + "=" + logName,
            "-D" + GAME_DATA_PROPERTY + "=" + appDirToken + "/" + GAME_DATA_DIR));
        opts.addAll(crashFileOptions(req.platform, logName));
        if (req.platform == Platform.MACOS) {
            opts.add("-XstartOnFirstThread");
            opts.add("-Dorg.lwjgl.vulkan.libname=" + appDirToken + "/natives/libMoltenVK.dylib");
        }
        return opts;
    }

    /**
     * Sends JVM crash reports and out-of-memory heap dumps to the folder Main logs to, which it creates at startup. The
     * launcher expands {@code $HOME} / {@code $LOCALAPPDATA} (jpackage's and the shell's alike), and Linux can't honor
     * XDG_STATE_HOME here because an unset variable would stay literal. If the folder is unusable the JVM falls back to
     * the working directory. The dump name has no PID on purpose: the JVM won't overwrite it, so a player's disk holds
     * at most one.
     */
    static List<String> crashFileOptions(Platform platform, String logName) {
        String dir = switch (platform) {
            case MACOS -> "$HOME/Library/Logs/RetroEngine";
            case WINDOWS -> "$LOCALAPPDATA/RetroEngine/logs";
            case LINUX -> "$HOME/.local/state/retroengine";
        };
        return List.of(
            "-XX:ErrorFile=" + dir + "/" + logName + "_crash_%p.log",
            "-XX:+HeapDumpOnOutOfMemoryError",
            "-XX:HeapDumpPath=" + dir + "/" + logName + "_oom.hprof");
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
        if (req.iconFile != null) {
            cmd.add("--icon");
            cmd.add(req.iconFile.getAbsolutePath());
        }
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
        if (!copyIcon(new File(appDir, exe + ".png"))) {
            BufferedImage icon = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) icon.setRGB(x, y, 0xFF3B82F6);
            ImageIO.write(icon, "png", new File(appDir, exe + ".png"));
        }

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
    // Packaging without jpackage (jlink against target jmods or the bundled JRE; assembled launchers)
    // ------------------------------------------------------------------------------------------

    /**
     * Builds without jpackage. {@code prebuiltRuntime} (the editor's bundled host JRE) is embedded as-is; when null a
     * runtime is jlinked from the target platform's jmods. {@code windowsLauncher} is required for Windows targets.
     */
    private File packageWithoutJpackage(File work, File app, File prebuiltRuntime, byte[] windowsLauncher) throws IOException, InterruptedException {
        File runtime = prebuiltRuntime;
        if (runtime == null) {
            runtime = new File(work, "runtime");
            run(List.of(jdkTool("jlink").getAbsolutePath(),
                "--module-path", jmodsDir(req.targetJdk).getAbsolutePath(),
                "--add-modules", RUNTIME_MODULES,
                "--strip-debug", "--no-header-files", "--no-man-pages",
                "--output", runtime.getAbsolutePath()), null);
        }

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
                String iconName = copyIcon(new File(contents, "Resources/" + exe + ".icns")) ? exe + ".icns" : null;
                Files.writeString(new File(contents, "Info.plist").toPath(), infoPlist(exe, iconName));
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
                copyIcon(new File(target, exe + ".png"));
                writeExecutable(new File(target, exe),
                    "#!/bin/sh\nHERE=\"$(dirname \"$(readlink -f \"$0\")\")\"\nAPPDIR=\"$HERE/app\"\n"
                        + "exec \"$HERE/runtime/bin/java\" " + shellOptions("$APPDIR") + " -cp \"$APPDIR/*\" " + Main.class.getName() + " --runtime \"$@\"\n");
            }
            default -> {
                target = new File(req.exportDir, exe);
                deleteRecursively(target.toPath());
                copyTree(runtime.toPath(), new File(target, "runtime").toPath());
                copyTree(app.toPath(), new File(target, "app").toPath());
                File appDir = new File(target, "app");
                Files.writeString(new File(appDir, exe + ".cfg").toPath(), windowsLauncherConfig(appDir));
                Files.write(new File(target, exe + ".exe").toPath(), withIcon(windowsLauncher));
            }
        }
        return target;
    }

    /**
     * The launcher for an .exe built without jpackage. jpackage's own output is just this prebuilt file renamed, next
     * to app/&lt;name&gt;.cfg and runtime/, so the same layout runs identically on any host that assembles it. It comes
     * from the target JDK's jmods, or for host exports from the copy staged in the packaged editor.
     */
    private byte[] windowsLauncher() throws IOException {
        File jmods = jmodsDir(req.targetJdk);
        if (jmods != null) {
            File jmod = new File(jmods, "jdk.jpackage.jmod");
            if (!jmod.isFile()) throw new IOException("No jdk.jpackage.jmod in " + jmods + ": it provides the Windows .exe launcher");
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jmod)) { // A jmod is a zip after a 4-byte magic
                java.util.zip.ZipEntry entry = zip.getEntry(WINDOWS_LAUNCHER_ENTRY);
                if (entry == null) throw new IOException(jmod + " has no Windows launcher: pick a Windows JDK");
                try (var in = zip.getInputStream(entry)) {
                    return in.readAllBytes();
                }
            }
        }
        String staged = System.getProperty(GAME_LAUNCHER_PROPERTY);
        if (staged != null && new File(staged.trim()).isFile()) return Files.readAllBytes(new File(staged.trim()).toPath());
        throw new IOException("No Windows launcher in this editor build: repackage it with ./gradlew packageEditor, or install "
            + "JDK 25 and set JAVA_HOME (or -D" + JDK_PROPERTY + "=<jdk>)");
    }

    /** The launcher's config, as jpackage writes it. --runtime is only the default, and is dropped if args are passed. */
    private String windowsLauncherConfig(File appDir) throws IOException {
        File[] jars = appDir.listFiles((d, n) -> n.endsWith(".jar") && !n.equals(ENGINE_JAR));
        if (jars == null) throw new IOException("Cannot list " + appDir);
        java.util.Arrays.sort(jars);
        List<String> lines = new ArrayList<>(List.of("[Application]", "app.classpath=$APPDIR\\" + ENGINE_JAR));
        for (File jar : jars) lines.add("app.classpath=$APPDIR\\" + jar.getName());
        lines.add("app.mainclass=" + Main.class.getName());
        lines.add("");
        lines.add("[JavaOptions]");
        for (String opt : jvmOptions("$APPDIR")) lines.add("java-options=" + opt);
        lines.add("");
        lines.add("[ArgOptions]");
        lines.add("arguments=--runtime");
        return String.join("\r\n", lines) + "\r\n";
    }

    /** {@code launcher} with the configured icon embedded. A launcher that can't take the icon still runs, so ship it as is. */
    private byte[] withIcon(byte[] launcher) throws IOException {
        if (req.iconFile == null) return launcher;
        try {
            return WindowsExeIcon.embed(launcher, Files.readAllBytes(req.iconFile.toPath()));
        } catch (IOException e) {
            System.err.println("[Exporter] Could not embed " + req.iconFile.getName() + ", keeping the default icon: " + e.getMessage());
            return launcher;
        }
    }

    private String shellOptions(String appDirVar) throws IOException {
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

    /** Looks for a JDK tool in -Dretro.jdk, the running JDK, then JAVA_HOME; null if none has it. */
    private static File findJdkTool(String name) {
        String suffix = Platform.host() == Platform.WINDOWS ? ".exe" : "";
        for (String home : new String[] { System.getProperty(JDK_PROPERTY), System.getProperty("java.home"), System.getenv("JAVA_HOME") }) {
            if (home == null || home.isBlank()) continue;
            File tool = new File(home.trim(), "bin/" + name + suffix);
            if (tool.isFile()) return tool;
        }
        return null;
    }

    private static File jdkTool(String name) throws IOException {
        File tool = findJdkTool(name);
        if (tool == null) throw new IOException(name + " not found: install JDK 25 and set JAVA_HOME (or -D" + JDK_PROPERTY + "=<jdk>)");
        return tool;
    }

    /**
     * Folder holding {@code jdk}'s .jmod files: its jmods/ subfolder, or {@code jdk} itself for a standalone jmods
     * package. Null when it has neither.
     */
    public static File jmodsDir(File jdk) {
        if (jdk == null) return null;
        File nested = new File(jdk, "jmods");
        if (nested.isDirectory()) return nested;
        return new File(jdk, "java.base.jmod").isFile() ? jdk : null;
    }

    /** The host JRE shipped inside the packaged editor (must contain bin/java), or null when running from source. */
    private static File bundledGameRuntime() {
        String path = System.getProperty(GAME_RUNTIME_PROPERTY);
        if (path == null || path.isBlank()) return null;
        File dir = new File(path.trim());
        String java = Platform.host() == Platform.WINDOWS ? "bin/javaw.exe" : "bin/java";
        return new File(dir, java).isFile() ? dir : null;
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
        StringBuilder shown = new StringBuilder("[Exporter] $");
        for (String arg : cmd) shown.append(' ').append(arg.indexOf(' ') >= 0 ? '"' + arg + '"' : arg);
        System.out.println(shown);
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

    /**
     * Best guess for the haptics default: a script calls vibrate / setVibration, or a scene or prefab has a Character
     * Controller (its solver rumbles on jump and landing). Scenes store component type names as plain ASCII.
     */
    public static boolean projectUsesHaptics(File projectDir) {
        Path assets = new File(projectDir, AssetDatabase.ASSETS_DIR).toPath();
        if (!Files.isDirectory(assets)) return false;
        byte[] controllerTag = "CharacterController".getBytes(StandardCharsets.US_ASCII);
        try (var stream = Files.walk(assets)) {
            return stream.filter(Files::isRegularFile).anyMatch(p -> {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                try {
                    if (name.endsWith(".java")) return HAPTICS_CALL.matcher(Files.readString(p)).find();
                    if (name.endsWith(".rscene") || name.endsWith(".prefab") || name.endsWith(".prefab.bin")) {
                        return indexOf(Files.readAllBytes(p), controllerTag) >= 0;
                    }
                } catch (IOException | java.io.UncheckedIOException e) {
                    return true; // Unreadable: keep haptics rather than silently drop rumble
                }
                return false;
            });
        } catch (IOException | java.io.UncheckedIOException e) {
            return true;
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0, last = haystack.length - needle.length; i <= last; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
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
