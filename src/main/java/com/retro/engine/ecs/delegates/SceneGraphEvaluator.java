package com.retro.engine.ecs.delegates;

import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.ecs.SceneManager.WorldTransform;
import com.retro.engine.ecs.components.TransformComponent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Delegate managing recursive parent-child scene graph matrix propagation,
 * vector rotations, and cycle detection.
 * Optimized with reusable scratchpad arrays to eliminate per-frame GC pressure.
 */
public class SceneGraphEvaluator {

    // Pre-allocated scratchpad vector for zero per-frame array allocations
    private final float[] rotScratch = new float[3];

    public WorldTransform getComputedWorldTransform(SceneEntity entity, List<SceneEntity> sceneEntities) {
        if (entity == null) return new WorldTransform(0,0,0, 0,0,0, 1,1,1);
        TransformComponent tc = entity.getTransform();
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

        SceneEntity parent = findEntityById(entity.parentId, sceneEntities);
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

        WorldTransform parentWt = getComputedWorldTransform(parent, sceneEntities);

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

    public boolean hasHierarchyCycle(SceneEntity startEntity, List<SceneEntity> sceneEntities) {
        if (startEntity == null) return false;
        Set<String> visitedIds = new HashSet<>();
        SceneEntity current = startEntity;

        while (current != null && current.parentId != null && !current.parentId.trim().isEmpty()) {
            if (!visitedIds.add(current.id)) {
                return true;
            }
            current = findEntityById(current.parentId, sceneEntities);
        }
        return false;
    }

    public boolean setParent(SceneEntity child, SceneEntity parent, List<SceneEntity> sceneEntities) {
        if (child == null) return false;
        assert (parent == null || (!parent.id.equalsIgnoreCase(child.id) && !hasHierarchyCycle(parent, sceneEntities))) : "[FATAL] Hierarchy cycle or self-parenting prevented between Child: " + child.id + " and Parent: " + (parent != null ? parent.id : "null");
        if (parent != null && (parent == child || parent.isDescendantOf(child, sceneEntities))) {
            System.out.println("[SceneManager] Circular parenting prevented!");
            return false;
        }

        if (child.parentId != null) {
            SceneEntity oldParent = findEntityById(child.parentId, sceneEntities);
            if (oldParent != null) {
                oldParent.removeChildId(child.id);
            }
        }

        if (parent == null) {
            child.parentId = null;
            System.out.println("[SceneManager] Unparented entity '" + child.name + "'");
        } else {
            child.parentId = parent.id;
            parent.addChildId(child.id);
            System.out.println("[SceneManager] Parented '" + child.name + "' to '" + parent.name + "'");
        }
        return true;
    }

    private SceneEntity findEntityById(String id, List<SceneEntity> sceneEntities) {
        if (id == null || id.isEmpty() || sceneEntities == null) return null;
        for (SceneEntity e : sceneEntities) {
            if (e.id != null && e.id.equalsIgnoreCase(id)) return e;
        }
        return null;
    }
}
