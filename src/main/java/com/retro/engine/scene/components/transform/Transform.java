package com.retro.engine.scene.components.transform;

import com.retro.engine.editor.ui.panels.InspectorProperty;
import com.retro.engine.scene.components.Component;

/**
 * Encapsulates 3D position, rotation (degrees), and scale.
 * Hardened with dirty-flag tracking for zero-cost static transform matrix propagation.
 */
public class Transform extends Component {

    @InspectorProperty(order = 0, label = "POS X", min = -1000.0f, max = 1000.0f, step = 0.5f)
    public float posX = 0.0f;

    @InspectorProperty(order = 1, label = "POS Y", min = -1000.0f, max = 1000.0f, step = 0.5f)
    public float posY = 0.0f;

    @InspectorProperty(order = 2, label = "POS Z", min = -1000.0f, max = 1000.0f, step = 0.5f)
    public float posZ = 0.0f;

    @InspectorProperty(order = 3, label = "ROT X", min = -360.0f, max = 360.0f, step = 5.0f)
    public float rotX = 0.0f;

    @InspectorProperty(order = 4, label = "ROT Y", min = -360.0f, max = 360.0f, step = 5.0f)
    public float rotY = 0.0f;

    @InspectorProperty(order = 5, label = "ROT Z", min = -360.0f, max = 360.0f, step = 5.0f)
    public float rotZ = 0.0f;

    @InspectorProperty(order = 6, label = "SCL X", min = 0.01f, max = 100.0f, step = 0.1f)
    public float scaleX = 1.0f;

    @InspectorProperty(order = 7, label = "SCL Y", min = 0.01f, max = 100.0f, step = 0.1f)
    public float scaleY = 1.0f;

    @InspectorProperty(order = 8, label = "SCL Z", min = 0.01f, max = 100.0f, step = 0.1f)
    public float scaleZ = 1.0f;

    private boolean dirty = true;

    public Transform() {}

    public Transform(float px, float py, float pz) {
        this.posX = px;
        this.posY = py;
        this.posZ = pz;
        this.dirty = true;
    }

    public boolean isDirty() { return dirty; }
    public void markDirty() { this.dirty = true; }
    public void setDirty(boolean dirty) { this.dirty = dirty; }

    public void setPosition(float x, float y, float z) {
        if (this.posX != x || this.posY != y || this.posZ != z) {
            this.posX = x; this.posY = y; this.posZ = z;
            this.dirty = true;
        }
    }

    public void setRotation(float x, float y, float z) {
        if (this.rotX != x || this.rotY != y || this.rotZ != z) {
            this.rotX = x; this.rotY = y; this.rotZ = z;
            this.dirty = true;
        }
    }

    public void setScale(float x, float y, float z) {
        if (this.scaleX != x || this.scaleY != y || this.scaleZ != z) {
            this.scaleX = x; this.scaleY = y; this.scaleZ = z;
            this.dirty = true;
        }
    }

    @Override
    public String getComponentName() {
        return "Transform";
    }
}
