package com.retro.engine.assets.importers;

import com.retro.engine.animation.clips.AnimationClip;
import com.retro.engine.animation.clips.KeyframeTrack;
import com.retro.engine.animation.skeleton.Skeleton;
import org.lwjgl.assimp.*;

import java.io.File;
import java.util.*;

import static org.lwjgl.assimp.Assimp.*;

/**
 * High-performance Assimp importer extracting bone hierarchies,
 * inverse bind pose offset matrices, and embedded animation tracks (FBX, glTF, DAE, GLB).
 */
public class SkeletonImporter {

    public record SkeletonImportResult(
            Skeleton skeleton,
            List<AnimationClip> animations
    ) {}

    public static File resolveModelFile(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) return null;
        File direct = new File(filePath);
        if (direct.exists()) return direct;

        String clean = filePath.replace('\\', '/').replace('/', File.separatorChar).trim();
        File userDirFile = new File(System.getProperty("user.dir"), clean);
        if (userDirFile.exists()) return userDirFile;

        String rawClean = filePath.replace('\\', '/').trim();
        if (rawClean.startsWith("Assets/")) {
            String stripped = rawClean.substring(7);
            File strippedFile = new File(System.getProperty("user.dir"), stripped);
            if (strippedFile.exists()) return strippedFile;
        }

