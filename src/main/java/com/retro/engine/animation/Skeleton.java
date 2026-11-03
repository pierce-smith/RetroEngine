package com.retro.engine.animation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates the skeletal bone hierarchy, parent-child bone linkage,
 * and inverse bind pose matrices (offset matrices) for GPU skinning.
 *
 * A shared, read-only asset once built: duplicated entities reference the same Skeleton, so animation never writes
 * pose data here. Per-entity poses (edit pose, evaluated palette) live on each AnimatorComponent.
 */
public class Skeleton {

    public static class BoneNode {
        public int id;
        public String name;
        public int parentId = -1; // -1 indicates root bone

        // Local transform matrix relative to parent bone (16 floats, column-major 4x4)
        public final float[] localBindTransform = new float[16];

        // Inverse bind pose matrix (Offset matrix) bringing mesh space vertices into bone local space
        public final float[] offsetMatrix = new float[16];

        // Static transform of the non-bone scene nodes between the parent bone (or the scene root) and this bone, e.g.
        // FBX pivot helpers or an armature node: world = parentWorld * preTransform * local. Identity for most rigs.
        public final float[] preTransform = new float[16];
        public boolean hasPreTransform = false;

        public BoneNode(int id, String name, int parentId) {
            this.id = id;
            this.name = name;
            this.parentId = parentId;
            setIdentity(this.localBindTransform);
            setIdentity(this.offsetMatrix);
            setIdentity(this.preTransform);
        }

        /** Sets {@link #preTransform}; an identity matrix keeps the hierarchy fast path. */
        public void setPreTransform(float[] m) {
            boolean identity = true;
            for (int i = 0; i < 16; i++) {
                if (Math.abs(m[i] - ((i % 5 == 0) ? 1.0f : 0.0f)) > 1e-6f) identity = false;
            }
            if (identity) setIdentity(preTransform); else System.arraycopy(m, 0, preTransform, 0, 16);
            hasPreTransform = !identity;
        }

        private static void setIdentity(float[] m) {
            for (int i = 0; i < 16; i++) {
                m[i] = (i % 5 == 0) ? 1.0f : 0.0f;
            }
        }
    }

    private final List<BoneNode> bones = new ArrayList<>();
    private final Map<String, Integer> boneNameToIndex = new HashMap<>();

    public int addBone(String name, int parentId, float[] localBindMatrix, float[] offsetMatrix) {
        if (boneNameToIndex.containsKey(name)) {
            return boneNameToIndex.get(name);
        }

        int id = bones.size();
        BoneNode node = new BoneNode(id, name, parentId);
        if (localBindMatrix != null && localBindMatrix.length == 16) {
            System.arraycopy(localBindMatrix, 0, node.localBindTransform, 0, 16);
        }
        if (offsetMatrix != null && offsetMatrix.length == 16) {
            System.arraycopy(offsetMatrix, 0, node.offsetMatrix, 0, 16);
        }

        bones.add(node);
        boneNameToIndex.put(name, id);
        return id;
    }

    public int getBoneCount() {
        return bones.size();
    }

    public BoneNode getBone(int index) {
        if (index < 0 || index >= bones.size()) return null;
        return bones.get(index);
    }

    public int getBoneIndex(String name) {
        Integer idx = boneNameToIndex.get(name);
        return (idx != null) ? idx : -1;
    }

    public List<BoneNode> getBones() {
        return bones;
    }

    /**
     * Bind-pose skinning palette for every bone (boneWorld * offset, column-major). Unlike the runtime palette this
     * covers bones past the GPU's 64-bone limit.
     */
    public float[][] computeBindPalette() {
        float[][] world = computeBindWorlds();
        float[][] palette = new float[world.length][16];
        for (int i = 0; i < world.length; i++) multiply(world[i], bones.get(i).offsetMatrix, palette[i]);
        return palette;
    }

    /** Every bone's bind-pose world transform (parent world * preTransform * local bind), evaluated parent-first. */
    public float[][] computeBindWorlds() {
        int n = bones.size();
        float[][] world = new float[n][16];
        float[] local = new float[16];
        for (int i : getEvaluationOrder()) {
            BoneNode bone = bones.get(i);
            float[] l = bone.localBindTransform;
            if (bone.hasPreTransform) {
                multiply(bone.preTransform, bone.localBindTransform, local);
                l = local;
            }
            int parent = getEvaluationParent(i);
            if (parent >= 0) multiply(world[parent], l, world[i]); else System.arraycopy(l, 0, world[i], 0, 16);
        }
        return world;
    }

