package com.retro.engine.content.mesh;

import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.*;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

public class ModelLoader {

    public static ModelData loadModel(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) return null;

        String lower = filePath.toLowerCase();
        
        // Try Assimp first for robust 3D model parsing (.fbx, .gltf, .glb, .obj, .dae, etc.)
        ModelData assimpData = loadWithAssimp(filePath);
        if (assimpData != null && assimpData.vertices != null && assimpData.vertices.length > 0) {
            return assimpData;
        }

        // Fallback for .obj text parsing
        if (lower.endsWith(".obj")) {
            return loadObjFallback(filePath);
        }

        return null;
    }

    private static ModelData loadWithAssimp(String filePath) {
        try {
            AIScene scene = Assimp.aiImportFile(filePath,
                    Assimp.aiProcess_Triangulate |
                    Assimp.aiProcess_GenSmoothNormals |
                    Assimp.aiProcess_JoinIdenticalVertices |
                    Assimp.aiProcess_FlipUVs);

            if (scene == null || (scene.mFlags() & Assimp.AI_SCENE_FLAGS_INCOMPLETE) != 0 || scene.mRootNode() == null) {
                System.err.println("[ModelLoader] Assimp failed to import: " + Assimp.aiGetErrorString());
                return null;
            }

            int numMeshes = scene.mNumMeshes();
            PointerBuffer pMeshes = scene.mMeshes();
            if (pMeshes == null || numMeshes == 0) return null;

            List<Float> vertexBuffer = new ArrayList<>();

            for (int i = 0; i < numMeshes; i++) {
                AIMesh mesh = AIMesh.create(pMeshes.get(i));
                int numVertices = mesh.mNumVertices();
                AIVector3D.Buffer vertices = mesh.mVertices();
                AIVector3D.Buffer normals = mesh.mNormals();

                AIFace.Buffer faces = mesh.mFaces();
                int numFaces = mesh.mNumFaces();

                for (int f = 0; f < numFaces; f++) {
                    AIFace face = faces.get(f);
                    IntBuffer indices = face.mIndices();
                    while (indices.hasRemaining()) {
                        int idx = indices.get();
                        if (idx >= 0 && idx < numVertices) {
                            AIVector3D pos = vertices.get(idx);
                            float x = pos.x();
                            float y = pos.y();
                            float z = pos.z();

                            float nx = 0.0f, ny = 1.0f, nz = 0.0f;
                            if (normals != null) {
                                AIVector3D norm = normals.get(idx);
                                nx = norm.x();
                                ny = norm.y();
                                nz = norm.z();
                            }

                            // Position (X, Y, Z)
                            vertexBuffer.add(x);
                            vertexBuffer.add(y);
                            vertexBuffer.add(z);

                            // Color gradient based on normal lighting / depth (R, G, B, A)
                            float r = Math.max(0.2f, Math.min(1.0f, Math.abs(nx) * 0.8f + 0.2f));
                            float g = Math.max(0.2f, Math.min(1.0f, Math.abs(ny) * 0.8f + 0.2f));
                            float b = Math.max(0.2f, Math.min(1.0f, Math.abs(nz) * 0.8f + 0.2f));

                            vertexBuffer.add(r);
                            vertexBuffer.add(g);
                            vertexBuffer.add(b);
                            vertexBuffer.add(1.0f);
                        }
                    }
                }
            }

            Assimp.aiReleaseImport(scene);

            if (vertexBuffer.isEmpty()) return null;

            float[] verts = new float[vertexBuffer.size()];
            for (int k = 0; k < vertexBuffer.size(); k++) {
                verts[k] = vertexBuffer.get(k);
            }

            System.out.println("[ModelLoader] Successfully parsed " + filePath + " via Assimp (" + (verts.length / 7) + " vertices).");
            return new ModelData(verts, verts.length / 7);

        } catch (Throwable t) {
            System.err.println("[ModelLoader] Assimp exception during load: " + t.getMessage());
            return null;
        }
    }

    private static ModelData loadObjFallback(String filePath) {
        List<float[]> positions = new ArrayList<>();
        List<float[]> normals = new ArrayList<>();
        List<Float> vertexBuffer = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("v ")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 4) {
                        float x = Float.parseFloat(parts[1]);
                        float y = Float.parseFloat(parts[2]);
                        float z = Float.parseFloat(parts[3]);
                        positions.add(new float[]{x, y, z});
                    }
                } else if (line.startsWith("vn ")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 4) {
                        float nx = Float.parseFloat(parts[1]);
                        float ny = Float.parseFloat(parts[2]);
                        float nz = Float.parseFloat(parts[3]);
                        normals.add(new float[]{nx, ny, nz});
                    }
                } else if (line.startsWith("f ")) {
                    String[] parts = line.split("\\s+");
                    for (int i = 1; i <= 3 && i < parts.length; i++) {
                        String[] indices = parts[i].split("/");
                        int posIndex = Integer.parseInt(indices[0]) - 1;
                        float[] pos = (posIndex >= 0 && posIndex < positions.size()) ? positions.get(posIndex) : new float[]{0, 0, 0};

                        float[] norm = {0, 1, 0};
                        if (indices.length >= 3 && !indices[2].isEmpty()) {
                            int normIndex = Integer.parseInt(indices[2]) - 1;
                            if (normIndex >= 0 && normIndex < normals.size()) {
                                norm = normals.get(normIndex);
                            }
                        }

                        vertexBuffer.add(pos[0]);
                        vertexBuffer.add(pos[1]);
                        vertexBuffer.add(pos[2]);

                        float r = Math.max(0.2f, Math.abs(norm[0]));
                        float g = Math.max(0.2f, Math.abs(norm[1]));
                        float b = Math.max(0.2f, Math.abs(norm[2]));
                        vertexBuffer.add(r);
                        vertexBuffer.add(g);
                        vertexBuffer.add(b);
                        vertexBuffer.add(1.0f);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[ModelLoader] Fallback OBJ loader error: " + e.getMessage());
            return null;
        }

        if (vertexBuffer.isEmpty()) return null;

        float[] verts = new float[vertexBuffer.size()];
        for (int i = 0; i < vertexBuffer.size(); i++) {
            verts[i] = vertexBuffer.get(i);
        }

        System.out.println("[ModelLoader] Loaded OBJ via text fallback (" + (verts.length / 7) + " vertices).");
        return new ModelData(verts, verts.length / 7);
    }
}
