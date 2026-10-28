package com.retro.engine.assets.importers;

import com.retro.engine.scene.components.media.AnimatorComponent;
import org.lwjgl.assimp.*;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.assimp.Assimp.*;

/**
 * High-performance Assimp model importer enforcing:
 * 1. 22-float vertex stride: [PX, PY, PZ, NX, NY, NZ, R, G, B, A, U1, V1, U2, V2, TX, TY, TZ, BX, BY, BZ, LM_SCALE, LM_BIAS],
 *    extended to a 30-float skinned stride with [BONE_IDX_0..3, BONE_WT_0..3] when the scene contains bones.
 *    Bone indices follow SkeletonImporter's first-seen bone order so they address AnimatorComponent palettes directly.
 * 2. Clean neutral base material color (no debug rainbow RGB normal packing).
 * 3. Safe Assimp buffer limits checking to prevent IndexOutOfBoundsException fallback to cube.
 * 4. True Voxel Grid Vertex Collapse Decimation (Rossignac-Borrel Algorithm) preserving
 *    closed 3D surface geometry, normals, UVs, and Tangents/Bitangents for RenderWare 3.5 target budgets (~4,500 verts).
 */
public class MeshImporter {

    public static final int FLOATS_PER_VERTEX = MeshBufferData.STATIC_FLOATS_PER_VERTEX;
    public static final int RW35_TARGET_MAX_VERTICES = 4500; // RenderWare 3.5 target prop budget
    // UV bins per axis for decimation clustering. Vertices whose UVs fall in different bins are never merged, which
    // keeps texture seams and atlas boundaries intact. Finer bins catch narrower seams but split more cells inside
    // a continuous UV island.
    private static final int UV_SEAM_BINS = 16;

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

