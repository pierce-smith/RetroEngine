package com.retro.engine.tools;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.Camera;
import com.retro.engine.ecs.components.Light;
import com.retro.engine.ecs.components.NavMeshAgent;
import com.retro.engine.ecs.components.ParticleSystem;
import com.retro.engine.ecs.components.RigidBodyComponent;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.ecs.serialization.SceneSerializer;
import com.retro.engine.navigation.NavMesh;
import com.retro.engine.navigation.NavMeshBaker;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Writes the test project's Assets/Scenes/Bench*.rscene files, the fixed scenes of the Volume 5 §3.2 benchmark protocol.
 * Scenes are binary, so this is the readable source. Rerun it from the repo root to change them, then commit what it
 * writes.
 */
public final class BenchmarkScenes {

    public static final String SCENE_DIR = "Assets/Scenes/";
    public static final int STATIC_MESHES = 1000;
    public static final int POINT_LIGHTS = 32, SPOT_LIGHTS = 8;
    public static final int PHYSICS_BOXES = 500;
    public static final int SKINNED_CHARACTERS = 64;
    public static final int PARTICLE_SYSTEMS = 32, PARTICLES_PER_SYSTEM = 4096;
    public static final int NAV_AGENTS = 100;

    private static final String CUBE = "Assets/Meshes/Cube.obj", PLANE = "Assets/Meshes/Plane.obj", ARMS = "Assets/Meshes/arms_rig.glb";
    private static final String CUBE_TEXTURE = "Assets/Textures/RenderCheckCube.png";
    private static final String CAMERA_SCRIPT = "BenchCameraScript.java", AGENT_SCRIPT = "BenchAgentScript.java";
    private static final float FLOOR_HALF = 50.0f;

    private BenchmarkScenes() {}

    /** Scene file name to builder, in Volume 5 order. */
    public static Map<String, Supplier<List<Entity>>> scenes() {
        Map<String, Supplier<List<Entity>>> scenes = new LinkedHashMap<>();
        scenes.put("BenchEmpty.rscene", BenchmarkScenes::empty);
        scenes.put("BenchStatic.rscene", BenchmarkScenes::staticLoad);
        scenes.put("BenchLights.rscene", BenchmarkScenes::lightLoad);
        scenes.put("BenchPhysics.rscene", BenchmarkScenes::physicsLoad);
        scenes.put("BenchSkinning.rscene", BenchmarkScenes::skinningLoad);
        scenes.put("BenchParticles.rscene", BenchmarkScenes::particleLoad);
        scenes.put("BenchNavigation.rscene", BenchmarkScenes::navigationLoad);
        return scenes;
    }

    private static List<Entity> empty() {
        List<Entity> entities = new ArrayList<>();
        addSun(entities);
        addCamera(entities, 30.0f, 45.0f);
        return entities;
    }

    private static List<Entity> staticLoad() {
        List<Entity> entities = empty();
        int side = (int) Math.ceil(Math.sqrt(STATIC_MESHES));
        for (int i = 0; i < STATIC_MESHES; i++) {
            float x = (i % side - (side - 1) * 0.5f) * 2.0f, z = (i / side - (side - 1) * 0.5f) * 2.0f;
            entities.add(cube("static_" + i, x, 0.5f, z));
        }
        return entities;
    }

    private static List<Entity> lightLoad() {
        List<Entity> entities = empty();
        addFloor(entities, false);
        for (int i = 0; i < 100; i++) {
            entities.add(cube("lit_" + i, (i % 10 - 4.5f) * 8.0f, 0.5f, (i / 10 - 4.5f) * 8.0f));
        }
        for (int i = 0; i < POINT_LIGHTS + SPOT_LIGHTS; i++) {
            boolean spot = i >= POINT_LIGHTS;
            Entity e = new Entity("light_" + i, (spot ? "Spot " : "Point ") + i, null);
            Light light = new Light();
            light.type = spot ? Light.LightType.SPOT : Light.LightType.POINT;
            light.r = (i % 3) == 0 ? 1.0f : 0.3f;
            light.g = (i % 3) == 1 ? 1.0f : 0.3f;
            light.b = (i % 3) == 2 ? 1.0f : 0.3f;
            e.addComponent(light);
            Transform t = e.getTransform();
            t.posX = (i % 8 - 3.5f) * 10.0f;
            t.posY = 4.0f;
            t.posZ = (i / 8 - 2.0f) * 14.0f;
            if (spot) t.rotX = -90.0f;
            entities.add(e);
        }
        return entities;
    }

    private static List<Entity> physicsLoad() {
        List<Entity> entities = empty();
        addFloor(entities, true);
        // A loose grid that falls and piles up, so the broadphase and solver both have work for the whole run
        for (int i = 0; i < PHYSICS_BOXES; i++) {
            int layer = i / 100, inLayer = i % 100;
            Entity box = cube("box_" + i, (inLayer % 10 - 4.5f) * 1.5f + layer * 0.3f, 3.0f + layer * 2.0f, (inLayer / 10 - 4.5f) * 1.5f + layer * 0.3f);
            box.addComponent(new BoxCollider());
            box.addComponent(new RigidBodyComponent());
            entities.add(box);
        }
        return entities;
    }