        String fileName = new File(clean).getName();
        if (!fileName.isEmpty()) {
            File found = findFileByName(new File(System.getProperty("user.dir")), fileName, 0);
            if (found != null && found.exists()) return found;

            String userHome = System.getProperty("user.home");
            if (userHome != null) {
                File homeFound = findFileByName(new File(userHome, "Downloads"), fileName, 0);
                if (homeFound != null && homeFound.exists()) return homeFound;
            }
        }
        return null;
    }

    private static File findFileByName(File dir, String name, int depth) {
        if (dir == null || !dir.exists() || !dir.isDirectory() || depth > 5) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;

        for (File f : files) {
            if (f.isFile() && f.getName().equalsIgnoreCase(name)) return f;
            if (f.isDirectory() && !f.getName().startsWith(".") && !f.getName().equals("build") && !f.getName().equals("node_modules")) {
                File sub = findFileByName(f, name, depth + 1);
                if (sub != null) return sub;
            }
        }
        return null;
    }

    public static SkeletonImportResult loadSkeletonAndAnimations(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) return null;

        File file = resolveModelFile(filePath);
        if (file == null || !file.exists()) {
            System.err.println("[SkeletonImporter] Model file not found: " + filePath);
            return null;
        }

        String resolvedPath = file.getAbsolutePath();

        AIScene scene = aiImportFile(resolvedPath,
                aiProcess_Triangulate |
                aiProcess_JoinIdenticalVertices |
                aiProcess_LimitBoneWeights);

        if (scene == null || scene.mRootNode() == null) {
            System.err.println("[SkeletonImporter] Failed to parse scene with Assimp for " + resolvedPath + ": " + aiGetErrorString());
            return null;
        }

        Skeleton skeleton = new Skeleton();
        Map<String, Integer> boneNameToIndex = new HashMap<>();

        // 1. Scan all meshes to register unique bone names & offset matrices
        int numMeshes = scene.mNumMeshes();
        for (int m = 0; m < numMeshes; m++) {
            AIMesh mesh = AIMesh.create(scene.mMeshes().get(m));
            int numBones = mesh.mNumBones();
            if (numBones == 0) continue;

            for (int b = 0; b < numBones; b++) {
                AIBone bone = AIBone.create(mesh.mBones().get(b));
                String boneName = bone.mName().dataString();

                if (!boneNameToIndex.containsKey(boneName)) {
                    float[] offsetMatrix = convertMatrix(bone.mOffsetMatrix());
                    int boneId = skeleton.addBone(boneName, -1, null, offsetMatrix);
                    boneNameToIndex.put(boneName, boneId);
                }
            }
        }

        // 2. Scan animation channels for any additional node names that act as animated bones
        int numAnimations = scene.mNumAnimations();
        for (int a = 0; a < numAnimations; a++) {
            AIAnimation aiAnim = AIAnimation.create(scene.mAnimations().get(a));
            int numChannels = aiAnim.mNumChannels();
            for (int c = 0; c < numChannels; c++) {
                AINodeAnim channel = AINodeAnim.create(aiAnim.mChannels().get(c));
                String nodeName = channel.mNodeName().dataString();
                if (nodeName != null && !nodeName.isEmpty() && !boneNameToIndex.containsKey(nodeName)) {
                    int boneId = skeleton.addBone(nodeName, -1, null, null);
                    boneNameToIndex.put(nodeName, boneId);
                }
            }
        }

        // 3. Traverse scene graph hierarchy to resolve parent-child bone relationships
        if (skeleton.getBoneCount() > 0) {
            AINode rootNode = scene.mRootNode();
            buildSkeletonHierarchy(rootNode, -1, skeleton, boneNameToIndex);
        }

        // 4. Extract embedded animation sequences
        List<AnimationClip> animations = new ArrayList<>();

        for (int a = 0; a < numAnimations; a++) {
            AIAnimation aiAnim = AIAnimation.create(scene.mAnimations().get(a));
            String animName = aiAnim.mName().dataString();
            if (animName == null || animName.trim().isEmpty()) animName = "Anim_" + a;

            float ticksPerSec = (float) (aiAnim.mTicksPerSecond() != 0.0 ? aiAnim.mTicksPerSecond() : 25.0);
            float durationSec = (float) (aiAnim.mDuration() / ticksPerSec);

            AnimationClip clip = new AnimationClip(animName, durationSec);
            clip.ticksPerSecond = ticksPerSec;

            int numChannels = aiAnim.mNumChannels();
            for (int c = 0; c < numChannels; c++) {
                AINodeAnim channel = AINodeAnim.create(aiAnim.mChannels().get(c));
                String nodeName = channel.mNodeName().dataString();

                KeyframeTrack track = new KeyframeTrack(nodeName);
                int boneIdx = skeleton.getBoneIndex(nodeName);
                track.boneIndex = boneIdx;

                // Extract Translation Keyframes (Flat 1D float array)
                int numPosKeys = channel.mNumPositionKeys();
                track.positionTimes = new float[numPosKeys];
                track.positions = new float[numPosKeys * 3];
                for (int k = 0; k < numPosKeys; k++) {
                    AIVectorKey posKey = channel.mPositionKeys().get(k);
                    track.positionTimes[k] = (float) (posKey.mTime() / ticksPerSec);
                    AIVector3D val = posKey.mValue();
                    int idx = k * 3;
                    track.positions[idx]     = val.x();
                    track.positions[idx + 1] = val.y();
                    track.positions[idx + 2] = val.z();
                }

                // Extract Rotation Keyframes (Quaternions - Flat 1D float array)
                int numRotKeys = channel.mNumRotationKeys();
                track.rotationTimes = new float[numRotKeys];
                track.rotations = new float[numRotKeys * 4];
                for (int k = 0; k < numRotKeys; k++) {
                    AIQuatKey rotKey = channel.mRotationKeys().get(k);
                    track.rotationTimes[k] = (float) (rotKey.mTime() / ticksPerSec);
                    AIQuaternion val = rotKey.mValue();
                    int idx = k * 4;
                    track.rotations[idx]     = val.x();
                    track.rotations[idx + 1] = val.y();
                    track.rotations[idx + 2] = val.z();
                    track.rotations[idx + 3] = val.w();
                }

                // Extract Scale Keyframes (Flat 1D float array)
                int numScaleKeys = channel.mNumScalingKeys();
                track.scaleTimes = new float[numScaleKeys];
                track.scales = new float[numScaleKeys * 3];
                for (int k = 0; k < numScaleKeys; k++) {
                    AIVectorKey scaleKey = channel.mScalingKeys().get(k);
                    track.scaleTimes[k] = (float) (scaleKey.mTime() / ticksPerSec);
                    AIVector3D val = scaleKey.mValue();
                    int idx = k * 3;
                    track.scales[idx]     = val.x();
                    track.scales[idx + 1] = val.y();
                    track.scales[idx + 2] = val.z();
                }

                clip.addTrack(track);
            }

            animations.add(clip);
        }

        aiReleaseImport(scene);
        return new SkeletonImportResult(skeleton.getBoneCount() > 0 ? skeleton : null, animations);
    }

    private static void buildSkeletonHierarchy(AINode node, int parentBoneId, Skeleton skeleton, Map<String, Integer> boneNameToIndex) {
        String nodeName = node.mName().dataString();
        int currentBoneId = parentBoneId;

        if (boneNameToIndex.containsKey(nodeName)) {
            currentBoneId = boneNameToIndex.get(nodeName);
            Skeleton.BoneNode boneNode = skeleton.getBone(currentBoneId);
            if (boneNode != null) {
                boneNode.parentId = parentBoneId;
                float[] localTransform = convertMatrix(node.mTransformation());
                System.arraycopy(localTransform, 0, boneNode.localBindTransform, 0, 16);
            }
        }

        int numChildren = node.mNumChildren();
        for (int i = 0; i < numChildren; i++) {
            AINode childNode = AINode.create(node.mChildren().get(i));
            buildSkeletonHierarchy(childNode, currentBoneId, skeleton, boneNameToIndex);
        }
    }

    private static float[] convertMatrix(AIMatrix4x4 aiMat) {
        float[] m = new float[16];
        m[0] = aiMat.a1(); m[1] = aiMat.b1(); m[2] = aiMat.c1(); m[3] = aiMat.d1();
        m[4] = aiMat.a2(); m[5] = aiMat.b2(); m[6] = aiMat.c2(); m[7] = aiMat.d2();
        m[8] = aiMat.a3(); m[9] = aiMat.b3(); m[10] = aiMat.c3(); m[11] = aiMat.d3();
        m[12] = aiMat.a4(); m[13] = aiMat.b4(); m[14] = aiMat.c4(); m[15] = aiMat.d4();
        return m;
    }
}
