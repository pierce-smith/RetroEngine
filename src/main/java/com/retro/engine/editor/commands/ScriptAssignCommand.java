package com.retro.engine.editor.commands;

import com.retro.engine.scene.Entity;
import com.retro.engine.scene.components.ScriptComponent;

/**
 * Encapsulates attaching or changing a ScriptComponent on an Entity for Undo/Redo.
 */
public class ScriptAssignCommand implements ICommand {

    private final Entity entity;
    private final String oldPath;
    private final String oldName;
    private final String newPath;
    private final String newName;

    public ScriptAssignCommand(Entity entity, String oldPath, String oldName, String newPath, String newName) {
        this.entity = entity;
        this.oldPath = oldPath;
        this.oldName = oldName;
        this.newPath = newPath;
        this.newName = newName;
    }

    @Override
    public void execute() {
        if (entity == null) return;
        ScriptComponent sc = entity.getComponent(ScriptComponent.class);
        if (sc == null) {
            sc = new ScriptComponent(newPath, newName);
            entity.addComponent(sc);
        } else {
            sc.scriptAssetPath = newPath;
            sc.scriptName = newName;
            sc.activeScriptInstance = null;
        }
    }

    @Override
    public void undo() {
        if (entity == null) return;
        ScriptComponent sc = entity.getComponent(ScriptComponent.class);
        if (sc != null) {
            if (oldPath == null && oldName == null) {
                entity.removeComponent(ScriptComponent.class);
            } else {
                sc.scriptAssetPath = oldPath;
                sc.scriptName = oldName;
                sc.activeScriptInstance = null;
            }
        }
    }

    @Override
    public String getName() {
        return "Assign Script '" + (newName != null ? newName : "None") + "' to Entity '" + (entity != null ? entity.name : "null") + "'";
    }
}
