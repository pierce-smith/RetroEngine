package com.retro.engine.serialization;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.ecs.components.LightComponent;
import com.retro.engine.ecs.components.MaterialComponent;
import com.retro.engine.ecs.components.MeshRendererComponent;
import com.retro.engine.ecs.components.TransformComponent;
import com.retro.engine.editor.assets.AssetEntry;
import com.retro.engine.editor.assets.AssetType;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * SceneSerializer handles saving and loading .scene and .retroproj JSON files
 * for the Retro Engine Editor using pure Java 21+ JSON formatting.
 */
public class SceneSerializer {

    public record SceneLoadData(List<SceneEntity> entities, List<AssetEntry> assetDirectory) {}

    public static boolean saveScene(String filePath, List<SceneEntity> entities) {
        return saveScene(filePath, entities, null);
    }

    public static boolean saveScene(String filePath, List<SceneEntity> entities, List<AssetEntry> assetDirectory) {
        if (filePath == null || entities == null) return false;

        File file = new File(filePath);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"version\": \"1.0\",\n");

        // 1. Asset Directory Serialization
        json.append("  \"assetDirectory\": [\n");
        if (assetDirectory != null && !assetDirectory.isEmpty()) {
            for (int i = 0; i < assetDirectory.size(); i++) {
                AssetEntry asset = assetDirectory.get(i);
                json.append("    {\n");
                json.append("      \"name\": \"").append(escapeJson(asset.name)).append("\",\n");
                json.append("      \"absolutePath\": \"").append(escapeJson(asset.absolutePath)).append("\",\n");
                json.append("      \"type\": \"").append(asset.type.name()).append("\"\n");
                json.append("    }");
                if (i < assetDirectory.size() - 1) json.append(",");
                json.append("\n");
            }
        }
        json.append("  ],\n");

        // 2. Entity Graph Serialization
        json.append("  \"entityCount\": ").append(entities.size()).append(",\n");
        json.append("  \"entities\": [\n");

        for (int i = 0; i < entities.size(); i++) {
            SceneEntity entity = entities.get(i);
            json.append("    {\n");
            json.append("      \"id\": \"").append(escapeJson(entity.id)).append("\",\n");
            json.append("      \"name\": \"").append(escapeJson(entity.name)).append("\",\n");
            json.append("      \"components\": [\n");

            List<Component> compList = new ArrayList<>(entity.getAllComponents());
            for (int j = 0; j < compList.size(); j++) {
                Component comp = compList.get(j);
                json.append("        {\n");
                json.append("          \"type\": \"").append(comp.getClass().getName()).append("\",\n");

                if (comp instanceof TransformComponent tc) {
                    json.append("          \"posX\": ").append(tc.posX).append(",\n");
                    json.append("          \"posY\": ").append(tc.posY).append(",\n");
                    json.append("          \"posZ\": ").append(tc.posZ).append(",\n");
                    json.append("          \"rotX\": ").append(tc.rotX).append(",\n");
                    json.append("          \"rotY\": ").append(tc.rotY).append(",\n");
                    json.append("          \"rotZ\": ").append(tc.rotZ).append(",\n");
                    json.append("          \"scaleX\": ").append(tc.scaleX).append(",\n");
                    json.append("          \"scaleY\": ").append(tc.scaleY).append(",\n");
                    json.append("          \"scaleZ\": ").append(tc.scaleZ).append("\n");
                } else if (comp instanceof MeshRendererComponent mc) {
                    json.append("          \"meshAssetPath\": ").append(mc.meshAssetPath != null ? "\"" + escapeJson(mc.meshAssetPath) + "\"" : "null").append(",\n");
                    json.append("          \"isVisible\": ").append(mc.isVisible).append("\n");
                } else if (comp instanceof MaterialComponent mat) {
                    json.append("          \"textureAssetPath\": ").append(mat.textureAssetPath != null ? "\"" + escapeJson(mat.textureAssetPath) + "\"" : "null").append(",\n");
                    json.append("          \"textureName\": \"").append(escapeJson(mat.textureName)).append("\",\n");
                    json.append("          \"shaderName\": \"").append(escapeJson(mat.shaderName)).append("\"\n");
                } else if (comp instanceof LightComponent lc) {
                    json.append("          \"lightType\": \"").append(lc.type.name()).append("\",\n");
                    json.append("          \"intensity\": ").append(lc.intensity).append(",\n");
                    json.append("          \"r\": ").append(lc.r).append(",\n");
                    json.append("          \"g\": ").append(lc.g).append(",\n");
                    json.append("          \"b\": ").append(lc.b).append("\n");
                }
                json.append("        }");
                if (j < compList.size() - 1) json.append(",");
                json.append("\n");
            }
            json.append("      ]\n");
            json.append("    }");
            if (i < entities.size() - 1) json.append(",");
            json.append("\n");
        }

