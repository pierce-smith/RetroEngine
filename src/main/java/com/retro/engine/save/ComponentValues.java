package com.retro.engine.save;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.ComponentSchema;
import com.retro.engine.ecs.serialization.SceneSerializer;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Reads, copies, compares and writes back component property values in the portable form a save file holds: enums by
 * name and asset paths relative to {@code Assets/}, so a save works after the game folder moves.
 */
final class ComponentValues {

    private ComponentValues() {}

    /** The property's value in portable form (a private copy for maps and byte arrays). */
    static Object read(ComponentSchema.Property p, Component c) {
        Object v;
        try {
            v = p.get(c);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return switch (p.kind) {
            case ENUM -> v != null ? ((Enum<?>) v).name() : null;
            case ASSET_PATH -> v != null ? SceneSerializer.sanitizeAssetPath((String) v) : null;
            case STRING_MAP -> {
                if (v == null) yield null;
                @SuppressWarnings("unchecked") Map<String, String> m = (Map<String, String>) v;
                yield new LinkedHashMap<>(m);
            }
            case BYTES -> v != null ? ((byte[]) v).clone() : null;
            default -> v;
        };
    }

    static boolean same(Object a, Object b) {
        if (a instanceof byte[] x && b instanceof byte[] y) return Arrays.equals(x, y);
        if (a instanceof Float x && b instanceof Float y) return Float.floatToIntBits(x) == Float.floatToIntBits(y);
        return Objects.equals(a, b);
    }

    /**
     * Writes a portable value back into the component. A value that no longer fits the property (the game changed the
     * field's type since the save was made) is skipped and false returned.
     */
    static boolean write(ComponentSchema.Property p, Component c, Object v, AssetResolver assets) {
        try {
            switch (p.kind) {
                case FLOAT -> { if (!(v instanceof Float f)) return false; p.set(c, f); }
                case INT -> { if (!(v instanceof Integer i)) return false; p.set(c, i); }
                case BOOLEAN -> { if (!(v instanceof Boolean b)) return false; p.set(c, b); }
                case ENUM -> {
                    if (!(v instanceof String name)) return false;
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Class<Enum> type = (Class<Enum>) p.field.getType();
                    try {
                        @SuppressWarnings("unchecked") Object e = Enum.valueOf(type, name);
                        p.set(c, e);
                    } catch (IllegalArgumentException missing) {
                        return false;
                    }
                }
                case STRING -> { if (v != null && !(v instanceof String)) return false; p.set(c, v); }
                case ASSET_PATH -> {
                    if (v != null && !(v instanceof String)) return false;
                    String path = (String) v;
                    p.set(c, (path == null || path.isEmpty() || assets == null) ? path : assets.resolve(path));
                }
                case STRING_MAP -> {
                    if (v != null && !(v instanceof Map)) return false;
                    @SuppressWarnings("unchecked") Map<String, String> src = (Map<String, String>) v;
                    p.set(c, src != null ? new java.util.HashMap<>(src) : new java.util.HashMap<String, String>());
                }
                case BYTES -> { if (v != null && !(v instanceof byte[])) return false; p.set(c, v != null ? ((byte[]) v).clone() : null); }
                default -> { return false; }
            }
            return true;
        } catch (IllegalAccessException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Turns an {@code Assets/}-relative path back into the path this run of the game uses (mods included). */
    interface AssetResolver {
        String resolve(String assetsRelativePath);
    }
}
