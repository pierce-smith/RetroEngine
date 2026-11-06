package com.retro.engine.ecs.serialization;

import com.retro.engine.assets.AssetDatabase;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.core.AtomicFile;
import com.retro.engine.ecs.Entity;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure binary scene serializer (.rscene) enforcing fast memory stream I/O,
 * 128-bit UUID asset indirection, multi-channel material serialization,
 * and clean zero-JSON runtime scene graph persistence.
 */
public class SceneSerializer {

    public static final int BINARY_MAGIC = 0x52455452; // 'RETR' in ASCII
    public static final int BINARY_VERSION = 10;        // Version 10 (collision layers, mesh collider)

    /** {@code error} is null on success. A failed load has no entities, which must never be mistaken for an empty scene. */
    public record SceneLoadData(List<Entity> entities, List<AssetMeta> assetDirectory, String error) {
        public SceneLoadData(List<Entity> entities, List<AssetMeta> assetDirectory) {
            this(entities, assetDirectory, null);
        }

        static SceneLoadData failure(String error) {
            return new SceneLoadData(new ArrayList<>(), new ArrayList<>(), error);
        }

        public boolean failed() { return error != null; }
    }

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

    /**
     * The whole file is built in memory, then swapped in atomically, so nothing that goes wrong during a save can damage
     * the existing file. Returns false, leaving that file untouched, when the scene can't be written.
     */
    public static boolean saveBinaryScene(String filePath, List<Entity> entities, List<AssetMeta> assetDirectory, AssetDatabase assetDb) {
        if (filePath == null || filePath.trim().isEmpty()) return false;
        File file = new File(filePath);

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bytes);

            // 1. Header (16 Bytes)
            dos.writeInt(BINARY_MAGIC); // Magic 'RETR'
            dos.writeInt(BINARY_VERSION);
            dos.writeInt(entities != null ? entities.size() : 0);
            dos.writeInt(0);            // Checksum, filled in below

            if (entities != null && !entities.isEmpty()) {
                // 2. Build String Dictionary
                ComponentBinaryCodec codec = ComponentBinaryCodec.scene(BINARY_VERSION);
                ComponentBinaryCodec.StringTable stringTable = new ComponentBinaryCodec.StringTable();
                for (Entity e : entities) {
                    codec.collectStrings(e, stringTable);
                }
                stringTable.write(dos);

                // 3. Write Entity Records
                for (Entity entity : entities) {
                    codec.writeEntity(dos, entity, stringTable, assetDb);
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
            }

            byte[] finished = bytes.toByteArray();
            ComponentBinaryCodec.sealChecksum(finished);
            AtomicFile.write(file, finished);
            System.out.println("[SceneSerializer] Saved compiled binary scene (.rscene): " + filePath);
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("[SceneSerializer] Could not save " + filePath + ": " + e);
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

        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
            int magic = dis.readInt(); // 'RETR'
            if (magic != BINARY_MAGIC) {
                return SceneLoadData.failure("It is not a RetroEngine scene file.");
            }

            int version = dis.readInt();
            if (version > BINARY_VERSION) {
                return SceneLoadData.failure("It was saved by a newer version of the engine (format " + version + ", this version reads up to " + BINARY_VERSION + ").");
            }
            int entityCount = dis.readInt();
            dis.readInt(); // Checksum, or padding in older files
            if (version >= ComponentBinaryCodec.SCENE_SELF_CONTAINED_VERSION) ComponentBinaryCodec.verifyChecksum(bytes);

            // Empty scenes are written header-only (see saveBinaryScene)
            if (entityCount == 0 && dis.available() == 0) {
                System.out.println("[SceneSerializer] Loaded empty binary scene (.rscene): " + file.getAbsolutePath());
                return new SceneLoadData(entities, assets);
            }

            // Read String Table
            String[] stringTable = ComponentBinaryCodec.StringTable.read(dis);

            // Read Entities
            ComponentBinaryCodec codec = ComponentBinaryCodec.scene(version);
            for (int i = 0; i < entityCount; i++) {
                entities.add(codec.readEntity(dis, stringTable, assetDb, "Entity"));
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
                    // Legacy per-scene directory blocks are parsed for format compatibility only; the project
                    // manifest (.retro/asset_registry.bin) is the sole authority, so scene loads never mutate it.
                } catch (EOFException ignored) {}
            }

            postProcessHierarchy(entities);
            System.out.println("[SceneSerializer] Loaded binary scene (.rscene): " + file.getAbsolutePath());
            return new SceneLoadData(entities, assets);
        } catch (Exception e) {
            System.err.println("[SceneSerializer] Could not load " + file.getAbsolutePath() + ": " + e);
            e.printStackTrace();
            return SceneLoadData.failure(e instanceof EOFException ? "The file is damaged or incomplete." : String.valueOf(e.getMessage()));
        }
    }

    private static final String[] EDITOR_UI_SOUNDS = {
        "click.ogg", "open.ogg", "close.ogg", "spawn.ogg", "delete.ogg", "duplicate.ogg", "toggle.ogg"
    };

    /** True for editor-only files: legacy copies of UISoundPlayer clips under Audio/UI/ (now bundled in the jar at /audio/ui/), and Editor/ and Engine/ folders. */
    public static boolean isInternalEditorResource(String path) {
        if (path == null) return false;
        String lower = path.replace('\\', '/').toLowerCase();
        // Match whole file names inside Audio/UI/ so user clips like "door_open.ogg" are not swallowed
        for (String sound : EDITOR_UI_SOUNDS) {
            String rel = "audio/ui/" + sound;
            if (lower.equals(rel) || lower.endsWith("/" + rel)) return true;
        }
        return lower.startsWith("editor/") || lower.contains("/editor/") ||
               lower.startsWith("engine/") || lower.contains("/engine/");
    }

    // --- HELPER UTILITIES ---
    private static void writeUuidBinary(DataOutputStream dos, String uuidStr) throws IOException {
        ComponentBinaryCodec.writeUuid(dos, uuidStr);
    }

    private static String readUuidBinary(DataInputStream dis) throws IOException {
        return ComponentBinaryCodec.readUuid(dis);
    }

    /**
     * Rebuilds child links from each entity's parentId. Entity records are written in scene-list order, which is the
     * Hierarchy's sibling order, so child lists come back in the same order they were saved. A parent loop in a
     * corrupt file (which would hide every entity in it from the tree) is broken by moving the entity to the root.
     */
    private static void postProcessHierarchy(List<Entity> entities) {
        for (Entity e : entities) {
            if (e.parentId != null && !e.parentId.isEmpty()
                    && (e.parentId.equalsIgnoreCase(e.id) || e.isDescendantOf(e, entities))) {
                System.err.println("[SceneSerializer] Broke circular parent link on entity '" + e.name + "'");
                e.parentId = null;
            }
        }
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