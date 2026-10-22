package com.retro.engine.editor.ui;

import com.retro.engine.editor.ui.InspectorProperty.PropertyType;
import com.retro.engine.scene.components.Component;
import com.retro.engine.scene.Entity;
import com.retro.engine.assets.AssetMeta;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * InspectorPanel handles dynamic component rendering using @InspectorProperty reflection.
 * Hardened with clean component header margins and zero-overlap layout geometry.
 */
public class InspectorPanel {

    // Reflection Caches
    private static final Map<Class<?>, Field[]> CACHED_FIELDS = new HashMap<>();
    private static final Map<Field, InspectorProperty> CACHED_ANNOTATIONS = new HashMap<>();
    private static final Map<Field, String> CACHED_LABELS = new HashMap<>();

    // Reusable StringBuilder for string construction without heap churn
    private static final StringBuilder strBuilder = new StringBuilder(128);

    // Scrollbar state
    private static float scrollOffset = 0.0f;
    private static float maxScroll = 0.0f;

    public static float getScrollOffset() {
        return scrollOffset;
    }

    public static void scroll(float delta) {
        scrollOffset = Math.max(0.0f, Math.min(maxScroll, scrollOffset + delta));
    }

    public static void resetScroll() {
        scrollOffset = 0.0f;
    }

    public static void render(UIGeometryBatch.Buffer buffer, Entity selectedEntity) {
        render(buffer, selectedEntity, null);
    }

