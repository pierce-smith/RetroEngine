package com.retro.engine.assets.importers;

import com.retro.engine.core.EngineContext;

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

    /**
     * Post-process steps that decide the mesh list and each mesh's bone set (SortByPType splits meshes, LimitBoneWeights
     * drops emptied bones). Both importers must use them so skeleton bone indices match the vertex bone IDs.
     */
    public static final int STRUCTURE_FLAGS = aiProcess_Triangulate | aiProcess_SortByPType
            | aiProcess_JoinIdenticalVertices | aiProcess_LimitBoneWeights;

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

        File inProject = ProjectFileLocator.resolveInProject(rawClean);
        if (inProject != null) return inProject;

        // Exported games resolve from Game_Data/ and never search. The editor searches the open project only
        // (see ProjectFileLocator for why it must not walk user.dir or the home folder).
        if (EngineContext.isRuntimeProcess()) return null;
        return ProjectFileLocator.findByName(new File(clean).getName());
    }


    public static SkeletonImportResult loadSkeletonAndAnimations(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) return null;

        File file = resolveModelFile(filePath);
        if (file == null || !file.exists()) {
            System.err.println("[SkeletonImporter] Model file not found: " + filePath);
            return null;
        }

        String resolvedPath = file.getAbsolutePath();

        AIScene scene = aiImportFile(resolvedPath, STRUCTURE_FLAGS);

        if (scene == null || scene.mRootNode() == null) {
            System.err.println("[SkeletonImporter] Failed to parse scene with Assimp for " + resolvedPath + ": " + aiGetErrorString());
            return null;
        }

        Skeleton skeleton = buildSkeleton(scene, file.getName());
        int numAnimations = scene.mNumAnimations();

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

    /**
     * Builds the skeleton of an imported scene. Bone indices are the order MeshImporter writes into vertex bone IDs,
     * so both importers must build from scenes imported with {@link #STRUCTURE_FLAGS} (which fix the mesh list and
     * each mesh's bone set) and go through this method:
     * 1. skinned bones, in first-seen order across meshes (scene mesh order, then each mesh's bone order), so the bones
     *    that carry vertex weights claim the lowest indices and fit the 64-bone GPU palette first;
     * 2. animated nodes without skin weights (e.g. a parent bone that deforms nothing), in channel order.
     * Parent links and local bind transforms come from the node hierarchy. Non-bone nodes between two bones (FBX pivot
     * helpers, armature nodes) are folded into the child's {@link Skeleton.BoneNode#preTransform} rather than dropped,
     * and the scene root's placement ({@link #sceneRootTransform}) is folded into the top-level bones, so bone worlds are
     * in the same model space the static mesh path bakes (FBX centimeters converted to meters).
     * Offsets are rebased to inverse(bind world): MeshImporter bakes skinned vertices into their bind-pose positions,
     * so the bind palette is identity and the skinned mesh draws exactly where the static path would put it.
     */
    public static Skeleton buildSkeleton(AIScene scene, String fileName) {
        Skeleton skeleton = new Skeleton();
        int numMeshes = scene.mNumMeshes();
        for (int m = 0; m < numMeshes; m++) {
            AIMesh mesh = AIMesh.create(scene.mMeshes().get(m));
            int numBones = mesh.mNumBones();
            for (int b = 0; b < numBones; b++) {
                AIBone bone = AIBone.create(mesh.mBones().get(b));
                String boneName = bone.mName().dataString();
                if (skeleton.getBoneIndex(boneName) < 0) {
                    skeleton.addBone(boneName, -1, null, convertMatrix(bone.mOffsetMatrix()));
                }
            }
        }

        int numAnimations = scene.mNumAnimations();
        for (int a = 0; a < numAnimations; a++) {
            AIAnimation aiAnim = AIAnimation.create(scene.mAnimations().get(a));
            int numChannels = aiAnim.mNumChannels();
            for (int c = 0; c < numChannels; c++) {
                String nodeName = AINodeAnim.create(aiAnim.mChannels().get(c)).mNodeName().dataString();
                if (nodeName != null && !nodeName.isEmpty() && skeleton.getBoneIndex(nodeName) < 0) {
                    skeleton.addBone(nodeName, -1, null, null);
                }
            }
        }

        AINode root = scene.mRootNode();
        if (skeleton.getBoneCount() > 0 && root != null) {
            float[] unitScale = identity();
            unitScale[0] = unitScale[5] = unitScale[10] = fbxUnitScaleToMeters(scene, fileName);
            if (skeleton.getBoneIndex(root.mName().dataString()) >= 0) {
                // The root's transform is its local; only the unit scale sits above it
                buildSkeletonHierarchy(root, -1, unitScale, skeleton);
            } else {
                float[] rootPre = new float[16];
                Skeleton.multiply(unitScale, convertMatrix(root.mTransformation()), rootPre);
                int numChildren = root.mNumChildren();
                for (int i = 0; i < numChildren; i++) {
                    buildSkeletonHierarchy(AINode.create(root.mChildren().get(i)), -1, rootPre, skeleton);
                }
            }
        }

        float[][] bindWorld = skeleton.computeBindWorlds();
        for (int i = 0; i < bindWorld.length; i++) {
            float[] inverse = Skeleton.invertAffine(bindWorld[i]);
            if (inverse != null) System.arraycopy(inverse, 0, skeleton.getBone(i).offsetMatrix, 0, 16);
        }
        return skeleton;
    }

    /**
     * Column-major placement of the scene root in model space: the FBX unit conversion times the root node's
     * transform. Top-level bones and bone-less meshes hang below it.
     */
    static float[] sceneRootTransform(AIScene scene, String fileName) {
        float[] unitScale = identity();
        unitScale[0] = unitScale[5] = unitScale[10] = fbxUnitScaleToMeters(scene, fileName);
        float[] out = new float[16];
        Skeleton.multiply(unitScale, convertMatrix(scene.mRootNode().mTransformation()), out);
        return out;
    }

    /**
     * FBX scenes are usually authored in centimeters (UnitScaleFactor 1) with a compensating 100x node scale. Baking
     * node matrices pulls that scale into the vertices, so convert FBX units to meters. Other formats are unitless.
     */
    static float fbxUnitScaleToMeters(AIScene scene, String fileName) {
        if (fileName == null || !fileName.toLowerCase().endsWith(".fbx")) return 1.0f;
        AIMetaData meta = scene.mMetaData();
        if (meta == null) return 1.0f;
        for (int i = 0; i < meta.mNumProperties(); i++) {
            if (!"UnitScaleFactor".equals(meta.mKeys().get(i).dataString())) continue;
            AIMetaDataEntry entry = meta.mValues().get(i);
            double unit;
            if (entry.mType() == AI_FLOAT) unit = entry.mData(4).getFloat(0);
            else if (entry.mType() == AI_DOUBLE) unit = entry.mData(8).getDouble(0);
            else return 1.0f;
            return unit > 0.0 && Double.isFinite(unit) ? (float) (unit / 100.0) : 1.0f;
        }
        return 1.0f;
    }

    /** {@code pre} is the accumulated transform of the non-bone nodes walked since {@code parentBoneId}. */
    private static void buildSkeletonHierarchy(AINode node, int parentBoneId, float[] pre, Skeleton skeleton) {
        float[] nodeTransform = convertMatrix(node.mTransformation());
        int boneId = skeleton.getBoneIndex(node.mName().dataString());

        int childParent = parentBoneId;
        float[] childPre;
        if (boneId >= 0) {
            Skeleton.BoneNode boneNode = skeleton.getBone(boneId);
            boneNode.parentId = parentBoneId;
            System.arraycopy(nodeTransform, 0, boneNode.localBindTransform, 0, 16);
            boneNode.setPreTransform(pre);
            childParent = boneId;
            childPre = identity();
        } else {
            childPre = new float[16];
            Skeleton.multiply(pre, nodeTransform, childPre);
        }

        int numChildren = node.mNumChildren();
        for (int i = 0; i < numChildren; i++) {
            buildSkeletonHierarchy(AINode.create(node.mChildren().get(i)), childParent, childPre, skeleton);
        }
    }

    private static float[] identity() {
        return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    /** Column-major copy of an Assimp (row-major) matrix. */
    static float[] convertMatrix(AIMatrix4x4 aiMat) {
        float[] m = new float[16];
        m[0] = aiMat.a1(); m[1] = aiMat.b1(); m[2] = aiMat.c1(); m[3] = aiMat.d1();
        m[4] = aiMat.a2(); m[5] = aiMat.b2(); m[6] = aiMat.c2(); m[7] = aiMat.d2();
        m[8] = aiMat.a3(); m[9] = aiMat.b3(); m[10] = aiMat.c3(); m[11] = aiMat.d3();
        m[12] = aiMat.a4(); m[13] = aiMat.b4(); m[14] = aiMat.c4(); m[15] = aiMat.d4();
        return m;
    }
}
