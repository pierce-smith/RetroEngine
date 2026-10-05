package com.retro.engine.rhi;

public interface IRenderBackend {
    long INVALID_HANDLE = 0L;

    boolean initialize(WindowInfo windowInfo);
    void shutdown();

    void beginFrame();
    void endFrame();
    void present();

    default void updateMesh(float[] vertices, int count) {}
    default void updateUiMesh(float[] vertices, int count) {}
    default void updateTexture(byte[] pixels, int width, int height) {}
    default void setCameraTransform(float panX, float panY, float zoom, float pitch, float yaw) {}

    long createBuffer(BufferCreateInfo createInfo);
    void destroyBuffer(long handle);

    long createTexture(TextureCreateInfo createInfo);
    void destroyTexture(long handle);

    GraphicsAPI getAPI();
    String getDeviceName();
}
