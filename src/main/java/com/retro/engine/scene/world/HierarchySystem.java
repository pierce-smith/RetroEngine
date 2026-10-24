package com.retro.engine.scene.world;

import com.retro.engine.scene.world.SceneWorld.WorldTransform;
import com.retro.engine.scene.components.transform.Transform;
import com.retro.engine.scene.entity.Entity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Delegate managing recursive parent-child scene graph matrix propagation,
 * vector rotations, dirty-flag transform caching, and cycle detection.
 *
 * Hardened with zero per-frame JVM heap allocations, mutable snapshot pooling,
 * and transform dirty-flag caching.
 */
public class HierarchySystem {

    private static final WorldTransform DEFAULT_IDENTITY_TRANSFORM = new WorldTransform(0,0,0, 0,0,0, 1,1,1);

    // Mutable snapshot structures for zero-allocation cache updates
    private static class LocalSnapshot {
        float px, py, pz;
        float rx, ry, rz;
        float sx, sy, sz;

        LocalSnapshot(float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
            set(px, py, pz, rx, ry, rz, sx, sy, sz);
        }

        void set(float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
            this.px = px; this.py = py; this.pz = pz;
            this.rx = rx; this.ry = ry; this.rz = rz;
            this.sx = sx; this.sy = sy; this.sz = sz;
        }
    }

    private static class ParentSnapshot {
        float px, py, pz;
        float rx, ry, rz;
        float sx, sy, sz;

        ParentSnapshot(float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
            set(px, py, pz, rx, ry, rz, sx, sy, sz);
        }

        void set(float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
            this.px = px; this.py = py; this.pz = pz;
            this.rx = rx; this.ry = ry; this.rz = rz;
            this.sx = sx; this.sy = sy; this.sz = sz;
        }
    }

    // Pre-allocated scratchpad vector for zero per-frame array allocations
    private final float[] rotScratch = new float[3];

    // Pre-allocated reusable Map scratchpads to eliminate per-call HashMap instantiations
    private final Map<String, Entity> entityMapScratch = new HashMap<>(256);
    private final Set<String> visitedIdsScratch = new HashSet<>(256);

    // Transform Caches for zero-cost static matrix propagation
    private final Map<String, WorldTransform> worldTransformCache = new HashMap<>(256);
    private final Map<String, LocalSnapshot> localSnapshotMap = new HashMap<>(256);
    private final Map<String, ParentSnapshot> parentSnapshotMap = new HashMap<>(256);

    public void clearCache() {
        worldTransformCache.clear();
        localSnapshotMap.clear();
        parentSnapshotMap.clear();
        entityMapScratch.clear();
        visitedIdsScratch.clear();
    }

    private void putOrUpdateLocalSnapshot(String entityId, float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
        LocalSnapshot snap = localSnapshotMap.get(entityId);
        if (snap != null) {
            snap.set(px, py, pz, rx, ry, rz, sx, sy, sz);
        } else {
            localSnapshotMap.put(entityId, new LocalSnapshot(px, py, pz, rx, ry, rz, sx, sy, sz));
        }
    }

    private void putOrUpdateParentSnapshot(String entityId, WorldTransform parentWt) {
        ParentSnapshot snap = parentSnapshotMap.get(entityId);
        if (snap != null) {
            snap.set(parentWt.px(), parentWt.py(), parentWt.pz(), parentWt.rx(), parentWt.ry(), parentWt.rz(), parentWt.sx(), parentWt.sy(), parentWt.sz());
        } else {
            parentSnapshotMap.put(entityId, new ParentSnapshot(parentWt.px(), parentWt.py(), parentWt.pz(), parentWt.rx(), parentWt.ry(), parentWt.rz(), parentWt.sx(), parentWt.sy(), parentWt.sz()));
        }
    }

    public WorldTransform getComputedWorldTransform(Entity entity, List<Entity> sceneEntities) {
        if (entity == null) return DEFAULT_IDENTITY_TRANSFORM;
        Transform tc = entity.getTransform();
        if (tc == null) return DEFAULT_IDENTITY_TRANSFORM;

        if (sceneEntities == null || sceneEntities.isEmpty()) {
            WorldTransform cached = worldTransformCache.get(entity.id);
            if (cached != null && !tc.isDirty()) return cached;
            WorldTransform wt = new WorldTransform(tc.posX, tc.posY, tc.posZ, tc.rotX, tc.rotY, tc.rotZ, tc.scaleX, tc.scaleY, tc.scaleZ);
            if (entity.id != null) worldTransformCache.put(entity.id, wt);
            tc.setDirty(false);
            return wt;
        }

        entityMapScratch.clear();
        for (int i = 0; i < sceneEntities.size(); i++) {
            Entity e = sceneEntities.get(i);
            if (e != null && e.id != null) {
                entityMapScratch.put(e.id, e);
            }
        }

        return getComputedWorldTransformIndexed(entity, entityMapScratch);
    }

