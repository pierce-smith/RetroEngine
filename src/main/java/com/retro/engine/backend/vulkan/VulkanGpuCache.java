package com.retro.engine.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Service Registry & VRAM Coordinator (SRP Pattern matching EditorWorkspace):
 * Coordinates static VBO mesh buffers, Light UBO allocations, ground shadow quad VBOs,
 * and delegates texture management to VulkanTextureRegistry and dynamic geometry buffering to VulkanRingBuffer.
 */
public class VulkanGpuCache {

    private static final int RING_SLOTS = 3;

    public static class VulkanBuffer {
        public long bufferHandle;
        public long memoryHandle;
        public int vertexCount;

        public VulkanBuffer(long bufferHandle, long memoryHandle, int vertexCount) {
            this.bufferHandle = bufferHandle;
            this.memoryHandle = memoryHandle;
            this.vertexCount = vertexCount;
        }
    }

    public static class VulkanTexture {
        public long imageHandle;
        public long memoryHandle;
        public long imageViewHandle;
        public long samplerHandle;

        public VulkanTexture(long imageHandle, long memoryHandle, long imageViewHandle, long samplerHandle) {
            this.imageHandle = imageHandle;
            this.memoryHandle = memoryHandle;
            this.imageViewHandle = imageViewHandle;
            this.samplerHandle = samplerHandle;
        }
    }

    public static class DeferredVulkanResource {
        public final long bufferHandle;
        public final long memoryHandle;
        public final int frameEnqueued;

        public DeferredVulkanResource(long bufferHandle, long memoryHandle, int frameEnqueued) {
            this.bufferHandle = bufferHandle;
            this.memoryHandle = memoryHandle;
            this.frameEnqueued = frameEnqueued;
        }
    }

    public static class SingleTimeCmdContext {
        public final VkCommandBuffer cmd;
        public final long pool;

        public SingleTimeCmdContext(VkCommandBuffer cmd, long pool) {
            this.cmd = cmd;
            this.pool = pool;
        }
    }

    // Subsystem Domain Managers
    private final VulkanTextureRegistry textureRegistry = new VulkanTextureRegistry();
    private final VulkanRingBuffer uiRingBuffer = new VulkanRingBuffer();
    private final VulkanRingBuffer gridRingBuffer = new VulkanRingBuffer();

    private final Map<String, VulkanBuffer> meshCache = Collections.synchronizedMap(new HashMap<>());
    private final Queue<DeferredVulkanResource> deferredDisposalQueue = new ArrayDeque<>();

    private VulkanBuffer lightUniformBuffer = null;
    private VulkanBuffer groundQuadBuffer = null;

    private int totalFrameCount = 0;

    public long getUiVertexBuffer() { return uiRingBuffer.getCurrentBufferHandle(); }
    public int getUiVertexCount() { return uiRingBuffer.getVertexCount(); }

    public long getGridVertexBuffer() { return gridRingBuffer.getCurrentBufferHandle(); }
    public int getGridVertexCount() { return gridRingBuffer.getVertexCount(); }

    public long getGroundQuadBuffer(VulkanDevice device) {
        if (groundQuadBuffer != null) return groundQuadBuffer.bufferHandle;
        if (device == null || device.getLogicalDevice() == null) return 0L;

        // 22-float stride (88 bytes) ground quad VBO for dynamic shadow blob projection
        float[] groundVerts = new float[] {
            -1.0f, 0.005f, -1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  0.0f, 0.0f,  0.0f, 0.0f,  1.0f, 0.0f, 0.0f,  0.0f, 0.0f, 1.0f,  1.0f, 0.0f,
            -1.0f, 0.005f,  1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  0.0f, 1.0f,  0.0f, 1.0f,  1.0f, 0.0f, 0.0f,  0.0f, 0.0f, 1.0f,  1.0f, 0.0f,
             1.0f, 0.005f,  1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  1.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f, 0.0f,  0.0f, 0.0f, 1.0f,  1.0f, 0.0f,

            -1.0f, 0.005f, -1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  0.0f, 0.0f,  0.0f, 0.0f,  1.0f, 0.0f, 0.0f,  0.0f, 0.0f, 1.0f,  1.0f, 0.0f,
             1.0f, 0.005f,  1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  1.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f, 0.0f,  0.0f, 0.0f, 1.0f,  1.0f, 0.0f,
             1.0f, 0.005f, -1.0f,  0.0f, 1.0f, 0.0f,  1.0f, 1.0f, 1.0f, 1.0f,  1.0f, 0.0f,  1.0f, 0.0f,  1.0f, 0.0f, 0.0f,  0.0f, 0.0f, 1.0f,  1.0f, 0.0f
        };

        groundQuadBuffer = createHostVisibleBuffer(device, groundVerts);
        return (groundQuadBuffer != null) ? groundQuadBuffer.bufferHandle : 0L;
    }

