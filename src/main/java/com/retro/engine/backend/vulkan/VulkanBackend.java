package com.retro.engine.backend.vulkan;

import com.retro.engine.rhi.BufferDesc;
import com.retro.engine.rhi.GraphicsAPI;
import com.retro.engine.rhi.IRenderBackend;
import com.retro.engine.rhi.TextureDesc;
import com.retro.engine.rhi.ViewportSpec;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Modular Vulkan 1.3 IRenderBackend implementation. Integrates Apple Silicon / MoltenVK
 * dynamic buffer alignment rules and orchestrates the 4-Phase rendering pipeline.
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
    private long minUniformAlignment = 16L;

    private float camPanX = 0.0f;
    private float camPanY = 0.25f;
    private float camZoom = -5.0f;
    private float camPitch = 0.0f;
    private float camYaw = 0.0f;

    private int activeLightType = 0;
    private float activeLightPosX = 0.0f, activeLightPosY = 5.0f, activeLightPosZ = 0.0f;
    private float activeLightDirX = -0.5f, activeLightDirY = -1.0f, activeLightDirZ = -0.5f;
    private float activeLightR = 1.0f, activeLightG = 0.95f, activeLightB = 0.85f;
    private float activeLightIntensity = 1.2f;

    private final List<VulkanPassEncoder.RenderEntity> sceneQueuePool = new ArrayList<>();
    private int activeSceneQueueCount = 0;

    @Override
    public boolean initialize(ViewportSpec windowInfo) {
        System.out.println("[VulkanBackend] Modular Vulkan 1.3 Driver Initializing...");

        if (!deviceManager.initialize(windowInfo.nativeWindowHandle())) return false;
        if (!surfaceManager.createSurface(deviceManager.getInstance(), windowInfo.nativeWindowHandle())) return false;
        if (!deviceManager.selectPhysicalDeviceAndCreateLogicalDevice(surfaceManager.getSurfaceHandle())) return false;

        queryPhysicalDeviceLimits();

        if (!swapchainManager.createSwapchain(deviceManager, surfaceManager, windowInfo.width(), windowInfo.height())) return false;
        if (!passEncoder.initialize(deviceManager)) return false;

        targetPass.initFramebuffer(deviceManager, 712, 488);
        if (!pipelineManager.initialize(deviceManager, swapchainManager)) return false;

        System.out.println("[VulkanBackend] Vulkan 1.3 Multi-Pass Architecture Active on Apple Silicon / MoltenVK.");
        return true;
    }

    private void queryPhysicalDeviceLimits() {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(deviceManager.getPhysicalDevice(), props);
            this.minUniformAlignment = props.limits().minUniformBufferOffsetAlignment();
            System.out.println("[VulkanBackend] Hardware minUniformBufferOffsetAlignment: " + minUniformAlignment + " bytes.");
        }
    }

    @Override
    public void beginFrame() {
        VkDevice device = deviceManager.getDevice();
        if (device == null) return;

        vkWaitForFences(device, passEncoder.getInFlightFence(), true, Long.MAX_VALUE);
        vkResetFences(device, passEncoder.getInFlightFence());

        activeImageIndex = swapchainManager.acquireNextImage(deviceManager, passEncoder.getImageAvailableSemaphore());
        if (activeImageIndex < 0) return;

        VkCommandBuffer cmd = passEncoder.getCommandBuffer();
        vkResetCommandBuffer(cmd, 0);

        try (MemoryStack stack = stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            vkBeginCommandBuffer(cmd, beginInfo);

            float[] viewMatrix = computeViewMatrix();
            float[] projMatrix = computePerspectiveProjectionMatrix(45.0f, (float) targetPass.getWidth() / (float) targetPass.getHeight(), 0.1f, 100.0f);

            passEncoder.record4PhasePipeline(
                    cmd,
                    targetPass,
                    swapchainManager,
                    activeImageIndex,
                    pipelineManager,
                    viewMatrix,
                    projMatrix
            );

            vkEndCommandBuffer(cmd);
        }
    }

    private float[] computeViewMatrix() {
        // Translation Matrix (Identity with camPanX, camPanY, camZoom)
        float[] view = new float[16];
        view[0] = 1.0f; view[5] = 1.0f; view[10] = 1.0f; view[15] = 1.0f;
        view[12] = camPanX;
        view[13] = camPanY;
        view[14] = camZoom;
        return view;
    }

    private float[] computePerspectiveProjectionMatrix(float fovDeg, float aspect, float zNear, float zFar) {
        float f = (float) (1.0 / Math.tan(Math.toRadians(fovDeg) / 2.0));
        float[] proj = new float[16];
        proj[0] = f / aspect;
        proj[5] = f;
        proj[10] = zFar / (zNear - zFar); // Vulkan clip space z: [0, 1]
        proj[11] = -1.0f;
        proj[14] = (zNear * zFar) / (zNear - zFar);
        return proj;
    }

    @Override
    public void endFrame() {
        if (activeImageIndex < 0) return;
        VkCommandBuffer cmd = passEncoder.getCommandBuffer();

        try (MemoryStack stack = stackPush()) {
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType$Default()
                    .pWaitSemaphores(stack.longs(passEncoder.getImageAvailableSemaphore()))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                    .pCommandBuffers(stack.pointers(cmd))
                    .pSignalSemaphores(stack.longs(passEncoder.getRenderFinishedSemaphore()));

            vkQueueSubmit(deviceManager.getGraphicsQueue(), submitInfo, passEncoder.getInFlightFence());
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
            vkDeviceWaitIdle(device);
        }

        targetPass.cleanup(deviceManager);
        pipelineManager.cleanup(deviceManager);
        gpuCache.cleanup(deviceManager);
        passEncoder.cleanup(deviceManager);
        swapchainManager.cleanup(deviceManager);
        surfaceManager.cleanup(deviceManager);
        deviceManager.cleanup();

        System.out.println("[VulkanBackend] Vulkan subsystem shutdown complete.");
    }

    @Override public long getOffscreenViewportTexture() { return targetPass.getOffscreenColorTexture(); }
    @Override public void resizeViewport(int width, int height) { targetPass.resizeViewport(deviceManager, width, height); }

    @Override public void setCameraTransform(float panX, float panY, float zoom, float pitch, float yaw) {
        this.camPanX = panX; this.camPanY = panY; this.camZoom = zoom; this.camPitch = pitch; this.camYaw = yaw;
    }

    @Override
    public void setSceneLight(int type, float posX, float posY, float posZ, float dirX, float dirY, float dirZ, float r, float g, float b, float intensity) {
        this.activeLightType = type;
        this.activeLightPosX = posX; this.activeLightPosY = posY; this.activeLightPosZ = posZ;
        this.activeLightDirX = dirX; this.activeLightDirY = dirY; this.activeLightDirZ = dirZ;
        this.activeLightR = r; this.activeLightG = g; this.activeLightB = b;
        this.activeLightIntensity = intensity;
    }

    @Override public void updateGridMesh(float[] vertices, int count) {}
    @Override public void updateUiMesh(float[] vertices, int count) {}
    @Override public void clearSceneQueue() { activeSceneQueueCount = 0; }

    @Override
    public void submitEntity(String meshPath, float[] vertices, int vertexCount, String texturePath, byte[] pixels, int texW, int texH, float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz, float groundY, float reflectionStrength) {
        VulkanPassEncoder.RenderEntity item;
        if (activeSceneQueueCount < sceneQueuePool.size()) {
            item = sceneQueuePool.get(activeSceneQueueCount);
        } else {
            item = new VulkanPassEncoder.RenderEntity();
            sceneQueuePool.add(item);
        }
        activeSceneQueueCount++;

        item.px = px; item.py = py; item.rz = rz;
        item.sx = sx; item.sy = sy; item.sz = sz;
        item.groundY = groundY;
        item.reflectionStrength = reflectionStrength;
    }

    @Override
    public void submitEntity(String meshPath, float[] vertices, int vertexCount, String texturePath, byte[] pixels, int texW, int texH, float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
        submitEntity(meshPath, vertices, vertexCount, texturePath, pixels, texW, texH, px, py, pz, rx, ry, rz, sx, sy, sz, 0.0f, 0.0f);
    }

    @Override public long createBuffer(BufferDesc createInfo) { return 101L; }
    @Override public void destroyBuffer(long handle) { }
    @Override public long createTexture(TextureDesc createInfo) { return 201L; }
    @Override public void destroyTexture(long handle) { }

    @Override public GraphicsAPI getAPI() { return GraphicsAPI.VULKAN; }
    @Override public String getDeviceName() { return "Modular Vulkan 1.3 Driver (MoltenVK / Apple Silicon)"; }
}
