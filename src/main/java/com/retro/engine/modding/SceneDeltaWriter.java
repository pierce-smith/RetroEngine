package com.retro.engine.modding;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.ComponentSchema;
import com.retro.engine.ecs.Entity;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Creation Kit mode: turns "baseline scene" vs "edited scene" into the minimal {@code .rpatch} that
 * {@link SceneDeltaPatcher} replays to get from one to the other. Entities match by id; components match by type
 * and occurrence (the second ScriptComponent pairs with the second ScriptComponent).
 *
 * <p>Asset paths are written portably: files inside the authoring mod become {@code mod://<id>/...}, files under
 * the vanilla Assets/ folder or any mod overlay become {@code Assets/...} (re-resolved through the VFS on load).
 */
public final class SceneDeltaWriter {

    private final ModManifest mod;
    private final ModVirtualFileSystem vfs;

    public SceneDeltaWriter(ModManifest mod, ModVirtualFileSystem vfs) {
        this.mod = mod;
        this.vfs = vfs;
    }

    /** The full .rpatch document (format header + operations); operations is empty when nothing changed. */
    public Map<String, Object> diff(String targetScene, List<Entity> baseline, List<Entity> current) throws IllegalAccessException {
        List<Object> ops = new ArrayList<>();
        Map<String, Entity> base = index(baseline);
        Map<String, Entity> now = index(current);

        // Removed subtrees: one REMOVE_ENTITY per removed root (the patcher removes descendants with it)
        for (Entity b : baseline) {
            if (b.id == null || now.containsKey(key(b.id))) continue;
            Entity parent = (b.parentId != null) ? base.get(key(b.parentId)) : null;
            if (parent != null && !now.containsKey(key(parent.id))) continue;
            ops.add(op("REMOVE_ENTITY", "entity", b.id));
        }

        // Changed entities
        for (Entity c : current) {
            Entity b = (c.id != null) ? base.get(key(c.id)) : null;
            if (b == null) continue;
            Map<String, Object> modify = op("MODIFY_ENTITY", "entity", c.id);
            if (!Objects.equals(b.name, c.name)) modify.put("name", c.name);
            if (!Objects.equals(blankToNull(b.parentId), blankToNull(c.parentId))) modify.put("parent", blankToNull(c.parentId));
            if (modify.size() > 2) ops.add(modify);
            diffComponents(c.id, b, c, ops);
        }

        // New entities, parents before children so every "parent" reference already exists when it is applied
        Set<String> emitted = new HashSet<>();
        for (Entity c : current) emitAdd(c, base, now, emitted, ops);

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("format", "rpatch");
        doc.put("version", SceneDeltaPatcher.FORMAT_VERSION);
        doc.put("target_scene", SceneDeltaPatcher.normalizeScenePath(targetScene));
        doc.put("generated_by", "RetroEngine Creation Kit mode (" + mod.id + ")");
        doc.put("operations", ops);
        return doc;
    }

    private void emitAdd(Entity c, Map<String, Entity> base, Map<String, Entity> now, Set<String> emitted, List<Object> ops) throws IllegalAccessException {
        if (c.id == null || base.containsKey(key(c.id)) || !emitted.add(key(c.id))) return;
        Entity parent = (c.parentId != null && !c.parentId.isBlank()) ? now.get(key(c.parentId)) : null;
        if (parent != null) emitAdd(parent, base, now, emitted, ops);

        Map<String, Object> add = op("ADD_ENTITY", "id", c.id);
        add.put("name", c.name);
        if (parent != null) add.put("parent", parent.id);
        List<Object> components = new ArrayList<>();
        for (Component comp : c.getAllComponents()) {
            ComponentSchema schema = ComponentSchema.of(comp.getClass());
            if (!schema.isPersistent()) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", comp.getClass().getSimpleName());
            entry.put("properties", allProperties(schema, comp));
            components.add(entry);
        }
        add.put("components", components);
        ops.add(add);
    }

    private void diffComponents(String entityId, Entity b, Entity c, List<Object> ops) throws IllegalAccessException {
        Map<Class<?>, List<Component>> before = byType(b), after = byType(c);
        Set<Class<?>> types = new java.util.LinkedHashSet<>(before.keySet());
        types.addAll(after.keySet());
        for (Class<?> type : types) {
            ComponentSchema schema = ComponentSchema.of(type);
            if (!schema.isPersistent()) continue;
            List<Component> bl = before.getOrDefault(type, List.of()), cl = after.getOrDefault(type, List.of());
            int shared = Math.min(bl.size(), cl.size());
            for (int i = 0; i < shared; i++) {
                Map<String, Object> changed = changedProperties(schema, bl.get(i), cl.get(i));
                if (changed.isEmpty()) continue;
                Map<String, Object> m = op("MODIFY_COMPONENT", "entity", entityId);
                m.put("component", type.getSimpleName());
                if (i > 0) m.put("index", i);
                m.put("properties", changed);
                ops.add(m);
            }
            // Highest index first, so earlier detaches never shift the indices of later ones
            for (int i = bl.size() - 1; i >= shared; i--) {
                Map<String, Object> d = op("DETACH_COMPONENT", "entity", entityId);
                d.put("component", type.getSimpleName());
                if (i > 0) d.put("index", i);
                ops.add(d);
            }
            for (int i = shared; i < cl.size(); i++) {
                Map<String, Object> a = op("ATTACH_COMPONENT", "entity", entityId);
                a.put("component", type.getSimpleName());
                a.put("properties", allProperties(schema, cl.get(i)));
                ops.add(a);
            }
        }
    }

    private Map<String, Object> allProperties(ComponentSchema schema, Component comp) throws IllegalAccessException {
        comp.onBeforeSerialize();
        Map<String, Object> props = new LinkedHashMap<>();
        for (ComponentSchema.Property p : schema.serializedProperties()) {
            props.put(p.name(), portable(p, p.get(comp)));
        }
        return props;
    }

    private Map<String, Object> changedProperties(ComponentSchema schema, Component before, Component after) throws IllegalAccessException {
        before.onBeforeSerialize();
        after.onBeforeSerialize();
        Map<String, Object> props = new LinkedHashMap<>();
        for (ComponentSchema.Property p : schema.serializedProperties()) {
            Object a = p.get(before), c = p.get(after);
            if (!sameValue(p, a, c)) {
                props.put(p.name(), portable(p, c));
            }
        }
        return props;
    }

    /**
     * Value equality for diffing: asset paths by portable identity, binary blobs by content (snapshots, duplicates
     * and undo hand out equal copies, which must not register as edits), everything else by equals().
     */
    private boolean sameValue(ComponentSchema.Property p, Object a, Object c) {
        return switch (p.kind) {
            case ASSET_PATH -> samePath((String) a, (String) c);
            case BYTES -> Arrays.equals((byte[]) a, (byte[]) c);
            default -> Objects.equals(a, c);
        };
    }

    /** JSON-ready value: enums by name, maps copied, asset paths made portable, binary blobs as Base64. */
    private Object portable(ComponentSchema.Property p, Object v) {
        if (v == null) return null;
        return switch (p.kind) {
            case ENUM -> ((Enum<?>) v).name();
            case ASSET_PATH -> portablePath((String) v);
            case STRING_MAP -> new LinkedHashMap<>((Map<?, ?>) v);
            case BYTES -> Base64.getEncoder().encodeToString((byte[]) v);
            default -> v;
        };
    }

    String portablePath(String path) {
        if (path == null || path.isEmpty() || path.startsWith(ModVirtualFileSystem.MOD_SCHEME)) return path;
        File f = new File(path);
        if (!f.isAbsolute()) return path;
        Path abs = f.toPath().normalize();
        Path modRoot = mod.root.toPath().normalize();
        // Overlay copies (this mod's or another's) map back to their Assets/-relative identity
        for (ModVirtualFileSystem.Layer layer : vfs.layers()) {
            Path root = layer.root().toPath().normalize();
            if (abs.startsWith(root)) return "Assets/" + root.relativize(abs).toString().replace('\\', '/');
        }
        if (abs.startsWith(modRoot)) return ModVirtualFileSystem.MOD_SCHEME + mod.id + "/" + modRoot.relativize(abs).toString().replace('\\', '/');
        Path baseAssets = vfs.baseAssetsDir().toPath().normalize();
        if (abs.startsWith(baseAssets)) return "Assets/" + baseAssets.relativize(abs).toString().replace('\\', '/');
        System.out.println("[SceneDeltaWriter] Keeping absolute path outside the project and mod: " + path);
        return path;
    }

    private boolean samePath(String a, String b) {
        if (Objects.equals(a, b)) return true;
        if (a == null || b == null) return false;
        return portablePath(a).equals(portablePath(b));
    }

    private static Map<Class<?>, List<Component>> byType(Entity e) {
        Map<Class<?>, List<Component>> out = new LinkedHashMap<>();
        for (Component c : e.getAllComponents()) out.computeIfAbsent(c.getClass(), k -> new ArrayList<>()).add(c);
        return out;
    }

    private static Map<String, Entity> index(List<Entity> entities) {
        Map<String, Entity> out = new HashMap<>();
        for (Entity e : entities) if (e.id != null) out.putIfAbsent(key(e.id), e);
        return out;
    }

    private static Map<String, Object> op(String kind, String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", kind);
        m.put(key, value);
        return m;
    }

    private static String key(String id) {
        return id.toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
