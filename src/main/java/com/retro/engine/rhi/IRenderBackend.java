package com.retro.engine.rhi;

/**
 * Render Hardware Interface (RHI) abstraction contract for cross-platform backends.
 * Extended with Offscreen Viewport Render Target support.
 */
public interface IRenderBackend {
    boolean initialize(WindowInfo windowInfo);
    void setCameraTransform(float panX, float panY, float zoom, float pitch, float yaw);
    void updateGridMesh(float[] vertices, int count);
    void updateUiMesh(float[] vertices, int count);
    void clearSceneQueue();
    void submitEntity(String meshPath, float[] vertices, int vertexCount, String texturePath, byte[] pixels, int texW, int texH, float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz);

    void beginFrame();
    void endFrame();
    void present();
    void shutdown();

    long getOffscreenViewportTexture();
    void resizeViewport(int width, int height);

    long createBuffer(BufferCreateInfo createInfo);
    void destroyBuffer(long handle);
    long createTexture(TextureCreateInfo createInfo);
    void destroyTexture(long handle);
    GraphicsAPI getAPI();
    String getDeviceName();
}
