package com.retro.engine.tools;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.Camera;
import com.retro.engine.ecs.components.CapsuleCollider;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.Light;
import com.retro.engine.ecs.components.RigidBodyComponent;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.ecs.serialization.PrefabSerializer;
import com.retro.engine.ecs.serialization.SceneSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes Assets/Prefabs/ThirdPersonController.prefab.bin and a small Assets/Scenes/ThirdPerson.rscene to try it in.
 * Both are binary, so this is the readable source. Rerun it from the repo root to change them, then commit what it writes.
 */
public final class ThirdPersonScene {

    public static final String PREFAB = "Assets/Prefabs/ThirdPersonController.prefab.bin";
    public static final String SCENE = "Assets/Scenes/ThirdPerson.rscene";
    private static final String CUBE = "Assets/Meshes/Cube.obj", PLANE = "Assets/Meshes/Plane.obj";
    private static final String HERO = "Assets/Meshes/hero_character.glb", HERO_TEXTURE = "Assets/Textures/hero_character_diffuse.png";
    private static final String FLOOR_TEXTURE = "Assets/Textures/RenderCheckFloor.png", BLOCK_TEXTURE = "Assets/Textures/RenderCheckCube.png";

    private static final float CAPSULE_HEIGHT = 1.8f, CAPSULE_RADIUS = 0.35f;
    // The orbit pivot sits at chest height; the camera looks down at it from behind
    private static final float PIVOT_HEIGHT = 0.5f, CAMERA_DISTANCE = 3.2f, CAMERA_PITCH = 12.0f;

    private ThirdPersonScene() {}

    /** Player root first, then its children. The root stays unrotated; the Hero child faces the -Z the camera looks down. */
    public static List<Entity> buildController() {
        List<Entity> entities = new ArrayList<>();

        Entity player = new Entity("tp_player", "Player", null);
        CapsuleCollider capsule = new CapsuleCollider();
        capsule.radius = CAPSULE_RADIUS;
        capsule.height = CAPSULE_HEIGHT;
        capsule.offsetY = 0.0f;
        player.addComponent(capsule);
        player.addComponent(new CharacterControllerComponent());
        player.addComponent(new ScriptComponent("Assets/Scripts/ThirdPersonScript.java", "ThirdPersonScript.java"));
        entities.add(player);

        Entity camera = child(player, "tp_camera", "Main Camera", null);
        Camera lens = new Camera();
        lens.isPrimary = true;
        lens.fov = 65.0f;
        camera.addComponent(lens);
        double pitch = Math.toRadians(CAMERA_PITCH);
        Transform view = camera.getTransform();
        view.posY = PIVOT_HEIGHT + CAMERA_DISTANCE * (float) Math.sin(pitch);
        view.posZ = CAMERA_DISTANCE * (float) Math.cos(pitch);
        view.rotX = CAMERA_PITCH;
        entities.add(camera);

        Entity hero = child(player, "tp_hero", "Hero", HERO);
        hero.setTextureAsset(HERO_TEXTURE, "hero_character_diffuse.png");
        hero.getTransform().posY = -CAPSULE_HEIGHT * 0.5f; // the mesh's origin is at its feet
        hero.getTransform().rotY = 180.0f;
        entities.add(hero);
        return entities;
    }

    public static List<Entity> buildScene() {
        List<Entity> entities = new ArrayList<>(buildController());
        entities.get(0).getTransform().posY = CAPSULE_HEIGHT * 0.5f + 0.05f;

        Entity sun = new Entity("tp_sun", "Sun", null);
        sun.addComponent(new Light());
        sun.getTransform().posY = 20.0f;
        sun.getTransform().rotX = 55.0f;
        sun.getTransform().rotY = 35.0f;
        entities.add(sun);

        Entity floor = new Entity("tp_floor", "Floor", PLANE);
        floor.setTextureAsset(FLOOR_TEXTURE, "RenderCheckFloor.png");
        floor.getTransform().scaleX = 8.0f;
        floor.getTransform().scaleZ = 8.0f;
        BoxCollider slab = new BoxCollider();
        slab.sizeX = 10.0f;
        slab.sizeY = 0.2f;
        slab.sizeZ = 10.0f;
        floor.addComponent(slab);
        floor.addComponent(staticBody());
        entities.add(floor);

        // Things to run around, and to back the camera into
        block(entities, "Wall", 0.0f, 1.5f, -12.0f, 16.0f, 3.0f, 1.0f);
        block(entities, "Pillar A", -6.0f, 1.5f, -4.0f, 1.5f, 3.0f, 1.5f);
        block(entities, "Pillar B", 6.0f, 1.5f, -4.0f, 1.5f, 3.0f, 1.5f);
        block(entities, "Crate", 3.0f, 0.5f, 4.0f, 1.0f, 1.0f, 1.0f);
        return entities;
    }

    private static void block(List<Entity> entities, String name, float x, float y, float z, float sx, float sy, float sz) {
        Entity e = new Entity("tp_" + name.toLowerCase().replace(' ', '_'), name, CUBE);
        e.setTextureAsset(BLOCK_TEXTURE, "RenderCheckCube.png");
        Transform t = e.getTransform();
        t.posX = x;
        t.posY = y;
        t.posZ = z;
        t.scaleX = sx;
        t.scaleY = sy;
        t.scaleZ = sz;
        e.addComponent(new BoxCollider());
        e.addComponent(staticBody());
        entities.add(e);
    }

    private static RigidBodyComponent staticBody() {
        RigidBodyComponent body = new RigidBodyComponent();
        body.motionType = RigidBodyComponent.MotionType.STATIC;
        return body;
    }

    private static Entity child(Entity parent, String id, String name, String mesh) {
        Entity e = new Entity(id, name, mesh);
        e.parentId = parent.id;
        parent.addChildId(e.id);
        return e;
    }

    public static void main(String[] args) throws IOException {
        List<Entity> controller = buildController();
        if (!PrefabSerializer.savePrefab(PREFAB, controller.get(0), controller, null)) throw new IOException("Could not write " + PREFAB);
        if (!SceneSerializer.saveScene(SCENE, buildScene())) throw new IOException("Could not write " + SCENE);
        System.out.println("Wrote " + PREFAB + " and " + SCENE);
    }
}
