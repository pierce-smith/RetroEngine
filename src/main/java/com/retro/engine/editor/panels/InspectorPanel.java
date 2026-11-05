package com.retro.engine.editor.panels;

import com.retro.engine.animation.AnimationSystem;
import com.retro.engine.animation.Skeleton;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.ComponentSchema;
import com.retro.engine.ecs.ComponentSchema.Control;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.editor.EditorTheme;
import com.retro.engine.editor.UIContext;
import com.retro.engine.scripting.EntityScript;
import com.retro.engine.scripting.ScriptEngine;
import com.retro.engine.ui.UIGeometryBatch;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * InspectorPanel handles dynamic component rendering using @InspectorProperty reflection
 * and Pillar 3 User Script Property Overrides reflection. Lays itself out against its docked tile (see {@link #layout()}):
 * the label column takes a clamped share of the width, controls shrink in narrow tiles, content is capped in wide ones,
 * and the scrolling view spans whatever height the tile has.
 */
public class InspectorPanel {

    // --- Vertical rhythm (row advances and control bands are size-independent) ---
    public static final float HEADER_ADVANCE = 0.046f;
    public static final float ROW_ADVANCE = 0.040f;
    public static final float ASSET_ROW_ADVANCE = 0.044f;
    public static final float TEXT_ROW_ADVANCE = 0.036f;
    public static final float COMPONENT_GAP = 0.012f;
    public static final float ADD_BUTTON_ADVANCE = 0.06f;

    /** Control band of a row spans [drawY - CTRL_BELOW, drawY + CTRL_ABOVE]. */
    public static final float CTRL_ABOVE = 0.004f, CTRL_BELOW = 0.030f;
    public static final float ADD_ABOVE = 0.01f, ADD_BELOW = 0.04f;
    public static final float REMOVE_INSET = 0.004f;

    // --- Responsive layout. Recomputed from the docked tile (DockPanel.INSPECTOR.rect) by layout(), which render and
    // InspectorInputDelegate both call first, so every edge below is shared by drawing and hit-testing.
    private static final float EDGE_PAD = 0.025f;
    /** Content never grows wider than this, so wide tiles keep label and value close instead of stretching rows. */
    private static final float MAX_CONTENT_W = 0.9f;
    /** Label column share of the content width, clamped to a readable range. */
    private static final float LABEL_SHARE = 0.40f, LABEL_MIN_W = 0.09f, LABEL_MAX_W = 0.28f;
    /** Value column width the fixed-size buttons were designed for; narrower columns shrink them proportionally. */
    private static final float NOMINAL_VALUE_W = 0.24f;
    private static final float STEP_W = 0.055f, PICK_W = 0.057f, IMPORT_W = 0.065f, BUTTON_GAP = 0.005f;
    private static final float PREFAB_W = 0.16f, REMOVE_W = 0.084f;

    // Two-column grid: label column | value column
    public static float labelX, valueX0, valueX1;
    // Float rows: value well + segmented [-|+] stepper
    public static float fieldX1, decX0, decX1, incX0, incX1;
    // Asset rows: value well + PICK + IMPORT
    public static float assetFieldX1, pickX0, pickX1, importX0, importX1;
    // Header "+ PREFAB" button, footer "+ ADD COMPONENT" button, section header "REMOVE" button
    public static float prefabX0, prefabX1, prefabY0, prefabY1;
    public static float addX0, addX1, removeX0, removeX1;
    // Scrolling view band and where the first row starts
    public static float viewTop, viewBottom, contentTop;
    private static float headerBarX0, headerTextY, headerRuleY, scrollbarX0, scrollbarX1;

    static { layout(); }

    /** Lays the panel out against its current tile. Cheap and allocation-free; safe to call any number of times. */
    public static void layout() {
        PanelRect r = DockPanel.INSPECTOR.rect;
        labelX = r.x0 + EDGE_PAD;
        float right = Math.max(labelX + 0.05f, Math.min(r.x1 - EDGE_PAD, labelX + MAX_CONTENT_W));
        float contentW = right - labelX;
        float labelW = Math.max(LABEL_MIN_W, Math.min(LABEL_MAX_W, contentW * LABEL_SHARE));
        valueX0 = labelX + Math.min(labelW, contentW * 0.5f);
        valueX1 = right;

        float k = Math.min(1.0f, (valueX1 - valueX0) / NOMINAL_VALUE_W);
        incX1 = right; incX0 = incX1 - STEP_W * k;
        decX1 = incX0; decX0 = decX1 - STEP_W * k;
        fieldX1 = decX0 - 0.007f * k;

        importX1 = right; importX0 = importX1 - IMPORT_W * k;
        pickX1 = importX0 - BUTTON_GAP * k; pickX0 = pickX1 - PICK_W * k;
        assetFieldX1 = pickX0 - BUTTON_GAP * k;

        prefabX1 = right + 0.005f;
        prefabX0 = prefabX1 - Math.min(PREFAB_W, contentW * 0.4f);
        prefabY0 = r.y1 - 0.080f; prefabY1 = r.y1 - 0.035f;
        addX0 = labelX + 0.005f; addX1 = right;
        removeX1 = right - 0.006f; removeX0 = removeX1 - REMOVE_W * Math.max(0.6f, k);

        headerBarX0 = labelX - 0.01f;
        headerTextY = r.y1 - 0.047f;
        headerRuleY = r.y1 - 0.095f;
        viewTop = r.y1 - 0.11f;
        contentTop = r.y1 - 0.14f;
        viewBottom = r.y0 + 0.02f;
        // Scrollbar rides just right of the (possibly capped) content, not at a far-away tile edge
        scrollbarX1 = Math.min(r.x1 - 0.007f, right + 0.018f); scrollbarX0 = scrollbarX1 - 0.009f;
    }

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
        return drawY + CTRL_ABOVE <= viewTop && drawY - CTRL_BELOW >= viewBottom;
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

    // Entity header text; names the Viewport-selected bone shown in the BONE TRANSFORM section
    private static final StringBuilder headerSb = new StringBuilder(64);

    // --- Bone Transform section: the selected bone's local pose as POSITION / ROTATION (degrees) / SCALE rows ---
    public static final int BONE_ROW_COUNT = 9;
    public static final String BONE_KEY_PREFIX = "bone:";
    private static final String[] BONE_ROW_KEYS = new String[BONE_ROW_COUNT];
    static {
        for (int i = 0; i < BONE_ROW_COUNT; i++) BONE_ROW_KEYS[i] = BONE_KEY_PREFIX + i;
    }

    // Euler angles are not unique, so the last values the user set are shown for as long as the bone keeps the matrix
    // they produced; otherwise re-decomposing after every edit could flip axes (e.g. Y past 90 degrees) under the cursor.
    private static AnimatorComponent eulerCacheAnimator = null;
    private static int eulerCacheBone = -1;
    private static final float[] eulerCacheLocal = new float[16];
    private static final float[] eulerCacheTrs = new float[BONE_ROW_COUNT];
    private static final float[] boneTrsScratch = new float[BONE_ROW_COUNT];

    /** Inline-edit key of bone row {@code row} (0-2 position, 3-5 rotation, 6-8 scale). */
    public static String boneRowKey(int row) { return BONE_ROW_KEYS[row]; }

    /** Height of the BONE TRANSFORM section, which pushes the component list down while a bone is selected. */
    public static float boneSectionHeight() {
        return HEADER_ADVANCE + BONE_ROW_COUNT * ROW_ADVANCE + COMPONENT_GAP;
    }

    /** Vertical step and clamp of bone rows: position (units), rotation (degrees), scale. */
    public static float boneRowStep(int row) {
        return row < 3 ? 0.01f : (row < 6 ? 1.0f : 0.01f);
    }

    /** Fills {@code outTrs} with bone {@code boneIndex}'s local pose as pos xyz, Euler xyz in degrees, scale xyz. */
    public static boolean readBoneTrs(AnimatorComponent animator, int boneIndex, float[] outTrs) {
        float[] local = (animator != null) ? animator.getLocalPose(boneIndex) : null;
        if (local == null) return false;
        if (animator == eulerCacheAnimator && boneIndex == eulerCacheBone && Arrays.equals(local, eulerCacheLocal)) {
            System.arraycopy(eulerCacheTrs, 0, outTrs, 0, BONE_ROW_COUNT);
            return true;
        }
        float[] pos = new float[3], quat = new float[4], scale = new float[3];
        AnimationSystem.decomposeTRS(local, pos, quat, scale);
        outTrs[0] = pos[0]; outTrs[1] = pos[1]; outTrs[2] = pos[2];
        quatToEulerDegrees(quat, outTrs, 3);
        outTrs[6] = scale[0]; outTrs[7] = scale[1]; outTrs[8] = scale[2];
        return true;
    }

    /** Recomposes pos / Euler-degrees / scale into a column-major local matrix and remembers the Euler values for display. */
    public static void composeBoneTrs(AnimatorComponent animator, int boneIndex, float[] trs, float[] outLocal) {
        float[] quat = new float[4];
        eulerDegreesToQuat(trs[3], trs[4], trs[5], quat);
        AnimationSystem.composeTRS(new float[] {trs[0], trs[1], trs[2]}, quat, new float[] {trs[6], trs[7], trs[8]}, outLocal);
        eulerCacheAnimator = animator;
        eulerCacheBone = boneIndex;
        System.arraycopy(outLocal, 0, eulerCacheLocal, 0, 16);
        System.arraycopy(trs, 0, eulerCacheTrs, 0, BONE_ROW_COUNT);
    }

    /** XYZ Euler (Blender's default order: R = Rz * Ry * Rx) in degrees to a quaternion (x, y, z, w). */
    private static void eulerDegreesToQuat(float xDeg, float yDeg, float zDeg, float[] q) {
        double hx = Math.toRadians(xDeg) * 0.5, hy = Math.toRadians(yDeg) * 0.5, hz = Math.toRadians(zDeg) * 0.5;
        double cx = Math.cos(hx), sx = Math.sin(hx), cy = Math.cos(hy), sy = Math.sin(hy), cz = Math.cos(hz), sz = Math.sin(hz);
        q[0] = (float) (sx * cy * cz - cx * sy * sz);
        q[1] = (float) (cx * sy * cz + sx * cy * sz);
        q[2] = (float) (cx * cy * sz - sx * sy * cz);
        q[3] = (float) (cx * cy * cz + sx * sy * sz);
    }

    /** Inverse of {@link #eulerDegreesToQuat}; writes x, y, z degrees at {@code out[off..off+2]}. */
    private static void quatToEulerDegrees(float[] q, float[] out, int off) {
        double x = q[0], y = q[1], z = q[2], w = q[3];
        double sinP = 2.0 * (w * y - z * x);
        out[off] = (float) Math.toDegrees(Math.atan2(2.0 * (w * x + y * z), 1.0 - 2.0 * (x * x + y * y)));
        out[off + 1] = (float) Math.toDegrees(Math.abs(sinP) >= 1.0 ? Math.copySign(Math.PI / 2.0, sinP) : Math.asin(sinP));
        out[off + 2] = (float) Math.toDegrees(Math.atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z)));
    }

    public static void render(UIContext ui, Entity selectedEntity, List<AssetMeta> assetDirectory) {
        render(ui, selectedEntity, null, -1, assetDirectory);
    }

    public static void render(UIContext ui, Entity selectedEntity, Skeleton.BoneNode selectedBone, int selectedBoneIndex,
                              List<AssetMeta> assetDirectory) {
        UIGeometryBatch.Buffer buffer = ui.geometry();
        layout();
        PanelRect r = DockPanel.INSPECTOR.rect;
        UIGeometryBatch.addQuad(buffer, r.x0, r.y0, r.x1, r.y1, EditorTheme.SURFACE, 1.0f);
        activeWellVisible = false;

        if (selectedEntity == null) {
            resetScroll();
            float y = r.y1 - Math.min(0.64f, r.height() * 0.4f);
            centeredText(buffer, "NO SELECTION", y, 1.15f, EditorTheme.TEXT_MUTED);
            centeredText(buffer, "SELECT AN ENTITY IN THE HIERARCHY", y - 0.07f, 0.8f, EditorTheme.TEXT_DISABLED);
            return;
        }

        // Entity header
        headerSb.setLength(0);
        headerSb.append(selectedEntity.name);
        if (selectedBone != null) headerSb.append(" / BONE ").append(selectedBone.id).append(": ").append(selectedBone.name);
        UIGeometryBatch.addTruncatedText(buffer, headerSb, labelX, headerTextY, prefabX0 - 0.012f, 1.1f,
                selectedBone != null ? EditorTheme.ACCENT : EditorTheme.TEXT, 1.0f);
        UIGeometryBatch.addRoundedCard(buffer, prefabX0, prefabY0, prefabX1, prefabY1, EditorTheme.RADIUS, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
        centeredTextIn(buffer, "+ PREFAB", prefabX0, prefabX1, (prefabY0 + prefabY1) * 0.5f, 0.8f, EditorTheme.TEXT);
        UIGeometryBatch.addQuad(buffer, labelX, headerRuleY, valueX1, headerRuleY + EditorTheme.HAIRLINE, EditorTheme.BORDER, 1.0f);

        float curY = contentTop;

        AnimatorComponent boneAnimator = (selectedBone != null) ? selectedEntity.getComponent(AnimatorComponent.class) : null;
        if (boneAnimator != null && readBoneTrs(boneAnimator, selectedBoneIndex, boneTrsScratch)) {
            curY = renderBoneSection(buffer, boneAnimator, curY);
        }

        for (Component comp : selectedEntity.getAllComponents()) {
            float drawHeaderY = curY + scrollOffset;

            if (isRowVisible(drawHeaderY)) {
                // Each ScriptComponent gets its own card, titled by the script it runs
                String title = (comp instanceof ScriptComponent sc) ? sc.getInspectorTitle() : comp.getComponentName();
                renderSectionHeader(buffer, title, drawHeaderY, isRemovable(comp), comp.isInspectorCollapsed);
            }
            curY -= HEADER_ADVANCE;
            if (comp.isInspectorCollapsed) {
                curY -= COMPONENT_GAP;
                continue;
            }

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
                            well(buffer, valueX0, assetFieldX1, drawY);
                            UIGeometryBatch.addTruncatedText(buffer, empty ? "NONE" : extractFileName(pathStr), valueX0 + 0.008f,
                                    textTop(midY, VALUE_TEXT), assetFieldX1 - 0.006f, VALUE_TEXT, empty ? EditorTheme.TEXT_MUTED : EditorTheme.TEXT, 1.0f);
                            button(buffer, "PICK", pickX0, pickX1, drawY, EditorTheme.SURFACE_RAISED);
                            button(buffer, "IMPORT", importX0, importX1, drawY, EditorTheme.ACCENT);
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
                            float floatVal = (val instanceof Number n) ? n.floatValue() : 0.0f;
                            boolean isIntField = val instanceof Integer;
                            boolean isColor = p.isColor();
                            int axis = isColor ? -1 : CACHED_AXES.computeIfAbsent(field, f -> axisOf(label));
                            String display = CACHED_DISPLAY_LABELS.computeIfAbsent(field, f -> {
                                int ax = axisOf(label);
                                if (isColor || ax < 0) return label;
                                return ax == 0 ? vectorGroupName(label) : "";
                            });

                            boolean editing = isActiveField(comp, field.getName());
                            rowLabel(buffer, display, midY);
                            if (editing) activeWell(buffer, valueX0, fieldX1, drawY);
                            else well(buffer, valueX0, fieldX1, drawY);

                            float textX = valueX0 + 0.008f;
                            if (isColor && !editing) {
                                float range = prop.max() - prop.min();
                                float t = range > 0.0f ? Math.max(0.0f, Math.min(1.0f, (floatVal - prop.min()) / range)) : 0.0f;
                                float inset = 0.004f;
                                float fillX1 = valueX0 + inset + (fieldX1 - valueX0 - inset * 2.0f) * t;
                                if (fillX1 > valueX0 + inset) {
                                    UIGeometryBatch.addRoundedQuad(buffer, valueX0 + inset, drawY - CTRL_BELOW + inset, fillX1, drawY + CTRL_ABOVE - inset,
                                            EditorTheme.RADIUS_SMALL, channelColor(label), 0.45f);
                                }
                            } else if (axis >= 0) {
                                textX = axisBadge(buffer, axis, valueX0 + 0.005f, drawY) + 0.008f;
                            }

                            if (editing) {
                                renderInputText(buffer, textX, fieldX1 - 0.006f, midY);
                            } else {
                                strBuilder.setLength(0);
                                if (isIntField) strBuilder.append((int) floatVal);
                                else appendFloat(strBuilder, floatVal, isColor ? 2 : 1);
                                UIGeometryBatch.addTruncatedText(buffer, strBuilder, textX, textTop(midY, VALUE_TEXT), fieldX1 - 0.006f, VALUE_TEXT, EditorTheme.TEXT, 1.0f);
                            }

                            renderStepper(buffer, drawY, midY);
                        }
                    } else if (p.control == Control.STRING) {
                        if (isVisible) {
                            rowLabel(buffer, label, midY);
                            if (isActiveField(comp, field.getName())) {
                                activeWell(buffer, valueX0, valueX1, drawY);
                                renderInputText(buffer, valueX0 + 0.008f, valueX1 - 0.006f, midY);
                            } else {
                                String strVal = (val != null) ? (String) val : "";
                                well(buffer, valueX0, valueX1, drawY);
                                UIGeometryBatch.addTruncatedText(buffer, strVal, valueX0 + 0.008f, textTop(midY, VALUE_TEXT),
                                        valueX1 - 0.006f, VALUE_TEXT, EditorTheme.TEXT, 1.0f);
                            }
                        }
                    } else {
                        if (isVisible) {
                            String textVal = (val != null) ? val.toString() : "";
                            rowLabel(buffer, label, midY);
                            UIGeometryBatch.addTruncatedText(buffer, textVal.isEmpty() ? "-" : textVal, valueX0 + 0.008f, textTop(midY, VALUE_TEXT),
                                    valueX1, VALUE_TEXT, EditorTheme.TEXT_MUTED, 1.0f);
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
                                    activeWell(buffer, valueX0, fieldX1, drawY);
                                    renderInputText(buffer, valueX0 + 0.008f, fieldX1 - 0.006f, midY);
                                } else {
                                    float floatVal = 0.0f;
                                    try { floatVal = Float.parseFloat(valStr); } catch (Exception ignored) {}
                                    well(buffer, valueX0, fieldX1, drawY);
                                    strBuilder.setLength(0);
                                    appendFloat(strBuilder, floatVal, 1);
                                    UIGeometryBatch.addTruncatedText(buffer, strBuilder, valueX0 + 0.008f, textTop(midY, VALUE_TEXT), fieldX1 - 0.006f, VALUE_TEXT, EditorTheme.TEXT, 1.0f);
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
                                    activeWell(buffer, valueX0, valueX1, drawY);
                                    renderInputText(buffer, valueX0 + 0.008f, valueX1 - 0.006f, midY);
                                } else {
                                    well(buffer, valueX0, valueX1, drawY);
                                    UIGeometryBatch.addTruncatedText(buffer, valStr, valueX0 + 0.008f, textTop(midY, VALUE_TEXT),
                                            valueX1 - 0.006f, VALUE_TEXT, EditorTheme.TEXT, 1.0f);
                                }
                            }
                            curY -= ROW_ADVANCE;
                        } else {
                            if (isVisible) {
                                rowLabel(buffer, label, midY);
                                UIGeometryBatch.addTruncatedText(buffer, valStr.isEmpty() ? "-" : valStr, valueX0 + 0.008f, textTop(midY, VALUE_TEXT),
                                        valueX1, VALUE_TEXT, EditorTheme.TEXT_MUTED, 1.0f);
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
        if (drawAddBtnY + ADD_ABOVE <= viewTop && drawAddBtnY - ADD_BELOW >= viewBottom) {
            float y0 = drawAddBtnY - ADD_BELOW, y1 = drawAddBtnY + ADD_ABOVE;
            UIGeometryBatch.addRoundedQuad(buffer, addX0, y0, addX1, y1, EditorTheme.RADIUS, EditorTheme.ACCENT, 1.0f);
            centeredTextIn(buffer, "+ ADD COMPONENT", addX0, addX1, (y0 + y1) * 0.5f, 0.95f, EditorTheme.TEXT);
        }
        curY -= ADD_BUTTON_ADVANCE;
        finishScroll(buffer, curY);
    }

    /** Clamps the scroll offset to the content height ending at {@code curY} and draws the scrollbar thumb. */
    private static void finishScroll(UIGeometryBatch.Buffer buffer, float curY) {
        float viewH = (viewTop - viewBottom) - 0.03f;
        float totalContentHeight = contentTop - curY;
        maxScroll = Math.max(0.0f, totalContentHeight - viewH);
        scrollOffset = Math.max(0.0f, Math.min(maxScroll, scrollOffset));

        if (maxScroll > 0.001f) {
            float trackH = viewTop - viewBottom;
            float thumbHeight = Math.max(0.12f, (viewH / totalContentHeight) * trackH);
            float thumbTopY = viewTop - (scrollOffset / maxScroll) * (trackH - thumbHeight);
            UIGeometryBatch.addPill(buffer, scrollbarX0, thumbTopY - thumbHeight, scrollbarX1, thumbTopY, EditorTheme.GRAPHITE, 0.85f);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Multi-selection view
    // ------------------------------------------------------------------------------------------

    /** Inline-edit key prefix for batch transform fields (the edit is anchored on the primary entity's Transform). */
    public static final String MULTI_KEY_PREFIX = "multi:";
    private static final float MIXED_EPSILON = 1.0e-4f;

    /** Transform properties offered for batch editing, in the same order the single-entity Transform card shows them. */
    public static List<ComponentSchema.Property> batchTransformProperties() {
        List<ComponentSchema.Property> out = new ArrayList<>();
        for (ComponentSchema.Property p : ComponentSchema.of(Transform.class).inspectorProperties()) {
            if (p.control == Control.NUMBER) out.add(p);
        }
        return out;
    }

    /**
     * Component classes every selected entity has, in the primary entity's card order. Transform is edited by the batch
     * section and ScriptComponent is per-script (an entity may carry several), so neither is listed.
     */
    public static List<Class<? extends Component>> sharedComponentTypes(List<Entity> selection, Entity primary) {
        List<Class<? extends Component>> out = new ArrayList<>();
        if (primary == null) return out;
        for (Component c : primary.getAllComponents()) {
            if (c == null || c instanceof Transform || c instanceof ScriptComponent || out.contains(c.getClass())) continue;
            boolean shared = true;
            for (Entity e : selection) {
                if (e.getComponent(c.getClass()) == null) { shared = false; break; }
            }
            if (shared) out.add(c.getClass());
        }
        return out;
    }

    /** The common value of {@code field} across the selection's transforms, or NaN when they differ (or none have one). */
    public static float sharedTransformValue(List<Entity> selection, Field field) {
        float value = Float.NaN;
        for (Entity e : selection) {
            Transform tc = e.getTransform();
            if (tc == null) continue;
            float v;
            try { v = field.getFloat(tc); } catch (IllegalAccessException ex) { return Float.NaN; }
            if (Float.isNaN(value)) value = v;
            else if (Math.abs(value - v) > MIXED_EPSILON) return Float.NaN;
        }
        return value;
    }

    /**
     * Inspector for a multi-selection: "N ENTITIES SELECTED", batch transform rows (a shared value or MIXED; typing sets
     * every entity, the steppers offset every entity), the components all of them share (REMOVE ALL) and ADD TO ALL.
     * {@link com.retro.engine.editor.input.InspectorInputDelegate} hit-tests the same row sequence.
     */
    public static void renderMultiSelection(UIContext ui, List<Entity> selection, Entity primary) {
        UIGeometryBatch.Buffer buffer = ui.geometry();
        layout();
        PanelRect r = DockPanel.INSPECTOR.rect;
        UIGeometryBatch.addQuad(buffer, r.x0, r.y0, r.x1, r.y1, EditorTheme.SURFACE, 1.0f);
        activeWellVisible = false;

        headerSb.setLength(0);
        headerSb.append(selection.size()).append(" ENTITIES SELECTED");
        UIGeometryBatch.addTruncatedText(buffer, headerSb, labelX, headerTextY, valueX1, 1.1f, EditorTheme.ACCENT, 1.0f);
        UIGeometryBatch.addQuad(buffer, labelX, headerRuleY, valueX1, headerRuleY + EditorTheme.HAIRLINE, EditorTheme.BORDER, 1.0f);

        float curY = contentTop;

        // Names of the selected entities (primary first)
        float drawY = curY + scrollOffset;
        if (isRowVisible(drawY)) {
            strBuilder.setLength(0);
            if (primary != null) strBuilder.append(primary.name);
            for (Entity e : selection) {
                if (e == primary) continue;
                if (strBuilder.length() > 0) strBuilder.append(", ");
                strBuilder.append(e.name);
                if (strBuilder.length() > 120) break;
            }
            UIGeometryBatch.addTruncatedText(buffer, strBuilder, labelX, textTop(drawY - 0.012f, 0.75f), valueX1, 0.75f, EditorTheme.TEXT_MUTED, 1.0f);
        }
        curY -= TEXT_ROW_ADVANCE;

        // Batch transform
        drawY = curY + scrollOffset;
        if (isRowVisible(drawY)) renderSectionHeader(buffer, "TRANSFORM (ALL SELECTED)", drawY, false, false);
        curY -= HEADER_ADVANCE;
        Transform anchor = (primary != null) ? primary.getTransform() : null;
        for (ComponentSchema.Property p : batchTransformProperties()) {
            drawY = curY + scrollOffset;
            curY -= rowAdvance(p.control);
            if (!isRowVisible(drawY)) continue;
            float midY = drawY - (CTRL_BELOW - CTRL_ABOVE) * 0.5f;
            Field field = p.field;
            int axis = CACHED_AXES.computeIfAbsent(field, f -> axisOf(p.label));
            String display = CACHED_DISPLAY_LABELS.computeIfAbsent(field, f -> {
                int ax = axisOf(p.label);
                if (ax < 0) return p.label;
                return ax == 0 ? vectorGroupName(p.label) : "";
            });
            boolean editing = anchor != null && isActiveField(anchor, MULTI_KEY_PREFIX + field.getName());
            rowLabel(buffer, display, midY);
            if (editing) activeWell(buffer, valueX0, fieldX1, drawY);
            else well(buffer, valueX0, fieldX1, drawY);
            float textX = (axis >= 0) ? axisBadge(buffer, axis, valueX0 + 0.005f, drawY) + 0.008f : valueX0 + 0.008f;
            if (editing) {
                renderInputText(buffer, textX, fieldX1 - 0.006f, midY);
            } else {
                float shared = sharedTransformValue(selection, field);
                strBuilder.setLength(0);
                if (Float.isNaN(shared)) strBuilder.append("MIXED");
                else appendFloat(strBuilder, shared, 1);
                UIGeometryBatch.addTruncatedText(buffer, strBuilder, textX, textTop(midY, VALUE_TEXT), fieldX1 - 0.006f, VALUE_TEXT,
                        Float.isNaN(shared) ? EditorTheme.TEXT_MUTED : EditorTheme.TEXT, 1.0f);
            }
            renderStepper(buffer, drawY, midY);
        }
        curY -= COMPONENT_GAP;

        // Shared components
        drawY = curY + scrollOffset;
        if (isRowVisible(drawY)) renderSectionHeader(buffer, "SHARED COMPONENTS", drawY, false, false);
        curY -= HEADER_ADVANCE;
        List<Class<? extends Component>> shared = sharedComponentTypes(selection, primary);
        if (shared.isEmpty()) {
            drawY = curY + scrollOffset;
            if (isRowVisible(drawY)) {
                UIGeometryBatch.addTruncatedText(buffer, "NO COMPONENTS SHARED BY ALL SELECTED", labelX, textTop(drawY - 0.012f, 0.75f),
                        valueX1, 0.75f, EditorTheme.TEXT_DISABLED, 1.0f);
            }
            curY -= TEXT_ROW_ADVANCE;
        }
        for (Class<? extends Component> type : shared) {
            drawY = curY + scrollOffset;
            curY -= ROW_ADVANCE;
            if (!isRowVisible(drawY)) continue;
            float midY = drawY - (CTRL_BELOW - CTRL_ABOVE) * 0.5f;
            Component sample = primary.getComponent(type);
            rowLabel(buffer, sample.getComponentName(), midY);
            if (isRemovable(sample)) button(buffer, "REMOVE ALL", valueX0, valueX1, drawY, EditorTheme.SURFACE_RAISED);
        }
        curY -= COMPONENT_GAP;

        float drawAddBtnY = curY + scrollOffset;
        if (drawAddBtnY + ADD_ABOVE <= viewTop && drawAddBtnY - ADD_BELOW >= viewBottom) {
            float y0 = drawAddBtnY - ADD_BELOW, y1 = drawAddBtnY + ADD_ABOVE;
            UIGeometryBatch.addRoundedQuad(buffer, addX0, y0, addX1, y1, EditorTheme.RADIUS, EditorTheme.ACCENT, 1.0f);
            centeredTextIn(buffer, "+ ADD COMPONENT TO ALL", addX0, addX1, (y0 + y1) * 0.5f, 0.95f, EditorTheme.TEXT);
        }
        curY -= ADD_BUTTON_ADVANCE;
        finishScroll(buffer, curY);
    }

    /** Draws the BONE TRANSFORM rows from {@link #boneTrsScratch}; returns curY below the section. */
    private static float renderBoneSection(UIGeometryBatch.Buffer buffer, AnimatorComponent animator, float curY) {
        float drawHeaderY = curY + scrollOffset;
        if (isRowVisible(drawHeaderY)) renderSectionHeader(buffer, "Bone Transform", drawHeaderY, false, false);
        curY -= HEADER_ADVANCE;

        for (int row = 0; row < BONE_ROW_COUNT; row++) {
            float drawY = curY + scrollOffset;
            curY -= ROW_ADVANCE;
            if (!isRowVisible(drawY)) continue;
            float midY = drawY - (CTRL_BELOW - CTRL_ABOVE) * 0.5f;
            int axis = row % 3;
            if (axis == 0) rowLabel(buffer, row == 0 ? "POSITION" : (row == 3 ? "ROTATION" : "SCALE"), midY);

            boolean editing = isActiveField(animator, BONE_ROW_KEYS[row]);
            if (editing) activeWell(buffer, valueX0, fieldX1, drawY);
            else well(buffer, valueX0, fieldX1, drawY);
            float textX = axisBadge(buffer, axis, valueX0 + 0.005f, drawY) + 0.008f;
            if (editing) {
                renderInputText(buffer, textX, fieldX1 - 0.006f, midY);
            } else {
                strBuilder.setLength(0);
                appendFloat(strBuilder, boneTrsScratch[row], row < 3 || row >= 6 ? 2 : 1);
                UIGeometryBatch.addTruncatedText(buffer, strBuilder, textX, textTop(midY, VALUE_TEXT), fieldX1 - 0.006f, VALUE_TEXT, EditorTheme.TEXT, 1.0f);
            }
            renderStepper(buffer, drawY, midY);
        }
        return curY - COMPONENT_GAP;
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
        UIGeometryBatch.addTruncatedText(buffer, label, labelX, textTop(midY, LABEL_TEXT), valueX0 - 0.01f, LABEL_TEXT, EditorTheme.TEXT_MUTED, 1.0f);
    }

    public static boolean isRemovable(Component comp) {
        return comp != null && !(comp instanceof Transform);
    }

    /** True when (ndcX, ndcY) lies on the bar of a section header drawn at drawY (REMOVE button included). */
    public static boolean isOverSectionHeader(float ndcX, float ndcY, float drawY) {
        return ndcX >= headerBarX0 && ndcX <= valueX1 && ndcY >= drawY - CTRL_BELOW && ndcY <= drawY + CTRL_ABOVE + 0.002f;
    }

    /** True when (ndcX, ndcY) lies on the REMOVE button of a section header drawn at drawY. */
    public static boolean isOverRemoveButton(float ndcX, float ndcY, float drawY) {
        return ndcX >= removeX0 && ndcX <= removeX1
                && ndcY >= drawY - CTRL_BELOW + REMOVE_INSET && ndcY <= drawY + CTRL_ABOVE + 0.002f - REMOVE_INSET;
    }

    private static void renderSectionHeader(UIGeometryBatch.Buffer buffer, String name, float drawY, boolean removable, boolean collapsed) {
        float y0 = drawY - CTRL_BELOW, y1 = drawY + CTRL_ABOVE + 0.002f;
        UIGeometryBatch.addRoundedQuad(buffer, headerBarX0, y0, valueX1, y1, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_RAISED, 1.0f);

        float mid = (y0 + y1) * 0.5f;
        float tw = UIGeometryBatch.squareWidth(buffer, 0.012f);
        float tx = labelX;
        // Disclosure arrow: pointing down (expanded) or right (collapsed)
        if (collapsed) {
            UIGeometryBatch.addTriangle(buffer, tx + tw * 0.15f, mid + 0.0065f, tx + tw * 0.15f, mid - 0.0065f, tx + tw * 0.95f, mid, EditorTheme.TEXT_MUTED, 1.0f);
        } else {
            UIGeometryBatch.addTriangle(buffer, tx, mid + 0.005f, tx + tw, mid + 0.005f, tx + tw * 0.5f, mid - 0.006f, EditorTheme.TEXT_MUTED, 1.0f);
        }

        strBuilder.setLength(0);
        strBuilder.append(name);
        float textX1 = removable ? removeX0 - 0.008f : valueX1 - 0.008f;
        UIGeometryBatch.addTruncatedText(buffer, strBuilder, tx + tw + 0.012f, textTop(mid, 0.9f), textX1, 0.9f, EditorTheme.TEXT, 1.0f);

        if (removable) {
            UIGeometryBatch.addRoundedQuad(buffer, removeX0, y0 + REMOVE_INSET, removeX1, y1 - REMOVE_INSET, EditorTheme.RADIUS_SMALL, EditorTheme.RED, 1.0f);
            centeredTextIn(buffer, "REMOVE", removeX0, removeX1, mid, 0.68f, EditorTheme.TEXT);
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
        UIGeometryBatch.addRoundedCard(buffer, decX0, y0, incX1, y1, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);
        UIGeometryBatch.addQuad(buffer, incX0 - 0.001f, y0 + 0.006f, incX0 + 0.001f, y1 - 0.006f, EditorTheme.BORDER_STRONG, 1.0f);
        centeredTextIn(buffer, "-", decX0, decX1, midY, 0.9f, EditorTheme.TEXT);
        centeredTextIn(buffer, "+", incX0, incX1, midY, 0.9f, EditorTheme.TEXT);
    }

    private static void renderSwitch(UIGeometryBatch.Buffer buffer, boolean on, float midY) {
        float h = 0.024f;
        float knobW = UIGeometryBatch.squareWidth(buffer, h - 0.006f);
        float trackW = Math.max(UIGeometryBatch.squareWidth(buffer, h) * 1.8f, knobW * 2.0f + 0.004f);
        float x0 = valueX0, x1 = valueX0 + trackW;
        UIGeometryBatch.addPill(buffer, x0, midY - h * 0.5f, x1, midY + h * 0.5f, on ? EditorTheme.ACCENT : EditorTheme.GRAPHITE, 1.0f);
        float pad = (trackW - knobW * 2.0f) * 0.25f + UIGeometryBatch.squareWidth(buffer, 0.003f);
        float kx0 = on ? x1 - pad - knobW : x0 + pad;
        UIGeometryBatch.addPill(buffer, kx0, midY - h * 0.5f + 0.003f, kx0 + knobW, midY + h * 0.5f - 0.003f, EditorTheme.TEXT, 1.0f);
        UIGeometryBatch.addBoundedText(buffer, on ? "ON" : "OFF", x1 + 0.01f, textTop(midY, 0.75f), valueX1, 0.75f, EditorTheme.TEXT_MUTED, 1.0f);
    }

    private static void renderPopupButton(UIGeometryBatch.Buffer buffer, String value, float drawY, float midY) {
        float y0 = drawY - CTRL_BELOW, y1 = drawY + CTRL_ABOVE;
        UIGeometryBatch.addRoundedCard(buffer, valueX0, y0, valueX1, y1, EditorTheme.RADIUS_SMALL, EditorTheme.SURFACE_RAISED, EditorTheme.BORDER_STRONG, 1.0f);

        float cw = UIGeometryBatch.squareWidth(buffer, 0.010f);
        float cx1 = valueX1 - 0.008f, cx0 = cx1 - cw;
        float cxm = (cx0 + cx1) * 0.5f;
        UIGeometryBatch.addTriangle(buffer, cx0, midY + 0.002f, cx1, midY + 0.002f, cxm, midY + 0.009f, EditorTheme.TEXT_MUTED, 1.0f);
        UIGeometryBatch.addTriangle(buffer, cx0, midY - 0.002f, cxm, midY - 0.009f, cx1, midY - 0.002f, EditorTheme.TEXT_MUTED, 1.0f);

        UIGeometryBatch.addTruncatedText(buffer, value, valueX0 + 0.008f, textTop(midY, VALUE_TEXT), cx0 - 0.006f, VALUE_TEXT, EditorTheme.TEXT, 1.0f);
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
        PanelRect r = DockPanel.INSPECTOR.rect;
        centeredTextIn(buffer, text, r.x0 + 0.01f, r.x1 - 0.01f, midY, size, rgb);
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
