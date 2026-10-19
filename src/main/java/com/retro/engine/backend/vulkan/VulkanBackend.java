package com.retro.engine.backend.vulkan;

import com.retro.engine.rhi.BufferDesc;
import com.retro.engine.rhi.GraphicsAPI;
import com.retro.engine.rhi.IRenderBackend;
import com.retro.engine.rhi.TextureDesc;
import com.retro.engine.rhi.ViewportSpec;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * High-level facade for the modular Vulkan 1.3 rendering subsystem.
 * Coordinates VulkanDevice, VulkanSurface, VulkanSwapchain, VulkanPipeline, VulkanGpuCache, VulkanTargetPass, and VulkanPassEncoder.
 */
public class VulkanBackend implements IRenderBackend {

    private final VulkanDevice deviceManager = new VulkanDevice();
    private final VulkanSurface surfaceManager = new VulkanSurface();
    private final VulkanSwapchain swapchainManager = new VulkanSwapchain();
    private final VulkanPassEncoder passEncoder = new VulkanPassEncoder();
    private final VulkanPipeline pipelineManager = new VulkanPipeline();
    private final VulkanGpuCache gpuCache = new VulkanGpuCache();
    private final VulkanTargetPass targetPass = new VulkanTargetPass();

    private int activeImageIndex = 0;

    @Override
    public boolean initialize(ViewportSpec windowInfo) {
        System.out.println("[VulkanBackend] Modular Vulkan 1.3 Architecture Initializing...");

        if (!deviceManager.initialize(windowInfo.nativeWindowHandle())) {
            return false;
        }

        if (!surfaceManager.createSurface(deviceManager.getInstance(), windowInfo.nativeWindowHandle())) {
            return false;
        }

        if (!deviceManager.selectPhysicalDeviceAndCreateLogicalDevice(surfaceManager.getSurfaceHandle())) {
            return false;
        }

        if (!swapchainManager.createSwapchain(deviceManager, surfaceManager, windowInfo.width(), windowInfo.height())) {
            return false;
        }

        if (!passEncoder.initialize(deviceManager)) {
            return false;
        }

        targetPass.initFramebuffer(deviceManager, 712, 488);
        pipelineManager.initialize(deviceManager, swapchainManager);

        System.out.println("[VulkanBackend] Modular Vulkan 1.3 Driver Initialized Successfully.");
        return true;
    }

    @Override
    public void beginFrame() {
        VkDevice device = deviceManager.getDevice();
        if (device == null) return;

        VK10.vkWaitForFences(device, passEncoder.getInFlightFence(), true, Long.MAX_VALUE);
        VK10.vkResetFences(device, passEncoder.getInFlightFence());

        activeImageIndex = swapchainManager.acquireNextImage(deviceManager, passEncoder.getImageAvailableSemaphore());
        if (activeImageIndex < 0) return;

        try (MemoryStack stack = stackPush()) {
            VkCommandBuffer cmd = passEncoder.getCommandBuffer();
            VK10.vkResetCommandBuffer(cmd, 0);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            VK10.vkBeginCommandBuffer(cmd, beginInfo);

            long currentImageView = swapchainManager.getImageView(activeImageIndex);

            VkRenderingAttachmentInfo.Buffer colorAttachment = VkRenderingAttachmentInfo.calloc(1, stack)
                    .sType$Default()
                    .imageView(currentImageView)
                    .imageLayout(VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);

            colorAttachment.clearValue().color().float32(0, 0.10f).float32(1, 0.12f).float32(2, 0.15f).float32(3, 1.0f);

            VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack)
                    .sType$Default()
                    .layerCount(1)
                    .pColorAttachments(colorAttachment);
            renderingInfo.renderArea().extent().set(swapchainManager.getWidth(), swapchainManager.getHeight());

            VK13.vkCmdBeginRendering(cmd, renderingInfo);
        }
    }

    @Override
    public void endFrame() {
        VkCommandBuffer cmd = passEncoder.getCommandBuffer();
        if (cmd == null || activeImageIndex < 0) return;

        VK13.vkCmdEndRendering(cmd);
        VK10.vkEndCommandBuffer(cmd);

        try (MemoryStack stack = stackPush()) {
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType$Default()
                    .pWaitSemaphores(stack.longs(passEncoder.getImageAvailableSemaphore()))
                    .pWaitDstStageMask(stack.ints(VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                    .pCommandBuffers(stack.pointers(cmd))
                    .pSignalSemaphores(stack.longs(passEncoder.getRenderFinishedSemaphore()));

            VK10.vkQueueSubmit(deviceManager.getGraphicsQueue(), submitInfo, passEncoder.getInFlightFence());
        }
    }

    @Override
    public void present() {
        if (activeImageIndex < 0) return;
        swapchainManager.presentImage(deviceManager, activeImageIndex, passEncoder.getRenderFinishedSemaphore());
    }

    @Override
    public void shutdown() {
        VkDevice device = deviceManager.getDevice();
        if (device != null) {
            VK10.vkDeviceWaitIdle(device);
        }

        targetPass.cleanup(deviceManager);
        pipelineManager.cleanup(deviceManager);
        gpuCache.cleanup(deviceManager);
        passEncoder.cleanup(deviceManager);
        swapchainManager.cleanup(deviceManager);
        surfaceManager.cleanup(deviceManager);
        deviceManager.cleanup();

        System.out.println("[VulkanBackend] Modular Vulkan driver shutdown complete.");
    }

    @Override public void setCameraTransform(float panX, float panY, float zoom, float pitch, float yaw) {}
    @Override public void setSceneLight(int type, float posX, float posY, float posZ, float dirX, float dirY, float dirZ, float r, float g, float b, float intensity) {}
    @Override public void updateGridMesh(float[] vertices, int count) {}
    @Override public void updateUiMesh(float[] vertices, int count) {}
    @Override public void clearSceneQueue() {}
    @Override public void submitEntity(String meshPath, float[] vertices, int vertexCount, String texturePath, byte[] pixels, int texW, int texH, float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz, float groundY, float reflectionStrength) {}

    @Override
    public long getOffscreenViewportTexture() {
        return targetPass.getOffscreenColorTexture();
    }

    @Override
    public void resizeViewport(int width, int height) {
        targetPass.resizeViewport(deviceManager, width, height);
    }

    @Override public long createBuffer(BufferDesc createInfo) { return 101L; }
    @Override public void destroyBuffer(long handle) {}
    @Override public long createTexture(TextureDesc createInfo) { return 201L; }
    @Override public void destroyTexture(long handle) {}

    @Override public GraphicsAPI getAPI() { return GraphicsAPI.VULKAN; }
    @Override public String getDeviceName() { return "Modular Vulkan 1.3 Driver (Linux/Windows/MoltenVK)"; }
}
