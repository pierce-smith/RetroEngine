package com.retro.engine.assets;

import com.retro.engine.graphics.IRenderBackend;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

/**
 * Asset streaming on JDK 25 virtual threads
 * (Executors.newVirtualThreadPerTaskExecutor()) for non-blocking asynchronous disk I/O,
 * background Assimp model parsing, ImageIO texture decoding, and pre-cooked .rpack VRAM archive loading.
 *
 * Implements atomic double-buffered hot-reloading with lock-free ConcurrentHashMaps
 * and main-thread frame boundary completion callbacks (processCompletedUploads()) to eliminate
 * CPU/GPU rendering hitches and mutex lock contention.
 *
 * Once a render backend is bound, streamed assets are uploaded to the GPU on the same virtual thread
 * that decoded them, and the resulting opaque handles are published before the asset reports as loaded.
 * The render thread then submits by handle only, never by raw vertex or pixel arrays.
 *
 * Streamed assets are reference-tracked by frame stamps: the render thread {@link #markMeshReferenced marks} every
 * asset a live entity uses each frame, and {@link #sweepUnreferenced} evicts assets nothing has marked for a grace
 * period, releasing both the CPU copy and the GPU handle. Scene unloads and entity deletions therefore return their
 * VRAM and host RAM without explicit hooks, and an undo within the grace period finds the asset still resident.
 *
 * Texture pixels leave the heap once the GPU has them (the backends copy them during registerTexture), and only
 * {@value #MAX_CONCURRENT_TEXTURE_DECODES} textures decode at a time, so heap use doesn't grow with a project's textures.
 */
public class AssetStreamer {

    private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // Standalone builds: raw Assets/ paths resolve to pre-cooked packages under Game_Data/Cooked/
    private static volatile File cookedAssetsRoot = null;
    private static volatile File cookedOutputRoot = null;

    public static void setCookedMirror(File assetsRoot, File cookedRoot) {
        cookedAssetsRoot = (assetsRoot != null) ? assetsRoot.getAbsoluteFile() : null;
        cookedOutputRoot = (cookedRoot != null) ? cookedRoot.getAbsoluteFile() : null;
    }

    /** Pre-cooked .rpack for a raw asset path: the sidecar next to it, else the cooked mirror; null when neither exists. */
    private static String findCookedPackage(String filePath) {
        String sidecar = filePath + ".rpack";
        if (new File(sidecar).exists()) return sidecar;
        File assetsRoot = cookedAssetsRoot, cookedRoot = cookedOutputRoot;
        if (assetsRoot == null || cookedRoot == null) return null;
        File raw = new File(filePath).getAbsoluteFile();
        if (!raw.toPath().normalize().startsWith(assetsRoot.toPath().normalize())) return null;
        File cooked = com.retro.engine.assets.RpackCookerCli.cookedPathFor(assetsRoot, cookedRoot, raw);
        return cooked.isFile() ? cooked.getPath() : null;
    }

    /** Reads a mesh on the calling thread, cooked package first, for systems that need its data rather than a GPU handle. */
    public static MeshBufferData readMeshNow(String filePath) {
        if (filePath == null || filePath.isEmpty()) return null;
        String cooked = filePath.endsWith(".rpack") ? filePath : findCookedPackage(filePath);
        MeshBufferData data = cooked != null ? RpackCooker.readRpackMesh(cooked) : null;
        return data != null ? data : MeshImporter.loadModel(filePath);
    }

    private final Map<String, MeshBufferData> modelCache = new ConcurrentHashMap<>();
    private final Map<String, PixelBufferData> textureCache = new ConcurrentHashMap<>();

    private final Set<String> activeModelRequests = ConcurrentHashMap.newKeySet();
    private final Set<String> activeTextureRequests = ConcurrentHashMap.newKeySet();
    // Textures that couldn't be loaded, with the file's timestamp at the time. The render loop asks for a missing
    // texture every frame, so this stops it re-reading the file until the file changes
    private final Map<String, Long> failedTextures = new ConcurrentHashMap<>();

    // One 2K texture peaks near 55 MB of heap while it decodes and builds mips, and a 4K texture four times that
    private static final int MAX_CONCURRENT_TEXTURE_DECODES = 2;
    private final Semaphore textureDecodeSlots = new Semaphore(MAX_CONCURRENT_TEXTURE_DECODES);

    private final ConcurrentLinkedQueue<String> completedUploadsQueue = new ConcurrentLinkedQueue<>();

    private final Map<String, Long> meshHandles = new ConcurrentHashMap<>();
    private final Map<String, Long> textureHandles = new ConcurrentHashMap<>();
    // Bumped whenever a handle is unregistered so components can invalidate their cached handles
    private final AtomicInteger handleEpoch = new AtomicInteger();
    private volatile IRenderBackend renderBackend;