    public static void render(List<Float> verts, Entity selectedEntity) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        render(tempBuf, selectedEntity);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }

    public static void render(UIGeometryBatch.Buffer buffer, Entity selectedEntity, List<AssetMeta> assetDirectory) {
        // Main Panel Frame & Top Header
        UIGeometryBatch.addQuad(buffer, 0.55f, -0.5f, 1.0f, 1.0f, 0.08f, 0.08f, 0.10f, 1.0f);
        UIGeometryBatch.addQuad(buffer, 0.55f, 0.88f, 1.0f, 1.0f, 0.14f, 0.14f, 0.18f, 1.0f);
        UIGeometryBatch.addFrameBorder(buffer, 0.55f, -0.5f, 1.0f, 1.0f, 0.003f, 0.25f, 0.25f, 0.32f, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, "INSPECTOR [ECS REFLECTION]", 0.58f, 0.95f, 0.98f, 1.6f, 1.0f, 1.0f, 1.0f, 1.0f);

        if (selectedEntity != null) {
            strBuilder.setLength(0);
            strBuilder.append("ENTITY: ").append(selectedEntity.name);
            UIGeometryBatch.addBoundedText(buffer, strBuilder, 0.58f, 0.82f, 0.81f, 1.25f, 1.0f, 0.8f, 0.2f, 1.0f);

            // Save as Prefab button in Inspector Header
            UIGeometryBatch.addQuad(buffer, 0.82f, 0.800f, 0.98f, 0.845f, 0.18f, 0.35f, 0.55f, 1.0f);
            UIGeometryBatch.addFrameBorder(buffer, 0.82f, 0.800f, 0.98f, 0.845f, 0.001f, 0.4f, 0.7f, 1.0f, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, "+ PREFAB", 0.83f, 0.815f, 0.97f, 0.88f, 1.0f, 1.0f, 1.0f, 1.0f);

            float curY = 0.74f;

            for (Component comp : selectedEntity.getAllComponents()) {
                float drawHeaderY = curY + scrollOffset;

                if (drawHeaderY <= 0.77f && drawHeaderY - 0.028f >= -0.48f) {
                    UIGeometryBatch.addQuad(buffer, 0.57f, drawHeaderY - 0.028f, 0.98f, drawHeaderY + 0.010f, 0.14f, 0.16f, 0.22f, 1.0f);
                    UIGeometryBatch.addFrameBorder(buffer, 0.57f, drawHeaderY - 0.028f, 0.98f, drawHeaderY + 0.010f, 0.001f, 0.30f, 0.45f, 0.65f, 1.0f);

                    strBuilder.setLength(0);
                    strBuilder.append("--- ").append(comp.getComponentName().toUpperCase()).append(" ---");
                    UIGeometryBatch.addBoundedText(buffer, strBuilder, 0.58f, drawHeaderY - 0.004f, 0.97f, 1.0f, 0.4f, 0.85f, 1.0f, 1.0f);
                }
                curY -= 0.055f;

                Class<?> compClass = comp.getClass();
                Field[] fields = CACHED_FIELDS.computeIfAbsent(compClass, Class::getDeclaredFields);

                for (Field field : fields) {
                    InspectorProperty prop = CACHED_ANNOTATIONS.computeIfAbsent(field, f -> f.getAnnotation(InspectorProperty.class));
                    if (prop == null) continue;

                    field.setAccessible(true);
                    String label = CACHED_LABELS.computeIfAbsent(field, f -> prop.label().isEmpty() ? f.getName().toUpperCase() : prop.label());

                    float drawY = curY + scrollOffset;
                    boolean isVisible = (drawY <= 0.77f && drawY - 0.045f >= -0.48f);

                    try {
                        Object val = field.get(comp);

                        if (prop.type() == PropertyType.ASSET_PICKER) {
                            if (isVisible) {
                                String pathStr = (val != null) ? val.toString() : "NONE";
                                String nameStr = pathStr.isEmpty() ? "NONE" : extractFileName(pathStr);

                                UIGeometryBatch.addQuad(buffer, 0.58f, drawY - 0.036f, 0.79f, drawY + 0.008f, 0.12f, 0.16f, 0.22f, 1.0f);
                                UIGeometryBatch.addFrameBorder(buffer, 0.58f, drawY - 0.036f, 0.79f, drawY + 0.008f, 0.001f, 0.0f, 0.45f, 0.70f, 1.0f);

                                strBuilder.setLength(0);
                                strBuilder.append(label).append(": ").append(nameStr);
                                UIGeometryBatch.addBoundedText(buffer, strBuilder, 0.59f, drawY - 0.010f, 0.78f, 0.88f, 0.4f, 0.8f, 1.0f, 1.0f);

                                UIGeometryBatch.addQuad(buffer, 0.80f, drawY - 0.036f, 0.88f, drawY + 0.008f, 0.20f, 0.35f, 0.55f, 1.0f);
                                UIGeometryBatch.addFrameBorder(buffer, 0.80f, drawY - 0.036f, 0.88f, drawY + 0.008f, 0.001f, 0.4f, 0.7f, 1.0f, 1.0f);
                                UIGeometryBatch.addBoundedText(buffer, "PICK", 0.81f, drawY - 0.010f, 0.87f, 0.88f, 1.0f, 1.0f, 1.0f, 1.0f);

                                UIGeometryBatch.addQuad(buffer, 0.89f, drawY - 0.036f, 0.98f, drawY + 0.008f, 0.15f, 0.45f, 0.30f, 1.0f);
                                UIGeometryBatch.addFrameBorder(buffer, 0.89f, drawY - 0.036f, 0.98f, drawY + 0.008f, 0.001f, 0.3f, 0.9f, 0.5f, 1.0f);
                                UIGeometryBatch.addBoundedText(buffer, "IMPORT", 0.895f, drawY - 0.010f, 0.975f, 0.85f, 1.0f, 1.0f, 1.0f, 1.0f);
                            }
                            curY -= 0.054f;

                        } else if (prop.type() == PropertyType.TOGGLE || field.getType() == boolean.class) {
                            if (isVisible) {
                                boolean boolVal = (Boolean) val;
                                strBuilder.setLength(0);
                                strBuilder.append(label).append(": ").append(boolVal ? "[ON]" : "[OFF]");

                                UIGeometryBatch.addQuad(buffer, 0.58f, drawY - 0.030f, 0.85f, drawY + 0.008f, 0.20f, 0.25f, 0.35f, 1.0f);
                                UIGeometryBatch.addFrameBorder(buffer, 0.58f, drawY - 0.030f, 0.85f, drawY + 0.008f, 0.001f, 0.4f, 0.5f, 0.8f, 1.0f);
                                UIGeometryBatch.addBoundedText(buffer, strBuilder, 0.59f, drawY - 0.008f, 0.84f, 0.85f, 1.0f, 1.0f, 1.0f, 1.0f);
                            }
                            curY -= 0.048f;

                        } else if (prop.type() == PropertyType.BUTTON_GROUP || field.getType().isEnum()) {
                            if (isVisible) {
                                String enumStr = (val != null) ? val.toString() : "NONE";
                                strBuilder.setLength(0);
                                strBuilder.append(label).append(": ").append(enumStr);
                                UIGeometryBatch.addBoundedText(buffer, strBuilder, 0.58f, drawY, 0.82f, 0.95f, 1.0f, 0.9f, 0.3f, 1.0f);

                                UIGeometryBatch.addQuad(buffer, 0.84f, drawY - 0.030f, 0.98f, drawY + 0.008f, 0.25f, 0.25f, 0.35f, 1.0f);
                                UIGeometryBatch.addFrameBorder(buffer, 0.84f, drawY - 0.030f, 0.98f, drawY + 0.008f, 0.001f, 0.5f, 0.5f, 0.7f, 1.0f);
                                UIGeometryBatch.addBoundedText(buffer, "[CYCLE]", 0.85f, drawY - 0.008f, 0.97f, 0.85f, 1.0f, 1.0f, 1.0f, 1.0f);
                            }
                            curY -= 0.048f;

                        } else if (prop.type() == PropertyType.COLOR) {
                            if (isVisible) {
                                float floatVal = (Float) val;
                                strBuilder.setLength(0);
                                strBuilder.append(label).append(": ");
                                appendFloat(strBuilder, floatVal, 2);
                                UIGeometryBatch.addBoundedText(buffer, strBuilder, 0.58f, drawY, 0.82f, 0.95f, 0.9f, 0.9f, 0.9f, 1.0f);

                                UIGeometryBatch.addQuad(buffer, 0.84f, drawY - 0.030f, 0.90f, drawY + 0.008f, 0.20f, 0.20f, 0.25f, 1.0f);
                                UIGeometryBatch.addBoundedText(buffer, "[-]", 0.85f, drawY - 0.008f, 0.89f, 0.85f, 1.0f, 0.4f, 0.4f, 1.0f);
                                UIGeometryBatch.addQuad(buffer, 0.91f, drawY - 0.030f, 0.97f, drawY + 0.008f, 0.20f, 0.20f, 0.25f, 1.0f);
                                UIGeometryBatch.addBoundedText(buffer, "[+]", 0.92f, drawY - 0.008f, 0.96f, 0.85f, 0.4f, 1.0f, 0.4f, 1.0f);
                            }
                            curY -= 0.048f;

                        } else if (field.getType() == float.class || field.getType() == Float.class) {
                            if (isVisible) {
                                float floatVal = (Float) val;
                                strBuilder.setLength(0);
                                strBuilder.append(label).append(": ");
                                appendFloat(strBuilder, floatVal, 1);
                                UIGeometryBatch.addBoundedText(buffer, strBuilder, 0.58f, drawY, 0.82f, 0.95f, 0.9f, 0.9f, 0.9f, 1.0f);

                                UIGeometryBatch.addQuad(buffer, 0.84f, drawY - 0.030f, 0.90f, drawY + 0.008f, 0.20f, 0.20f, 0.25f, 1.0f);
                                UIGeometryBatch.addBoundedText(buffer, "[-]", 0.85f, drawY - 0.008f, 0.89f, 0.85f, 1.0f, 0.4f, 0.4f, 1.0f);
                                UIGeometryBatch.addQuad(buffer, 0.91f, drawY - 0.030f, 0.97f, drawY + 0.008f, 0.20f, 0.20f, 0.25f, 1.0f);
                                UIGeometryBatch.addBoundedText(buffer, "[+]", 0.92f, drawY - 0.008f, 0.96f, 0.85f, 0.4f, 1.0f, 0.4f, 1.0f);
                            }
                            curY -= 0.048f;

                        } else {
                            if (isVisible) {
                                String textVal = (val != null) ? val.toString() : "";
                                strBuilder.setLength(0);
                                strBuilder.append(label).append(": ").append(textVal);
                                UIGeometryBatch.addBoundedText(buffer, strBuilder, 0.58f, drawY, 0.98f, 0.90f, 0.8f, 0.8f, 0.8f, 1.0f);
                            }
                            curY -= 0.044f;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                curY -= 0.020f;
            }

            // Button: + ADD COMPONENT
            float drawAddBtnY = curY + scrollOffset;
            if (drawAddBtnY <= 0.77f && drawAddBtnY - 0.04f >= -0.48f) {
                UIGeometryBatch.addQuad(buffer, 0.58f, drawAddBtnY - 0.04f, 0.98f, drawAddBtnY + 0.01f, 0.15f, 0.35f, 0.55f, 1.0f);
                UIGeometryBatch.addFrameBorder(buffer, 0.58f, drawAddBtnY - 0.04f, 0.98f, drawAddBtnY + 0.01f, 0.0015f, 0.3f, 0.7f, 1.0f, 1.0f);
                UIGeometryBatch.addBoundedText(buffer, "+ ADD COMPONENT", 0.67f, drawAddBtnY - 0.008f, 0.97f, 1.05f, 1.0f, 1.0f, 1.0f, 1.0f);
            }
            curY -= 0.06f;

            // Compute total content height & clamp scrollOffset
            float totalContentHeight = 0.74f - curY;
            maxScroll = Math.max(0.0f, totalContentHeight - 1.22f);
            scrollOffset = Math.max(0.0f, Math.min(maxScroll, scrollOffset));

            // Render Visual Scrollbar if content exceeds viewport
            if (maxScroll > 0.001f) {
                UIGeometryBatch.addQuad(buffer, 0.982f, -0.48f, 0.995f, 0.77f, 0.12f, 0.13f, 0.16f, 0.85f);
                UIGeometryBatch.addFrameBorder(buffer, 0.982f, -0.48f, 0.995f, 0.77f, 0.001f, 0.25f, 0.30f, 0.40f, 1.0f);

                float thumbHeight = Math.max(0.12f, (1.22f / totalContentHeight) * 1.25f);
                float thumbTopY = 0.77f - (scrollOffset / maxScroll) * (1.25f - thumbHeight);

                UIGeometryBatch.addQuad(buffer, 0.982f, thumbTopY - thumbHeight, 0.995f, thumbTopY, 0.25f, 0.55f, 0.85f, 1.0f);
                UIGeometryBatch.addFrameBorder(buffer, 0.982f, thumbTopY - thumbHeight, 0.995f, thumbTopY, 0.001f, 0.5f, 0.8f, 1.0f, 1.0f);
            }

        } else {
            resetScroll();
            UIGeometryBatch.addBoundedText(buffer, "NO ENTITY SELECTED", 0.58f, 0.80f, 0.98f, 1.2f, 0.5f, 0.5f, 0.5f, 1.0f);
            UIGeometryBatch.addBoundedText(buffer, "CLICK AN ENTITY IN SCENE HIERARCHY", 0.58f, 0.72f, 0.98f, 0.95f, 0.4f, 0.4f, 0.4f, 1.0f);
        }
    }

    public static void render(List<Float> verts, Entity selectedEntity, List<AssetMeta> assetDirectory) {
        UIGeometryBatch.Buffer tempBuf = new UIGeometryBatch.Buffer();
        render(tempBuf, selectedEntity, assetDirectory);
        for (int idx = 0; idx < tempBuf.getSize(); idx++) verts.add(tempBuf.getArray()[idx]);
    }

    private static String extractFileName(String path) {
        if (path == null) return "NONE";
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return (lastSlash >= 0 && lastSlash < path.length() - 1) ? path.substring(lastSlash + 1) : path;
    }

    private static void appendFloat(StringBuilder sb, float val, int decimals) {
        if (Float.isNaN(val) || Float.isInfinite(val)) {
            sb.append(val);
            return;
        }
        if (val < 0) {
            sb.append('-');
            val = -val;
        }
        long intPart = (long) val;
        sb.append(intPart);
        sb.append('.');
        float frac = val - intPart;
        if (decimals == 1) {
            long d = Math.round(frac * 10.0f);
            if (d >= 10) { intPart++; d = 0; }
            sb.append(d);
        } else {
            long d = Math.round(frac * 100.0f);
            if (d >= 100) { intPart++; d = 0; }
            if (d < 10) sb.append('0');
            sb.append(d);
        }
    }
}
