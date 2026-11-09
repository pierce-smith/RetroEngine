package com.retro.engine.tools;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.Camera;
import com.retro.engine.ecs.components.CapsuleCollider;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.ecs.serialization.PrefabSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the Third Person Example template's ThirdPersonController prefab. It is binary, so this is the readable source.
 * Rerun it from the repo root to change it, then commit what it writes.
 */
public final class ThirdPersonScene {

    /** Where a project created from the template holds the prefab. */
    public static final String PREFAB = "Assets/Prefabs/ThirdPersonController.rprefab";
    private static final String TEMPLATE_PREFAB = "src/main/resources/templates/third_person_example/Prefabs/ThirdPersonController.rprefab";
    private static final String HERO = "Assets/Meshes/hero_character.glb", HERO_TEXTURE = "Assets/Textures/hero_character_diffuse.png";

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

    private static Entity child(Entity parent, String id, String name, String mesh) {
        Entity e = new Entity(id, name, mesh);
        e.parentId = parent.id;
        parent.addChildId(e.id);
        return e;
    }

    public static void main(String[] args) throws IOException {
        List<Entity> controller = buildController();
        if (!PrefabSerializer.savePrefab(TEMPLATE_PREFAB, controller.get(0), controller, null)) throw new IOException("Could not write " + TEMPLATE_PREFAB);
        System.out.println("Wrote " + TEMPLATE_PREFAB);
    }
}
