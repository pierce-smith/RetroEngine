package com.retro.engine.graphics.vulkan;

import com.retro.engine.graphics.IRenderBackend;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO;

/**
 * Manages Vulkan 1.3 Graphics Pipelines for offscreen 3D Blinn-Phong geometry (88-byte 22-float stride),
 * GPU-skinned geometry (120-byte 30-float stride with bone palette storage buffer at binding 3),
 * infinite 3D grid rendering (36-byte 9-float stride VBO from GizmoState matching Metal backend),
 * dynamic ground shadow blobs, depth-only directional shadow map passes, vertical Y-axis pillar lines, and swapchain 2D UI composition
 * using Dynamic Rendering (VK_KHR_dynamic_rendering).
 * All 3D pipelines share one bindless set 0 layout (see VulkanGpuCache) and a 128-byte push constant block carrying
 * the model matrix, surface parameters and material/bone-palette slot indices.
 * Owns the VkPipelineCache and its disk serialization (.pipeline_cache.bin): the blob is validated against this
 * device's vendor/device ID and pipelineCacheUUID before reuse and written atomically so a crash never leaves a
 * truncated cache behind.
 */
public class VulkanPipeline {

    // 3D push constant block (bytes): mat4 model 0, vec4 params 64, vec4 surface 80, ivec4 texIndices 96, ivec4 texIndices2 112
    public static final int MESH_PUSH_CONSTANT_SIZE = 128;
    /** UI fragment push constants: int font atlas bindless slot, padded to 16 bytes. */
    public static final int UI_PUSH_CONSTANT_SIZE = 16;
    /** Post-process fragment push constants: int scene slot (padded to 16 bytes) + PostProcessSettings.pack() floats. */
    public static final int POST_PUSH_CONSTANT_SIZE = 16 + com.retro.engine.graphics.PostProcessSettings.PACKED_FLOATS * 4;
    /** Color-ID picking target format: entity_id.frag writes the 32-bit pick ID little-endian across RGBA. */
    public static final int PICK_ID_FORMAT = VK_FORMAT_R8G8B8A8_UINT;

    // Upper bound for the bindless texture table; clamped further to the device's descriptor limits
    private static final int MAX_BINDLESS_TEXTURES = 4096;
    // Non-table resources in set 0 that count against the same per-stage limits (shadow map + headroom)
    private static final int RESERVED_STAGE_RESOURCES = 8;

    private static final String PIPELINE_CACHE_FILE_NAME = ".pipeline_cache.bin";
    private static final int PIPELINE_CACHE_HEADER_SIZE = 16 + VK_UUID_SIZE;

    private long pipelineCache = 0L;
    private int bindlessTextureCapacity = 0;

    private long minUniformBufferOffsetAlignment = 64L;

    private long descriptorSetLayout3D = 0L;
    private long pipelineLayout3D = 0L;
    private long graphicsPipeline3D = 0L;
    private long graphicsPipelineShadow = 0L;
    private long graphicsPipelineSkinned = 0L;
    // Depth-only directional shadow map pipelines (static 88 B and skinned 120 B strides)
    private long graphicsPipelineShadowDepth = 0L;
    private long graphicsPipelineShadowDepthSkinned = 0L;
    // Color-ID picking pipelines (static 88 B and skinned 120 B strides) writing into PICK_ID_FORMAT
    private long graphicsPipelinePick = 0L;
    private long graphicsPipelinePickSkinned = 0L;
    // Screen-space pick overlay (gizmo handles) drawn last in the pick pass, depth testing off
    private long graphicsPipelinePickOverlay = 0L;

    private long descriptorSetLayoutGrid = 0L;
    private long pipelineLayoutGrid = 0L;
    private long graphicsPipelineGrid = 0L;
    private long graphicsPipelineGridLines = 0L;
    private long graphicsPipelineParticles = 0L;

    private long descriptorSetLayoutUI = 0L;
    private long pipelineLayoutUI = 0L;
    private long graphicsPipelineUI = 0L;

    private long pipelineLayoutPost = 0L;
    private long graphicsPipelinePost = 0L;

    public static long calcAlignedOffset(long size, long alignment) {
        if (alignment <= 0) return size;
        return (size + alignment - 1) & ~(alignment - 1);
    }

    public boolean initialize(VulkanDevice device, VulkanSwapchain swapchain) {
        if (device == null || device.getLogicalDevice() == null) return false;

        VkDevice vkDevice = device.getLogicalDevice();

        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceProperties deviceProperties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(device.getPhysicalDevice(), deviceProperties);
            this.minUniformBufferOffsetAlignment = deviceProperties.limits().minUniformBufferOffsetAlignment();
            System.out.println("[VulkanPipeline] Hardware minUniformBufferOffsetAlignment: " + minUniformBufferOffsetAlignment + " bytes.");

            VkPhysicalDeviceLimits limits = deviceProperties.limits();
            this.bindlessTextureCapacity = computeBindlessCapacity(limits);
            if (bindlessTextureCapacity < 2) {
                System.err.println("[VulkanPipeline] ERROR: Device descriptor limits leave no room for a bindless texture table.");
                return false;
            }
            if (limits.maxPushConstantsSize() < MESH_PUSH_CONSTANT_SIZE) {
                System.err.println("[VulkanPipeline] ERROR: maxPushConstantsSize " + limits.maxPushConstantsSize() + " < " + MESH_PUSH_CONSTANT_SIZE + " bytes.");
                return false;
            }

            createPipelineCache(vkDevice, deviceProperties);
        }

