package com.retro.engine.scene;

import com.retro.engine.scene.Entity;
import com.retro.engine.scene.SceneWorld.WorldTransform;
import com.retro.engine.scene.components.Transform;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Delegate managing recursive parent-child scene graph matrix propagation,
 * vector rotations, and cycle detection.
 *
 * Optimized with reusable scratchpad arrays and O(1) Map lookups to eliminate
 * per-frame array allocations and O(N^2) linear string search overhead.
 */
public class HierarchySystem {

    // Pre-allocated scratchpad vector for zero per-frame array allocations
    private final float[] rotScratch = new float[3];

    public WorldTransform getComputedWorldTransform(Entity entity, List<Entity> sceneEntities) {
        if (entity == null) return new WorldTransform(0,0,0, 0,0,0, 1,1,1);
        Transform tc = entity.getTransform();
        if (tc == null) return new WorldTransform(0,0,0, 0,0,0, 1,1,1);

        if (sceneEntities == null || sceneEntities.isEmpty()) {
            return new WorldTransform(tc.posX, tc.posY, tc.posZ, tc.rotX, tc.rotY, tc.rotZ, tc.scaleX, tc.scaleY, tc.scaleZ);
        }

        // Build ephemeral O(1) lookup table for current frame evaluation
        Map<String, Entity> entityMap = new HashMap<>(sceneEntities.size());
        for (Entity e : sceneEntities) {
            if (e != null && e.id != null) {
                entityMap.put(e.id.toLowerCase(), e);
            }
        }

        return getComputedWorldTransformIndexed(entity, entityMap);
    }

    private WorldTransform getComputedWorldTransformIndexed(Entity entity, Map<String, Entity> entityMap) {
        if (entity == null) return new WorldTransform(0,0,0, 0,0,0, 1,1,1);
        Transform tc = entity.getTransform();
        if (tc == null) return new WorldTransform(0,0,0, 0,0,0, 1,1,1);

        if (entity.parentId == null || entity.parentId.isEmpty()) {
            WorldTransform wt = new WorldTransform(tc.posX, tc.posY, tc.posZ, tc.rotX, tc.rotY, tc.rotZ, tc.scaleX, tc.scaleY, tc.scaleZ);
            assert (!Float.isNaN(wt.px()) && !Float.isInfinite(wt.px()) &&
                !Float.isNaN(wt.py()) && !Float.isInfinite(wt.py()) &&
                !Float.isNaN(wt.pz()) && !Float.isInfinite(wt.pz()) &&
                !Float.isNaN(wt.sx()) && !Float.isInfinite(wt.sx()) && wt.sx() != 0.0f &&
                !Float.isNaN(wt.sy()) && !Float.isInfinite(wt.sy()) && wt.sy() != 0.0f &&
                !Float.isNaN(wt.sz()) && !Float.isInfinite(wt.sz()) && wt.sz() != 0.0f) : "[FATAL] Degenerate matrix transform calculated for Entity: " + entity.id;
            return wt;
        }

        Entity parent = entityMap.get(entity.parentId.toLowerCase());
        if (parent == null || parent == entity) {
            WorldTransform wt = new WorldTransform(tc.posX, tc.posY, tc.posZ, tc.rotX, tc.rotY, tc.rotZ, tc.scaleX, tc.scaleY, tc.scaleZ);
            assert (!Float.isNaN(wt.px()) && !Float.isInfinite(wt.px()) &&
                !Float.isNaN(wt.py()) && !Float.isInfinite(wt.py()) &&
                !Float.isNaN(wt.pz()) && !Float.isInfinite(wt.pz()) &&
                !Float.isNaN(wt.sx()) && !Float.isInfinite(wt.sx()) && wt.sx() != 0.0f &&
                !Float.isNaN(wt.sy()) && !Float.isInfinite(wt.sy()) && wt.sy() != 0.0f &&
                !Float.isNaN(wt.sz()) && !Float.isInfinite(wt.sz()) && wt.sz() != 0.0f) : "[FATAL] Degenerate matrix transform calculated for Entity: " + entity.id;
            return wt;
        }

        WorldTransform parentWt = getComputedWorldTransformIndexed(parent, entityMap);

        float sx = parentWt.sx() * tc.scaleX;
        float sy = parentWt.sy() * tc.scaleY;
        float sz = parentWt.sz() * tc.scaleZ;

        float rx = (parentWt.rx() + tc.rotX) % 360.0f;
        float ry = (parentWt.ry() + tc.rotY) % 360.0f;
        float rz = (parentWt.rz() + tc.rotZ) % 360.0f;

        float scaledLx = tc.posX * parentWt.sx();
        float scaledLy = tc.posY * parentWt.sy();
        float scaledLz = tc.posZ * parentWt.sz();

        rotateVector(scaledLx, scaledLy, scaledLz, parentWt.rx(), parentWt.ry(), parentWt.rz(), rotScratch);

        float px = parentWt.px() + rotScratch[0];
        float py = parentWt.py() + rotScratch[1];
        float pz = parentWt.pz() + rotScratch[2];

        WorldTransform wt = new WorldTransform(px, py, pz, rx, ry, rz, sx, sy, sz);
        assert (!Float.isNaN(wt.px()) && !Float.isInfinite(wt.px()) &&
                !Float.isNaN(wt.py()) && !Float.isInfinite(wt.py()) &&
                !Float.isNaN(wt.pz()) && !Float.isInfinite(wt.pz()) &&
                !Float.isNaN(wt.sx()) && !Float.isInfinite(wt.sx()) && wt.sx() != 0.0f &&
                !Float.isNaN(wt.sy()) && !Float.isInfinite(wt.sy()) && wt.sy() != 0.0f &&
                !Float.isNaN(wt.sz()) && !Float.isInfinite(wt.sz()) && wt.sz() != 0.0f) : "[FATAL] Degenerate matrix transform calculated for Entity: " + entity.id;
        return wt;
    }

