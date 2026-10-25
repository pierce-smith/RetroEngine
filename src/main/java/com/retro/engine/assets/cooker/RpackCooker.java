package com.retro.engine.assets.cooker;

import com.retro.engine.assets.importers.MeshBufferData;
import com.retro.engine.assets.importers.MeshImporter;
import com.retro.engine.assets.importers.PixelBufferData;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * High-performance offline and runtime VRAM asset cooker baking raw Assimp 3D models
 * and ImageIO textures into pre-swizzled, zero-copy VRAM binary packages (.rpack).
 *
 * Package Layout (.rpack):
 * - Magic Header: 'R' 'P' 'A' 'C' 'K' '1' '0' '\0' (8 Bytes)
 * - Asset Type: Int32 (1 = MESH_3D, 2 = TEXTURE_2D) (Big-Endian)
 * - Asset UUID: 16 Bytes (128-bit)
 * - MESH_3D Payloads: Stride (Int32), VertexCount (Int32), Vertices FloatCount (Int32), Float32 Array
 * - TEXTURE_2D Payloads: Width (Int32), Height (Int32), ByteLength (Int32), RGBA Byte Array
 */
public class RpackCooker {

    public static final byte[] RPACK_MAGIC = new byte[]{'R', 'P', 'A', 'C', 'K', '1', '0', 0};
    public static final int TYPE_MESH = 1;
    public static final int TYPE_TEXTURE = 2;
    private static final int HEADER_SIZE = 8 + 4 + 16 + 4 + 4 + 4; // 40 Bytes

    public static boolean cookMeshToRpack(String modelFilePath, String rpackOutputPath) {
        return cookMeshToRpack(modelFilePath, rpackOutputPath, null);
    }

