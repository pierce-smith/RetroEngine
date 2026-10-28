package com.retro.engine.assets.importers;

import com.retro.engine.scene.components.media.AnimatorComponent;
import org.lwjgl.assimp.*;

import java.io.File;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.assimp.Assimp.*;

/**
 * High-performance Assimp model importer enforcing:
 * 1. 22-float vertex stride: [PX, PY, PZ, NX, NY, NZ, R, G, B, A, U1, V1, U2, V2, TX, TY, TZ, BX, BY, BZ, LM_SCALE, LM_BIAS],
 *    extended to a 30-float skinned stride with [BONE_IDX_0..3, BONE_WT_0..3] when the scene contains bones and animations.
 *    Bone indices follow SkeletonImporter's first-seen bone order so they address AnimatorComponent palettes directly.
 *    Rigged scenes without animation clips are baked as static 22-float geometry in bind pose.
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
                    aiProcess_Triangulate |
                    aiProcess_SortByPType |
                    aiProcess_GenSmoothNormals |
                    aiProcess_CalcTangentSpace |
                    aiProcess_JoinIdenticalVertices |
                    aiProcess_LimitBoneWeights |
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
                // Without animation clips the bone palette never leaves identity, so a skinned buffer would draw
                // every part in its own mesh-local space. Bake those rigs as static geometry in bind pose instead.
                boolean skinned = hasBones && scene.mNumAnimations() > 0;
                Map<String, Integer> boneNameToIndex = new HashMap<>();

                List<MeshInstance> instances = new ArrayList<>();
                if (skinned) {
                    // Skinned vertices stay in mesh space; the bone palette carries each part's placement. Meshes are
                    // walked in scene order so bone indices match SkeletonImporter.
                    for (int i = 0; i < numMeshes; i++) {
                        MeshInstance inst = new MeshInstance(AIMesh.create(scene.mMeshes().get(i)), null);
                        inst.boneIds = new int[inst.numVertices * MeshBufferData.MAX_BONE_INFLUENCES];
                        inst.boneWeights = new float[inst.numVertices * MeshBufferData.MAX_BONE_INFLUENCES];
                        gatherBoneInfluences(inst.mesh, boneNameToIndex, inst.boneIds, inst.boneWeights);
                        instances.add(inst);
                    }
                } else if (scene.mRootNode() != null) {
                    float unitScale = fbxUnitScaleToMeters(scene, file.getName());
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
                            + (skinned ? ", " + boneNameToIndex.size() + " bones" : hasBones ? ", static bind pose" : "") + ").");
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

    /**
     * FBX scenes are usually authored in centimeters (UnitScaleFactor 1) with a compensating 100x node scale. Baking
     * node matrices pulls that scale into the vertices, so convert FBX units to meters. Other formats are unitless.
     */
    private static float fbxUnitScaleToMeters(AIScene scene, String fileName) {
        if (!fileName.toLowerCase().endsWith(".fbx")) return 1.0f;
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
     * Bone indices are assigned in first-seen order across meshes, matching SkeletonImporter's skeleton layout.
     */
    private static void gatherBoneInfluences(AIMesh mesh, Map<String, Integer> boneNameToIndex, int[] boneIds, float[] boneWeights) {
        final int maxInf = MeshBufferData.MAX_BONE_INFLUENCES;
        int numVertices = mesh.mNumVertices();
        int numBones = mesh.mNumBones();

        for (int b = 0; b < numBones; b++) {
            AIBone bone = AIBone.create(mesh.mBones().get(b));
            String boneName = bone.mName().dataString();
            Integer boneIndex = boneNameToIndex.get(boneName);
            if (boneIndex == null) {
                boneIndex = boneNameToIndex.size();
                boneNameToIndex.put(boneName, boneIndex);
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

        if (boneNameToIndex.size() > AnimatorComponent.MAX_BONES) {
            System.err.println("[MeshImporter] Skeleton has " + boneNameToIndex.size() + " bones; only the first "
                    + AnimatorComponent.MAX_BONES + " fit the GPU palette. Out-of-range influences are dropped.");
        }

        for (int v = 0; v < numVertices; v++) {
            int base = v * maxInf;
            float total = 0.0f;
            for (int s = base; s < base + maxInf; s++) {
                if (boneIds[s] >= AnimatorComponent.MAX_BONES) {
                    boneIds[s] = 0;
                    boneWeights[s] = 0.0f;
                }
                total += boneWeights[s];
            }
            if (total > 0.0f) {
                float inv = 1.0f / total;
                for (int s = base; s < base + maxInf; s++) boneWeights[s] *= inv;
            }
        }
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