    // Parent-first evaluation order, rebuilt whenever a bone is added or re-parented (parentId is a public field)
    private int[] evalOrder = new int[0];
    private int[] evalParent = new int[0];
    private int[] evalParentSnapshot = new int[0];

    /**
     * Bone indices ordered so every bone comes after its parent. Bone indices themselves are not parent-first (skinned
     * bones are numbered before the animated-only nodes that may parent them), so hierarchy passes must walk this
     * order: world[i] = world[parent] * preTransform * local[i] then sees the parent's world already updated for this
     * frame, and an edit to an upper arm carries down the forearm, hand and fingers.
     */
    public int[] getEvaluationOrder() {
        int n = bones.size();
        boolean stale = evalParentSnapshot.length != n;
        for (int i = 0; i < n && !stale; i++) stale = evalParentSnapshot[i] != bones.get(i).parentId;
        if (stale) rebuildEvaluationOrder();
        return evalOrder;
    }

    /**
     * The parent bone used when evaluating {@code index} in {@link #getEvaluationOrder()}: its parentId, or -1 when
     * that is out of range or part of a parent cycle (the bone is then evaluated as a root).
     */
    public int getEvaluationParent(int index) {
        getEvaluationOrder();
        return (index >= 0 && index < evalParent.length) ? evalParent[index] : -1;
    }

    private void rebuildEvaluationOrder() {
        int n = bones.size();
        int[] order = new int[n];
        int[] parent = new int[n];
        int[] snapshot = new int[n];
        byte[] state = new byte[n]; // 0 unvisited, 1 on the current parent chain, 2 placed
        int[] chain = new int[n];
        int count = 0;
        for (int i = 0; i < n; i++) snapshot[i] = bones.get(i).parentId;
        for (int start = 0; start < n; start++) {
            // Climb to the first placed ancestor (or a root), then place the chain top-down
            int depth = 0;
            int b = start;
            while (b >= 0 && state[b] == 0) {
                state[b] = 1;
                chain[depth++] = b;
                int p = snapshot[b];
                if (p < 0 || p >= n || p == b) {
                    parent[b] = -1;
                    break;
                }
                if (state[p] == 1) {
                    parent[b] = -1; // parent cycle: break it here
                    break;
                }
                parent[b] = p;
                b = p;
            }
            for (int d = depth - 1; d >= 0; d--) {
                state[chain[d]] = 2;
                order[count++] = chain[d];
            }
        }
        evalOrder = order;
        evalParent = parent;
        evalParentSnapshot = snapshot;
    }

    /** Inverse of a column-major affine matrix, or null when singular. */
    public static float[] invertAffine(float[] m) {
        float a = m[0], b = m[4], c = m[8], d = m[1], e = m[5], f = m[9], g = m[2], h = m[6], k = m[10];
        float A = e * k - f * h, B = f * g - d * k, C = d * h - e * g;
        float det = a * A + b * B + c * C;
        if (Math.abs(det) < 1e-12f) return null;
        float inv = 1.0f / det;
        float[] r = new float[16];
        r[0] = A * inv;  r[4] = (c * h - b * k) * inv; r[8] = (b * f - c * e) * inv;
        r[1] = B * inv;  r[5] = (a * k - c * g) * inv; r[9] = (c * d - a * f) * inv;
        r[2] = C * inv;  r[6] = (b * g - a * h) * inv; r[10] = (a * e - b * d) * inv;
        r[12] = -(r[0] * m[12] + r[4] * m[13] + r[8] * m[14]);
        r[13] = -(r[1] * m[12] + r[5] * m[13] + r[9] * m[14]);
        r[14] = -(r[2] * m[12] + r[6] * m[13] + r[10] * m[14]);
        r[15] = 1.0f;
        return r;
    }

