package com.retro.engine.assets.importers;

import com.retro.engine.core.EngineContext;

import com.retro.engine.animation.skeleton.Skeleton;
import com.retro.engine.scene.components.media.AnimatorComponent;
import org.lwjgl.assimp.*;

import java.io.File;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.assimp.Assimp.*;

/**
 * High-performance Assimp model importer enforcing:
 * 1. 22-float vertex stride: [PX, PY, PZ, NX, NY, NZ, R, G, B, A, U1, V1, U2, V2, TX, TY, TZ, BX, BY, BZ, LM_SCALE, LM_BIAS],
 *    extended to a 30-float skinned stride with [BONE_IDX_0..3, BONE_WT_0..3] when the scene contains bones.
 *    Bone indices are the skeleton indices of SkeletonImporter.buildSkeleton, so they address AnimatorComponent palettes
 *    directly; meshes without skin weights are rigidly bound to their nearest bone ancestor. Skinned vertices are
 *    baked in bind pose, so a rig with or without clips draws where the static path would put it.
 * 2. Static geometry walks the AINode hierarchy and bakes each sub-mesh's global node matrix into positions,
 *    normals, and tangent frames, so multi-part vehicles and props assemble correctly.
 * 3. Vertices are read straight from Assimp's native buffers into primitive float arrays; the full-resolution
 *    triangle soup is never materialized on the Java heap.
 * 4. Voxel Grid Vertex Collapse Decimation (Rossignac-Borrel) that coarsens the grid until the output strictly
 *    fits the RenderWare 3.5 budget (4,500 verts), preserving UV seams, normals, and tangent frames.
 */
public class MeshImporter {

    public static final int FLOATS_PER_VERTEX = MeshBufferData.STATIC_FLOATS_PER_VERTEX;
    public static final int RW35_TARGET_MAX_VERTICES = 4500; // RenderWare 3.5 target prop budget

    public static MeshBufferData loadModel(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) return createDefaultCube();

        File file = new File(filePath);
        if (!file.exists()) {
            File resolved = resolveModelFile(filePath);
            if (resolved != null && resolved.exists()) {
                file = resolved;
                filePath = file.getAbsolutePath();
            } else {
                System.err.println("[MeshImporter] File not found: " + filePath + " -> Using default unit cube.");
                return createDefaultCube();
            }
        }

