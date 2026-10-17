package com.retro.engine.scene;

import com.retro.engine.assets.AssetDatabase;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.importers.TextureImporter;
import com.retro.engine.assets.importers.MeshImporter;
import com.retro.engine.scene.components.*;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pure binary scene serializer (.rscene) enforcing fast memory stream I/O,
 * 128-bit UUID asset indirection, and clean zero-JSON runtime scene graph persistence.
 */
public class SceneSerializer {

    public static final int BINARY_MAGIC = 0x52455452; // 'RETR' in ASCII

    public record SceneLoadData(List<Entity> entities, List<AssetMeta> assetDirectory) {}

    public static String sanitizeScenePath(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) return "Untitled.rscene";
        String targetPath = filePath.trim();
        if (!targetPath.toLowerCase().endsWith(".rscene")) {
            int lastDot = targetPath.lastIndexOf('.');
            if (lastDot != -1) {
                targetPath = targetPath.substring(0, lastDot) + ".rscene";
            } else {
                targetPath = targetPath + ".rscene";
            }
        }
        return targetPath;
    }

    public static String sanitizeAssetPath(String fullPath) {
        if (fullPath == null || fullPath.trim().isEmpty()) return "";
        String clean = fullPath.replace('\\', '/').trim();

        int assetsIdx = clean.toLowerCase().indexOf("assets/");
        if (assetsIdx != -1) {
            return clean.substring(assetsIdx);
        }

        if (clean.startsWith("/") || (clean.length() > 2 && clean.charAt(1) == ':')) {
            return new File(clean).getName();
        }

        return clean;
    }

    public static boolean saveScene(String filePath, List<Entity> entities) {
        return saveScene(filePath, entities, null, null);
    }

    public static boolean saveScene(String filePath, List<Entity> entities, List<AssetMeta> assetDirectory) {
        return saveScene(filePath, entities, assetDirectory, null);
    }

    public static boolean saveScene(String filePath, List<Entity> entities, List<AssetMeta> assetDirectory, AssetDatabase assetDb) {
        if (filePath == null || filePath.trim().isEmpty()) return false;
        String sanitizedPath = sanitizeScenePath(filePath);
        return saveBinaryScene(sanitizedPath, entities, assetDirectory, assetDb);
    }

    // --- BINARY SERIALIZATION (.rscene) ---
    public static boolean saveBinaryScene(String filePath, List<Entity> entities, List<AssetMeta> assetDirectory, AssetDatabase assetDb) {
        if (filePath == null || filePath.trim().isEmpty()) return false;
        File file = new File(filePath);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }

        try (FileOutputStream fos = new FileOutputStream(file);
             DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(fos))) {

            // 1. Header (16 Bytes)
            dos.writeInt(BINARY_MAGIC); // Magic 'RETR'
            dos.writeInt(1);            // Version
            dos.writeInt(entities != null ? entities.size() : 0);
            dos.writeInt(0);            // Reserved Padding

            if (entities == null || entities.isEmpty()) {
                return true;
            }

            // 2. Build String Dictionary (Sanitizing all paths)
            List<String> stringTable = new ArrayList<>();
            for (Entity e : entities) {
                if (e.name != null && !stringTable.contains(e.name)) stringTable.add(e.name);

                String meshPath = sanitizeAssetPath(e.getMeshAssetPath());
                if (!meshPath.isEmpty() && !stringTable.contains(meshPath)) stringTable.add(meshPath);

                Material mat = e.getComponent(Material.class);
                if (mat != null) {
                    String texPath = sanitizeAssetPath(mat.textureAssetPath);
                    if (!texPath.isEmpty() && !stringTable.contains(texPath)) stringTable.add(texPath);

                    if (mat.textureName != null && !stringTable.contains(mat.textureName)) stringTable.add(mat.textureName);
                    if (mat.shaderName != null && !stringTable.contains(mat.shaderName)) stringTable.add(mat.shaderName);
                }

                AudioSource ac = e.getComponent(AudioSource.class);
                if (ac != null) {
                    String audioPath = sanitizeAssetPath(ac.audioAssetPath);
                    if (!audioPath.isEmpty() && !stringTable.contains(audioPath)) stringTable.add(audioPath);
                }

                ScriptComponent sc = e.getComponent(ScriptComponent.class);
                if (sc != null) {
                    String scriptPath = sanitizeAssetPath(sc.scriptAssetPath);
                    if (!scriptPath.isEmpty() && !stringTable.contains(scriptPath)) stringTable.add(scriptPath);
                    if (sc.scriptName != null && !stringTable.contains(sc.scriptName)) stringTable.add(sc.scriptName);
                }
            }

            dos.writeInt(stringTable.size());
            for (String s : stringTable) {
                dos.writeUTF(s != null ? s : "");
            }

            // 3. Write Entity Records
            for (Entity entity : entities) {
                dos.writeUTF(entity.id != null ? entity.id : "");
                dos.writeUTF(entity.parentId != null ? entity.parentId : "");
                dos.writeInt(stringTable.indexOf(entity.name));

                List<Component> comps = entity.getAllComponents();
                dos.writeInt(comps.size());

                for (Component comp : comps) {
                    String typeName = comp.getClass().getSimpleName();
                    dos.writeUTF(typeName);

                    if (comp instanceof Transform tc) {
                        dos.writeFloat(tc.posX); dos.writeFloat(tc.posY); dos.writeFloat(tc.posZ);
                        dos.writeFloat(tc.rotX); dos.writeFloat(tc.rotY); dos.writeFloat(tc.rotZ);
                        dos.writeFloat(tc.scaleX); dos.writeFloat(tc.scaleY); dos.writeFloat(tc.scaleZ);
                    } else if (comp instanceof MeshFilter mc) {
                        String uuid = (assetDb != null && mc.meshAssetPath != null) ? assetDb.resolveUuidByPath(mc.meshAssetPath) : null;
                        writeUuidBinary(dos, uuid);
                        String cleanPath = sanitizeAssetPath(mc.meshAssetPath);
                        dos.writeInt(stringTable.indexOf(cleanPath));
                        dos.writeBoolean(mc.isVisible);
                    } else if (comp instanceof Material mat) {
                        String uuid = (assetDb != null && mat.textureAssetPath != null) ? assetDb.resolveUuidByPath(mat.textureAssetPath) : null;
                        writeUuidBinary(dos, uuid);
                        String cleanPath = sanitizeAssetPath(mat.textureAssetPath);
                        dos.writeInt(stringTable.indexOf(cleanPath));
                        dos.writeInt(stringTable.indexOf(mat.textureName != null ? mat.textureName : ""));
                        dos.writeInt(stringTable.indexOf(mat.shaderName != null ? mat.shaderName : ""));
                    } else if (comp instanceof Light lc) {
                        dos.writeUTF(lc.type.name());
                        dos.writeFloat(lc.intensity);
                        dos.writeFloat(lc.r); dos.writeFloat(lc.g); dos.writeFloat(lc.b);
                    } else if (comp instanceof AudioSource ac) {
                        String uuid = (assetDb != null && ac.audioAssetPath != null) ? assetDb.resolveUuidByPath(ac.audioAssetPath) : null;
                        writeUuidBinary(dos, uuid);
                        String cleanPath = sanitizeAssetPath(ac.audioAssetPath);
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
                        String uuid = (assetDb != null && sc.scriptAssetPath != null) ? assetDb.resolveUuidByPath(sc.scriptAssetPath) : null;
                        writeUuidBinary(dos, uuid);
                        String cleanPath = sanitizeAssetPath(sc.scriptAssetPath);
                        dos.writeInt(stringTable.indexOf(cleanPath));
                        dos.writeInt(stringTable.indexOf(sc.scriptName != null ? sc.scriptName : ""));
                        dos.writeBoolean(sc.enabled);
                    }
                }
            }

            // 4. Write Virtual Folder & Workspace Directory Entries
            List<AssetMeta> validVirtualAssets = new ArrayList<>();
            if (assetDirectory != null) {
                for (AssetMeta meta : assetDirectory) {
                    if (meta != null && !isInternalEditorResource(meta.absolutePath)) {
                        validVirtualAssets.add(meta);
                    }
                }
            }
            dos.writeInt(validVirtualAssets.size());
            for (AssetMeta meta : validVirtualAssets) {
                writeUuidBinary(dos, meta.uuid);
                dos.writeUTF(meta.name != null ? meta.name : "");
                dos.writeUTF(sanitizeAssetPath(meta.absolutePath));
                dos.writeUTF(meta.type != null ? meta.type.name() : "UNKNOWN");
                dos.writeUTF(meta.parentFolderPath != null ? meta.parentFolderPath : "");
            }

            System.out.println("[SceneSerializer] Saved compiled binary scene (.rscene): " + filePath);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static SceneLoadData loadSceneData(String filePath) {
        return loadSceneData(filePath, null);
    }

    public static SceneLoadData loadSceneData(String filePath, AssetDatabase assetDb) {
        if (filePath == null || filePath.trim().isEmpty()) return new SceneLoadData(new ArrayList<>(), new ArrayList<>());
        File file = new File(filePath);
        if (!file.exists()) return new SceneLoadData(new ArrayList<>(), new ArrayList<>());

        return loadBinaryScene(file, assetDb);
    }

    private static SceneLoadData loadBinaryScene(File file, AssetDatabase assetDb) {
        List<Entity> entities = new ArrayList<>();
        List<AssetMeta> assets = new ArrayList<>();

        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            int magic = dis.readInt(); // 'RETR'
            if (magic != BINARY_MAGIC) {
                System.err.println("[SceneSerializer] Error: File is not a valid binary .rscene file: " + file.getName());
                return new SceneLoadData(entities, assets);
            }

            int version = dis.readInt();
            int entityCount = dis.readInt();
            dis.readInt(); // Padding

            // Read String Table
            int stringCount = dis.readInt();
            String[] stringTable = new String[stringCount];
            for (int i = 0; i < stringCount; i++) {
                stringTable[i] = dis.readUTF();
            }

            // Read Entities
            for (int i = 0; i < entityCount; i++) {
                String entityId = dis.readUTF();
                String parentId = dis.readUTF();
                int nameIdx = dis.readInt();
                String name = (nameIdx >= 0 && nameIdx < stringCount) ? stringTable[nameIdx] : "Entity";

                Entity entity = new Entity(entityId, name, null);
                entity.parentId = parentId;

                int compCount = dis.readInt();
                for (int j = 0; j < compCount; j++) {
                    String typeName = dis.readUTF();

                    if ("Transform".equalsIgnoreCase(typeName) || "TransformComponent".equalsIgnoreCase(typeName)) {
                        Transform tc = entity.getTransform();
                        if (tc != null) {
                            tc.posX = dis.readFloat(); tc.posY = dis.readFloat(); tc.posZ = dis.readFloat();
                            tc.rotX = dis.readFloat(); tc.rotY = dis.readFloat(); tc.rotZ = dis.readFloat();
                            tc.scaleX = dis.readFloat(); tc.scaleY = dis.readFloat(); tc.scaleZ = dis.readFloat();
                        }
                    } else if ("MeshFilter".equalsIgnoreCase(typeName) || "MeshRendererComponent".equalsIgnoreCase(typeName)) {
                        String uuid = readUuidBinary(dis);
                        int pathIdx = dis.readInt();
                        String fallbackPath = (pathIdx >= 0 && pathIdx < stringCount) ? stringTable[pathIdx] : "";
                        boolean isVisible = dis.readBoolean();

                        String resolvedPath = (assetDb != null && uuid != null) ? assetDb.resolvePathByUuid(uuid) : null;
                        if (resolvedPath == null || resolvedPath.isEmpty()) resolvedPath = fallbackPath;

                        File meshFile = MeshImporter.resolveModelFile(resolvedPath);
                        if (meshFile != null && meshFile.exists()) {
                            resolvedPath = meshFile.getAbsolutePath();
                        }

                        entity.setMeshAssetPath(resolvedPath);
                        MeshFilter mc = entity.getComponent(MeshFilter.class);
                        if (mc != null) mc.isVisible = isVisible;
                    } else if ("Material".equalsIgnoreCase(typeName) || "MaterialComponent".equalsIgnoreCase(typeName)) {
                        String uuid = readUuidBinary(dis);
                        int pathIdx = dis.readInt();
                        int nameIdxMat = dis.readInt();
                        int shaderIdx = dis.readInt();

                        String fallbackPath = (pathIdx >= 0 && pathIdx < stringCount) ? stringTable[pathIdx] : "";
                        String texName = (nameIdxMat >= 0 && nameIdxMat < stringCount) ? stringTable[nameIdxMat] : "";
                        String shaderName = (shaderIdx >= 0 && shaderIdx < stringCount) ? stringTable[shaderIdx] : "";

                        String resolvedPath = (assetDb != null && uuid != null) ? assetDb.resolvePathByUuid(uuid) : null;
                        if (resolvedPath == null || resolvedPath.isEmpty()) resolvedPath = fallbackPath;

                        File texFile = TextureImporter.resolveTextureFile(resolvedPath);
                        if (texFile != null && texFile.exists()) {
                            resolvedPath = texFile.getAbsolutePath();
                        }

                        entity.setTextureAsset(resolvedPath, texName);
                        Material mat = entity.getComponent(Material.class);
                        if (mat != null && !shaderName.isEmpty()) mat.shaderName = shaderName;
                    } else if ("Light".equalsIgnoreCase(typeName) || "LightComponent".equalsIgnoreCase(typeName)) {
                        String lightTypeStr = dis.readUTF();
                        Light.LightType lt = Light.LightType.DIRECTIONAL;
                        try { lt = Light.LightType.valueOf(lightTypeStr); } catch (Exception ignored) {}

                        Light lc = new Light();
                        lc.type = lt;
                        lc.intensity = dis.readFloat();
                        lc.r = dis.readFloat(); lc.g = dis.readFloat(); lc.b = dis.readFloat();
                        entity.addComponent(lc);
                    } else if ("AudioSource".equalsIgnoreCase(typeName) || "AudioSourceComponent".equalsIgnoreCase(typeName)) {
                        String uuid = readUuidBinary(dis);
                        int pathIdx = dis.readInt();
                        String fallbackPath = (pathIdx >= 0 && pathIdx < stringCount) ? stringTable[pathIdx] : "";

                        String resolvedPath = (assetDb != null && uuid != null) ? assetDb.resolvePathByUuid(uuid) : null;
                        if (resolvedPath == null || resolvedPath.isEmpty()) resolvedPath = fallbackPath;

                        AudioSource ac = new AudioSource(resolvedPath);
                        ac.setVolume(dis.readFloat());
                        ac.setPitch(dis.readFloat());
                        ac.loop = dis.readBoolean();
                        ac.setDistances(dis.readFloat(), dis.readFloat());
                        ac.attenuation = dis.readFloat();
                        String stateStr = dis.readUTF();
                        try { ac.state = AudioSource.PlaybackState.valueOf(stateStr); } catch (Exception ignored) {}
                        entity.addComponent(ac);
                    } else if ("Camera".equalsIgnoreCase(typeName) || "CameraComponent".equalsIgnoreCase(typeName)) {
                        Camera cc = new Camera(dis.readFloat(), dis.readFloat(), dis.readFloat());
                        cc.isPrimary = dis.readBoolean();
                        entity.addComponent(cc);
                    } else if ("ScriptComponent".equalsIgnoreCase(typeName) || "Script".equalsIgnoreCase(typeName)) {
                        String uuid = readUuidBinary(dis);
                        int pathIdx = dis.readInt();
                        int nameIdxSc = dis.readInt();
                        boolean enabled = dis.readBoolean();

                        String fallbackPath = (pathIdx >= 0 && pathIdx < stringCount) ? stringTable[pathIdx] : "";
                        String scriptName = (nameIdxSc >= 0 && nameIdxSc < stringCount) ? stringTable[nameIdxSc] : "";

                        String resolvedPath = (assetDb != null && uuid != null) ? assetDb.resolvePathByUuid(uuid) : null;
                        if (resolvedPath == null || resolvedPath.isEmpty()) resolvedPath = fallbackPath;

                        ScriptComponent sc = new ScriptComponent(resolvedPath, scriptName);
                        sc.enabled = enabled;
                        entity.addComponent(sc);
                    }
                }
                entities.add(entity);
            }

            // 4. Read Virtual Folder & Workspace Directory Entries
            if (dis.available() > 0) {
                try {
                    int virtualAssetCount = dis.readInt();
                    for (int k = 0; k < virtualAssetCount; k++) {
                        String uuid = readUuidBinary(dis);
                        String name = dis.readUTF();
                        String path = dis.readUTF();
                        String typeStr = dis.readUTF();
                        String parentFolder = dis.readUTF();

                        if (!isInternalEditorResource(path)) {
                            com.retro.engine.assets.AssetType type = com.retro.engine.assets.AssetType.UNKNOWN;
                            try { type = com.retro.engine.assets.AssetType.valueOf(typeStr); } catch (Exception ignored) {}
                            AssetMeta meta = new AssetMeta(uuid, name, path, type, parentFolder);
                            assets.add(meta);
                        }
                    }
                    if (assetDb != null) {
                        assetDb.restoreAssetDirectory(assets);
                    }
                } catch (EOFException ignored) {}
            }

            postProcessHierarchy(entities);
            System.out.println("[SceneSerializer] Loaded binary scene (.rscene): " + file.getAbsolutePath());
            return new SceneLoadData(entities, assets);
        } catch (Exception e) {
            e.printStackTrace();
            return new SceneLoadData(new ArrayList<>(), new ArrayList<>());
        }
    }

    private static boolean isInternalEditorResource(String path) {
        if (path == null) return false;
        String lower = path.replace('\\', '/').toLowerCase();
        return lower.endsWith("click.ogg") || lower.endsWith("open.ogg") ||
               lower.endsWith("close.ogg") || lower.endsWith("spawn.ogg") ||
               lower.endsWith("delete.ogg") || lower.endsWith("duplicate.ogg") ||
               lower.endsWith("toggle.ogg") || lower.startsWith("editor/") ||
               lower.contains("/editor/") || lower.startsWith("engine/") ||
               lower.contains("/engine/");
    }

    // --- HELPER UTILITIES ---
    private static void writeUuidBinary(DataOutputStream dos, String uuidStr) throws IOException {
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

    private static String readUuidBinary(DataInputStream dis) throws IOException {
        long high = dis.readLong();
        long low = dis.readLong();
        if (high == 0L && low == 0L) return null;
        return new UUID(high, low).toString();
    }

    private static void postProcessHierarchy(List<Entity> entities) {
        for (Entity e : entities) {
            if (e.parentId != null && !e.parentId.isEmpty()) {
                for (Entity p : entities) {
                    if (p.id.equalsIgnoreCase(e.parentId)) {
                        p.addChildId(e.id);
                        break;
                    }
                }
            }
        }
    }
}
