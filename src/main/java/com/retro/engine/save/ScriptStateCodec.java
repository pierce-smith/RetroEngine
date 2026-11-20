package com.retro.engine.save;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.scripting.EntityScript;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Reads a running script's {@link Save} fields and {@code onSave} record, and puts them back into a fresh instance. */
final class ScriptStateCodec {

    private ScriptStateCodec() {}

    /** Saved state of every running script on {@code e} that has any (scripts without @Save fields or onSave data are left out). */
    static List<SceneState.ScriptState> capture(Entity e) {
        List<SceneState.ScriptState> out = new ArrayList<>(0);
        List<ScriptComponent> scripts = e.getComponents(ScriptComponent.class);
        for (int i = 0; i < scripts.size(); i++) {
            EntityScript script = scripts.get(i).activeScriptInstance;
            if (script == null) continue;
            SceneState.ScriptState state = new SceneState.ScriptState();
            state.index = i;
            state.scriptName = script.getClass().getSimpleName();
            for (Field f : saveFields(script.getClass())) {
                try {
                    String text = toText(f.get(script));
                    if (text != null) state.fields.set(f.getName(), text);
                } catch (IllegalAccessException ex) {
                    System.err.println("[SaveGame] Could not read @Save field " + state.scriptName + "." + f.getName() + ": " + ex.getMessage());
                }
            }
            try {
                script.onSave(state.custom);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError && !(t instanceof StackOverflowError)) throw (VirtualMachineError) t;
                System.err.println("[SaveGame] " + state.scriptName + ".onSave() on '" + e.name + "' threw " + t + "; its custom data is left out of the save.");
                state.custom.clear();
            }
            if (!state.fields.isEmpty() || !state.custom.isEmpty()) out.add(state);
        }
        return out;
    }

    /**
     * Restores {@code state} into a script that has just run {@code onStart}: sets its @Save fields, then calls
     * {@code onLoad}. Skipped when the entity now runs a different script there.
     */
    static void restore(SceneState.ScriptState state, EntityScript script, Entity owner) {
        String name = script.getClass().getSimpleName();
        if (!name.equals(state.scriptName)) {
            System.err.println("[SaveGame] '" + owner.name + "' runs " + name + " where the save has " + state.scriptName + "; that script's saved state is skipped.");
            return;
        }
        for (Field f : saveFields(script.getClass())) {
            if (!state.fields.has(f.getName())) continue;
            String text = state.fields.getString(f.getName(), null);
            try {
                Object value = fromText(f.getType(), text);
                if (value != null || !f.getType().isPrimitive()) f.set(script, value);
            } catch (RuntimeException | IllegalAccessException ex) {
                System.err.println("[SaveGame] Saved value '" + text + "' does not fit " + name + "." + f.getName() + " (" + f.getType().getSimpleName() + "); keeping its current value.");
            }
        }
        try {
            script.onLoad(state.custom);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError && !(t instanceof StackOverflowError)) throw (VirtualMachineError) t;
            System.err.println("[SaveGame] " + name + ".onLoad() on '" + owner.name + "' threw " + t);
        }
    }

    /** Non-static @Save fields of the class and its superclasses up to EntityScript; a subclass field hides a parent's of the same name. */
    static List<Field> saveFields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Class<?> c = type; c != null && c != EntityScript.class && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!f.isAnnotationPresent(Save.class) || Modifier.isStatic(f.getModifiers())) continue;
                if (!names.add(f.getName())) continue;
                if (!isSupported(f.getType())) {
                    System.err.println("[SaveGame] @Save on " + type.getSimpleName() + "." + f.getName() + " ignored: type "
                            + f.getType().getSimpleName() + " is not supported (save it in onSave instead).");
                    continue;
                }
                f.setAccessible(true);
                out.add(f);
            }
        }
        return out;
    }

    static boolean isSupported(Class<?> t) {
        return t == int.class || t == Integer.class || t == long.class || t == Long.class
                || t == float.class || t == Float.class || t == double.class || t == Double.class
                || t == boolean.class || t == Boolean.class || t == String.class || t.isEnum()
                || t == int[].class || t == float[].class;
    }

    /** Null for a null value. Arrays are comma-separated. Floats round-trip exactly through Float.toString. */
    static String toText(Object v) {
        if (v == null) return null;
        if (v instanceof Enum<?> e) return e.name();
        if (v instanceof int[] a) return join(Arrays.stream(a).mapToObj(String::valueOf).toArray(String[]::new));
        if (v instanceof float[] a) {
            String[] parts = new String[a.length];
            for (int i = 0; i < a.length; i++) parts[i] = Float.toString(a[i]);
            return join(parts);
        }
        return String.valueOf(v);
    }

    private static String join(String[] parts) { return String.join(",", parts); }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object fromText(Class<?> t, String s) {
        if (s == null) return null;
        if (t == String.class) return s;
        if (t == int.class || t == Integer.class) return Integer.parseInt(s.trim());
        if (t == long.class || t == Long.class) return Long.parseLong(s.trim());
        if (t == float.class || t == Float.class) return Float.parseFloat(s.trim());
        if (t == double.class || t == Double.class) return Double.parseDouble(s.trim());
        if (t == boolean.class || t == Boolean.class) {
            if (s.equalsIgnoreCase("true") || s.equals("1")) return true;
            if (s.equalsIgnoreCase("false") || s.equals("0")) return false;
            throw new IllegalArgumentException("not a boolean");
        }
        if (t.isEnum()) return Enum.valueOf((Class<Enum>) t, s);
        if (t == int[].class) {
            if (s.isEmpty()) return new int[0];
            String[] p = s.split(",");
            int[] a = new int[p.length];
            for (int i = 0; i < p.length; i++) a[i] = Integer.parseInt(p[i].trim());
            return a;
        }
        if (t == float[].class) {
            if (s.isEmpty()) return new float[0];
            String[] p = s.split(",");
            float[] a = new float[p.length];
            for (int i = 0; i < p.length; i++) a[i] = Float.parseFloat(p[i].trim());
            return a;
        }
        throw new IllegalArgumentException("unsupported type");
    }
}
