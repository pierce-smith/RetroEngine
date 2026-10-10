package com.retro.engine.content;

import com.retro.engine.content.mesh.ModelData;
import com.retro.engine.content.mesh.ModelLoader;
import com.retro.engine.content.texture.TextureData;
import com.retro.engine.content.texture.TextureLoader;

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
public class AssetStreamingService {

    private final ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final Map<String, ModelData> meshCache = new ConcurrentHashMap<>();
    private final Map<String, TextureData> textureCache = new ConcurrentHashMap<>();

    private final ConcurrentLinkedQueue<PendingMeshResult> pendingMeshes = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PendingTextureResult> pendingTextures = new ConcurrentLinkedQueue<>();

    private final Map<String, Boolean> loadingStatus = new ConcurrentHashMap<>();

    public record PendingMeshResult(String path, ModelData modelData) {}
    public record PendingTextureResult(String path, TextureData textureData) {}

    /**
     * Requests non-blocking async loading of a 3D model on a JVM Virtual Thread.
     */
    public void requestModelAsync(String meshPath) {
        if (meshPath == null || meshCache.containsKey(meshPath)) return;

        if (loadingStatus.putIfAbsent("mesh:" + meshPath, Boolean.TRUE) == null) {
            virtualExecutor.submit(() -> {
                try {
                    System.out.println("[VirtualThread] Streaming 3D Mesh in background: " + meshPath);
                    ModelData data = ModelLoader.loadModel(meshPath);
                    if (data != null) {
                        pendingMeshes.add(new PendingMeshResult(meshPath, data));
                    }
                } catch (Exception e) {
                    System.err.println("[VirtualThread] Failed to stream 3D mesh: " + e.getMessage());
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
        if (texturePath == null || textureCache.containsKey(texturePath)) return;

        if (loadingStatus.putIfAbsent("tex:" + texturePath, Boolean.TRUE) == null) {
            virtualExecutor.submit(() -> {
                try {
                    System.out.println("[VirtualThread] Streaming 2D Texture in background: " + texturePath);
                    TextureData data = TextureLoader.loadTexture(texturePath);
                    if (data != null) {
                        pendingTextures.add(new PendingTextureResult(texturePath, data));
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

    public ModelData getModel(String path) {
        return meshCache.get(path);
    }

    public TextureData getTexture(String path) {
        return textureCache.get(path);
    }

    public boolean isMeshLoaded(String path) {
        return meshCache.containsKey(path);
    }

    public boolean isTextureLoaded(String path) {
        return textureCache.containsKey(path);
    }

    public void shutdown() {
        virtualExecutor.shutdown();
    }
}
