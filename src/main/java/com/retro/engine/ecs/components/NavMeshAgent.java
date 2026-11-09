package com.retro.engine.ecs.components;

import com.retro.engine.api.Internal;
import com.retro.engine.api.PublicApi;
import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/**
 * Moves its entity across the scene's baked NavMesh toward a destination (see {@code NavMeshSystem}). With a
 * Character Controller on the same entity the agent drives the controller's horizontal velocity (physics keeps
 * handling gravity and ground snapping); otherwise it moves the Transform and keeps it on the mesh surface.
 * Agents are expected on root entities (their Transform is treated as world space).
 */
@PublicApi
public class NavMeshAgent extends Component {

    /** Corner capacity of one path; longer routes are re-planned from the last corner when it is reached. */
    public static final int MAX_CORNERS = 64;

    @InspectorProperty(order = 0, label = "SPEED", min = 0.0f, max = 50.0f, step = 0.25f)
    public float speed = 3.5f;

    @InspectorProperty(order = 1, label = "ANGULAR SPEED", min = 0.0f, max = 1440.0f, step = 10.0f)
    public float angularSpeed = 360.0f;

    @InspectorProperty(order = 2, label = "ACCELERATION", min = 0.0f, max = 100.0f, step = 0.5f)
    public float acceleration = 8.0f;

    @InspectorProperty(order = 3, label = "STOPPING DIST", min = 0.0f, max = 20.0f, step = 0.05f)
    public float stoppingDistance = 0.1f;

    @InspectorProperty(order = 4, label = "AUTO BRAKING", type = PropertyType.TOGGLE)
    public boolean autoBraking = true;

    /** Height of the entity's origin above the NavMesh surface (used when moving the Transform directly). */
    @InspectorProperty(order = 5, label = "BASE OFFSET", min = -5.0f, max = 5.0f, step = 0.05f)
    public float baseOffset = 0.0f;

    // --- Runtime state (not persisted; reset when play starts and stops) ---
    public float destX, destY, destZ;
    public boolean pathPending;
    public boolean hasPath;
    public boolean pathPartial;
    /** True when the agent stopped short of an unreachable destination, so a gate opening sends it on its way. */
    public boolean awaitingRoute;
    /** True while the current corner list ends before the destination (corner capacity was exceeded). */
    public boolean pathTruncated;
    public final float[] corners = new float[MAX_CORNERS * 3];
    public int cornerCount;
    public int cornerIndex;
    public float velocityX, velocityZ;
    public float remainingDistance;

    public NavMeshAgent() {}

    /** Requests a path; it is computed on the next navigation tick (until then {@link #pathPending} is true). */
    public void setDestination(float x, float y, float z) {
        destX = x; destY = y; destZ = z;
        pathPending = true;
        awaitingRoute = false;
    }

    /** Drops the current path; the agent decelerates to a stop. */
    public void stop() {
        pathPending = false;
        awaitingRoute = false;
        clearPath();
    }

    public void clearPath() {
        hasPath = false;
        pathPartial = false;
        pathTruncated = false;
        cornerCount = 0;
        cornerIndex = 0;
        remainingDistance = 0.0f;
    }

    @Internal
    public void resetRuntime() {
        stop();
        velocityX = 0.0f;
        velocityZ = 0.0f;
    }

    @Override
    public String getComponentName() {
        return "NavMesh Agent";
    }
}
