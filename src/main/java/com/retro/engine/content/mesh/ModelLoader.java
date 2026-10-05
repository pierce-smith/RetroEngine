package com.retro.engine.content.mesh;

import org.lwjgl.assimp.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.assimp.Assimp.*;

public class ModelLoader {

    public static ModelData loadModel(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) return null;

        File file = new File(filePath);
        if (!file.exists()) {
            System.err.println("[ModelLoader] File not found: " + filePath);
            return null;
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

                    AIFace.Buffer faces = mesh.mFaces();
                    int numFaces = mesh.mNumFaces();

                    for (int f = 0; f < numFaces; f++) {
                        AIFace aiFace = faces.get(f);
                        if (aiFace.mNumIndices() == 3) {
                            for (int k = 0; k < 3; k++) {
                                int index = aiFace.mIndices().get(k);

                                AIVector3D pos = vertices.get(index);
                                float px = pos.x();
                                float py = pos.y();
                                float pz = pos.z();

                                float r = 0.8f, g = 0.8f, b = 0.85f;
                                if (normals != null) {
                                    AIVector3D norm = normals.get(index);
                                    r = Math.max(0.2f, Math.abs(norm.x()));
                                    g = Math.max(0.2f, Math.abs(norm.y()));
                                    b = Math.max(0.2f, Math.abs(norm.z()));
                                }

                                float u = 0.0f, v = 0.0f;
                                if (uvs != null) {
                                    AIVector3D uv = uvs.get(index);
                                    u = uv.x();
                                    v = uv.y();
                                }

                                // 9 floats per vertex: x, y, z, r, g, b, a, u, v
                                vertexBuffer.add(px);
                                vertexBuffer.add(py);
                                vertexBuffer.add(pz);
                                vertexBuffer.add(r);
                                vertexBuffer.add(g);
                                vertexBuffer.add(b);
                                vertexBuffer.add(1.0f);
                                vertexBuffer.add(u);
                                vertexBuffer.add(v);
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
                    System.out.println("[ModelLoader] Loaded mesh " + file.getName() + " with " + (verts.length / 9) + " vertices.");
                    return new ModelData(verts, verts.length / 9);
                }
            }
        } catch (Throwable t) {
            System.err.println("[ModelLoader] Assimp exception on " + filePath + ": " + t.getMessage());
        }

        return createDefaultCube();
    }

    public static ModelData createDefaultCube() {
        float[] cubeVerts = new float[] {
            // Front Face (+Z) - Reddish/Orange
            -1.0f,-1.0f, 1.0f,  0.9f,0.3f,0.2f,1.0f,  0.0f,1.0f,
             1.0f,-1.0f, 1.0f,  0.9f,0.3f,0.2f,1.0f,  1.0f,1.0f,
             1.0f, 1.0f, 1.0f,  0.9f,0.3f,0.2f,1.0f,  1.0f,0.0f,
            -1.0f,-1.0f, 1.0f,  0.9f,0.3f,0.2f,1.0f,  0.0f,1.0f,
             1.0f, 1.0f, 1.0f,  0.9f,0.3f,0.2f,1.0f,  1.0f,0.0f,
            -1.0f, 1.0f, 1.0f,  0.9f,0.3f,0.2f,1.0f,  0.0f,0.0f,

            // Back Face (-Z) - Cyan
             1.0f,-1.0f,-1.0f,  0.2f,0.8f,0.9f,1.0f,  0.0f,1.0f,
            -1.0f,-1.0f,-1.0f,  0.2f,0.8f,0.9f,1.0f,  1.0f,1.0f,
            -1.0f, 1.0f,-1.0f,  0.2f,0.8f,0.9f,1.0f,  1.0f,0.0f,
             1.0f,-1.0f,-1.0f,  0.2f,0.8f,0.9f,1.0f,  0.0f,1.0f,
            -1.0f, 1.0f,-1.0f,  0.2f,0.8f,0.9f,1.0f,  1.0f,0.0f,
             1.0f, 1.0f,-1.0f,  0.2f,0.8f,0.9f,1.0f,  0.0f,0.0f,

            // Top Face (+Y) - Green
            -1.0f, 1.0f, 1.0f,  0.2f,0.9f,0.3f,1.0f,  0.0f,1.0f,
             1.0f, 1.0f, 1.0f,  0.2f,0.9f,0.3f,1.0f,  1.0f,1.0f,
             1.0f, 1.0f,-1.0f,  0.2f,0.9f,0.3f,1.0f,  1.0f,0.0f,
            -1.0f, 1.0f, 1.0f,  0.2f,0.9f,0.3f,1.0f,  0.0f,1.0f,
             1.0f, 1.0f,-1.0f,  0.2f,0.9f,0.3f,1.0f,  1.0f,0.0f,
            -1.0f, 1.0f,-1.0f,  0.2f,0.9f,0.3f,1.0f,  0.0f,0.0f,

            // Bottom Face (-Y) - Blue
            -1.0f,-1.0f,-1.0f,  0.2f,0.3f,0.9f,1.0f,  0.0f,1.0f,
             1.0f,-1.0f,-1.0f,  0.2f,0.3f,0.9f,1.0f,  1.0f,1.0f,
             1.0f,-1.0f, 1.0f,  0.2f,0.3f,0.9f,1.0f,  1.0f,0.0f,
            -1.0f,-1.0f,-1.0f,  0.2f,0.3f,0.9f,1.0f,  0.0f,1.0f,
             1.0f,-1.0f, 1.0f,  0.2f,0.3f,0.9f,1.0f,  1.0f,0.0f,
            -1.0f,-1.0f, 1.0f,  0.2f,0.3f,0.9f,1.0f,  0.0f,0.0f,

            // Left Face (-X) - Yellow
            -1.0f,-1.0f,-1.0f,  0.9f,0.8f,0.2f,1.0f,  0.0f,1.0f,
            -1.0f,-1.0f, 1.0f,  0.9f,0.8f,0.2f,1.0f,  1.0f,1.0f,
            -1.0f, 1.0f, 1.0f,  0.9f,0.8f,0.2f,1.0f,  1.0f,0.0f,
            -1.0f,-1.0f,-1.0f,  0.9f,0.8f,0.2f,1.0f,  0.0f,1.0f,
            -1.0f, 1.0f, 1.0f,  0.9f,0.8f,0.2f,1.0f,  1.0f,0.0f,
            -1.0f, 1.0f,-1.0f,  0.9f,0.8f,0.2f,1.0f,  0.0f,0.0f,

            // Right Face (+X) - Purple
             1.0f,-1.0f, 1.0f,  0.8f,0.2f,0.9f,1.0f,  0.0f,1.0f,
             1.0f,-1.0f,-1.0f,  0.8f,0.2f,0.9f,1.0f,  1.0f,1.0f,
             1.0f, 1.0f,-1.0f,  0.8f,0.2f,0.9f,1.0f,  1.0f,0.0f,
             1.0f,-1.0f, 1.0f,  0.8f,0.2f,0.9f,1.0f,  0.0f,1.0f,
             1.0f, 1.0f,-1.0f,  0.8f,0.2f,0.9f,1.0f,  1.0f,0.0f,
             1.0f, 1.0f, 1.0f,  0.8f,0.2f,0.9f,1.0f,  0.0f,0.0f
        };
        return new ModelData(cubeVerts, 36);
    }
}
