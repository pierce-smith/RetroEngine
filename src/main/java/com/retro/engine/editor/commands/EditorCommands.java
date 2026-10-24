package com.retro.engine.editor.commands;

import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.scene.entity.Entity;
import com.retro.engine.scene.components.*;
import com.retro.engine.scene.components.gameplay.ScriptComponent;
import com.retro.engine.scene.components.transform.Transform;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Unified container for all editor transactional undo/redo commands.
 * Consolidates individual single-method command implementations into
 * static inner modules for zero-allocation transaction dispatching and
 * clean project source layout.
 */
public class EditorCommands {

    /**
     * Encapsulates Entity renaming edits for Undo/Redo.
     */
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

    /**
     * Encapsulates Entity deletion edits for Undo/Redo.
     * Preserves child-parent relationships across deletion and restoration cycles.
     */
    public static class EntityDeleteCommand implements ICommand {
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
            manager.getSceneManager().deleteEntity(entity);
        }

        @Override
        public void undo() {
            if (manager == null || entity == null) return;
            manager.getSceneManager().addEntity(entity, originalIndex);

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

    /**
     * Encapsulates Entity spawning edits for Undo/Redo.
     */
    public static class EntitySpawnCommand implements ICommand {
        private final EditorWorkspace manager;
        private final Entity entity;

        public EntitySpawnCommand(EditorWorkspace manager, Entity entity) {
            this.manager = manager;
            this.entity = entity;
        }

        @Override
        public void execute() {
            if (manager == null || entity == null) return;
            manager.getSceneManager().addEntity(entity, -1);
        }

        @Override
        public void undo() {
            if (manager == null || entity == null) return;
            manager.getSceneManager().deleteEntity(entity);
        }

        @Override
        public String getName() {
            return "Spawn Entity '" + (entity != null ? entity.name : "null") + "'";
        }
    }

    /**
     * Encapsulates Entity hierarchy re-parenting edits for Undo/Redo.
     */
    public static class ParentChangeCommand implements ICommand {
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

    /**
     * Universal reflection-driven ICommand for undo/redo property mutations across all ECS components.
     */
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
        public void execute() {
            setValue(newValue);
        }

        @Override
        public void undo() {
            setValue(oldValue);
        }

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

    /**
     * Encapsulates attaching or changing a ScriptComponent on an Entity for Undo/Redo.
     */
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

    /**
     * Encapsulates Material texture assignment edits for Undo/Redo.
     */
    public static class TextureAssignCommand implements ICommand {
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

    /**
     * Encapsulates MeshFilter mesh asset assignment edits for Undo/Redo.
     */
    public static class MeshAssignCommand implements ICommand {
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

    /**
     * Atomic TransformCommand bundling 9-axis (position, rotation, scale)
     * transform mutations into a single undo/redo transaction.
     */
    public static class TransformCommand implements ICommand {
        private final Transform transform;

        private final float startPx, startPy, startPz;
        private final float startRx, startRy, startRz;
        private final float startSx, startSy, startSz;

        private final float endPx, endPy, endPz;
        private final float endRx, endRy, endRz;
        private final float endSx, endSy, endSz;

        public TransformCommand(Transform transform,
                                float startPx, float startPy, float startPz,
                                float startRx, float startRy, float startRz,
                                float startSx, float startSy, float startSz,
                                float endPx, float endPy, float endPz,
                                float endRx, float endRy, float endRz,
                                float endSx, float endSy, float endSz) {
            this.transform = transform;
            this.startPx = startPx; this.startPy = startPy; this.startPz = startPz;
            this.startRx = startRx; this.startRy = startRy; this.startRz = startRz;
            this.startSx = startSx; this.startSy = startSy; this.startSz = startSz;

            this.endPx = endPx; this.endPy = endPy; this.endPz = endPz;
            this.endRx = endRx; this.endRy = endRy; this.endRz = endRz;
            this.endSx = endSx; this.endSy = endSy; this.endSz = endSz;
        }

        @Override
        public void execute() {
            if (transform == null) return;
            transform.setPosition(endPx, endPy, endPz);
            transform.setRotation(endRx, endRy, endRz);
            transform.setScale(endSx, endSy, endSz);
        }

        @Override
        public void undo() {
            if (transform == null) return;
            transform.setPosition(startPx, startPy, startPz);
            transform.setRotation(startRx, startRy, startRz);
            transform.setScale(startSx, startSy, startSz);
        }

        @Override
        public String getName() {
            return "Transform Delta";
        }
    }
}
