package com.retro.engine.editor.templates;

import com.retro.engine.assets.AssetDatabase;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.AudioSource;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.Camera;
import com.retro.engine.ecs.components.CapsuleCollider;
import com.retro.engine.ecs.components.Light;
import com.retro.engine.ecs.components.Material;
import com.retro.engine.ecs.components.RigidBodyComponent;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.ecs.components.SphereCollider;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.ecs.components.UIPanel;
import com.retro.engine.ecs.components.UIProgressBar;
import com.retro.engine.ecs.components.UIText;
import com.retro.engine.ecs.components.VehicleControllerComponent;
import com.retro.engine.ecs.serialization.PrefabSerializer;
import com.retro.engine.ecs.serialization.SceneSerializer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registry of New Project baseline templates. Bundled templates live under /templates/&lt;id&gt;/ with a project.json
 * manifest (file list + scene descriptor) and are installed on top of the shared starter content (meshes, material,
 * checker texture). The template's text scene descriptor is baked into a binary .rscene once the project's
 * AssetDatabase is bound, since binary scenes reference per-project asset GUIDs.
 */
public final class TemplateManager {

    /** A selectable baseline. {@code resourceId} is null for templates built in code (Blank / Starter Content). */
    public record ProjectTemplate(String id, String displayName, String description, boolean starterContent, String resourceId) {
        public boolean isBundled() { return resourceId != null; }
    }

    public static final ProjectTemplate BLANK = new ProjectTemplate("blank", "Blank",
        "An empty Main.rscene with no assets.", false, null);
    public static final ProjectTemplate STARTER_CONTENT = new ProjectTemplate("starter_content", "Starter Content",
        "Primitive models, a default material and texture, a test script and a spinning-cube Main.rscene.", true, null);
    public static final ProjectTemplate FIRST_PERSON_EXAMPLE = new ProjectTemplate("first_person_example", "First Person Example",
        "Collect-a-thon microgame: FPS arms prefab on the CharacterController, punch/guard, collectibles, score, victory and restart.", true, "first_person_example");
    public static final ProjectTemplate INFINITE_DRIVER = new ProjectTemplate("infinite_driver", "Infinite Driver",
        "Endless highway microgame: steer a PSX car smoothly around procedurally spawned obstacles as the speed ramps up.", true, "infinite_driver");

    private static final List<ProjectTemplate> TEMPLATES = List.of(BLANK, STARTER_CONTENT, FIRST_PERSON_EXAMPLE, INFINITE_DRIVER);
    private static final String ROOT = "/templates/";

    private TemplateManager() {}

    public static List<ProjectTemplate> getTemplates() { return TEMPLATES; }

    public static ProjectTemplate byId(String id) {
        for (ProjectTemplate t : TEMPLATES) if (t.id().equals(id)) return t;
        return null;
    }

    public static ProjectTemplate byDisplayName(String name) {
        for (ProjectTemplate t : TEMPLATES) if (t.displayName().equalsIgnoreCase(name)) return t;
        return null;
    }

