package com.retro.engine.ecs.components;

import com.retro.engine.animation.Skeleton;
import com.retro.engine.api.Internal;
import com.retro.engine.api.PublicApi;
import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/**
 * Holds its entity on a bone of a rigged entity, for weapons, carried items and anything else that rides a skeleton.
 * {@code BoneSocketSystem} places it each Play frame right after animation, so it never trails the pose it follows.
 * While {@link #active} the socket owns the entity's position and rotation, and a script writing them is overruled.
 * The entity keeps its own scale.
 *
 * <p>Sockets only run during Play, so an attached entity sits at its authored Transform in the editor and goes back
 * there on Stop.</p>
 */
@PublicApi
public class BoneSocket extends Component {

    /** Name of the rigged entity to follow. Blank follows this entity's parent. */
    @InspectorProperty(order = 0, label = "TARGET ENTITY", type = PropertyType.TEXT)
    public String targetEntity = "";

    /** Bone name exactly as the skeleton has it, such as Hand_L. */
    @InspectorProperty(order = 1, label = "BONE", type = PropertyType.TEXT)
    public String boneName = "";

    /** Off leaves the entity wherever it is, so a script can attach and drop it by flipping this. */
    @InspectorProperty(order = 2, label = "ACTIVE", type = PropertyType.TOGGLE)
    public boolean active = true;

    // Offset in the bone's space. Rotations use the same Euler convention as Transform
    @InspectorProperty(order = 3, label = "OFFSET POS X", min = -10.0f, max = 10.0f, step = 0.01f)
    public float offsetPosX = 0.0f;
    @InspectorProperty(order = 4, label = "OFFSET POS Y", min = -10.0f, max = 10.0f, step = 0.01f)
    public float offsetPosY = 0.0f;
    @InspectorProperty(order = 5, label = "OFFSET POS Z", min = -10.0f, max = 10.0f, step = 0.01f)
    public float offsetPosZ = 0.0f;
    @InspectorProperty(order = 6, label = "OFFSET ROT X", min = -360.0f, max = 360.0f, step = 1.0f)
    public float offsetRotX = 0.0f;
    @InspectorProperty(order = 7, label = "OFFSET ROT Y", min = -360.0f, max = 360.0f, step = 1.0f)
    public float offsetRotY = 0.0f;
    @InspectorProperty(order = 8, label = "OFFSET ROT Z", min = -360.0f, max = 360.0f, step = 1.0f)
    public float offsetRotZ = 0.0f;

    // --- Runtime state (not persisted) ---
    @Internal
    public transient Skeleton resolvedSkeleton;
    @Internal
    public transient String resolvedBone;
    @Internal
    public transient int boneIndex = -1;
    @Internal
    public transient boolean warned;

    public BoneSocket() {}

    public void resetRuntime() {
        resolvedSkeleton = null;
        resolvedBone = null;
        boneIndex = -1;
        warned = false;
    }

    @Override
    public String getComponentName() {
        return "Bone Socket";
    }
}
