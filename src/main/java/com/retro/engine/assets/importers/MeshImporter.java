package com.retro.engine.assets.importers;

import org.lwjgl.assimp.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.assimp.Assimp.*;

/**
 * High-performance Assimp model importer enforcing:
 * 1. 14-float vertex stride: [PX, PY, PZ, NX, NY, NZ, R, G, B, A, U1, V1, U2, V2].
 * 2. Clean neutral base material color (no debug rainbow RGB normal packing).
 * 3. Safe Assimp buffer limits checking to prevent IndexOutOfBoundsException fallback to cube.
 */
public class MeshImporter {

    public static final int FLOATS_PER_VERTEX = 14;

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

                                float u2 = u1, v2 = v1;

                                // 14 floats per vertex
                                vertexBuffer.add(px); vertexBuffer.add(py); vertexBuffer.add(pz);
                                vertexBuffer.add(nx); vertexBuffer.add(ny); vertexBuffer.add(nz);
                                vertexBuffer.add(r);  vertexBuffer.add(g);  vertexBuffer.add(b);  vertexBuffer.add(a);
                                vertexBuffer.add(u1); vertexBuffer.add(v1);
                                vertexBuffer.add(u2); vertexBuffer.add(v2);
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
                    System.out.println("[MeshImporter] Successfully loaded 3D mesh " + file.getName() + " with " + loadedCount + " vertices.");
                    return new MeshBufferData(verts, loadedCount);
                }
            }
        } catch (Throwable t) {
            System.err.println("[MeshImporter] Assimp exception on " + filePath + ": " + t.getMessage());
            t.printStackTrace();
        }

        return createDefaultCube();
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
            -1f,-1f, 1f,  0f,0f,1f,  0.9f,0.3f,0.2f,1f,  0f,1f, 0f,1f,
             1f,-1f, 1f,  0f,0f,1f,  0.9f,0.3f,0.2f,1f,  1f,1f, 1f,1f,
             1f, 1f, 1f,  0f,0f,1f,  0.9f,0.3f,0.2f,1f,  1f,0f, 1f,0f,
            -1f,-1f, 1f,  0f,0f,1f,  0.9f,0.3f,0.2f,1f,  0f,1f, 0f,1f,
             1f, 1f, 1f,  0f,0f,1f,  0.9f,0.3f,0.2f,1f,  1f,0f, 1f,0f,
            -1f, 1f, 1f,  0f,0f,1f,  0.9f,0.3f,0.2f,1f,  0f,0f, 0f,0f,

            // Back (-Z)
             1f,-1f,-1f,  0f,0f,-1f, 0.2f,0.8f,0.9f,1f,  0f,1f, 0f,1f,
            -1f,-1f,-1f,  0f,0f,-1f, 0.2f,0.8f,0.9f,1f,  1f,1f, 1f,1f,
            -1f, 1f,-1f,  0f,0f,-1f, 0.2f,0.8f,0.9f,1f,  1f,0f, 1f,0f,
             1f,-1f,-1f,  0f,0f,-1f, 0.2f,0.8f,0.9f,1f,  0f,1f, 0f,1f,
            -1f, 1f,-1f,  0f,0f,-1f, 0.2f,0.8f,0.9f,1f,  1f,0f, 1f,0f,
             1f, 1f,-1f,  0f,0f,-1f, 0.2f,0.8f,0.9f,1f,  0f,0f, 0f,0f,

            // Top (+Y)
            -1f, 1f, 1f,  0f,1f,0f,  0.2f,0.9f,0.3f,1f,  0f,1f, 0f,1f,
             1f, 1f, 1f,  0f,1f,0f,  0.2f,0.9f,0.3f,1f,  1f,1f, 1f,1f,
             1f, 1f,-1f,  0f,1f,0f,  0.2f,0.9f,0.3f,1f,  1f,0f, 1f,0f,
            -1f, 1f, 1f,  0f,1f,0f,  0.2f,0.9f,0.3f,1f,  0f,1f, 0f,1f,
             1f, 1f,-1f,  0f,1f,0f,  0.2f,0.9f,0.3f,1f,  1f,0f, 1f,0f,
            -1f, 1f,-1f,  0f,1f,0f,  0.2f,0.9f,0.3f,1f,  0f,0f, 0f,0f,

            // Bottom (-Y)
            -1f,-1f,-1f,  0f,-1f,0f, 0.2f,0.3f,0.9f,1f,  0f,1f, 0f,1f,
             1f,-1f,-1f,  0f,-1f,0f, 0.2f,0.3f,0.9f,1f,  1f,1f, 1f,1f,
             1f,-1f, 1f,  0f,-1f,0f, 0.2f,0.3f,0.9f,1f,  1f,0f, 1f,0f,
            -1f,-1f,-1f,  0f,-1f,0f, 0.2f,0.3f,0.9f,1f,  0f,1f, 0f,1f,
             1f,-1f, 1f,  0f,-1f,0f, 0.2f,0.3f,0.9f,1f,  1f,0f, 1f,0f,
            -1f,-1f, 1f,  0f,-1f,0f, 0.2f,0.3f,0.9f,1f,  0f,0f, 0f,0f,

            // Left (-X)
            -1f,-1f,-1f, -1f,0f,0f,  0.9f,0.8f,0.2f,1f,  0f,1f, 0f,1f,
            -1f,-1f, 1f, -1f,0f,0f,  0.9f,0.8f,0.2f,1f,  1f,1f, 1f,1f,
            -1f, 1f, 1f, -1f,0f,0f,  0.9f,0.8f,0.2f,1f,  1f,0f, 1f,0f,
            -1f,-1f,-1f, -1f,0f,0f,  0.9f,0.8f,0.2f,1f,  0f,1f, 0f,1f,
            -1f, 1f, 1f, -1f,0f,0f,  0.9f,0.8f,0.2f,1f,  1f,0f, 1f,0f,
            -1f, 1f,-1f, -1f,0f,0f,  0.9f,0.8f,0.2f,1f,  0f,0f, 0f,0f,

            // Right (+X)
             1f,-1f, 1f,  1f,0f,0f,  0.8f,0.2f,0.9f,1f,  0f,1f, 0f,1f,
             1f,-1f,-1f,  1f,0f,0f,  0.8f,0.2f,0.9f,1f,  1f,1f, 1f,1f,
             1f, 1f,-1f,  1f,0f,0f,  0.8f,0.2f,0.9f,1f,  1f,0f, 1f,0f,
             1f,-1f, 1f,  1f,0f,0f,  0.8f,0.2f,0.9f,1f,  0f,1f, 0f,1f,
             1f, 1f,-1f,  1f,0f,0f,  0.8f,0.2f,0.9f,1f,  1f,0f, 1f,0f,
             1f, 1f, 1f,  1f,0f,0f,  0.8f,0.2f,0.9f,1f,  0f,0f, 0f,0f
        };
        return new MeshBufferData(cubeVerts, cubeVerts.length / FLOATS_PER_VERTEX);
    }
}
