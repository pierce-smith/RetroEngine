package com.retro.engine.ecs.components;

import com.retro.engine.animation.Skeleton;
import com.retro.engine.api.Internal;
import com.retro.engine.api.PublicApi;
import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/**
 * Stands a rigged entity's feet on the ground under them, for slopes and stairs. Goes on the entity with the
 * AnimatorComponent, whose origin must be at the soles. {@code IKSystem} adjusts the finished pose each Play frame, so
 * clips are never changed: on flat ground at the origin's height the pose comes out as the clip authored it.
 *
 * <p>Each leg is found from the skeleton: the foot's parent is the knee and its grandparent the hip, so a rig with twist
 * bones between them won't solve correctly. The pelvis drops to let the lower leg reach, and absorbs the jump a Character
 * Controller makes when it steps up a stair. A stopped animator is left alone, so a script that takes over a skeleton,
 * as a paired execution does, gets exactly the frames it asks for.</p>
 */
@PublicApi
public class FootIK extends Component {

    /** Off plays the clip untouched. It fades rather than snaps, so a script can flip this freely. */
    @InspectorProperty(order = 0, label = "ACTIVE", type = PropertyType.TOGGLE)
    public boolean active = true;

    @InspectorProperty(order = 1, label = "WEIGHT", min = 0.0f, max = 1.0f, step = 0.01f)
    public float weight = 1.0f;

    @InspectorProperty(order = 2, label = "LEFT FOOT", type = PropertyType.TEXT)
    public String leftFoot = "Foot_L";

    @InspectorProperty(order = 3, label = "RIGHT FOOT", type = PropertyType.TEXT)
    public String rightFoot = "Foot_R";

    @InspectorProperty(order = 4, label = "PELVIS", type = PropertyType.TEXT)
    public String pelvis = "Pelvis";

    /** Furthest the ground may be above or below the origin. Anything higher is a wall, not a step. */
    @InspectorProperty(order = 5, label = "MAX STEP", min = 0.05f, max = 2.0f, step = 0.01f)
    public float maxStep = 0.45f;

    /** Ankle to toe, in metres, for catching a stair the ankle hasn't reached. 0 only probes under the ankle. */
    @InspectorProperty(order = 6, label = "FOOT LENGTH", min = 0.0f, max = 1.0f, step = 0.01f)
    public float footLength = 0.16f;

    @InspectorProperty(order = 7, label = "MAX FOOT ANGLE", min = 0.0f, max = 80.0f, step = 1.0f)
    public float maxFootAngle = 35.0f;

    /** How fast the feet and pelvis settle on a new height. Higher is snappier. */
    @InspectorProperty(order = 8, label = "SMOOTHING", min = 1.0f, max = 60.0f, step = 0.5f)
    public float smoothing = 18.0f;

    /** The way the model faces, in degrees around Y from +Z. */
    @InspectorProperty(order = 9, label = "FORWARD YAW", min = -180.0f, max = 180.0f, step = 1.0f)
    public float forwardYaw = 0.0f;

    // --- Runtime state (not persisted) ---
    @Internal
    public transient Skeleton resolvedSkeleton;
    @Internal
    public transient String resolvedLeft, resolvedRight, resolvedPelvis;
    /** Foot, knee and hip of the left leg, then of the right. */
    @Internal
    public final transient int[] legBones = new int[6];
    @Internal
    public transient int pelvisBone = -1;
    @Internal
    public transient boolean resolved, warned, warm;
    /** Each foot's ground height relative to the origin, eased. */
    @Internal
    public final transient float[] footOffset = new float[2];
    /** Each foot's ground normal, eased: left x, y, z then right x, y, z. */
    @Internal
    public final transient double[] footNormal = {0.0, 1.0, 0.0, 0.0, 1.0, 0.0};
    @Internal
    public transient float pelvisOffset, blend, lastRootY;

    public FootIK() {}

    public void resetRuntime() {
        resolvedSkeleton = null;
        resolved = false;
        warned = false;
        resetSolve();
    }

    /** Forgets the eased heights, so the next solve fades in from the plain clip. */
    public void resetSolve() {
        warm = false;
        blend = 0.0f;
    }

    @Override
    public String getComponentName() {
        return "Foot IK";
    }
}
