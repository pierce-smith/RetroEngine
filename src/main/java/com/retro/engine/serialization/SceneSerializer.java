package com.retro.engine.serialization;

import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.ecs.components.*;
import com.retro.engine.editor.assets.AssetEntry;
import com.retro.engine.editor.assets.AssetType;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class SceneSerializer {

    public record SceneLoadData(List<SceneEntity> entities, List<AssetEntry> assetDirectory) {}

    public static boolean saveScene(String filePath, List<SceneEntity> entities) {
        return saveScene(filePath, entities, null);
    }

    public static boolean saveScene(String filePath, List<SceneEntity> entities, List<AssetEntry> assetDirectory) {
        return saveSceneToFile(filePath, entities, assetDirectory);
    }

    public static boolean saveSceneToFile(String filePath, List<SceneEntity> entities, List<AssetEntry> assetDirectory) {
        if (filePath == null || filePath.trim().isEmpty()) return false;

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"version\": \"1.0\",\n");

        // Serialize Asset Directory
        json.append("  \"assetDirectory\": [\n");
        if (assetDirectory != null) {
            for (int i = 0; i < assetDirectory.size(); i++) {
                AssetEntry entry = assetDirectory.get(i);
                json.append("    {\n");
                json.append("      \"name\": \"").append(escapeJson(entry.name)).append("\",\n");
                json.append("      \"absolutePath\": \"").append(escapeJson(entry.absolutePath)).append("\",\n");
                json.append("      \"type\": \"").append(entry.type.name()).append("\",\n");
                json.append("      \"parentFolderPath\": \"").append(escapeJson(entry.parentFolderPath != null ? entry.parentFolderPath : "")).append("\"\n");
                json.append("    }").append(i < assetDirectory.size() - 1 ? ",\n" : "\n");
            }
        }
        json.append("  ],\n");

        // Serialize Entities
        json.append("  \"entities\": [\n");
        if (entities != null) {
            for (int i = 0; i < entities.size(); i++) {
                SceneEntity entity = entities.get(i);
                json.append("    {\n");
                json.append("      \"id\": \"").append(escapeJson(entity.id)).append("\",\n");
                json.append("      \"name\": \"").append(escapeJson(entity.name)).append("\",\n");
                json.append("      \"components\": [\n");

                List<com.retro.engine.ecs.Component> comps = entity.getAllComponents();
                for (int j = 0; j < comps.size(); j++) {
                    com.retro.engine.ecs.Component comp = comps.get(j);
                    json.append("        {\n");
                    json.append("          \"type\": \"").append(comp.getClass().getSimpleName()).append("\",\n");
                    if (comp instanceof TransformComponent tc) {
                        json.append(String.format("          \"posX\": %.4f, \"posY\": %.4f, \"posZ\": %.4f,\n", tc.posX, tc.posY, tc.posZ));
                        json.append(String.format("          \"rotX\": %.4f, \"rotY\": %.4f, \"rotZ\": %.4f,\n", tc.rotX, tc.rotY, tc.rotZ));
                        json.append(String.format("          \"scaleX\": %.4f, \"scaleY\": %.4f, \"scaleZ\": %.4f\n", tc.scaleX, tc.scaleY, tc.scaleZ));
                    } else if (comp instanceof MeshRendererComponent mc) {
                        json.append("          \"meshAssetPath\": \"").append(escapeJson(mc.meshAssetPath)).append("\",\n");
                        json.append("          \"isVisible\": ").append(mc.isVisible).append("\n");
                    } else if (comp instanceof MaterialComponent mat) {
                        json.append("          \"textureAssetPath\": \"").append(escapeJson(mat.textureAssetPath)).append("\",\n");
                        json.append("          \"textureName\": \"").append(escapeJson(mat.textureName)).append("\",\n");
                        json.append("          \"shaderName\": \"").append(escapeJson(mat.shaderName)).append("\"\n");
                    } else if (comp instanceof LightComponent lc) {
                        json.append("          \"lightType\": \"").append(lc.type.name()).append("\",\n");
                        json.append(String.format("          \"intensity\": %.4f,\n", lc.intensity));
                        json.append(String.format("          \"r\": %.4f, \"g\": %.4f, \"b\": %.4f\n", lc.r, lc.g, lc.b));
                    } else if (comp instanceof CameraComponent cc) {
                        json.append(String.format("          \"fov\": %.4f,\n", cc.fov));
                        json.append(String.format("          \"nearPlane\": %.4f, \"farPlane\": %.4f,\n", cc.nearPlane, cc.farPlane));
                        json.append("          \"isPrimary\": ").append(cc.isPrimary).append("\n");
                    }
                    json.append("        }").append(j < comps.size() - 1 ? ",\n" : "\n");
                }
                json.append("      ]\n");
                json.append("    }").append(i < entities.size() - 1 ? ",\n" : "\n");
            }
        }
        json.append("  ]\n");
        json.append("}\n");

        try {
            File file = new File(filePath);
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(json.toString());
                return true;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static SceneLoadData loadSceneData(String filePath) {
        return loadSceneFromFile(filePath);
    }

    public static SceneLoadData loadSceneFromFile(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) return new SceneLoadData(new ArrayList<>(), new ArrayList<>());
        File file = new File(filePath);
        if (!file.exists()) return new SceneLoadData(new ArrayList<>(), new ArrayList<>());

        try {
            String jsonText = new String(Files.readAllBytes(Paths.get(filePath)));
            return parseSceneJson(jsonText);
        } catch (Exception e) {
            e.printStackTrace();
            return new SceneLoadData(new ArrayList<>(), new ArrayList<>());
        }
    }

    public static SceneLoadData parseSceneJson(String jsonText) {
        if (jsonText == null || jsonText.trim().isEmpty()) return new SceneLoadData(new ArrayList<>(), new ArrayList<>());

        List<AssetEntry> assets = new ArrayList<>();
        List<SceneEntity> entities = new ArrayList<>();

        // 1. Parse assetDirectory array if present
        int assetDirIdx = jsonText.indexOf("\"assetDirectory\"");
        if (assetDirIdx != -1) {
            int arrayStart = jsonText.indexOf('[', assetDirIdx);
            int arrayEnd = jsonText.indexOf(']', arrayStart);
            if (arrayStart != -1 && arrayEnd != -1) {
                String arrayContent = jsonText.substring(arrayStart + 1, arrayEnd);
                List<String> objects = splitJsonObjects(arrayContent);
                for (String obj : objects) {
                    String name = parseJsonString(obj, "name");
                    String absolutePath = parseJsonString(obj, "absolutePath");
                    String typeStr = parseJsonString(obj, "type");
                    String parentFolderPath = parseJsonString(obj, "parentFolderPath");

                    if (typeStr != null && !typeStr.isEmpty()) {
                        try {
                            AssetType type = AssetType.valueOf(typeStr);
                            assets.add(new AssetEntry(name, absolutePath, type, parentFolderPath));
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        // 2. Parse entities array
        int entitiesIdx = jsonText.indexOf("\"entities\"");
        if (entitiesIdx != -1) {
            int arrayStart = jsonText.indexOf('[', entitiesIdx);
            if (arrayStart != -1) {
                String arrayContent = jsonText.substring(arrayStart + 1);
                List<String> entityObjs = splitJsonObjects(arrayContent);
                for (String entStr : entityObjs) {
                    String id = parseJsonString(entStr, "id");
                    String name = parseJsonString(entStr, "name");
                    SceneEntity entity = new SceneEntity((id != null) ? id : java.util.UUID.randomUUID().toString(), (name != null) ? name : "Entity", null);

                    int compIdx = entStr.indexOf("\"components\"");
                    if (compIdx != -1) {
                        int compStart = entStr.indexOf('[', compIdx);
                        if (compStart != -1) {
                            String compContent = entStr.substring(compStart + 1);
                            List<String> compObjs = splitJsonObjects(compContent);
                            for (String compStr : compObjs) {
                                String typeStr = parseJsonString(compStr, "type");
                                if ("TransformComponent".equalsIgnoreCase(typeStr)) {
                                    TransformComponent tc = entity.getTransform();
                                    if (tc != null) {
                                        tc.posX = parseJsonFloat(compStr, "posX", tc.posX);
                                        tc.posY = parseJsonFloat(compStr, "posY", tc.posY);
                                        tc.posZ = parseJsonFloat(compStr, "posZ", tc.posZ);
                                        tc.rotX = parseJsonFloat(compStr, "rotX", tc.rotX);
                                        tc.rotY = parseJsonFloat(compStr, "rotY", tc.rotY);
                                        tc.rotZ = parseJsonFloat(compStr, "rotZ", tc.rotZ);
                                        tc.scaleX = parseJsonFloat(compStr, "scaleX", tc.scaleX);
                                        tc.scaleY = parseJsonFloat(compStr, "scaleY", tc.scaleY);
                                        tc.scaleZ = parseJsonFloat(compStr, "scaleZ", tc.scaleZ);
                                    }
                                } else if ("MeshRendererComponent".equalsIgnoreCase(typeStr)) {
                                    String meshPath = parseJsonString(compStr, "meshAssetPath");
                                    entity.setMeshAssetPath(meshPath);
                                    String isVis = parseJsonString(compStr, "isVisible");
                                    if (isVis != null) {
                                        MeshRendererComponent mc = entity.getComponent(MeshRendererComponent.class);
                                        if (mc != null) mc.isVisible = Boolean.parseBoolean(isVis);
                                    }
                                } else if ("MaterialComponent".equalsIgnoreCase(typeStr)) {
                                    String texPath = parseJsonString(compStr, "textureAssetPath");
                                    String texName = parseJsonString(compStr, "textureName");
                                    String shaderName = parseJsonString(compStr, "shaderName");
                                    if (texPath != null || texName != null) {
                                        entity.setTextureAsset(texPath, texName);
                                    }
                                    if (shaderName != null) {
                                        MaterialComponent mat = entity.getComponent(MaterialComponent.class);
                                        if (mat != null) mat.shaderName = shaderName;
                                    }
                                } else if ("LightComponent".equalsIgnoreCase(typeStr)) {
                                    String lightTypeStr = parseJsonString(compStr, "lightType");
                                    LightComponent.LightType lt = LightComponent.LightType.DIRECTIONAL;
                                    try { lt = LightComponent.LightType.valueOf(lightTypeStr); } catch (Exception ignored) {}
                                    float intensity = parseJsonFloat(compStr, "intensity", 1.0f);
                                    float r = parseJsonFloat(compStr, "r", 1.0f);
                                    float g = parseJsonFloat(compStr, "g", 0.95f);
                                    float b = parseJsonFloat(compStr, "b", 0.80f);

                                    LightComponent lc = new LightComponent();
                                    lc.type = lt;
                                    lc.intensity = intensity;
                                    lc.r = r; lc.g = g; lc.b = b;
                                    entity.addComponent(lc);
                                } else if ("CameraComponent".equalsIgnoreCase(typeStr)) {
                                    float fov = parseJsonFloat(compStr, "fov", 50.0f);
                                    float nearPlane = parseJsonFloat(compStr, "nearPlane", 0.1f);
                                    float farPlane = parseJsonFloat(compStr, "farPlane", 1000.0f);
                                    boolean isPrimary = compStr.contains("\"isPrimary\": true");

                                    CameraComponent cc = new CameraComponent(fov, nearPlane, farPlane);
                                    cc.isPrimary = isPrimary;
                                    entity.addComponent(cc);
                                }
                            }
                        }
                    }
                    entities.add(entity);
                }
            }
        }

        return new SceneLoadData(entities, assets);
    }

    private static String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String parseJsonString(String json, String key) {
        String pattern = "\"" + key + "\":\\s*\"([^\"]*)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) return m.group(1);
        return null;
    }

    private static float parseJsonFloat(String json, String key, float defaultValue) {
        String pattern = "\"" + key + "\":\\s*([-+]?[0-9]*\\.?[0-9]+)";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) {
            try { return Float.parseFloat(m.group(1)); } catch (Exception ignored) {}
        }
        return defaultValue;
    }

    private static List<String> splitJsonObjects(String json) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = -1;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start != -1) {
                    result.add(json.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return result;
    }
}
