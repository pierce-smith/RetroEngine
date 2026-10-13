package com.retro.engine.editor.input;

import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.ecs.components.TransformComponent;
import com.retro.engine.editor.commands.CommandHistory;
import com.retro.engine.editor.commands.TransformCommand;

/**
 * TransformUndoTracker manages starting transform state snapshots and
 * commits undoable TransformCommands to CommandHistory upon drag completion.
 */
public class TransformUndoTracker {
    private SceneEntity trackedEntity;
    private float startPx, startPy, startPz;
    private float startRx, startRy, startRz;
    private float startSx, startSy, startSz;

    public void captureStart(SceneEntity entity) {
        if (entity == null) return;
        this.trackedEntity = entity;
        TransformComponent tc = entity.getTransform();
        if (tc != null) {
            this.startPx = tc.posX; this.startPy = tc.posY; this.startPz = tc.posZ;
            this.startRx = tc.rotX; this.startRy = tc.rotY; this.startRz = tc.rotZ;
            this.startSx = tc.scaleX; this.startSy = tc.scaleY; this.startSz = tc.scaleZ;
        }
    }

    public void commit(CommandHistory history) {
        if (trackedEntity == null || history == null) return;
        TransformComponent tc = trackedEntity.getTransform();
        if (tc == null) return;

        boolean changed = (startPx != tc.posX || startPy != tc.posY || startPz != tc.posZ
                || startRx != tc.rotX || startRy != tc.rotY || startRz != tc.rotZ
                || startSx != tc.scaleX || startSy != tc.scaleY || startSz != tc.scaleZ);

        if (changed) {
            TransformCommand cmd = new TransformCommand(
                    trackedEntity,
                    startPx, startPy, startPz,
                    startRx, startRy, startRz,
                    startSx, startSy, startSz,
                    tc.posX, tc.posY, tc.posZ,
                    tc.rotX, tc.rotY, tc.rotZ,
                    tc.scaleX, tc.scaleY, tc.scaleZ
            );
            history.executeCommand(cmd);
            System.out.println("[UndoTracker] Committed TransformCommand for Entity: " + trackedEntity.name);
        }
        clear();
    }

    public void clear() {
        this.trackedEntity = null;
    }

    public SceneEntity getTrackedEntity() {
        return trackedEntity;
    }
}
