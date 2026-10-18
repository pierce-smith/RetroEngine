package com.retro.engine.editor.ui;

import com.retro.engine.editor.ui.InspectorProperty.PropertyType;
import com.retro.engine.scene.components.Component;
import com.retro.engine.scene.Entity;
import com.retro.engine.assets.AssetMeta;

import java.lang.reflect.Field;
import java.util.List;

/**
 * InspectorPanel handles dynamic component rendering using @InspectorProperty reflection.
 */
public class InspectorPanel {

    public static void render(UIGeometryBatch.Buffer buffer, Entity selectedEntity) {
        render(buffer, selectedEntity, null);
    }

    public static void render(List<Float> verts, Entity selectedEntity) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        render(tempBuf, selectedEntity);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }

    public static void render(UIGeometryBatch.Buffer buffer, Entity selectedEntity, List<AssetMeta> assetDirectory) {
        UIGeometryBatch.addQuad(buffer, 0.55f, -0.5f, 1.0f, 1.0f, 0.08f, 0.08f, 0.10f, 1.0f);
        UIGeometryBatch.addQuad(buffer, 0.55f, 0.88f, 1.0f, 1.0f, 0.14f, 0.14f, 0.18f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, 0.55f, -0.5f, 1.0f, 1.0f, 0.003f, 0.25f, 0.25f, 0.32f, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "INSPECTOR [ECS REFLECTION]", 0.58f, 0.95f, 0.98f, 1.6f, 1.0f, 1.0f, 1.0f, 1.0f);

        if (selectedEntity != null) {
            UIGeometryBatch.addBoundedText(buffer, "ENTITY: " + selectedEntity.name, 0.58f, 0.80f, 0.98f, 1.3f, 1.0f, 0.8f, 0.2f, 1.0f);

            float curY = 0.72f;

            for (Component comp : selectedEntity.getAllComponents()) {
                if (curY < -0.45f) break;

                UIGeometryBatch.addBoundedText(buffer, "--- " + comp.getComponentName().toUpperCase() + " ---", 0.58f, curY, 0.98f, 1.1f, 0.5f, 0.5f, 0.6f, 1.0f);
                curY -= 0.06f;

                Field[] fields = comp.getClass().getDeclaredFields();
                for (Field field : fields) {
                    if (curY < -0.45f) break;
                    if (!field.isAnnotationPresent(InspectorProperty.class)) continue;

                    InspectorProperty prop = field.getAnnotation(InspectorProperty.class);
                    field.setAccessible(true);
                    String label = prop.label().isEmpty() ? field.getName().toUpperCase() : prop.label();

                    try {
                        Object val = field.get(comp);

                        if (prop.type() == PropertyType.ASSET_PICKER) {
                            String pathStr = (val != null) ? val.toString() : "NONE";
                            String nameStr = pathStr.isEmpty() ? "NONE" : new java.io.File(pathStr).getName();

                            UIGeometryBatch.addQuad(buffer, 0.58f, curY - 0.04f, 0.79f, curY + 0.02f, 0.12f, 0.16f, 0.22f, 1.0f);
                            UIGeometryBatch.addFrameBorder(buffer, 0.58f, curY - 0.04f, 0.79f, curY + 0.02f, 0.002f, 0.0f, 0.45f, 0.70f, 1.0f);
                            UIGeometryBatch.addBoundedText(buffer, label + ": " + nameStr, 0.59f, curY - 0.01f, 0.78f, 0.95f, 0.4f, 0.8f, 1.0f, 1.0f);

                            UIGeometryBatch.addQuad(buffer, 0.80f, curY - 0.04f, 0.88f, curY + 0.02f, 0.20f, 0.35f, 0.55f, 1.0f);
                            UIGeometryBatch.addFrameBorder(buffer, 0.80f, curY - 0.04f, 0.88f, curY + 0.02f, 0.002f, 0.4f, 0.7f, 1.0f, 1.0f);
                            UIGeometryBatch.addBoundedText(buffer, "PICK", 0.81f, curY - 0.01f, 0.87f, 0.95f, 1.0f, 1.0f, 1.0f, 1.0f);

                            UIGeometryBatch.addQuad(buffer, 0.89f, curY - 0.04f, 0.98f, curY + 0.02f, 0.15f, 0.45f, 0.30f, 1.0f);
                            UIGeometryBatch.addFrameBorder(buffer, 0.89f, curY - 0.04f, 0.98f, curY + 0.02f, 0.002f, 0.3f, 0.9f, 0.5f, 1.0f);
                            UIGeometryBatch.addBoundedText(buffer, "IMPORT", 0.895f, curY - 0.01f, 0.975f, 0.90f, 1.0f, 1.0f, 1.0f, 1.0f);

                            curY -= 0.08f;

                        } else if (prop.type() == PropertyType.TOGGLE || field.getType() == boolean.class) {
                            boolean boolVal = (Boolean) val;
                            String toggleText = label + ": " + (boolVal ? "[ON]" : "[OFF]");
                            UIGeometryBatch.addQuad(buffer, 0.58f, curY - 0.03f, 0.85f, curY + 0.02f, 0.20f, 0.25f, 0.35f, 1.0f);
                            UIGeometryBatch.addFrameBorder(buffer, 0.58f, curY - 0.03f, 0.85f, curY + 0.02f, 0.002f, 0.4f, 0.5f, 0.8f, 1.0f);
                            UIGeometryBatch.addBoundedText(buffer, toggleText, 0.59f, curY - 0.01f, 0.84f, 0.90f, 1.0f, 1.0f, 1.0f, 1.0f);
                            curY -= 0.06f;

                        } else if (prop.type() == PropertyType.BUTTON_GROUP || field.getType().isEnum()) {
                            String enumStr = (val != null) ? val.toString() : "NONE";
                            UIGeometryBatch.addBoundedText(buffer, label + ": " + enumStr, 0.58f, curY, 0.82f, 1.0f, 1.0f, 0.9f, 0.3f, 1.0f);
                            UIGeometryBatch.addQuad(buffer, 0.84f, curY - 0.03f, 0.98f, curY + 0.02f, 0.25f, 0.25f, 0.35f, 1.0f);
                            UIGeometryBatch.addFrameBorder(buffer, 0.84f, curY - 0.03f, 0.98f, curY + 0.02f, 0.002f, 0.5f, 0.5f, 0.7f, 1.0f);
                            UIGeometryBatch.addBoundedText(buffer, "[CYCLE]", 0.85f, curY - 0.01f, 0.97f, 0.90f, 1.0f, 1.0f, 1.0f, 1.0f);
                            curY -= 0.06f;

                        } else if (prop.type() == PropertyType.COLOR) {
                            float floatVal = (Float) val;
                            UIGeometryBatch.addBoundedText(buffer, String.format("%s: %.2f", label, floatVal), 0.58f, curY, 0.82f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);
                            UIGeometryBatch.addQuad(buffer, 0.84f, curY - 0.03f, 0.90f, curY + 0.02f, 0.20f, 0.20f, 0.25f, 1.0f);
                            UIGeometryBatch.addBoundedText(buffer, "[-]", 0.85f, curY - 0.01f, 0.89f, 0.90f, 1.0f, 0.4f, 0.4f, 1.0f);
                            UIGeometryBatch.addQuad(buffer, 0.91f, curY - 0.03f, 0.97f, curY + 0.02f, 0.20f, 0.20f, 0.25f, 1.0f);
                            UIGeometryBatch.addBoundedText(buffer, "[+]", 0.92f, curY - 0.01f, 0.96f, 0.90f, 0.4f, 1.0f, 0.4f, 1.0f);
                            curY -= 0.06f;

                        } else if (field.getType() == float.class || field.getType() == Float.class) {
                            float floatVal = (Float) val;
                            UIGeometryBatch.addBoundedText(buffer, String.format("%s: %.1f", label, floatVal), 0.58f, curY, 0.82f, 1.0f, 0.9f, 0.9f, 0.9f, 1.0f);
                            UIGeometryBatch.addQuad(buffer, 0.84f, curY - 0.03f, 0.90f, curY + 0.02f, 0.20f, 0.20f, 0.25f, 1.0f);
                            UIGeometryBatch.addBoundedText(buffer, "[-]", 0.85f, curY - 0.01f, 0.89f, 0.90f, 1.0f, 0.4f, 0.4f, 1.0f);
                            UIGeometryBatch.addQuad(buffer, 0.91f, curY - 0.03f, 0.97f, curY + 0.02f, 0.20f, 0.20f, 0.25f, 1.0f);
                            UIGeometryBatch.addBoundedText(buffer, "[+]", 0.92f, curY - 0.01f, 0.96f, 0.90f, 0.4f, 1.0f, 0.4f, 1.0f);
                            curY -= 0.06f;

                        } else {
                            String textVal = (val != null) ? val.toString() : "";
                            UIGeometryBatch.addBoundedText(buffer, label + ": " + textVal, 0.58f, curY, 0.98f, 1.0f, 0.8f, 0.8f, 0.8f, 1.0f);
                            curY -= 0.05f;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                curY -= 0.02f;
            }
        } else {
            UIGeometryBatch.addBoundedText(buffer, "NO ENTITY SELECTED", 0.58f, 0.80f, 0.98f, 1.2f, 0.5f, 0.5f, 0.5f, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, "CLICK AN ENTITY IN SCENE HIERARCHY", 0.58f, 0.72f, 0.98f, 0.95f, 0.4f, 0.4f, 0.4f, 1.0f);
        }
    }

    public static void render(List<Float> verts, Entity selectedEntity, List<AssetMeta> assetDirectory) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        render(tempBuf, selectedEntity, assetDirectory);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }
}
