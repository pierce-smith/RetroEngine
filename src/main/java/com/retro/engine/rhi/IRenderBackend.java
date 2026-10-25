package com.retro.engine.rhi;

/**
 * Render Hardware Interface (RHI) abstraction contract for cross-platform backends.
 * Extended with Offscreen Viewport Render Target, unified scene lighting support,
 * 64-matrix GPU skeletal animation matrix palette streaming, and handle-based asset registration
 * (meshes and textures are uploaded once; frames reference them by opaque 64-bit handle).
 */
public interface IRenderBackend {
    boolean initialize(ViewportSpec windowInfo);

    void setCameraTransform(float panX, float panY, float zoom, float pitch, float yaw);
    
    default void setSceneLight(int lightType, float posX, float posY, float posZ, 
                              float dirX, float dirY, float dirZ, 
                              float r, float g, float b, float intensity) {}

    /**
     * Screen-space NDC rect the offscreen scene image is composited into (the docked Scene tile),
     * plus the projection aspect matching that tile. A zero-area rect means the Scene tab is hidden.
     */
    default void setSceneViewport(float minX, float minY, float maxX, float maxY, float aspect) {}

    void updateGridMesh(float[] vertices, int count);
    void updateUiMesh(float[] vertices, int count);
    void clearSceneQueue();

    /**
     * Uploads a mesh to GPU memory once and returns an opaque handle for {@link #submitEntity}.
     * Registering an assetKey that is already live returns the existing handle without re-uploading.
     * Safe to call from any thread (e.g. AssetStreamer virtual threads). Returns 0L when the backend
     * has no GPU (headless/null rendering) or the upload fails.
     */
    default long registerMesh(String assetKey, float[] vertices, int vertexCount) { return 0L; }

    /** Texture counterpart of {@link #registerMesh}; pixels are tightly packed RGBA8. */
    default long registerTexture(String assetKey, byte[] pixels, int width, int height) { return 0L; }

    /** Releases a mesh handle. GPU memory is reclaimed once in-flight frames no longer reference it. */
    default void unregisterMesh(long meshHandle) {}

    /** Releases a texture handle. GPU memory is reclaimed once in-flight frames no longer reference it. */
    default void unregisterTexture(long textureHandle) {}

    /**
     * Queues one entity for this frame using handles from {@link #registerMesh} / {@link #registerTexture}.
     * A 0L or stale meshHandle skips the draw; a 0L or stale textureHandle falls back to the default white texture.
     */
    void submitEntity(long meshHandle, long textureHandle,
                      float px, float py, float pz,
                      float rx, float ry, float rz,
                      float sx, float sy, float sz,
                      float groundY, float reflectionStrength,
                      float[][] bonePalette);

    default void submitEntity(long meshHandle, long textureHandle,
                              float px, float py, float pz,
                              float rx, float ry, float rz,
                              float sx, float sy, float sz,
                              float groundY, float reflectionStrength) {
        submitEntity(meshHandle, textureHandle, px, py, pz, rx, ry, rz, sx, sy, sz, groundY, reflectionStrength, null);
    }

    default void submitEntity(long meshHandle, long textureHandle,
                              float px, float py, float pz,
                              float rx, float ry, float rz,
                              float sx, float sy, float sz) {
        submitEntity(meshHandle, textureHandle, px, py, pz, rx, ry, rz, sx, sy, sz, 0.0f, 0.0f, null);
    }

    void beginFrame();
    void endFrame();
    void present();
    void shutdown();

    long getOffscreenViewportTexture();
    void resizeViewport(int width, int height);

    long createBuffer(BufferDesc createInfo);
    void destroyBuffer(long handle);
    long createTexture(TextureDesc createInfo);
    void destroyTexture(long handle);

    GraphicsAPI getAPI();
    String getDeviceName();
}
