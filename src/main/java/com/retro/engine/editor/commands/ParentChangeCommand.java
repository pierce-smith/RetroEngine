package com.retro.engine.editor.commands;

import com.retro.engine.scene.Entity;
import com.retro.engine.editor.EditorWorkspace;

/**
 * Encapsulates Entity hierarchy re-parenting edits for Undo/Redo.
 * Directly invokes SceneWorld's setParent to avoid recursive command stack re-entry.
 */
public class ParentChangeCommand implements ICommand {
    private final EditorWorkspace manager;
    private final Entity child;
    private final Entity oldParent;
    private final Entity newParent;

    public ParentChangeCommand(EditorWorkspace manager, Entity child, Entity oldParent, Entity newParent) {
        this.manager = manager;
        this.child = child;
        this.oldParent = oldParent;
        this.newParent = newParent;
    }

    @Override
    public void execute() {
        if (manager == null || child == null || manager.getSceneManager() == null) return;
        manager.getSceneManager().setParent(child, newParent);
    }

    @Override
    public void undo() {
        if (manager == null || child == null || manager.getSceneManager() == null) return;
        manager.getSceneManager().setParent(child, oldParent);
    }

    @Override
    public String getName() {
        return "Reparent Entity '" + (child != null ? child.name : "null") + "'";
    }
}
