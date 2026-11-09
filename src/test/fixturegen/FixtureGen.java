import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runs against one tagged build (package layout differs between tags, so everything is found by simple name).
 * Writes scene + prefab with every inspector field of every component set to a distinct value, reloads them with
 * the same build, and records what came back. The current engine must read back exactly that.
 *
 * args: classesDir outDir tagName texturePng
 */
public class FixtureGen {
    static Map<String, Class<?>> bySimple = new TreeMap<>();
    static Class<?> componentClass, inspectorClass, entityClass;
    static int counter = 0;

    public static void main(String[] args) throws Exception {
        Path classes = Path.of(args[0]);
        Path out = Path.of(args[1]);
        String tag = args[2];
        Files.createDirectories(out);
        ClassLoader cl = FixtureGen.class.getClassLoader();
        List<String> names = new ArrayList<>();
        try (var walk = Files.walk(classes)) {
            walk.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                String rel = classes.relativize(p).toString();
                names.add(rel.substring(0, rel.length() - 6).replace(File.separatorChar, '.'));
            });
        }
        for (String n : names) {
            if (n.contains("$") && !n.matches(".*\\$[A-Z]\\w*$")) continue;
            try {
                Class<?> c = Class.forName(n, false, cl);
                if (!n.contains("$")) bySimple.putIfAbsent(c.getSimpleName(), c);
            } catch (Throwable ignored) {}
        }
        componentClass = need("Component");
        inspectorClass = need("InspectorProperty");
        entityClass = need("Entity");

        List<Class<?>> types = new ArrayList<>();
        for (String n : names) {
            if (n.contains("$")) continue;
            Class<?> c;
            try { c = Class.forName(n, false, cl); } catch (Throwable t) { continue; }
            if (!componentClass.isAssignableFrom(c) || c == componentClass) continue;
            if (Modifier.isAbstract(c.getModifiers()) || !Modifier.isPublic(c.getModifiers())) continue;
            if (c.getSimpleName().equals("UnknownComponent") || c.getSimpleName().equals("Transform")) continue;
            try { c.getConstructor(); } catch (NoSuchMethodException e) { continue; }
            types.add(c);
        }
        types.sort((a, b) -> a.getSimpleName().compareTo(b.getSimpleName()));

        // --- Scene: a root with one child per component type ---
        List<Object> scene = new ArrayList<>();
        Object root = newEntity("root", "Root");
        fillTransform(root);
        scene.add(root);
        List<String> skipped = new ArrayList<>();
        for (Class<?> c : types) {
            Object comp;
            try { comp = c.getConstructor().newInstance(); } catch (Throwable t) { skipped.add(c.getSimpleName() + " (ctor " + t + ")"); continue; }
            fill(comp);
            Object e = newEntity("e_" + c.getSimpleName(), c.getSimpleName());
            fillTransform(e);
            try {
                entityClass.getMethod("addComponent", componentClass).invoke(e, comp);
            } catch (Throwable t) { skipped.add(c.getSimpleName() + " (add " + t + ")"); continue; }
            entityClass.getField("parentId").set(e, "root");
            entityClass.getMethod("addChildId", String.class).invoke(root, "e_" + c.getSimpleName());
            scene.add(e);
        }
        Class<?> sceneSer = need("SceneSerializer");
        Path scenePath = out.resolve("scene-" + tag + ".rscene");
        boolean ok = (Boolean) sceneSer.getMethod("saveScene", String.class, List.class).invoke(null, scenePath.toString(), scene);
        if (!ok) throw new IllegalStateException("scene save failed");
        Object data = sceneSer.getMethod("loadSceneData", String.class).invoke(null, scenePath.toString());
        @SuppressWarnings("unchecked")
        List<Object> loaded = (List<Object>) data.getClass().getMethod("entities").invoke(data);
        Files.writeString(out.resolve("scene-" + tag + ".rscene.expected"), dump(loaded, true));

        // --- Prefab: same tree, instantiated by the same build ---
        Class<?> prefabSer = need("PrefabSerializer");
        Class<?> assetDb = need("AssetDatabase");
        Class<?> world = need("SceneWorld");
        Path prefabPath = out.resolve("prefab-" + tag + ".rprefab");
        ok = (Boolean) prefabSer.getMethod("savePrefab", String.class, entityClass, List.class, assetDb)
                .invoke(null, prefabPath.toString(), root, scene, null);
        if (!ok) throw new IllegalStateException("prefab save failed");
        if (!Files.exists(prefabPath)) prefabPath = out.resolve("prefab-" + tag + ".prefab.bin");
        if (!Files.exists(prefabPath)) throw new IllegalStateException("prefab file not found");
        Object w = world.getConstructor().newInstance();
        @SuppressWarnings("unchecked")
        List<Object> made = (List<Object>) prefabSer.getMethod("instantiatePrefab", String.class, world, assetDb)
                .invoke(null, prefabPath.toString(), w, null);
        if (made.isEmpty()) throw new IllegalStateException("prefab instantiate returned nothing");
        Files.writeString(Path.of(prefabPath + ".expected"), dump(made, false));

        if (bySimple.containsKey("ProjectSettings")) project(out, tag, Path.of(args[3]));

        System.out.println(tag + ": " + types.size() + " component types, scene " + loaded.size() + " entities, prefab " + made.size());
        for (String s : skipped) System.out.println("  skipped " + s);
        System.exit(0); // The asset watcher thread would keep the VM alive
    }

    /**
     * A whole project folder as that build left it: settings, the asset registry with one texture, and a scene
     * that references the texture through the registry (by UUID), so migration has to carry the UUID over.
     */
    static void project(Path out, String tag, Path png) throws Exception {
        Path dir = out.resolve("project-" + tag);
        Files.createDirectories(dir.resolve("Assets/Textures"));
        Files.createDirectories(dir.resolve("Assets/Scenes"));
        Files.copy(png, dir.resolve("Assets/Textures/Brick.png"));

        Class<?> settingsClass = need("ProjectSettings");
        Object settings = settingsClass.getMethod("defaults").invoke(null);
        fillSettings(settings);
        settingsClass.getField("projectName").set(settings, "Fixture " + tag);
        settingsClass.getField("startupScene").set(settings, "Assets/Scenes/Main.rscene");
        settingsClass.getMethod("write", File.class).invoke(settings, dir.toFile());

        Class<?> dbClass = need("AssetDatabase");
        Object db = dbClass.getConstructor().newInstance();
        if (!(Boolean) dbClass.getMethod("openProject", String.class).invoke(db, dir.toString())) throw new IllegalStateException("openProject");
        String uuid = (String) dbClass.getMethod("resolveUuidByPath", String.class).invoke(db, "Assets/Textures/Brick.png");
        if (uuid == null) throw new IllegalStateException("texture has no uuid");

        Object e = newEntity("wall", "Wall");
        Object mat = need("Material").getConstructor().newInstance();
        mat.getClass().getField("textureAssetPath").set(mat, "Assets/Textures/Brick.png");
        entityClass.getMethod("addComponent", componentClass).invoke(e, mat);
        Class<?> sceneSer = need("SceneSerializer");
        List<Object> entities = new ArrayList<>(List.of(e));
        Object directory = dbClass.getMethod("getAssetDirectory").invoke(db);
        boolean ok = (Boolean) sceneSer.getMethod("saveScene", String.class, List.class, List.class, dbClass)
                .invoke(null, dir.resolve("Assets/Scenes/Main.rscene").toString(), entities, directory, db);
        if (!ok) throw new IllegalStateException("project scene save");
        dbClass.getMethod("saveManifest").invoke(db);
        dbClass.getMethod("clear").invoke(db);

        Object reread = settingsClass.getMethod("read", File.class).invoke(null, dir.toFile());
        StringBuilder sb = new StringBuilder();
        sb.append("U|Assets/Textures/Brick.png=").append(uuid).append('\n');
        for (Field f : settingsFields(settingsClass)) {
            if (f.getName().equals("lastOpenedAtMillis") || f.getName().equals("engineVersion")) continue;
            String v = reprAny(f.get(reread));
            if (v != null) sb.append("S|").append(f.getName()).append('=').append(v).append('\n');
        }
        Files.writeString(out.resolve("project-" + tag + ".expected"), sb.toString());
    }

    static List<Field> settingsFields(Class<?> c) {
        List<Field> fields = new ArrayList<>();
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || !Modifier.isPublic(f.getModifiers())) continue;
            fields.add(f);
        }
        fields.sort((a, b) -> a.getName().compareTo(b.getName()));
        return fields;
    }

    /** In-range values, so the build's own sanitize() keeps them and the comparison tests the format, not the clamps. */
    static void fillSettings(Object s) throws Exception {
        int k = 0;
        for (Field f : settingsFields(s.getClass())) {
            k++;
            Class<?> t = f.getType();
            String n = f.getName();
            if (n.equals("projectName") || n.equals("engineVersion") || n.equals("startupScene")) continue;
            if (t == long.class) f.setLong(s, 1_700_000_000_000L + k);
            else if (Modifier.isFinal(f.getModifiers())) {
                Object arr = f.get(s);
                if (arr instanceof float[] fa) for (int i = 0; i < fa.length; i++) fa[i] = 0.2f + 0.1f * i + 0.01f * k;
                else if (arr instanceof boolean[] ba) for (int i = 0; i < ba.length; i++) ba[i] = (i % 2 == 0);
            }
            else if (t == float.class) f.setFloat(s, n.startsWith("fogEnd") ? 400.0f : n.startsWith("fogStart") ? 25.0f : 0.25f + 0.01f * k);
            else if (t == int.class) f.setInt(s, n.equals("maxHeapMb") ? 3072 : n.equals("frameRateCap") ? 144 : 2 + k % 5);
            else if (t == boolean.class) f.setBoolean(s, !f.getBoolean(s));
            else if (t.isEnum()) {
                Object[] values = t.getEnumConstants();
                Object pick = values[values.length - 1];
                if (pick == f.get(s)) pick = values[0];
                f.set(s, pick);
            }
        }
    }

    static String reprAny(Object v) {
        if (v instanceof Long l) return l.toString();
        if (v instanceof float[] fa) return java.util.Arrays.toString(fa);
        if (v instanceof boolean[] ba) return java.util.Arrays.toString(ba);
        return repr(v);
    }

    static Class<?> need(String simple) {
        Class<?> c = bySimple.get(simple);
        if (c == null) throw new IllegalStateException("no class " + simple);
        return c;
    }

    static Object newEntity(String id, String name) throws Exception {
        return entityClass.getConstructor(String.class, String.class, String.class).newInstance(id, name, null);
    }

    static void fillTransform(Object entity) throws Exception {
        fill(entityClass.getMethod("getTransform").invoke(entity));
    }

    static List<Field> inspectorFields(Class<?> c) {
        List<Field> fields = new ArrayList<>();
        @SuppressWarnings("unchecked")
        Class<? extends Annotation> ann = (Class<? extends Annotation>) inspectorClass;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                int m = f.getModifiers();
                if (Modifier.isStatic(m) || Modifier.isFinal(m) || Modifier.isTransient(m)) continue;
                if (f.getAnnotation(ann) == null) continue;
                f.setAccessible(true);
                fields.add(f);
            }
        }
        fields.sort((a, b) -> a.getName().compareTo(b.getName()));
        return fields;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    static void fill(Object comp) throws Exception {
        String owner = comp.getClass().getSimpleName();
        for (Field f : inspectorFields(comp.getClass())) {
            int k = ++counter;
            Class<?> t = f.getType();
            if (t == float.class) f.setFloat(comp, 1.5f + 0.25f * (k % 37));
            else if (t == int.class) f.setInt(comp, 2 + (k % 5));
            else if (t == boolean.class) f.setBoolean(comp, !f.getBoolean(comp));
            else if (t == String.class) f.set(comp, "Assets/Fixtures/" + owner + "_" + f.getName());
            else if (t.isEnum()) {
                Object[] values = t.getEnumConstants();
                Object pick = values[values.length - 1];
                if (pick == f.get(comp)) pick = values[0];
                f.set(comp, pick);
            } else if (Map.class.isAssignableFrom(t)) {
                Map m = (Map) f.get(comp);
                if (m != null) m.put("fixture_key", "fixture_value");
            }
        }
    }

    static String dump(List<Object> entities, boolean withIds) throws Exception {
        Map<String, String> idToName = new TreeMap<>();
        for (Object e : entities) idToName.put((String) entityClass.getField("id").get(e), (String) entityClass.getField("name").get(e));
        StringBuilder sb = new StringBuilder();
        for (Object e : entities) {
            String name = (String) entityClass.getField("name").get(e);
            String parent = (String) entityClass.getField("parentId").get(e);
            String parentName = parent == null ? "-" : idToName.getOrDefault(parent, "?" + parent);
            sb.append("E|").append(name).append("|parent=").append(parentName);
            if (withIds) sb.append("|id=").append(entityClass.getField("id").get(e));
            sb.append('\n');
            @SuppressWarnings("unchecked")
            List<Object> comps = (List<Object>) entityClass.getMethod("getAllComponents").invoke(e);
            List<Object> all = new ArrayList<>();
            Object tr = entityClass.getMethod("getTransform").invoke(e);
            all.add(tr);
            for (Object c : comps) if (c != tr) all.add(c);
            for (Object c : all) {
                for (Field f : inspectorFields(c.getClass())) {
                    String v = repr(f.get(c));
                    if (v == null) continue;
                    sb.append("F|").append(name).append('|').append(c.getClass().getSimpleName()).append('|')
                      .append(f.getName()).append('=').append(v).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** Same encoding as TaggedFormatsTest.repr; null means "not a value kind we compare". */
    static String repr(Object v) {
        if (v == null) return "null";
        if (v instanceof Float || v instanceof Integer || v instanceof Boolean) return v.toString();
        if (v instanceof String s) return '"' + s.replace("\\", "\\\\").replace("\n", "\\n") + '"';
        if (v instanceof Enum<?> en) return en.name();
        if (v instanceof Map<?, ?> m) return new TreeMap<>(m).toString();
        return null;
    }
}
