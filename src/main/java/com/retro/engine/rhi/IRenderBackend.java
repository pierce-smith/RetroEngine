package com.retro.engine.rhi;

public interface IRenderBackend {
    long INVALID_HANDLE = 0L;

    boolean initialize(WindowInfo windowInfo);
    void shutdown();

    void beginFrame();
    void endFrame();
    void present();

    default void updateUiMesh(float[] vertices, int count) {}

    long createBuffer(BufferCreateInfo createInfo);
    void destroyBuffer(long handle);

    long createTexture(TextureCreateInfo createInfo);
    void destroyTexture(long handle);

    GraphicsAPI getAPI();
    String getDeviceName();
}