        json.append("  ]\n");
        json.append("}\n");

        try (FileWriter writer = new FileWriter(file)) {
            writer.write(json.toString());
            System.out.println("[SceneSerializer] Successfully saved scene state to: " + file.getAbsolutePath());
            return true;
        } catch (IOException e) {
            System.err.println("[SceneSerializer] ERROR: Failed to write scene file: " + e.getMessage());
            return false;
        }
    }

    public static List<SceneEntity> loadScene(String filePath) {
        return loadSceneData(filePath).entities();
    }

    public static SceneLoadData loadSceneData(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            System.err.println("[SceneSerializer] Scene file not found: " + filePath);
            return new SceneLoadData(new ArrayList<>(), new ArrayList<>());
        }

        try (FileReader reader = new FileReader(file)) {
            StringBuilder content = new StringBuilder();
            char[] buf = new char[1024];
            int numRead;
            while ((numRead = reader.read(buf)) != -1) {
                content.append(buf, 0, numRead);
            }

            String jsonText = content.toString();
            List<SceneEntity> entities = parseSceneJson(jsonText);
            List<AssetEntry> assetDirectory = parseAssetDirectoryJson(jsonText);
            System.out.println("[SceneSerializer] Successfully loaded " + entities.size() + " entity(ies) and " + assetDirectory.size() + " asset(s) from: " + file.getAbsolutePath());
            return new SceneLoadData(entities, assetDirectory);
        } catch (Exception e) {
            System.err.println("[SceneSerializer] ERROR: Failed to parse scene file: " + e.getMessage());
            return new SceneLoadData(new ArrayList<>(), new ArrayList<>());
        }
    }

    private static List<AssetEntry> parseAssetDirectoryJson(String json) {
        List<AssetEntry> assets = new ArrayList<>();
        int assetIdx = json.indexOf("\"assetDirectory\":");
        if (assetIdx == -1) return assets;

        int arrayStart = json.indexOf("[", assetIdx);
        if (arrayStart == -1) return assets;

        int depth = 0;
        int arrayEnd = -1;
        for (int i = arrayStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) {
                    arrayEnd = i;
                    break;
                }
            }
        }
        if (arrayEnd == -1) return assets;

        String assetBody = json.substring(arrayStart + 1, arrayEnd);
        List<String> assetBlocks = splitJsonObjects(assetBody);

        for (String block : assetBlocks) {
            String name = extractJsonValue(block, "name");
            String path = extractJsonValue(block, "absolutePath");
            String typeStr = extractJsonValue(block, "type");

            if (path != null && !path.equals("null")) {
                AssetType type = AssetType.UNKNOWN;
                if (typeStr != null) {
                    try { type = AssetType.valueOf(typeStr); } catch (Exception ignored) {}
                }
                if (name == null || name.isEmpty()) {
                    name = new File(path).getName();
                }
                assets.add(new AssetEntry(name, path, type));
            }
        }

        return assets;
    }

    private static List<SceneEntity> parseSceneJson(String json) {
        List<SceneEntity> entities = new ArrayList<>();

        int entitiesIdx = json.indexOf("\"entities\":");
        if (entitiesIdx == -1) return entities;

        int arrayStart = json.indexOf("[", entitiesIdx);
        if (arrayStart == -1) return entities;

        int depth = 0;
        int arrayEnd = -1;
        for (int i = arrayStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) {
                    arrayEnd = i;
                    break;
                }
            }
        }
        if (arrayEnd == -1) return entities;

        String entitiesBody = json.substring(arrayStart + 1, arrayEnd);
        List<String> entityBlocks = splitJsonObjects(entitiesBody);

        for (String block : entityBlocks) {
            String id = extractJsonValue(block, "id");
            String name = extractJsonValue(block, "name");

            if (id == null) id = "entity_" + (entities.size() + 1);
            if (name == null) name = "Entity_" + (entities.size() + 1);

            SceneEntity entity = new SceneEntity(id, name, null);

            int compIdx = block.indexOf("\"components\":");
            if (compIdx != -1) {
                int compStart = block.indexOf("[", compIdx);
                int compEnd = block.lastIndexOf("]");
                if (compStart != -1 && compEnd != -1 && compStart < compEnd) {
                    String compsBody = block.substring(compStart + 1, compEnd);
                    List<String> compBlocks = splitJsonObjects(compsBody);

                    for (String compBlock : compBlocks) {
                        String type = extractJsonValue(compBlock, "type");
                        if (type == null) continue;

                        if (type.contains("TransformComponent")) {
                            TransformComponent tc = entity.getTransform();
                            tc.posX = parseJsonFloat(compBlock, "posX", 0.0f);
                            tc.posY = parseJsonFloat(compBlock, "posY", 0.0f);
                            tc.posZ = parseJsonFloat(compBlock, "posZ", 0.0f);
                            tc.rotX = parseJsonFloat(compBlock, "rotX", 0.0f);
                            tc.rotY = parseJsonFloat(compBlock, "rotY", 0.0f);
                            tc.rotZ = parseJsonFloat(compBlock, "rotZ", 0.0f);
                            tc.scaleX = parseJsonFloat(compBlock, "scaleX", 1.0f);
                            tc.scaleY = parseJsonFloat(compBlock, "scaleY", 1.0f);
                            tc.scaleZ = parseJsonFloat(compBlock, "scaleZ", 1.0f);
                        } else if (type.contains("MeshRendererComponent")) {
                            String meshPath = extractJsonValue(compBlock, "meshAssetPath");
                            if (meshPath != null && !meshPath.equals("null")) {
                                MeshRendererComponent mc = entity.getComponent(MeshRendererComponent.class);
                                if (mc == null) {
                                    mc = new MeshRendererComponent(meshPath);
                                    entity.addComponent(mc);
                                } else {
                                    mc.meshAssetPath = meshPath;
                                }
                                String isVis = extractJsonValue(compBlock, "isVisible");
                                if (isVis != null) mc.isVisible = Boolean.parseBoolean(isVis);
                            }
                        } else if (type.contains("MaterialComponent")) {
                            MaterialComponent mat = entity.getComponent(MaterialComponent.class);
                            if (mat == null) {
                                mat = new MaterialComponent();
                                entity.addComponent(mat);
                            }
                            String texPath = extractJsonValue(compBlock, "textureAssetPath");
                            if (texPath != null && !texPath.equals("null")) mat.textureAssetPath = texPath;

                            String texName = extractJsonValue(compBlock, "textureName");
                            if (texName != null) mat.textureName = texName;

                            String shader = extractJsonValue(compBlock, "shaderName");
                            if (shader != null) mat.shaderName = shader;
                        } else if (type.contains("LightComponent")) {
                            LightComponent lc = new LightComponent();
                            String lType = extractJsonValue(compBlock, "lightType");
                            if (lType != null) {
                                try { lc.type = LightComponent.LightType.valueOf(lType); } catch (Exception ignored) {}
                            }
                            lc.intensity = parseJsonFloat(compBlock, "intensity", 1.0f);
                            lc.r = parseJsonFloat(compBlock, "r", 1.0f);
                            lc.g = parseJsonFloat(compBlock, "g", 0.95f);
                            lc.b = parseJsonFloat(compBlock, "b", 0.80f);
                            entity.addComponent(lc);
                        }
                    }
                }
            }

            entities.add(entity);
        }

        return entities;
    }

    private static List<String> splitJsonObjects(String body) {
        List<String> list = new ArrayList<>();
        int depth = 0;
        int start = -1;

        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start != -1) {
                    list.add(body.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return list;
    }

    private static String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;

        int valStart = idx + pattern.length();
        while (valStart < json.length() && Character.isWhitespace(json.charAt(valStart))) valStart++;
        if (valStart >= json.length()) return null;

        if (json.charAt(valStart) == '"') {
            int valEnd = json.indexOf('"', valStart + 1);
            if (valEnd == -1) return null;
            return unescapeJson(json.substring(valStart + 1, valEnd));
        } else {
            int valEnd = valStart;
            while (valEnd < json.length() && json.charAt(valEnd) != ',' && json.charAt(valEnd) != '\n' && json.charAt(valEnd) != '}') valEnd++;
            return json.substring(valStart, valEnd).trim();
        }
    }

    private static float parseJsonFloat(String json, String key, float defaultValue) {
        String valStr = extractJsonValue(json, key);
        if (valStr == null) return defaultValue;
        try { return Float.parseFloat(valStr); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\\"\"", "\"").replace("\\\\", "\\");
    }
}
