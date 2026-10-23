package com.retro.engine.scene;

import com.retro.engine.assets.AssetDatabase;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.importers.MeshImporter;
import com.retro.engine.assets.importers.TextureImporter;
import com.retro.engine.scene.components.*;

import java.io.*;
import java.util.*;

/**
 * Binary Prefab Template Serializer (.prefab.bin) enforcing fast memory stream I/O,
 * UUID asset indirection, entity tree duplication, and clean ID remapping during instantiation.
 */
public class PrefabSerializer {

    public static final int PREFAB_MAGIC = 0x50524546; // 'PREF' in ASCII

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
        String sanitizedPath = sanitizePrefabPath(filePath);
        File file = new File(sanitizedPath);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }

        // Gather root entity and all descendants
        List<Entity> prefabEntities = new ArrayList<>();
        prefabEntities.add(rootEntity);

        if (sceneEntities != null) {
            for (Entity e : sceneEntities) {
                if (e != rootEntity && e.isDescendantOf(rootEntity, sceneEntities)) {
                    prefabEntities.add(e);
                }
            }
        }

        try (FileOutputStream fos = new FileOutputStream(file);
             DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(fos))) {

            // 1. Header (16 Bytes)
            dos.writeInt(PREFAB_MAGIC); // 'PREF'
            dos.writeInt(1);            // Version
            dos.writeInt(prefabEntities.size());
            dos.writeInt(0);            // Padding

            // 2. Build String Dictionary
            List<String> stringTable = new ArrayList<>();
            for (Entity e : prefabEntities) {
                if (e.name != null && !stringTable.contains(e.name)) stringTable.add(e.name);

                String meshPath = SceneSerializer.sanitizeAssetPath(e.getMeshAssetPath());
                if (!meshPath.isEmpty() && !stringTable.contains(meshPath)) stringTable.add(meshPath);

                Material mat = e.getComponent(Material.class);
                if (mat != null) {
                    String texPath = SceneSerializer.sanitizeAssetPath(mat.textureAssetPath);
                    if (!texPath.isEmpty() && !stringTable.contains(texPath)) stringTable.add(texPath);
                    if (mat.textureName != null && !stringTable.contains(mat.textureName)) stringTable.add(mat.textureName);
                    if (mat.shaderName != null && !stringTable.contains(mat.shaderName)) stringTable.add(mat.shaderName);
                }

                AudioSource ac = e.getComponent(AudioSource.class);
                if (ac != null) {
                    String audioPath = SceneSerializer.sanitizeAssetPath(ac.audioAssetPath);
                    if (!audioPath.isEmpty() && !stringTable.contains(audioPath)) stringTable.add(audioPath);
                }

                ScriptComponent sc = e.getComponent(ScriptComponent.class);
                if (sc != null) {
                    String scriptPath = SceneSerializer.sanitizeAssetPath(sc.scriptAssetPath);
                    if (!scriptPath.isEmpty() && !stringTable.contains(scriptPath)) stringTable.add(scriptPath);
                    if (sc.scriptName != null && !stringTable.contains(sc.scriptName)) stringTable.add(sc.scriptName);
                }
            }

            dos.writeInt(stringTable.size());
            for (String s : stringTable) {
                dos.writeUTF(s != null ? s : "");
            }

            // Write Root Entity ID
            dos.writeUTF(rootEntity.id != null ? rootEntity.id : "");

            // 3. Write Entity Records
            for (Entity entity : prefabEntities) {
                dos.writeUTF(entity.id != null ? entity.id : "");
                dos.writeUTF(entity.parentId != null ? entity.parentId : "");
                dos.writeInt(stringTable.indexOf(entity.name));

                List<com.retro.engine.scene.components.Component> comps = entity.getAllComponents();
                dos.writeInt(comps.size());

                for (com.retro.engine.scene.components.Component comp : comps) {
                    String typeName = comp.getClass().getSimpleName();
                    dos.writeUTF(typeName);

                    if (comp instanceof Transform tc) {
                        dos.writeFloat(tc.posX); dos.writeFloat(tc.posY); dos.writeFloat(tc.posZ);
                        dos.writeFloat(tc.rotX); dos.writeFloat(tc.rotY); dos.writeFloat(tc.rotZ);
                        dos.writeFloat(tc.scaleX); dos.writeFloat(tc.scaleY); dos.writeFloat(tc.scaleZ);
                    } else if (comp instanceof MeshFilter mc) {
                        String cleanPath = SceneSerializer.sanitizeAssetPath(mc.meshAssetPath);
                        dos.writeInt(stringTable.indexOf(cleanPath));
                        dos.writeBoolean(mc.isVisible);
                    } else if (comp instanceof Material mat) {
                        String cleanPath = SceneSerializer.sanitizeAssetPath(mat.textureAssetPath);
                        dos.writeInt(stringTable.indexOf(cleanPath));
                        dos.writeInt(stringTable.indexOf(mat.textureName != null ? mat.textureName : ""));
                        dos.writeInt(stringTable.indexOf(mat.shaderName != null ? mat.shaderName : ""));
                    } else if (comp instanceof Light lc) {
                        dos.writeUTF(lc.type.name());
                        dos.writeFloat(lc.intensity);
                        dos.writeFloat(lc.r); dos.writeFloat(lc.g); dos.writeFloat(lc.b);
                    } else if (comp instanceof AudioSource ac) {
                        String cleanPath = SceneSerializer.sanitizeAssetPath(ac.audioAssetPath);
                        dos.writeInt(stringTable.indexOf(cleanPath));
                        dos.writeFloat(ac.volume);
                        dos.writeFloat(ac.pitch);
                        dos.writeBoolean(ac.loop);
                        dos.writeFloat(ac.minDistance);
                        dos.writeFloat(ac.maxDistance);
                        dos.writeFloat(ac.attenuation);
                        dos.writeUTF(ac.state.name());
                    } else if (comp instanceof Camera cc) {
                        dos.writeFloat(cc.fov);
                        dos.writeFloat(cc.nearPlane);
                        dos.writeFloat(cc.farPlane);
                        dos.writeBoolean(cc.isPrimary);
                    } else if (comp instanceof ScriptComponent sc) {
                        String cleanPath = SceneSerializer.sanitizeAssetPath(sc.scriptAssetPath);
                        dos.writeInt(stringTable.indexOf(cleanPath));
                        dos.writeInt(stringTable.indexOf(sc.scriptName != null ? sc.scriptName : ""));
                        dos.writeBoolean(sc.enabled);
                    } else if (comp instanceof RigidBodyComponent rb) {
                        dos.writeUTF(rb.motionType.name());
                        dos.writeFloat(rb.mass);
                        dos.writeBoolean(rb.useGravity);
                        dos.writeFloat(rb.linearDrag);
                        dos.writeFloat(rb.angularDrag);
                        dos.writeFloat(rb.friction);
                        dos.writeFloat(rb.restitution);
                    } else if (comp instanceof BoxCollider box) {
                        dos.writeFloat(box.sizeX); dos.writeFloat(box.sizeY); dos.writeFloat(box.sizeZ);
                        dos.writeFloat(box.offsetX); dos.writeFloat(box.offsetY); dos.writeFloat(box.offsetZ);
                        dos.writeBoolean(box.isTrigger);
                    } else if (comp instanceof SphereCollider sphere) {
                        dos.writeFloat(sphere.radius);
                        dos.writeFloat(sphere.offsetX); dos.writeFloat(sphere.offsetY); dos.writeFloat(sphere.offsetZ);
                        dos.writeBoolean(sphere.isTrigger);
                    } else if (comp instanceof CapsuleCollider capsule) {
                        dos.writeFloat(capsule.radius); dos.writeFloat(capsule.height);
                        dos.writeFloat(capsule.offsetX); dos.writeFloat(capsule.offsetY); dos.writeFloat(capsule.offsetZ);
                        dos.writeBoolean(capsule.isTrigger);
                    }
                }
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
        if (!file.exists()) {
            file = new File("Assets/" + filePath);
            if (!file.exists()) {
                file = new File("Assets/Prefabs/" + filePath);
            }
        }
        if (!file.exists()) {
            System.err.println("[PrefabSerializer] Error: Prefab file not found at " + filePath);
            return new ArrayList<>();
        }

        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            int magic = dis.readInt();
            if (magic != PREFAB_MAGIC) {
                System.err.println("[PrefabSerializer] Invalid prefab binary magic in " + file.getName());
                return new ArrayList<>();
            }

            int version = dis.readInt();
            int entityCount = dis.readInt();
            dis.readInt(); // Padding

            int stringCount = dis.readInt();
            String[] stringTable = new String[stringCount];
            for (int i = 0; i < stringCount; i++) {
                stringTable[i] = dis.readUTF();
            }

            String rootIdInFile = dis.readUTF();

            List<Entity> templateEntities = new ArrayList<>();
            Map<String, String> idRemap = new HashMap<>();

            for (int i = 0; i < entityCount; i++) {
                String oldId = dis.readUTF();
                String oldParentId = dis.readUTF();
                int nameIdx = dis.readInt();
                String name = (nameIdx >= 0 && nameIdx < stringCount) ? stringTable[nameIdx] : "PrefabEntity";

                String newId = "prefab_" + System.currentTimeMillis() + "_" + i + "_" + UUID.randomUUID().toString().substring(0, 6);
                idRemap.put(oldId, newId);

                Entity entity = new Entity(newId, name, null);
                entity.parentId = oldParentId;

                int compCount = dis.readInt();
                for (int j = 0; j < compCount; j++) {
                    String typeName = dis.readUTF();

                    if ("Transform".equalsIgnoreCase(typeName)) {
                        Transform tc = entity.getTransform();
                        if (tc != null) {
                            tc.posX = dis.readFloat(); tc.posY = dis.readFloat(); tc.posZ = dis.readFloat();
                            tc.rotX = dis.readFloat(); tc.rotY = dis.readFloat(); tc.rotZ = dis.readFloat();
                            tc.scaleX = dis.readFloat(); tc.scaleY = dis.readFloat(); tc.scaleZ = dis.readFloat();
                        }
                    } else if ("MeshFilter".equalsIgnoreCase(typeName)) {
                        int pathIdx = dis.readInt();
                        String meshPath = (pathIdx >= 0 && pathIdx < stringCount) ? stringTable[pathIdx] : "";
                        boolean isVisible = dis.readBoolean();
                        entity.setMeshAssetPath(meshPath);
                        MeshFilter mc = entity.getComponent(MeshFilter.class);
                        if (mc != null) mc.isVisible = isVisible;
                    } else if ("Material".equalsIgnoreCase(typeName)) {
                        int pathIdx = dis.readInt();
                        int nameIdxMat = dis.readInt();
                        int shaderIdx = dis.readInt();
                        String texPath = (pathIdx >= 0 && pathIdx < stringCount) ? stringTable[pathIdx] : "";
                        String texName = (nameIdxMat >= 0 && nameIdxMat < stringCount) ? stringTable[nameIdxMat] : "";
                        String shaderName = (shaderIdx >= 0 && shaderIdx < stringCount) ? stringTable[shaderIdx] : "";
                        entity.setTextureAsset(texPath, texName);
                        Material mat = entity.getComponent(Material.class);
                        if (mat != null && !shaderName.isEmpty()) mat.shaderName = shaderName;
                    } else if ("Light".equalsIgnoreCase(typeName)) {
                        Light lc = new Light();
                        lc.type = Light.LightType.valueOf(dis.readUTF());
                        lc.intensity = dis.readFloat();
                        lc.r = dis.readFloat(); lc.g = dis.readFloat(); lc.b = dis.readFloat();
                        entity.addComponent(lc);
                    } else if ("AudioSource".equalsIgnoreCase(typeName)) {
                        int pathIdx = dis.readInt();
                        String audioPath = (pathIdx >= 0 && pathIdx < stringCount) ? stringTable[pathIdx] : "";
                        AudioSource ac = new AudioSource(audioPath);
                        ac.setVolume(dis.readFloat());
                        ac.setPitch(dis.readFloat());
                        ac.loop = dis.readBoolean();
                        ac.setDistances(dis.readFloat(), dis.readFloat());
                        ac.attenuation = dis.readFloat();
                        try { ac.state = AudioSource.PlaybackState.valueOf(dis.readUTF()); } catch (Exception ignored) {}
                        entity.addComponent(ac);
                    } else if ("Camera".equalsIgnoreCase(typeName)) {
                        Camera cc = new Camera(dis.readFloat(), dis.readFloat(), dis.readFloat());
                        cc.isPrimary = dis.readBoolean();
                        entity.addComponent(cc);
                    } else if ("ScriptComponent".equalsIgnoreCase(typeName)) {
                        int pathIdx = dis.readInt();
                        int nameIdxSc = dis.readInt();
                        boolean enabled = dis.readBoolean();
                        String scriptPath = (pathIdx >= 0 && pathIdx < stringCount) ? stringTable[pathIdx] : "";
                        String scriptName = (nameIdxSc >= 0 && nameIdxSc < stringCount) ? stringTable[nameIdxSc] : "";
                        ScriptComponent sc = new ScriptComponent(scriptPath, scriptName);
                        sc.enabled = enabled;
                        entity.addComponent(sc);
                    } else if ("RigidBodyComponent".equalsIgnoreCase(typeName) || "RigidBody".equalsIgnoreCase(typeName)) {
                        RigidBodyComponent rb = new RigidBodyComponent();
                        try { rb.motionType = RigidBodyComponent.MotionType.valueOf(dis.readUTF()); } catch (Exception ignored) {}
                        rb.mass = dis.readFloat();
                        rb.useGravity = dis.readBoolean();
                        rb.linearDrag = dis.readFloat();
                        rb.angularDrag = dis.readFloat();
                        rb.friction = dis.readFloat();
                        rb.restitution = dis.readFloat();
                        entity.addComponent(rb);
                    } else if ("BoxCollider".equalsIgnoreCase(typeName) || "BoxColliderComponent".equalsIgnoreCase(typeName)) {
                        BoxCollider box = new BoxCollider();
                        box.sizeX = dis.readFloat(); box.sizeY = dis.readFloat(); box.sizeZ = dis.readFloat();
                        box.offsetX = dis.readFloat(); box.offsetY = dis.readFloat(); box.offsetZ = dis.readFloat();
                        box.isTrigger = dis.readBoolean();
                        entity.addComponent(box);
                    } else if ("SphereCollider".equalsIgnoreCase(typeName) || "SphereColliderComponent".equalsIgnoreCase(typeName)) {
                        SphereCollider sphere = new SphereCollider();
                        sphere.radius = dis.readFloat();
                        sphere.offsetX = dis.readFloat(); sphere.offsetY = dis.readFloat(); sphere.offsetZ = dis.readFloat();
                        sphere.isTrigger = dis.readBoolean();
                        entity.addComponent(sphere);
                    } else if ("CapsuleCollider".equalsIgnoreCase(typeName) || "CapsuleColliderComponent".equalsIgnoreCase(typeName)) {
                        CapsuleCollider capsule = new CapsuleCollider();
                        capsule.radius = dis.readFloat(); capsule.height = dis.readFloat();
                        capsule.offsetX = dis.readFloat(); capsule.offsetY = dis.readFloat(); capsule.offsetZ = dis.readFloat();
                        capsule.isTrigger = dis.readBoolean();
                        entity.addComponent(capsule);
                    }
                }
                templateEntities.add(entity);
            }

            // Remap parent IDs
            Entity rootInstantiated = null;
            for (Entity e : templateEntities) {
                if (e.parentId != null && idRemap.containsKey(e.parentId)) {
                    e.parentId = idRemap.get(e.parentId);
                } else {
                    e.parentId = null; // Sub-tree root
                }

                world.addEntity(e, -1);
                if (rootInstantiated == null) {
                    rootInstantiated = e;
                }
            }

            if (rootInstantiated != null) {
                world.selectEntity(rootInstantiated);
            }

            System.out.println("[PrefabSerializer] Instantiated binary prefab blueprint (" + templateEntities.size() + " entities) from: " + file.getAbsolutePath());
            return templateEntities;

        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
}