    /** Copies every file listed in the template manifest into assetsDir (paths are relative to Assets/). */
    public static void installFiles(ProjectTemplate template, File assetsDir) throws IOException {
        if (!template.isBundled()) return;
        for (String rel : manifestFiles(readManifest(template))) {
            File target = new File(assetsDir, rel);
            if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) {
                throw new IOException("Could not create " + target.getParentFile());
            }
            try (InputStream in = open(template, rel)) {
                Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Bakes the template's scene descriptor into a binary scene at sceneFile. Builds it inside {@code world} (prefabs
     * can only be instantiated into a live SceneWorld), saves, then clears the world again. Requires the new
     * project's AssetDatabase to be bound and scanned so prefab asset references resolve.
     */
    public static void writeScene(ProjectTemplate template, File assetsDir, File sceneFile, AssetDatabase assetDb, SceneWorld world) throws IOException {
        String descriptorName = jsonString(readManifest(template), "scene");
        if (descriptorName == null) throw new IOException("Template '" + template.id() + "' has no scene descriptor.");
        String text = new String(readAll(open(template, descriptorName)), StandardCharsets.UTF_8);
        if (text.startsWith("RETR")) {
            throw new IOException("Template scene '" + descriptorName + "' is a binary .rscene; it must be the text descriptor.");
        }
        world.newScene();
        try {
            List<Entity> built = parseScene(text, assetsDir, prefabFile -> PrefabSerializer.instantiatePrefab(prefabFile, world, assetDb));
            for (Entity e : built) world.addEntity(e, -1); // no-op for prefab entities, already added
            sceneFile.getParentFile().mkdirs();
            if (!SceneSerializer.saveScene(sceneFile.getAbsolutePath(), new ArrayList<>(world.getSceneEntities()), new ArrayList<>(), assetDb)) {
                throw new IOException("Failed to write template scene " + sceneFile);
            }
        } finally {
            world.newScene();
        }
        assetDb.rescanProjectAssets();
    }

    // --- Manifest / descriptor parsing ---

    private static String readManifest(ProjectTemplate template) throws IOException {
        return new String(readAll(open(template, "project.json")), StandardCharsets.UTF_8);
    }

    private static List<String> manifestFiles(String json) {
        List<String> out = new ArrayList<>();
        Matcher arr = Pattern.compile("\"files\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL).matcher(json);
        if (arr.find()) {
            Matcher item = Pattern.compile("\"([^\"]+)\"").matcher(arr.group(1));
            while (item.find()) out.add(item.group(1));
        }
        return out;
    }

    private static String jsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Builds descriptor entities. {@code prefabLoader} instantiates a prefab file and returns all its entities. */
    static List<Entity> parseScene(String text, File assetsDir, Function<String, List<Entity>> prefabLoader) throws IOException {
        List<Entity> entities = new ArrayList<>();
        java.util.Map<Entity, String> parentNames = new java.util.LinkedHashMap<>();
        int lineNo = 0;
        for (String raw : text.split("\\R")) {
            lineNo++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\|");
            String head = parts[0].trim();
            if (!head.startsWith("entity ")) throw new IOException("Scene descriptor line " + lineNo + ": expected 'entity <Name>'");
            String name = head.substring(7).trim();

            String mesh = null, prefab = null;
            for (int i = 1; i < parts.length; i++) {
                String p = parts[i].trim();
                if (p.startsWith("mesh=")) mesh = asset(assetsDir, p.substring(5));
                if (p.startsWith("prefab=")) prefab = asset(assetsDir, p.substring(7));
            }
            Entity e;
            if (prefab != null) {
                if (prefabLoader == null) throw new IOException("Scene descriptor line " + lineNo + ": prefabs need a SceneWorld");
                if (!new File(prefab).isFile()) throw new IOException("Scene descriptor line " + lineNo + ": prefab not found: " + prefab);
                List<Entity> inst = prefabLoader.apply(prefab);
                final int ln = lineNo;
                final String pf = prefab;
                e = inst.stream().filter(x -> x.parentId == null).findFirst()
                    .orElseThrow(() -> new IOException("Scene descriptor line " + ln + ": prefab produced no root entity: " + pf));
                e.name = name;
                entities.addAll(inst);
                entities.remove(e); // re-added below so the root keeps descriptor order semantics
            } else {
                e = new Entity("entity_" + (entities.size() + 1), name, mesh);
            }
            Transform t = e.getComponent(Transform.class);
            if (t == null) { t = new Transform(); e.addComponent(t); }

            try {
                for (int i = 1; i < parts.length; i++) {
                    String p = parts[i].trim();
                    int eq = p.indexOf('=');
                    String key = eq < 0 ? p : p.substring(0, eq);
                    String val = eq < 0 ? "" : p.substring(eq + 1).trim();
                    switch (key) {
                        case "mesh", "prefab" -> {}
                        case "pos" -> { float[] v = floats(val, 3); t.posX = v[0]; t.posY = v[1]; t.posZ = v[2]; }
                        case "rot" -> { float[] v = floats(val, 3); t.rotX = v[0]; t.rotY = v[1]; t.rotZ = v[2]; }
                        case "scale" -> { float[] v = floats(val, 3); t.scaleX = v[0]; t.scaleY = v[1]; t.scaleZ = v[2]; }
                        case "texture" -> {
                            Material m = e.getComponent(Material.class);
                            if (m != null) m.textureAssetPath = asset(assetsDir, val);
                        }
                        case "box" -> {
                            BoxCollider b = new BoxCollider();
                            float[] v = floats(val.replace(",trigger", ""), 3);
                            b.sizeX = v[0]; b.sizeY = v[1]; b.sizeZ = v[2];
                            b.isTrigger = val.endsWith(",trigger");
                            e.addComponent(b);
                        }
                        case "sphere" -> {
                            SphereCollider s = new SphereCollider();
                            s.radius = floats(val.replace(",trigger", ""), 1)[0];
                            s.isTrigger = val.endsWith(",trigger");
                            e.addComponent(s);
                        }
                        case "capsule" -> {
                            CapsuleCollider c = new CapsuleCollider();
                            float[] v = floats(val, 2);
                            c.radius = v[0]; c.height = v[1];
                            e.addComponent(c);
                        }
                        case "body" -> {
                            RigidBodyComponent rb = new RigidBodyComponent();
                            rb.motionType = RigidBodyComponent.MotionType.valueOf(val.toUpperCase());
                            rb.useGravity = rb.motionType == RigidBodyComponent.MotionType.DYNAMIC;
                            e.addComponent(rb);
                        }
                        case "script" -> e.addComponent(new ScriptComponent(asset(assetsDir, val), new File(val).getName()));
                        case "camera" -> {
                            Camera cam = new Camera();
                            if (!val.isEmpty()) cam.fov = floats(val, 1)[0];
                            e.addComponent(cam);
                        }
                        case "audio" -> {
                            String[] a = val.split(",");
                            AudioSource src = new AudioSource(asset(assetsDir, a[0]));
                            for (int k = 1; k < a.length; k++) {
                                String opt = a[k].trim();
                                if (opt.equals("loop")) src.loop = true;
                                else if (opt.equals("manual")) src.playOnStart = false;
                                else if (opt.startsWith("vol:")) src.volume = Float.parseFloat(opt.substring(4));
                                else throw new IllegalArgumentException("unknown audio option '" + opt + "'");
                            }
                            src.minDistance = 8.0f;
                            src.maxDistance = 60.0f;
                            e.addComponent(src);
                        }
                        case "vehicle" -> { // [track,wheelBase,wheelRadius] in world units, matched to the model's wheels
                            VehicleControllerComponent vc = new VehicleControllerComponent();
                            if (!val.isEmpty()) {
                                float[] v = floats(val, 3);
                                vc.wheelTrackWidth = v[0]; vc.wheelBase = v[1]; vc.wheelRadius = v[2];
                            }
                            e.addComponent(vc);
                        }
                        case "uipanel" -> { // ax,ay,w,h,r,g,b,alpha (drawn behind text)
                            float[] v = floats(val, 8);
                            UIPanel ui = new UIPanel();
                            ui.anchorX = v[0]; ui.anchorY = v[1]; ui.width = v[2]; ui.height = v[3];
                            ui.colorR = v[4]; ui.colorG = v[5]; ui.colorB = v[6]; ui.alpha = v[7];
                            ui.cornerRadius = 8.0f; ui.sortOrder = -1.0f;
                            e.addComponent(ui);
                        }
                        case "uibar" -> { // ax,ay,w,h,r,g,b
                            float[] v = floats(val, 7);
                            UIProgressBar ui = new UIProgressBar();
                            ui.anchorX = v[0]; ui.anchorY = v[1]; ui.width = v[2]; ui.height = v[3];
                            ui.fillR = v[4]; ui.fillG = v[5]; ui.fillB = v[6];
                            ui.colorR = 0.1f; ui.colorG = 0.1f; ui.colorB = 0.12f; ui.alpha = 0.8f;
                            ui.value = 0.0f;
                            e.addComponent(ui);
                        }
                        case "uitext" -> {
                            float[] v = floats(val, 3);
                            UIText ui = new UIText();
                            ui.anchorX = v[0]; ui.anchorY = v[1]; ui.fontSize = v[2];
                            ui.width = 900.0f; ui.height = v[2] * 1.5f;
                            ui.text = "";
                            e.addComponent(ui);
                        }
                        case "light" -> e.addComponent(new Light());
                        case "particles" -> e.addComponent(particlePreset(val));
                        case "parent" -> parentNames.put(e, val);
                        default -> {
                            if (!key.startsWith("prop.")) throw new IOException("unknown key '" + key + "'");
                            ScriptComponent sc = e.getComponent(ScriptComponent.class);
                            if (sc == null) throw new IOException("'" + key + "' requires a script= before it");
                            sc.propertyOverrides.put(key.substring(5), val);
                        }
                    }
                }
            } catch (IOException | IllegalArgumentException ex) {
                throw new IOException("Scene descriptor line " + lineNo + " (" + name + "): " + ex.getMessage(), ex);
            }
            entities.add(e);
        }
        for (java.util.Map.Entry<Entity, String> link : parentNames.entrySet()) {
            Entity parent = entities.stream().filter(x -> x.name.equals(link.getValue())).findFirst()
                .orElseThrow(() -> new IOException("Scene descriptor: parent '" + link.getValue() + "' of '" + link.getKey().name + "' not found"));
            link.getKey().parentId = parent.id;
        }
        return entities;
    }

    /** Built-in ParticleSystem presets for scene descriptors: {@code particles=fire|sparks}. */
    private static com.retro.engine.ecs.components.ParticleSystem particlePreset(String name) {
        com.retro.engine.ecs.components.ParticleSystem ps = new com.retro.engine.ecs.components.ParticleSystem();
        switch (name) {
            case "fire" -> {
                ps.maxParticles = 160.0f;
                ps.emissionRate = 70.0f;
                ps.lifetime = 0.8f;
                ps.startSize = 0.32f; ps.endSize = 0.04f;
                ps.startColorR = 1.0f; ps.startColorG = 0.72f; ps.startColorB = 0.22f; ps.startColorA = 1.0f;
                ps.endColorR = 0.9f; ps.endColorG = 0.12f; ps.endColorB = 0.02f; ps.endColorA = 0.0f;
                ps.speed = 1.1f;
                ps.gravityScale = -0.12f; // hot gas rises
                ps.emitterShape = com.retro.engine.ecs.components.ParticleSystem.EmitterShape.CONE;
                ps.shapeAngle = 12.0f;
                ps.shapeRadius = 0.07f;
                ps.blendMode = com.retro.engine.ecs.components.ParticleSystem.BlendMode.ADDITIVE;
            }
            case "sparks" -> {
                ps.maxParticles = 64.0f;
                ps.emissionRate = 0.0f;
                ps.lifetime = 0.5f;
                ps.startSize = 0.07f; ps.endSize = 0.02f;
                ps.startColorR = 1.0f; ps.startColorG = 0.9f; ps.startColorB = 0.55f; ps.startColorA = 1.0f;
                ps.endColorR = 1.0f; ps.endColorG = 0.35f; ps.endColorB = 0.05f; ps.endColorA = 0.0f;
                ps.speed = 3.5f;
                ps.gravityScale = 0.8f;
                ps.emitterShape = com.retro.engine.ecs.components.ParticleSystem.EmitterShape.SPHERE;
                ps.shapeRadius = 0.05f;
                ps.blendMode = com.retro.engine.ecs.components.ParticleSystem.BlendMode.ADDITIVE;
            }
            default -> throw new IllegalArgumentException("unknown particle preset '" + name + "' (fire, sparks)");
        }
        return ps;
    }

    private static String asset(File assetsDir, String rel) {
        return new File(assetsDir, rel.trim()).getAbsolutePath();
    }

    private static float[] floats(String csv, int n) {
        String[] s = csv.split(",");
        if (s.length != n) throw new IllegalArgumentException("expected " + n + " values, got '" + csv + "'");
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = Float.parseFloat(s[i].trim());
        return out;
    }

    // --- Resource I/O ---

    private static InputStream open(ProjectTemplate template, String rel) throws IOException {
        InputStream in = TemplateManager.class.getResourceAsStream(ROOT + template.resourceId() + "/" + rel);
        if (in == null) throw new IOException("Missing bundled template resource: " + template.resourceId() + "/" + rel);
        return in;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }
}
