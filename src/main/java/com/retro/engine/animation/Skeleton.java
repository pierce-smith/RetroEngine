package com.retro.engine.animation;

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
}
