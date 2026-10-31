package com.retro.engine.editor.input;

import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ui.panels.InspectorPanel;
import com.retro.engine.editor.ui.panels.InspectorProperty;
import com.retro.engine.editor.ui.panels.MenuItem;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.entity.EntitySpawner;
import com.retro.engine.scene.components.*;
import com.retro.engine.assets.AssetType;
import com.retro.engine.editor.audio.UISoundPlayer;
import com.retro.engine.editor.audio.UISoundPlayer.UISound;
import com.retro.engine.editor.commands.EditorCommands;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.scene.components.gameplay.CharacterControllerComponent;
import com.retro.engine.scene.components.gameplay.ScriptComponent;
import com.retro.engine.scene.components.gameplay.VehicleControllerComponent;
import com.retro.engine.scene.components.media.AnimatorComponent;
import com.retro.engine.scene.components.media.AudioSource;
import com.retro.engine.scene.components.physics.BoxCollider;
import com.retro.engine.scene.components.physics.CapsuleCollider;
import com.retro.engine.scene.components.physics.RigidBodyComponent;
import com.retro.engine.scene.components.physics.SphereCollider;
import com.retro.engine.scene.components.rendering.Light;
import com.retro.engine.scene.components.rendering.Material;
import com.retro.engine.scene.components.rendering.MeshFilter;
import com.retro.engine.scene.components.ui.UICanvasComponent;
import com.retro.engine.scene.components.ui.UIImage;
import com.retro.engine.scene.components.ui.UIPanel;
import com.retro.engine.scene.components.ui.UIProgressBar;
import com.retro.engine.scene.components.ui.UIText;
import com.retro.engine.scripting.ScriptEngine;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Dynamic reflection-driven input delegate for InspectorPanel UI controls.
 */
public class InspectorInputDelegate {

    private static boolean isDraggingSlider = false;
    private static String draggingFieldName = null;
    private static Component draggingComponent = null;

