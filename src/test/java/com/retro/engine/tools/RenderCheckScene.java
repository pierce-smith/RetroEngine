package com.retro.engine.tools;

import com.retro.engine.core.ProjectSettings;
import com.retro.engine.core.ProjectWorkspace;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.Camera;
import com.retro.engine.ecs.components.Light;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.ecs.components.UIPanel;
import com.retro.engine.ecs.components.UIText;
import com.retro.engine.ecs.serialization.SceneSerializer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Writes the test project's Assets/Scenes/RenderCheck.rscene and its textures, for judging the renderer by eye (Volume 5
 * §3.1). Scenes are binary, so this is the readable source. Rerun it from the repo root to change the scene, then commit
 * what it writes.
 */
public final class RenderCheckScene {

    public static final int GRID = 40;
    public static final String PROJECT = "src/test/resources/TestProject";
    public static final String SCENE = "Assets/Scenes/RenderCheck.rscene";
    private static final float SPACING = 2.0f;
    private static final String CUBE = "Assets/Meshes/Cube.obj", PLANE = "Assets/Meshes/Plane.obj", ARMS = "Assets/Meshes/arms_rig.glb";
    private static final String CUBE_TEXTURE = "Assets/Textures/RenderCheckCube.png", FLOOR_TEXTURE = "Assets/Textures/RenderCheckFloor.png";

    private RenderCheckScene() {}

    public static List<Entity> build() {
        List<Entity> entities = new ArrayList<>();

        Entity sun = new Entity("rc_sun", "Sun", null);
        Light light = new Light();
        sun.addComponent(light);
        Transform sunTransform = sun.getTransform();
        sunTransform.posY = 60.0f;
        sunTransform.rotX = 55.0f;
        sunTransform.rotY = 35.0f;
        entities.add(sun);

        Entity camera = new Entity("rc_camera", "Main Camera", null);
        camera.addComponent(new Camera());
        camera.addComponent(new ScriptComponent("Assets/Scripts/RenderCheckScript.java", "RenderCheckScript.java"));
        camera.getTransform().posY = 48.0f;
        camera.getTransform().posZ = 72.0f;
        entities.add(camera);

        addPanel(entities, camera, "HUD Panel", 0.5f, 0.9f, 1120.0f, 150.0f);
        addText(entities, camera, "HUD Title", 0.5f, 0.945f, 34.0f);
        addText(entities, camera, "HUD Hint", 0.5f, 0.895f, 22.0f);
        addText(entities, camera, "HUD Status", 0.5f, 0.855f, 20.0f);

        Entity floor = new Entity("rc_floor", "Floor", PLANE);
        floor.setTextureAsset(FLOOR_TEXTURE, "RenderCheckFloor.png");
        floor.getTransform().scaleX = 60.0f;
        floor.getTransform().scaleY = 0.01f;
        floor.getTransform().scaleZ = 60.0f;
        entities.add(floor);

        // Skinned meshes sort after static ones, so the old 256-draw limit dropped these first
        Entity arms = new Entity("rc_arms", "Arms", ARMS);
        arms.setTextureAsset("Assets/Textures/arms_01.png", "arms_01.png");
        arms.getTransform().posY = -6.0f;
        arms.getTransform().scaleX = 12.0f;
        arms.getTransform().scaleY = 12.0f;
        arms.getTransform().scaleZ = 12.0f;
        entities.add(arms);

        float half = (GRID - 1) * SPACING * 0.5f;
        for (int z = 0; z < GRID; z++) {
            for (int x = 0; x < GRID; x++) {
                Entity cube = new Entity("rc_cube_" + x + "_" + z, "Cube " + x + "," + z, CUBE);
                cube.setTextureAsset(CUBE_TEXTURE, "RenderCheckCube.png");
                Transform t = cube.getTransform();
                t.posX = x * SPACING - half;
                t.posY = 2.0f;
                t.posZ = z * SPACING - half;
                entities.add(cube);
            }
        }
        return entities;
    }

    private static void addPanel(List<Entity> entities, Entity camera, String name, float x, float y, float w, float h) {
        Entity e = child(camera, name);
        UIPanel panel = new UIPanel();
        panel.anchorX = x;
        panel.anchorY = y;
        panel.width = w;
        panel.height = h;
        panel.colorR = 0.04f;
        panel.colorG = 0.04f;
        panel.colorB = 0.07f;
        panel.alpha = 0.75f;
        panel.cornerRadius = 8.0f;
        panel.sortOrder = -1.0f;
        e.addComponent(panel);
        entities.add(e);
    }

    private static void addText(List<Entity> entities, Entity camera, String name, float x, float y, float size) {
        Entity e = child(camera, name);
        UIText text = new UIText();
        text.anchorX = x;
        text.anchorY = y;
        text.fontSize = size;
        text.width = 1100.0f;
        text.height = size * 1.5f;
        text.text = "";
        e.addComponent(text);
        entities.add(e);
    }

    private static Entity child(Entity parent, String name) {
        Entity e = new Entity("rc_" + name.toLowerCase().replace(' ', '_'), name, null);
        e.parentId = parent.id;
        parent.addChildId(e.id);
        return e;
    }

    /** Bright with a dark edge, so individual cubes read clearly in a big grid. */
    private static void writeCubeTexture(File target) throws IOException {
        int size = 64;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean edge = x < 4 || y < 4 || x >= size - 4 || y >= size - 4;
                image.setRGB(x, y, edge ? 0x3A2A10 : 0xF0A020);
            }
        }
        ImageIO.write(image, "png", target);
    }

    /** A very fine checker: the worst case for a texture with no mipmaps. */
    private static void writeFloorTexture(File target) throws IOException {
        int size = 2048, cell = 4;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, ((x / cell) + (y / cell)) % 2 == 0 ? 0xE8E8E8 : 0x181818);
            }
        }
        ImageIO.write(image, "png", target);
    }

    private static void copyResource(String resource, String target) throws IOException {
        try (InputStream in = RenderCheckScene.class.getResourceAsStream(resource)) {
            Files.copy(in, new File(PROJECT, target).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void main(String[] args) throws IOException {
        copyResource("/starter/Meshes/Cube.obj", CUBE);
        copyResource("/starter/Meshes/Plane.obj", PLANE);
        writeCubeTexture(new File(PROJECT, CUBE_TEXTURE));
        writeFloorTexture(new File(PROJECT, FLOOR_TEXTURE));
        if (!SceneSerializer.saveScene(new File(PROJECT, SCENE).getPath(), build())) throw new IOException("Could not write " + SCENE);

        // Opening the project then lands straight on this scene, ready for Play. Blobs are what station 2 checks, and fog
        // would hide the far side of the grid
        File project = new File(PROJECT).getCanonicalFile();
        ProjectSettings settings = ProjectWorkspace.readSettings(project);
        settings.startupScene = SCENE;
        settings.shadowBlobsEnabled = true;
        settings.fogEnabled = false;
        ProjectWorkspace.writeSettings(project, settings);
        System.out.println("Wrote " + SCENE + " and set up the project to open on it.");
    }
}
