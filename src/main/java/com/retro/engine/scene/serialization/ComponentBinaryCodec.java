package com.retro.engine.scene.serialization;

import com.retro.engine.assets.AssetDatabase;
import com.retro.engine.assets.importers.MeshImporter;
import com.retro.engine.assets.importers.TextureImporter;
import com.retro.engine.scene.components.Component;
import com.retro.engine.scene.components.ComponentSchema;
import com.retro.engine.scene.entity.Entity;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Descriptor-driven binary codec for entity records shared by {@link SceneSerializer} and {@link PrefabSerializer}.
 * Each component is written as its simple class name followed by its persisted {@link ComponentSchema} properties
 * in declaration order: float/int/boolean as raw values, enums as UTF names, strings as string-table indices,
 * asset paths as [128-bit UUID (scenes only)] + string-table index, and string maps as count + UTF pairs.
 */
final class ComponentBinaryCodec {

    private final boolean prefab;
    private final int version;

    private ComponentBinaryCodec(boolean prefab, int version) {
        this.prefab = prefab;
        this.version = version;
    }

    static ComponentBinaryCodec scene(int version) { return new ComponentBinaryCodec(false, version); }

    static ComponentBinaryCodec prefab(int version) { return new ComponentBinaryCodec(true, version); }

    /** Insertion-ordered, de-duplicated string dictionary written ahead of the entity records. */
    static final class StringTable {
        private final Map<String, Integer> indices = new LinkedHashMap<>();

        void add(String s) {
            if (s != null) indices.putIfAbsent(s, indices.size());
        }

        int indexOf(String s) {
            Integer idx = (s != null) ? indices.get(s) : null;
            return (idx != null) ? idx : -1;
        }

        void write(DataOutputStream dos) throws IOException {
            dos.writeInt(indices.size());
            for (String s : indices.keySet()) dos.writeUTF(s);
        }

        static String[] read(DataInputStream dis) throws IOException {
            String[] table = new String[dis.readInt()];
            for (int i = 0; i < table.length; i++) table[i] = dis.readUTF();
            return table;
        }
    }

    // --- WRITE ---

    void collectStrings(Entity entity, StringTable table) {
        table.add(entity.name);
        for (Component comp : entity.getAllComponents()) {
            for (ComponentSchema.Property p : ComponentSchema.of(comp.getClass()).serializedProperties()) {
                if (!p.isPersistedIn(prefab, version)) continue;
                if (p.kind == ComponentSchema.ValueKind.ASSET_PATH) {
                    String path = SceneSerializer.sanitizeAssetPath((String) get(p, comp));
                    if (!path.isEmpty()) table.add(path);
                } else if (p.kind == ComponentSchema.ValueKind.STRING) {
                    table.add((String) get(p, comp));
                }
            }
        }
    }

    void writeEntity(DataOutputStream dos, Entity entity, StringTable table, AssetDatabase assetDb) throws IOException {
        dos.writeUTF(entity.id != null ? entity.id : "");
        dos.writeUTF(entity.parentId != null ? entity.parentId : "");
        dos.writeInt(table.indexOf(entity.name));

        List<Component> comps = entity.getAllComponents();
        dos.writeInt(comps.size());
        for (Component comp : comps) {
            dos.writeUTF(comp.getClass().getSimpleName());
            for (ComponentSchema.Property p : ComponentSchema.of(comp.getClass()).serializedProperties()) {
                if (p.isPersistedIn(prefab, version)) writeValue(dos, p, get(p, comp), table, assetDb);
            }
        }
    }