    /** Column-major 4x4 product out = a * b ({@code out} must not alias an input). */
    public static void multiply(float[] a, float[] b, float[] out) {
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                out[col * 4 + row] = a[row] * b[col * 4] + a[4 + row] * b[col * 4 + 1] + a[8 + row] * b[col * 4 + 2] + a[12 + row] * b[col * 4 + 3];
            }
        }
    }

    /**
     * Writes bone {@code boneIndex}'s full model-space transform (column-major 4x4) under a skinning palette into
     * {@code out}: palette * offset^-1, since palette entries are boneWorld * offset. Returns false (out untouched) for
     * an unknown bone or a singular offset matrix.
     */
    public boolean computeBoneModelTransform(int boneIndex, float[][] palette, float[] out) {
        BoneNode bone = getBone(boneIndex);
        if (bone == null || boneIndex >= palette.length) return false;
        float[] o = bone.offsetMatrix;
        // Affine inverse [R | t]^-1 = [R^-1 | -R^-1 t], R^-1 = adjugate / det (column-major)
        float a = o[0], b = o[4], c = o[8], d = o[1], e = o[5], f = o[9], g = o[2], h = o[6], k = o[10];
        float A = e * k - f * h, B = f * g - d * k, C = d * h - e * g;
        float det = a * A + b * B + c * C;
        if (Math.abs(det) < 1e-12f) return false;
        float inv = 1.0f / det;
        float[] r = new float[16];
        r[0] = A * inv;                 r[4] = (c * h - b * k) * inv; r[8] = (b * f - c * e) * inv;
        r[1] = B * inv;                 r[5] = (a * k - c * g) * inv; r[9] = (c * d - a * f) * inv;
        r[2] = C * inv;                 r[6] = (b * g - a * h) * inv; r[10] = (a * e - b * d) * inv;
        r[12] = -(r[0] * o[12] + r[4] * o[13] + r[8] * o[14]);
        r[13] = -(r[1] * o[12] + r[5] * o[13] + r[9] * o[14]);
        r[14] = -(r[2] * o[12] + r[6] * o[13] + r[10] * o[14]);
        r[15] = 1.0f;

        float[] p = palette[boneIndex];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                out[col * 4 + row] = p[row] * r[col * 4] + p[4 + row] * r[col * 4 + 1] + p[8 + row] * r[col * 4 + 2] + p[12 + row] * r[col * 4 + 3];
            }
        }
        return true;
    }

    /**
     * Writes each bone's joint position in mesh (model) space under a skinning palette into {@code out} (x, y, z per
     * bone) and returns the number of joints written. Palette entries are boneWorld * offset, so the joint origin is
     * palette * offset^-1 * (0, 0, 0, 1), where offset^-1's origin is the joint's bind-pose position.
     */
    public int computeJointModelPositions(float[][] palette, float[] out) {
        int count = Math.min(bones.size(), Math.min(palette.length, out.length / 3));
        for (int i = 0; i < count; i++) {
            float[] o = bones.get(i).offsetMatrix;
            // Bind joint x solves R x = -t for offset = [R | t] (Cramer's rule on the column-major 3x3 R)
            float c0x = o[0], c0y = o[1], c0z = o[2];
            float c1x = o[4], c1y = o[5], c1z = o[6];
            float c2x = o[8], c2y = o[9], c2z = o[10];
            float bx = -o[12], by = -o[13], bz = -o[14];

            float k12x = c1y * c2z - c1z * c2y, k12y = c1z * c2x - c1x * c2z, k12z = c1x * c2y - c1y * c2x;
            float det = c0x * k12x + c0y * k12y + c0z * k12z;
            float jx = 0.0f, jy = 0.0f, jz = 0.0f;
            if (Math.abs(det) > 1e-12f) {
                float inv = 1.0f / det;
                jx = (bx * k12x + by * k12y + bz * k12z) * inv;
                jy = (c0x * (by * c2z - bz * c2y) + c0y * (bz * c2x - bx * c2z) + c0z * (bx * c2y - by * c2x)) * inv;
                jz = (c0x * (c1y * bz - c1z * by) + c0y * (c1z * bx - c1x * bz) + c0z * (c1x * by - c1y * bx)) * inv;
            }

            float[] p = palette[i];
            out[i * 3] = p[0] * jx + p[4] * jy + p[8] * jz + p[12];
            out[i * 3 + 1] = p[1] * jx + p[5] * jy + p[9] * jz + p[13];
            out[i * 3 + 2] = p[2] * jx + p[6] * jy + p[10] * jz + p[14];
        }
        return count;
    }
}
