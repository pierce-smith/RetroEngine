package com.retro.engine.rhi;

/**
 * Render Hardware Interface (RHI) abstraction contract for cross-platform backends.
 * Extended with Offscreen Viewport Render Target, unified scene lighting support,
 * and 64-matrix GPU skeletal animation matrix palette streaming.
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

    void submitEntity(String meshPath, float[] vertices, int vertexCount, 
                      String texturePath, byte[] pixels, int texW, int texH, 
                      float px, float py, float pz, 
                      float rx, float ry, float rz, 
                      float sx, float sy, float sz, 
                      float groundY, float reflectionStrength);

    default void submitEntity(String meshPath, float[] vertices, int vertexCount, 
                              String texturePath, byte[] pixels, int texW, int texH, 
                              float px, float py, float pz, 
                              float rx, float ry, float rz, 
                              float sx, float sy, float sz) {
        submitEntity(meshPath, vertices, vertexCount, texturePath, pixels, texW, texH, 
                     px, py, pz, rx, ry, rz, sx, sy, sz, 0.0f, 0.0f);
    }

    default void submitEntity(String meshPath, float[] vertices, int vertexCount, 
                              String texturePath, byte[] pixels, int texW, int texH, 
                              float px, float py, float pz, 
                              float rx, float ry, float rz, 
                              float sx, float sy, float sz, 
                              float groundY, float reflectionStrength,
                              float[][] bonePalette) {
        submitEntity(meshPath, vertices, vertexCount, texturePath, pixels, texW, texH, 
                     px, py, pz, rx, ry, rz, sx, sy, sz, groundY, reflectionStrength);
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
