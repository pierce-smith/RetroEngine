package com.retro.engine.editor.ui.panels;

import com.retro.engine.scene.components.ComponentSchema;
import com.retro.engine.scene.components.ComponentSchema.Control;
import com.retro.engine.editor.ui.panels.InspectorProperty.PropertyType;
import com.retro.engine.editor.ui.UIContext;
import com.retro.engine.editor.ui.batch.UIGeometryBatch;
import com.retro.engine.editor.ui.theme.EditorTheme;
import com.retro.engine.scene.components.Component;
import com.retro.engine.scene.components.gameplay.ScriptComponent;
import com.retro.engine.scene.components.transform.Transform;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scripting.EntityScript;
import com.retro.engine.scripting.ScriptEngine;
import com.retro.engine.assets.AssetMeta;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * InspectorPanel handles dynamic component rendering using @InspectorProperty reflection
 * and Pillar 3 User Script Property Overrides reflection.
 */
public class InspectorPanel {

    // --- Shared layout (design-space NDC) ---
    public static final float VIEW_TOP = 0.77f, VIEW_BOTTOM = -0.48f;
    public static final float CONTENT_TOP = 0.74f;

    public static final float HEADER_ADVANCE = 0.046f;
    public static final float ROW_ADVANCE = 0.040f;
    public static final float ASSET_ROW_ADVANCE = 0.044f;
    public static final float TEXT_ROW_ADVANCE = 0.036f;
    public static final float COMPONENT_GAP = 0.012f;
    public static final float ADD_BUTTON_ADVANCE = 0.06f;

    /** Control band of a row spans [drawY - CTRL_BELOW, drawY + CTRL_ABOVE]. */
    public static final float CTRL_ABOVE = 0.004f, CTRL_BELOW = 0.030f;

    // Two-column grid: label column | value column
    public static final float LABEL_X = 0.575f;
    public static final float VALUE_X0 = 0.735f, VALUE_X1 = 0.975f;

    // Float rows: value well + segmented [-|+] stepper
    public static final float FIELD_X1 = 0.858f;
    public static final float DEC_X0 = 0.865f, DEC_X1 = 0.92f;
    public static final float INC_X0 = 0.92f, INC_X1 = 0.975f;

    // Asset rows: value well + PICK + IMPORT
    public static final float ASSET_FIELD_X1 = 0.843f;
    public static final float PICK_X0 = 0.848f, PICK_X1 = 0.905f;
    public static final float IMPORT_X0 = 0.91f, IMPORT_X1 = 0.975f;

    // Header "+ PREFAB" button and footer "+ ADD COMPONENT" button
    public static final float PREFAB_X0 = 0.82f, PREFAB_X1 = 0.98f, PREFAB_Y0 = 0.800f, PREFAB_Y1 = 0.845f;
    public static final float ADD_X0 = 0.58f, ADD_X1 = 0.975f, ADD_ABOVE = 0.01f, ADD_BELOW = 0.04f;

    // Section header "REMOVE" button (every component except Transform)
    public static final float REMOVE_X0 = 0.885f, REMOVE_X1 = 0.969f, REMOVE_INSET = 0.004f;

    private static final float LABEL_TEXT = 0.82f;
    private static final float VALUE_TEXT = 0.85f;

    // Reflection Caches
    private static final Map<Field, Integer> CACHED_AXES = new HashMap<>();
    private static final Map<Field, String> CACHED_DISPLAY_LABELS = new HashMap<>();

    // Reusable StringBuilder for string construction without heap churn
    private static final StringBuilder strBuilder = new StringBuilder(128);

    // Scrollbar state
    private static float scrollOffset = 0.0f;
    private static float maxScroll = 0.0f;

    public static float getScrollOffset() { return scrollOffset; }
    public static float getMaxScroll() { return maxScroll; }
    public static void setScrollOffset(float offset) { scrollOffset = Math.max(0.0f, Math.min(maxScroll, offset)); }
    public static void scroll(float delta) { scrollOffset = Math.max(0.0f, Math.min(maxScroll, scrollOffset + delta)); }
    public static void resetScroll() { scrollOffset = 0.0f; }

