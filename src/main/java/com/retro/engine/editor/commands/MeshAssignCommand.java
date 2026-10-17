package com.retro.engine.editor.commands;

import com.retro.engine.scene.Entity;

/**
 * Encapsulates MeshFilter mesh asset assignment edits for Undo/Redo.
 */
public class MeshAssignCommand implements ICommand {
    private final Entity entity;
    private final String oldPath;
    private final String newPath;

    public MeshAssignCommand(Entity entity, String oldPath, String newPath) {
        this.entity = entity;
        this.oldPath = oldPath;
        this.newPath = newPath;
    }

    @Override
    public void execute() {
        if (entity == null) return;
        entity.setMeshAssetPath(newPath);
    }

    @Override
    public void undo() {
        if (entity == null) return;
        entity.setMeshAssetPath(oldPath);
    }

    @Override
    public String getName() {
        return "Assign Mesh to '" + (entity != null ? entity.name : "null") + "'";
    }
}