    // ~10 s at 60 FPS before an unreferenced asset is evicted; the sweep itself runs every ~2 s
    private static final int EVICTION_GRACE_FRAMES = 600;
    private static final int SWEEP_INTERVAL_FRAMES = 120;

    /** Last render frame that referenced a streamed asset; created once per load, then stamped allocation-free. */
    private static final class Residency {
        final String key;
        final boolean texture;
        long lastReferencedFrame;

        Residency(String key, boolean texture, long frame) {
            this.key = key;
            this.texture = texture;
            this.lastReferencedFrame = frame;
        }
    }

    private final Map<String, Residency> meshResidency = new ConcurrentHashMap<>();
    private final Map<String, Residency> textureResidency = new ConcurrentHashMap<>();
    // Streaming threads publish new entries here; the render thread drains them into its own indexable list
    private final ConcurrentLinkedQueue<Residency> newResidency = new ConcurrentLinkedQueue<>();
    private final ArrayList<Residency> trackedResidency = new ArrayList<>();
    private volatile long residencyFrame = 0L;

    /**
     * Binds the GPU backend that streamed assets are registered with. Assets already resident in the
     * CPU caches are registered immediately. Pass null to detach (handles then resolve to 0L).
     */
    public void bindRenderBackend(IRenderBackend backend) {
        this.renderBackend = backend;
        if (backend == null) return;
        modelCache.forEach(this::registerMeshHandle);
        // Textures with no pixels left belong to the previous backend, so they stream again
        textureCache.values().removeIf(data -> data.pixels == null);
        textureCache.forEach(this::registerTextureHandle);
    }

    private void registerMeshHandle(String key, MeshBufferData data) {
        IRenderBackend backend = renderBackend;
        if (backend == null || data == null) return;
        long handle = backend.registerMesh(key, data.vertices, data.vertexCount, data.indices, data.indexCount);
        if (handle == 0L) return;
        // A different live handle under the same key would otherwise be dropped without ever being unregistered
        Long previous = meshHandles.put(key, handle);
        if (previous != null && previous != handle) {
            handleEpoch.incrementAndGet();
            backend.unregisterMesh(previous);
        }
    }

    /** True when the GPU now holds the texture. */
    private boolean registerTextureHandle(String key, PixelBufferData data) {
        IRenderBackend backend = renderBackend;
        if (backend == null || data == null) return false;
        long handle = backend.registerTexture(key, data.pixels, data.width, data.height);
        if (handle == 0L) return false;
        Long previous = textureHandles.put(key, handle);
        if (previous != null && previous != handle) {
            handleEpoch.incrementAndGet();
            backend.unregisterTexture(previous);
        }
        return true;
    }

    private void trackResidency(Map<String, Residency> table, String key, boolean texture) {
        // A fresh load counts as referenced now, so it survives until the requesting entity marks it
        if (table.get(key) != null) return;
        Residency entry = new Residency(key, texture, residencyFrame);
        if (table.putIfAbsent(key, entry) == null) newResidency.add(entry);
    }

    /** Render thread: a live entity uses this mesh this frame. Allocation-free; unknown paths are ignored. */
    public void markMeshReferenced(String filePath) {
        if (filePath == null) return;
        Residency entry = meshResidency.get(filePath);
        if (entry != null) entry.lastReferencedFrame = residencyFrame;
    }

    /** Render thread: a live entity's material uses this texture this frame. Allocation-free; unknown paths are ignored. */
    public void markTextureReferenced(String filePath) {
        if (filePath == null) return;
        Residency entry = textureResidency.get(filePath);
        if (entry != null) entry.lastReferencedFrame = residencyFrame;
    }

    /**
     * Render thread, once per frame after every live entity has been marked: advances the residency clock and, every
     * {@value #SWEEP_INTERVAL_FRAMES} frames, unregisters and evicts streamed assets unreferenced for
     * {@value #EVICTION_GRACE_FRAMES} frames. Backends defer the GPU release until in-flight frames retire.
     */
    public void sweepUnreferenced() {
        long frame = ++residencyFrame;
        for (Residency entry; (entry = newResidency.poll()) != null; ) {
            trackedResidency.add(entry);
        }
        if (frame % SWEEP_INTERVAL_FRAMES != 0) return;

        for (int i = trackedResidency.size() - 1; i >= 0; i--) {
            Residency entry = trackedResidency.get(i);
            if (frame - entry.lastReferencedFrame < EVICTION_GRACE_FRAMES) continue;

            // Entries no longer in their table (superseded by a newer load) are just untracked, otherwise they would be
            // re-checked on every sweep forever
            if (entry.texture) {
                if (textureResidency.remove(entry.key, entry)) {
                    textureCache.remove(entry.key);
                    unregisterTextureHandle(entry.key);
                }
            } else {
                if (meshResidency.remove(entry.key, entry)) {
                    modelCache.remove(entry.key);
                    unregisterMeshHandle(entry.key);
                }
            }
            // Swap-remove keeps the sweep allocation-free and O(1) per eviction
            int last = trackedResidency.size() - 1;
            trackedResidency.set(i, trackedResidency.get(last));
            trackedResidency.remove(last);
        }
    }