    private static List<Entity> skinningLoad() {
        List<Entity> entities = empty();
        int side = (int) Math.ceil(Math.sqrt(SKINNED_CHARACTERS));
        for (int i = 0; i < SKINNED_CHARACTERS; i++) {
            Entity arms = new Entity("skinned_" + i, "Skinned " + i, ARMS);
            arms.setTextureAsset("Assets/Textures/arms_01.png", "arms_01.png");
            Transform t = arms.getTransform();
            t.posX = (i % side - (side - 1) * 0.5f) * 5.0f;
            t.posZ = (i / side - (side - 1) * 0.5f) * 5.0f;
            t.scaleX = 3.0f;
            t.scaleY = 3.0f;
            t.scaleZ = 3.0f;
            entities.add(arms);
        }
        return entities;
    }

    private static List<Entity> particleLoad() {
        List<Entity> entities = empty();
        for (int i = 0; i < PARTICLE_SYSTEMS; i++) {
            Entity e = new Entity("particles_" + i, "Particles " + i, null);
            ParticleSystem particles = new ParticleSystem();
            particles.maxParticles = PARTICLES_PER_SYSTEM;
            particles.lifetime = 2.0f;
            particles.emissionRate = PARTICLES_PER_SYSTEM / particles.lifetime; // Holds the pool full
            particles.blendMode = i % 2 == 0 ? ParticleSystem.BlendMode.ADDITIVE : ParticleSystem.BlendMode.ALPHA;
            e.addComponent(particles);
            e.getTransform().posX = (i % 8 - 3.5f) * 6.0f;
            e.getTransform().posZ = (i / 8 - 1.5f) * 6.0f;
            entities.add(e);
        }
        return entities;
    }

    private static List<Entity> navigationLoad() {
        List<Entity> entities = empty();
        addFloor(entities, true);
        // Solid blocks so paths have corners to find, in a pattern that leaves every part of the floor reachable
        for (int i = 0; i < 36; i++) {
            Entity block = cube("block_" + i, (i % 6 - 2.5f) * 14.0f, 1.0f, (i / 6 - 2.5f) * 14.0f);
            block.getTransform().scaleX = 6.0f;
            block.getTransform().scaleY = 2.0f;
            block.getTransform().scaleZ = 6.0f;
            block.addComponent(new BoxCollider());
            entities.add(block);
        }
        for (int i = 0; i < NAV_AGENTS; i++) {
            Entity agent = cube("agent_" + i, (i % 10 - 4.5f) * 14.0f + 7.0f, 0.5f, (i / 10 - 4.5f) * 14.0f + 7.0f);
            agent.addComponent(new NavMeshAgent());
            agent.addComponent(new ScriptComponent("Assets/Scripts/" + AGENT_SCRIPT, AGENT_SCRIPT));
            entities.add(agent);
        }
        return entities;
    }

    private static void addSun(List<Entity> entities) {
        Entity sun = new Entity("bench_sun", "Sun", null);
        sun.addComponent(new Light());
        sun.getTransform().posY = 60.0f;
        sun.getTransform().rotX = 55.0f;
        sun.getTransform().rotY = 35.0f;
        entities.add(sun);
    }

    private static void addCamera(List<Entity> entities, float height, float distance) {
        Entity camera = new Entity("bench_camera", "Main Camera", null);
        camera.addComponent(new Camera());
        camera.addComponent(new ScriptComponent("Assets/Scripts/" + CAMERA_SCRIPT, CAMERA_SCRIPT));
        camera.getTransform().posY = height;
        camera.getTransform().posZ = distance;
        entities.add(camera);
    }

    private static void addFloor(List<Entity> entities, boolean solid) {
        Entity floor = new Entity("bench_floor", "Floor", PLANE);
        floor.getTransform().scaleX = FLOOR_HALF / 5.0f; // Plane.obj is 10 units wide
        floor.getTransform().scaleZ = FLOOR_HALF / 5.0f;
        if (solid) {
            // A slab under the visible plane, with its top at y = 0. Sizes are in the entity's scaled space
            BoxCollider slab = new BoxCollider();
            slab.sizeX = 10.0f;
            slab.sizeY = 1.0f;
            slab.sizeZ = 10.0f;
            slab.offsetY = -0.5f;
            floor.addComponent(slab);
        }
        entities.add(floor);
    }

    private static Entity cube(String id, float x, float y, float z) {
        Entity cube = new Entity(id, id, CUBE);
        cube.setTextureAsset(CUBE_TEXTURE, "RenderCheckCube.png");
        cube.getTransform().posX = x;
        cube.getTransform().posY = y;
        cube.getTransform().posZ = z;
        return cube;
    }

    public static void main(String[] args) throws IOException {
        for (Map.Entry<String, Supplier<List<Entity>>> scene : scenes().entrySet()) {
            File file = new File(RenderCheckScene.PROJECT, SCENE_DIR + scene.getKey());
            List<Entity> entities = scene.getValue().get();
            if (!SceneSerializer.saveScene(file.getPath(), entities)) throw new IOException("Could not write " + file);
            if (scene.getKey().equals("BenchNavigation.rscene")) {
                NavMeshBaker.Settings settings = new NavMeshBaker.Settings();
                settings.cellSize = 0.5f; // Flat ground needs no detail, and this file is committed
                NavMeshBaker.Result baked = NavMeshBaker.bake(entities, new SceneWorld(), settings);
                if (baked.mesh() == null) throw new IOException("NavMesh bake failed: " + baked.message());
                baked.mesh().save(new File(NavMesh.pathForScene(file.getPath())));
                System.out.println(baked.message());
            }
            System.out.println("Wrote " + file + " (" + entities.size() + " entities)");
        }
    }
}
