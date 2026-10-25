package com.retro.engine.editor.ui.animator;

import com.retro.engine.animation.state.AnimatorController;
import com.retro.engine.animation.state.AnimatorTransition;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ui.batch.UIGeometryBatch;

/**
 * AnimatorGraphPanel Facade: Orchestrates rendering and event dispatching across
 * specialized sub-delegates:
 * - AnimatorCanvasDelegate: Graph Canvas & State Nodes
 * - AnimatorParametersDelegate: Parameters Sidebar & Controls
 * - AnimatorInspectorDelegate: State & Transition Inspector
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

    public static void render(UIGeometryBatch.Buffer buffer, AnimatorController controller, String selectedStateName, AnimatorTransition selectedTransition) {
        // 1. Central Canvas & State Nodes
        AnimatorCanvasDelegate.renderCanvas(buffer, controller, selectedStateName);

        // 2. Parameters Sidebar
        AnimatorParametersDelegate.renderParametersSidebar(buffer, controller);

        // 3. Inspector Sidebar
        AnimatorInspectorDelegate.renderInspectorSidebar(buffer, controller, selectedStateName, selectedTransition);
    }

    public static boolean handleMouseClick(float ndcX, float ndcY, int button, int action, EditorWorkspace manager) {
        if (manager == null) return false;

        // 1. Route to Parameters Sidebar (left press only; it has no button discrimination of its own)
        if (button == 0 && action == 1 && AnimatorParametersDelegate.handleParametersClick(ndcX, ndcY, manager.getActiveAnimatorController())) {
            return true;
        }

        // 2. Route to Graph Canvas
        return AnimatorCanvasDelegate.handleCanvasClick(ndcX, ndcY, button, action, manager);
    }

    public static boolean handleCursorPos(float ndcX, float ndcY, EditorWorkspace manager) {
        return AnimatorCanvasDelegate.handleCursorPos(ndcX, ndcY, manager);
    }
}
