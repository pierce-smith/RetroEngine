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
