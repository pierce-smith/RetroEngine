package com.retro.engine.animation.skeleton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates the skeletal bone hierarchy, parent-child bone linkage,
 * and inverse bind pose matrices (offset matrices) for GPU skinning.
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

        public BoneNode(int id, String name, int parentId) {
            this.id = id;
            this.name = name;
            this.parentId = parentId;
            setIdentity(this.localBindTransform);
            setIdentity(this.offsetMatrix);
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
