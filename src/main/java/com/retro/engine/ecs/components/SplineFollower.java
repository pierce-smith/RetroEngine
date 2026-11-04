package com.retro.engine.ecs.components;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/**
 * Moves its entity along a {@link SplineComponent} at a constant world speed (see {@code SplineSystem}). The path is
 * the spline on the entity named {@link #splineEntity}; when blank, a spline on this entity's parent is used. Scripts
 * can also assign a spline directly ({@code SimpleEntityScript.followSpline}). Followers are expected on root entities
 * (their Transform is treated as world space).
 */
public class SplineFollower extends Component {

    public enum WrapMode { CLAMP, LOOP, PING_PONG }

    @InspectorProperty(order = 0, label = "SPLINE ENTITY", type = PropertyType.TEXT)
    public String splineEntity = "";

    /** World units per second (negative travels backwards). */
    @InspectorProperty(order = 1, label = "SPEED", min = -50.0f, max = 50.0f, step = 0.25f)
    public float speed = 3.0f;

    @InspectorProperty(order = 2, label = "WRAP MODE")
    public WrapMode wrapMode = WrapMode.LOOP;

    @InspectorProperty(order = 3, label = "ALIGN TO TANGENT", type = PropertyType.TOGGLE)
    public boolean alignToTangent = true;

    @InspectorProperty(order = 4, label = "AUTO PLAY", type = PropertyType.TOGGLE)
    public boolean autoPlay = true;

    /** Normalized arc-length progress the follower starts from when play begins. */
    @InspectorProperty(order = 5, label = "START PROGRESS", min = 0.0f, max = 1.0f, step = 0.01f)
    public float startProgress = 0.0f;

    // --- Runtime state (not persisted; reset when play starts and stops) ---
    public SplineComponent spline;
    public boolean resolved;
    public boolean playing;
    /** Arc-length travelled from the spline start. */
    public float distance;
    /** +1 forward, -1 backward (PING_PONG flips it). */
    public float direction = 1.0f;
    /** Pending normalized progress to apply on the next tick (NaN = none); set before the spline is resolved. */
    public float pendingProgress = Float.NaN;

    public SplineFollower() {}

    public void resetRuntime() {
        spline = null;
        resolved = false;
        playing = autoPlay;
        distance = 0.0f;
        direction = 1.0f;
        pendingProgress = startProgress;
    }

    @Override
    public String getComponentName() {
        return "Spline Follower";
    }
}
