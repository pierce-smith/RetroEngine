package com.retro.engine.scripting;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.Camera;
import com.retro.engine.graphics.SceneRenderPipeline;
import com.retro.engine.physics.RaycastHit;

import java.util.List;

/**
 * Drives {@link EntityScript#onMouseEnter()}, {@code onMouseExit}, {@code onMouseDown} and {@code onMouseUp}. Once
 * per Play frame, after physics and before onUpdate, it casts a ray from the Main Camera through the cursor (the view's
 * centre while the cursor is locked) and finds the first solid collider. Triggers don't block the ray, the same as
 * {@link EntityScript#raycast}. The hooks go to the scripts on the collider's own entity.
 *
 * <ul>
 *   <li>Enter / Exit: the entity under the cursor changed. Leaving the view counts as pointing at nothing.</li>
 *   <li>Down: the left button was pressed over an entity.</li>
 *   <li>Up: the left button was released after a Down. It goes to the entity that got the Down, wherever the cursor
 *       is by then, so a script can tell a click (Up while still hovered) from a drag off.</li>
 * </ul>
 *
 * <p>Single-threaded, like the rest of the script runtime. Holds no state across Play sessions.</p>
 */
public final class PointerEvents {

    private static final int LEFT_BUTTON = 0;

    private static final RaycastHit hit = new RaycastHit();
    private static final float[] view = new float[16];
    private static Entity hovered;
    private static Entity pressed;
    private static boolean buttonWasDown;

    private PointerEvents() {}

    /** Forgets the hovered and pressed entities without sending any hooks: Play started, stopped or changed scene. */
    public static void reset() {
        hovered = null;
        pressed = null;
        buttonWasDown = false;
        hit.entity = null;
    }

    public static void update(List<Entity> entities, SceneWorld world) {
        if (entities == null) return;
        // Destroyed since last frame: it has no scripts left to tell
        if (hovered != null && !entities.contains(hovered)) hovered = null;
        if (pressed != null && !entities.contains(pressed)) pressed = null;

        Entity target = pick(entities, world);
        if (target != hovered) {
            Entity left = hovered;
            hovered = target;
            if (left != null) ScriptEngine.dispatchPointer(left, ScriptEngine.Pointer.EXIT);
            if (target != null) ScriptEngine.dispatchPointer(target, ScriptEngine.Pointer.ENTER);
        }

        boolean down = Input.isMouseButtonDown(LEFT_BUTTON);
        boolean pressedNow = Input.isMouseButtonPressed(LEFT_BUTTON);
        if (pressedNow && hovered != null) {
            pressed = hovered;
            ScriptEngine.dispatchPointer(hovered, ScriptEngine.Pointer.DOWN);
        }
        // pressedNow && !down: pressed and released within one frame, still a full click
        if (!down && (buttonWasDown || pressedNow) && pressed != null) {
            Entity released = pressed;
            pressed = null;
            ScriptEngine.dispatchPointer(released, ScriptEngine.Pointer.UP);
        }
        buttonWasDown = down;
    }

    /** Entity whose collider is under the cursor, or null. */
    private static Entity pick(List<Entity> entities, SceneWorld world) {
        if (world == null || !Input.isMouseInView()) return null;
        float w = Input.getViewWidth(), h = Input.getViewHeight();
        Entity cameraEntity = SceneWorld.findMainCamera(entities);
        Camera camera = cameraEntity != null ? cameraEntity.getComponent(Camera.class) : null;
        SceneWorld.WorldTransform wt = cameraEntity != null ? world.getComputedWorldTransform(cameraEntity) : null;
        if (camera == null || wt == null) return null;

        // The renderer's view matrix and projection (vertical FOV, view aspect), inverted for one ray
        SceneRenderPipeline.computeCameraView(wt, view);
        float fov = Math.max(1.0f, Math.min(179.0f, camera.fov));
        float tanHalf = (float) Math.tan(Math.toRadians(fov) * 0.5);
        float ndcX = Input.getMouseX() / w * 2.0f - 1.0f;
        float ndcY = 1.0f - Input.getMouseY() / h * 2.0f;
        float cx = ndcX * tanHalf * (w / h), cy = ndcY * tanHalf, cz = -1.0f;
        // The view's 3x3 block is the camera rotation transposed, so its rows take camera space to world space
        float dx = view[0] * cx + view[1] * cy + view[2] * cz;
        float dy = view[4] * cx + view[5] * cy + view[6] * cz;
        float dz = view[8] * cx + view[9] * cy + view[10] * cz;

        float far = Math.max(Math.max(0.01f, camera.nearPlane) + 0.01f, camera.farPlane);
        world.getPhysicsWorld().raycastExcludingInPlace(cameraEntity, wt.px(), wt.py(), wt.pz(), dx, dy, dz, far, hit);
        Entity found = hit.hit ? hit.entity : null;
        hit.entity = null; // don't keep a destroyed entity reachable
        return found;
    }
}
