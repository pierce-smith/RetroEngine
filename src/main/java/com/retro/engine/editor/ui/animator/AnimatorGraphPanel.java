package com.retro.engine.editor.ui.animator;

import com.retro.engine.animation.state.AnimatorController;
import com.retro.engine.animation.state.AnimatorTransition;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ui.batch.UIGeometryBatch;
import com.retro.engine.scene.components.media.AnimatorComponent;

/**
 * AnimatorGraphPanel Facade: Orchestrates rendering and event dispatching across
 * specialized sub-delegates:
 * - AnimatorCanvasDelegate: Graph Canvas & State Nodes
 * - AnimatorParametersDelegate: Parameter list & inline value editors
 * - AnimatorInspectorDelegate: State Inspector & Dope Sheet timeline
 */
public class AnimatorGraphPanel {

    public static class NodePos {
        public float x;
        public float y;
        public float w;
        public float h;

        public NodePos(float x, float y, float w, float h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    /** {@code animator}: the instance whose live playback and parameter values are shown (null for none). */
    public static void render(UIGeometryBatch.Buffer buffer, AnimatorController controller, AnimatorComponent animator,
                              String selectedStateName, AnimatorTransition selectedTransition) {
        // 1. Central Canvas & State Nodes
        AnimatorCanvasDelegate.renderCanvas(buffer, controller, animator, selectedStateName);

        // 2. Parameters Sidebar
        AnimatorParametersDelegate.renderParametersSidebar(buffer, controller, animator);

        // 3. Inspector Sidebar
        AnimatorInspectorDelegate.renderInspectorSidebar(buffer, controller, animator, selectedStateName, selectedTransition, null);
    }

    // --- Per-section entry points: each section is an independently dockable panel ---

    public static void renderCanvas(UIGeometryBatch.Buffer buffer, AnimatorController controller, AnimatorComponent animator, String selectedStateName) {
        AnimatorCanvasDelegate.renderCanvas(buffer, controller, animator, selectedStateName);
    }

    public static void renderParameters(UIGeometryBatch.Buffer buffer, AnimatorController controller, AnimatorComponent animator) {
        AnimatorParametersDelegate.renderParametersSidebar(buffer, controller, animator);
    }

    /** {@code recorder}: the animator whose recorded clip the dope sheet edits as a record timeline (null when not EDITING). */
    public static void renderInspector(UIGeometryBatch.Buffer buffer, AnimatorController controller, AnimatorComponent animator, String selectedStateName,
                                       AnimatorTransition selectedTransition, AnimatorComponent recorder) {
        AnimatorInspectorDelegate.renderInspectorSidebar(buffer, controller, animator, selectedStateName, selectedTransition, recorder);
    }

    public static boolean handleCanvasClick(float ndcX, float ndcY, int button, int action, EditorWorkspace manager) {
        return AnimatorCanvasDelegate.handleCanvasClick(ndcX, ndcY, button, action, manager);
    }

    public static boolean handleParametersClick(float ndcX, float ndcY, int button, int action, EditorWorkspace manager) {
        if (manager == null || button != 0 || action != 1) return false;
        return AnimatorParametersDelegate.handleParametersClick(ndcX, ndcY, manager);
    }

    public static boolean handleMouseClick(float ndcX, float ndcY, int button, int action, EditorWorkspace manager) {
        if (manager == null) return false;

        if (button == 0 && action == 1) {
            // 1. Parameters list (left press only; it has no button discrimination of its own)
            if (AnimatorParametersDelegate.handleParametersClick(ndcX, ndcY, manager)) {
                return true;
            }
            // 2. Dope sheet playhead scrub
            if (AnimatorInspectorDelegate.handleInspectorClick(ndcX, ndcY, manager)) {
                return true;
            }
        }

        // 3. Route to Graph Canvas
        return AnimatorCanvasDelegate.handleCanvasClick(ndcX, ndcY, button, action, manager);
    }

    public static boolean handleCursorPos(float ndcX, float ndcY, EditorWorkspace manager) {
        return AnimatorCanvasDelegate.handleCursorPos(ndcX, ndcY, manager);
    }

    /** Cursor in the inspector panel's design space; drives an active playhead scrub. */
    public static boolean handleInspectorCursorPos(float ndcX, float ndcY, EditorWorkspace manager) {
        return AnimatorInspectorDelegate.handleCursorPos(ndcX, ndcY, manager);
    }

    /** Ends a playhead scrub wherever the button is released. */
    public static boolean handleInspectorRelease() {
        return AnimatorInspectorDelegate.handleRelease();
    }

    public static boolean handleCanvasScroll(float ndcX, float ndcY, double yOffset) {
        return AnimatorCanvasDelegate.handleScroll(ndcX, ndcY, yOffset);
    }

    public static boolean handleInspectorScroll(float ndcX, float ndcY, double yOffset) {
        return AnimatorInspectorDelegate.handleScroll(ndcX, ndcY, yOffset);
    }
}
