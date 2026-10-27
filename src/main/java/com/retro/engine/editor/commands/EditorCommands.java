package com.retro.engine.editor.commands;

import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.components.*;
import com.retro.engine.scene.components.gameplay.ScriptComponent;
import com.retro.engine.scene.components.transform.Transform;
import com.retro.engine.scripting.ScriptEngine;

import java.lang.reflect.Field;

/**
 * Unified container for all editor transactional undo/redo commands.
 */
public class EditorCommands {

    public static class EntitySpawnCommand implements ICommand {
        private final EditorWorkspace workspace;
        private final Entity entity;

        public EntitySpawnCommand(EditorWorkspace workspace, Entity entity) {
            this.workspace = workspace;
            this.entity = entity;
        }

        @Override
        public void execute() {
            if (workspace != null && entity != null) {
                workspace.getSceneManager().addEntity(entity, -1);
            }
        }

        @Override
        public void undo() {
            if (workspace != null && entity != null) {
                workspace.getSceneManager().deleteEntity(entity);
            }
        }

        @Override
        public String getName() {
            return "Spawn Entity '" + (entity != null ? entity.name : "null") + "'";
        }
    }

    public static class EntityDeleteCommand implements ICommand {
        private final EditorWorkspace workspace;
        private final Entity entity;

        public EntityDeleteCommand(EditorWorkspace workspace, Entity entity) {
            this.workspace = workspace;
            this.entity = entity;
        }

        @Override
        public void execute() {
            if (workspace != null && entity != null) {
                workspace.getSceneManager().deleteEntity(entity);
            }
        }

        @Override
        public void undo() {
            if (workspace != null && entity != null) {
                workspace.getSceneManager().addEntity(entity, -1);
            }
        }

        @Override
        public String getName() {
            return "Delete Entity '" + (entity != null ? entity.name : "null") + "'";
        }
    }

    public static class EntityRenameCommand implements ICommand {
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
            if (entity != null) {
                entity.name = newName;
            }
        }

        @Override
        public void undo() {
            if (entity != null) {
                entity.name = oldName;
            }
        }

        @Override
        public String getName() {
            return "Rename Entity to '" + newName + "'";
        }
    }

    public static class ParentChangeCommand implements ICommand {
        private final EditorWorkspace workspace;
        private final Entity child;
        private final Entity oldParent;
        private final Entity newParent;

        public ParentChangeCommand(EditorWorkspace workspace, Entity child, Entity oldParent, Entity newParent) {
            this.workspace = workspace;
            this.child = child;
            this.oldParent = oldParent;
            this.newParent = newParent;
        }

        @Override
        public void execute() {
            if (workspace != null && child != null) {
                workspace.getSceneManager().setParent(child, newParent);
            }
        }

        @Override
        public void undo() {
            if (workspace != null && child != null) {
                workspace.getSceneManager().setParent(child, oldParent);
            }
        }

        @Override
        public String getName() {
            return "Reparent Entity '" + (child != null ? child.name : "null") + "'";
        }
    }

    public static class TransformCommand implements ICommand {
        private final Transform transform;
        private final float oldPx, oldPy, oldPz, oldRx, oldRy, oldRz, oldSx, oldSy, oldSz;
        private final float newPx, newPy, newPz, newRx, newRy, newRz, newSx, newSy, newSz;

        public TransformCommand(Transform transform,
                                float oldPx, float oldPy, float oldPz, float oldRx, float oldRy, float oldRz, float oldSx, float oldSy, float oldSz,
                                float newPx, float newPy, float newPz, float newRx, float newRy, float newRz, float newSx, float newSy, float newSz) {
            this.transform = transform;
            this.oldPx = oldPx; this.oldPy = oldPy; this.oldPz = oldPz;
            this.oldRx = oldRx; this.oldRy = oldRy; this.oldRz = oldRz;
            this.oldSx = oldSx; this.oldSy = oldSy; this.oldSz = oldSz;
            this.newPx = newPx; this.newPy = newPy; this.newPz = newPz;
            this.newRx = newRx; this.newRy = newRy; this.newRz = newRz;
            this.newSx = newSx; this.newSy = newSy; this.newSz = newSz;
        }

        @Override
        public void execute() {
            if (transform == null) return;
            transform.posX = newPx; transform.posY = newPy; transform.posZ = newPz;
            transform.rotX = newRx; transform.rotY = newRy; transform.rotZ = newRz;
            transform.scaleX = newSx; transform.scaleY = newSy; transform.scaleZ = newSz;
            transform.markDirty();
        }

        @Override
        public void undo() {
            if (transform == null) return;
            transform.posX = oldPx; transform.posY = oldPy; transform.posZ = oldPz;
            transform.rotX = oldRx; transform.rotY = oldRy; transform.rotZ = oldRz;
            transform.scaleX = oldSx; transform.scaleY = oldSy; transform.scaleZ = oldSz;
            transform.markDirty();
        }

        @Override
        public String getName() {
            return "Transform Entity";
        }
    }

    public static class ModifyPropertyCommand implements ICommand {
        private final Component component;
        private final Field field;
        private final Object oldValue;
        private final Object newValue;
        private final String propertyLabel;

        public ModifyPropertyCommand(Component component, Field field, Object oldValue, Object newValue, String propertyLabel) {
            this.component = component;
            this.field = field;
            this.oldValue = oldValue;
            this.newValue = newValue;
            this.propertyLabel = propertyLabel;
            if (this.field != null) {
                this.field.setAccessible(true);
            }
        }

        @Override
        public void execute() { setValue(newValue); }

        @Override
        public void undo() { setValue(oldValue); }

        private void setValue(Object val) {
            if (component == null || field == null) return;
            try {
                field.set(component, val);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public String getName() {
            String compName = (component != null) ? component.getComponentName() : "Component";
            return "Modify " + compName + " [" + propertyLabel + "]";
        }
    }

    public static class ScriptAssignCommand implements ICommand {
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

    public static class ScriptPropertyOverrideCommand implements ICommand {
        private final ScriptComponent scriptComponent;
        private final String fieldName;
        private final String oldValue;
        private final String newValue;

        public ScriptPropertyOverrideCommand(ScriptComponent sc, String fieldName, String oldValue, String newValue) {
            this.scriptComponent = sc;
            this.fieldName = fieldName;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }

        @Override
        public void execute() {
            ScriptEngine.applyPropertyOverride(scriptComponent, fieldName, newValue);
        }

        @Override
        public void undo() {
            ScriptEngine.applyPropertyOverride(scriptComponent, fieldName, oldValue);
        }

        @Override
        public String getName() {
            return "Modify Script Property [" + fieldName + "]";
        }
    }
}