    private void unregisterMeshHandle(String key) {
        Long handle = meshHandles.remove(key);
        IRenderBackend backend = renderBackend;
        if (handle != null) {
            handleEpoch.incrementAndGet();
            if (backend != null) backend.unregisterMesh(handle);
        }
    }

    private void unregisterTextureHandle(String key) {
        Long handle = textureHandles.remove(key);
        IRenderBackend backend = renderBackend;
        if (handle != null) {
            handleEpoch.incrementAndGet();
            if (backend != null) backend.unregisterTexture(handle);
        }
    }

    /** GPU mesh handle for a streamed asset, or 0L if not yet registered (or no backend is bound). */
    public long getMeshHandle(String filePath) {
        if (filePath == null) return 0L;
        Long handle = meshHandles.get(filePath);
        return handle != null ? handle : 0L;
    }

    /** GPU texture handle for a streamed asset, or 0L if not yet registered (or no backend is bound). */
    public long getTextureHandle(String filePath) {
        if (filePath == null) return 0L;
        Long handle = textureHandles.get(filePath);
        return handle != null ? handle : 0L;
    }

    /** Registers procedural (non-streamed) geometry such as the fallback cube under assetKey, once. */
    public long resolveMeshHandle(String assetKey, MeshBufferData data) {
        long handle = getMeshHandle(assetKey);
        if (handle == 0L) {
            registerMeshHandle(assetKey, data);
            handle = getMeshHandle(assetKey);
        }
        return handle;
    }

    /** Frees geometry registered through {@link #resolveMeshHandle}; a later resolve uploads it again. */
    public void releaseMeshHandle(String assetKey) {
        unregisterMeshHandle(assetKey);
    }

    /** Texture counterpart of {@link #resolveMeshHandle}, for generated images. */
    public long resolveTextureHandle(String assetKey, PixelBufferData data) {
        long handle = getTextureHandle(assetKey);
        if (handle == 0L) {
            registerTextureHandle(assetKey, data);
            handle = getTextureHandle(assetKey);
        }
        return handle;
    }

    /** Changes whenever any handle is unregistered; a cached handle is only valid for the epoch it was read in. */
    public int getHandleEpoch() {
        return handleEpoch.get();
    }

    public void requestModelAsync(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) return;
        if (modelCache.containsKey(filePath) || !activeModelRequests.add(filePath)) return;