        AIScene scene = null;
        try {
            scene = aiImportFile(filePath,
                    SkeletonImporter.STRUCTURE_FLAGS |
                    aiProcess_GenSmoothNormals |
                    aiProcess_CalcTangentSpace |
                    aiProcess_FlipUVs);

            if (scene == null) {
                System.err.println("[MeshImporter] Assimp failed to parse " + filePath + ": " + aiGetErrorString());
            } else if (scene.mNumMeshes() > 0) {
                int numMeshes = scene.mNumMeshes();
                boolean hasBones = false;
                for (int i = 0; i < numMeshes; i++) {
                    if (AIMesh.create(scene.mMeshes().get(i)).mNumBones() > 0) {
                        hasBones = true;
                        break;
                    }
                }
                // Rigs without clips are skinned too, so editor pose edits and recorded keys deform them
                boolean skinned = hasBones;
                Skeleton skeleton = null;

                List<MeshInstance> instances = new ArrayList<>();
                if (skinned) {
                    // Skinned vertices are baked into their bind-pose model-space positions (as the static path would
                    // place them) and the skeleton's offsets are inverse bind worlds, so the bind palette is identity.
                    // Bone IDs are the indices of the skeleton SkeletonImporter builds from the same scene structure.
                    skeleton = SkeletonImporter.buildSkeleton(scene, file.getName());
                    float[][] bindWorld = skeleton.computeBindWorlds();
                    RigidBinding[] rigid = bindBonelessMeshes(scene, skeleton, file.getName());
                    for (int i = 0; i < numMeshes; i++) {
                        RigidBinding binding = rigid[i];
                        AIMesh mesh = AIMesh.create(scene.mMeshes().get(i));
                        float[] matrix = (binding != null) ? binding.matrix : bindPoseMatrix(mesh, skeleton, bindWorld);
                        MeshInstance inst = new MeshInstance(mesh, matrix);
                        inst.boneIds = new int[inst.numVertices * MeshBufferData.MAX_BONE_INFLUENCES];
                        inst.boneWeights = new float[inst.numVertices * MeshBufferData.MAX_BONE_INFLUENCES];
                        if (binding == null) {
                            gatherBoneInfluences(inst.mesh, skeleton, inst.boneIds, inst.boneWeights);
                        } else if (binding.boneIndex >= 0) {
                            for (int v = 0; v < inst.numVertices; v++) {
                                inst.boneIds[v * MeshBufferData.MAX_BONE_INFLUENCES] = binding.boneIndex;
                                inst.boneWeights[v * MeshBufferData.MAX_BONE_INFLUENCES] = 1.0f;
                            }
                        }
                        instances.add(inst);
                    }
                } else if (scene.mRootNode() != null) {
                    float unitScale = SkeletonImporter.fbxUnitScaleToMeters(scene, file.getName());
                    float[] root = identityMatrix();
                    root[0] = root[5] = root[10] = unitScale;
                    collectNodeInstances(scene, scene.mRootNode(), root, instances);
                }
                if (instances.isEmpty()) {
                    for (int i = 0; i < numMeshes; i++) {
                        instances.add(new MeshInstance(AIMesh.create(scene.mMeshes().get(i)), null));
                    }
                }

                AssimpSource source = new AssimpSource(instances, skinned);
                MeshBufferData data = MeshDecimator.buildOrDecimate(source, RW35_TARGET_MAX_VERTICES);
                if (data != null) {
                    System.out.println("[MeshImporter] Successfully loaded 3D mesh " + file.getName() + " with " + data.vertexCount
                            + " vertices from " + instances.size() + " sub-meshes (" + source.stride + "-float stride"
                            + (skinned ? ", " + skeleton.getBoneCount() + " bones" : "") + ").");
                    return data;
                }
            }
        } catch (Throwable t) {
            System.err.println("[MeshImporter] Assimp exception on " + filePath + ": " + t);
            t.printStackTrace();
        } finally {
            if (scene != null) aiReleaseImport(scene);
        }