    public static void processInspectorClick(float ndcX, float ndcY, EditorWorkspace manager) {
        if (manager == null) return;
        // Clicking inside the focused field keeps typing; anywhere else is focus loss, which commits
        if (InspectorPanel.isOverActiveField(ndcX, ndcY)) return;
        commitInlineEdit(manager);

        Entity selected = manager.getSelectedEntity();
        if (selected == null || ndcX < 0.55f) return;

        // Top Header: "+ PREFAB" Button
        if (ndcX >= InspectorPanel.PREFAB_X0 && ndcX <= InspectorPanel.PREFAB_X1 && ndcY >= InspectorPanel.PREFAB_Y0 && ndcY <= InspectorPanel.PREFAB_Y1) {
            UISoundPlayer.play(UISound.OPEN_MODAL);
            manager.getSceneManager().promptSaveSelectedAsPrefab(
                manager.getAssetDatabase().getMutableAssetDirectory(),
                manager.getAssetDatabase()
            );
            return;
        }

        float scrollOffset = InspectorPanel.getScrollOffset();
        float curY = InspectorPanel.CONTENT_TOP;

        // BONE TRANSFORM section (drawn above the components while a Viewport bone is selected)
        AnimatorComponent boneAnimator = boneSectionAnimator(manager, selected);
        if (boneAnimator != null) {
            if (processBoneRowClick(ndcX, ndcY, curY + scrollOffset, manager, selected, boneAnimator)) return;
            curY -= InspectorPanel.boneSectionHeight();
        }

        for (Component comp : selected.getAllComponents()) {
            if (comp == null) continue;

            // Section header: "REMOVE" button
            float drawHeaderY = curY + scrollOffset;
            if (InspectorPanel.isRemovable(comp) && InspectorPanel.isRowVisible(drawHeaderY)
                    && InspectorPanel.isOverRemoveButton(ndcX, ndcY, drawHeaderY)) {
                UISoundPlayer.play(UISound.DELETE);
                EditorCommands.RemoveComponentCommand cmd = new EditorCommands.RemoveComponentCommand(manager, selected, comp);
                manager.getCommandHistory().executeCommand(cmd);
                return;
            }
            // Section header bar: collapse / expand the card
            if (InspectorPanel.isRowVisible(drawHeaderY) && InspectorPanel.isOverSectionHeader(ndcX, ndcY, drawHeaderY)) {
                UISoundPlayer.play(UISound.CLICK);
                comp.isInspectorCollapsed = !comp.isInspectorCollapsed;
                return;
            }
            curY -= InspectorPanel.HEADER_ADVANCE;
            if (comp.isInspectorCollapsed) {
                curY -= InspectorPanel.COMPONENT_GAP;
                continue;
            }

            for (ComponentSchema.Property p : ComponentSchema.of(comp.getClass()).inspectorProperties()) {
                float drawY = curY + scrollOffset;
                curY -= InspectorPanel.rowAdvance(p.control);
                if (!InspectorPanel.isRowVisible(drawY) || ndcY < drawY - InspectorPanel.CTRL_BELOW || ndcY > drawY + InspectorPanel.CTRL_ABOVE) continue;

                try {
                    if (handlePropertyClick(manager, comp, p, p.get(comp), ndcX)) return;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            // Pillar 3: User Script Reflected Custom Field Interactions
            if (comp instanceof ScriptComponent sc) {
                Class<?> scriptClass = ScriptEngine.resolveScriptClass(sc);
                if (scriptClass != null) {
                    List<Field> userFields = InspectorPanel.getUserScriptFields(scriptClass);
                    for (Field f : userFields) {
                        f.setAccessible(true);
                        String fName = f.getName();
                        InspectorProperty prop = f.getAnnotation(InspectorProperty.class);
                        String label = (prop != null && !prop.label().isEmpty()) ? prop.label() : fName.toUpperCase();

                        float drawY = curY + scrollOffset;
                        boolean isVisible = InspectorPanel.isRowVisible(drawY);
                        boolean inRow = isVisible && ndcY >= drawY - InspectorPanel.CTRL_BELOW && ndcY <= drawY + InspectorPanel.CTRL_ABOVE;

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
                            if (inRow && ndcX >= InspectorPanel.LABEL_X && ndcX <= InspectorPanel.VALUE_X1) {
                                UISoundPlayer.play(UISound.CLICK);
                                boolean currentBool = Boolean.parseBoolean(valStr);
                                boolean newBool = !currentBool;
                                EditorCommands.ScriptPropertyOverrideCommand cmd = new EditorCommands.ScriptPropertyOverrideCommand(sc, fName, valStr, String.valueOf(newBool));
                                manager.getCommandHistory().executeCommand(cmd);
                                return;
                            }
                            curY -= InspectorPanel.ROW_ADVANCE;
                        } else if (fieldType == float.class || fieldType == Float.class || fieldType == double.class || fieldType == Double.class || fieldType == int.class || fieldType == Integer.class) {
                            if (inRow) {
                                float currentVal = 0.0f;
                                try { currentVal = Float.parseFloat(valStr); } catch (Exception ignored) {}
                                float step = (prop != null && prop.step() > 0.0f) ? prop.step() : 0.1f;

                                if (ndcX >= InspectorPanel.DEC_X0 && ndcX < InspectorPanel.DEC_X1) {
                                    UISoundPlayer.play(UISound.CLICK);
                                    float newVal = currentVal - step;
                                    String newStr = (fieldType == int.class || fieldType == Integer.class) ? String.valueOf((int) newVal) : String.valueOf(newVal);
                                    EditorCommands.ScriptPropertyOverrideCommand cmd = new EditorCommands.ScriptPropertyOverrideCommand(sc, fName, valStr, newStr);
                                    manager.getCommandHistory().executeCommand(cmd);
                                    return;
                                }
                                if (ndcX >= InspectorPanel.INC_X0 && ndcX <= InspectorPanel.INC_X1) {
                                    UISoundPlayer.play(UISound.CLICK);
                                    float newVal = currentVal + step;
                                    String newStr = (fieldType == int.class || fieldType == Integer.class) ? String.valueOf((int) newVal) : String.valueOf(newVal);
                                    EditorCommands.ScriptPropertyOverrideCommand cmd = new EditorCommands.ScriptPropertyOverrideCommand(sc, fName, valStr, newStr);
                                    manager.getCommandHistory().executeCommand(cmd);
                                    return;
                                }
                                if (ndcX >= InspectorPanel.VALUE_X0 && ndcX <= InspectorPanel.FIELD_X1) {
                                    beginInlineEdit(sc, fName, null, fieldType, prop, label, valStr, valStr);
                                    return;
                                }
                            }
                            curY -= InspectorPanel.ROW_ADVANCE;
                        } else if (fieldType.isEnum()) {
                            if (inRow && ndcX >= InspectorPanel.VALUE_X0 && ndcX <= InspectorPanel.VALUE_X1) {
                                UISoundPlayer.play(UISound.CLICK);
                                Object[] enumConstants = fieldType.getEnumConstants();
                                if (enumConstants != null && enumConstants.length > 0) {
                                    int currentIdx = 0;
                                    for (int i = 0; i < enumConstants.length; i++) {
                                        if (enumConstants[i].toString().equalsIgnoreCase(valStr)) {
                                            currentIdx = i;
                                            break;
                                        }
                                    }
                                    Object nextEnum = enumConstants[(currentIdx + 1) % enumConstants.length];
                                    EditorCommands.ScriptPropertyOverrideCommand cmd = new EditorCommands.ScriptPropertyOverrideCommand(sc, fName, valStr, nextEnum.toString());
                                    manager.getCommandHistory().executeCommand(cmd);
                                    return;
                                }
                            }
                            curY -= InspectorPanel.ROW_ADVANCE;
                        } else if (fieldType == String.class) {
                            if (inRow && ndcX >= InspectorPanel.VALUE_X0 && ndcX <= InspectorPanel.VALUE_X1) {
                                beginInlineEdit(sc, fName, null, fieldType, prop, label, valStr, valStr);
                                return;
                            }
                            curY -= InspectorPanel.ROW_ADVANCE;
                        } else {
                            curY -= InspectorPanel.TEXT_ROW_ADVANCE;
                        }
                    }
                }
            }

            curY -= InspectorPanel.COMPONENT_GAP;
        }

        // "+ ADD COMPONENT" Button
        float drawAddBtnY = curY + scrollOffset;
        if (drawAddBtnY + InspectorPanel.ADD_ABOVE <= InspectorPanel.VIEW_TOP && drawAddBtnY - InspectorPanel.ADD_BELOW >= InspectorPanel.VIEW_BOTTOM) {
            if (ndcX >= InspectorPanel.ADD_X0 && ndcX <= InspectorPanel.ADD_X1
                    && ndcY >= drawAddBtnY - InspectorPanel.ADD_BELOW && ndcY <= drawAddBtnY + InspectorPanel.ADD_ABOVE) {
                UISoundPlayer.play(UISound.OPEN_MODAL);
                promptAddComponentMenu(manager, selected, ndcX, ndcY);
                return;
            }
        }
    }

    /** Applies a click inside a property row; returns true when the click was consumed. */
    private static boolean handlePropertyClick(EditorWorkspace manager, Component comp, ComponentSchema.Property p, Object val, float ndcX) {
        Field field = p.field;
        InspectorProperty prop = p.meta;
        String label = p.label;

        switch (p.control) {
            case ASSET -> {
                if (ndcX >= InspectorPanel.PICK_X0 && ndcX <= InspectorPanel.PICK_X1) {
                    UISoundPlayer.play(UISound.OPEN_MODAL);
                    AssetType assetType = parseAssetType(prop.assetType());
                    // Script cards bind the picker to their own instance, so each ScriptComponent is assigned independently
                    manager.getOverlayManager().openAssetPicker(assetType, (comp instanceof Material) ? field.getName() : null,
                            (comp instanceof ScriptComponent) ? comp : null);
                    return true;
                }
                if (ndcX >= InspectorPanel.IMPORT_X0 && ndcX <= InspectorPanel.IMPORT_X1) {
                    UISoundPlayer.play(UISound.OPEN_MODAL);
                    if (comp instanceof Material && "TEXTURE".equalsIgnoreCase(prop.assetType())) {
                        promptImportTexture(manager, field.getName());
                    } else if (comp instanceof ScriptComponent sc) {
                        promptImportScript(manager, sc);
                    } else {
                        promptImportAssetForType(manager, prop.assetType());
                    }
                    return true;
                }
            }
            case TOGGLE -> {
                if (ndcX >= InspectorPanel.LABEL_X && ndcX <= InspectorPanel.VALUE_X1) {
                    UISoundPlayer.play(UISound.CLICK);
                    boolean currentBool = (val != null) && (Boolean) val;
                    execute(manager, comp, field, currentBool, !currentBool, label);
                    return true;
                }
            }
            case CHOICE -> {
                Object[] enumConstants = field.getType().getEnumConstants();
                if (ndcX >= InspectorPanel.VALUE_X0 && ndcX <= InspectorPanel.VALUE_X1 && enumConstants != null && enumConstants.length > 0) {
                    UISoundPlayer.play(UISound.CLICK);
                    int currentIdx = Math.max(0, Arrays.asList(enumConstants).indexOf(val));
                    execute(manager, comp, field, val, enumConstants[(currentIdx + 1) % enumConstants.length], label);
                    return true;
                }
            }
            case NUMBER -> {
                float currentVal = (val != null) ? (Float) val : 0.0f;
                float step = prop.step() > 0.0f ? prop.step() : 0.1f;
                float delta = 0.0f;
                if (ndcX >= InspectorPanel.DEC_X0 && ndcX < InspectorPanel.DEC_X1) delta = -step;
                else if (ndcX >= InspectorPanel.INC_X0 && ndcX <= InspectorPanel.INC_X1) delta = step;
                if (delta != 0.0f) {
                    UISoundPlayer.play(UISound.CLICK);
                    float newVal = Math.max(prop.min(), Math.min(prop.max(), currentVal + delta));
                    execute(manager, comp, field, currentVal, newVal, label);
                    return true;
                }
                if (ndcX >= InspectorPanel.VALUE_X0 && ndcX <= InspectorPanel.FIELD_X1) {
                    beginInlineEdit(comp, field.getName(), field, field.getType(), prop, label, val, Float.toString(currentVal));
                    return true;
                }
            }
            case STRING -> {
                if (ndcX >= InspectorPanel.VALUE_X0 && ndcX <= InspectorPanel.VALUE_X1) {
                    beginInlineEdit(comp, field.getName(), field, String.class, prop, label, val, (val != null) ? (String) val : "");
                    return true;
                }
            }
            case READ_ONLY -> {}
        }
        return false;
    }

    // --- Bone Transform rows ---

    // Label scrub drag on a bone row: horizontal motion from the press point changes the value live
    private static final float BONE_SCRUB_STEPS_PER_NDC = 250.0f;
    private static AnimatorComponent boneDragAnimator = null;
    private static Entity boneDragEntity = null;
    private static int boneDragIndex = -1, boneDragRow = -1;
    private static float boneDragStartX, boneDragStartValue;
    private static float[] boneDragStartLocal = null;
    private static boolean boneDragStartActive = false;

    /** Animator whose selected bone the Inspector shows a BONE TRANSFORM section for (the same test the panel draws with). */
    private static AnimatorComponent boneSectionAnimator(EditorWorkspace manager, Entity selected) {
        int boneIndex = manager.getSelectedBoneIndex();
        AnimatorComponent animator = (boneIndex >= 0) ? selected.getComponent(AnimatorComponent.class) : null;
        return (animator != null && animator.getLocalPose(boneIndex) != null) ? animator : null;
    }

    /** Clicks inside the BONE TRANSFORM section starting at {@code drawHeaderY}; returns true when consumed. */
    private static boolean processBoneRowClick(float ndcX, float ndcY, float drawHeaderY, EditorWorkspace manager,
                                               Entity selected, AnimatorComponent animator) {
        float rowY = drawHeaderY - InspectorPanel.HEADER_ADVANCE;
        for (int row = 0; row < InspectorPanel.BONE_ROW_COUNT; row++, rowY -= InspectorPanel.ROW_ADVANCE) {
            if (!InspectorPanel.isRowVisible(rowY) || ndcY < rowY - InspectorPanel.CTRL_BELOW || ndcY > rowY + InspectorPanel.CTRL_ABOVE) continue;
            // Edit poses only drive the mesh while EDITING; in play mode the clip owns the bones
            if (!manager.isBoneEditActive()) return true;

            int boneIndex = manager.getSelectedBoneIndex();
            float[] trs = new float[InspectorPanel.BONE_ROW_COUNT];
            if (!InspectorPanel.readBoneTrs(animator, boneIndex, trs)) return true;
            float step = InspectorPanel.boneRowStep(row);

            float delta = 0.0f;
            if (ndcX >= InspectorPanel.DEC_X0 && ndcX < InspectorPanel.DEC_X1) delta = -step;
            else if (ndcX >= InspectorPanel.INC_X0 && ndcX <= InspectorPanel.INC_X1) delta = step;
            if (delta != 0.0f) {
                UISoundPlayer.play(UISound.CLICK);
                commitBoneValue(manager, selected, animator, boneIndex, row, trs[row] + delta);
                return true;
            }
            if (ndcX >= InspectorPanel.VALUE_X0 && ndcX <= InspectorPanel.FIELD_X1) {
                beginInlineEdit(animator, InspectorPanel.boneRowKey(row), null, float.class, null, null, null, Float.toString(trs[row]));
                editBoneRow = row;
                editBoneIndex = boneIndex;
                return true;
            }
            if (ndcX >= InspectorPanel.LABEL_X && ndcX < InspectorPanel.VALUE_X0) {
                float[] local = animator.getLocalPose(boneIndex);
                boneDragAnimator = animator;
                boneDragEntity = selected;
                boneDragIndex = boneIndex;
                boneDragRow = row;
                boneDragStartX = ndcX;
                boneDragStartValue = trs[row];
                boneDragStartLocal = local.clone();
                boneDragStartActive = animator.editPoseActive[boneIndex];
                return true;
            }
            return true;
        }
        return false;
    }

    /** Sets one TRS component of a bone's local pose and re-poses the Viewport mesh (no undo entry). */
    private static boolean applyBoneValue(EditorWorkspace manager, AnimatorComponent animator, int boneIndex, int row, float value) {
        float[] trs = new float[InspectorPanel.BONE_ROW_COUNT];
        if (!Float.isFinite(value) || !InspectorPanel.readBoneTrs(animator, boneIndex, trs)) return false;
        // A zero scale collapses the matrix and loses its rotation, so keep scale strictly positive
        trs[row] = (row >= 6) ? Math.max(0.001f, value) : value;
        float[] local = new float[16];
        InspectorPanel.composeBoneTrs(animator, boneIndex, trs, local);
        animator.setEditPose(boneIndex, local);
        animator.bindPoseApplied = false;
        manager.getSceneManager().refreshEditPose(animator);
        animator.setDirty(true);
        return true;
    }

    /** One undoable bone value edit, keyed into the recorded pose like a gizmo drag release. */
    private static void commitBoneValue(EditorWorkspace manager, Entity entity, AnimatorComponent animator, int boneIndex, int row, float value) {
        float[] oldLocal = animator.getLocalPose(boneIndex).clone();
        boolean oldActive = animator.editPoseActive[boneIndex];
        if (!applyBoneValue(manager, animator, boneIndex, row, value)) return;
        pushBonePose(manager, entity, animator, boneIndex, oldLocal, oldActive);
    }

    private static void pushBonePose(EditorWorkspace manager, Entity entity, AnimatorComponent animator, int boneIndex,
                                     float[] oldLocal, boolean oldActive) {
        float[] newLocal = animator.getLocalPose(boneIndex);
        if (newLocal == null || (Arrays.equals(newLocal, oldLocal) && animator.editPoseActive[boneIndex] == oldActive)) return;
        manager.getCommandHistory().executeCommand(new EditorCommands.BonePoseCommand(manager.getSceneManager(), animator, boneIndex,
                oldLocal, oldActive, newLocal.clone(), animator.editPoseActive[boneIndex]));
        manager.recordBoneKeyframe(entity, boneIndex);
    }

    private static void execute(EditorWorkspace manager, Component comp, Field field, Object oldVal, Object newVal, String label) {
        manager.getCommandHistory().executeCommand(new EditorCommands.ModifyPropertyCommand(comp, field, oldVal, newVal, label));
    }

    // --- Inline text field editing (focus/buffer state lives in InspectorPanel) ---

    /** Component field being edited; null for user script fields, which commit as property overrides. */
    private static Field editField;
    private static Class<?> editType;
    private static InspectorProperty editMeta;
    private static String editLabel;
    private static Object editOldValue;
    /** Bone row (and bone) being typed into, or -1 when the focused field is a component / script field. */
    private static int editBoneRow = -1, editBoneIndex = -1;

    public static boolean isEditingText() {
        return InspectorPanel.isEditing();
    }

    private static void beginInlineEdit(Component comp, String key, Field field, Class<?> type, InspectorProperty meta,
                                        String label, Object oldValue, String initialText) {
        UISoundPlayer.play(UISound.CLICK);
        editField = field;
        editType = type;
        editMeta = meta;
        editLabel = label;
        editOldValue = oldValue;
        InspectorPanel.beginInlineEdit(comp, key, initialText);
    }

    /** Key events while a field is focused: Enter commits, Escape cancels, Backspace deletes. */
    public static void handleKey(int key, int action, EditorWorkspace manager) {
        if (!InspectorPanel.isEditing() || action == GLFW_RELEASE) return;
        switch (key) {
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> commitInlineEdit(manager);
            case GLFW_KEY_ESCAPE -> cancelInlineEdit();
            case GLFW_KEY_BACKSPACE -> InspectorPanel.backspaceInline();
            default -> {}
        }
    }

    /** Typed characters while a field is focused; numeric fields accept digits, '-', and '.' (non-integer only). */
    public static void handleChar(int codepoint) {
        if (!InspectorPanel.isEditing()) return;
        if (editType == String.class) {
            if (codepoint >= 32 && codepoint != 127) InspectorPanel.typeInline(codepoint);
            return;
        }
        boolean isInt = editType == int.class || editType == Integer.class;
        if ((codepoint >= '0' && codepoint <= '9') || codepoint == '-' || (codepoint == '.' && !isInt)) {
            InspectorPanel.typeInline(codepoint);
        }
    }

    public static void cancelInlineEdit() {
        if (!InspectorPanel.isEditing()) return;
        clearInlineEdit();
    }

    /** Parses the buffer to the field type and commits it as an undoable command; invalid input reverts. */
    public static void commitInlineEdit(EditorWorkspace manager) {
        if (!InspectorPanel.isEditing()) return;
        Component comp = InspectorPanel.getActiveComponent();
        String key = InspectorPanel.getActivePropertyKey();
        String text = InspectorPanel.getActiveInputBuffer().toString();
        Field field = editField;
        Class<?> type = editType;
        InspectorProperty meta = editMeta;
        String label = editLabel;
        Object oldValue = editOldValue;
        int boneRow = editBoneRow, boneIndex = editBoneIndex;
        clearInlineEdit();

        // The edited component must still belong to the selection (it may have been removed mid-edit)
        Entity selected = (manager != null) ? manager.getSelectedEntity() : null;
        if (selected == null || !selected.getAllComponents().contains(comp)) return;

        if (boneRow >= 0) {
            // The bone must still be the selected one and the scene still EDITING
            if (!(comp instanceof AnimatorComponent animator) || manager.getSelectedBoneIndex() != boneIndex || !manager.isBoneEditActive()) return;
            Object parsed = parseInlineValue(text, float.class, null, false);
            float[] trs = new float[InspectorPanel.BONE_ROW_COUNT];
            if (parsed == null || !InspectorPanel.readBoneTrs(animator, boneIndex, trs) || (Float) parsed == trs[boneRow]) return;
            commitBoneValue(manager, selected, animator, boneIndex, boneRow, (Float) parsed);
        } else if (field == null && comp instanceof ScriptComponent sc) {
            // Script fields: clamp only when the annotation declares a range other than its 0..100 default
            boolean clamp = meta != null && (meta.min() != 0.0f || meta.max() != 100.0f);
            Object parsed = parseInlineValue(text, type, meta, clamp);
            String oldStr = (String) oldValue;
            if (parsed == null || parsed.toString().equals(oldStr)) return;
            manager.getCommandHistory().executeCommand(new EditorCommands.ScriptPropertyOverrideCommand(sc, key, oldStr, parsed.toString()));
        } else if (field != null) {
            Object parsed = parseInlineValue(text, type, meta, true);
            if (parsed == null || parsed.equals(oldValue)) return;
            execute(manager, comp, field, oldValue, parsed, label);
        }
    }

    private static void clearInlineEdit() {
        editField = null;
        editType = null;
        editMeta = null;
        editLabel = null;
        editOldValue = null;
        editBoneRow = -1;
        editBoneIndex = -1;
        InspectorPanel.endInlineEdit();
    }

    /** Returns the boxed value for {@code type}, clamped to meta's [min, max] when requested, or null if unparseable. */
    private static Object parseInlineValue(String text, Class<?> type, InspectorProperty meta, boolean clamp) {
        if (type == String.class) return text;
        clamp &= meta != null;
        try {
            String s = text.trim();
            if (type == int.class || type == Integer.class) {
                int v = Integer.parseInt(s);
                return clamp ? Math.max((int) Math.ceil(meta.min()), Math.min((int) Math.floor(meta.max()), v)) : v;
            }
            double v = Double.parseDouble(s);
            if (!Double.isFinite(v)) return null;
            if (clamp) v = Math.max(meta.min(), Math.min(meta.max(), v));
            if (type == double.class || type == Double.class) return v;
            return (float) v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static AssetType parseAssetType(String typeStr) {
        if (typeStr == null || typeStr.isEmpty()) return AssetType.TEXTURE;
        try {
            return AssetType.valueOf(typeStr.toUpperCase());
        } catch (Exception e) {
            if (typeStr.equalsIgnoreCase("MESH") || typeStr.equalsIgnoreCase("MESH_3D")) return AssetType.MESH_3D;
            if (typeStr.equalsIgnoreCase("AUDIO")) return AssetType.AUDIO;
            if (typeStr.equalsIgnoreCase("SCRIPT")) return AssetType.SCRIPT;
            return AssetType.TEXTURE;
        }
    }

    private static void promptImportAssetForType(EditorWorkspace manager, String typeStr) {
        if (typeStr == null) typeStr = "";
        String upper = typeStr.toUpperCase();
        if (upper.contains("MESH")) {
            promptImportMesh(manager);
        } else if (upper.contains("AUDIO")) {
            promptImportAudio(manager);
        } else if (upper.contains("SCRIPT")) {
            promptImportScript(manager);
        } else {
            promptImportTexture(manager);
        }
    }

    public static void processInspectorDrag(float ndcX, float ndcY, EditorWorkspace manager) {
        if (boneDragAnimator != null && manager != null) {
            if (manager.getSelectedBoneIndex() != boneDragIndex || !manager.isBoneEditActive()) return;
            float value = boneDragStartValue + (ndcX - boneDragStartX) * BONE_SCRUB_STEPS_PER_NDC * InspectorPanel.boneRowStep(boneDragRow);
            applyBoneValue(manager, boneDragAnimator, boneDragIndex, boneDragRow, value);
            return;
        }
        if (!isDraggingSlider || draggingComponent == null || draggingFieldName == null) return;
    }

    public static void onMouseRelease(EditorWorkspace manager) {
        isDraggingSlider = false;
        draggingFieldName = null;
        draggingComponent = null;

        // A finished bone scrub becomes one undoable pose edit (and key), like a gizmo drag
        if (boneDragAnimator != null && manager != null) {
            pushBonePose(manager, boneDragEntity, boneDragAnimator, boneDragIndex, boneDragStartLocal, boneDragStartActive);
        }
        boneDragAnimator = null;
        boneDragEntity = null;
        boneDragIndex = -1;
        boneDragRow = -1;
        boneDragStartLocal = null;
    }

    public static void promptImportAssetForDirectory(EditorWorkspace manager) {
        if (manager == null) return;
        List<String> importedPaths = NativeFileDialog.openMultipleFileDialogs(
            "Import Workspace Asset(s)", "", "All Supported Assets (*.png, *.jpg, *.obj, *.fbx, *.wav, *.mp3, *.java)",
            "png", "jpg", "jpeg", "obj", "fbx", "gltf", "glb", "wav", "mp3", "java"
        );
        if (importedPaths != null && !importedPaths.isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
            manager.getAssetDatabase().onFilesDropped(importedPaths);
        } else {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
        }
    }

    public static void promptImportAudio(EditorWorkspace manager) {
        if (manager == null) return;
        List<String> importedPaths = NativeFileDialog.openMultipleFileDialogs(
            "Select Audio Asset(s)", "", "Audio Files (*.wav, *.mp3, *.ogg)", "wav", "mp3", "ogg"
        );
        if (importedPaths != null && !importedPaths.isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
            // Files outside the project are copied into the current Assets/ folder; bind the in-project copy
            importedPaths = manager.getAssetDatabase().onFilesDropped(importedPaths);
            if (importedPaths.isEmpty()) return;
            String primaryPath = importedPaths.get(0);
            Entity selected = manager.getSelectedEntity();
            if (selected != null) {
                AudioSource ac = selected.getComponent(AudioSource.class);
                if (ac == null) {
                    ac = new AudioSource(primaryPath);
                    selected.addComponent(ac);
                } else {
                    try {
                        Field field = AudioSource.class.getDeclaredField("audioAssetPath");
                        EditorCommands.ModifyPropertyCommand cmd = new EditorCommands.ModifyPropertyCommand(ac, field, ac.audioAssetPath, primaryPath, "AUDIO");
                        manager.getCommandHistory().executeCommand(cmd);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                InspectorPanel.resetScroll();
            }
        } else {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
        }
    }

    public static void promptImportScript(EditorWorkspace manager) {
        promptImportScript(manager, null);
    }

    /** Imports a script and assigns it to {@code target}, or to the entity's first ScriptComponent when target is null. */
    public static void promptImportScript(EditorWorkspace manager, ScriptComponent target) {
        if (manager == null) return;
        List<String> importedPaths = NativeFileDialog.openMultipleFileDialogs(
            "Select Script Asset(s)", "", "Java Scripts (*.java)", "java"
        );
        if (importedPaths != null && !importedPaths.isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
            // Files outside the project are copied into the current Assets/ folder; bind the in-project copy
            importedPaths = manager.getAssetDatabase().onFilesDropped(importedPaths);
            if (importedPaths.isEmpty()) return;
            String primaryPath = importedPaths.get(0);
            File file = new File(primaryPath);
            Entity selected = manager.getSelectedEntity();
            if (selected != null) {
                EditorCommands.ScriptAssignCommand cmd;
                if (target != null && selected.getComponents(ScriptComponent.class).contains(target)) {
                    cmd = new EditorCommands.ScriptAssignCommand(selected, target, primaryPath, file.getName());
                } else {
                    ScriptComponent sc = selected.getComponent(ScriptComponent.class);
                    String oldPath = (sc != null) ? sc.scriptAssetPath : null;
                    String oldName = (sc != null) ? sc.scriptName : null;
                    cmd = new EditorCommands.ScriptAssignCommand(selected, oldPath, oldName, primaryPath, file.getName());
                }
                manager.getCommandHistory().executeCommand(cmd);
                InspectorPanel.resetScroll();
            }
        } else {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
        }
    }

    public static void promptImportTexture(EditorWorkspace manager) {
        promptImportTexture(manager, "textureAssetPath");
    }

    public static void promptImportTexture(EditorWorkspace manager, String materialFieldName) {
        if (manager == null) return;
        List<String> importedPaths = NativeFileDialog.openMultipleFileDialogs(
            "Select Texture Asset(s)", "", "Image Files (*.png, *.jpg, *.jpeg, *.tga)", "png", "jpg", "jpeg", "tga"
        );
        if (importedPaths != null && !importedPaths.isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
            // Files outside the project are copied into the current Assets/ folder; bind the in-project copy
            importedPaths = manager.getAssetDatabase().onFilesDropped(importedPaths);
            if (importedPaths.isEmpty()) return;
            String primaryPath = importedPaths.get(0);
            Entity selected = manager.getSelectedEntity();
            if (selected != null) {
                Material mat = selected.getComponent(Material.class);
                if (mat != null) {
                    try {
                        Field field = Material.class.getDeclaredField(materialFieldName);
                        EditorCommands.ModifyPropertyCommand cmd = new EditorCommands.ModifyPropertyCommand(mat, field, field.get(mat), primaryPath, "TEXTURE");
                        manager.getCommandHistory().executeCommand(cmd);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        } else {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
        }
    }

    public static void promptImportMesh(EditorWorkspace manager) {
        if (manager == null) return;
        List<String> importedPaths = NativeFileDialog.openMultipleFileDialogs(
            "Select 3D Mesh Asset(s)", "", "3D Models (*.obj, *.fbx, *.gltf, *.glb)", "obj", "fbx", "gltf", "glb"
        );
        if (importedPaths != null && !importedPaths.isEmpty()) {
            UISoundPlayer.play(UISound.SPAWN);
            // Files outside the project are copied into the current Assets/ folder; bind the in-project copy
            importedPaths = manager.getAssetDatabase().onFilesDropped(importedPaths);
            if (importedPaths.isEmpty()) return;
            String primaryPath = importedPaths.get(0);
            Entity selected = manager.getSelectedEntity();
            if (selected != null) {
                MeshFilter mc = selected.getComponent(MeshFilter.class);
                if (mc != null) {
                    try {
                        Field field = MeshFilter.class.getDeclaredField("meshAssetPath");
                        EditorCommands.ModifyPropertyCommand cmd = new EditorCommands.ModifyPropertyCommand(mc, field, mc.meshAssetPath, primaryPath, "MESH_3D");
                        manager.getCommandHistory().executeCommand(cmd);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    EntitySpawner.attachAnimatorIfRigged(selected, primaryPath);
                }
            }
        } else {
            UISoundPlayer.play(UISound.CLOSE_MODAL);
        }
    }

    public static void promptAddComponentMenu(EditorWorkspace manager, Entity entity) {
        promptAddComponentMenu(manager, entity, 0.68f, 0.20f);
    }

    public static void promptAddComponentMenu(EditorWorkspace manager, Entity entity, float ndcX, float ndcY) {
        if (entity == null || manager == null) return;
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem("+ RigidBody", () -> {
            if (entity.getComponent(RigidBodyComponent.class) == null) {
                entity.addComponent(new RigidBodyComponent());
                UISoundPlayer.play(UISound.SPAWN);
            }
        }));
        items.add(new MenuItem("+ Box Collider", () -> {
            if (entity.getComponent(BoxCollider.class) == null) {
                entity.addComponent(new BoxCollider());
                UISoundPlayer.play(UISound.SPAWN);
            }
        }));
        items.add(new MenuItem("+ Sphere Collider", () -> {
            if (entity.getComponent(SphereCollider.class) == null) {
                entity.addComponent(new SphereCollider());
                UISoundPlayer.play(UISound.SPAWN);
            }
        }));
        items.add(new MenuItem("+ Capsule Collider", () -> {
            if (entity.getComponent(CapsuleCollider.class) == null) {
                entity.addComponent(new CapsuleCollider());
                UISoundPlayer.play(UISound.SPAWN);
            }
        }));
        items.add(new MenuItem("+ Light Source", () -> {
            if (entity.getComponent(Light.class) == null) {
                entity.addComponent(new Light());
                UISoundPlayer.play(UISound.SPAWN);
            }
        }));
        items.add(new MenuItem("+ Audio Source", () -> {
            if (entity.getComponent(AudioSource.class) == null) {
                entity.addComponent(new AudioSource());
                UISoundPlayer.play(UISound.SPAWN);
            }
        }));
        // Not limited to one per entity: an unbound SCRIPT picker attaches an additional ScriptComponent
        items.add(new MenuItem("+ Script Component", () -> {
            manager.getOverlayManager().openAssetPicker(AssetType.SCRIPT, null, null);
        }));
        items.add(new MenuItem("+ Character Controller", () -> {
            if (entity.getComponent(CharacterControllerComponent.class) == null) {
                entity.addComponent(new CharacterControllerComponent());
                UISoundPlayer.play(UISound.SPAWN);
            }
        }));
        items.add(new MenuItem("+ Vehicle Controller", () -> {
            if (entity.getComponent(VehicleControllerComponent.class) == null) {
                entity.addComponent(new VehicleControllerComponent());
                UISoundPlayer.play(UISound.SPAWN);
            }
        }));

        addUiComponentItem(items, entity, "+ UI Canvas", UICanvasComponent.class, UICanvasComponent::new);
        addUiComponentItem(items, entity, "+ UI Panel", UIPanel.class, UIPanel::new);
        addUiComponentItem(items, entity, "+ UI Text", UIText.class, UIText::new);
        addUiComponentItem(items, entity, "+ UI Image", UIImage.class, UIImage::new);
        addUiComponentItem(items, entity, "+ UI Progress Bar", UIProgressBar.class, UIProgressBar::new);

        manager.getOverlayManager().openContextMenu(ndcX, ndcY, items);
    }

    private static <T extends Component> void addUiComponentItem(List<MenuItem> items, Entity entity, String label,
                                                                 Class<T> type, java.util.function.Supplier<T> factory) {
        items.add(new MenuItem(label, () -> {
            if (entity.getComponent(type) == null) {
                entity.addComponent(factory.get());
                UISoundPlayer.play(UISound.SPAWN);
            }
        }));
    }
}