        try (MemoryStack stack = stackPush()) {
            // 1. 3D Scene Pass Layout: one bindless set shared by the scene, skinned, blob and shadow depth pipelines.
            // No dynamic descriptors: the palette slot travels in push constants, so the set is bound once per pass.
            VkDescriptorSetLayoutBinding.Buffer bindings3D = VkDescriptorSetLayoutBinding.calloc(5, stack);
            bindings3D.get(0).binding(VulkanGpuCache.BINDING_LIGHT_UBO).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings3D.get(1).binding(VulkanGpuCache.BINDING_MATERIAL_SAMPLER).descriptorType(VK_DESCRIPTOR_TYPE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings3D.get(2).binding(VulkanGpuCache.BINDING_SHADOW_MAP).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings3D.get(3).binding(VulkanGpuCache.BINDING_BONE_PALETTES).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
            bindings3D.get(4).binding(VulkanGpuCache.BINDING_TEXTURE_TABLE).descriptorType(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).descriptorCount(bindlessTextureCapacity).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);

            // Only the texture table is partially bound: slots without a registered texture are never written
            VkDescriptorSetLayoutBindingFlagsCreateInfo bindingFlags = VkDescriptorSetLayoutBindingFlagsCreateInfo.calloc(stack)
                    .sType$Default()
                    .pBindingFlags(stack.ints(0, 0, 0, 0, VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT));

            VkDescriptorSetLayoutCreateInfo dsCreateInfo3D = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pNext(bindingFlags.address())
                    .pBindings(bindings3D);

            LongBuffer pDSLayout3D = stack.mallocLong(1);
            if (vkCreateDescriptorSetLayout(vkDevice, dsCreateInfo3D, null, pDSLayout3D) != VK_SUCCESS) return false;
            descriptorSetLayout3D = pDSLayout3D.get(0);

            VkPushConstantRange.Buffer pushConstants3D = VkPushConstantRange.calloc(1, stack);
            pushConstants3D.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(MESH_PUSH_CONSTANT_SIZE); // view/proj come from the binding 0 UBO

            VkPipelineLayoutCreateInfo layoutCreateInfo3D = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descriptorSetLayout3D))
                    .pPushConstantRanges(pushConstants3D);

            LongBuffer pPipelineLayout3D = stack.mallocLong(1);
            if (vkCreatePipelineLayout(vkDevice, layoutCreateInfo3D, null, pPipelineLayout3D) != VK_SUCCESS) return false;
            pipelineLayout3D = pPipelineLayout3D.get(0);

            // 2. Infinite 3D Grid Pass Layout (Push constants: View Matrix + Proj Matrix = 128 bytes)
            VkPushConstantRange.Buffer pushConstantsGrid = VkPushConstantRange.calloc(1, stack);
            pushConstantsGrid.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(128);

            VkPipelineLayoutCreateInfo layoutCreateInfoGrid = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pPushConstantRanges(pushConstantsGrid);

            LongBuffer pPipelineLayoutGrid = stack.mallocLong(1);
            if (vkCreatePipelineLayout(vkDevice, layoutCreateInfoGrid, null, pPipelineLayoutGrid) != VK_SUCCESS) return false;
            pipelineLayoutGrid = pPipelineLayoutGrid.get(0);

            // 3. 2D UI Overlay Composition Pass Layout
            VkDescriptorSetLayoutBinding.Buffer bindingsUI = VkDescriptorSetLayoutBinding.calloc(1, stack);
            bindingsUI.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);

            VkDescriptorSetLayoutCreateInfo dsCreateInfoUI = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindingsUI);

            LongBuffer pDSLayoutUI = stack.mallocLong(1);
            if (vkCreateDescriptorSetLayout(vkDevice, dsCreateInfoUI, null, pDSLayoutUI) != VK_SUCCESS) return false;
            descriptorSetLayoutUI = pDSLayoutUI.get(0);

            // The UI samples its SDF font atlas from the bindless table; the atlas slot travels in a push constant
            VkPushConstantRange.Buffer pushConstantsUI = VkPushConstantRange.calloc(1, stack);
            pushConstantsUI.get(0).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(UI_PUSH_CONSTANT_SIZE);

            VkPipelineLayoutCreateInfo layoutCreateInfoUI = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descriptorSetLayout3D))
                    .pPushConstantRanges(pushConstantsUI);

            LongBuffer pPipelineLayoutUI = stack.mallocLong(1);
            if (vkCreatePipelineLayout(vkDevice, layoutCreateInfoUI, null, pPipelineLayoutUI) != VK_SUCCESS) return false;
            pipelineLayoutUI = pPipelineLayoutUI.get(0);

            // 4. Camera post-process composite: bindless set 0 + fragment push constants
            VkPushConstantRange.Buffer pushConstantsPost = VkPushConstantRange.calloc(1, stack);
            pushConstantsPost.get(0).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(POST_PUSH_CONSTANT_SIZE);
            VkPipelineLayoutCreateInfo layoutCreateInfoPost = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descriptorSetLayout3D))
                    .pPushConstantRanges(pushConstantsPost);
            LongBuffer pPipelineLayoutPost = stack.mallocLong(1);
            if (vkCreatePipelineLayout(vkDevice, layoutCreateInfoPost, null, pPipelineLayoutPost) == VK_SUCCESS) {
                pipelineLayoutPost = pPipelineLayoutPost.get(0);
            }

            System.out.println("[VulkanPipeline] Layouts and Descriptor Sets configured (bindless table: " + bindlessTextureCapacity + " textures).");
            createGraphicsPipelines(device);

            // Persist right away so a crash later in the session still keeps this run's compiled pipelines
            savePipelineCache(vkDevice);
            return graphicsPipeline3D != 0L;
        }
    }

    /** Largest texture table the fragment stage can address alongside set 0's other resources. */
    private static int computeBindlessCapacity(VkPhysicalDeviceLimits limits) {
        long capacity = MAX_BINDLESS_TEXTURES;
        capacity = Math.min(capacity, Integer.toUnsignedLong(limits.maxPerStageDescriptorSampledImages()) - 1);
        capacity = Math.min(capacity, Integer.toUnsignedLong(limits.maxDescriptorSetSampledImages()) - 1);
        capacity = Math.min(capacity, Integer.toUnsignedLong(limits.maxPerStageResources()) - RESERVED_STAGE_RESOURCES);
        return (int) Math.max(0L, capacity);
    }

    // ---------------------------------------------------------------------------------
    // VkPipelineCache disk serialization (.pipeline_cache.bin)
    // ---------------------------------------------------------------------------------

    private static Path pipelineCachePath() {
        // Packaged apps start in "/" (macOS Finder) or a read-only install dir: fall back to a per-user cache folder
        Path cwd = Path.of(System.getProperty("user.dir"));
        if (!Boolean.getBoolean("retro.packaged") && Files.isWritable(cwd)) return cwd.resolve(PIPELINE_CACHE_FILE_NAME);
        Path dir = Path.of(System.getProperty("user.home"), ".retroengine", "cache");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            return cwd.resolve(PIPELINE_CACHE_FILE_NAME);
        }
        return dir.resolve(PIPELINE_CACHE_FILE_NAME);
    }

    /** Creates the pipeline cache, seeded from disk when the stored blob was produced by this exact device/driver. */
    private void createPipelineCache(VkDevice vkDevice, VkPhysicalDeviceProperties props) {
        ByteBuffer initialData = null;
        try (MemoryStack stack = stackPush()) {
            Path path = pipelineCachePath();
            if (Files.isRegularFile(path)) {
                try {
                    byte[] bytes = Files.readAllBytes(path);
                    if (isPipelineCacheCompatible(bytes, props)) {
                        initialData = MemoryUtil.memAlloc(bytes.length);
                        initialData.put(bytes).flip();
                        System.out.println("[VulkanPipeline] Loaded VkPipelineCache from " + path.getFileName() + " (" + bytes.length + " bytes).");
                    } else {
                        System.out.println("[VulkanPipeline] Ignoring " + path.getFileName() + ": produced by a different GPU or driver.");
                    }
                } catch (IOException e) {
                    System.err.println("[VulkanPipeline] Failed to read " + path.getFileName() + ", starting with an empty cache: " + e.getMessage());
                }
            }

            VkPipelineCacheCreateInfo cacheInfo = VkPipelineCacheCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO);
            if (initialData != null) cacheInfo.pInitialData(initialData);

            LongBuffer pCache = stack.mallocLong(1);
            int result = vkCreatePipelineCache(vkDevice, cacheInfo, null, pCache);
            if (result != VK_SUCCESS && initialData != null) {
                // A driver may still reject a blob that passed the header check; fall back to an empty cache
                cacheInfo.pInitialData(null);
                result = vkCreatePipelineCache(vkDevice, cacheInfo, null, pCache);
            }
            pipelineCache = (result == VK_SUCCESS) ? pCache.get(0) : 0L;
            if (pipelineCache == 0L) {
                System.err.println("[VulkanPipeline] WARNING: vkCreatePipelineCache failed (" + result + "); pipelines will compile uncached.");
            }
        } finally {
            if (initialData != null) MemoryUtil.memFree(initialData);
        }
    }

    /** Validates the VkPipelineCacheHeaderVersionOne prefix against the running device. */
    private static boolean isPipelineCacheCompatible(byte[] bytes, VkPhysicalDeviceProperties props) {
        if (bytes.length < PIPELINE_CACHE_HEADER_SIZE) return false;
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int headerSize = header.getInt(0);
        int headerVersion = header.getInt(4);
        int vendorId = header.getInt(8);
        int deviceId = header.getInt(12);
        if (headerSize < PIPELINE_CACHE_HEADER_SIZE || headerSize > bytes.length) return false;
        if (headerVersion != VK_PIPELINE_CACHE_HEADER_VERSION_ONE) return false;
        if (vendorId != props.vendorID() || deviceId != props.deviceID()) return false;
        ByteBuffer uuid = props.pipelineCacheUUID();
        for (int i = 0; i < VK_UUID_SIZE; i++) {
            if (bytes[16 + i] != uuid.get(uuid.position() + i)) return false;
        }
        return true;
    }

    /** Writes the cache blob to a temp file and atomically swaps it in. */
    public void savePipelineCache(VkDevice vkDevice) {
        if (vkDevice == null || pipelineCache == 0L) return;

        ByteBuffer data = null;
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pSize = stack.mallocPointer(1);
            if (vkGetPipelineCacheData(vkDevice, pipelineCache, pSize, null) != VK_SUCCESS) return;
            long size = pSize.get(0);
            if (size <= 0L || size > Integer.MAX_VALUE) return;

            data = MemoryUtil.memAlloc((int) size);
            if (vkGetPipelineCacheData(vkDevice, pipelineCache, pSize, data) != VK_SUCCESS) return;
            byte[] bytes = new byte[(int) pSize.get(0)];
            data.get(0, bytes);

            Path target = pipelineCachePath();
            Path temp = target.resolveSibling(PIPELINE_CACHE_FILE_NAME + ".tmp");
            try {
                Files.write(temp, bytes);
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                System.out.println("[VulkanPipeline] Saved VkPipelineCache to " + target.getFileName() + " (" + bytes.length + " bytes).");
            } catch (IOException e) {
                System.err.println("[VulkanPipeline] Failed to save VkPipelineCache: " + e.getMessage());
                try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
            }
        } finally {
            if (data != null) MemoryUtil.memFree(data);
        }
    }

    /**
     * Shader hot reload: rebuilds every VkPipeline from the current SPIR-V against the existing set and pipeline layouts,
     * so VulkanGpuCache's bindless set stays valid. The rebuilt set is kept only when it covers every pipeline that was
     * live; otherwise it is destroyed and the previous pipelines are restored. The caller guarantees the GPU no longer
     * executes work recorded with the old pipelines.
     */
    public boolean reloadGraphicsPipelines(VulkanDevice device) {
        if (device == null || device.getLogicalDevice() == null || pipelineLayout3D == 0L) return false;
        VkDevice vkDevice = device.getLogicalDevice();

        long[] previous = pipelineHandles();
        setPipelineHandles(new long[previous.length]);
        try {
            createGraphicsPipelines(device);
        } catch (RuntimeException e) {
            // A driver or loader error mid-build: drop whatever was built and keep rendering with the previous set
            System.err.println("[VulkanPipeline] Pipeline rebuild threw: " + e);
            destroyPipelines(vkDevice, pipelineHandles());
            setPipelineHandles(previous);
            return false;
        }
        long[] rebuilt = pipelineHandles();

        for (int i = 0; i < previous.length; i++) {
            if (previous[i] != 0L && rebuilt[i] == 0L) {
                destroyPipelines(vkDevice, rebuilt);
                setPipelineHandles(previous);
                return false;
            }
        }
        destroyPipelines(vkDevice, previous);
        savePipelineCache(vkDevice);
        return true;
    }

    /** Every shader-dependent VkPipeline, in a fixed order (layouts are shader-independent and never rebuilt). */
    private long[] pipelineHandles() {
        return new long[] { graphicsPipeline3D, graphicsPipelineShadow, graphicsPipelineSkinned, graphicsPipelineShadowDepth,
                graphicsPipelineShadowDepthSkinned, graphicsPipelinePick, graphicsPipelinePickSkinned, graphicsPipelinePickOverlay,
                graphicsPipelineGrid, graphicsPipelineGridLines, graphicsPipelineUI, graphicsPipelinePost, graphicsPipelineParticles };
    }

    private void setPipelineHandles(long[] h) {
        graphicsPipeline3D = h[0];
        graphicsPipelineShadow = h[1];
        graphicsPipelineSkinned = h[2];
        graphicsPipelineShadowDepth = h[3];
        graphicsPipelineShadowDepthSkinned = h[4];
        graphicsPipelinePick = h[5];
        graphicsPipelinePickSkinned = h[6];
        graphicsPipelinePickOverlay = h[7];
        graphicsPipelineGrid = h[8];
        graphicsPipelineGridLines = h[9];
        graphicsPipelineUI = h[10];
        graphicsPipelinePost = h[11];
        graphicsPipelineParticles = h[12];
    }

    private static void destroyPipelines(VkDevice vkDevice, long[] handles) {
        for (long handle : handles) {
            if (handle != 0L) vkDestroyPipeline(vkDevice, handle, null);
        }
    }

    private void createGraphicsPipelines(VulkanDevice device) {
        VkDevice vkDevice = device.getLogicalDevice();
        long cacheHandle = pipelineCache;

        try (MemoryStack stack = stackPush()) {
            // 1A. Grid Pipeline (Triangle List for Floor Quad - 36-Byte 9-Float Stride)
            long vertGridModule = VulkanShaderLoader.createShaderModule(vkDevice, "/shaders/vulkan/infinite_grid.vert.spv");
            long fragGridModule = VulkanShaderLoader.createShaderModule(vkDevice, "/shaders/vulkan/infinite_grid.frag.spv");

            if (vertGridModule != 0L && fragGridModule != 0L) {
                VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
                shaderStages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertGridModule).pName(stack.UTF8("main"));
                shaderStages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragGridModule).pName(stack.UTF8("main"));

                VkVertexInputBindingDescription.Buffer bindingDescriptionGrid = VkVertexInputBindingDescription.calloc(1, stack).binding(0).stride(36).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
                VkVertexInputAttributeDescription.Buffer attributeDescriptionsGrid = VkVertexInputAttributeDescription.calloc(3, stack);
                attributeDescriptionsGrid.get(0).binding(0).location(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);  // POS
                attributeDescriptionsGrid.get(1).binding(0).location(1).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(12); // COL
                attributeDescriptionsGrid.get(2).binding(0).location(2).format(VK_FORMAT_R32G32_SFLOAT).offset(28); // UV

                VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                        .pVertexBindingDescriptions(bindingDescriptionGrid)
                        .pVertexAttributeDescriptions(attributeDescriptionsGrid);

                VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO).topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).primitiveRestartEnable(false);
                VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).viewportCount(1).scissorCount(1);
                VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO).polygonMode(VK_POLYGON_MODE_FILL).lineWidth(1.0f).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);
                VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO).rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
                VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO).depthTestEnable(true).depthWriteEnable(false).depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL);

                VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                        .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                        .blendEnable(true).srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).colorBlendOp(VK_BLEND_OP_ADD).srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ZERO).alphaBlendOp(VK_BLEND_OP_ADD);

                VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).pAttachments(colorBlendAttachment);
                IntBuffer pDynamicStates = stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR);
                VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(pDynamicStates);

                VkPipelineRenderingCreateInfo renderingCreateInfo = VkPipelineRenderingCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO).pColorAttachmentFormats(stack.ints(VK_FORMAT_B8G8R8A8_UNORM)).depthAttachmentFormat(VK_FORMAT_D32_SFLOAT);

                VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO).pNext(renderingCreateInfo.address()).pStages(shaderStages).pVertexInputState(vertexInputInfo).pInputAssemblyState(inputAssembly).pViewportState(viewportState).pRasterizationState(rasterizer).pMultisampleState(multisampling).pDepthStencilState(depthStencil).pColorBlendState(colorBlending).pDynamicState(dynamicState).layout(pipelineLayoutGrid).renderPass(VK_NULL_HANDLE).subpass(0);

                LongBuffer pPipeline = stack.mallocLong(1);
                if (vkCreateGraphicsPipelines(vkDevice, cacheHandle, pipelineInfo, null, pPipeline) == VK_SUCCESS) {
                    graphicsPipelineGrid = pPipeline.get(0);
                }

                // 1B. Grid Lines Pipeline (Line List for Vertical Y-Axis Pillar)
                VkPipelineInputAssemblyStateCreateInfo inputAssemblyLines = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO).topology(VK_PRIMITIVE_TOPOLOGY_LINE_LIST).primitiveRestartEnable(false);
                VkGraphicsPipelineCreateInfo.Buffer pipelineInfoLines = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO).pNext(renderingCreateInfo.address()).pStages(shaderStages).pVertexInputState(vertexInputInfo).pInputAssemblyState(inputAssemblyLines).pViewportState(viewportState).pRasterizationState(rasterizer).pMultisampleState(multisampling).pDepthStencilState(depthStencil).pColorBlendState(colorBlending).pDynamicState(dynamicState).layout(pipelineLayoutGrid).renderPass(VK_NULL_HANDLE).subpass(0);

                LongBuffer pPipelineLines = stack.mallocLong(1);
                if (vkCreateGraphicsPipelines(vkDevice, cacheHandle, pipelineInfoLines, null, pPipelineLines) == VK_SUCCESS) {
                    graphicsPipelineGridLines = pPipelineLines.get(0);
                }

                VulkanShaderLoader.destroyShaderModule(vkDevice, vertGridModule);
                VulkanShaderLoader.destroyShaderModule(vkDevice, fragGridModule);

                // 1C. Billboard Particles: grid vertex layout and push constants, premultiplied blending (ONE / ONE_MINUS_SRC_ALPHA)
                long vertParticleModule = VulkanShaderLoader.createShaderModule(vkDevice, "/shaders/vulkan/particle.vert.spv");
                long fragParticleModule = VulkanShaderLoader.createShaderModule(vkDevice, "/shaders/vulkan/particle.frag.spv");
                if (vertParticleModule != 0L && fragParticleModule != 0L) {
                    VkPipelineShaderStageCreateInfo.Buffer particleStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
                    particleStages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertParticleModule).pName(stack.UTF8("main"));
                    particleStages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragParticleModule).pName(stack.UTF8("main"));
                    VkPipelineColorBlendAttachmentState.Buffer particleBlend = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                            .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                            .blendEnable(true).srcColorBlendFactor(VK_BLEND_FACTOR_ONE).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).colorBlendOp(VK_BLEND_OP_ADD)
                            .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).alphaBlendOp(VK_BLEND_OP_ADD);
                    VkPipelineColorBlendStateCreateInfo particleBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).pAttachments(particleBlend);
                    VkGraphicsPipelineCreateInfo.Buffer pipelineInfoParticles = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                            .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO).pNext(renderingCreateInfo.address()).pStages(particleStages).pVertexInputState(vertexInputInfo).pInputAssemblyState(inputAssembly).pViewportState(viewportState).pRasterizationState(rasterizer).pMultisampleState(multisampling).pDepthStencilState(depthStencil).pColorBlendState(particleBlending).pDynamicState(dynamicState).layout(pipelineLayoutGrid).renderPass(VK_NULL_HANDLE).subpass(0);
                    LongBuffer pPipelineParticles = stack.mallocLong(1);
                    if (vkCreateGraphicsPipelines(vkDevice, cacheHandle, pipelineInfoParticles, null, pPipelineParticles) == VK_SUCCESS) {
                        graphicsPipelineParticles = pPipelineParticles.get(0);
                    }
                }
                VulkanShaderLoader.destroyShaderModule(vkDevice, vertParticleModule);
                VulkanShaderLoader.destroyShaderModule(vkDevice, fragParticleModule);
            }

            // 2. Standard 3D Scene Pipeline (88-Byte Stride, 22 Floats per Vertex)
            long vert3DModule = VulkanShaderLoader.createShaderModule(vkDevice, "/shaders/vulkan/blinn_phong.vert.spv");
            long frag3DModule = VulkanShaderLoader.createShaderModule(vkDevice, "/shaders/vulkan/blinn_phong.frag.spv");

            if (vert3DModule != 0L && frag3DModule != 0L) {
                VkPipelineShaderStageCreateInfo.Buffer shaderStages3D = VkPipelineShaderStageCreateInfo.calloc(2, stack);
                shaderStages3D.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert3DModule).pName(stack.UTF8("main"));
                shaderStages3D.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag3DModule).pName(stack.UTF8("main"));

                VkVertexInputBindingDescription.Buffer bindingDescription = VkVertexInputBindingDescription.calloc(1, stack).binding(0).stride(88).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
                VkVertexInputAttributeDescription.Buffer attributeDescriptions = VkVertexInputAttributeDescription.calloc(7, stack);
                attributeDescriptions.get(0).binding(0).location(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);  // POS
                attributeDescriptions.get(1).binding(0).location(1).format(VK_FORMAT_R32G32B32_SFLOAT).offset(12); // NORM
                attributeDescriptions.get(2).binding(0).location(2).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(24); // COL
                attributeDescriptions.get(3).binding(0).location(3).format(VK_FORMAT_R32G32_SFLOAT).offset(40); // UV1
                attributeDescriptions.get(4).binding(0).location(4).format(VK_FORMAT_R32G32_SFLOAT).offset(48); // UV2
                attributeDescriptions.get(5).binding(0).location(5).format(VK_FORMAT_R32G32B32_SFLOAT).offset(56); // TAN
                attributeDescriptions.get(6).binding(0).location(6).format(VK_FORMAT_R32G32B32_SFLOAT).offset(68); // BITAN

                VkPipelineVertexInputStateCreateInfo vertexInputInfo3D = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO).pVertexBindingDescriptions(bindingDescription).pVertexAttributeDescriptions(attributeDescriptions);
                VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO).topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).primitiveRestartEnable(false);
                VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).viewportCount(1).scissorCount(1);
                VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO).polygonMode(VK_POLYGON_MODE_FILL).lineWidth(1.0f).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);
                VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO).rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
                
                VkPipelineDepthStencilStateCreateInfo depthStencil3D = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO).depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL);

                VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment3D = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                        .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                        .blendEnable(true)
                        .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                        .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .colorBlendOp(VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                        .dstAlphaBlendFactor(VK_BLEND_FACTOR_ZERO)
                        .alphaBlendOp(VK_BLEND_OP_ADD);

                VkPipelineColorBlendStateCreateInfo colorBlending3D = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).pAttachments(colorBlendAttachment3D);

                IntBuffer pDynamicStates = stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR);
                VkPipelineDynamicStateCreateInfo dynamicState3D = VkPipelineDynamicStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(pDynamicStates);

                VkPipelineRenderingCreateInfo renderingCreateInfo = VkPipelineRenderingCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO).pColorAttachmentFormats(stack.ints(VK_FORMAT_B8G8R8A8_UNORM)).depthAttachmentFormat(VK_FORMAT_D32_SFLOAT);

                VkGraphicsPipelineCreateInfo.Buffer pipelineInfo3D = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO).pNext(renderingCreateInfo.address()).pStages(shaderStages3D).pVertexInputState(vertexInputInfo3D).pInputAssemblyState(inputAssembly).pViewportState(viewportState).pRasterizationState(rasterizer).pMultisampleState(multisampling).pDepthStencilState(depthStencil3D).pColorBlendState(colorBlending3D).pDynamicState(dynamicState3D).layout(pipelineLayout3D).renderPass(VK_NULL_HANDLE).subpass(0);

                LongBuffer pPipeline3D = stack.mallocLong(1);
                if (vkCreateGraphicsPipelines(vkDevice, cacheHandle, pipelineInfo3D, null, pPipeline3D) == VK_SUCCESS) {
                    graphicsPipeline3D = pPipeline3D.get(0);
                    System.out.println("[VulkanPipeline] Created Standard 3D Scene Blinn-Phong VkPipeline [0x" + Long.toHexString(graphicsPipeline3D) + "].");
                }
                // 2B. Dedicated Shadow Blob Pipeline (Depth-Writes OFF for Soft Radial Alpha Blending)
                VkPipelineDepthStencilStateCreateInfo depthStencilShadow = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                        .depthTestEnable(true)
                        .depthWriteEnable(false)
                        .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL);

                VkGraphicsPipelineCreateInfo.Buffer pipelineInfoShadow = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                        .pNext(renderingCreateInfo.address())
                        .pStages(shaderStages3D)
                        .pVertexInputState(vertexInputInfo3D)
                        .pInputAssemblyState(inputAssembly)
                        .pViewportState(viewportState)
                        .pRasterizationState(rasterizer)
                        .pMultisampleState(multisampling)
                        .pDepthStencilState(depthStencilShadow)
                        .pColorBlendState(colorBlending3D)
                        .pDynamicState(dynamicState3D)
                        .layout(pipelineLayout3D)
                        .renderPass(VK_NULL_HANDLE)
                        .subpass(0);

                LongBuffer pPipelineShadow = stack.mallocLong(1);
                if (vkCreateGraphicsPipelines(vkDevice, cacheHandle, pipelineInfoShadow, null, pPipelineShadow) == VK_SUCCESS) {
                    graphicsPipelineShadow = pPipelineShadow.get(0);
                    System.out.println("[VulkanPipeline] Created Dedicated Shadow Blob VkPipeline [0x" + Long.toHexString(graphicsPipelineShadow) + "].");
                }

                // 2C. GPU Skinned 3D Pipeline (120-Byte Stride, 30 Floats per Vertex: 22 static + 4 bone ids + 4 weights)
                long vertSkinnedModule = VulkanShaderLoader.createShaderModule(vkDevice, "/shaders/vulkan/blinn_phong_skinned.vert.spv");
                if (vertSkinnedModule != 0L) {
                    VkPipelineShaderStageCreateInfo.Buffer shaderStagesSkinned = VkPipelineShaderStageCreateInfo.calloc(2, stack);
                    shaderStagesSkinned.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertSkinnedModule).pName(stack.UTF8("main"));
                    shaderStagesSkinned.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag3DModule).pName(stack.UTF8("main"));

                    VkVertexInputBindingDescription.Buffer bindingSkinned = VkVertexInputBindingDescription.calloc(1, stack).binding(0).stride(120).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
                    VkVertexInputAttributeDescription.Buffer attributesSkinned = VkVertexInputAttributeDescription.calloc(9, stack);
                    for (int a = 0; a < 7; a++) {
                        VkVertexInputAttributeDescription src = attributeDescriptions.get(a);
                        attributesSkinned.get(a).binding(0).location(src.location()).format(src.format()).offset(src.offset());
                    }
                    attributesSkinned.get(7).binding(0).location(7).format(VK_FORMAT_R32G32B32A32_UINT).offset(88);    // BONE IDS (raw int bits)
                    attributesSkinned.get(8).binding(0).location(8).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(104); // BONE WEIGHTS

                    VkPipelineVertexInputStateCreateInfo vertexInputSkinned = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO).pVertexBindingDescriptions(bindingSkinned).pVertexAttributeDescriptions(attributesSkinned);

                    VkGraphicsPipelineCreateInfo.Buffer pipelineInfoSkinned = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                            .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO).pNext(renderingCreateInfo.address()).pStages(shaderStagesSkinned).pVertexInputState(vertexInputSkinned).pInputAssemblyState(inputAssembly).pViewportState(viewportState).pRasterizationState(rasterizer).pMultisampleState(multisampling).pDepthStencilState(depthStencil3D).pColorBlendState(colorBlending3D).pDynamicState(dynamicState3D).layout(pipelineLayout3D).renderPass(VK_NULL_HANDLE).subpass(0);

                    LongBuffer pPipelineSkinned = stack.mallocLong(1);
                    if (vkCreateGraphicsPipelines(vkDevice, cacheHandle, pipelineInfoSkinned, null, pPipelineSkinned) == VK_SUCCESS) {
                        graphicsPipelineSkinned = pPipelineSkinned.get(0);
                        System.out.println("[VulkanPipeline] Created GPU Skinned 3D VkPipeline [0x" + Long.toHexString(graphicsPipelineSkinned) + "].");
                    }

                    graphicsPipelineShadowDepthSkinned = createShadowDepthPipeline(vkDevice, cacheHandle, vertSkinnedModule, vertexInputSkinned, inputAssembly, viewportState, rasterizer, multisampling, dynamicState3D);
                    graphicsPipelinePickSkinned = createPickPipeline(vkDevice, cacheHandle, vertSkinnedModule, vertexInputSkinned, inputAssembly, viewportState, rasterizer, multisampling, dynamicState3D);
                    VulkanShaderLoader.destroyShaderModule(vkDevice, vertSkinnedModule);
                }

                // 2D. Directional Shadow Map Depth Pipeline (88-Byte Stride, no color attachments)
                graphicsPipelineShadowDepth = createShadowDepthPipeline(vkDevice, cacheHandle, vert3DModule, vertexInputInfo3D, inputAssembly, viewportState, rasterizer, multisampling, dynamicState3D);
                if (graphicsPipelineShadowDepth != 0L) {
                    System.out.println("[VulkanPipeline] Created Directional Shadow Map Depth VkPipeline [0x" + Long.toHexString(graphicsPipelineShadowDepth) + "].");
                }

                // 2E. Color-ID Picking Pipeline (88-Byte Stride, R8G8B8A8_UINT + D32)
                graphicsPipelinePick = createPickPipeline(vkDevice, cacheHandle, vert3DModule, vertexInputInfo3D, inputAssembly, viewportState, rasterizer, multisampling, dynamicState3D);
                if (graphicsPipelinePick != 0L) {
                    System.out.println("[VulkanPipeline] Created Color-ID Picking VkPipeline [0x" + Long.toHexString(graphicsPipelinePick) + "].");
                }

                VulkanShaderLoader.destroyShaderModule(vkDevice, vert3DModule);
                VulkanShaderLoader.destroyShaderModule(vkDevice, frag3DModule);
            }

            // 3. 2D UI Overlay Pipeline
            long vertUIModule = VulkanShaderLoader.createShaderModule(vkDevice, "/shaders/vulkan/ui.vert.spv");
            long fragUIModule = VulkanShaderLoader.createShaderModule(vkDevice, "/shaders/vulkan/ui.frag.spv");

            if (vertUIModule != 0L && fragUIModule != 0L) {
                VkPipelineShaderStageCreateInfo.Buffer shaderStagesUI = VkPipelineShaderStageCreateInfo.calloc(2, stack);
                shaderStagesUI.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertUIModule).pName(stack.UTF8("main"));
                shaderStagesUI.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragUIModule).pName(stack.UTF8("main"));

                VkVertexInputBindingDescription.Buffer bindingDescriptionUI = VkVertexInputBindingDescription.calloc(1, stack).binding(0).stride(32).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
                VkVertexInputAttributeDescription.Buffer attributeDescriptionsUI = VkVertexInputAttributeDescription.calloc(3, stack);
                attributeDescriptionsUI.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
                attributeDescriptionsUI.get(1).binding(0).location(1).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(8);
                attributeDescriptionsUI.get(2).binding(0).location(2).format(VK_FORMAT_R32G32_SFLOAT).offset(24);   // FONT ATLAS UV

                VkPipelineVertexInputStateCreateInfo vertexInputInfoUI = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO).pVertexBindingDescriptions(bindingDescriptionUI).pVertexAttributeDescriptions(attributeDescriptionsUI);
                VkPipelineInputAssemblyStateCreateInfo inputAssemblyUI = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO).topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).primitiveRestartEnable(false);
                VkPipelineViewportStateCreateInfo viewportStateUI = VkPipelineViewportStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).viewportCount(1).scissorCount(1);
                VkPipelineRasterizationStateCreateInfo rasterizerUI = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO).polygonMode(VK_POLYGON_MODE_FILL).lineWidth(1.0f).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);
                VkPipelineMultisampleStateCreateInfo multisamplingUI = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO).rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
                VkPipelineDepthStencilStateCreateInfo depthStencilUI = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO).depthTestEnable(false).depthWriteEnable(false).depthCompareOp(VK_COMPARE_OP_ALWAYS);

                VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachmentUI = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                        .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                        .blendEnable(true)
                        .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                        .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .colorBlendOp(VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                        .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .alphaBlendOp(VK_BLEND_OP_ADD);

                VkPipelineColorBlendStateCreateInfo colorBlendingUI = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).pAttachments(colorBlendAttachmentUI);
                IntBuffer pDynamicStates = stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR);
                VkPipelineDynamicStateCreateInfo dynamicStateUI = VkPipelineDynamicStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(pDynamicStates);

                VkPipelineRenderingCreateInfo renderingCreateInfoUI = VkPipelineRenderingCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO).pColorAttachmentFormats(stack.ints(VK_FORMAT_B8G8R8A8_UNORM));

                VkGraphicsPipelineCreateInfo.Buffer pipelineInfoUI = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO).pNext(renderingCreateInfoUI.address()).pStages(shaderStagesUI).pVertexInputState(vertexInputInfoUI).pInputAssemblyState(inputAssemblyUI).pViewportState(viewportStateUI).pRasterizationState(rasterizerUI).pMultisampleState(multisamplingUI).pDepthStencilState(depthStencilUI).pColorBlendState(colorBlendingUI).pDynamicState(dynamicStateUI).layout(pipelineLayoutUI).renderPass(VK_NULL_HANDLE).subpass(0);

                LongBuffer pPipelineUI = stack.mallocLong(1);
                if (vkCreateGraphicsPipelines(vkDevice, cacheHandle, pipelineInfoUI, null, pPipelineUI) == VK_SUCCESS) {
                    graphicsPipelineUI = pPipelineUI.get(0);
                    System.out.println("[VulkanPipeline] Created 2D UI Overlay VkPipeline [0x" + Long.toHexString(graphicsPipelineUI) + "].");
                }

                VulkanShaderLoader.destroyShaderModule(vkDevice, vertUIModule);
                VulkanShaderLoader.destroyShaderModule(vkDevice, fragUIModule);
            }

            // 3b. Camera post-process composite (fullscreen triangle into the swapchain Scene rect)
            graphicsPipelinePost = createPostProcessPipeline(vkDevice, cacheHandle);
            if (graphicsPipelinePost != 0L) {
                System.out.println("[VulkanPipeline] Created Post-Process VkPipeline [0x" + Long.toHexString(graphicsPipelinePost) + "].");
            }

            // 4. Color-ID Pick Overlay Pipeline (12-Byte Stride, R8G8B8A8_UINT + D32)
            graphicsPipelinePickOverlay = createPickOverlayPipeline(vkDevice, cacheHandle);
            if (graphicsPipelinePickOverlay != 0L) {
                System.out.println("[VulkanPipeline] Created Color-ID Pick Overlay VkPipeline [0x" + Long.toHexString(graphicsPipelinePickOverlay) + "].");
            }
        }
    }

    /** Opaque post-process pipeline: no vertex input, no depth, B8G8R8A8 swapchain color; 0L when unavailable. */
    private long createPostProcessPipeline(VkDevice vkDevice, long cacheHandle) {
        if (pipelineLayoutPost == 0L) return 0L;
        long vertModule = VulkanShaderLoader.createShaderModule(vkDevice, "/shaders/vulkan/postprocess.vert.spv");
        long fragModule = VulkanShaderLoader.createShaderModule(vkDevice, "/shaders/vulkan/postprocess.frag.spv");
        if (vertModule == 0L || fragModule == 0L) {
            VulkanShaderLoader.destroyShaderModule(vkDevice, vertModule);
            VulkanShaderLoader.destroyShaderModule(vkDevice, fragModule);
            return 0L;
        }
        try (MemoryStack stack = stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(stack.UTF8("main"));
            stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(stack.UTF8("main"));

            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO).topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).viewportCount(1).scissorCount(1);
            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO).polygonMode(VK_POLYGON_MODE_FILL).lineWidth(1.0f).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);
            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO).rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO).depthTestEnable(false).depthWriteEnable(false).depthCompareOp(VK_COMPARE_OP_ALWAYS);
            VkPipelineColorBlendAttachmentState.Buffer blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                    .blendEnable(false);
            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).pAttachments(blendAttachment);
            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));
            VkPipelineRenderingCreateInfo renderingInfo = VkPipelineRenderingCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO).pColorAttachmentFormats(stack.ints(VK_FORMAT_B8G8R8A8_UNORM));

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO).pNext(renderingInfo.address()).pStages(stages)
                    .pVertexInputState(vertexInput).pInputAssemblyState(inputAssembly).pViewportState(viewportState)
                    .pRasterizationState(rasterizer).pMultisampleState(multisampling).pDepthStencilState(depthStencil)
                    .pColorBlendState(colorBlending).pDynamicState(dynamicState).layout(pipelineLayoutPost).renderPass(VK_NULL_HANDLE).subpass(0);

            LongBuffer pPipeline = stack.mallocLong(1);
            long pipeline = vkCreateGraphicsPipelines(vkDevice, cacheHandle, pipelineInfo, null, pPipeline) == VK_SUCCESS ? pPipeline.get(0) : 0L;
            VulkanShaderLoader.destroyShaderModule(vkDevice, vertModule);
            VulkanShaderLoader.destroyShaderModule(vkDevice, fragModule);
            return pipeline;
        }
    }

    /**
     * Pick overlay pipeline: pick_overlay.vert/.frag write {@code PICK_OVERLAY_ID_BASE | id} for screen-space triangles
     * (x, y, id floats) into the PICK_ID_FORMAT attachment. Depth testing is off so handles win over meshes beneath them;
     * the D32 format only matches the pick pass's depth attachment.
     */
    private long createPickOverlayPipeline(VkDevice vkDevice, long cacheHandle) {
        long vertModule = VulkanShaderLoader.createShaderModule(vkDevice, "/shaders/vulkan/pick_overlay.vert.spv");
        long fragModule = VulkanShaderLoader.createShaderModule(vkDevice, "/shaders/vulkan/pick_overlay.frag.spv");
        if (vertModule == 0L || fragModule == 0L) {
            VulkanShaderLoader.destroyShaderModule(vkDevice, vertModule);
            VulkanShaderLoader.destroyShaderModule(vkDevice, fragModule);
            return 0L;
        }

        try (MemoryStack stack = stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(stack.UTF8("main"));
            stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(stack.UTF8("main"));

            VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack)
                    .binding(0).stride(IRenderBackend.PICK_OVERLAY_VERTEX_FLOATS * Float.BYTES).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(2, stack);
            attributes.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);  // scene-tile NDC
            attributes.get(1).binding(0).location(1).format(VK_FORMAT_R32_SFLOAT).offset(8);     // overlay ID low 16 bits

            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO).pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attributes);
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO).topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST).primitiveRestartEnable(false);
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).viewportCount(1).scissorCount(1);
            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO).polygonMode(VK_POLYGON_MODE_FILL).lineWidth(1.0f).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);
            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO).rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO).depthTestEnable(false).depthWriteEnable(false).depthCompareOp(VK_COMPARE_OP_ALWAYS);

            // Integer attachments cannot blend
            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                    .blendEnable(false);
            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).pAttachments(colorBlendAttachment);
            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

            VkPipelineRenderingCreateInfo renderingCreateInfo = VkPipelineRenderingCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO)
                    .pColorAttachmentFormats(stack.ints(PICK_ID_FORMAT))
                    .depthAttachmentFormat(VK_FORMAT_D32_SFLOAT);

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO).pNext(renderingCreateInfo.address()).pStages(stages).pVertexInputState(vertexInput).pInputAssemblyState(inputAssembly).pViewportState(viewportState).pRasterizationState(rasterizer).pMultisampleState(multisampling).pDepthStencilState(depthStencil).pColorBlendState(colorBlending).pDynamicState(dynamicState).layout(pipelineLayoutUI).renderPass(VK_NULL_HANDLE).subpass(0);

            LongBuffer pPipeline = stack.mallocLong(1);
            long pipeline = vkCreateGraphicsPipelines(vkDevice, cacheHandle, pipelineInfo, null, pPipeline) == VK_SUCCESS ? pPipeline.get(0) : 0L;
            VulkanShaderLoader.destroyShaderModule(vkDevice, vertModule);
            VulkanShaderLoader.destroyShaderModule(vkDevice, fragModule);
            return pipeline;
        }
    }

    /**
     * Depth-only pipeline rendering into the D32 shadow map. Reuses the blinn_phong vertex stage (passFlags 2.0 selects
     * the light-space transform) with shadow_depth.frag, which only performs the alpha cutout and never touches the
     * shadow map binding, so binding 2 is not statically used while the shadow map is a depth attachment.
     */
    private long createShadowDepthPipeline(VkDevice vkDevice, long cacheHandle, long vertModule,
                                           VkPipelineVertexInputStateCreateInfo vertexInput,
                                           VkPipelineInputAssemblyStateCreateInfo inputAssembly,
                                           VkPipelineViewportStateCreateInfo viewportState,
                                           VkPipelineRasterizationStateCreateInfo rasterizer,
                                           VkPipelineMultisampleStateCreateInfo multisampling,
                                           VkPipelineDynamicStateCreateInfo dynamicState) {
        long fragModule = VulkanShaderLoader.createShaderModule(vkDevice, "/shaders/vulkan/shadow_depth.frag.spv");
        if (vertModule == 0L || fragModule == 0L) {
            VulkanShaderLoader.destroyShaderModule(vkDevice, fragModule);
            return 0L;
        }

        try (MemoryStack stack = stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(stack.UTF8("main"));
            stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(stack.UTF8("main"));

            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                    .depthTestEnable(true)
                    .depthWriteEnable(true)
                    .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL);

            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO);

            VkPipelineRenderingCreateInfo renderingCreateInfo = VkPipelineRenderingCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO)
                    .depthAttachmentFormat(VulkanGpuCache.SHADOW_MAP_FORMAT);

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO).pNext(renderingCreateInfo.address()).pStages(stages).pVertexInputState(vertexInput).pInputAssemblyState(inputAssembly).pViewportState(viewportState).pRasterizationState(rasterizer).pMultisampleState(multisampling).pDepthStencilState(depthStencil).pColorBlendState(colorBlending).pDynamicState(dynamicState).layout(pipelineLayout3D).renderPass(VK_NULL_HANDLE).subpass(0);

            LongBuffer pPipeline = stack.mallocLong(1);
            long pipeline = vkCreateGraphicsPipelines(vkDevice, cacheHandle, pipelineInfo, null, pPipeline) == VK_SUCCESS ? pPipeline.get(0) : 0L;
            VulkanShaderLoader.destroyShaderModule(vkDevice, fragModule);
            return pipeline;
        }
    }

    /**
     * Color-ID pipeline: the blinn_phong vertex stage (scene pass flag, editor camera) with entity_id.frag writing the
     * pushed pick ID into an unblended PICK_ID_FORMAT attachment, depth-tested so only the nearest surface survives.
     */
    private long createPickPipeline(VkDevice vkDevice, long cacheHandle, long vertModule,
                                    VkPipelineVertexInputStateCreateInfo vertexInput,
                                    VkPipelineInputAssemblyStateCreateInfo inputAssembly,
                                    VkPipelineViewportStateCreateInfo viewportState,
                                    VkPipelineRasterizationStateCreateInfo rasterizer,
                                    VkPipelineMultisampleStateCreateInfo multisampling,
                                    VkPipelineDynamicStateCreateInfo dynamicState) {
        long fragModule = VulkanShaderLoader.createShaderModule(vkDevice, "/shaders/vulkan/entity_id.frag.spv");
        if (vertModule == 0L || fragModule == 0L) {
            VulkanShaderLoader.destroyShaderModule(vkDevice, fragModule);
            return 0L;
        }

        try (MemoryStack stack = stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(stack.UTF8("main"));
            stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(stack.UTF8("main"));

            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                    .depthTestEnable(true)
                    .depthWriteEnable(true)
                    .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL);

            // Integer attachments cannot blend
            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                    .blendEnable(false);
            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .pAttachments(colorBlendAttachment);

            VkPipelineRenderingCreateInfo renderingCreateInfo = VkPipelineRenderingCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO)
                    .pColorAttachmentFormats(stack.ints(PICK_ID_FORMAT))
                    .depthAttachmentFormat(VK_FORMAT_D32_SFLOAT);

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO).pNext(renderingCreateInfo.address()).pStages(stages).pVertexInputState(vertexInput).pInputAssemblyState(inputAssembly).pViewportState(viewportState).pRasterizationState(rasterizer).pMultisampleState(multisampling).pDepthStencilState(depthStencil).pColorBlendState(colorBlending).pDynamicState(dynamicState).layout(pipelineLayout3D).renderPass(VK_NULL_HANDLE).subpass(0);

            LongBuffer pPipeline = stack.mallocLong(1);
            long pipeline = vkCreateGraphicsPipelines(vkDevice, cacheHandle, pipelineInfo, null, pPipeline) == VK_SUCCESS ? pPipeline.get(0) : 0L;
            VulkanShaderLoader.destroyShaderModule(vkDevice, fragModule);
            return pipeline;
        }
    }

    public long getMinUniformBufferOffsetAlignment() { return minUniformBufferOffsetAlignment; }
    /** Number of slots in the bindless texture table (binding 4 descriptorCount). */
    public int getBindlessTextureCapacity() { return bindlessTextureCapacity; }
    public long getDescriptorSetLayout3D() { return descriptorSetLayout3D; }
    public long getPipelineLayout3D() { return pipelineLayout3D; }
    public long getGraphicsPipeline3D() { return graphicsPipeline3D; }
    public long getGraphicsPipelineShadow() { return graphicsPipelineShadow != 0L ? graphicsPipelineShadow : graphicsPipeline3D; }
    public long getGraphicsPipelineSkinned() { return graphicsPipelineSkinned; }
    public long getGraphicsPipelineShadowDepth() { return graphicsPipelineShadowDepth; }
    public long getGraphicsPipelineShadowDepthSkinned() { return graphicsPipelineShadowDepthSkinned; }
    public long getGraphicsPipelinePick() { return graphicsPipelinePick; }
    public long getGraphicsPipelinePickSkinned() { return graphicsPipelinePickSkinned; }
    public long getGraphicsPipelinePickOverlay() { return graphicsPipelinePickOverlay; }

    public long getDescriptorSetLayoutGrid() { return descriptorSetLayoutGrid; }
    public long getPipelineLayoutGrid() { return pipelineLayoutGrid; }
    public long getGraphicsPipelineGrid() { return graphicsPipelineGrid; }
    public long getGraphicsPipelineGridLines() { return graphicsPipelineGridLines; }
    public long getGraphicsPipelineParticles() { return graphicsPipelineParticles; }

    public long getDescriptorSetLayoutUI() { return descriptorSetLayoutUI; }
    public long getPipelineLayoutUI() { return pipelineLayoutUI; }
    public long getGraphicsPipelineUI() { return graphicsPipelineUI; }
    public long getPipelineLayoutPost() { return pipelineLayoutPost; }
    public long getGraphicsPipelinePost() { return graphicsPipelinePost; }

    public void cleanup(VulkanDevice device) {
        if (device == null || device.getLogicalDevice() == null) return;
        VkDevice vkDevice = device.getLogicalDevice();

        if (pipelineCache != 0L) {
            savePipelineCache(vkDevice);
            vkDestroyPipelineCache(vkDevice, pipelineCache, null);
            pipelineCache = 0L;
        }

        if (graphicsPipeline3D != 0L) { vkDestroyPipeline(vkDevice, graphicsPipeline3D, null); graphicsPipeline3D = 0L; }
        if (graphicsPipelineShadow != 0L) { vkDestroyPipeline(vkDevice, graphicsPipelineShadow, null); graphicsPipelineShadow = 0L; }
        if (graphicsPipelineSkinned != 0L) { vkDestroyPipeline(vkDevice, graphicsPipelineSkinned, null); graphicsPipelineSkinned = 0L; }
        if (graphicsPipelineShadowDepth != 0L) { vkDestroyPipeline(vkDevice, graphicsPipelineShadowDepth, null); graphicsPipelineShadowDepth = 0L; }
        if (graphicsPipelineShadowDepthSkinned != 0L) { vkDestroyPipeline(vkDevice, graphicsPipelineShadowDepthSkinned, null); graphicsPipelineShadowDepthSkinned = 0L; }
        if (graphicsPipelinePick != 0L) { vkDestroyPipeline(vkDevice, graphicsPipelinePick, null); graphicsPipelinePick = 0L; }
        if (graphicsPipelinePickSkinned != 0L) { vkDestroyPipeline(vkDevice, graphicsPipelinePickSkinned, null); graphicsPipelinePickSkinned = 0L; }
        if (graphicsPipelinePickOverlay != 0L) { vkDestroyPipeline(vkDevice, graphicsPipelinePickOverlay, null); graphicsPipelinePickOverlay = 0L; }
        if (pipelineLayout3D != 0L) { vkDestroyPipelineLayout(vkDevice, pipelineLayout3D, null); pipelineLayout3D = 0L; }
        if (descriptorSetLayout3D != 0L) { vkDestroyDescriptorSetLayout(vkDevice, descriptorSetLayout3D, null); descriptorSetLayout3D = 0L; }

        if (graphicsPipelineGrid != 0L) { vkDestroyPipeline(vkDevice, graphicsPipelineGrid, null); graphicsPipelineGrid = 0L; }
        if (graphicsPipelineGridLines != 0L) { vkDestroyPipeline(vkDevice, graphicsPipelineGridLines, null); graphicsPipelineGridLines = 0L; }
        if (pipelineLayoutGrid != 0L) { vkDestroyPipelineLayout(vkDevice, pipelineLayoutGrid, null); pipelineLayoutGrid = 0L; }
        if (descriptorSetLayoutGrid != 0L) { vkDestroyDescriptorSetLayout(vkDevice, descriptorSetLayoutGrid, null); descriptorSetLayoutGrid = 0L; }

        if (graphicsPipelineUI != 0L) { vkDestroyPipeline(vkDevice, graphicsPipelineUI, null); graphicsPipelineUI = 0L; }
        if (pipelineLayoutUI != 0L) { vkDestroyPipelineLayout(vkDevice, pipelineLayoutUI, null); pipelineLayoutUI = 0L; }
        if (descriptorSetLayoutUI != 0L) { vkDestroyDescriptorSetLayout(vkDevice, descriptorSetLayoutUI, null); descriptorSetLayoutUI = 0L; }

        if (graphicsPipelinePost != 0L) { vkDestroyPipeline(vkDevice, graphicsPipelinePost, null); graphicsPipelinePost = 0L; }
        if (pipelineLayoutPost != 0L) { vkDestroyPipelineLayout(vkDevice, pipelineLayoutPost, null); pipelineLayoutPost = 0L; }
    }
}