    // Inline text field focus: the property (keyed by field name within its owning component) being typed into.
    // Driven by InspectorInputDelegate; the focused value well's rect is recorded each frame for focus hit-testing.
    private static Component activeComponent = null;
    private static String activePropertyKey = null;
    private static final StringBuilder activeInputBuffer = new StringBuilder(64);
    private static boolean activeSelectAll = false;
    private static boolean activeWellVisible = false;
    private static float activeWellX0, activeWellY0, activeWellX1, activeWellY1;

    public static boolean isEditing() { return activePropertyKey != null; }
    public static Component getActiveComponent() { return activeComponent; }
    public static String getActivePropertyKey() { return activePropertyKey; }
    public static CharSequence getActiveInputBuffer() { return activeInputBuffer; }

    public static boolean isActiveField(Component comp, String key) {
        return activePropertyKey != null && comp == activeComponent && activePropertyKey.equals(key);
    }

    /** Focuses a value well; the initial text starts fully selected, so the first keystroke replaces it. */
    public static void beginInlineEdit(Component comp, String key, String initialText) {
        activeComponent = comp;
        activePropertyKey = key;
        activeInputBuffer.setLength(0);
        if (initialText != null) activeInputBuffer.append(initialText);
        activeSelectAll = activeInputBuffer.length() > 0;
        activeWellVisible = false;
    }

    public static void endInlineEdit() {
        activeComponent = null;
        activePropertyKey = null;
        activeInputBuffer.setLength(0);
        activeSelectAll = false;
        activeWellVisible = false;
    }

    public static void typeInline(int codepoint) {
        if (activeSelectAll) activeInputBuffer.setLength(0);
        activeSelectAll = false;
        activeInputBuffer.appendCodePoint(codepoint);
    }

    public static void backspaceInline() {
        if (activeSelectAll) {
            activeInputBuffer.setLength(0);
        } else if (activeInputBuffer.length() > 0) {
            activeInputBuffer.setLength(activeInputBuffer.offsetByCodePoints(activeInputBuffer.length(), -1));
        }
        activeSelectAll = false;
    }

    /** True when (ndcX, ndcY) lies on the focused value well as drawn last frame. */
    public static boolean isOverActiveField(float ndcX, float ndcY) {
        return isEditing() && activeWellVisible
                && ndcX >= activeWellX0 && ndcX <= activeWellX1 && ndcY >= activeWellY0 && ndcY <= activeWellY1;
    }

    public static boolean isRowVisible(float drawY) {
        return drawY + CTRL_ABOVE <= VIEW_TOP && drawY - CTRL_BELOW >= VIEW_BOTTOM;
    }

    public static int axisOf(String label) {
        int n = (label != null) ? label.length() : 0;
        if (n < 3 || label.charAt(n - 2) != ' ') return -1;
        return switch (label.charAt(n - 1)) {
            case 'X', 'x' -> 0;
            case 'Y', 'y' -> 1;
            case 'Z', 'z' -> 2;
            default -> -1;
        };
    }

    private static String vectorGroupName(String label) {
        String prefix = label.substring(0, label.length() - 2).trim();
        return switch (prefix.toUpperCase()) {
            case "POS" -> "POSITION";
            case "ROT" -> "ROTATION";
            case "SCL" -> "SCALE";
            default -> prefix;
        };
    }

    public static List<Field> getUserScriptFields(Class<?> scriptClass) {
        List<Field> fields = new ArrayList<>();
        if (scriptClass == null || !EntityScript.class.isAssignableFrom(scriptClass)) return fields;
        Class<?> curr = scriptClass;
        while (curr != null && curr != EntityScript.class && curr != Object.class) {
            for (Field f : curr.getDeclaredFields()) {
                int mods = f.getModifiers();
                if (Modifier.isStatic(mods) || Modifier.isTransient(mods)) continue;
                if (Modifier.isPublic(mods) || f.isAnnotationPresent(InspectorProperty.class)) {
                    fields.add(f);
                }
            }
            curr = curr.getSuperclass();
        }
        return fields;
    }

