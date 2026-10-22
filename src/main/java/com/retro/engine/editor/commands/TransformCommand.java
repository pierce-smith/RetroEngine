package com.retro.engine.editor.commands;

import com.retro.engine.scene.components.Transform;

/**
 * Atomic TransformCommand bundling 9-axis (position, rotation, scale) 
 * transform mutations into a single undo/redo transaction.
 */
public class TransformCommand implements ICommand {

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
