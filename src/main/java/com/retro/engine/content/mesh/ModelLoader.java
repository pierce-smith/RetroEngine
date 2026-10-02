package com.retro.engine.content.mesh;

import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.*;

import java.io.File;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.assimp.Assimp.*;

/**
 * Unified Model Loader supporting .obj, .gltf, .glb, and .fbx formats
 * using LWJGL Assimp bindings. Extracts static vertex attributes and skeletal animation metadata.
 */
public class ModelLoader {

    public static ModelData loadModel(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            System.err.println("[ModelLoader] ERROR: File not found: " + filePath);
            return null;
        }

        int flags = aiProcess_Triangulate |
                aiProcess_GenSmoothNormals |
                aiProcess_FlipUVs |
                aiProcess_CalcTangentSpace |
                aiProcess_LimitBoneWeights |
                aiProcess_JoinIdenticalVertices;

        AIScene scene = aiImportFile(filePath, flags);
        if (scene == null || (scene.mFlags() & AI_SCENE_FLAGS_INCOMPLETE) != 0 || scene.mRootNode() == null) {
            System.err.println("[ModelLoader] Assimp import failed for '" + filePath + "': " + aiGetErrorString());
            return null;
        }

        List<Float> vertexData = new ArrayList<>();
        List<Integer> indexData = new ArrayList<>();

        int numMeshes = scene.mNumMeshes();
        PointerBuffer meshes = scene.mMeshes();

        int vertexOffset = 0;
        boolean hasAnimation = scene.mNumAnimations() > 0;

        for (int m = 0; m < numMeshes; m++) {
            AIMesh mesh = AIMesh.create(meshes.get(m));
            processMesh(mesh, vertexData, indexData, vertexOffset);
            vertexOffset += mesh.mNumVertices();
        }

        aiReleaseImport(scene);

        // Convert lists to primitive arrays
        float[] vertices = new float[vertexData.size()];
        for (int i = 0; i < vertexData.size(); i++) {
            vertices[i] = vertexData.get(i);
        }

        int[] indices = new int[indexData.size()];
        for (int i = 0; i < indexData.size(); i++) {
            indices[i] = indexData.get(i);
        }

        System.out.println("[ModelLoader] Successfully loaded '" + file.getName() +
                "' | Vertices: " + (vertices.length / 7) +
                " | Indices: " + indices.length +
                " | Animated: " + hasAnimation);

        return new ModelData(file.getName(), vertices, indices, hasAnimation);
    }

    private static void processMesh(AIMesh mesh, List<Float> vertexData, List<Integer> indexData, int vertexOffset) {
        AIVector3D.Buffer positions = mesh.mVertices();
        AIVector3D.Buffer normals = mesh.mNormals();

        int numVertices = mesh.mNumVertices();
        for (int i = 0; i < numVertices; i++) {
            AIVector3D pos = positions.get(i);
            vertexData.add(pos.x());
            vertexData.add(pos.y());
            vertexData.add(pos.z());

            // Default tint color (RGB) from surface normals
            if (normals != null && i < normals.capacity()) {
                AIVector3D norm = normals.get(i);
                vertexData.add(Math.abs(norm.x()) * 0.7f + 0.3f);
                vertexData.add(Math.abs(norm.y()) * 0.7f + 0.3f);
                vertexData.add(Math.abs(norm.z()) * 0.7f + 0.3f);
            } else {
                vertexData.add(0.9f);
                vertexData.add(0.9f);
                vertexData.add(0.9f);
            }
            vertexData.add(1.0f); // Alpha
        }

        int numFaces = mesh.mNumFaces();
        AIFace.Buffer faces = mesh.mFaces();
        for (int f = 0; f < numFaces; f++) {
            AIFace face = faces.get(f);
            IntBuffer indices = face.mIndices();
            while (indices.hasRemaining()) {
                indexData.add(vertexOffset + indices.get());
            }
        }
    }
}