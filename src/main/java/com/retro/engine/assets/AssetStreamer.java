package com.retro.engine.assets;

import com.retro.engine.assets.importers.MeshBufferData;
import com.retro.engine.assets.importers.MeshImporter;
import com.retro.engine.assets.importers.PixelBufferData;
import com.retro.engine.assets.importers.TextureImporter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Step 4: Virtual Thread Asset Streaming (JDK 21+ / JDK 25 Loom)
 *
 * Offloads heavy Assimp 3D model parsing and ImageIO 2D texture decoding onto lightweight
 * JVM Virtual Threads (Executors.newVirtualThreadPerTaskExecutor()).
 *
 * Completed assets are pushed to a thread-safe staging queue and uploaded to GPU VRAM
 * on the main render thread, eliminating disk/parse frame hitches.
 */
public class AssetStreamer {

    private final ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final Map<String, MeshBufferData> meshCache = new ConcurrentHashMap<>();
    private final Map<String, PixelBufferData> textureCache = new ConcurrentHashMap<>();

    private final ConcurrentLinkedQueue<PendingMeshResult> pendingMeshes = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PendingTextureResult> pendingTextures = new ConcurrentLinkedQueue<>();

    private final Map<String, Boolean> loadingStatus = new ConcurrentHashMap<>();

    public record PendingMeshResult(String path, MeshBufferData modelData) {}
    public record PendingTextureResult(String path, PixelBufferData textureData) {}

    /**
     * Requests non-blocking async loading of a 3D model on a JVM Virtual Thread.
     */
    public void requestModelAsync(String meshPath) {
        if (meshPath == null || meshPath.trim().isEmpty() || meshCache.containsKey(meshPath)) return;

        if (loadingStatus.putIfAbsent("mesh:" + meshPath, Boolean.TRUE) == null) {
            virtualExecutor.submit(() -> {
                try {
                    System.out.println("[VirtualThread] Streaming 3D Mesh in background: " + meshPath);
                    MeshBufferData data = MeshImporter.loadModel(meshPath);
                    if (data == null) {
                        data = MeshImporter.createDefaultCube();
                    }
                    pendingMeshes.add(new PendingMeshResult(meshPath, data));
                } catch (Exception e) {
                    System.err.println("[VirtualThread] Failed to stream 3D mesh: " + e.getMessage());
                    pendingMeshes.add(new PendingMeshResult(meshPath, MeshImporter.createDefaultCube()));
                } finally {
                    loadingStatus.remove("mesh:" + meshPath);
                }
            });
        }
    }

    /**
     * Requests non-blocking async loading of a 2D texture on a JVM Virtual Thread.
     */
    public void requestTextureAsync(String texturePath) {
        if (texturePath == null || texturePath.trim().isEmpty()) return;

        PixelBufferData existing = textureCache.get(texturePath);
        if (existing == PixelBufferData.EMPTY_FALLBACK) {
            textureCache.remove(texturePath);
        }

        if (textureCache.containsKey(texturePath)) return;

        if (loadingStatus.putIfAbsent("tex:" + texturePath, Boolean.TRUE) == null) {
            virtualExecutor.submit(() -> {
                try {
                    System.out.println("[VirtualThread] Streaming 2D Texture in background: " + texturePath);
                    PixelBufferData data = TextureImporter.loadTexture(texturePath);
                    if (data != null) {
                        pendingTextures.add(new PendingTextureResult(texturePath, data));
                    } else {
                        System.err.println("[VirtualThread] Texture missing or not decoded yet: " + texturePath);
                    }
                } catch (Exception e) {
                    System.err.println("[VirtualThread] Failed to stream 2D texture: " + e.getMessage());
                } finally {
                    loadingStatus.remove("tex:" + texturePath);
                }
            });
        }
    }

    /**
     * Drains the completed async queues on the main render thread to safely update main caches.
     */
    public void processCompletedUploads() {
        PendingMeshResult meshResult;
        while ((meshResult = pendingMeshes.poll()) != null) {
            meshCache.put(meshResult.path(), meshResult.modelData());
            System.out.println("[AssetStreaming] Synced 3D Mesh into Main Thread Cache: " + meshResult.path());
        }

        PendingTextureResult texResult;
        while ((texResult = pendingTextures.poll()) != null) {
            textureCache.put(texResult.path(), texResult.textureData());
            System.out.println("[AssetStreaming] Synced 2D Texture into Main Thread Cache: " + texResult.path());
        }
    }

    public MeshBufferData getModel(String path) {
        return meshCache.get(path);
    }

    public PixelBufferData getTexture(String path) {
        return textureCache.get(path);
    }

    public boolean isMeshLoaded(String path) {
        return meshCache.containsKey(path);
    }

    public boolean isTextureLoaded(String path) {
        return textureCache.containsKey(path);
    }

    public void invalidateTexture(String path) {
        if (path != null) {
            textureCache.remove(path);
            loadingStatus.remove("tex:" + path);
        }
    }

    public void shutdown() {
        virtualExecutor.shutdown();
    }
}
