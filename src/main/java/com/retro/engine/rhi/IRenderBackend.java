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
    /**
     * Explicit scene camera: a column-major world-to-view matrix (same conventions as the view built from
     * {@link #setCameraTransform}) and the eye's world position. While set it replaces the orbit transform for every
     * scene pass; pass null to return to the orbit transform. The matrix is copied, never retained.
     */
    default void setCameraView(float[] view, float eyeX, float eyeY, float eyeZ) {}

    /** Perspective parameters for the scene camera (editor default 50 deg / 0.1 / 500; Play mode uses the Main Camera's). */
    default void setCameraProjection(float fovDeg, float near, float far) {}
    
    /**
     * Active Directional, Point and Spot lights for the lit scene passes. An empty set falls back to the backend's
     * default sun. The set is copied, never retained.
     */
    default void setSceneLights(SceneLights lights) {}

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
    /** Drops the submitted draw list; the next frame renders no scene geometry until another queue is submitted. */
    void clearSceneQueue();

    /**
     * Uploads a mesh to GPU memory once and returns an opaque handle for {@link RenderCommand#meshHandle}.
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
     * Replaces this frame's draw list with {@code queue}, already sorted ({@link RenderQueue#sort()}) and carrying the
     * fitted {@link RenderQueue#lightSpaceMatrix}. Backends resolve handles here and read the commands again while
     * encoding the next {@link #beginFrame}, so the queue must not be modified until that call returns.
     * A 0L or stale meshHandle skips the draw; a 0L or stale texture handle leaves that material channel absent.
     */
    void submitRenderQueue(RenderQueue queue);

    /** Color-ID value of the cleared pick target background and of draws that carry no pick ID. */
    int PICK_ID_NONE = 0;
    /** {@link #queryPick} result when no completed readback sampled the requested texel. */
    int PICK_UNAVAILABLE = -1;

    /**
     * Arms the Color-ID probe. While armed, each frame renders the queued commands' pick IDs (editor camera) into an
     * offscreen R8G8B8A8_UINT target the size of the scene target, packed little-endian (r = bits 0-7 ... a = bits
     * 24-31), and copies back the single texel at scene-tile UV (u, v), origin top-left. Texel = (int) (u * width),
     * (int) (v * height). Coordinates outside [0, 1) disarm the probe and skip the pass.
     */
    default void setPickProbe(float u, float v) {}

    /**
     * Pick ID at scene-tile UV (u, v) from the newest completed readback, or {@link #PICK_UNAVAILABLE} when the backend
     * has no ID pass or that readback sampled a different texel (the probe has not caught up with the cursor yet).
     * Never blocks on the GPU.
     */
    default int queryPick(float u, float v) { return PICK_UNAVAILABLE; }

    void beginFrame();
    void endFrame();
    void present();
    void shutdown();

    long getOffscreenViewportTexture();
    void resizeViewport(int width, int height);

    /**
     * GLFW framebuffer-size hook: the window's backing store is now width x height pixels (resize, maximize,
     * fullscreen toggle, or a move to a display with a different scale). Backends with an explicit swapchain rebuild
     * it before the next acquire; backends whose drawable tracks the window automatically ignore it.
     */
    default void onFramebufferResized(int width, int height) {}

    long createBuffer(BufferDesc createInfo);
    void destroyBuffer(long handle);
    long createTexture(TextureDesc createInfo);
    void destroyTexture(long handle);

    GraphicsAPI getAPI();
    String getDeviceName();
}
