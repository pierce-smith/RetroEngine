package com.retro.engine.editor.commands;

import com.retro.engine.scene.Entity;
import com.retro.engine.editor.EditorWorkspace;

/**
 * Encapsulates Entity deletion edits for Undo/Redo.
 */
public class EntityDeleteCommand implements ICommand {
    private final EditorWorkspace manager;
    private final Entity entity;
    private final int originalIndex;

    public EntityDeleteCommand(EditorWorkspace manager, Entity entity) {
        this.manager = manager;
        this.entity = entity;
        this.originalIndex = (manager != null && entity != null) ? manager.getSceneEntities().indexOf(entity) : -1;
    }

    @Override
    public void execute() {
        if (manager == null || entity == null) return;
        manager.deleteEntity(entity);
    }

    @Override
    public void undo() {
        if (manager == null || entity == null) return;
        manager.addEntity(entity, originalIndex);
    }

    @Override
    public String getName() {
        return "Delete Entity '" + (entity != null ? entity.name : "null") + "'";
    }
}
