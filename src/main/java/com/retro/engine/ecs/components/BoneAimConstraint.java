package com.retro.engine.ecs.components;

import com.retro.engine.animation.Skeleton;
import com.retro.engine.api.Internal;
import com.retro.engine.api.PublicApi;
import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/**
 * Turns a chain of bones so one bone points at a target, sharing the turn along the chain with most of it near the tip.
 * The two kinds are {@link LookAtIK} and {@link AimIK}, separate types so a character can do both at once: aim runs
 * first, then look, so the head still finds its target after the spine has turned for the weapon.
 *
 * <p>Goes on the entity with the AnimatorComponent. {@code IKSystem} adjusts the finished pose each Play frame, after
 * foot IK and before bone sockets, so held items follow. A stopped animator is left alone.</p>
 */
@PublicApi
public abstract class BoneAimConstraint extends Component {

    /** Off eases back to the clip. */
    @InspectorProperty(order = 0, label = "ACTIVE", type = PropertyType.TOGGLE)
    public boolean active = true;

    @InspectorProperty(order = 1, label = "WEIGHT", min = 0.0f, max = 1.0f, step = 0.01f)
    public float weight = 1.0f;

    /** Name of the entity to point at. Blank uses the point from {@link #setTargetPoint}, which also takes priority. */
    @InspectorProperty(order = 2, label = "TARGET ENTITY", type = PropertyType.TEXT)
    public String targetEntity = "";

    /** Height above the target entity's origin to point at, so a character's eyes meet a face rather than its feet. */
    @InspectorProperty(order = 3, label = "TARGET HEIGHT", min = -5.0f, max = 5.0f, step = 0.01f)
    public float targetHeight = 0.0f;

    /** Comma-separated bones that turn, from the root of the chain out. */
    @InspectorProperty(order = 4, label = "CHAIN", type = PropertyType.TEXT)
    public String chain = "";

    /** The bone that has to end up pointing at the target. Blank is the chain's last bone. */
    @InspectorProperty(order = 5, label = "AIM BONE", type = PropertyType.TEXT)
    public String aimBone = "";

    // The direction that points at the target, as it lies in the mesh's bind pose. +Z is a model's forward
    @InspectorProperty(order = 6, label = "AXIS X", min = -1.0f, max = 1.0f, step = 0.01f)
    public float axisX = 0.0f;
    @InspectorProperty(order = 7, label = "AXIS Y", min = -1.0f, max = 1.0f, step = 0.01f)
    public float axisY = 0.0f;
    @InspectorProperty(order = 8, label = "AXIS Z", min = -1.0f, max = 1.0f, step = 0.01f)
    public float axisZ = 1.0f;

    /** Furthest the chain turns away from the clip's pose, in degrees. Well past this it gives up and eases back. */
    @InspectorProperty(order = 9, label = "MAX ANGLE", min = 1.0f, max = 180.0f, step = 1.0f)
    public float maxAngle = 70.0f;

    /** How fast it follows the target. Higher is snappier. */
    @InspectorProperty(order = 10, label = "SPEED", min = 0.5f, max = 40.0f, step = 0.5f)
    public float speed = 8.0f;

    // --- Runtime state (not persisted) ---
    @Internal
    public transient Skeleton resolvedSkeleton;
    @Internal
    public transient String resolvedChain, resolvedAimBone;
    @Internal
    public transient int[] chainBones = new int[0];
    @Internal
    public transient int aimBoneIndex = -1;
    @Internal
    public transient boolean resolved, warned, warm;
    /** Where it's pointing now, as a world direction, eased toward the target. */
    @Internal
    public final transient double[] direction = new double[3];
    @Internal
    public transient float blend;

    private transient boolean hasTargetPoint;
    private transient float targetX, targetY, targetZ;

    /** Points at a world position until {@link #clearTargetPoint}, for scripts tracking something other than an entity. */
    public void setTargetPoint(float x, float y, float z) {
        targetX = x;
        targetY = y;
        targetZ = z;
        hasTargetPoint = true;
    }

    public void clearTargetPoint() {
        hasTargetPoint = false;
    }

    public boolean hasTargetPoint() {
        return hasTargetPoint;
    }

    public float targetPointX() { return targetX; }
    public float targetPointY() { return targetY; }
    public float targetPointZ() { return targetZ; }

    public void resetRuntime() {
        resolvedSkeleton = null;
        resolved = false;
        warned = false;
        hasTargetPoint = false;
        resetSolve();
    }

    /** Forgets where it was pointing, so the next solve fades in from the plain clip. */
    public void resetSolve() {
        warm = false;
        blend = 0.0f;
    }
}
