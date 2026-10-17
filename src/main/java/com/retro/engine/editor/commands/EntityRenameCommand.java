package com.retro.engine.editor.commands;

import com.retro.engine.scene.Entity;

/**
 * Encapsulates Entity renaming edits for Undo/Redo.
 */
public class EntityRenameCommand implements ICommand {
    private final Entity entity;
    private final String oldName;
    private final String newName;

    public EntityRenameCommand(Entity entity, String oldName, String newName) {
        this.entity = entity;
        this.oldName = oldName;
        this.newName = newName;
    }

    @Override
    public void execute() {
        if (entity == null) return;
        entity.name = newName;
    }

    @Override
    public void undo() {
        if (entity == null) return;
        entity.name = oldName;
    }

    @Override
    public String getName() {
        return "Rename Entity '" + oldName + "' to '" + newName + "'";
    }
}