        ioExecutor.submit(() -> {
            try {
                MeshBufferData data = null;

                // 1. Fast Path: Read pre-cooked VRAM package (.rpack) if available
                if (filePath.endsWith(".rpack")) {
                    data = RpackCooker.readRpackMesh(filePath);
                } else {
                    String cooked = findCookedPackage(filePath);
                    if (cooked != null) {
                        data = RpackCooker.readRpackMesh(cooked);
                    }
                }

                // 2. Fallback Path: Raw Assimp model parsing
                if (data == null) {
                    data = MeshImporter.loadModel(filePath);
                }

                if (data != null && data.vertices != null && data.vertices.length > 0) {
                    // Upload before publishing so isMeshLoaded() never reports true without a GPU handle
                    registerMeshHandle(filePath, data);
                    modelCache.put(filePath, data);
                    trackResidency(meshResidency, filePath, false);
                    completedUploadsQueue.add("[Mesh] " + new File(filePath).getName());
                }
            } catch (Exception e) {
                System.err.println("[AssetStreamer] Error streaming mesh '" + filePath + "': " + e.getMessage());
            } finally {
                activeModelRequests.remove(filePath);
            }
        });
    }

    public void requestTextureAsync(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) return;
        if (textureCache.containsKey(filePath) || isUnchangedFailure(filePath) || !activeTextureRequests.add(filePath)) return;

        ioExecutor.submit(() -> {
            boolean decoding = false;
            try {
                textureDecodeSlots.acquire();
                decoding = true;
                PixelBufferData data = null;

                // 1. Fast Path: Read pre-cooked VRAM package (.rpack) if available
                if (filePath.endsWith(".rpack")) {
                    data = RpackCooker.readRpackTexture(filePath);
                } else {
                    String cooked = findCookedPackage(filePath);
                    if (cooked != null) {
                        data = RpackCooker.readRpackTexture(cooked);
                    }
                }

                // 2. Fallback Path: Raw ImageIO PNG/JPG decoding
                if (data == null) {
                    File file = new File(filePath);
                    if (file.exists()) {
                        BufferedImage image = ImageIO.read(file);
                        if (image != null) {
                            int w = image.getWidth();
                            int h = image.getHeight();
                            // A row at a time: a whole-image int[] would be one more full copy on the heap
                            int[] row = new int[w];
                            byte[] pixelsByte = new byte[w * h * 4];
                            int out = 0;
                            for (int y = 0; y < h; y++) {
                                image.getRGB(0, y, w, 1, row, 0, w);
                                for (int x = 0; x < w; x++) {
                                    int argb = row[x];
                                    pixelsByte[out++] = (byte) ((argb >> 16) & 0xFF); // R
                                    pixelsByte[out++] = (byte) ((argb >> 8) & 0xFF);  // G
                                    pixelsByte[out++] = (byte) (argb & 0xFF);         // B
                                    pixelsByte[out++] = (byte) ((argb >> 24) & 0xFF); // A
                                }
                            }
                            data = new PixelBufferData(pixelsByte, w, h);
                        }
                    }
                }

                if (data != null && data.pixels != null && data.pixels.length > 0) {
                    textureCache.put(filePath, registerTextureHandle(filePath, data) ? data.withoutPixels() : data);
                    trackResidency(textureResidency, filePath, true);
                    completedUploadsQueue.add("[Texture] " + new File(filePath).getName());
                } else {
                    failTexture(filePath, "the file is missing or isn't a readable image");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Shutting down
            } catch (Exception e) {
                failTexture(filePath, e.toString());
            } catch (OutOfMemoryError e) {
                failTexture(filePath, "it needs more memory than the heap has free");
            } finally {
                if (decoding) textureDecodeSlots.release();
                activeTextureRequests.remove(filePath);
            }
        });
    }

    private boolean isUnchangedFailure(String filePath) {
        Long failedAt = failedTextures.get(filePath);
        return failedAt != null && failedAt == new File(filePath).lastModified();
    }

    private void failTexture(String filePath, String reason) {
        failedTextures.put(filePath, new File(filePath).lastModified());
        System.err.println("[AssetStreamer] Could not load texture '" + filePath + "': " + reason);
    }

    public boolean isMeshLoaded(String filePath) {
        if (filePath == null) return false;
        return modelCache.containsKey(filePath);
    }

    public boolean isTextureLoaded(String filePath) {
        if (filePath == null) return false;
        return textureCache.containsKey(filePath);
    }

    public MeshBufferData getModel(String filePath) {
        if (filePath == null) return null;
        return modelCache.get(filePath);
    }

    /** Size and name of a loaded texture. Its pixels are null once the GPU holds them. */
    public PixelBufferData getTexture(String filePath) {
        if (filePath == null) return null;
        return textureCache.get(filePath);
    }

    /**
     * Executes atomic double-buffered hot-reloads on main thread frame boundaries.
     */
    public void processCompletedUploads() {
        String completionNotice;
        while ((completionNotice = completedUploadsQueue.poll()) != null) {
            System.out.println("[AssetStreamer] Virtual Thread Streamed: " + completionNotice);
        }
    }

    public void reloadModelAsync(String filePath) {
        if (filePath == null) return;
        modelCache.remove(filePath);
        unregisterMeshHandle(filePath);
        requestModelAsync(filePath);
    }

    public void reloadTextureAsync(String filePath) {
        if (filePath == null) return;
        textureCache.remove(filePath);
        failedTextures.remove(filePath);
        unregisterTextureHandle(filePath);
        requestTextureAsync(filePath);
    }

    public void clearCaches() {
        meshHandles.keySet().forEach(this::unregisterMeshHandle);
        textureHandles.keySet().forEach(this::unregisterTextureHandle);
        modelCache.clear();
        textureCache.clear();
        meshResidency.clear();
        textureResidency.clear();
        newResidency.clear();
        trackedResidency.clear();
        activeModelRequests.clear();
        activeTextureRequests.clear();
        failedTextures.clear();
        completedUploadsQueue.clear();
        System.out.println("[AssetStreamer] Asset caches flushed.");
    }

    public void shutdown() {
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        clearCaches();
        renderBackend = null;
        System.out.println("[AssetStreamer] Virtual Thread Executor shutdown complete.");
    }
}
