package com.retro.engine.core;

import com.retro.engine.assets.AssetDatabase;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.scene.components.gameplay.ScriptComponent;
import com.retro.engine.scene.components.rendering.Light;
import com.retro.engine.scene.components.rendering.Material;
import com.retro.engine.scene.components.transform.Transform;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.serialization.SceneSerializer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Project directory layout, ProjectSettings.bin persistence, starter content, and the
 * open/create entry points used by the project launcher and the --project CLI flag.
 *
 * Layout:
 * <pre>
 *   &lt;Project&gt;/ProjectSettings.bin
 *   &lt;Project&gt;/Assets/
 *   &lt;Project&gt;/Cooked/
 *   &lt;Project&gt;/.retro/asset_registry.bin
 * </pre>
 */
public final class ProjectWorkspace {

    public static final String SETTINGS_FILE = "ProjectSettings.bin";
    public static final String ENGINE_VERSION = "1.0.0";
    public static final String STARTER_SCENE = "Assets/Scenes/Main.rscene";

    private static final int SETTINGS_MAGIC = 0x5250524A; // 'RPRJ'
    private static final int SETTINGS_VERSION = 1;

    private static final String STARTER_RESOURCE_ROOT = "/starter/";
    private static final String[] STARTER_RESOURCES = {
        "Meshes/Cube.obj",
        "Meshes/Plane.obj",
        "Meshes/Sphere.obj",
        "Materials/Default.rmat",
        "Scripts/StarterRotateScript.java"
    };

    private ProjectWorkspace() {}

    public static final class ProjectSettings {
        public String projectName;
        public String engineVersion;
        public long createdAtMillis;
        public long lastOpenedAtMillis;
        /** Project-relative scene loaded when the project opens; empty for none. */
        public String startupScene;

        public ProjectSettings(String projectName, String startupScene) {
            this.projectName = projectName;
            this.engineVersion = ENGINE_VERSION;
            this.createdAtMillis = System.currentTimeMillis();
            this.lastOpenedAtMillis = createdAtMillis;
            this.startupScene = (startupScene != null) ? startupScene : "";
        }
    }

    // --- Settings I/O ---

    public static boolean isProjectDirectory(File dir) {
        return dir != null && new File(dir, SETTINGS_FILE).isFile();
    }

