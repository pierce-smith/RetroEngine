package com.retro.engine.editor.commands;

import com.retro.engine.scene.Entity;
import com.retro.engine.scene.components.Material;

/**
 * Encapsulates Material texture assignment edits for Undo/Redo.
 */
public class TextureAssignCommand implements ICommand {
    private final Entity entity;
    private final String oldPath;
    private final String oldName;
    private final String newPath;
    private final String newName;

    public TextureAssignCommand(Entity entity, String oldPath, String oldName, String newPath, String newName) {
        this.entity = entity;
        this.oldPath = oldPath;
        this.oldName = oldName;
        this.newPath = newPath;
        this.newName = newName;
    }

    @Override
    public void execute() {
        if (entity == null) return;
        entity.setTextureAsset(newPath, newName);
    }

    @Override
    public void undo() {
        if (entity == null) return;
        entity.setTextureAsset(oldPath, oldName);
    }

    @Override
    public String getName() {
        return "Assign Texture to '" + (entity != null ? entity.name : "null") + "'";
    }
}