    public void updateUiMesh(VulkanDevice device, float[] vertices, int count) {
        totalFrameCount++;
        processDeferredDisposals(device);
        uiRingBuffer.updateBuffer(device, vertices, count, 6, deferredDisposalQueue, totalFrameCount);
    }

    public void updateGridMesh(VulkanDevice device, float[] vertices, int count) {
        totalFrameCount++;
        processDeferredDisposals(device);
        gridRingBuffer.updateBuffer(device, vertices, count, 9, deferredDisposalQueue, totalFrameCount);
    }

    private void processDeferredDisposals(VulkanDevice device) {
        if (device == null || device.getLogicalDevice() == null) return;
        VkDevice vkDevice = device.getLogicalDevice();

        synchronized (deferredDisposalQueue) {
            while (!deferredDisposalQueue.isEmpty()) {
                DeferredVulkanResource res = deferredDisposalQueue.peek();
                if (totalFrameCount - res.frameEnqueued >= RING_SLOTS) {
                    deferredDisposalQueue.poll();
                    if (res.bufferHandle != 0L) vkDestroyBuffer(vkDevice, res.bufferHandle, null);
                    if (res.memoryHandle != 0L) vkFreeMemory(vkDevice, res.memoryHandle, null);
                } else {
                    break;
                }
            }
        }
    }

    public VulkanBuffer getOrCreateMeshBuffer(VulkanDevice device, String meshPath, float[] vertices) {
        if (meshPath == null || vertices == null || vertices.length == 0) return null;

        VulkanBuffer existing = meshCache.get(meshPath);
        if (existing != null) return existing;

        VulkanBuffer created = createHostVisibleBuffer(device, vertices);
        if (created != null) {
            meshCache.put(meshPath, created);
        }
        return created;
    }

    public VulkanTexture getOrCreateTexture(VulkanDevice device, String texturePath, byte[] pixels, int width, int height) {
        return textureRegistry.getOrCreateTexture(device, texturePath, pixels, width, height);
    }

    public VulkanTexture getDefaultWhiteTexture(VulkanDevice device) {
        return textureRegistry.getDefaultWhiteTexture(device);
    }

