package com.retro.engine.scene.components;

import com.retro.engine.editor.ui.InspectorProperty;

/**
 * Encapsulates 3D position, rotation (degrees), and scale.
 */
public class Transform implements Component {

    @InspectorProperty(label = "POS X", min = -1000.0f, max = 1000.0f, step = 0.5f)
    public float posX = 0.0f;

    @InspectorProperty(label = "POS Y", min = -1000.0f, max = 1000.0f, step = 0.5f)
    public float posY = 0.0f;

    @InspectorProperty(label = "POS Z", min = -1000.0f, max = 1000.0f, step = 0.5f)
    public float posZ = 0.0f;

    @InspectorProperty(label = "ROT X", min = -360.0f, max = 360.0f, step = 5.0f)
    public float rotX = 0.0f;

    @InspectorProperty(label = "ROT Y", min = -360.0f, max = 360.0f, step = 5.0f)
    public float rotY = 0.0f;

    @InspectorProperty(label = "ROT Z", min = -360.0f, max = 360.0f, step = 5.0f)
    public float rotZ = 0.0f;

    @InspectorProperty(label = "SCL X", min = 0.01f, max = 100.0f, step = 0.1f)
    public float scaleX = 1.0f;

    @InspectorProperty(label = "SCL Y", min = 0.01f, max = 100.0f, step = 0.1f)
    public float scaleY = 1.0f;

    @InspectorProperty(label = "SCL Z", min = 0.01f, max = 100.0f, step = 0.1f)
    public float scaleZ = 1.0f;

    public Transform() {}

    public Transform(float px, float py, float pz) {
        this.posX = px;
        this.posY = py;
        this.posZ = pz;
    }

    @Override
    public String getComponentName() {
        return "Transform";
    }
}