        return createDefaultCube();
    }

    /** Public entry for pre-expanded triangle soups (3 consecutive vertices per face). */
    public static MeshBufferData decimateToRW35Target(float[] rawVerts, int rawCount, int maxTargetVerts) {
        if (rawCount <= maxTargetVerts) return new MeshBufferData(rawVerts, rawCount);
        MeshBufferData data = MeshDecimator.decimate(new MeshDecimator.SoupSource(rawVerts, rawCount), maxTargetVerts, true);
        return data != null ? data : new MeshBufferData(rawVerts, rawCount);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Scene graph traversal
    // ------------------------------------------------------------------------------------------------------------

    private static void collectNodeInstances(AIScene scene, AINode node, float[] parentGlobal, List<MeshInstance> out) {
        float[] global = multiply(parentGlobal, toRowMajor(node.mTransformation()));
        IntBuffer meshIndices = node.mMeshes();
        int numNodeMeshes = node.mNumMeshes();
        for (int i = 0; i < numNodeMeshes && meshIndices != null; i++) {
            int meshIndex = meshIndices.get(i);
            if (meshIndex < 0 || meshIndex >= scene.mNumMeshes()) continue;
            out.add(new MeshInstance(AIMesh.create(scene.mMeshes().get(meshIndex)), global));
        }
        int numChildren = node.mNumChildren();
        for (int i = 0; i < numChildren && node.mChildren() != null; i++) {
            collectNodeInstances(scene, AINode.create(node.mChildren().get(i)), global, out);
        }
    }

    private static float[] identityMatrix() {
        return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static float[] toRowMajor(AIMatrix4x4 m) {
        return new float[] {
                m.a1(), m.a2(), m.a3(), m.a4(),
                m.b1(), m.b2(), m.b3(), m.b4(),
                m.c1(), m.c2(), m.c3(), m.c4(),
                m.d1(), m.d2(), m.d3(), m.d4()
        };
    }

    private static float[] multiply(float[] a, float[] b) {
        float[] r = new float[16];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                r[row * 4 + col] = a[row * 4] * b[col] + a[row * 4 + 1] * b[4 + col]
                        + a[row * 4 + 2] * b[8 + col] + a[row * 4 + 3] * b[12 + col];
            }
        }
        return r;
    }

    /** One placement of an AIMesh in the scene, with cached native attribute buffers and its baked transform. */
    private static final class MeshInstance {
        final AIMesh mesh;
        final AIVector3D.Buffer vertices, normals, uvs, lightmapUvs, tangents, bitangents;
        final AIFace.Buffer faces;
        final int numVertices, numFaces;
        final float[] matrix;       // row-major 4x4, null for identity
        final float[] normalMatrix; // cofactor (inverse-transpose up to scale) of the upper 3x3
        final boolean flipWinding;  // mirrored transforms reverse triangle winding
        int[] boneIds;
        float[] boneWeights;

        MeshInstance(AIMesh mesh, float[] matrix) {
            this.mesh = mesh;
            this.vertices = mesh.mVertices();
            this.normals = mesh.mNormals();
            this.uvs = mesh.mTextureCoords(0);
            this.lightmapUvs = mesh.mTextureCoords(1);
            this.tangents = mesh.mTangents();
            this.bitangents = mesh.mBitangents();
            this.faces = mesh.mFaces();
            this.numVertices = vertices != null ? Math.min(mesh.mNumVertices(), vertices.remaining()) : 0;
            this.numFaces = faces != null ? Math.min(mesh.mNumFaces(), faces.remaining()) : 0;

            boolean identity = matrix == null || Arrays.equals(matrix, identityMatrix());
            this.matrix = identity ? null : matrix;
            if (identity) {
                this.normalMatrix = null;
                this.flipWinding = false;
            } else {
                float a = matrix[0], b = matrix[1], c = matrix[2];
                float d = matrix[4], e = matrix[5], f = matrix[6];
                float g = matrix[8], h = matrix[9], i = matrix[10];
                float c00 = e * i - f * h, c01 = -(d * i - f * g), c02 = d * h - e * g;
                float det = a * c00 + b * c01 + c * c02;
                float s = det < 0.0f ? -1.0f : 1.0f;
                this.normalMatrix = new float[] {
                        s * c00, s * c01, s * c02,
                        s * -(b * i - c * h), s * (a * i - c * g), s * -(a * h - b * g),
                        s * (b * f - c * e), s * -(a * f - c * d), s * (a * e - b * d)
                };
                this.flipWinding = det < 0.0f;
            }
        }
    }

    /** Streams vertices from Assimp's native buffers, applying each instance's node transform on the fly. */
    private static final class AssimpSource implements MeshDecimator.GroupedSource {
        final MeshInstance[] instances;
        final int[] vertexBase, triangleBase;
        final int vertexCount, triangleCount, stride;
        final boolean skinned;

        AssimpSource(List<MeshInstance> list, boolean skinned) {
            this.instances = list.toArray(new MeshInstance[0]);
            this.skinned = skinned;
            this.stride = skinned ? MeshBufferData.SKINNED_FLOATS_PER_VERTEX : MeshBufferData.STATIC_FLOATS_PER_VERTEX;
            this.vertexBase = new int[instances.length];
            this.triangleBase = new int[instances.length];
            long verts = 0, tris = 0;
            for (int i = 0; i < instances.length; i++) {
                vertexBase[i] = (int) verts;
                triangleBase[i] = (int) tris;
                verts += instances[i].numVertices;
                tris += instances[i].numFaces;
            }
            if (verts > Integer.MAX_VALUE || tris > Integer.MAX_VALUE) {
                throw new IllegalStateException("Model exceeds addressable vertex/face count");
            }
            this.vertexCount = (int) verts;
            this.triangleCount = (int) tris;
        }

        public int vertexCount() { return vertexCount; }
        public int triangleCount() { return triangleCount; }
        public int stride() { return stride; }

        /** Largest instance index whose base is <= x (skips empty instances sharing the same base). */
        private static int find(int[] bases, int x) {
            int lo = 0, hi = bases.length - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (bases[mid] <= x) lo = mid; else hi = mid - 1;
            }
            return lo;
        }

        public boolean triangle(int t, int[] out) {
            int i = find(triangleBase, t);
            MeshInstance inst = instances[i];
            AIFace face = inst.faces.get(t - triangleBase[i]);
            if (face.mNumIndices() != 3) return false;
            IntBuffer idx = face.mIndices();
            int a = idx.get(0), b = idx.get(1), c = idx.get(2);
            int n = inst.numVertices;
            if (a < 0 || a >= n || b < 0 || b >= n || c < 0 || c >= n) return false;
            int base = vertexBase[i];
            out[0] = base + a;
            out[1] = base + (inst.flipWinding ? c : b);
            out[2] = base + (inst.flipWinding ? b : c);
            return true;
        }

        public int groupCount() { return instances.length; }

        public MeshDecimator.VertexSource group(int i) {
            return new MeshDecimator.SubSource(this, vertexBase[i], instances[i].numVertices, triangleBase[i], instances[i].numFaces);
        }

        public void position(int v, float[] out) {
            int i = find(vertexBase, v);
            AIVector3D pos = instances[i].vertices.get(v - vertexBase[i]);
            float px = pos.x(), py = pos.y(), pz = pos.z();
            float[] m = instances[i].matrix;
            if (m == null) {
                out[0] = px; out[1] = py; out[2] = pz;
            } else {
                out[0] = m[0] * px + m[1] * py + m[2]  * pz + m[3];
                out[1] = m[4] * px + m[5] * py + m[6]  * pz + m[7];
                out[2] = m[8] * px + m[9] * py + m[10] * pz + m[11];
            }
        }

        public void read(int v, float[] out, int o) {
            int i = find(vertexBase, v);
            MeshInstance inst = instances[i];
            int index = v - vertexBase[i];

            AIVector3D pos = inst.vertices.get(index);
            float px = pos.x(), py = pos.y(), pz = pos.z();

            float nx = 0.0f, ny = 1.0f, nz = 0.0f;
            if (inst.normals != null && index < inst.normals.remaining()) {
                AIVector3D n = inst.normals.get(index);
                nx = n.x(); ny = n.y(); nz = n.z();
            }

            float u1 = 0.0f, v1 = 0.0f;
            if (inst.uvs != null && index < inst.uvs.remaining()) {
                AIVector3D uv = inst.uvs.get(index);
                u1 = uv.x(); v1 = uv.y();
            }

            float u2 = u1, v2 = v1;
            if (inst.lightmapUvs != null && index < inst.lightmapUvs.remaining()) {
                AIVector3D uv = inst.lightmapUvs.get(index);
                u2 = uv.x(); v2 = uv.y();
            }

            float tx = 1.0f, ty = 0.0f, tz = 0.0f;
            if (inst.tangents != null && index < inst.tangents.remaining()) {
                AIVector3D t = inst.tangents.get(index);
                tx = t.x(); ty = t.y(); tz = t.z();
            }

            float bx = 0.0f, by = 1.0f, bz = 0.0f;
            if (inst.bitangents != null && index < inst.bitangents.remaining()) {
                AIVector3D b = inst.bitangents.get(index);
                bx = b.x(); by = b.y(); bz = b.z();
            }

            out[o] = px; out[o + 1] = py; out[o + 2] = pz;
            out[o + 3] = nx; out[o + 4] = ny; out[o + 5] = nz;
            out[o + 6] = 1.0f; out[o + 7] = 1.0f; out[o + 8] = 1.0f; out[o + 9] = 1.0f; // clean neutral white base color
            out[o + 10] = u1; out[o + 11] = v1;
            out[o + 12] = u2; out[o + 13] = v2;
            out[o + 14] = tx; out[o + 15] = ty; out[o + 16] = tz;
            out[o + 17] = bx; out[o + 18] = by; out[o + 19] = bz;
            out[o + 20] = 1.0f; out[o + 21] = 0.0f; // lightmap scale, bias

            float[] m = inst.matrix;
            if (m != null) {
                out[o]     = m[0] * px + m[1] * py + m[2]  * pz + m[3];
                out[o + 1] = m[4] * px + m[5] * py + m[6]  * pz + m[7];
                out[o + 2] = m[8] * px + m[9] * py + m[10] * pz + m[11];
                transform3(inst.normalMatrix, 3, out, o + 3);
                transform3(m, 4, out, o + 14);
                transform3(m, 4, out, o + 17);
            }

            if (skinned) {
                int base = index * MeshBufferData.MAX_BONE_INFLUENCES;
                for (int w = 0; w < MeshBufferData.MAX_BONE_INFLUENCES; w++) {
                    out[o + MeshBufferData.BONE_INDEX_OFFSET + w] = Float.intBitsToFloat(inst.boneIds[base + w]);
                    out[o + MeshBufferData.BONE_WEIGHT_OFFSET + w] = inst.boneWeights[base + w];
                }
            }
        }

        /** Multiplies the direction at out[o..o+2] by the upper-left 3x3 of row-major m (row length rowStep) and renormalizes. */
        private static void transform3(float[] m, int rowStep, float[] out, int o) {
            float x = out[o], y = out[o + 1], z = out[o + 2];
            out[o]     = m[0] * x + m[1] * y + m[2] * z;
            out[o + 1] = m[rowStep] * x + m[rowStep + 1] * y + m[rowStep + 2] * z;
            out[o + 2] = m[2 * rowStep] * x + m[2 * rowStep + 1] * y + m[2 * rowStep + 2] * z;
            MeshDecimator.normalize3(out, o);
        }
    }


    /**
     * Collects the strongest MAX_BONE_INFLUENCES (bone, weight) pairs per vertex and renormalizes them to sum to 1.
     * Bone IDs are the bone's index in {@code skeleton}; influences on bones past the GPU palette are dropped.
     */
    private static void gatherBoneInfluences(AIMesh mesh, Skeleton skeleton, int[] boneIds, float[] boneWeights) {
        final int maxInf = MeshBufferData.MAX_BONE_INFLUENCES;
        int numVertices = mesh.mNumVertices();
        int numBones = mesh.mNumBones();
        boolean dropped = false;

        for (int b = 0; b < numBones; b++) {
            AIBone bone = AIBone.create(mesh.mBones().get(b));
            int boneIndex = skeleton.getBoneIndex(bone.mName().dataString());
            if (boneIndex < 0 || boneIndex >= AnimatorComponent.MAX_BONES) {
                dropped |= bone.mNumWeights() > 0;
                continue;
            }

            AIVertexWeight.Buffer weights = bone.mWeights();
            int numWeights = bone.mNumWeights();
            for (int w = 0; w < numWeights; w++) {
                AIVertexWeight vw = weights.get(w);
                int vertexId = vw.mVertexId();
                float weight = vw.mWeight();
                if (vertexId < 0 || vertexId >= numVertices || weight <= 0.0f) continue;

                // Replace the weakest slot if this influence is stronger
                int base = vertexId * maxInf;
                int weakest = base;
                for (int s = base + 1; s < base + maxInf; s++) {
                    if (boneWeights[s] < boneWeights[weakest]) weakest = s;
                }
                if (weight > boneWeights[weakest]) {
                    boneWeights[weakest] = weight;
                    boneIds[weakest] = boneIndex;
                }
            }
        }

        if (dropped) {
            System.err.println("[MeshImporter] Skeleton has " + skeleton.getBoneCount() + " bones; only the first "
                    + AnimatorComponent.MAX_BONES + " fit the GPU palette. Out-of-range influences are dropped.");
        }

        for (int v = 0; v < numVertices; v++) {
            int base = v * maxInf;
            float total = 0.0f;
            for (int s = base; s < base + maxInf; s++) total += boneWeights[s];
            if (total > 0.0f) {
                float inv = 1.0f / total;
                for (int s = base; s < base + maxInf; s++) boneWeights[s] *= inv;
            }
        }
    }

    /**
     * The row-major matrix that bakes a skinned mesh's vertices into bind-pose model space: the palette Assimp's data
     * gives its first bone at bind, bind world * that mesh's own offset for the bone. Assimp stores offsets per mesh
     * (inverse bone bind * the mesh's node), so this also places each mesh by its own node, not the first mesh's.
     */
    private static float[] bindPoseMatrix(AIMesh mesh, Skeleton skeleton, float[][] bindWorld) {
        for (int b = 0; b < mesh.mNumBones(); b++) {
            AIBone bone = AIBone.create(mesh.mBones().get(b));
            int index = skeleton.getBoneIndex(bone.mName().dataString());
            if (index < 0) continue;
            float[] bind = new float[16];
            Skeleton.multiply(bindWorld[index], SkeletonImporter.convertMatrix(bone.mOffsetMatrix()), bind);
            return transpose(bind);
        }
        return null;
    }

    /** How a mesh without skin weights is placed in a skinned buffer: a baked matrix plus an optional rigid bone. */
    private record RigidBinding(float[] matrix, int boneIndex) {}

    /**
     * A mesh without skin weights (a prop parented under a hand bone, or a static part beside the rig) is baked at its
     * node's bind-pose placement and rigidly bound (weight 1) to its nearest bone ancestor, so it follows that bone;
     * with no bone ancestor it stays put. The bind palette is identity, so no inverse is needed.
     * Returns one entry per scene mesh, null for meshes that carry skin weights.
     */
    private static RigidBinding[] bindBonelessMeshes(AIScene scene, Skeleton skeleton, String fileName) {
        int numMeshes = scene.mNumMeshes();
        RigidBinding[] out = new RigidBinding[numMeshes];
        boolean any = false;
        for (int i = 0; i < numMeshes; i++) any |= AIMesh.create(scene.mMeshes().get(i)).mNumBones() == 0;
        if (!any || scene.mRootNode() == null) return out;

        // The root's transform (and the FBX unit scale) is the starting placement, as for the skeleton's top-level bones
        AINode root = scene.mRootNode();
        bindNodeMeshes(scene, root, SkeletonImporter.sceneRootTransform(scene, fileName), -1, skeleton, out, true);
        return out;
    }

    /**
     * {@code parentGlobal} is the node's parent placement (column-major; for the root, the root's own placement);
     * the first node referencing a mesh wins.
     */
    private static void bindNodeMeshes(AIScene scene, AINode node, float[] parentGlobal, int boneAncestor, Skeleton skeleton,
                                       RigidBinding[] out, boolean isRoot) {
        float[] global = parentGlobal;
        if (!isRoot) {
            global = new float[16];
            Skeleton.multiply(parentGlobal, SkeletonImporter.convertMatrix(node.mTransformation()), global);
        }
        int boneIndex = skeleton.getBoneIndex(node.mName().dataString());
        if (boneIndex >= 0) boneAncestor = boneIndex;

        IntBuffer meshIndices = node.mMeshes();
        for (int i = 0; i < node.mNumMeshes() && meshIndices != null; i++) {
            int meshIndex = meshIndices.get(i);
            if (meshIndex < 0 || meshIndex >= out.length || out[meshIndex] != null) continue;
            if (AIMesh.create(scene.mMeshes().get(meshIndex)).mNumBones() > 0) continue;

            int rigidBone = (boneAncestor >= 0 && boneAncestor < AnimatorComponent.MAX_BONES) ? boneAncestor : -1;
            out[meshIndex] = new RigidBinding(transpose(global), rigidBone);
        }

        int numChildren = node.mNumChildren();
        for (int i = 0; i < numChildren && node.mChildren() != null; i++) {
            bindNodeMeshes(scene, AINode.create(node.mChildren().get(i)), global, boneAncestor, skeleton, out, false);
        }
    }

    /** Column-major <-> row-major (MeshInstance matrices are row-major). */
    private static float[] transpose(float[] m) {
        float[] t = new float[16];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) t[row * 4 + col] = m[col * 4 + row];
        }
        return t;
    }

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


    public static MeshBufferData createDefaultCube() {
        float[] cubeVerts = new float[] {
            // Front (+Z)
            -1f,-1f, 1f,  0f,0f,1f,  0.9f,0.3f,0.2f,1f,  0f,1f,  0f,1f,  1f,0f,0f,  0f,1f,0f,  1f,0f,
             1f,-1f, 1f,  0f,0f,1f,  0.9f,0.3f,0.2f,1f,  1f,1f,  1f,1f,  1f,0f,0f,  0f,1f,0f,  1f,0f,
             1f, 1f, 1f,  0f,0f,1f,  0.9f,0.3f,0.2f,1f,  1f,0f,  1f,0f,  1f,0f,0f,  0f,1f,0f,  1f,0f,
            -1f,-1f, 1f,  0f,0f,1f,  0.9f,0.3f,0.2f,1f,  0f,1f,  0f,1f,  1f,0f,0f,  0f,1f,0f,  1f,0f,
             1f, 1f, 1f,  0f,0f,1f,  0.9f,0.3f,0.2f,1f,  1f,0f,  1f,0f,  1f,0f,0f,  0f,1f,0f,  1f,0f,
            -1f, 1f, 1f,  0f,0f,1f,  0.9f,0.3f,0.2f,1f,  0f,0f,  0f,0f,  1f,0f,0f,  0f,1f,0f,  1f,0f,

            // Back (-Z)
             1f,-1f,-1f,  0f,0f,-1f, 0.2f,0.8f,0.9f,1f,  0f,1f,  0f,1f, -1f,0f,0f,  0f,1f,0f,  1f,0f,
            -1f,-1f,-1f,  0f,0f,-1f, 0.2f,0.8f,0.9f,1f,  1f,1f,  1f,1f, -1f,0f,0f,  0f,1f,0f,  1f,0f,
            -1f, 1f,-1f,  0f,0f,-1f, 0.2f,0.8f,0.9f,1f,  1f,0f,  1f,0f, -1f,0f,0f,  0f,1f,0f,  1f,0f,
             1f,-1f,-1f,  0f,0f,-1f, 0.2f,0.8f,0.9f,1f,  0f,1f,  0f,1f, -1f,0f,0f,  0f,1f,0f,  1f,0f,
            -1f, 1f,-1f,  0f,0f,-1f, 0.2f,0.8f,0.9f,1f,  1f,0f,  1f,0f, -1f,0f,0f,  0f,1f,0f,  1f,0f,
             1f, 1f,-1f,  0f,0f,-1f, 0.2f,0.8f,0.9f,1f,  0f,0f,  0f,0f, -1f,0f,0f,  0f,1f,0f,  1f,0f,

            // Top (+Y)
            -1f, 1f, 1f,  0f,1f,0f,  0.2f,0.9f,0.3f,1f,  0f,1f,  0f,1f,  1f,0f,0f,  0f,0f,-1f, 1f,0f,
             1f, 1f, 1f,  0f,1f,0f,  0.2f,0.9f,0.3f,1f,  1f,1f,  1f,1f,  1f,0f,0f,  0f,0f,-1f, 1f,0f,
             1f, 1f,-1f,  0f,1f,0f,  0.2f,0.9f,0.3f,1f,  1f,0f,  1f,0f,  1f,0f,0f,  0f,0f,-1f, 1f,0f,
            -1f, 1f, 1f,  0f,1f,0f,  0.2f,0.9f,0.3f,1f,  0f,1f,  0f,1f,  1f,0f,0f,  0f,0f,-1f, 1f,0f,
             1f, 1f,-1f,  0f,1f,0f,  0.2f,0.9f,0.3f,1f,  1f,0f,  1f,0f,  1f,0f,0f,  0f,0f,-1f, 1f,0f,
            -1f, 1f,-1f,  0f,1f,0f,  0.2f,0.9f,0.3f,1f,  0f,0f,  0f,0f,  1f,0f,0f,  0f,0f,-1f, 1f,0f,

            // Bottom (-Y)
            -1f,-1f,-1f,  0f,-1f,0f, 0.2f,0.3f,0.9f,1f,  0f,1f,  0f,1f,  1f,0f,0f,  0f,0f,1f,  1f,0f,
             1f,-1f,-1f,  0f,-1f,0f, 0.2f,0.3f,0.9f,1f,  1f,1f,  1f,1f,  1f,0f,0f,  0f,0f,1f,  1f,0f,
             1f,-1f, 1f,  0f,-1f,0f, 0.2f,0.3f,0.9f,1f,  1f,0f,  1f,0f,  1f,0f,0f,  0f,0f,1f,  1f,0f,
            -1f,-1f,-1f,  0f,-1f,0f, 0.2f,0.3f,0.9f,1f,  0f,1f,  0f,1f,  1f,0f,0f,  0f,0f,1f,  1f,0f,
             1f,-1f, 1f,  0f,-1f,0f, 0.2f,0.3f,0.9f,1f,  1f,0f,  1f,0f,  1f,0f,0f,  0f,0f,1f,  1f,0f,
            -1f,-1f, 1f,  0f,-1f,0f, 0.2f,0.3f,0.9f,1f,  0f,0f,  0f,0f,  1f,0f,0f,  0f,0f,1f,  1f,0f,

            // Left (-X)
            -1f,-1f,-1f, -1f,0f,0f,  0.9f,0.8f,0.2f,1f,  0f,1f,  0f,1f,  0f,0f,1f,  0f,1f,0f,  1f,0f,
            -1f,-1f, 1f, -1f,0f,0f,  0.9f,0.8f,0.2f,1f,  1f,1f,  1f,1f,  0f,0f,1f,  0f,1f,0f,  1f,0f,
            -1f, 1f, 1f, -1f,0f,0f,  0.9f,0.8f,0.2f,1f,  1f,0f,  1f,0f,  0f,0f,1f,  0f,1f,0f,  1f,0f,
            -1f,-1f,-1f, -1f,0f,0f,  0.9f,0.8f,0.2f,1f,  0f,1f,  0f,1f,  0f,0f,1f,  0f,1f,0f,  1f,0f,
            -1f, 1f, 1f, -1f,0f,0f,  0.9f,0.8f,0.2f,1f,  1f,0f,  1f,0f,  0f,0f,1f,  0f,1f,0f,  1f,0f,
            -1f, 1f,-1f, -1f,0f,0f,  0.9f,0.8f,0.2f,1f,  0f,0f,  0f,0f,  0f,0f,1f,  0f,1f,0f,  1f,0f,

            // Right (+X)
             1f,-1f, 1f,  1f,0f,0f,  0.8f,0.2f,0.9f,1f,  0f,1f,  0f,1f,  0f,0f,-1f, 0f,1f,0f,  1f,0f,
             1f,-1f,-1f,  1f,0f,0f,  0.8f,0.2f,0.9f,1f,  1f,1f,  1f,1f,  0f,0f,-1f, 0f,1f,0f,  1f,0f,
             1f, 1f,-1f,  1f,0f,0f,  0.8f,0.2f,0.9f,1f,  1f,0f,  1f,0f,  0f,0f,-1f, 0f,1f,0f,  1f,0f,
             1f,-1f, 1f,  1f,0f,0f,  0.8f,0.2f,0.9f,1f,  0f,1f,  0f,1f,  0f,0f,-1f, 0f,1f,0f,  1f,0f,
             1f, 1f,-1f,  1f,0f,0f,  0.8f,0.2f,0.9f,1f,  1f,0f,  1f,0f,  0f,0f,-1f, 0f,1f,0f,  1f,0f,
             1f, 1f, 1f,  1f,0f,0f,  0.8f,0.2f,0.9f,1f,  0f,0f,  0f,0f,  0f,0f,-1f, 0f,1f,0f,  1f,0f
        };
        return new MeshBufferData(cubeVerts, cubeVerts.length / FLOATS_PER_VERTEX);
    }
}
