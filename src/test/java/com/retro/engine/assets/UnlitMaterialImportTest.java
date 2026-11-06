package com.retro.engine.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

class UnlitMaterialImportTest {

    @TempDir
    Path dir;

    /** One triangle per material; each entry is whether that primitive's material carries KHR_materials_unlit. */
    private String gltf(String name, boolean... unlit) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0}) buffer.putFloat(f);
        String data = Base64.getEncoder().encodeToString(buffer.array());

        StringBuilder primitives = new StringBuilder();
        StringBuilder materials = new StringBuilder();
        for (int i = 0; i < unlit.length; i++) {
            if (i > 0) {
                primitives.append(',');
                materials.append(',');
            }
            primitives.append("{\"attributes\":{\"POSITION\":0},\"material\":").append(i).append('}');
            materials.append("{\"name\":\"m").append(i).append('"')
                    .append(unlit[i] ? ",\"extensions\":{\"KHR_materials_unlit\":{}}" : "").append('}');
        }
        String json = "{\"asset\":{\"version\":\"2.0\"},\"extensionsUsed\":[\"KHR_materials_unlit\"],"
                + "\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0}],"
                + "\"meshes\":[{\"primitives\":[" + primitives + "]}],\"materials\":[" + materials + "],"
                + "\"buffers\":[{\"byteLength\":36,\"uri\":\"data:application/octet-stream;base64," + data + "\"}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteLength\":36}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\","
                + "\"min\":[0,0,0],\"max\":[1,1,0]}]}";
        Path file = dir.resolve(name);
        Files.writeString(file, json);
        return file.toString();
    }

    @Test
    void unlitExtensionSetsTheFlag() throws IOException {
        MeshBufferData mesh = MeshImporter.loadModel(gltf("unlit.gltf", true));
        assertEquals(3, mesh.vertexCount);
        assertTrue(mesh.unlit);
    }

    @Test
    void plainMaterialStaysLit() throws IOException {
        MeshBufferData mesh = MeshImporter.loadModel(gltf("lit.gltf", false));
        assertEquals(3, mesh.vertexCount);
        assertFalse(mesh.unlit);
    }

    @Test
    void mixedMaterialsStayLit() throws IOException {
        MeshBufferData mesh = MeshImporter.loadModel(gltf("mixed.gltf", true, false));
        assertEquals(6, mesh.vertexCount);
        assertFalse(mesh.unlit);
    }

    @Test
    void flagSurvivesCooking() throws IOException {
        String unlit = dir.resolve("unlit.rpack").toString();
        String lit = dir.resolve("lit.rpack").toString();
        assertTrue(RpackCooker.cookMeshToRpack(gltf("unlit.gltf", true), unlit));
        assertTrue(RpackCooker.cookMeshToRpack(gltf("lit.gltf", false), lit));
        assertTrue(RpackCooker.readRpackMesh(unlit).unlit);
        assertFalse(RpackCooker.readRpackMesh(lit).unlit);
    }

    @Test
    void packCookedBeforeTheFlagExistedReadsAsLit() throws IOException {
        Path pack = dir.resolve("old.rpack");
        assertTrue(RpackCooker.cookMeshToRpack(gltf("unlit.gltf", true), pack.toString()));
        byte[] bytes = Files.readAllBytes(pack);
        Files.write(pack, Arrays.copyOf(bytes, bytes.length - 4));

        MeshBufferData mesh = RpackCooker.readRpackMesh(pack.toString());
        assertEquals(3, mesh.vertexCount);
        assertFalse(mesh.unlit);
    }
}