    private WorldTransform getComputedWorldTransformIndexed(Entity entity, Map<String, Entity> entityMap) {
        if (entity == null) return DEFAULT_IDENTITY_TRANSFORM;
        Transform tc = entity.getTransform();
        if (tc == null) return DEFAULT_IDENTITY_TRANSFORM;

        String entityId = entity.id != null ? entity.id : "";
        LocalSnapshot snapshot = localSnapshotMap.get(entityId);

        boolean localChanged = tc.isDirty() || snapshot == null ||
                snapshot.px != tc.posX || snapshot.py != tc.posY || snapshot.pz != tc.posZ ||
                snapshot.rx != tc.rotX || snapshot.ry != tc.rotY || snapshot.rz != tc.rotZ ||
                snapshot.sx != tc.scaleX || snapshot.sy != tc.scaleY || snapshot.sz != tc.scaleZ;

        // Check if root entity
        if (entity.parentId == null || entity.parentId.isEmpty()) {
            WorldTransform cached = worldTransformCache.get(entityId);
            if (!localChanged && cached != null) {
                return cached;
            }

            WorldTransform wt = new WorldTransform(tc.posX, tc.posY, tc.posZ, tc.rotX, tc.rotY, tc.rotZ, tc.scaleX, tc.scaleY, tc.scaleZ);
            worldTransformCache.put(entityId, wt);
            putOrUpdateLocalSnapshot(entityId, tc.posX, tc.posY, tc.posZ, tc.rotX, tc.rotY, tc.rotZ, tc.scaleX, tc.scaleY, tc.scaleZ);
            tc.setDirty(false);
            return wt;
        }

        Entity parent = entityMap.get(entity.parentId);
        if (parent == null) {
            for (Map.Entry<String, Entity> entry : entityMap.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(entity.parentId)) {
                    parent = entry.getValue();
                    break;
                }
            }
        }

        if (parent == null || parent == entity) {
            WorldTransform cached = worldTransformCache.get(entityId);
            if (!localChanged && cached != null) {
                return cached;
            }

            WorldTransform wt = new WorldTransform(tc.posX, tc.posY, tc.posZ, tc.rotX, tc.rotY, tc.rotZ, tc.scaleX, tc.scaleY, tc.scaleZ);
            worldTransformCache.put(entityId, wt);
            putOrUpdateLocalSnapshot(entityId, tc.posX, tc.posY, tc.posZ, tc.rotX, tc.rotY, tc.rotZ, tc.scaleX, tc.scaleY, tc.scaleZ);
            tc.setDirty(false);
            return wt;
        }

        // Recursively evaluate parent
        WorldTransform parentWt = getComputedWorldTransformIndexed(parent, entityMap);
        WorldTransform cached = worldTransformCache.get(entityId);
        ParentSnapshot lastParentWt = parentSnapshotMap.get(entityId);

        boolean parentChanged = (lastParentWt == null ||
                lastParentWt.px != parentWt.px() || lastParentWt.py != parentWt.py() || lastParentWt.pz != parentWt.pz() ||
                lastParentWt.rx != parentWt.rx() || lastParentWt.ry != parentWt.ry() || lastParentWt.rz != parentWt.rz() ||
                lastParentWt.sx != parentWt.sx() || lastParentWt.sy != parentWt.sy() || lastParentWt.sz != parentWt.sz());

        if (!localChanged && !parentChanged && cached != null) {
            return cached;
        }

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
        worldTransformCache.put(entityId, wt);
        putOrUpdateLocalSnapshot(entityId, tc.posX, tc.posY, tc.posZ, tc.rotX, tc.rotY, tc.rotZ, tc.scaleX, tc.scaleY, tc.scaleZ);
        putOrUpdateParentSnapshot(entityId, parentWt);
        tc.setDirty(false);

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
        visitedIdsScratch.clear();
        Entity current = startEntity;

        while (current != null && current.parentId != null && !current.parentId.trim().isEmpty()) {
            if (!visitedIdsScratch.add(current.id)) {
                return true;
            }
            current = findEntityById(current.parentId, sceneEntities);
        }
        return false;
    }

    public boolean setParent(Entity child, Entity parent, List<Entity> sceneEntities) {
        if (child == null) return false;
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
        clearCache();
        return true;
    }

    private Entity findEntityById(String id, List<Entity> sceneEntities) {
        if (id == null || id.isEmpty() || sceneEntities == null) return null;
        for (int i = 0; i < sceneEntities.size(); i++) {
            Entity e = sceneEntities.get(i);
            if (e != null && e.id != null && e.id.equalsIgnoreCase(id)) return e;
        }
        return null;
    }
}