        try {
            AIScene scene = aiImportFile(filePath,
                    aiProcess_Triangulate |
                    aiProcess_GenSmoothNormals |
                    aiProcess_CalcTangentSpace |
                    aiProcess_JoinIdenticalVertices |
                    aiProcess_LimitBoneWeights |
                    aiProcess_FlipUVs);

            if (scene != null && scene.mNumMeshes() > 0) {
                List<Float> vertexBuffer = new ArrayList<>();
                int numMeshes = scene.mNumMeshes();

                boolean skinned = false;
                for (int i = 0; i < numMeshes; i++) {
                    if (AIMesh.create(scene.mMeshes().get(i)).mNumBones() > 0) {
                        skinned = true;
                        break;
                    }
                }
                int stride = skinned ? MeshBufferData.SKINNED_FLOATS_PER_VERTEX : MeshBufferData.STATIC_FLOATS_PER_VERTEX;
                Map<String, Integer> boneNameToIndex = new HashMap<>();

                for (int i = 0; i < numMeshes; i++) {
                    AIMesh mesh = AIMesh.create(scene.mMeshes().get(i));
                    AIVector3D.Buffer vertices = mesh.mVertices();
                    AIVector3D.Buffer normals = mesh.mNormals();
                    AIVector3D.Buffer uvs = mesh.mTextureCoords(0);
                    AIVector3D.Buffer lightmapUvs = mesh.mTextureCoords(1);
                    AIVector3D.Buffer tangents = mesh.mTangents();
                    AIVector3D.Buffer bitangents = mesh.mBitangents();

                    int numVertices = mesh.mNumVertices();
                    AIFace.Buffer faces = mesh.mFaces();
                    int numFaces = mesh.mNumFaces();

                    int[] boneIds = null;
                    float[] boneWeights = null;
                    if (skinned) {
                        boneIds = new int[numVertices * MeshBufferData.MAX_BONE_INFLUENCES];
                        boneWeights = new float[numVertices * MeshBufferData.MAX_BONE_INFLUENCES];
                        gatherBoneInfluences(mesh, boneNameToIndex, boneIds, boneWeights);
                    }

                    for (int f = 0; f < numFaces; f++) {
                        AIFace aiFace = faces.get(f);
                        if (aiFace.mNumIndices() == 3) {
                            for (int k = 0; k < 3; k++) {
                                int index = aiFace.mIndices().get(k);
                                if (index < 0 || index >= numVertices) continue;

                                AIVector3D pos = vertices.get(index);
                                float px = pos.x();
                                float py = pos.y();
                                float pz = pos.z();

                                // True Normals (NX, NY, NZ) with safe bounds check
                                float nx = 0.0f, ny = 1.0f, nz = 0.0f;
                                if (normals != null && index < normals.remaining()) {
                                    AIVector3D norm = normals.get(index);
                                    nx = norm.x();
                                    ny = norm.y();
                                    nz = norm.z();
                                }

                                // Clean neutral white base color
                                float r = 1.0f, g = 1.0f, b = 1.0f, a = 1.0f;

                                // Primary UVs with safe bounds check
                                float u1 = 0.0f, v1 = 0.0f;
                                if (uvs != null && index < uvs.remaining()) {
                                    AIVector3D uv = uvs.get(index);
                                    u1 = uv.x();
                                    v1 = uv.y();
                                }

                                // Lightmap UVs (U2, V2)
                                float u2 = u1, v2 = v1;
                                if (lightmapUvs != null && index < lightmapUvs.remaining()) {
                                    AIVector3D lmUv = lightmapUvs.get(index);
                                    u2 = lmUv.x();
                                    v2 = lmUv.y();
                                }

                                // Tangents (TX, TY, TZ)
                                float tx = 1.0f, ty = 0.0f, tz = 0.0f;
                                if (tangents != null && index < tangents.remaining()) {
                                    AIVector3D tan = tangents.get(index);
                                    tx = tan.x();
                                    ty = tan.y();
                                    tz = tan.z();
                                }

                                // Bitangents (BX, BY, BZ)
                                float bx = 0.0f, by = 1.0f, bz = 0.0f;
                                if (bitangents != null && index < bitangents.remaining()) {
                                    AIVector3D bitan = bitangents.get(index);
                                    bx = bitan.x();
                                    by = bitan.y();
                                    bz = bitan.z();
                                }

                                float lmScale = 1.0f, lmBias = 0.0f;

                                // 22 floats per vertex
                                vertexBuffer.add(px); vertexBuffer.add(py); vertexBuffer.add(pz);
                                vertexBuffer.add(nx); vertexBuffer.add(ny); vertexBuffer.add(nz);
                                vertexBuffer.add(r);  vertexBuffer.add(g);  vertexBuffer.add(b);  vertexBuffer.add(a);
                                vertexBuffer.add(u1); vertexBuffer.add(v1);
                                vertexBuffer.add(u2); vertexBuffer.add(v2);
                                vertexBuffer.add(tx); vertexBuffer.add(ty); vertexBuffer.add(tz);
                                vertexBuffer.add(bx); vertexBuffer.add(by); vertexBuffer.add(bz);
                                vertexBuffer.add(lmScale); vertexBuffer.add(lmBias);

                                if (skinned) {
                                    int base = index * MeshBufferData.MAX_BONE_INFLUENCES;
                                    for (int w = 0; w < MeshBufferData.MAX_BONE_INFLUENCES; w++) {
                                        vertexBuffer.add(Float.intBitsToFloat(boneIds[base + w]));
                                    }
                                    for (int w = 0; w < MeshBufferData.MAX_BONE_INFLUENCES; w++) {
                                        vertexBuffer.add(boneWeights[base + w]);
                                    }
                                }
                            }
                        }
                    }
                }

                aiReleaseImport(scene);

                if (!vertexBuffer.isEmpty()) {
                    float[] verts = new float[vertexBuffer.size()];
                    for (int j = 0; j < vertexBuffer.size(); j++) {
                        verts[j] = vertexBuffer.get(j);
                    }
                    int loadedCount = verts.length / stride;

                    // Execute RenderWare 3.5 Auto-Decimation if poly count exceeds target budget
                    if (loadedCount > RW35_TARGET_MAX_VERTICES) {
                        System.out.println("[MeshImporter] High-poly model detected (" + loadedCount + " verts). Executing RenderWare 3.5 target voxel vertex collapse...");
                        return decimateToRW35Target(verts, loadedCount, RW35_TARGET_MAX_VERTICES);
                    }

                    System.out.println("[MeshImporter] Successfully loaded 3D mesh " + file.getName() + " with " + loadedCount + " vertices (" + stride + "-float stride"
                            + (skinned ? ", " + boneNameToIndex.size() + " bones" : "") + ").");
                    return new MeshBufferData(verts, loadedCount);
                }
            }
        } catch (Throwable t) {
            System.err.println("[MeshImporter] Assimp exception on " + filePath + ": " + t.getMessage());
            t.printStackTrace();
        }

