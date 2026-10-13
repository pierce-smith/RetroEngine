package com.retro.engine.editor.commands;

import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.ecs.components.TransformComponent;

/**
 * Encapsulates 3D transform edits (translation, rotation, scale) for Undo/Redo.
 */
public class TransformCommand implements EditorCommand {
    private final SceneEntity entity;
    private final float oldPx, oldPy, oldPz;
    private final float oldRx, oldRy, oldRz;
    private final float oldSx, oldSy, oldSz;

    private final float newPx, newPy, newPz;
    private final float newRx, newRy, newRz;
    private final float newSx, newSy, newSz;

    public TransformCommand(SceneEntity entity,
                            float oldPx, float oldPy, float oldPz,
                            float oldRx, float oldRy, float oldRz,
                            float oldSx, float oldSy, float oldSz,
                            float newPx, float newPy, float newPz,
                            float newRx, float newRy, float newRz,
                            float newSx, float newSy, float newSz) {
        this.entity = entity;
        this.oldPx = oldPx; this.oldPy = oldPy; this.oldPz = oldPz;
        this.oldRx = oldRx; this.oldRy = oldRy; this.oldRz = oldRz;
        this.oldSx = oldSx; this.oldSy = oldSy; this.oldSz = oldSz;

        this.newPx = newPx; this.newPy = newPy; this.newPz = newPz;
        this.newRx = newRx; this.newRy = newRy; this.newRz = newRz;
        this.newSx = newSx; this.newSy = newSy; this.newSz = newSz;
    }

    @Override
    public void execute() {
        if (entity == null) return;
        TransformComponent tc = entity.getTransform();
        tc.posX = newPx; tc.posY = newPy; tc.posZ = newPz;
        tc.rotX = newRx; tc.rotY = newRy; tc.rotZ = newRz;
        tc.scaleX = newSx; tc.scaleY = newSy; tc.scaleZ = newSz;
    }

    @Override
    public void undo() {
        if (entity == null) return;
        TransformComponent tc = entity.getTransform();
        tc.posX = oldPx; tc.posY = oldPy; tc.posZ = oldPz;
        tc.rotX = oldRx; tc.rotY = oldRy; tc.rotZ = oldRz;
        tc.scaleX = oldSx; tc.scaleY = oldSy; tc.scaleZ = oldSz;
    }

    @Override
    public String getName() {
        return "Transform Entity '" + (entity != null ? entity.name : "null") + "'";
    }
}
