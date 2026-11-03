package com.retro.engine.modding;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.ComponentSchema;
import com.retro.engine.ecs.Entity;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Applies non-destructive {@code .rpatch} scene deltas to a freshly deserialized {@code .rscene} entity list. The
 * vanilla scene file is never modified: patches are re-applied, in mod load order, every time the scene loads.
 *
 * <h2>Format (JSON, {@code //} line comments allowed)</h2>
 * <pre>
 * {
 *   "format": "rpatch",
 *   "version": 1,
 *   "target_scene": "Assets/Scenes/Main.rscene",
 *   "operations": [
 *     { "op": "ADD_ENTITY", "id": "example_mod.crate", "name": "Mod Crate", "parent": "entity_1",
 *       "components": [
 *         { "type": "Transform",  "properties": { "posX": 2.0, "posY": 0.5 } },
 *         { "type": "MeshFilter", "properties": { "meshAssetPath": "mod://example_mod/Assets/Meshes/Crate.obj" } } ] },
 *     { "op": "MODIFY_COMPONENT", "entity": "entity_2", "component": "Light", "properties": { "intensity": 2.5 } },
 *     { "op": "ATTACH_COMPONENT", "entity_name": "Cube", "component": "Wobble", "properties": { "speed": 3.0 } },
 *     { "op": "DETACH_COMPONENT", "entity": "entity_4", "component": "AudioSource" },
 *     { "op": "MODIFY_ENTITY",    "entity": "entity_4", "name": "Renamed", "parent": null },
 *     { "op": "REMOVE_ENTITY",    "entity": "entity_5" }
 *   ]
 * }
 * </pre>
 *
 * <ul>
 *   <li>Entities are addressed by {@code "entity"} (id) or {@code "entity_name"} (first match).
 *       REMOVE_ENTITY also removes descendants.</li>
 *   <li>{@code "component"} / {@code "type"} take any registered name: built-ins, legacy aliases and
 *       {@link ModContext#registerComponent mod components}. {@code "index"} picks among same-type components
 *       (default 0).</li>
 *   <li>{@code "properties"} keys match {@code @InspectorProperty} field names or labels, case-insensitively.
 *       Values are numbers, booleans, strings, enum constant names, or objects for string maps. Asset paths resolve
 *       through the mod VFS ({@code mod://} URIs, then overlays, then the base Assets/ folder).</li>
 *   <li>ADD_ENTITY's {@code "components"} may also be an object ({@code {"Transform": {...}}}). Listing a component
 *       the new entity already has (every entity starts with a Transform) modifies it; repeating a type adds another.</li>
 *   <li>A failing operation is logged and skipped; the rest of the patch still applies.</li>
 * </ul>
 */
public final class SceneDeltaPatcher {

    public static final String EXTENSION = ".rpatch";
    public static final int FORMAT_VERSION = 1;

    /** A parsed patch file; {@code operations} are raw JSON objects validated when applied. */
    public record Patch(File source, String modId, String targetScene, List<Map<String, Object>> operations) {}

    /** Outcome of applying one patch. */
    public record Result(int applied, int failed) {}

    private final ModVirtualFileSystem vfs;

    public SceneDeltaPatcher(ModVirtualFileSystem vfs) {
        this.vfs = vfs;
    }

    // ------------------------------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static Patch parse(File file, String modId) throws IOException {
        Map<String, Object> json;
        try {
            json = MiniJson.parseObject(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (MiniJson.ParseException e) {
            throw new IOException(file + ": " + e.getMessage());
        }
        String format = MiniJson.string(json, "rpatch", "format");
        if (!"rpatch".equals(format)) throw new IOException(file + ": \"format\" must be \"rpatch\"");
        int version = MiniJson.integer(json, FORMAT_VERSION, "version");
        if (version > FORMAT_VERSION) throw new IOException(file + ": rpatch version " + version + " is newer than this engine supports");

        String target = normalizeScenePath(MiniJson.string(json, "", "target_scene", "targetScene", "target"));
        if (target.isEmpty()) throw new IOException(file + ": \"target_scene\" is required");

        List<Map<String, Object>> ops = new ArrayList<>();
        for (Object op : MiniJson.list(json, "operations")) {
            if (!(op instanceof Map)) throw new IOException(file + ": every operation must be an object");
            ops.add((Map<String, Object>) op);
        }
        return new Patch(file, modId, target, ops);
    }

    /** Project-relative, forward-slash, case-preserved scene path used to match patches to scenes. */
    public static String normalizeScenePath(String path) {
        if (path == null) return "";
        String p = path.trim().replace('\\', '/');
        while (p.startsWith("./")) p = p.substring(2);
        if (!p.isEmpty() && !p.startsWith("Assets/")) p = "Assets/" + p;
        return p;
    }

    // ------------------------------------------------------------------------------------------
    // Application
    // ------------------------------------------------------------------------------------------

    public Result apply(Patch patch, List<Entity> entities) {
        int applied = 0, failed = 0;
        for (int i = 0; i < patch.operations().size(); i++) {
            Map<String, Object> op = patch.operations().get(i);
            String kind = MiniJson.string(op, "", "op").trim().toUpperCase(Locale.ROOT);
            try {
                switch (kind) {
                    case "ADD_ENTITY" -> addEntity(op, entities, patch);
                    case "REMOVE_ENTITY" -> removeEntity(requireEntity(op, entities), entities);
                    case "MODIFY_ENTITY" -> modifyEntity(op, requireEntity(op, entities), entities);
                    case "MODIFY_COMPONENT" -> modifyComponent(op, requireEntity(op, entities));
                    case "ATTACH_COMPONENT" -> attachComponent(op, requireEntity(op, entities));
                    case "DETACH_COMPONENT" -> detachComponent(op, requireEntity(op, entities));
                    default -> throw new PatchException("unknown op \"" + kind + "\"");
                }
                applied++;
            } catch (PatchException | IllegalAccessException | RuntimeException e) {
                failed++;
                System.err.println("[SceneDeltaPatcher] " + patch.modId() + ": " + patch.source().getName()
                        + " operation #" + i + " (" + kind + ") skipped: " + e.getMessage());
            }
        }
        return new Result(applied, failed);
    }

    private void addEntity(Map<String, Object> op, List<Entity> entities, Patch patch) throws PatchException, IllegalAccessException {
        String id = MiniJson.string(op, "", "id").trim();
        if (id.isEmpty()) throw new PatchException("ADD_ENTITY needs an \"id\"");
        if (findById(entities, id) != null) throw new PatchException("entity id \"" + id + "\" already exists");
        String name = MiniJson.string(op, id, "name");

        Entity entity = new Entity(id, name, null);
        Object components = MiniJson.first(op, "components");
        if (components instanceof List<?> list) {
            // The n-th listed component of a type fills the n-th one on the entity (or is added), so repeated types
            // such as several ScriptComponents each get their own instance
            Map<Class<?>, Integer> seen = new HashMap<>();
            for (Object c : list) {
                if (!(c instanceof Map<?, ?> m)) throw new PatchException("components[] entries must be objects");
                @SuppressWarnings("unchecked")
                Map<String, Object> cm = (Map<String, Object>) m;
                ComponentSchema schema = requireSchema(MiniJson.string(cm, "", "type", "component"));
                int occurrence = seen.merge(schema.type(), 1, Integer::sum) - 1;
                upsertComponent(entity, schema, occurrence, MiniJson.object(cm, "properties"));
            }
        } else if (components instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> props = (e.getValue() instanceof Map) ? (Map<String, Object>) e.getValue() : null;
                upsertComponent(entity, requireSchema(String.valueOf(e.getKey())), 0, props);
            }
        } else if (components != null) {
            throw new PatchException("\"components\" must be an array or object");
        }

        entities.add(entity);
        if (op.containsKey("parent")) setParent(entity, parentOf(op, entities), entities);
        for (Component c : entity.getAllComponents()) c.onDeserialized(entity);
    }

    private void upsertComponent(Entity entity, ComponentSchema schema, int occurrence, Map<String, Object> props) throws PatchException, IllegalAccessException {
        Component existing = nthComponent(entity, schema.type(), occurrence);
        Component target = existing != null ? existing : schema.newInstance();
        applyProperties(schema, target, props);
        if (existing == null) entity.addComponent(target);
    }

    private void removeEntity(Entity root, List<Entity> entities) {
        Set<String> doomed = new HashSet<>();
        collectSubtree(root, entities, doomed);
        if (root.parentId != null && !root.parentId.isEmpty()) {
            Entity parent = findById(entities, root.parentId);
            if (parent != null) parent.removeChildId(root.id);
        }
        entities.removeIf(e -> e.id != null && doomed.contains(e.id));
    }

    private void modifyEntity(Map<String, Object> op, Entity entity, List<Entity> entities) throws PatchException {
        if (op.containsKey("name")) {
            String name = MiniJson.string(op, null, "name");
            if (name == null || name.isBlank()) throw new PatchException("\"name\" must be a non-empty string");
            entity.name = name;
        }
        if (op.containsKey("parent")) setParent(entity, parentOf(op, entities), entities);
    }

    private void modifyComponent(Map<String, Object> op, Entity entity) throws PatchException, IllegalAccessException {
        ComponentSchema schema = requireSchema(MiniJson.string(op, "", "component", "type"));
        int index = MiniJson.integer(op, 0, "index");
        Component c = nthComponent(entity, schema.type(), index);
        if (c == null) throw new PatchException("entity \"" + entity.id + "\" has no " + schema.type().getSimpleName() + " #" + index);
        applyProperties(schema, c, MiniJson.object(op, "properties"));
        c.onDeserialized(entity);
    }

    private void attachComponent(Map<String, Object> op, Entity entity) throws PatchException, IllegalAccessException {
        ComponentSchema schema = requireSchema(MiniJson.string(op, "", "component", "type"));
        Component c = schema.newInstance();
        applyProperties(schema, c, MiniJson.object(op, "properties"));
        entity.addComponent(c);
        c.onDeserialized(entity);
    }

    private void detachComponent(Map<String, Object> op, Entity entity) throws PatchException {
        ComponentSchema schema = requireSchema(MiniJson.string(op, "", "component", "type"));
        int index = MiniJson.integer(op, 0, "index");
        Component c = nthComponent(entity, schema.type(), index);
        if (c == null) throw new PatchException("entity \"" + entity.id + "\" has no " + schema.type().getSimpleName() + " #" + index);
        entity.removeComponentInstance(c);
    }

    // ------------------------------------------------------------------------------------------
    // Properties
    // ------------------------------------------------------------------------------------------

    private void applyProperties(ComponentSchema schema, Component target, Map<String, Object> props) throws PatchException, IllegalAccessException {
        if (props == null) return;
        Map<String, ComponentSchema.Property> byKey = new HashMap<>();
        for (ComponentSchema.Property p : schema.serializedProperties()) {
            byKey.putIfAbsent(p.name().toLowerCase(Locale.ROOT), p);
            byKey.putIfAbsent(p.label.toLowerCase(Locale.ROOT), p);
        }
        for (Map.Entry<String, Object> e : props.entrySet()) {
            ComponentSchema.Property p = byKey.get(e.getKey().toLowerCase(Locale.ROOT));
            if (p == null) {
                throw new PatchException(schema.type().getSimpleName() + " has no persisted property \"" + e.getKey() + "\"");
            }
            p.set(target, convert(p, e.getValue()));
        }
    }

    private Object convert(ComponentSchema.Property p, Object v) throws PatchException {
        String where = p.field.getDeclaringClass().getSimpleName() + "." + p.name();
        switch (p.kind) {
            case FLOAT -> {
                if (v instanceof Number n) return n.floatValue();
            }
            case INT -> {
                if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) return n.intValue();
            }
            case BOOLEAN -> {
                if (v instanceof Boolean b) return b;
            }
            case ENUM -> {
                if (v instanceof String s) {
                    for (Object constant : p.field.getType().getEnumConstants()) {
                        if (((Enum<?>) constant).name().equalsIgnoreCase(s.trim())) return constant;
                    }
                    throw new PatchException(where + ": unknown enum constant \"" + s + "\"");
                }
            }
            case STRING -> {
                if (v == null || v instanceof String) return v;
            }
            case ASSET_PATH -> {
                if (v == null) return null;
                if (v instanceof String s) return resolveAssetPath(s);
            }
            case STRING_MAP -> {
                if (v instanceof Map<?, ?> m) {
                    Map<String, String> out = new HashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : stringify(e.getValue()));
                    return out;
                }
            }
            default -> throw new PatchException(where + " (" + p.kind + ") cannot be set from a patch");
        }
        throw new PatchException(where + ": expected " + p.kind + ", got " + (v == null ? "null" : v.getClass().getSimpleName()));
    }

    /** mod:// URIs and overlay copies first, then the base project Assets/ folder; unresolvable paths pass through. */
    private String resolveAssetPath(String path) {
        String s = path.trim();
        if (vfs == null || s.isEmpty()) return s;
        String resolved = vfs.resolveOverride(s);
        if (!resolved.equals(s) || new File(s).isAbsolute() || s.startsWith(ModVirtualFileSystem.MOD_SCHEME)) return resolved;
        String rel = s.replace('\\', '/');
        if (rel.startsWith("Assets/")) rel = rel.substring("Assets/".length());
        File base = new File(vfs.baseAssetsDir(), rel);
        return base.isFile() ? base.getAbsolutePath() : s;
    }

    private static String stringify(Object v) {
        if (v instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) return Long.toString(d.longValue());
        return String.valueOf(v);
    }

    // ------------------------------------------------------------------------------------------
    // Entity graph helpers
    // ------------------------------------------------------------------------------------------

    private static ComponentSchema requireSchema(String type) throws PatchException {
        if (type == null || type.isBlank()) throw new PatchException("missing \"component\" type");
        ComponentSchema schema = ComponentSchema.forSerializedName(type.trim());
        if (schema == null) throw new PatchException("unknown component type \"" + type + "\" (is its mod loaded and registered?)");
        return schema;
    }

    private static Entity requireEntity(Map<String, Object> op, List<Entity> entities) throws PatchException {
        String id = MiniJson.string(op, null, "entity", "entity_id");
        if (id != null) {
            Entity e = findById(entities, id.trim());
            if (e == null) throw new PatchException("no entity with id \"" + id + "\"");
            return e;
        }
        String name = MiniJson.string(op, null, "entity_name", "entityName");
        if (name != null) {
            for (Entity e : entities) {
                if (name.equals(e.name)) return e;
            }
            throw new PatchException("no entity named \"" + name + "\"");
        }
        throw new PatchException("operation needs \"entity\" (id) or \"entity_name\"");
    }

    /** The "parent" value as an entity, or null for JSON null / "" (move to root). */
    private static Entity parentOf(Map<String, Object> op, List<Entity> entities) throws PatchException {
        Object v = op.get("parent");
        if (v == null || (v instanceof String s && s.isBlank())) return null;
        if (!(v instanceof String s)) throw new PatchException("\"parent\" must be an entity id or null");
        Entity parent = findById(entities, s.trim());
        if (parent == null) throw new PatchException("parent entity \"" + s + "\" not found");
        return parent;
    }

    private static void setParent(Entity child, Entity parent, List<Entity> entities) throws PatchException {
        if (parent != null) {
            for (Entity p = parent; p != null; p = (p.parentId == null || p.parentId.isEmpty()) ? null : findById(entities, p.parentId)) {
                if (p == child) throw new PatchException("parenting \"" + child.id + "\" under \"" + parent.id + "\" would form a loop");
            }
        }
        if (child.parentId != null && !child.parentId.isEmpty()) {
            Entity old = findById(entities, child.parentId);
            if (old != null) old.removeChildId(child.id);
        }
        child.parentId = (parent != null) ? parent.id : null;
        if (parent != null) parent.addChildId(child.id);
    }

    private static void collectSubtree(Entity root, List<Entity> entities, Set<String> out) {
        if (root.id == null || !out.add(root.id)) return;
        for (Entity e : entities) {
            if (root.id.equals(e.parentId)) collectSubtree(e, entities, out);
        }
    }

    private static Component nthComponent(Entity entity, Class<?> type, int index) {
        int seen = 0;
        for (Component c : entity.getAllComponents()) {
            if (c.getClass() == type && seen++ == index) return c;
        }
        return null;
    }

    private static Entity findById(List<Entity> entities, String id) {
        for (Entity e : entities) {
            if (e.id != null && e.id.equalsIgnoreCase(id)) return e;
        }
        return null;
    }

    private static final class PatchException extends Exception {
        PatchException(String message) { super(message); }
    }
}
