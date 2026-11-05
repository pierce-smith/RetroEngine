package com.retro.engine.ecs.serialization;

import com.retro.engine.assets.AssetDatabase;
import com.retro.engine.assets.MeshImporter;
import com.retro.engine.assets.TextureImporter;
import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.ComponentSchema;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.UnknownComponent;
import com.retro.engine.modding.ModLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Descriptor-driven binary codec for entity records shared by {@link SceneSerializer} and {@link PrefabSerializer}.
 * Each component is written as its simple class name, the byte length of its payload, then the payload: its persisted
 * {@link ComponentSchema} properties in declaration order. Floats, ints and booleans are raw values, enums are UTF
 * names, strings are length + UTF-8 bytes, asset paths are [128-bit UUID (scenes only)] + string, and string maps are
 * count + UTF pairs.
 *
 * <p>Payloads refer to nothing outside themselves, which is what makes a component whose type is missing safe: its
 * bytes are skipped by length and carried through saves unchanged (see {@link UnknownComponent}). A payload that ends
 * early leaves the remaining fields at their defaults, so a mod can append fields without breaking its old scenes.
 *
 * <p>Older files have no lengths and store strings as string-table indices. They still load; they can't skip unknown
 * types. Both file types keep a CRC-32 in the header's fourth int, covering every other byte of the file.
 */
final class ComponentBinaryCodec {

    /** First format versions with length-prefixed, self-contained components and a checksum. */
    static final int SCENE_SELF_CONTAINED_VERSION = 9;
    static final int PREFAB_SELF_CONTAINED_VERSION = 8;

    private static final int HEADER_CHECKSUM_OFFSET = 12;

    private final boolean prefab;
    private final int version;
    private final boolean selfContained;