    public static void render(UIContext ui, Entity selectedEntity, List<AssetMeta> assetDirectory) {
        UIGeometryBatch.Buffer buffer = ui.geometry();
        UIGeometryBatch.addQuad(buffer, 0.55f, -0.5f, 1.0f, 1.0f, EditorTheme.SURFACE, 1.0f);
        activeWellVisible = false;

        if (selectedEntity == null) {
            resetScroll();
            centeredText(buffer, "NO SELECTION", 0.24f, 1.15f, EditorTheme.TEXT_MUTED);
            centeredText(buffer, "SELECT AN ENTITY IN THE HIERARCHY", 0.17f, 0.8f, EditorTheme.TEXT_DISABLED);
            return;
        }

        // Entity header
        UIGeometryBatch.addTruncatedText(buffer, selectedEntity.name, LABEL_X, 0.833f, PREFAB_X0 - 0.012f, 1.1f, EditorTheme.TEXT, 1.0f);
        UIGeometryBatch.addRoundedCard(buffer, PREFAB_X0, PREFAB_Y0, PREFAB_X1, PREFAB_Y1, EditorTheme.RADIUS, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
        centeredTextIn(buffer, "+ PREFAB", PREFAB_X0, PREFAB_X1, (PREFAB_Y0 + PREFAB_Y1) * 0.5f, 0.8f, EditorTheme.TEXT);
        UIGeometryBatch.addQuad(buffer, LABEL_X, 0.785f, VALUE_X1, 0.785f + EditorTheme.HAIRLINE, EditorTheme.BORDER, 1.0f);

        float curY = CONTENT_TOP;

        for (Component comp : selectedEntity.getAllComponents()) {
            float drawHeaderY = curY + scrollOffset;

            if (isRowVisible(drawHeaderY)) {
                renderSectionHeader(buffer, comp.getComponentName(), drawHeaderY, isRemovable(comp));
            }
            curY -= HEADER_ADVANCE;

            for (ComponentSchema.Property p : ComponentSchema.of(comp.getClass()).inspectorProperties()) {
                Field field = p.field;
                InspectorProperty prop = p.meta;
                String label = p.label;

                float drawY = curY + scrollOffset;
                boolean isVisible = isRowVisible(drawY);
                float midY = drawY - (CTRL_BELOW - CTRL_ABOVE) * 0.5f;

                try {
                    Object val = field.get(comp);

                    if (p.control == Control.ASSET) {
                        if (isVisible) {
                            String pathStr = (val != null) ? val.toString() : "";
                            boolean empty = pathStr.isEmpty();
                            rowLabel(buffer, label, midY);
                            well(buffer, VALUE_X0, ASSET_FIELD_X1, drawY);
                            UIGeometryBatch.addTruncatedText(buffer, empty ? "NONE" : extractFileName(pathStr), VALUE_X0 + 0.008f,
                                    textTop(midY, VALUE_TEXT), ASSET_FIELD_X1 - 0.006f, VALUE_TEXT, empty ? EditorTheme.TEXT_MUTED : EditorTheme.TEXT, 1.0f);
                            button(buffer, "PICK", PICK_X0, PICK_X1, drawY, EditorTheme.SURFACE_RAISED);
                            button(buffer, "IMPORT", IMPORT_X0, IMPORT_X1, drawY, EditorTheme.ACCENT);
                        }
                    } else if (p.control == Control.TOGGLE) {
                        if (isVisible) {
                            boolean boolVal = (val != null) && (Boolean) val;
                            rowLabel(buffer, label, midY);
                            renderSwitch(buffer, boolVal, midY);
                        }
                    } else if (p.control == Control.CHOICE) {
                        if (isVisible) {
                            String enumStr = (val != null) ? val.toString() : "NONE";
                            rowLabel(buffer, label, midY);
                            renderPopupButton(buffer, enumStr, drawY, midY);
                        }
                    } else if (p.control == Control.NUMBER) {
                        if (isVisible) {
                            float floatVal = (val != null) ? (Float) val : 0.0f;
                            boolean isColor = p.isColor();
                            int axis = isColor ? -1 : CACHED_AXES.computeIfAbsent(field, f -> axisOf(label));
                            String display = CACHED_DISPLAY_LABELS.computeIfAbsent(field, f -> {
                                int ax = axisOf(label);
                                if (isColor || ax < 0) return label;
                                return ax == 0 ? vectorGroupName(label) : "";
                            });

                            boolean editing = isActiveField(comp, field.getName());
                            rowLabel(buffer, display, midY);
                            if (editing) activeWell(buffer, VALUE_X0, FIELD_X1, drawY);
                            else well(buffer, VALUE_X0, FIELD_X1, drawY);

                            float textX = VALUE_X0 + 0.008f;
                            if (isColor && !editing) {
                                float range = prop.max() - prop.min();
                                float t = range > 0.0f ? Math.max(0.0f, Math.min(1.0f, (floatVal - prop.min()) / range)) : 0.0f;
                                float inset = 0.004f;
                                float fillX1 = VALUE_X0 + inset + (FIELD_X1 - VALUE_X0 - inset * 2.0f) * t;
                                if (fillX1 > VALUE_X0 + inset) {
                                    UIGeometryBatch.addRoundedQuad(buffer, VALUE_X0 + inset, drawY - CTRL_BELOW + inset, fillX1, drawY + CTRL_ABOVE - inset,
                                            EditorTheme.RADIUS_SMALL, channelColor(label), 0.45f);
                                }
                            } else if (axis >= 0) {
                                textX = axisBadge(buffer, axis, VALUE_X0 + 0.005f, drawY) + 0.008f;
                            }

                            if (editing) {
                                renderInputText(buffer, textX, FIELD_X1 - 0.006f, midY);
                            } else {
                                strBuilder.setLength(0);
                                appendFloat(strBuilder, floatVal, isColor ? 2 : 1);
                                UIGeometryBatch.addTruncatedText(buffer, strBuilder, textX, textTop(midY, VALUE_TEXT), FIELD_X1 - 0.006f, VALUE_TEXT, EditorTheme.TEXT, 1.0f);
                            }

                            renderStepper(buffer, drawY, midY);
                        }
                    } else if (p.control == Control.STRING) {
                        if (isVisible) {
                            rowLabel(buffer, label, midY);
                            if (isActiveField(comp, field.getName())) {
                                activeWell(buffer, VALUE_X0, VALUE_X1, drawY);
                                renderInputText(buffer, VALUE_X0 + 0.008f, VALUE_X1 - 0.006f, midY);
                            } else {
                                String strVal = (val != null) ? (String) val : "";
                                well(buffer, VALUE_X0, VALUE_X1, drawY);
                                UIGeometryBatch.addTruncatedText(buffer, strVal, VALUE_X0 + 0.008f, textTop(midY, VALUE_TEXT),
                                        VALUE_X1 - 0.006f, VALUE_TEXT, EditorTheme.TEXT, 1.0f);
                            }
                        }
                    } else {
                        if (isVisible) {
                            String textVal = (val != null) ? val.toString() : "";
                            rowLabel(buffer, label, midY);
                            UIGeometryBatch.addTruncatedText(buffer, textVal.isEmpty() ? "-" : textVal, VALUE_X0 + 0.008f, textTop(midY, VALUE_TEXT),
                                    VALUE_X1, VALUE_TEXT, EditorTheme.TEXT_MUTED, 1.0f);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                curY -= rowAdvance(p.control);
            }

            // Pillar 3: Reflected User Script Custom Field Overrides
            if (comp instanceof ScriptComponent sc) {
                Class<?> scriptClass = ScriptEngine.resolveScriptClass(sc);
                if (scriptClass != null) {
                    List<Field> userFields = getUserScriptFields(scriptClass);
                    for (Field f : userFields) {
                        f.setAccessible(true);
                        String fName = f.getName();
                        InspectorProperty prop = f.getAnnotation(InspectorProperty.class);
                        String label = (prop != null && !prop.label().isEmpty()) ? prop.label() : fName.toUpperCase();

                        float drawY = curY + scrollOffset;
                        boolean isVisible = isRowVisible(drawY);
                        float midY = drawY - (CTRL_BELOW - CTRL_ABOVE) * 0.5f;

                        String valStr = null;
                        if (sc.propertyOverrides != null && sc.propertyOverrides.containsKey(fName)) {
                            valStr = sc.propertyOverrides.get(fName);
                        } else if (sc.activeScriptInstance != null) {
                            try {
                                Object o = f.get(sc.activeScriptInstance);
                                valStr = (o != null) ? o.toString() : "";
                            } catch (Exception ignored) {}
                        }

                        if (valStr == null) {
                            try {
                                Object dummy = f.get(scriptClass.getDeclaredConstructor().newInstance());
                                valStr = (dummy != null) ? dummy.toString() : "0";
                            } catch (Exception e) {
                                valStr = "0";
                            }
                        }

                        Class<?> fieldType = f.getType();

                        if (fieldType == boolean.class || fieldType == Boolean.class) {
                            if (isVisible) {
                                boolean boolVal = Boolean.parseBoolean(valStr);
                                rowLabel(buffer, label, midY);
                                renderSwitch(buffer, boolVal, midY);
                            }
                            curY -= ROW_ADVANCE;
                        } else if (fieldType == float.class || fieldType == Float.class || fieldType == double.class || fieldType == Double.class || fieldType == int.class || fieldType == Integer.class) {
                            if (isVisible) {
                                rowLabel(buffer, label, midY);
                                if (isActiveField(sc, fName)) {
                                    activeWell(buffer, VALUE_X0, FIELD_X1, drawY);
                                    renderInputText(buffer, VALUE_X0 + 0.008f, FIELD_X1 - 0.006f, midY);
                                } else {
                                    float floatVal = 0.0f;
                                    try { floatVal = Float.parseFloat(valStr); } catch (Exception ignored) {}
                                    well(buffer, VALUE_X0, FIELD_X1, drawY);
                                    strBuilder.setLength(0);
                                    appendFloat(strBuilder, floatVal, 1);
                                    UIGeometryBatch.addTruncatedText(buffer, strBuilder, VALUE_X0 + 0.008f, textTop(midY, VALUE_TEXT), FIELD_X1 - 0.006f, VALUE_TEXT, EditorTheme.TEXT, 1.0f);
                                }
                                renderStepper(buffer, drawY, midY);
                            }
                            curY -= ROW_ADVANCE;
                        } else if (fieldType.isEnum()) {
                            if (isVisible) {
                                rowLabel(buffer, label, midY);
                                renderPopupButton(buffer, valStr, drawY, midY);
                            }
                            curY -= ROW_ADVANCE;
                        } else if (fieldType == String.class) {
                            if (isVisible) {
                                rowLabel(buffer, label, midY);
                                if (isActiveField(sc, fName)) {
                                    activeWell(buffer, VALUE_X0, VALUE_X1, drawY);
                                    renderInputText(buffer, VALUE_X0 + 0.008f, VALUE_X1 - 0.006f, midY);
                                } else {
                                    well(buffer, VALUE_X0, VALUE_X1, drawY);
                                    UIGeometryBatch.addTruncatedText(buffer, valStr, VALUE_X0 + 0.008f, textTop(midY, VALUE_TEXT),
                                            VALUE_X1 - 0.006f, VALUE_TEXT, EditorTheme.TEXT, 1.0f);
                                }
                            }
                            curY -= ROW_ADVANCE;
                        } else {
                            if (isVisible) {
                                rowLabel(buffer, label, midY);
                                UIGeometryBatch.addTruncatedText(buffer, valStr.isEmpty() ? "-" : valStr, VALUE_X0 + 0.008f, textTop(midY, VALUE_TEXT),
                                        VALUE_X1, VALUE_TEXT, EditorTheme.TEXT_MUTED, 1.0f);
                            }
                            curY -= TEXT_ROW_ADVANCE;
                        }
                    }
                }
            }

            curY -= COMPONENT_GAP;
        }

        // Button: + ADD COMPONENT
        float drawAddBtnY = curY + scrollOffset;
        if (drawAddBtnY + ADD_ABOVE <= VIEW_TOP && drawAddBtnY - ADD_BELOW >= VIEW_BOTTOM) {
            float y0 = drawAddBtnY - ADD_BELOW, y1 = drawAddBtnY + ADD_ABOVE;
            UIGeometryBatch.addRoundedQuad(buffer, ADD_X0, y0, ADD_X1, y1, EditorTheme.RADIUS, EditorTheme.ACCENT, 1.0f);
            centeredTextIn(buffer, "+ ADD COMPONENT", ADD_X0, ADD_X1, (y0 + y1) * 0.5f, 0.95f, EditorTheme.TEXT);
        }
        curY -= ADD_BUTTON_ADVANCE;

        // Compute total content height & clamp scrollOffset
        float viewH = (VIEW_TOP - VIEW_BOTTOM) - 0.03f;
        float totalContentHeight = CONTENT_TOP - curY;
        maxScroll = Math.max(0.0f, totalContentHeight - viewH);
        scrollOffset = Math.max(0.0f, Math.min(maxScroll, scrollOffset));

        if (maxScroll > 0.001f) {
            float trackH = VIEW_TOP - VIEW_BOTTOM;
            float thumbHeight = Math.max(0.12f, (viewH / totalContentHeight) * trackH);
            float thumbTopY = VIEW_TOP - (scrollOffset / maxScroll) * (trackH - thumbHeight);
            UIGeometryBatch.addPill(buffer, 0.984f, thumbTopY - thumbHeight, 0.993f, thumbTopY, EditorTheme.GRAPHITE, 0.85f);
        }
    }

    /** Vertical advance of one property row; shared with InspectorInputDelegate hit-testing. */
    public static float rowAdvance(Control control) {
        return switch (control) {
            case ASSET -> ASSET_ROW_ADVANCE;
            case READ_ONLY -> TEXT_ROW_ADVANCE;
            default -> ROW_ADVANCE;
        };
    }

    private static float textTop(float midY, float size) {
        return midY + size * 0.009f;
    }

    private static void rowLabel(UIGeometryBatch.Buffer buffer, String label, float midY) {
        if (label == null || label.isEmpty()) return;
        UIGeometryBatch.addTruncatedText(buffer, label, LABEL_X, textTop(midY, LABEL_TEXT), VALUE_X0 - 0.01f, LABEL_TEXT, EditorTheme.TEXT_MUTED, 1.0f);
    }

    public static boolean isRemovable(Component comp) {
        return comp != null && !(comp instanceof Transform);
    }

    /** True when (ndcX, ndcY) lies on the REMOVE button of a section header drawn at drawY. */
    public static boolean isOverRemoveButton(float ndcX, float ndcY, float drawY) {
        return ndcX >= REMOVE_X0 && ndcX <= REMOVE_X1
                && ndcY >= drawY - CTRL_BELOW + REMOVE_INSET && ndcY <= drawY + CTRL_ABOVE + 0.002f - REMOVE_INSET;
    }

    private static void renderSectionHeader(UIGeometryBatch.Buffer buffer, String name, float drawY, boolean removable) {
        float y0 = drawY - CTRL_BELOW, y1 = drawY + CTRL_ABOVE + 0.002f;
        UIGeometryBatch.addRoundedQuad(buffer, 0.565f, y0, VALUE_X1, y1, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_RAISED, 1.0f);

        float mid = (y0 + y1) * 0.5f;
        float tw = UIGeometryBatch.squareWidth(buffer, 0.012f);
        float tx = LABEL_X;
        UIGeometryBatch.addTriangle(buffer, tx, mid + 0.005f, tx + tw, mid + 0.005f, tx + tw * 0.5f, mid - 0.006f, EditorTheme.TEXT_MUTED, 1.0f);

        strBuilder.setLength(0);
        strBuilder.append(name);
        float textX1 = removable ? REMOVE_X0 - 0.008f : VALUE_X1 - 0.008f;
        UIGeometryBatch.addTruncatedText(buffer, strBuilder, tx + tw + 0.012f, textTop(mid, 0.9f), textX1, 0.9f, EditorTheme.TEXT, 1.0f);

        if (removable) {
            UIGeometryBatch.addRoundedQuad(buffer, REMOVE_X0, y0 + REMOVE_INSET, REMOVE_X1, y1 - REMOVE_INSET, EditorTheme.RADIUS_SMALL, EditorTheme.RED, 1.0f);
            centeredTextIn(buffer, "REMOVE", REMOVE_X0, REMOVE_X1, mid, 0.68f, EditorTheme.TEXT);
        }
    }

    private static void well(UIGeometryBatch.Buffer buffer, float x0, float x1, float drawY) {
        UIGeometryBatch.addRoundedCard(buffer, x0, drawY - CTRL_BELOW, x1, drawY + CTRL_ABOVE, EditorTheme.RADIUS_SMALL,
                EditorTheme.SURFACE_SUNKEN, EditorTheme.BORDER, 1.0f);
    }

    /** Focused value well (accent ring); records its rect so clicks inside it keep focus. */
    private static void activeWell(UIGeometryBatch.Buffer buffer, float x0, float x1, float drawY) {
        float y0 = drawY - CTRL_BELOW, y1 = drawY + CTRL_ABOVE;
        UIGeometryBatch.addRoundedCard(buffer, x0, y0, x1, y1, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_SUNKEN, EditorTheme.ACCENT, 1.0f);
        activeWellX0 = x0;
        activeWellY0 = y0;
        activeWellX1 = x1;
        activeWellY1 = y1;
        activeWellVisible = true;
    }

    /** Inline input text: tail-clipped so the end stays visible, with a selection highlight or blinking caret. */
    private static void renderInputText(UIGeometryBatch.Buffer buffer, float x0, float x1, float midY) {
        CharSequence visible = activeInputBuffer;
        float caretW = 0.002f;
        while (visible.length() > 0 && UIGeometryBatch.textWidth(buffer, visible, VALUE_TEXT) > x1 - x0 - caretW) {
            visible = visible.subSequence(1, visible.length());
        }
        float w = UIGeometryBatch.textWidth(buffer, visible, VALUE_TEXT);
        if (activeSelectAll) {
            UIGeometryBatch.addQuad(buffer, x0 - 0.002f, midY - 0.011f, x0 + w + 0.002f, midY + 0.011f, EditorTheme.ACCENT, 0.4f);
        }
        UIGeometryBatch.addBoundedText(buffer, visible, x0, textTop(midY, VALUE_TEXT), x1 + 0.001f, VALUE_TEXT, EditorTheme.TEXT, 1.0f);
        if (!activeSelectAll && (System.nanoTime() / 500_000_000L) % 2 == 0) {
            UIGeometryBatch.addQuad(buffer, x0 + w, midY - 0.010f, x0 + w + caretW, midY + 0.010f, EditorTheme.TEXT, 1.0f);
        }
    }

    private static void button(UIGeometryBatch.Buffer buffer, String text, float x0, float x1, float drawY, int fill) {
        float y0 = drawY - CTRL_BELOW, y1 = drawY + CTRL_ABOVE;
        if (fill == EditorTheme.ACCENT) {
            UIGeometryBatch.addRoundedQuad(buffer, x0, y0, x1, y1, EditorTheme.RADIUS_SMALL, fill, 1.0f);
        } else {
            UIGeometryBatch.addRoundedCard(buffer, x0, y0, x1, y1, EditorTheme.RADIUS_SMALL, fill, EditorTheme.BORDER_STRONG, 1.0f);
        }
        centeredTextIn(buffer, text, x0, x1, (y0 + y1) * 0.5f, 0.72f, EditorTheme.TEXT);
    }

    private static float axisBadge(UIGeometryBatch.Buffer buffer, int axis, float x0, float drawY) {
        float h = 0.022f;
        float mid = drawY - (CTRL_BELOW - CTRL_ABOVE) * 0.5f;
        float w = Math.max(UIGeometryBatch.squareWidth(buffer, h), UIGeometryBatch.textWidth(buffer, 1, 0.75f) + 0.006f);
        UIGeometryBatch.addRoundedQuad(buffer, x0, mid - h * 0.5f, x0 + w, mid + h * 0.5f, 0.004f, EditorTheme.axisColor(axis), 1.0f);
        String letter = axis == 0 ? "X" : (axis == 1 ? "Y" : "Z");
        centeredTextIn(buffer, letter, x0, x0 + w, mid, 0.75f, EditorTheme.TEXT);
        return x0 + w;
    }

    private static void renderStepper(UIGeometryBatch.Buffer buffer, float drawY, float midY) {
        float y0 = drawY - CTRL_BELOW, y1 = drawY + CTRL_ABOVE;
        UIGeometryBatch.addRoundedCard(buffer, DEC_X0, y0, INC_X1, y1, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
        UIGeometryBatch.addQuad(buffer, INC_X0 - 0.001f, y0 + 0.006f, INC_X0 + 0.001f, y1 - 0.006f, EditorTheme.BORDER_STRONG, 1.0f);
        centeredTextIn(buffer, "-", DEC_X0, DEC_X1, midY, 0.9f, EditorTheme.TEXT);
        centeredTextIn(buffer, "+", INC_X0, INC_X1, midY, 0.9f, EditorTheme.TEXT);
    }

    private static void renderSwitch(UIGeometryBatch.Buffer buffer, boolean on, float midY) {
        float h = 0.024f;
        float knobW = UIGeometryBatch.squareWidth(buffer, h - 0.006f);
        float trackW = Math.max(UIGeometryBatch.squareWidth(buffer, h) * 1.8f, knobW * 2.0f + 0.004f);
        float x0 = VALUE_X0, x1 = VALUE_X0 + trackW;
        UIGeometryBatch.addPill(buffer, x0, midY - h * 0.5f, x1, midY + h * 0.5f, on ? EditorTheme.ACCENT : EditorTheme.GRAPHITE, 1.0f);
        float pad = (trackW - knobW * 2.0f) * 0.25f + UIGeometryBatch.squareWidth(buffer, 0.003f);
        float kx0 = on ? x1 - pad - knobW : x0 + pad;
        UIGeometryBatch.addPill(buffer, kx0, midY - h * 0.5f + 0.003f, kx0 + knobW, midY + h * 0.5f - 0.003f, EditorTheme.TEXT, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, on ? "ON" : "OFF", x1 + 0.01f, textTop(midY, 0.75f), VALUE_X1, 0.75f, EditorTheme.TEXT_MUTED, 1.0f);
    }

    private static void renderPopupButton(UIGeometryBatch.Buffer buffer, String value, float drawY, float midY) {
        float y0 = drawY - CTRL_BELOW, y1 = drawY + CTRL_ABOVE;
        UIGeometryBatch.addRoundedCard(buffer, VALUE_X0, y0, VALUE_X1, y1, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);

        float cw = UIGeometryBatch.squareWidth(buffer, 0.010f);
        float cx1 = VALUE_X1 - 0.008f, cx0 = cx1 - cw;
        float cxm = (cx0 + cx1) * 0.5f;
        UIGeometryBatch.addTriangle(buffer, cx0, midY + 0.002f, cx1, midY + 0.002f, cxm, midY + 0.009f, EditorTheme.TEXT_MUTED, 1.0f);
        UIGeometryBatch.addTriangle(buffer, cx0, midY - 0.002f, cxm, midY - 0.009f, cx1, midY - 0.002f, EditorTheme.TEXT_MUTED, 1.0f);

        UIGeometryBatch.addTruncatedText(buffer, value, VALUE_X0 + 0.008f, textTop(midY, VALUE_TEXT), cx0 - 0.006f, VALUE_TEXT, EditorTheme.TEXT, 1.0f);
    }

    private static int channelColor(String label) {
        if (label == null) return EditorTheme.ACCENT;
        return switch (label.toUpperCase()) {
            case "RED", "R" -> EditorTheme.RED;
            case "GREEN", "G" -> EditorTheme.GREEN;
            case "BLUE", "B" -> EditorTheme.ACCENT;
            default -> EditorTheme.ACCENT;
        };
    }

    private static void centeredTextIn(UIGeometryBatch.Buffer buffer, String text, float x0, float x1, float midY, float size, int rgb) {
        float w = UIGeometryBatch.textWidth(buffer, text, size);
        float x = x0 + Math.max(0.0f, ((x1 - x0) - w) * 0.5f);
        UIGeometryBatch.addBoundedText(buffer, text, x, midY + size * 0.009f, x1 + 0.001f, size, rgb, 1.0f);
    }

    private static void centeredText(UIGeometryBatch.Buffer buffer, String text, float midY, float size, int rgb) {
        centeredTextIn(buffer, text, 0.56f, 0.99f, midY, size, rgb);
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
        long pow = (decimals == 1) ? 10L : 100L;
        long scaled = Math.round(Math.abs((double) val) * pow);
        if (val < 0 && scaled != 0) sb.append('-');
        sb.append(scaled / pow).append('.');
        long frac = scaled % pow;
        if (decimals == 2 && frac < 10) sb.append('0');
        sb.append(frac);
    }
}