    public float[] rotateVector(float x, float y, float z, float pitchDeg, float yawDeg, float rollDeg) {
        return rotateVector(x, y, z, pitchDeg, yawDeg, rollDeg, rotScratch);
    }

    public float[] rotateVector(float x, float y, float z, float pitchDeg, float yawDeg, float rollDeg, float[] out) {
        float radPitch = (float) Math.toRadians(pitchDeg);
        float radYaw = (float) Math.toRadians(yawDeg);
        float radRoll = (float) Math.toRadians(rollDeg);

        float cp = (float) Math.cos(radPitch), sp = (float) Math.sin(radPitch);
        float cy = (float) Math.cos(radYaw), sy = (float) Math.sin(radYaw);
        float cr = (float) Math.cos(radRoll), sr = (float) Math.sin(radRoll);

        float x1 = cr * x - sr * y;
        float y1 = sr * x + cr * y;
        float z1 = z;

        float x2 = x1;
        float y2 = cp * y1 - sp * z1;
        float z2 = sp * y1 + cp * z1;

        float x3 = cy * x2 + sy * z2;
        float y3 = y2;
        float z3 = -sy * x2 + cy * z2;

        out[0] = x3;
        out[1] = y3;
        out[2] = z3;
        return out;
    }

    public boolean hasHierarchyCycle(Entity startEntity, List<Entity> sceneEntities) {
        if (startEntity == null) return false;
        Set<String> visitedIds = new HashSet<>();
        Entity current = startEntity;

        while (current != null && current.parentId != null && !current.parentId.trim().isEmpty()) {
            if (!visitedIds.add(current.id)) {
                return true;
            }
            current = findEntityById(current.parentId, sceneEntities);
        }
        return false;
    }

    public boolean setParent(Entity child, Entity parent, List<Entity> sceneEntities) {
        if (child == null) return false;
        assert (parent == null || (!parent.id.equalsIgnoreCase(child.id) && !hasHierarchyCycle(parent, sceneEntities))) : "[FATAL] Hierarchy cycle or self-parenting prevented between Child: " + child.id + " and Parent: " + (parent != null ? parent.id : "null");
        if (parent != null && (parent == child || parent.isDescendantOf(child, sceneEntities))) {
            System.out.println("[SceneWorld] Circular parenting prevented!");
            return false;
        }

        if (child.parentId != null) {
            Entity oldParent = findEntityById(child.parentId, sceneEntities);
            if (oldParent != null) {
                oldParent.removeChildId(child.id);
            }
        }

        if (parent == null) {
            child.parentId = null;
            System.out.println("[SceneWorld] Unparented entity '" + child.name + "'");
        } else {
            child.parentId = parent.id;
            parent.addChildId(child.id);
            System.out.println("[SceneWorld] Parented '" + child.name + "' to '" + parent.name + "'");
        }
        return true;
    }

    private Entity findEntityById(String id, List<Entity> sceneEntities) {
        if (id == null || id.isEmpty() || sceneEntities == null) return null;
        for (Entity e : sceneEntities) {
            if (e.id != null && e.id.equalsIgnoreCase(id)) return e;
        }
        return null;
    }
}
