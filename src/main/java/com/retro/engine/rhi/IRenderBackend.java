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

    /**
     * Picture-in-picture camera preview: while active, the queued scene is rendered a second time from {@code viewProj}
     * (column-major, same clip conventions as the editor camera, camera world position for lighting) into the target
     * returned by {@link #getCameraPreviewTexture}, before the UI samples it. viewProj is copied, never retained.
     */
    default void setCameraPreview(boolean active, float[] viewProj, float camX, float camY, float camZ) {}

    /** Opaque handle of the camera preview color target, or 0L when the backend has no preview pass. */
    default long getCameraPreviewTexture() { return 0L; }

    void updateGridMesh(float[] vertices, int count);
    void updateUiMesh(float[] vertices, int count);

    /**
     * Binds a {@link #registerTexture registered} signed-distance-field atlas for UI glyph quads. Returns false when the
     * backend cannot sample it, in which case the editor keeps its bitmap font.
     */
    default boolean setUiFontAtlas(long textureHandle) { return false; }
    void clearSceneQueue();

    /**
     * Uploads a mesh to GPU memory once and returns an opaque handle for {@link #submitEntity}.
     * Registering an assetKey that is already live returns the existing handle without re-uploading.
     * Safe to call from any thread (e.g. AssetStreamer virtual threads). Returns 0L when the backend
     * has no GPU (headless/null rendering) or the upload fails.
     */
    default long registerMesh(String assetKey, float[] vertices, int vertexCount) {
        return registerMesh(assetKey, vertices, vertexCount, null, 0);
    }

    /**
     * Indexed variant of {@link #registerMesh(String, float[], int)}. When indices is null or indexCount is 0
     * the mesh is drawn non-indexed; otherwise the backend picks 16-bit indices when every index fits in a
     * uint16 and 32-bit indices otherwise.
     */
    default long registerMesh(String assetKey, float[] vertices, int vertexCount, int[] indices, int indexCount) { return 0L; }

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

    /**
     * Multi-channel material variant of {@link #submitEntity}. Backends without multi-texture support fall back to the
     * albedo channel and reflection strength; the binding is read immediately and never retained.
     */
    default void submitEntity(long meshHandle, MaterialBinding material,
                              float px, float py, float pz,
                              float rx, float ry, float rz,
                              float sx, float sy, float sz,
                              float groundY, float[][] bonePalette) {
        long albedo = (material != null) ? material.albedoTexture : 0L;
        float reflect = (material != null) ? material.reflect : 0.0f;
        submitEntity(meshHandle, albedo, px, py, pz, rx, ry, rz, sx, sy, sz, groundY, reflect, bonePalette);
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
