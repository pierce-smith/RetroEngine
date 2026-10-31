package com.retro.engine.scene.serialization;

import com.retro.engine.assets.AssetDatabase;
import com.retro.engine.scene.components.media.AnimatorComponent;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.entity.EntitySpawner;
import com.retro.engine.scene.world.SceneWorld;

import java.io.*;
import java.util.*;

/**
 * Binary Prefab Template Serializer (.prefab.bin) enforcing fast memory stream I/O,
 * UUID asset indirection, entity tree duplication, and clean ID remapping during instantiation.
 */
public class PrefabSerializer {

    public static final int PREFAB_MAGIC = 0x50524546; // 'PREF' in ASCII
    // Version 2 layout: no asset UUIDs, reduced Material record (fields gated with prefabSince = 3 are omitted)
    public static final int PREFAB_VERSION = 4;

    public record PrefabTemplate(List<Entity> entities, String rootEntityId) {}

    public static String sanitizePrefabPath(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) return "Assets/Prefabs/Untitled.prefab.bin";
        String targetPath = filePath.trim();
        if (!targetPath.toLowerCase().endsWith(".prefab.bin") && !targetPath.toLowerCase().endsWith(".prefab")) {
            int lastDot = targetPath.lastIndexOf('.');
            if (lastDot != -1) {
                targetPath = targetPath.substring(0, lastDot) + ".prefab.bin";
            } else {
                targetPath = targetPath + ".prefab.bin";
            }
        }
        return targetPath;
    }

    public static boolean savePrefab(String filePath, Entity rootEntity, List<Entity> sceneEntities, AssetDatabase assetDb) {
        if (filePath == null || rootEntity == null) return false;
        File file = new File(sanitizePrefabPath(filePath));
        if (file.getParentFile() != null) file.getParentFile().mkdirs();

        List<Entity> prefabEntities = new ArrayList<>();
        prefabEntities.add(rootEntity);

        if (sceneEntities != null) {
            for (Entity e : sceneEntities) {
                if (e != rootEntity && isDescendantOf(e, rootEntity, sceneEntities)) {
                    prefabEntities.add(e);
                }
            }
        }

        try (FileOutputStream fos = new FileOutputStream(file);
             DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(fos))) {

            dos.writeInt(PREFAB_MAGIC);
            dos.writeInt(PREFAB_VERSION);
            dos.writeInt(prefabEntities.size());
            dos.writeInt(0);

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

            System.out.println("[PrefabSerializer] Saved binary prefab blueprint (.prefab.bin): " + file.getAbsolutePath());
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static List<Entity> instantiatePrefab(String filePath, SceneWorld world, AssetDatabase assetDb) {
        if (filePath == null || world == null) return new ArrayList<>();
        File file = new File(filePath);
        if (!file.exists()) file = new File("Assets/" + filePath);
        if (!file.exists()) file = new File("Assets/Prefabs/" + filePath);
        if (!file.exists()) return new ArrayList<>();

        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            int magic = dis.readInt();
            if (magic != PREFAB_MAGIC) return new ArrayList<>();

            int version = dis.readInt();
            int entityCount = dis.readInt();
            dis.readInt();

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
