package com.retro.engine.editor.commands;

import com.retro.engine.scene.Entity;
import com.retro.engine.scene.components.Light;

/**
 * Encapsulates Light component property modifications (type, intensity, RGB) for Undo/Redo.
 */
public class LightPropertyCommand implements ICommand {
    private final Entity entity;
    private final Light.LightType oldType, newType;
    private final float oldIntensity, newIntensity;
    private final float oldR, oldG, oldB;
    private final float newR, newG, newB;

    public LightPropertyCommand(Entity entity,
                                Light.LightType oldType, Light.LightType newType,
                                float oldIntensity, float newIntensity,
                                float oldR, float oldG, float oldB,
                                float newR, float newG, float newB) {
        this.entity = entity;
        this.oldType = oldType; this.newType = newType;
        this.oldIntensity = oldIntensity; this.newIntensity = newIntensity;
        this.oldR = oldR; this.oldG = oldG; this.oldB = oldB;
        this.newR = newR; this.newG = newG; this.newB = newB;
    }

    @Override
    public void execute() {
        if (entity == null) return;
        Light lc = entity.getComponent(Light.class);
        if (lc != null) {
            lc.type = newType;
            lc.intensity = newIntensity;
            lc.r = newR; lc.g = newG; lc.b = newB;
        }
    }

    @Override
    public void undo() {
        if (entity == null) return;
        Light lc = entity.getComponent(Light.class);
        if (lc != null) {
            lc.type = oldType;
            lc.intensity = oldIntensity;
            lc.r = oldR; lc.g = oldG; lc.b = oldB;
        }
    }

    @Override
    public String getName() {
        return "Modify Light on '" + (entity != null ? entity.name : "null") + "'";
    }
}