    public static boolean cookMeshToRpack(String modelFilePath, String rpackOutputPath, String customUuidStr) {
        if (modelFilePath == null || rpackOutputPath == null) return false;

        MeshBufferData meshData = MeshImporter.loadModel(modelFilePath);
        if (meshData == null || meshData.vertices == null || meshData.vertices.length == 0) {
            System.err.println("[RpackCooker] Failed to load source mesh: " + modelFilePath);
            return false;
        }

        File outFile = new File(rpackOutputPath);
        if (outFile.getParentFile() != null && !outFile.getParentFile().exists()) {
            outFile.getParentFile().mkdirs();
        }

        int stride = meshData.stride();
        UUID assetUuid;
        try {
            assetUuid = (customUuidStr != null && !customUuidStr.trim().isEmpty()) ? UUID.fromString(customUuidStr.trim()) : UUID.randomUUID();
        } catch (Exception e) {
            assetUuid = UUID.randomUUID();
        }

        int floatCount = meshData.vertices.length;
        long payloadBytes = (long) floatCount * 4L;

        try (FileChannel channel = FileChannel.open(Paths.get(rpackOutputPath),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

            // Write 40-byte header in Big-Endian format
            ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
            header.put(RPACK_MAGIC);
            header.putInt(TYPE_MESH);
            header.putLong(assetUuid.getMostSignificantBits());
            header.putLong(assetUuid.getLeastSignificantBits());
            header.putInt(stride);
            header.putInt(meshData.vertexCount);
            header.putInt(floatCount);
            header.flip();

            channel.write(header);

            // Fast Bulk Direct ByteBuffer write for float array payload
            ByteBuffer payloadBuf = ByteBuffer.allocateDirect((int) payloadBytes).order(ByteOrder.BIG_ENDIAN);
            FloatBuffer floatView = payloadBuf.asFloatBuffer();
            floatView.put(meshData.vertices);
            payloadBuf.position(0);
            payloadBuf.limit((int) payloadBytes);

            channel.write(payloadBuf);

            System.out.println("[RpackCooker] Successfully cooked mesh '" + new File(modelFilePath).getName() +
                    "' -> '" + outFile.getName() + "' (" + meshData.vertexCount + " verts, " + stride + "-float stride).");
            return true;
        } catch (IOException e) {
            System.err.println("[RpackCooker] Failed to cook mesh .rpack: " + e.getMessage());
            return false;
        }
    }

    public static MeshBufferData readRpackMesh(String rpackFilePath) {
        if (rpackFilePath == null || !new File(rpackFilePath).exists()) return null;

        try (FileChannel channel = FileChannel.open(Paths.get(rpackFilePath), StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
            channel.read(header);
            header.flip();

            byte[] magic = new byte[8];
            header.get(magic);
            for (int i = 0; i < 8; i++) {
                if (magic[i] != RPACK_MAGIC[i]) return null;
            }

            int type = header.getInt();
            if (type != TYPE_MESH) return null;

            long msb = header.getLong();
            long lsb = header.getLong();

            int stride = header.getInt();
            int vertexCount = header.getInt();
            int floatCount = header.getInt();

            // Match Big-Endian byte order written during cooking
            ByteBuffer floatBufBytes = ByteBuffer.allocateDirect(floatCount * 4).order(ByteOrder.BIG_ENDIAN);
            channel.read(floatBufBytes);
            floatBufBytes.flip();

            float[] vertices = new float[floatCount];
            floatBufBytes.asFloatBuffer().get(vertices);

            return new MeshBufferData(vertices, vertexCount);
        } catch (Exception e) {
            System.err.println("[RpackCooker] Failed to read mesh .rpack '" + rpackFilePath + "': " + e.getMessage());
            return null;
        }
    }

    public static boolean cookTextureToRpack(String textureFilePath, String rpackOutputPath) {
        return cookTextureToRpack(textureFilePath, rpackOutputPath, null);
    }

    public static boolean cookTextureToRpack(String textureFilePath, String rpackOutputPath, String customUuidStr) {
        if (textureFilePath == null || rpackOutputPath == null) return false;

        File srcFile = new File(textureFilePath);
        if (!srcFile.exists()) return false;

        try {
            BufferedImage image = ImageIO.read(srcFile);
            if (image == null) return false;

            int w = image.getWidth();
            int h = image.getHeight();
            int[] pixelsInt = new int[w * h];
            image.getRGB(0, 0, w, h, pixelsInt, 0, w);

            byte[] pixelsByte = new byte[w * h * 4];
            for (int i = 0; i < pixelsInt.length; i++) {
                int argb = pixelsInt[i];
                pixelsByte[i * 4]     = (byte) ((argb >> 16) & 0xFF); // R
                pixelsByte[i * 4 + 1] = (byte) ((argb >> 8) & 0xFF);  // G
                pixelsByte[i * 4 + 2] = (byte) (argb & 0xFF);         // B
                pixelsByte[i * 4 + 3] = (byte) ((argb >> 24) & 0xFF); // A
            }

            File outFile = new File(rpackOutputPath);
            if (outFile.getParentFile() != null && !outFile.getParentFile().exists()) {
                outFile.getParentFile().mkdirs();
            }

            UUID assetUuid;
            try {
                assetUuid = (customUuidStr != null && !customUuidStr.trim().isEmpty()) ? UUID.fromString(customUuidStr.trim()) : UUID.randomUUID();
            } catch (Exception e) {
                assetUuid = UUID.randomUUID();
            }

            try (FileChannel channel = FileChannel.open(Paths.get(rpackOutputPath),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

                ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
                header.put(RPACK_MAGIC);
                header.putInt(TYPE_TEXTURE);
                header.putLong(assetUuid.getMostSignificantBits());
                header.putLong(assetUuid.getLeastSignificantBits());
                header.putInt(w);
                header.putInt(h);
                header.putInt(pixelsByte.length);
                header.flip();

                channel.write(header);

                ByteBuffer pixelBuf = ByteBuffer.allocateDirect(pixelsByte.length);
                pixelBuf.put(pixelsByte);
                pixelBuf.flip();

                channel.write(pixelBuf);

                System.out.println("[RpackCooker] Successfully cooked texture '" + srcFile.getName() +
                        "' -> '" + outFile.getName() + "' (" + w + "x" + h + " RGBA).");
                return true;
            }
        } catch (Exception e) {
            System.err.println("[RpackCooker] Failed to cook texture .rpack: " + e.getMessage());
            return false;
        }
    }

    public static PixelBufferData readRpackTexture(String rpackFilePath) {
        if (rpackFilePath == null || !new File(rpackFilePath).exists()) return null;

        try (FileChannel channel = FileChannel.open(Paths.get(rpackFilePath), StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
            channel.read(header);
            header.flip();

            byte[] magic = new byte[8];
            header.get(magic);
            for (int i = 0; i < 8; i++) {
                if (magic[i] != RPACK_MAGIC[i]) return null;
            }

            int type = header.getInt();
            if (type != TYPE_TEXTURE) return null;

            long msb = header.getLong();
            long lsb = header.getLong();

            int w = header.getInt();
            int h = header.getInt();
            int byteLength = header.getInt();

            ByteBuffer pixelBuf = ByteBuffer.allocateDirect(byteLength);
            channel.read(pixelBuf);
            pixelBuf.flip();

            byte[] pixels = new byte[byteLength];
            pixelBuf.get(pixels);

            return new PixelBufferData(pixels, w, h);
        } catch (Exception e) {
            System.err.println("[RpackCooker] Failed to read texture .rpack '" + rpackFilePath + "': " + e.getMessage());
            return null;
        }
    }
}
