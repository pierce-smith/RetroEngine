package com.retro.engine.backend.vulkan;

import com.retro.engine.rhi.BufferDesc;
import com.retro.engine.rhi.GraphicsAPI;
import com.retro.engine.rhi.IRenderBackend;
import com.retro.engine.rhi.RenderQueue;
import com.retro.engine.rhi.SceneLights;
import com.retro.engine.rhi.TextureDesc;
import com.retro.engine.rhi.ViewportSpec;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Modular Vulkan 1.3 IRenderBackend implementation. Integrates Apple Silicon / MoltenVK
 * dynamic buffer alignment rules, zero-allocation frame loops, and orchestrates
 * the 4-Phase rendering pipeline with 3-slot ring buffers, a bindless multi-channel material table,
 * synchronization2 frame submission and asynchronous timeline-semaphore texture uploads.
 */
public class VulkanBackend implements IRenderBackend {

    private final VulkanDevice deviceManager = new VulkanDevice();
    private final VulkanSurface surfaceManager = new VulkanSurface();
    private final VulkanSwapchain swapchainManager = new VulkanSwapchain();
    private final VulkanPassEncoder passEncoder = new VulkanPassEncoder();
    private final VulkanPipeline pipelineManager = new VulkanPipeline();
    private final VulkanGpuCache gpuCache = new VulkanGpuCache();
    private final VulkanTargetPass targetPass = new VulkanTargetPass();
    // Picture-in-picture render target for the selected Camera entity, sampled by the UI pass from the bindless table.
    // The editor sets the projection aspect, so a fixed 16:9 resolution is only stretched, never distorted.
    private final VulkanTargetPass cameraPreviewTarget = new VulkanTargetPass();
    private static final int CAMERA_PREVIEW_WIDTH = 640;
    private static final int CAMERA_PREVIEW_HEIGHT = 360;
    private int cameraPreviewSlot = VulkanGpuCache.NO_TEXTURE;
    private boolean cameraPreviewActive = false;
    private final float[] cameraPreviewViewProj = new float[16];
    private float cameraPreviewX, cameraPreviewY, cameraPreviewZ;
    private final VulkanQueueSubmitter queueSubmitter = new VulkanQueueSubmitter();

    // Color-ID picking: R8G8B8A8_UINT target matching the scene target, plus a persistently mapped 4-byte readback buffer.
    // One frame is in flight, so the texel copied by frame N is read right after beginFrame N+1 waits on its fence.
    private final VulkanTargetPass pickTarget = new VulkanTargetPass(VulkanPipeline.PICK_ID_FORMAT);
    private long pickReadbackBuffer = 0L;
    private long pickReadbackMemory = 0L;
    private long pickReadbackMapped = 0L;
    private float pickProbeU = -1.0f, pickProbeV = -1.0f;
    // Texel the in-flight frame copies (-1 = no pick pass recorded) and the newest completed readback
    private int pendingPickX = -1, pendingPickY = -1, pendingPickW, pendingPickH;
    private int lastPickX = -1, lastPickY = -1, lastPickW, lastPickH;
    private int lastPickId = PICK_UNAVAILABLE;

    private int activeImageIndex = 0;
    private long minUniformAlignment = 16L;
    // GLFW window the surface belongs to; queried for its framebuffer (pixel) size when the surface extent is undefined
    private long windowHandle = 0L;

    private float camPanX = 0.0f;
    private float projFovDeg = 50.0f, projNear = 0.1f, projFar = 500.0f;
    private float camPanY = 0.25f;
    private float camZoom = -5.0f;
    private float camPitch = 0.0f;
    private float camYaw = 0.0f;

    private final SceneLights sceneLights = new SceneLights();
    { sceneLights.setDefaultSun(); }

    // Submitted queue (resolved into drawBuffers at beginFrame, then read while recording); null = no scene draws
    private RenderQueue submittedQueue = null;
    private final VulkanDrawBuffers drawBuffers = new VulkanDrawBuffers();

    // Reusable Matrix Buffers for Zero-Allocation Frame Loops
    private final float[] viewMatrix = new float[16];
    private final float[] cameraEye = new float[3];
    // Explicit camera view (Play mode camera entity), replacing the orbit parameters while active
    private boolean explicitViewActive = false;
    private final float[] explicitView = new float[16];
    private final float[] explicitEye = new float[3];
    private final float[] projMatrix = new float[16];
    private final float[] lightSpaceMatrix = new float[16];
    private float sceneAspect = 0.0f; // docked Scene tile aspect; 0 = use offscreen target aspect

