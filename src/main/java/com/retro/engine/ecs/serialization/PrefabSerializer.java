package com.retro.engine.ecs.serialization;

import com.retro.engine.assets.AssetDatabase;
import com.retro.engine.core.AtomicFile;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.EntitySpawner;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.AnimatorComponent;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

/**
 * Binary Prefab Template Serializer (.rprefab; the older .prefab.bin name still loads) enforcing fast memory stream I/O,
 * UUID asset indirection, entity tree duplication, and clean ID remapping during instantiation.
 */
public class PrefabSerializer {

    public static final int PREFAB_MAGIC = 0x50524546; // 'PREF' in ASCII
    // Version 2 layout: no asset UUIDs, reduced Material record (fields gated with prefabSince = 3 are omitted)
    public static final int PREFAB_VERSION = 9; // Version 9 (collision layers, mesh collider)

    public record PrefabTemplate(List<Entity> entities, String rootEntityId) {}

    public static final String EXTENSION = com.retro.engine.core.ProjectFile.PREFAB_EXTENSION;

    /** Save path for a prefab: always ends in .rprefab, replacing a legacy .prefab.bin / .prefab or any other extension. */
    public static String sanitizePrefabPath(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) return "Assets/Prefabs/Untitled" + EXTENSION;
        String targetPath = com.retro.engine.core.ProjectFile.toCurrentPrefabName(filePath.trim());
        String lower = targetPath.toLowerCase();
        if (lower.endsWith(EXTENSION)) return targetPath;
        if (lower.endsWith(".prefab")) return targetPath.substring(0, targetPath.length() - ".prefab".length()) + EXTENSION;
        int lastDot = targetPath.lastIndexOf('.');
        int lastSlash = Math.max(targetPath.lastIndexOf('/'), targetPath.lastIndexOf('\\'));
        return (lastDot > lastSlash ? targetPath.substring(0, lastDot) : targetPath) + EXTENSION;
    }

    /**
     * The prefab file for a path, trying the other name when the given one is missing: a script or scene written
     * before the rename says "X.prefab.bin" for what is now X.rprefab, and a mod or old export may still ship
     * X.prefab.bin when asked for X.rprefab. Returns the original path's file when neither exists.
     */
    public static File resolvePrefabFile(String path) {
        File file = new File(path);
        if (file.exists()) return file;
        String current = com.retro.engine.core.ProjectFile.toCurrentPrefabName(path);
        String alternate = !current.equals(path) ? current : com.retro.engine.core.ProjectFile.toLegacyPrefabName(path);
        if (!alternate.equals(path)) {
            File alt = new File(alternate);
            if (alt.exists()) return alt;
        }
        return file;
    }

    public static boolean savePrefab(String filePath, Entity rootEntity, List<Entity> sceneEntities, AssetDatabase assetDb) {
        if (filePath == null || rootEntity == null) return false;
        File file = new File(sanitizePrefabPath(filePath));

        List<Entity> prefabEntities = new ArrayList<>();
        prefabEntities.add(rootEntity);

        if (sceneEntities != null) {
            for (Entity e : sceneEntities) {
                if (e != rootEntity && isDescendantOf(e, rootEntity, sceneEntities)) {
                    prefabEntities.add(e);
                }
            }
        }

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bytes);

            dos.writeInt(PREFAB_MAGIC);
            dos.writeInt(PREFAB_VERSION);
            dos.writeInt(prefabEntities.size());
            dos.writeInt(0); // Checksum, filled in below

            ComponentBinaryCodec codec = ComponentBinaryCodec.prefab(PREFAB_VERSION);
            ComponentBinaryCodec.StringTable stringTable = new ComponentBinaryCodec.StringTable();
            for (Entity e : prefabEntities) {
                codec.collectStrings(e, stringTable);
            }
            stringTable.write(dos);

            dos.writeUTF(rootEntity.id != null ? rootEntity.id : "");

            for (Entity entity : prefabEntities) {
                codec.writeEntity(dos, entity, stringTable, assetDb);
            }

            byte[] finished = bytes.toByteArray();
            ComponentBinaryCodec.sealChecksum(finished);
            AtomicFile.write(file, finished);
            System.out.println("[PrefabSerializer] Saved prefab: " + file.getAbsolutePath());
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("[PrefabSerializer] Could not save " + file.getAbsolutePath() + ": " + e);
            e.printStackTrace();
            return false;
        }
    }

    public static List<Entity> instantiatePrefab(String filePath, SceneWorld world, AssetDatabase assetDb) {
        if (filePath == null || world == null) return new ArrayList<>();
        File file = resolvePrefabFile(filePath);
        if (!file.exists()) file = resolvePrefabFile("Assets/" + filePath);
        if (!file.exists()) file = resolvePrefabFile("Assets/Prefabs/" + filePath);
        if (!file.exists()) return new ArrayList<>();

        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
            int magic = dis.readInt();
            if (magic != PREFAB_MAGIC) {
                System.err.println("[PrefabSerializer] Not a prefab file: " + file.getAbsolutePath());
                return new ArrayList<>();
            }

            int version = dis.readInt();
            if (version > PREFAB_VERSION) {
                System.err.println("[PrefabSerializer] " + file.getName() + " was saved by a newer version of the engine (format " + version + ").");
                return new ArrayList<>();
            }
            int entityCount = dis.readInt();
            dis.readInt(); // Checksum, or padding in older files
            if (version >= ComponentBinaryCodec.PREFAB_SELF_CONTAINED_VERSION) ComponentBinaryCodec.verifyChecksum(bytes);

            String[] stringTable = ComponentBinaryCodec.StringTable.read(dis);

            dis.readUTF(); // Root entity id (the root is always the first record)
            List<Entity> templateEntities = new ArrayList<>();
            Map<String, String> idRemap = new HashMap<>();

            ComponentBinaryCodec codec = ComponentBinaryCodec.prefab(version);
            for (int i = 0; i < entityCount; i++) {
                Entity entity = codec.readEntity(dis, stringTable, assetDb, "PrefabEntity");
                String newId = "prefab_" + System.currentTimeMillis() + "_" + i + "_" + UUID.randomUUID().toString().substring(0, 6);
                idRemap.put(entity.id, newId);
                entity.id = newId;
                templateEntities.add(entity);
            }

            Entity rootInstantiated = null;
            for (Entity e : templateEntities) {
                if (e.parentId != null && idRemap.containsKey(e.parentId)) {
                    e.parentId = idRemap.get(e.parentId);
                } else {
                    e.parentId = null;
                }
                // Skeletons / controllers are not serialized: rebuild this instance's rig from its mesh, as a scene load
                // does, so every instantiated copy animates with its own state rather than sitting unposed
                String meshPath = e.getMeshAssetPath();
                AnimatorComponent animator = e.getComponent(AnimatorComponent.class);
                if ((animator == null || animator.skeleton == null) && meshPath != null && !meshPath.trim().isEmpty()) {
                    EntitySpawner.attachAnimatorIfRigged(e, meshPath);
                }
                world.addEntity(e, -1);
                if (rootInstantiated == null) rootInstantiated = e;
            }

            if (rootInstantiated != null) world.selectEntity(rootInstantiated);

            return templateEntities;
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    private static boolean isDescendantOf(Entity child, Entity ancestor, List<Entity> allEntities) {
        if (child == null || ancestor == null || child.parentId == null) return false;
        String curParentId = child.parentId;
        while (curParentId != null && !curParentId.isEmpty()) {
            if (curParentId.equalsIgnoreCase(ancestor.id)) return true;
            String nextParentId = null;
            for (Entity e : allEntities) {
                if (e.id.equalsIgnoreCase(curParentId)) {
                    nextParentId = e.parentId;
                    break;
                }
            }
            curParentId = nextParentId;
        }
        return false;
    }
}
