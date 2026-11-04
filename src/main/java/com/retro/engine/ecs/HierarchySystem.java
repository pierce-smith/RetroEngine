package com.retro.engine.ecs;

import com.retro.engine.ecs.SceneWorld.WorldTransform;
import com.retro.engine.ecs.components.Transform;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes world transforms through the parent chain, with cycle detection and parenting helpers.
 *
 * <p>Results are cached per entity id and recomputed only when the entity's local transform or a parent's world
 * transform changed. Cached WorldTransform and snapshot objects are updated in place, and the id-to-entity index is
 * built once per frame (or when the scene size changes), so steady-state frames allocate nothing and cost O(n).
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

    // Id index over the scene list, rebuilt lazily: once per frame (invalidateEntityIndex) or when the list changes size
    private List<Entity> indexedList = null;
    private int indexedSize = -1;
    private boolean indexDirty = true;

    /** Marks the id index stale; the next lookup rebuilds it. SceneWorld calls this once per frame. */
    public void invalidateEntityIndex() {
        indexDirty = true;
    }

    private void ensureEntityIndex(List<Entity> sceneEntities) {
        if (!indexDirty && indexedList == sceneEntities && indexedSize == sceneEntities.size()) return;
        entityMapScratch.clear();
        for (int i = 0; i < sceneEntities.size(); i++) {
            Entity e = sceneEntities.get(i);
            if (e != null && e.id != null) entityMapScratch.put(e.id, e);
        }
        indexedList = sceneEntities;
        indexedSize = sceneEntities.size();
        indexDirty = false;
    }

    /** Stores a result in the per-entity cache, reusing the cached object when there is one. */
    private WorldTransform storeWorld(String entityId, WorldTransform cached, float px, float py, float pz,
                                      float rx, float ry, float rz, float sx, float sy, float sz) {
        if (cached != null) {
            cached.set(px, py, pz, rx, ry, rz, sx, sy, sz);
            return cached;
        }
        WorldTransform wt = new WorldTransform(px, py, pz, rx, ry, rz, sx, sy, sz);
        worldTransformCache.put(entityId, wt);
        return wt;
    }

    public void clearCache() {
        indexDirty = true;
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
            if (entity.id == null) {
                return new WorldTransform(tc.posX, tc.posY, tc.posZ, tc.rotX, tc.rotY, tc.rotZ, tc.scaleX, tc.scaleY, tc.scaleZ);
            }
            WorldTransform cached = worldTransformCache.get(entity.id);
            if (cached != null && !tc.isDirty()) return cached;
            tc.setDirty(false);
            return storeWorld(entity.id, cached, tc.posX, tc.posY, tc.posZ, tc.rotX, tc.rotY, tc.rotZ, tc.scaleX, tc.scaleY, tc.scaleZ);
        }

        ensureEntityIndex(sceneEntities);
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

            WorldTransform wt = storeWorld(entityId, cached, tc.posX, tc.posY, tc.posZ, tc.rotX, tc.rotY, tc.rotZ, tc.scaleX, tc.scaleY, tc.scaleZ);
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

            WorldTransform wt = storeWorld(entityId, cached, tc.posX, tc.posY, tc.posZ, tc.rotX, tc.rotY, tc.rotZ, tc.scaleX, tc.scaleY, tc.scaleZ);
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

        // Compose rotations as matrices (R_world = R_parent * R_local) in the renderer's convention; adding Euler
        // angles is only correct for pure-yaw parents and makes children of a pitched camera tilt the wrong way.
        float[] world = composeRotation(parentWt.rx(), parentWt.ry(), parentWt.rz(), tc.rotX, tc.rotY, tc.rotZ);
        float rx = world[0], ry = world[1], rz = world[2];

        float scaledLx = tc.posX * parentWt.sx();
        float scaledLy = tc.posY * parentWt.sy();
        float scaledLz = tc.posZ * parentWt.sz();

        rotateVector(scaledLx, scaledLy, scaledLz, parentWt.rx(), parentWt.ry(), parentWt.rz(), rotScratch);

        float px = parentWt.px() + rotScratch[0];
        float py = parentWt.py() + rotScratch[1];
        float pz = parentWt.pz() + rotScratch[2];

        WorldTransform wt = storeWorld(entityId, cached, px, py, pz, rx, ry, rz, sx, sy, sz);
        putOrUpdateLocalSnapshot(entityId, tc.posX, tc.posY, tc.posZ, tc.rotX, tc.rotY, tc.rotZ, tc.scaleX, tc.scaleY, tc.scaleZ);
        putOrUpdateParentSnapshot(entityId, parentWt);
        tc.setDirty(false);

        return wt;
    }

    public float[] rotateVector(float x, float y, float z, float pitchDeg, float yawDeg, float rollDeg) {
        return rotateVector(x, y, z, pitchDeg, yawDeg, rollDeg, rotScratch);
    }

    /** Rotates a local vector by an entity rotation exactly as the renderers' model matrix does. */
    public float[] rotateVector(float x, float y, float z, float pitchDeg, float yawDeg, float rollDeg, float[] out) {
        rotationMatrix(pitchDeg, yawDeg, rollDeg, matScratchA);
        double[] m = matScratchA;
        out[0] = (float) (m[0] * x + m[1] * y + m[2] * z);
        out[1] = (float) (m[3] * x + m[4] * y + m[5] * z);
        out[2] = (float) (m[6] * x + m[7] * y + m[8] * z);
        return out;
    }

    private final double[] matScratchA = new double[9], matScratchB = new double[9], matScratchC = new double[9];
    private final float[] eulerScratch = new float[3];

    /**
     * Row-major 3x3 of the renderers' model rotation (MetalUniformStream / VulkanPassEncoder): R = Rz(-z) * Ry(-y) * Rx(-x)
     * with standard right-handed axis matrices. Cameras use the same matrix (SceneRenderPipeline.computeCameraView).
     */
    private static void rotationMatrix(float xDeg, float yDeg, float zDeg, double[] m) {
        double ax = Math.toRadians(xDeg), ay = Math.toRadians(yDeg), az = Math.toRadians(zDeg);
        double cx = Math.cos(ax), sx = Math.sin(ax), cy = Math.cos(ay), sy = Math.sin(ay), cz = Math.cos(az), sz = Math.sin(az);
        m[0] = cy * cz;  m[1] = cx * sz + sx * sy * cz;  m[2] = sx * sz - cx * sy * cz;
        m[3] = -cy * sz; m[4] = cx * cz - sx * sy * sz;  m[5] = sx * cz + cx * sy * sz;
        m[6] = sy;       m[7] = -sx * cy;                m[8] = cx * cy;
    }

    /**
     * World Euler angles of a child: the rotation R_parent * R_local, expressed in the engine's Euler convention. When
     * plain addition gives the same rotation (pure-yaw parents, the common case) its familiar values are kept; otherwise
     * the equivalent Euler solution closest to them is returned so angles stay continuous frame to frame.
     */
    private float[] composeRotation(float px, float py, float pz, float lx, float ly, float lz) {
        float ax = (px + lx) % 360.0f, ay = (py + ly) % 360.0f, az = (pz + lz) % 360.0f;
        rotationMatrix(px, py, pz, matScratchA);
        rotationMatrix(lx, ly, lz, matScratchB);
        double[] r = matScratchC;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i * 3 + j] = matScratchA[i * 3] * matScratchB[j] + matScratchA[i * 3 + 1] * matScratchB[3 + j] + matScratchA[i * 3 + 2] * matScratchB[6 + j];
            }
        }
        float[] out = eulerScratch;
        rotationMatrix(ax, ay, az, matScratchA);
        double diff = 0.0;
        for (int i = 0; i < 9; i++) diff = Math.max(diff, Math.abs(matScratchA[i] - r[i]));
        if (diff < 1e-5) { out[0] = ax; out[1] = ay; out[2] = az; return out; }

        double sy = Math.max(-1.0, Math.min(1.0, r[6]));
        double x1, y1, z1, x2, y2, z2;
        y1 = Math.toDegrees(Math.asin(sy));
        if (Math.abs(sy) < 0.99999) {
            x1 = Math.toDegrees(Math.atan2(-r[7], r[8]));
            z1 = Math.toDegrees(Math.atan2(-r[3], r[0]));
            x2 = x1 + 180.0; y2 = 180.0 - y1; z2 = z1 + 180.0;
        } else { // gimbal lock: fold roll into pitch
            x1 = Math.toDegrees(Math.atan2(sy * r[1], r[4]));
            z1 = 0.0;
            x2 = x1; y2 = y1; z2 = z1;
        }
        double d1 = angleDist(x1, ax) + angleDist(y1, ay) + angleDist(z1, az);
        double d2 = angleDist(x2, ax) + angleDist(y2, ay) + angleDist(z2, az);
        boolean first = d1 <= d2;
        out[0] = (float) nearAngle(first ? x1 : x2, ax);
        out[1] = (float) nearAngle(first ? y1 : y2, ay);
        out[2] = (float) nearAngle(first ? z1 : z2, az);
        return out;
    }

    private static double angleDist(double a, double b) {
        double d = Math.abs((a - b) % 360.0);
        return d > 180.0 ? 360.0 - d : d;
    }

    /** a shifted by whole turns to lie within 180 degrees of the reference. */
    private static double nearAngle(double a, double ref) {
        return a + 360.0 * Math.round((ref - a) / 360.0);
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
