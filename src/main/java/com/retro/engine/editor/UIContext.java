package com.retro.engine.editor;

import com.retro.engine.editor.panels.DockSpace;
import com.retro.engine.ui.UIGeometryBatch;

/**
 * Per-editor UI state handed to panel render methods: the vertex buffer the frame's UI is packed into (with its
 * window metrics) and the dock layout that maps each panel onto its tile. Owned by EditorWorkspace; confined to the
 * thread that builds the editor UI.
 */
public final class UIContext {

    private final UIGeometryBatch.Buffer geometry;
    private final DockSpace dock;

    public UIContext(int initialFloatCapacity) {
        this.geometry = new UIGeometryBatch.Buffer(initialFloatCapacity);
        this.dock = new DockSpace(geometry);
    }

    public UIGeometryBatch.Buffer geometry() { return geometry; }

    public DockSpace dock() { return dock; }

    /** Clears the buffer and applies this frame's window size to text and corner metrics. */
    public void beginFrame(int windowWidth, int windowHeight) {
        geometry.reset();
        geometry.setWindowSize(windowWidth, windowHeight);
    }
}