        return createDefaultCube();
    }

    /**
     * True Voxel Grid Vertex Collapse Decimation (Rossignac-Borrel Algorithm):
     * Maps vertices to 3D spatial voxel centroids, split by quantized UV bin so seams never blend, rebuilds
     * non-degenerate faces, and recomputes normals from the decimated face geometry.
     */
    public static MeshBufferData decimateToRW35Target(float[] rawVerts, int rawCount, int maxTargetVerts) {
        if (rawCount <= maxTargetVerts) return new MeshBufferData(rawVerts, rawCount);

        final int FLOATS_PER_VERTEX = MeshBufferData.strideOf(rawVerts, rawCount);
        final boolean skinned = FLOATS_PER_VERTEX >= MeshBufferData.SKINNED_FLOATS_PER_VERTEX;

        int totalFaces = rawCount / 3;

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        for (int i = 0; i < rawVerts.length; i += FLOATS_PER_VERTEX) {
            minX = Math.min(minX, rawVerts[i]);
            minY = Math.min(minY, rawVerts[i + 1]);
            minZ = Math.min(minZ, rawVerts[i + 2]);
            maxX = Math.max(maxX, rawVerts[i]);
            maxY = Math.max(maxY, rawVerts[i + 1]);
            maxZ = Math.max(maxZ, rawVerts[i + 2]);
        }

        float extentX = Math.max(0.001f, maxX - minX);
        float extentY = Math.max(0.001f, maxY - minY);
        float extentZ = Math.max(0.001f, maxZ - minZ);

        // Calculate grid resolution targeting maxTargetVerts
        int targetFaces = maxTargetVerts / 3;
        int gridRes = Math.max(8, Math.min(48, (int) Math.round(Math.cbrt(targetFaces * 2.0))));
        float voxelSizeX = extentX / gridRes;
        float voxelSizeY = extentY / gridRes;
        float voxelSizeZ = extentZ / gridRes;

        // Map raw vertices to voxel cells. Each vertex gets two keys:
        //  - spatialHash: the 3D voxel cell, which decides the collapsed position and whether a face survives
        //  - clusterKey: the voxel cell plus the quantized UV bin, which decides which vertices share attributes.
        // Vertices on opposite sides of a UV seam or atlas boundary share a position but land in different UV bins,
        // so they become separate clusters at the same point. The surface stays watertight and the UVs never blend.
        long[] vertSpatialHashes = new long[rawCount];
        int[] vertClusters = new int[rawCount];
        Map<Long, Integer> spatialIndex = new HashMap<>();
        Map<Long, Integer> clusterIndex = new LinkedHashMap<>();
        List<float[]> spatialSums = new ArrayList<>();   // [sumX, sumY, sumZ, count]
        List<float[]> clusterSums = new ArrayList<>();   // summed static attributes
        List<float[]> clusterFirst = new ArrayList<>();  // first vertex, for non-averageable bone data
        List<Integer> clusterCounts = new ArrayList<>();
        List<Integer> clusterSpatial = new ArrayList<>();

        // Bone indices are int bit patterns and cannot be averaged; skinned clusters average only the
        // static attributes and inherit bone indices/weights from the cluster's first vertex.
        final int averagedFloats = skinned ? MeshBufferData.BONE_INDEX_OFFSET : FLOATS_PER_VERTEX;

        for (int i = 0; i < rawCount; i++) {
            int base = i * FLOATS_PER_VERTEX;
            int vx = Math.max(0, Math.min(gridRes - 1, (int) ((rawVerts[base] - minX) / voxelSizeX)));
            int vy = Math.max(0, Math.min(gridRes - 1, (int) ((rawVerts[base + 1] - minY) / voxelSizeY)));
            int vz = Math.max(0, Math.min(gridRes - 1, (int) ((rawVerts[base + 2] - minZ) / voxelSizeZ)));
            long spatialHash = ((long) vx & 0xFFL) | (((long) vy & 0xFFL) << 8) | (((long) vz & 0xFFL) << 16);

            // floor() keeps tiled UVs outside [0, 1] in distinct bins; 20 bits per axis wraps only after ~65k tiles
            long ub = (long) Math.floor(rawVerts[base + 10] * UV_SEAM_BINS) & 0xFFFFFL;
            long vb = (long) Math.floor(rawVerts[base + 11] * UV_SEAM_BINS) & 0xFFFFFL;
            long clusterKey = spatialHash | (ub << 24) | (vb << 44);

            Integer s = spatialIndex.get(spatialHash);
            if (s == null) {
                s = spatialSums.size();
                spatialIndex.put(spatialHash, s);
                spatialSums.add(new float[4]);
            }
            float[] ss = spatialSums.get(s);
            ss[0] += rawVerts[base]; ss[1] += rawVerts[base + 1]; ss[2] += rawVerts[base + 2]; ss[3] += 1.0f;

            Integer c = clusterIndex.get(clusterKey);
            if (c == null) {
                c = clusterSums.size();
                clusterIndex.put(clusterKey, c);
                clusterSums.add(new float[FLOATS_PER_VERTEX]);
                float[] first = new float[FLOATS_PER_VERTEX];
                System.arraycopy(rawVerts, base, first, 0, FLOATS_PER_VERTEX);
                clusterFirst.add(first);
                clusterCounts.add(0);
                clusterSpatial.add(s);
            }
            float[] cs = clusterSums.get(c);
            for (int k = 0; k < averagedFloats; k++) {
                cs[k] += rawVerts[base + k];
            }
            clusterCounts.set(c, clusterCounts.get(c) + 1);

            vertSpatialHashes[i] = spatialHash;
            vertClusters[i] = c;
        }

        // Build each cluster's representative vertex: the shared spatial centroid as its position plus the
        // cluster's own averaged UVs, colors, and tangent frame
        int clusterCount = clusterSums.size();
        float[][] clusterVerts = new float[clusterCount][];
        for (int c = 0; c < clusterCount; c++) {
            float[] avg = clusterSums.get(c);
            float invCount = 1.0f / clusterCounts.get(c);
            for (int k = 0; k < averagedFloats; k++) {
                avg[k] *= invCount;
            }
            if (skinned) {
                System.arraycopy(clusterFirst.get(c), averagedFloats, avg, averagedFloats, FLOATS_PER_VERTEX - averagedFloats);
            }

            float[] ss = spatialSums.get(clusterSpatial.get(c));
            avg[0] = ss[0] / ss[3]; avg[1] = ss[1] / ss[3]; avg[2] = ss[2] / ss[3];

            // Normals are rebuilt from the decimated faces below; the averaged source normal is kept only as a
            // fallback for clusters whose faces all collapsed
            normalize3(avg, 3);
            normalize3(avg, 14); // tangent (tx, ty, tz)
            normalize3(avg, 17); // bitangent (bx, by, bz)

            clusterVerts[c] = avg;
        }

        // Reconstruct faces. A face survives only if its 3 corners fall in distinct spatial cells. Two UV clusters
        // from the same cell share one position, so letting them form a face would give a zero-area triangle.
        List<int[]> faces = new ArrayList<>();
        float[] faceNormalSums = new float[clusterCount * 3];
        for (int f = 0; f < totalFaces; f++) {
            int i0 = f * 3;
            int i1 = f * 3 + 1;
            int i2 = f * 3 + 2;

            long h0 = vertSpatialHashes[i0];
            long h1 = vertSpatialHashes[i1];
            long h2 = vertSpatialHashes[i2];
            if (h0 == h1 || h1 == h2 || h2 == h0) continue;

            int c0 = vertClusters[i0], c1 = vertClusters[i1], c2 = vertClusters[i2];
            float[] p0 = clusterVerts[c0], p1 = clusterVerts[c1], p2 = clusterVerts[c2];

            // Face normal from the new triangle's edges; its length is twice the area, which gives area weighting
            float e1x = p1[0] - p0[0], e1y = p1[1] - p0[1], e1z = p1[2] - p0[2];
            float e2x = p2[0] - p0[0], e2y = p2[1] - p0[1], e2z = p2[2] - p0[2];
            float fnx = e1y * e2z - e1z * e2y;
            float fny = e1z * e2x - e1x * e2z;
            float fnz = e1x * e2y - e1y * e2x;

            // A face that folded over during the collapse points against the source triangle. Orient its
            // contribution to match the source so a fold doesn't cancel out its neighbours' normals.
            int b0 = i0 * FLOATS_PER_VERTEX, b1 = i1 * FLOATS_PER_VERTEX, b2 = i2 * FLOATS_PER_VERTEX;
            float o1x = rawVerts[b1] - rawVerts[b0], o1y = rawVerts[b1 + 1] - rawVerts[b0 + 1], o1z = rawVerts[b1 + 2] - rawVerts[b0 + 2];
            float o2x = rawVerts[b2] - rawVerts[b0], o2y = rawVerts[b2 + 1] - rawVerts[b0 + 1], o2z = rawVerts[b2 + 2] - rawVerts[b0 + 2];
            float onx = o1y * o2z - o1z * o2y;
            float ony = o1z * o2x - o1x * o2z;
            float onz = o1x * o2y - o1y * o2x;
            if (fnx * onx + fny * ony + fnz * onz < 0.0f) {
                fnx = -fnx; fny = -fny; fnz = -fnz;
            }

            for (int c : new int[] {c0, c1, c2}) {
                faceNormalSums[c * 3] += fnx;
                faceNormalSums[c * 3 + 1] += fny;
                faceNormalSums[c * 3 + 2] += fnz;
            }
            faces.add(new int[] {c0, c1, c2});
        }

        // Replace cluster normals with the area-weighted sum of their decimated face normals. Clusters on opposite
        // sides of a UV seam accumulate separately, so seams keep their hard shading edge.
        for (int c = 0; c < clusterCount; c++) {
            float nx = faceNormalSums[c * 3], ny = faceNormalSums[c * 3 + 1], nz = faceNormalSums[c * 3 + 2];
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1e-12f) {
                float[] v = clusterVerts[c];
                v[3] = nx / len; v[4] = ny / len; v[5] = nz / len;
            }
        }

        if (faces.isEmpty()) {
            return new MeshBufferData(rawVerts, rawCount);
        }

        float[] finalVerts = new float[faces.size() * 3 * FLOATS_PER_VERTEX];
        int dst = 0;
        for (int[] face : faces) {
            for (int c : face) {
                System.arraycopy(clusterVerts[c], 0, finalVerts, dst, FLOATS_PER_VERTEX);
                dst += FLOATS_PER_VERTEX;
            }
        }

        int finalCount = finalVerts.length / FLOATS_PER_VERTEX;
        System.out.println("[MeshImporter] Successfully decimated high-poly mesh from " + rawCount + " to " + finalCount + " vertices (RW 3.5 Target: " + maxTargetVerts + ").");
        return new MeshBufferData(finalVerts, finalCount);
    }

    private static void normalize3(float[] v, int offset) {
        float len = (float) Math.sqrt(v[offset] * v[offset] + v[offset + 1] * v[offset + 1] + v[offset + 2] * v[offset + 2]);
        if (len > 0.0001f) {
            v[offset] /= len; v[offset + 1] /= len; v[offset + 2] /= len;
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
