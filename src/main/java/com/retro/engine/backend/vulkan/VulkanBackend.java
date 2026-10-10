package com.retro.engine.backend.vulkan;

import com.retro.engine.rhi.*;

public class VulkanBackend implements IRenderBackend {

    @Override
    public boolean initialize(WindowInfo windowInfo) {
        System.out.println("[VulkanBackend] Initializing Vulkan 1.3 Driver Context...");
        return true;
    }

    @Override
    public void shutdown() {
        System.out.println("[VulkanBackend] Destroying Vulkan Context...");
    }

    @Override
    public void beginFrame() {}

    @Override
    public void endFrame() {}

    @Override
    public void present() {}

    @Override
    public void updateMesh(float[] vertices, int count) {}

    @Override
    public void updateGridMesh(float[] vertices, int count) {}

    @Override
    public void updateUiMesh(float[] vertices, int count) {}

    @Override
    public void updateUiBackgroundMesh(float[] vertices, int count) {}

    @Override
    public void updateTexture(byte[] pixels, int width, int height) {}

    @Override
    public void setCameraTransform(float panX, float panY, float zoom, float pitch, float yaw) {}

    @Override
    public void setModelTransform(float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {}

    @Override
    public void clearSceneQueue() {}

    @Override
    public void submitEntity(String meshPath, float[] vertices, int vertexCount,
                              String texturePath, byte[] pixels, int texW, int texH,
                              float px, float py, float pz,
                              float rx, float ry, float rz,
                              float sx, float sy, float sz) {}

    @Override
    public long createBuffer(BufferCreateInfo createInfo) {
        return 101L;
    }

    @Override
    public void destroyBuffer(long handle) {}

    @Override
    public long createTexture(TextureCreateInfo createInfo) {
        return 201L;
    }

    @Override
    public void destroyTexture(long handle) {}

    @Override
    public GraphicsAPI getAPI() {
        return GraphicsAPI.VULKAN;
    }

    @Override
    public String getDeviceName() {
        return "Vulkan 1.3 Driver (Linux/Windows)";
    }
}