    private void writeValue(DataOutputStream dos, ComponentSchema.Property p, Object value, StringTable table, AssetDatabase assetDb) throws IOException {
        switch (p.kind) {
            case FLOAT -> dos.writeFloat((Float) value);
            case INT -> dos.writeInt((Integer) value);
            case BOOLEAN -> dos.writeBoolean((Boolean) value);
            case ENUM -> dos.writeUTF(value != null ? ((Enum<?>) value).name() : "");
            case STRING -> dos.writeInt(table.indexOf(value != null ? (String) value : ""));
            case ASSET_PATH -> {
                String path = (String) value;
                if (!prefab) writeUuid(dos, (assetDb != null && path != null) ? assetDb.resolveUuidByPath(path) : null);
                dos.writeInt(table.indexOf(SceneSerializer.sanitizeAssetPath(path)));
            }
            case STRING_MAP -> {
                @SuppressWarnings("unchecked")
                Map<String, String> map = (value != null) ? (Map<String, String>) value : Map.of();
                dos.writeInt(map.size());
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    dos.writeUTF(entry.getKey() != null ? entry.getKey() : "");
                    dos.writeUTF(entry.getValue() != null ? entry.getValue() : "");
                }
            }
            case UNSUPPORTED -> throw new IllegalStateException("Unsupported field " + p.name());
        }
    }

    // --- READ ---

    /** Reads one entity record; unknown or runtime-only component types carry no payload and are skipped. */
    Entity readEntity(DataInputStream dis, String[] table, AssetDatabase assetDb, String fallbackName) throws IOException {
        String id = dis.readUTF();
        String parentId = dis.readUTF();
        String name = stringAt(table, dis.readInt(), fallbackName);

        Entity entity = new Entity(id, name, null);
        entity.parentId = parentId;

        // Components pre-created by the Entity constructor or an onDeserialized hook are filled in rather than duplicated
        Set<Component> populated = Collections.newSetFromMap(new IdentityHashMap<>());
        int compCount = dis.readInt();
        for (int j = 0; j < compCount; j++) {
            ComponentSchema schema = ComponentSchema.forSerializedName(dis.readUTF());
            if (schema == null || !schema.isPersistent()) continue;

            Component comp = findUnpopulated(entity, schema.type(), populated);
            boolean created = comp == null;
            if (created) comp = schema.newInstance();

            for (ComponentSchema.Property p : schema.serializedProperties()) {
                if (p.isPersistedIn(prefab, version)) readValue(dis, p, comp, table, assetDb);
            }

            populated.add(comp);
            if (created) entity.addComponent(comp);
            comp.onDeserialized(entity);
        }
        return entity;
    }

    private void readValue(DataInputStream dis, ComponentSchema.Property p, Component comp, String[] table, AssetDatabase assetDb) throws IOException {
        try {
            switch (p.kind) {
                case FLOAT -> p.set(comp, dis.readFloat());
                case INT -> p.set(comp, dis.readInt());
                case BOOLEAN -> p.set(comp, dis.readBoolean());
                case ENUM -> {
                    String constant = dis.readUTF();
                    for (Object e : p.field.getType().getEnumConstants()) {
                        if (((Enum<?>) e).name().equals(constant)) {
                            p.set(comp, e);
                            break;
                        }
                    }
                }
                case STRING -> {
                    String current = (String) p.get(comp);
                    p.set(comp, stringAt(table, dis.readInt(), current != null ? current : ""));
                }
                case ASSET_PATH -> {
                    String uuid = prefab ? null : readUuid(dis);
                    String fallbackPath = stringAt(table, dis.readInt(), "");
                    String resolved = (assetDb != null && uuid != null) ? assetDb.resolvePathByUuid(uuid) : null;
                    if (resolved == null || resolved.isEmpty()) resolved = fallbackPath;
                    p.set(comp, resolveImportedFile(resolved, p.meta.assetType()));
                }
                case STRING_MAP -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> map = (Map<String, String>) p.get(comp);
                    if (map == null) {
                        map = new HashMap<>();
                        p.set(comp, map);
                    }
                    int count = dis.readInt();
                    for (int k = 0; k < count; k++) map.put(dis.readUTF(), dis.readUTF());
                }
                case UNSUPPORTED -> throw new IllegalStateException("Unsupported field " + p.name());
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- HELPERS ---

    private static Object get(ComponentSchema.Property p, Component comp) {
        try {
            return p.get(comp);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Component findUnpopulated(Entity entity, Class<?> type, Set<Component> populated) {
        for (Component c : entity.getAllComponents()) {
            if (c.getClass() == type && !populated.contains(c)) return c;
        }
        return null;
    }

    private static String stringAt(String[] table, int idx, String fallback) {
        return (idx >= 0 && idx < table.length) ? table[idx] : fallback;
    }

    private static String resolveImportedFile(String path, String assetType) {
        File file = switch (assetType.toUpperCase()) {
            case "TEXTURE" -> TextureImporter.resolveTextureFile(path);
            case "MESH", "MESH_3D" -> MeshImporter.resolveModelFile(path);
            default -> null;
        };
        return (file != null && file.exists()) ? file.getAbsolutePath() : path;
    }

    static void writeUuid(DataOutputStream dos, String uuidStr) throws IOException {
        if (uuidStr != null && !uuidStr.trim().isEmpty()) {
            try {
                UUID u = UUID.fromString(uuidStr.trim());
                dos.writeLong(u.getMostSignificantBits());
                dos.writeLong(u.getLeastSignificantBits());
                return;
            } catch (Exception ignored) {}
        }
        dos.writeLong(0L);
        dos.writeLong(0L);
    }

    static String readUuid(DataInputStream dis) throws IOException {
        long high = dis.readLong();
        long low = dis.readLong();
        if (high == 0L && low == 0L) return null;
        return new UUID(high, low).toString();
    }
}