    public VulkanBuffer createHostVisibleBuffer(VulkanDevice device, float[] vertices) {
        if (device == null || device.getLogicalDevice() == null || vertices == null || vertices.length == 0) return null;

        VkDevice vkDevice = device.getLogicalDevice();
        long bufferSize = (long) vertices.length * Float.BYTES;

        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(bufferSize)
                    .usage(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            if (vkCreateBuffer(vkDevice, bufferInfo, null, pBuffer) != VK_SUCCESS) return null;
            long buffer = pBuffer.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(vkDevice, buffer, memReqs);

            int memTypeIndex = findMemoryType(device.getPhysicalDevice(), memReqs.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(memTypeIndex);

            LongBuffer pBufferMemory = stack.mallocLong(1);
            if (vkAllocateMemory(vkDevice, allocInfo, null, pBufferMemory) != VK_SUCCESS) {
                vkDestroyBuffer(vkDevice, buffer, null);
                return null;
            }
            long bufferMemory = pBufferMemory.get(0);

            vkBindBufferMemory(vkDevice, buffer, bufferMemory, 0);

            PointerBuffer pData = stack.mallocPointer(1);
            vkMapMemory(vkDevice, bufferMemory, 0, bufferSize, 0, pData);
            FloatBuffer floatBuf = pData.getFloatBuffer(0, vertices.length);
            floatBuf.put(vertices);
            vkUnmapMemory(vkDevice, bufferMemory);

            int floatStride = (vertices.length % 22 == 0) ? 22 : ((vertices.length % 14 == 0) ? 14 : 9);
            return new VulkanBuffer(buffer, bufferMemory, vertices.length / floatStride);
        }
    }

    public VulkanBuffer getOrCreateLightUniformBuffer(VulkanDevice device) {
        if (lightUniformBuffer != null) return lightUniformBuffer;
        if (device == null || device.getLogicalDevice() == null) return null;

        VkDevice vkDevice = device.getLogicalDevice();
        long bufferSize = 512L;

        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(bufferSize)
                    .usage(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            if (vkCreateBuffer(vkDevice, bufferInfo, null, pBuffer) != VK_SUCCESS) return null;
            long buffer = pBuffer.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(vkDevice, buffer, memReqs);

            int memTypeIndex = findMemoryType(device.getPhysicalDevice(), memReqs.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(memTypeIndex);

            LongBuffer pMemory = stack.mallocLong(1);
            if (vkAllocateMemory(vkDevice, allocInfo, null, pMemory) != VK_SUCCESS) return null;
            long memory = pMemory.get(0);

            vkBindBufferMemory(vkDevice, buffer, memory, 0);

            PointerBuffer pData = stack.mallocPointer(1);
            vkMapMemory(vkDevice, memory, 0, bufferSize, 0, pData);
            FloatBuffer floatBuf = pData.getFloatBuffer(0, 128);
            floatBuf.put(new float[]{-0.5f, -1.0f, -0.5f, 0.0f, 1.0f, 0.95f, 0.85f, 1.2f});
            vkUnmapMemory(vkDevice, memory);

            lightUniformBuffer = new VulkanBuffer(buffer, memory, 0);
            return lightUniformBuffer;
        }
    }

    public void updateLightUniformBuffer(VulkanDevice device, int lightType, float posX, float posY, float posZ,
                                        float dirX, float dirY, float dirZ,
                                        float r, float g, float b, float intensity,
                                        float camX, float camY, float camZ, float groundY) {
        VulkanBuffer buf = getOrCreateLightUniformBuffer(device);
        if (buf == null || device == null || device.getLogicalDevice() == null) return;

        VkDevice vkDevice = device.getLogicalDevice();

        try (MemoryStack stack = stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            if (vkMapMemory(vkDevice, buf.memoryHandle, 0, 512L, 0, pData) == VK_SUCCESS) {
                FloatBuffer floatBuf = pData.getFloatBuffer(0, 128);

                float numDir = (lightType == 0) ? 1.0f : 0.0f;
                float numPoint = (lightType == 1) ? 1.0f : 0.0f;
                float numSpot = (lightType == 2) ? 1.0f : 0.0f;

                floatBuf.put(0, dirX); floatBuf.put(1, dirY); floatBuf.put(2, dirZ); floatBuf.put(3, 0.0f);
                floatBuf.put(4, r);    floatBuf.put(5, g);    floatBuf.put(6, b);    floatBuf.put(7, intensity);

                floatBuf.put(8, posX); floatBuf.put(9, posY); floatBuf.put(10, posZ); floatBuf.put(11, 0.0f);
                floatBuf.put(12, r);   floatBuf.put(13, g);   floatBuf.put(14, b);    floatBuf.put(15, intensity);
                floatBuf.put(16, 1.0f);floatBuf.put(17, 0.09f);floatBuf.put(18, 0.032f);floatBuf.put(19, 0.0f);

                floatBuf.put(20, posX); floatBuf.put(21, posY); floatBuf.put(22, posZ); floatBuf.put(23, 0.93f);
                floatBuf.put(24, dirX); floatBuf.put(25, dirY); floatBuf.put(26, dirZ); floatBuf.put(27, 0.82f);
                floatBuf.put(28, r);    floatBuf.put(29, g);    floatBuf.put(30, b);    floatBuf.put(31, intensity);
                floatBuf.put(32, 1.0f); floatBuf.put(33, 0.09f);floatBuf.put(34, 0.032f);floatBuf.put(35, 0.0f);

                floatBuf.put(36, numDir); floatBuf.put(37, numPoint); floatBuf.put(38, numSpot); floatBuf.put(39, 0.005f);

                floatBuf.put(40, camX); floatBuf.put(41, camY); floatBuf.put(42, camZ); floatBuf.put(43, groundY);

                vkUnmapMemory(vkDevice, buf.memoryHandle);
            }
        }
    }

    public static SingleTimeCmdContext beginSingleTimeCommands(VulkanDevice device) {
        VkDevice vkDevice = device.getLogicalDevice();
        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .queueFamilyIndex(device.getGraphicsQueueFamilyIndex());

            LongBuffer pPool = stack.mallocLong(1);
            vkCreateCommandPool(vkDevice, poolInfo, null, pPool);
            long pool = pPool.get(0);

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);

            PointerBuffer pCmd = stack.mallocPointer(1);
            vkAllocateCommandBuffers(vkDevice, allocInfo, pCmd);
            VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), vkDevice);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            vkBeginCommandBuffer(cmd, beginInfo);
            return new SingleTimeCmdContext(cmd, pool);
        }
    }

    public static void endSingleTimeCommands(VulkanDevice device, SingleTimeCmdContext stc) {
        if (stc == null || device == null || device.getLogicalDevice() == null) return;
        VkDevice vkDevice = device.getLogicalDevice();
        try (MemoryStack stack = stackPush()) {
            vkEndCommandBuffer(stc.cmd);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);

            LongBuffer pFence = stack.mallocLong(1);
            if (vkCreateFence(vkDevice, fenceInfo, null, pFence) == VK_SUCCESS) {
                long fence = pFence.get(0);

                VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                        .pCommandBuffers(stack.pointers(stc.cmd));

                vkQueueSubmit(device.getGraphicsQueue(), submitInfo, fence);
                vkWaitForFences(vkDevice, fence, true, 1000000000L);
                vkDestroyFence(vkDevice, fence, null);
            } else {
                VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                        .pCommandBuffers(stack.pointers(stc.cmd));

                vkQueueSubmit(device.getGraphicsQueue(), submitInfo, VK_NULL_HANDLE);
                vkQueueWaitIdle(device.getGraphicsQueue());
            }

            if (stc.pool != 0L) {
                vkFreeCommandBuffers(vkDevice, stc.pool, stc.cmd);
                vkDestroyCommandPool(vkDevice, stc.pool, null);
            }
        }
    }

    public static int findMemoryType(VkPhysicalDevice physicalDevice, int typeFilter, int properties) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceMemoryProperties memProperties = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProperties);

            for (int i = 0; i < memProperties.memoryTypes().capacity(); i++) {
                if ((typeFilter & (1 << i)) != 0 && (memProperties.memoryTypes(i).propertyFlags() & properties) == properties) {
                    return i;
                }
            }
        }
        return 0;
    }

    public void cleanup(VulkanDevice device) {
        if (device == null || device.getLogicalDevice() == null) return;
        VkDevice vkDevice = device.getLogicalDevice();

        textureRegistry.cleanup(vkDevice);
        uiRingBuffer.cleanup(vkDevice);
        gridRingBuffer.cleanup(vkDevice);

        for (VulkanBuffer buf : meshCache.values()) {
            if (buf != null) {
                if (buf.bufferHandle != 0L) vkDestroyBuffer(vkDevice, buf.bufferHandle, null);
                if (buf.memoryHandle != 0L) vkFreeMemory(vkDevice, buf.memoryHandle, null);
            }
        }
        meshCache.clear();

        if (lightUniformBuffer != null) {
            if (lightUniformBuffer.bufferHandle != 0L) vkDestroyBuffer(vkDevice, lightUniformBuffer.bufferHandle, null);
            if (lightUniformBuffer.memoryHandle != 0L) vkFreeMemory(vkDevice, lightUniformBuffer.memoryHandle, null);
            lightUniformBuffer = null;
        }

        if (groundQuadBuffer != null) {
            if (groundQuadBuffer.bufferHandle != 0L) vkDestroyBuffer(vkDevice, groundQuadBuffer.bufferHandle, null);
            if (groundQuadBuffer.memoryHandle != 0L) vkFreeMemory(vkDevice, groundQuadBuffer.memoryHandle, null);
            groundQuadBuffer = null;
        }
    }
}
