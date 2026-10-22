package com.retro.engine.assets.importers;

import org.lwjgl.assimp.*;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.assimp.Assimp.*;

/**
 * High-performance Assimp model importer enforcing:
 * 1. 22-float vertex stride: [PX, PY, PZ, NX, NY, NZ, R, G, B, A, U1, V1, U2, V2, TX, TY, TZ, BX, BY, BZ, LM_SCALE, LM_BIAS].
 * 2. Clean neutral base material color (no debug rainbow RGB normal packing).
 * 3. Safe Assimp buffer limits checking to prevent IndexOutOfBoundsException fallback to cube.
 * 4. True Voxel Grid Vertex Collapse Decimation (Rossignac-Borrel Algorithm) preserving
 *    closed 3D surface geometry, normals, UVs, and Tangents/Bitangents for RenderWare 3.5 target budgets (~4,500 verts).
 */
public class MeshImporter {

    public static final int FLOATS_PER_VERTEX = 22;
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

        try {
            AIScene scene = aiImportFile(filePath,
                    aiProcess_Triangulate |
                    aiProcess_GenSmoothNormals |
                    aiProcess_CalcTangentSpace |
                    aiProcess_JoinIdenticalVertices |
                    aiProcess_FlipUVs);

            if (scene != null && scene.mNumMeshes() > 0) {
                List<Float> vertexBuffer = new ArrayList<>();
                int numMeshes = scene.mNumMeshes();

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
                    int loadedCount = verts.length / FLOATS_PER_VERTEX;

                    // Execute RenderWare 3.5 Auto-Decimation if poly count exceeds target budget
                    if (loadedCount > RW35_TARGET_MAX_VERTICES) {
                        System.out.println("[MeshImporter] High-poly model detected (" + loadedCount + " verts). Executing RenderWare 3.5 target voxel vertex collapse...");
                        return decimateToRW35Target(verts, loadedCount, RW35_TARGET_MAX_VERTICES);
                    }

                    System.out.println("[MeshImporter] Successfully loaded 3D mesh " + file.getName() + " with " + loadedCount + " vertices (22-float stride).");
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
     * Maps vertices to 3D spatial voxel centroids and rebuilds non-degenerate faces,
     * maintaining contiguous 3D surface geometry, volume, normals, UVs, and Tangents/Bitangents.
     */
    public static MeshBufferData decimateToRW35Target(float[] rawVerts, int rawCount, int maxTargetVerts) {
        if (rawCount <= maxTargetVerts) return new MeshBufferData(rawVerts, rawCount);

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

        // Map raw vertices to voxel cell hashes
        long[] vertCellHashes = new long[rawCount];
        Map<Long, List<float[]>> cellVertMap = new LinkedHashMap<>();

        for (int i = 0; i < rawCount; i++) {
            int base = i * FLOATS_PER_VERTEX;
            int vx = Math.max(0, Math.min(gridRes - 1, (int) ((rawVerts[base] - minX) / voxelSizeX)));
            int vy = Math.max(0, Math.min(gridRes - 1, (int) ((rawVerts[base + 1] - minY) / voxelSizeY)));
            int vz = Math.max(0, Math.min(gridRes - 1, (int) ((rawVerts[base + 2] - minZ) / voxelSizeZ)));

            long hash = ((long) vx & 0xFFFFL) | (((long) vy & 0xFFFFL) << 16) | (((long) vz & 0xFFFFL) << 32);
            vertCellHashes[i] = hash;

            float[] vert = new float[FLOATS_PER_VERTEX];
            System.arraycopy(rawVerts, base, vert, 0, FLOATS_PER_VERTEX);

            List<float[]> list = cellVertMap.get(hash);
            if (list == null) {
                list = new ArrayList<>();
                cellVertMap.put(hash, list);
            }
            list.add(vert);
        }

        // Compute centroid for each cell
        Map<Long, float[]> cellCentroids = new LinkedHashMap<>();
        for (Map.Entry<Long, List<float[]>> entry : cellVertMap.entrySet()) {
            List<float[]> list = entry.getValue();
            float[] avg = new float[FLOATS_PER_VERTEX];
            for (float[] v : list) {
                for (int k = 0; k < FLOATS_PER_VERTEX; k++) {
                    avg[k] += v[k];
                }
            }
            float invCount = 1.0f / list.size();
            for (int k = 0; k < FLOATS_PER_VERTEX; k++) {
                avg[k] *= invCount;
            }

            // Normalize average surface normal (nx, ny, nz)
            float nLen = (float) Math.sqrt(avg[3] * avg[3] + avg[4] * avg[4] + avg[5] * avg[5]);
            if (nLen > 0.0001f) {
                avg[3] /= nLen; avg[4] /= nLen; avg[5] /= nLen;
            }
            // Normalize tangent (tx, ty, tz)
            float tLen = (float) Math.sqrt(avg[14] * avg[14] + avg[15] * avg[15] + avg[16] * avg[16]);
            if (tLen > 0.0001f) {
                avg[14] /= tLen; avg[15] /= tLen; avg[16] /= tLen;
            }
            // Normalize bitangent (bx, by, bz)
            float bLen = (float) Math.sqrt(avg[17] * avg[17] + avg[18] * avg[18] + avg[19] * avg[19]);
            if (bLen > 0.0001f) {
                avg[17] /= bLen; avg[18] /= bLen; avg[19] /= bLen;
            }

            cellCentroids.put(entry.getKey(), avg);
        }

        // Reconstruct faces using cell centroids
        List<Float> decimatedVerts = new ArrayList<>();
        for (int f = 0; f < totalFaces; f++) {
            int i0 = f * 3;
            int i1 = f * 3 + 1;
            int i2 = f * 3 + 2;

            long h0 = vertCellHashes[i0];
            long h1 = vertCellHashes[i1];
            long h2 = vertCellHashes[i2];

            // Only emit non-degenerate faces (where all 3 corners map to unique voxel cells)
            if (h0 != h1 && h1 != h2 && h2 != h0) {
                float[] c0 = cellCentroids.get(h0);
                float[] c1 = cellCentroids.get(h1);
                float[] c2 = cellCentroids.get(h2);

                if (c0 != null && c1 != null && c2 != null) {
                    for (float val : c0) decimatedVerts.add(val);
                    for (float val : c1) decimatedVerts.add(val);
                    for (float val : c2) decimatedVerts.add(val);
                }
            }
        }

        if (decimatedVerts.isEmpty()) {
            return new MeshBufferData(rawVerts, rawCount);
        }

        float[] finalVerts = new float[decimatedVerts.size()];
        for (int i = 0; i < decimatedVerts.size(); i++) {
            finalVerts[i] = decimatedVerts.get(i);
        }

        int finalCount = finalVerts.length / FLOATS_PER_VERTEX;
        System.out.println("[MeshImporter] Successfully decimated high-poly mesh from " + rawCount + " to " + finalCount + " vertices (RW 3.5 Target: " + maxTargetVerts + ").");
        return new MeshBufferData(finalVerts, finalCount);
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