    public static void writeSettings(File projectDir, ProjectSettings settings) throws IOException {
        File file = new File(projectDir, SETTINGS_FILE);
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file.toPath())))) {
            dos.writeInt(SETTINGS_MAGIC);
            dos.writeInt(SETTINGS_VERSION);
            dos.writeUTF(settings.projectName != null ? settings.projectName : projectDir.getName());
            dos.writeUTF(settings.engineVersion != null ? settings.engineVersion : ENGINE_VERSION);
            dos.writeLong(settings.createdAtMillis);
            dos.writeLong(settings.lastOpenedAtMillis);
            dos.writeUTF(settings.startupScene != null ? settings.startupScene : "");
        }
    }

    public static ProjectSettings readSettings(File projectDir) throws IOException {
        File file = new File(projectDir, SETTINGS_FILE);
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(file.toPath())))) {
            if (dis.readInt() != SETTINGS_MAGIC) throw new IOException("Not a RetroEngine ProjectSettings.bin: " + file);
            int version = dis.readInt();
            if (version > SETTINGS_VERSION) throw new IOException("ProjectSettings.bin version " + version + " is newer than this editor supports");
            ProjectSettings s = new ProjectSettings(dis.readUTF(), "");
            s.engineVersion = dis.readUTF();
            s.createdAtMillis = dis.readLong();
            s.lastOpenedAtMillis = dis.readLong();
            s.startupScene = dis.readUTF();
            return s;
        }
    }

    // --- Create / Open ---

    /** Creates Assets/, Cooked/, and .retro/ under projectDir and writes an initial ProjectSettings.bin. */
    public static void initializeLayout(File projectDir, String projectName, String startupScene) throws IOException {
        for (String sub : new String[] { AssetDatabase.ASSETS_DIR, AssetDatabase.COOKED_DIR, AssetDatabase.INTERNAL_DIR }) {
            File d = new File(projectDir, sub);
            if (!d.isDirectory() && !d.mkdirs()) throw new IOException("Could not create " + d.getAbsolutePath());
        }
        writeSettings(projectDir, new ProjectSettings(projectName, startupScene));
    }

    /**
     * Creates a new project at parentDir/projectName, optionally seeding starter content,
     * then binds the editor to it. Returns the project directory.
     */
    public static File createProject(EditorWorkspace workspace, File parentDir, String projectName, boolean includeStarterContent) throws IOException {
        String cleanName = projectName.trim();
        File projectDir = new File(parentDir, cleanName).getAbsoluteFile();
        if (projectDir.exists()) {
            String[] contents = projectDir.list();
            if (!projectDir.isDirectory() || (contents != null && contents.length > 0)) {
                throw new IOException("Target folder already exists and is not empty: " + projectDir.getAbsolutePath());
            }
        }
        if (!projectDir.isDirectory() && !projectDir.mkdirs()) {
            throw new IOException("Could not create project folder: " + projectDir.getAbsolutePath());
        }

        // Every project opens into Assets/Scenes/Main.rscene; without starter content it is simply empty
        initializeLayout(projectDir, cleanName, STARTER_SCENE);
        if (includeStarterContent) {
            copyStarterAssets(new File(projectDir, AssetDatabase.ASSETS_DIR));
        }

        workspace.getSceneManager().newScene();
        workspace.getCommandHistory().clear();
        if (!workspace.getAssetDatabase().openProject(projectDir.getAbsolutePath())) {
            throw new IOException("Could not bind asset registry to " + projectDir.getAbsolutePath());
        }
        if (includeStarterContent) {
            writeStarterScene(projectDir, workspace.getAssetDatabase());
        } else {
            writeBlankScene(projectDir, workspace.getAssetDatabase());
        }
        loadStartupScene(workspace, projectDir, readSettings(projectDir));
        System.out.println("[Project] Created project '" + cleanName + "' at " + projectDir.getAbsolutePath()
            + (includeStarterContent ? " with starter content." : "."));
        return projectDir;
    }

    /**
     * Opens an existing project: loads ProjectSettings.bin, binds the AssetDatabase (clear + manifest restore + scan),
     * and loads the startup scene if one is configured.
     */
    public static void openProject(EditorWorkspace workspace, File projectDir) throws IOException {
        File dir = projectDir.getAbsoluteFile();
        ProjectSettings settings = readSettings(dir);
        settings.lastOpenedAtMillis = System.currentTimeMillis();
        writeSettings(dir, settings);

        workspace.getSceneManager().newScene();
        workspace.getCommandHistory().clear();
        if (!workspace.getAssetDatabase().openProject(dir.getAbsolutePath())) {
            throw new IOException("Could not bind asset registry to " + dir.getAbsolutePath());
        }
        loadStartupScene(workspace, dir, settings);
        System.out.println("[Project] Opened project '" + settings.projectName + "' (" + dir.getAbsolutePath() + ")");
    }

    private static void loadStartupScene(EditorWorkspace workspace, File projectDir, ProjectSettings settings) {
        if (settings.startupScene == null || settings.startupScene.isEmpty()) return;
        File scene = new File(projectDir, settings.startupScene);
        if (scene.isFile()) {
            workspace.getSceneManager().loadSceneFromFile(scene.getAbsolutePath(), workspace.getAssetDatabase());
        }
    }

    // --- Starter Content ---

    private static void copyStarterAssets(File assetsDir) throws IOException {
        for (String rel : STARTER_RESOURCES) {
            File target = new File(assetsDir, rel);
            target.getParentFile().mkdirs();
            try (InputStream in = ProjectWorkspace.class.getResourceAsStream(STARTER_RESOURCE_ROOT + rel)) {
                if (in == null) throw new IOException("Missing bundled starter resource: " + rel);
                Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        writeCheckerTexture(new File(assetsDir, "Textures/Checker.png"));
        new File(assetsDir, "Scenes").mkdirs();
    }

    private static void writeCheckerTexture(File target) throws IOException {
        target.getParentFile().mkdirs();
        final int size = 64, cell = 8;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean light = ((x / cell) + (y / cell)) % 2 == 0;
                img.setRGB(x, y, light ? 0xC8C8D0 : 0x5E5E6E);
            }
        }
        if (!ImageIO.write(img, "png", target)) throw new IOException("No PNG writer available for " + target);
    }

    private static void writeBlankScene(File projectDir, AssetDatabase assetDb) throws IOException {
        File sceneFile = new File(projectDir, STARTER_SCENE);
        if (!SceneSerializer.saveScene(sceneFile.getAbsolutePath(), new ArrayList<>(), new ArrayList<>(), assetDb)) {
            throw new IOException("Failed to write " + sceneFile);
        }
        assetDb.rescanProjectAssets();
    }

    /** Main.rscene: a checker-textured spinning cube on a ground plane, lit by one directional light. */
    private static void writeStarterScene(File projectDir, AssetDatabase assetDb) throws IOException {
        File assets = new File(projectDir, AssetDatabase.ASSETS_DIR);
        String cube = new File(assets, "Meshes/Cube.obj").getAbsolutePath();
        String plane = new File(assets, "Meshes/Plane.obj").getAbsolutePath();
        String checker = new File(assets, "Textures/Checker.png").getAbsolutePath();
        String script = new File(assets, "Scripts/StarterRotateScript.java").getAbsolutePath();

        List<Entity> entities = new ArrayList<>();

        Entity ground = new Entity("entity_1", "Ground", plane);
        Material groundMat = ground.getComponent(Material.class);
        if (groundMat != null) groundMat.textureAssetPath = checker;
        entities.add(ground);

        Entity cubeEntity = new Entity("entity_2", "Cube", cube);
        Transform t = cubeEntity.getComponent(Transform.class);
        if (t != null) t.posY = 0.5f;
        Material cubeMat = cubeEntity.getComponent(Material.class);
        if (cubeMat != null) cubeMat.textureAssetPath = checker;
        cubeEntity.addComponent(new ScriptComponent(script, "StarterRotateScript.java"));
        entities.add(cubeEntity);

        Entity light = new Entity("light_1", "DirectionalLight", null);
        light.addComponent(new Light());
        Transform lt = light.getComponent(Transform.class);
        if (lt != null) { lt.posY = 5.0f; lt.rotX = -50.0f; lt.rotY = 30.0f; }
        entities.add(light);

        File sceneFile = new File(projectDir, STARTER_SCENE);
        if (!SceneSerializer.saveScene(sceneFile.getAbsolutePath(), entities, new ArrayList<>(), assetDb)) {
            throw new IOException("Failed to write starter scene " + sceneFile);
        }
        assetDb.rescanProjectAssets();
    }
}