    private ComponentBinaryCodec(boolean prefab, int version) {
        this.prefab = prefab;
        this.version = version;
        this.selfContained = version >= (prefab ? PREFAB_SELF_CONTAINED_VERSION : SCENE_SELF_CONTAINED_VERSION);
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

    /** Prepares an entity for writing: its name goes in the string table, and components encode derived state. */
    void collectStrings(Entity entity, StringTable table) {
        table.add(entity.name);
        for (Component comp : entity.getAllComponents()) comp.onBeforeSerialize();
    }

    void writeEntity(DataOutputStream dos, Entity entity, StringTable table, AssetDatabase assetDb) throws IOException {
        dos.writeUTF(entity.id != null ? entity.id : "");
        dos.writeUTF(entity.parentId != null ? entity.parentId : "");
        dos.writeInt(table.indexOf(entity.name));

        List<Component> comps = entity.getAllComponents();
        dos.writeInt(comps.size());
        for (Component comp : comps) {
            if (comp instanceof UnknownComponent unknown) {
                dos.writeUTF(unknown.typeName());
                dos.writeInt(unknown.payload().length);
                dos.write(unknown.payload());
                continue;
            }
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(payload);
            for (ComponentSchema.Property p : ComponentSchema.of(comp.getClass()).serializedProperties()) {
                if (p.isPersistedIn(prefab, version)) writeValue(out, p, get(p, comp), assetDb);
            }
            dos.writeUTF(comp.getClass().getSimpleName());
            dos.writeInt(payload.size());
            payload.writeTo(dos);
        }
    }

    private void writeValue(DataOutputStream dos, ComponentSchema.Property p, Object value, AssetDatabase assetDb) throws IOException {
        switch (p.kind) {
            case FLOAT -> dos.writeFloat((Float) value);
            case INT -> dos.writeInt((Integer) value);
            case BOOLEAN -> dos.writeBoolean((Boolean) value);
            case ENUM -> dos.writeUTF(value != null ? ((Enum<?>) value).name() : "");
            case STRING -> writeText(dos, (String) value);
            case ASSET_PATH -> {
                String path = (String) value;
                if (!prefab) writeUuid(dos, (assetDb != null && path != null) ? assetDb.resolveUuidByPath(path) : null);
                writeText(dos, SceneSerializer.sanitizeAssetPath(path));
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
            case BYTES -> {
                byte[] bytes = (value != null) ? (byte[]) value : new byte[0];
                dos.writeInt(bytes.length);
                dos.write(bytes);
            }
            case UNSUPPORTED -> throw new IllegalStateException("Unsupported field " + p.name());
        }
    }

    /** -1 marks null. Not writeUTF, which fails on strings over 64 KB. */
    private static void writeText(DataOutputStream dos, String text) throws IOException {
        if (text == null) {
            dos.writeInt(-1);
            return;
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        dos.writeInt(bytes.length);
        dos.write(bytes);
    }

    // --- CHECKSUM ---

    private static int checksum(byte[] file) {
        CRC32 crc = new CRC32();
        crc.update(file, 0, HEADER_CHECKSUM_OFFSET);
        crc.update(file, HEADER_CHECKSUM_OFFSET + 4, file.length - HEADER_CHECKSUM_OFFSET - 4);
        return (int) crc.getValue();
    }

    /** Stores the checksum of a finished file in its header. */
    static void sealChecksum(byte[] file) {
        ByteBuffer.wrap(file).putInt(HEADER_CHECKSUM_OFFSET, checksum(file));
    }

    /** Throws when a finished file doesn't match its header's checksum. */
    static void verifyChecksum(byte[] file) throws IOException {
        if (ByteBuffer.wrap(file).getInt(HEADER_CHECKSUM_OFFSET) != checksum(file)) {
            throw new IOException("The file is damaged (checksum mismatch).");
        }
    }

    // --- READ ---

    /** Reads one entity record. Components of unknown types are kept as {@link UnknownComponent}s when the format allows it. */
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
            String typeName = dis.readUTF();
            ComponentSchema schema = ComponentSchema.forSerializedName(typeName);
            DataInputStream in = dis;
            if (selfContained) {
                byte[] payload = readBytes(dis, typeName);
                if (schema == null) {
                    System.err.println("[ComponentBinaryCodec] Keeping unknown component '" + typeName + "' on '" + name + "'. Is a mod missing?");
                    entity.addComponent(new UnknownComponent(typeName, payload));
                    continue;
                }
                in = new DataInputStream(new ByteArrayInputStream(payload));
            }
            // Files older than a component's first persisted field wrote it without payload: rebuild it as runtime-only
            if (schema == null || !schema.isPersistent() || !schema.hasPropertiesPersistedIn(prefab, version)) continue;

            Component comp = findUnpopulated(entity, schema.type(), populated);
            boolean created = comp == null;
            if (created) comp = schema.newInstance();

            for (ComponentSchema.Property p : schema.serializedProperties()) {
                if (selfContained && in.available() == 0) break;
                if (p.isPersistedIn(prefab, version)) readValue(in, p, comp, table, assetDb);
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
                    if (selfContained) {
                        String text = readText(dis);
                        p.set(comp, text != null ? text : current != null ? current : "");
                    } else {
                        p.set(comp, stringAt(table, dis.readInt(), current != null ? current : ""));
                    }
                }
                case ASSET_PATH -> {
                    String uuid = prefab ? null : readUuid(dis);
                    String fallbackPath;
                    if (selfContained) {
                        String text = readText(dis);
                        fallbackPath = text != null ? text : "";
                    } else {
                        fallbackPath = stringAt(table, dis.readInt(), "");
                    }
                    String resolved = (assetDb != null && uuid != null) ? assetDb.resolvePathByUuid(uuid) : null;
                    if (resolved == null || resolved.isEmpty()) resolved = fallbackPath;
                    // Mod VFS overlays replace vanilla files at the same Assets/-relative path (no-op without mods)
                    p.set(comp, ModLoader.resolveAssetOverride(resolveImportedFile(resolved, p.meta.assetType())));
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
                case BYTES -> {
                    p.set(comp, readBytes(dis, p.name()));
                }
                case UNSUPPORTED -> throw new IllegalStateException("Unsupported field " + p.name());
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Readers always sit on an in-memory file, so available() is the true number of bytes left. */
    private static byte[] readBytes(DataInputStream dis, String what) throws IOException {
        int length = dis.readInt();
        if (length < 0 || length > dis.available()) throw new IOException("Corrupt length " + length + " for " + what);
        byte[] bytes = new byte[length];
        dis.readFully(bytes);
        return bytes;
    }

    private static String readText(DataInputStream dis) throws IOException {
        int length = dis.readInt();
        if (length == -1) return null;
        if (length < 0 || length > dis.available()) throw new IOException("Corrupt string length " + length);
        byte[] bytes = new byte[length];
        dis.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
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
