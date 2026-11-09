package com.retro.engine.ecs.components;

import com.retro.engine.api.PublicApi;
import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/**
 * A box of NavMesh that scripts can close during play, such as a doorway or a bridge. The box is centred on the entity
 * and turns and scales with it. The baker keeps the walkable area inside it apart, so closing it blocks that area and
 * nothing around it. Agents route around a closed gate, or wait at it and carry on when it opens.
 *
 * <p>The entity's own colliders stay out of the bake, so a shut door doesn't wall off its doorway. Turn on Bake Colliders
 * for a surface agents walk on, such as a bridge deck, and bake it in the position where it can be crossed. Bake again
 * after moving a gate or changing its size.
 */
@PublicApi
public class NavMeshGate extends Component {

    @InspectorProperty(order = 0, label = "SIZE X", min = 0.01f, max = 100.0f, step = 0.1f)
    public float sizeX = 2.0f;

    @InspectorProperty(order = 1, label = "SIZE Y", min = 0.01f, max = 100.0f, step = 0.1f)
    public float sizeY = 3.0f;

    @InspectorProperty(order = 2, label = "SIZE Z", min = 0.01f, max = 100.0f, step = 0.1f)
    public float sizeZ = 0.5f;

    @InspectorProperty(order = 3, label = "START OPEN", type = PropertyType.TOGGLE)
    public boolean startOpen = true;

    @InspectorProperty(order = 4, label = "BAKE COLLIDERS", type = PropertyType.TOGGLE)
    public boolean bakeColliders = false;

    private Boolean openOverride; // Set during play. Null follows startOpen, which loads after this object is built

    public NavMeshGate() {}

    public boolean isOpen() {
        return openOverride != null ? openOverride : startOpen;
    }

    /** Opens or closes the gate. Agents re-plan on the next navigation tick. */
    public void setOpen(boolean open) {
        openOverride = open;
    }

    public void resetRuntime() {
        openOverride = null;
    }

    @Override
    public String getComponentName() {
        return "NavMesh Gate";
    }
}
