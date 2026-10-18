package com.retro.engine.editor.commands;

import com.retro.engine.scene.Entity;
import com.retro.engine.editor.EditorWorkspace;

import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates Entity deletion edits for Undo/Redo.
 * Preserves child-parent relationships across deletion and restoration cycles.
 */
public class EntityDeleteCommand implements ICommand {
    private final EditorWorkspace manager;
    private final Entity entity;
    private final int originalIndex;
    private final List<Entity> childEntities = new ArrayList<>();

    public EntityDeleteCommand(EditorWorkspace manager, Entity entity) {
        this.manager = manager;
        this.entity = entity;
        this.originalIndex = (manager != null && entity != null && manager.getSceneEntities() != null)
                ? manager.getSceneEntities().indexOf(entity) : -1;

        if (manager != null && entity != null && manager.getSceneEntities() != null) {
            for (Entity e : manager.getSceneEntities()) {
                if (e != null && entity.id != null && entity.id.equalsIgnoreCase(e.parentId)) {
                    childEntities.add(e);
                }
            }
        }
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

        // Restore parent-child hierarchy linkages for all child entities
        for (Entity child : childEntities) {
            if (child != null) {
                child.parentId = entity.id;
                entity.addChildId(child.id);
            }
        }
    }

    @Override
    public String getName() {
        return "Delete Entity '" + (entity != null ? entity.name : "null") + "'";
    }
}
