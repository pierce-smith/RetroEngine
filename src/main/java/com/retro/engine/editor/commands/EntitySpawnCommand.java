package com.retro.engine.editor.commands;

import com.retro.engine.scene.Entity;
import com.retro.engine.editor.EditorWorkspace;

/**
 * Encapsulates Entity spawning edits for Undo/Redo.
 */
public class EntitySpawnCommand implements ICommand {
    private final EditorWorkspace manager;
    private final Entity entity;

    public EntitySpawnCommand(EditorWorkspace manager, Entity entity) {
        this.manager = manager;
        this.entity = entity;
    }

    @Override
    public void execute() {
        if (manager == null || entity == null) return;
        manager.addEntity(entity, -1);
    }

    @Override
    public void undo() {
        if (manager == null || entity == null) return;
        manager.deleteEntity(entity);
    }

    @Override
    public String getName() {
        return "Spawn Entity '" + (entity != null ? entity.name : "null") + "'";
    }
}