    @Override
    public boolean initialize(ViewportSpec windowInfo) {
        System.out.println("[VulkanBackend] Modular Vulkan 1.3 Driver Initializing...");

        long nativeHandle = windowInfo.nativeWindowHandle();
        this.windowHandle = nativeHandle;
        // ViewportSpec carries the window size in screen points; the swapchain must match backing pixels (Retina/HiDPI)
        int width = windowInfo.width();
        int height = windowInfo.height();
        try (MemoryStack stack = stackPush()) {
            IntBuffer pW = stack.mallocInt(1);
            IntBuffer pH = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(nativeHandle, pW, pH);
            if (pW.get(0) > 0 && pH.get(0) > 0) {
                width = pW.get(0);
                height = pH.get(0);
            }
        }

        if (!deviceManager.initialize(nativeHandle)) return false;
        if (!surfaceManager.createSurface(deviceManager.getInstance(), nativeHandle)) return false;
        if (!deviceManager.selectPhysicalDeviceAndCreateLogicalDevice(surfaceManager.getSurfaceHandle())) return false;

        queryPhysicalDeviceLimits();

        if (!queueSubmitter.initialize(deviceManager)) return false;
        if (!swapchainManager.createSwapchain(deviceManager, surfaceManager, width, height)) return false;

        targetPass.initFramebuffer(deviceManager, 712, 488);
        if (!pipelineManager.initialize(deviceManager, swapchainManager)) return false;
        if (!gpuCache.initialize(deviceManager, pipelineManager.getDescriptorSetLayout3D(), pipelineManager.getBindlessTextureCapacity())) return false;

        cameraPreviewTarget.initFramebuffer(deviceManager, CAMERA_PREVIEW_WIDTH, CAMERA_PREVIEW_HEIGHT);
        cameraPreviewSlot = gpuCache.registerRenderTargetView(cameraPreviewTarget.getOffscreenImageView());
        initPickReadback();

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

    /**
     * Shader hot reload. Edited GLSL is recompiled to SPIR-V in the background (the resulting .spv write arrives here as
     * its own change); changed .spv files rebuild every VkPipeline against the existing layouts. Runs on the render thread
     * between frames: waiting on the in-flight fence retires the only frame that can still use the old pipelines.
     */
    @Override
    public void reloadShaders(List<String> changedShaderFiles) {
        boolean spirvChanged = false;
        for (String path : changedShaderFiles) {
            String lower = path.toLowerCase();
            if (lower.endsWith(".spv")) {
                spirvChanged = true;
            } else if (lower.endsWith(".vert") || lower.endsWith(".frag")) {
                VulkanShaderCompiler.compileAsync(path);
            }
        }
        VkDevice device = deviceManager.getDevice();
        if (!spirvChanged || device == null) return;

        vkWaitForFences(device, queueSubmitter.getInFlightFence(), true, Long.MAX_VALUE);
        if (pipelineManager.reloadGraphicsPipelines(deviceManager)) {
            System.out.println("[VulkanBackend] Shader hot reload: Vulkan pipelines rebuilt and swapped.");
        } else {
            System.err.println("[VulkanBackend] Shader hot reload failed; keeping the previous pipelines.");
        }
    }

    @Override
    public void beginFrame() {
        VkDevice device = deviceManager.getDevice();
        if (device == null) return;

        // The previous frame's command buffer, descriptor sets, light UBO and bone palettes are only safe to touch
        // once its fence signals.
        vkWaitForFences(device, queueSubmitter.getInFlightFence(), true, Long.MAX_VALUE);
        harvestPickReadback();

        // A resize/maximize event, or OUT_OF_DATE/SUBOPTIMAL from the previous acquire or present, invalidated the
        // swapchain: rebuild it at the new surface extent before acquiring. A minimized (zero-area) window skips the frame.
        if (swapchainManager.needsRecreate() && !recreateSwapchain()) {
            activeImageIndex = -1;
            return;
        }

        // Acquire before resetting the fence: if acquisition fails no submit follows, and a reset fence would
        // never be signaled again, deadlocking the next frame's wait.
        activeImageIndex = swapchainManager.acquireNextImage(deviceManager, queueSubmitter.getImageAvailableSemaphore());
        if (activeImageIndex < 0 && swapchainManager.needsRecreate() && recreateSwapchain()) {
            // OUT_OF_DATE leaves the semaphore unsignaled, so one retry against the fresh swapchain is safe
            activeImageIndex = swapchainManager.acquireNextImage(deviceManager, queueSubmitter.getImageAvailableSemaphore());
        }
        if (activeImageIndex < 0) return;

        vkResetFences(device, queueSubmitter.getInFlightFence());

        // The GPU is idle with respect to this backend's set and buffers: retire deferred resources and apply queued
        // bindless texture table writes before anything is recorded against them
        gpuCache.beginFrame(deviceManager);

        // View/projection feed the light UBO (binding 0), so they must be ready before it is written
        computeViewMatrix();
        float aspect = sceneAspect > 0.0f ? sceneAspect : (float) targetPass.getWidth() / (float) targetPass.getHeight();
        computePerspectiveProjectionMatrix(projFovDeg, aspect, projNear, projFar);

        gpuCache.updateLightUniformBuffer(
                deviceManager,
                sceneLights,
                cameraEye[0], cameraEye[1], cameraEye[2],
                0.0f,
                viewMatrix,
                projMatrix,
                lightSpaceMatrix,
                cameraPreviewActive ? cameraPreviewViewProj : null,
                cameraPreviewX, cameraPreviewY, cameraPreviewZ
        );

        // Material slots and bone palettes are resolved here; recording only pushes constants
        gpuCache.prepareScene(deviceManager, submittedQueue, drawBuffers);

        armPickPass();

        VkCommandBuffer cmd = queueSubmitter.getCommandBuffer();
        vkResetCommandBuffer(cmd, 0);

        try (MemoryStack stack = stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            vkBeginCommandBuffer(cmd, beginInfo);

            passEncoder.record4PhasePipeline(
                    cmd,
                    targetPass,
                    swapchainManager,
                    activeImageIndex,
                    pipelineManager,
                    gpuCache,
                    deviceManager,
                    viewMatrix,
                    projMatrix,
                    submittedQueue != null ? submittedQueue.commands() : null,
                    drawBuffers,
                    cameraPreviewTarget,
                    cameraPreviewActive && cameraPreviewSlot != VulkanGpuCache.NO_TEXTURE,
                    cameraPreviewSlot
            );

            vkEndCommandBuffer(cmd);
        }
    }

    private void initPickReadback() {
        long[] handles = VulkanGpuCache.allocateHostVisibleBuffer(deviceManager, Integer.BYTES, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        if (handles == null) {
            System.err.println("[VulkanBackend] WARNING: Color-ID pick readback buffer unavailable; GPU picking disabled.");
            return;
        }
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            if (vkMapMemory(deviceManager.getLogicalDevice(), handles[1], 0, VK_WHOLE_SIZE, 0, pData) != VK_SUCCESS) {
                vkDestroyBuffer(deviceManager.getLogicalDevice(), handles[0], null);
                vkFreeMemory(deviceManager.getLogicalDevice(), handles[1], null);
                return;
            }
            pickReadbackMapped = pData.get(0);
        }
        pickReadbackBuffer = handles[0];
        pickReadbackMemory = handles[1];
    }

    /** The previous frame's fence has signaled: publish the texel its pick pass copied (host-coherent memory). */
    private void harvestPickReadback() {
        if (pendingPickX < 0 || pickReadbackMapped == 0L) return;
        lastPickId = MemoryUtil.memGetInt(pickReadbackMapped); // little-endian RGBA bytes = the packed ID
        lastPickX = pendingPickX; lastPickY = pendingPickY;
        lastPickW = pendingPickW; lastPickH = pendingPickH;
        pendingPickX = -1;
    }

    /** Arms (or disarms) the pick pass for the frame about to be recorded; the GPU is idle, so resizing is safe here. */
    private void armPickPass() {
        boolean armed = pickReadbackBuffer != 0L && pickProbeU >= 0.0f && pickProbeU < 1.0f && pickProbeV >= 0.0f && pickProbeV < 1.0f
                && pipelineManager.getGraphicsPipelinePick() != 0L;
        if (!armed) {
            passEncoder.setPickPass(null, 0L, 0, 0);
            return;
        }
        int w = targetPass.getWidth(), h = targetPass.getHeight();
        if (pickTarget.getOffscreenImage() == 0L || pickTarget.getWidth() != w || pickTarget.getHeight() != h) {
            pickTarget.initFramebuffer(deviceManager, w, h);
        }
        int x = pickTexel(pickProbeU, w), y = pickTexel(pickProbeV, h);
        passEncoder.setPickPass(pickTarget, pickReadbackBuffer, x, y);
        pendingPickX = x; pendingPickY = y; pendingPickW = w; pendingPickH = h;
    }

    private static int pickTexel(float uv, int size) {
        return Math.max(0, Math.min(size - 1, (int) (uv * size)));
    }

    @Override
    public void setPickProbe(float u, float v) {
        boolean inside = u >= 0.0f && u < 1.0f && v >= 0.0f && v < 1.0f;
        pickProbeU = inside ? u : -1.0f;
        pickProbeV = inside ? v : -1.0f;
    }

    @Override
    public int queryPick(float u, float v) {
        if (lastPickX < 0 || u < 0.0f || u >= 1.0f || v < 0.0f || v >= 1.0f) return PICK_UNAVAILABLE;
        int w = targetPass.getWidth(), h = targetPass.getHeight();
        if (w != lastPickW || h != lastPickH || pickTexel(u, w) != lastPickX || pickTexel(v, h) != lastPickY) return PICK_UNAVAILABLE;
        return lastPickId;
    }

    /**
     * Rebuilds swapchain images and views at the surface's current pixel extent. Recording uses dynamic rendering, so
     * there are no VkFramebuffer objects to rebuild; the composition pass reads the new extent and views every frame.
     * Returns false while the window has zero area, leaving the request pending.
     */
    private boolean recreateSwapchain() {
        VkDevice device = deviceManager.getDevice();
        if (device == null) return false;

        int fbWidth = 0, fbHeight = 0;
        try (MemoryStack stack = stackPush()) {
            IntBuffer pW = stack.mallocInt(1);
            IntBuffer pH = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(windowHandle, pW, pH);
            fbWidth = pW.get(0);
            fbHeight = pH.get(0);
        }
        if (fbWidth <= 0 || fbHeight <= 0) return false;

        // The old images may still be read by an in-flight present; the upload thread shares the queue lock
        synchronized (deviceManager.getQueueLock()) {
            vkDeviceWaitIdle(device);
        }
        return swapchainManager.recreateSwapchain(deviceManager, surfaceManager.getSurfaceHandle(), fbWidth, fbHeight);
    }

    @Override
    public void onFramebufferResized(int width, int height) {
        swapchainManager.markOutOfDate();
    }

    /** View matrix and eye for this frame: the explicit camera view when set, else the orbit transform. */
    private void computeViewMatrix() {
        if (explicitViewActive) {
            System.arraycopy(explicitView, 0, viewMatrix, 0, 16);
            System.arraycopy(explicitEye, 0, cameraEye, 0, 3);
            return;
        }
        computeOrbitViewMatrix();
        // Eye = -R^T * t, so specular and reflections use the real camera position
        for (int i = 0; i < 3; i++) {
            cameraEye[i] = -(viewMatrix[i * 4] * viewMatrix[12] + viewMatrix[i * 4 + 1] * viewMatrix[13] + viewMatrix[i * 4 + 2] * viewMatrix[14]);
        }
    }

    private void computeOrbitViewMatrix() {
        float radYaw = (float) Math.toRadians(camYaw);
        float radPitch = (float) Math.toRadians(camPitch);

        float cy = (float) Math.cos(radYaw), sy = (float) Math.sin(radYaw);
        float cp = (float) Math.cos(radPitch), sp = (float) Math.sin(radPitch);

        viewMatrix[0] = cy;        viewMatrix[1] = sy * sp;   viewMatrix[2] = sy * cp;   viewMatrix[3] = 0.0f;
        viewMatrix[4] = 0.0f;      viewMatrix[5] = cp;        viewMatrix[6] = -sp;       viewMatrix[7] = 0.0f;
        viewMatrix[8] = -sy;       viewMatrix[9] = cy * sp;   viewMatrix[10] = cy * cp;  viewMatrix[11] = 0.0f;
        viewMatrix[12] = camPanX;  viewMatrix[13] = camPanY;  viewMatrix[14] = camZoom;  viewMatrix[15] = 1.0f;
    }

    @Override
    public void setSceneViewport(float minX, float minY, float maxX, float maxY, float aspect) {
        passEncoder.setSceneRect(minX, minY, maxX, maxY);
        this.sceneAspect = aspect;
    }

    private void computePerspectiveProjectionMatrix(float fovDeg, float aspect, float zNear, float zFar) {
        float f = (float) (1.0 / Math.tan(Math.toRadians(fovDeg) / 2.0));
        projMatrix[0] = f / aspect; projMatrix[1] = 0.0f; projMatrix[2] = 0.0f; projMatrix[3] = 0.0f;
        projMatrix[4] = 0.0f;       projMatrix[5] = f;    projMatrix[6] = 0.0f; projMatrix[7] = 0.0f;
        projMatrix[8] = 0.0f;       projMatrix[9] = 0.0f; projMatrix[10] = zFar / (zNear - zFar); projMatrix[11] = -1.0f;
        projMatrix[12] = 0.0f;      projMatrix[13] = 0.0f;projMatrix[14] = (zNear * zFar) / (zNear - zFar); projMatrix[15] = 0.0f;
    }

    @Override
    public void endFrame() {
        if (activeImageIndex < 0) return;
        VkCommandBuffer cmd = queueSubmitter.getCommandBuffer();

        try (MemoryStack stack = stackPush()) {
            VulkanUploadRing uploads = gpuCache.getUploadRing();

            // Wait 0: swapchain image acquisition. The first swapchain write is a transfer (clear/blit), so the wait
            // covers transfer as well as color output; the pass encoder's swapchain barrier chains off this stage.
            // Wait 1: every texture upload submitted so far (timeline), before any fragment shader samples them.
            VkSemaphoreSubmitInfo.Buffer waits = VkSemaphoreSubmitInfo.calloc(2, stack);
            waits.get(0).sType$Default()
                    .semaphore(queueSubmitter.getImageAvailableSemaphore())
                    .stageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT | VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT);
            waits.get(1).sType$Default()
                    .semaphore(uploads.getTimelineSemaphore())
                    .value(uploads.getSubmittedValue())
                    .stageMask(VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT);

            VkCommandBufferSubmitInfo.Buffer cmdInfo = VkCommandBufferSubmitInfo.calloc(1, stack)
                    .sType$Default()
                    .commandBuffer(cmd);

            VkSemaphoreSubmitInfo.Buffer signals = VkSemaphoreSubmitInfo.calloc(1, stack);
            signals.get(0).sType$Default()
                    .semaphore(queueSubmitter.getRenderFinishedSemaphore())
                    .stageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);

            VkSubmitInfo2.Buffer submitInfo = VkSubmitInfo2.calloc(1, stack)
                    .sType$Default()
                    .pWaitSemaphoreInfos(waits)
                    .pCommandBufferInfos(cmdInfo)
                    .pSignalSemaphoreInfos(signals);

            int result;
            synchronized (deviceManager.getQueueLock()) {
                result = vkQueueSubmit2(deviceManager.getGraphicsQueue(), submitInfo, queueSubmitter.getInFlightFence());
            }
            if (result != VK_SUCCESS) {
                System.err.println("[VulkanBackend] ERROR: vkQueueSubmit2 failed with code: " + result);
            }
        }
    }

    @Override
    public void present() {
        if (activeImageIndex < 0) return;
        swapchainManager.presentImage(deviceManager, activeImageIndex, queueSubmitter.getRenderFinishedSemaphore());
    }

    @Override
    public void shutdown() {
        VkDevice device = deviceManager.getDevice();
        if (device != null) {
            synchronized (deviceManager.getQueueLock()) {
                vkDeviceWaitIdle(device);
            }
        }

        queueSubmitter.cleanup(deviceManager);
        targetPass.cleanup(deviceManager);
        cameraPreviewTarget.cleanup(deviceManager);
        pickTarget.cleanup(deviceManager);
        if (device != null && pickReadbackBuffer != 0L) {
            vkUnmapMemory(device, pickReadbackMemory);
            vkDestroyBuffer(device, pickReadbackBuffer, null);
            vkFreeMemory(device, pickReadbackMemory, null);
            pickReadbackBuffer = pickReadbackMemory = pickReadbackMapped = 0L;
        }
        pipelineManager.cleanup(deviceManager);
        gpuCache.cleanup(deviceManager);
        swapchainManager.cleanup(deviceManager);
        surfaceManager.cleanup(deviceManager);
        deviceManager.cleanup();

        System.out.println("[VulkanBackend] Vulkan subsystem shutdown complete.");
    }

    @Override public long getOffscreenViewportTexture() { return targetPass.getOffscreenColorTexture(); }

    @Override
    public long getCameraPreviewTexture() {
        return cameraPreviewSlot != VulkanGpuCache.NO_TEXTURE ? cameraPreviewTarget.getOffscreenColorTexture() : 0L;
    }

    @Override
    public void setCameraPreview(boolean active, float[] viewProj, float camX, float camY, float camZ) {
        cameraPreviewActive = active && viewProj != null && viewProj.length == 16;
        if (!cameraPreviewActive) return;
        System.arraycopy(viewProj, 0, cameraPreviewViewProj, 0, 16);
        cameraPreviewX = camX; cameraPreviewY = camY; cameraPreviewZ = camZ;
    }
    @Override public void resizeViewport(int width, int height) { targetPass.resizeViewport(deviceManager, width, height); }

    @Override public void setCameraTransform(float panX, float panY, float zoom, float pitch, float yaw) {
        this.camPanX = panX; this.camPanY = panY; this.camZoom = zoom; this.camPitch = pitch; this.camYaw = yaw;
    }
    @Override
    public void setCameraView(float[] view, float eyeX, float eyeY, float eyeZ) {
        explicitViewActive = view != null && view.length == 16;
        if (!explicitViewActive) return;
        System.arraycopy(view, 0, explicitView, 0, 16);
        explicitEye[0] = eyeX; explicitEye[1] = eyeY; explicitEye[2] = eyeZ;
    }

    @Override public void setCameraProjection(float fovDeg, float near, float far) {
        this.projFovDeg = fovDeg; this.projNear = near; this.projFar = far;
    }

    @Override
    public void setSceneLights(SceneLights lights) {
        if (lights == null || lights.isEmpty()) {
            sceneLights.setDefaultSun();
        } else {
            sceneLights.copyFrom(lights);
        }
    }

    @Override
    public void updateGridMesh(float[] vertices, int count) {
        gpuCache.updateGridMesh(deviceManager, vertices, count);
    }

    @Override
    public void updateUiMesh(float[] vertices, int count) {
        gpuCache.updateUiMesh(deviceManager, vertices, count);
    }

    @Override
    public void setPickOverlay(float[] vertices, int vertexCount) {
        gpuCache.updatePickOverlay(deviceManager, vertices, vertexCount);
    }

    @Override
    public boolean setUiFontAtlas(long textureHandle) {
        if (gpuCache.getTexture(textureHandle) == null) return false;
        gpuCache.setUiFontTexture(textureHandle);
        return true;
    }

    @Override public void clearSceneQueue() { submittedQueue = null; }

    @Override
    public void submitRenderQueue(RenderQueue queue) {
        submittedQueue = queue;
        if (queue != null) System.arraycopy(queue.lightSpaceMatrix, 0, lightSpaceMatrix, 0, 16);
    }

    @Override
    public long registerMesh(String assetKey, float[] vertices, int vertexCount, int[] indices, int indexCount) {
        return gpuCache.registerMesh(deviceManager, assetKey, vertices, vertexCount, indices, indexCount);
    }

    @Override
    public long registerTexture(String assetKey, byte[] pixels, int width, int height) {
        return gpuCache.registerTexture(deviceManager, assetKey, pixels, width, height);
    }

    @Override public void unregisterMesh(long meshHandle) { gpuCache.unregisterMesh(meshHandle); }
    @Override public void unregisterTexture(long textureHandle) { gpuCache.unregisterTexture(textureHandle); }

    @Override public long createBuffer(BufferDesc createInfo) { return 101L; }
    @Override public void destroyBuffer(long handle) { }
    @Override public long createTexture(TextureDesc createInfo) { return 201L; }
    @Override public void destroyTexture(long handle) { }

    @Override public GraphicsAPI getAPI() { return GraphicsAPI.VULKAN; }
    @Override public String getDeviceName() { return "Modular Vulkan 1.3 Driver (MoltenVK / Apple Silicon)"; }
}
