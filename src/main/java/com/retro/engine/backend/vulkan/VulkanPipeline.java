package com.retro.engine.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO;

/**
 * Manages Vulkan 1.3 Graphics Pipelines for offscreen 3D Blinn-Phong geometry (88-byte 22-float stride),
 * infinite 3D grid rendering, dynamic ground shadow blobs, vertical Y-axis pillar lines,
 * and swapchain 2D UI composition using Dynamic Rendering (VK_KHR_dynamic_rendering).
 * Features disk-backed VkPipelineCache serialization for instant shader compilation.
 */
public class VulkanPipeline {

    private static final String CACHE_FILE_NAME = "vulkan_pipeline_cache.bin";

    private long minUniformBufferOffsetAlignment = 64L;
    private long pipelineCache = 0L;

    private long descriptorSetLayout3D = 0L;
    private long pipelineLayout3D = 0L;
    private long graphicsPipeline3D = 0L;

    private long descriptorSetLayoutGrid = 0L;
    private long pipelineLayoutGrid = 0L;
    private long graphicsPipelineGrid = 0L;
    private long graphicsPipelineGridLines = 0L;

    private long descriptorSetLayoutUI = 0L;
    private long pipelineLayoutUI = 0L;
    private long graphicsPipelineUI = 0L;

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
        }

        initPipelineCache(vkDevice);

        try (MemoryStack stack = stackPush()) {
            // 1. 3D Scene Pass Layout
            VkDescriptorSetLayoutBinding.Buffer bindings3D = VkDescriptorSetLayoutBinding.calloc(3, stack);
            bindings3D.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings3D.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings3D.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);

            VkDescriptorSetLayoutCreateInfo dsCreateInfo3D = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings3D);

            LongBuffer pDSLayout3D = stack.mallocLong(1);
            if (vkCreateDescriptorSetLayout(vkDevice, dsCreateInfo3D, null, pDSLayout3D) != VK_SUCCESS) return false;
            descriptorSetLayout3D = pDSLayout3D.get(0);

            VkPushConstantRange.Buffer pushConstants3D = VkPushConstantRange.calloc(1, stack);
            pushConstants3D.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(272);

            VkPipelineLayoutCreateInfo layoutCreateInfo3D = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descriptorSetLayout3D))
                    .pPushConstantRanges(pushConstants3D);

            LongBuffer pPipelineLayout3D = stack.mallocLong(1);
            if (vkCreatePipelineLayout(vkDevice, layoutCreateInfo3D, null, pPipelineLayout3D) != VK_SUCCESS) return false;
            pipelineLayout3D = pPipelineLayout3D.get(0);

            // 2. Infinite 3D Grid Pass Layout
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

            VkPipelineLayoutCreateInfo layoutCreateInfoUI = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);

            LongBuffer pPipelineLayoutUI = stack.mallocLong(1);
            if (vkCreatePipelineLayout(vkDevice, layoutCreateInfoUI, null, pPipelineLayoutUI) != VK_SUCCESS) return false;
            pipelineLayoutUI = pPipelineLayoutUI.get(0);

            System.out.println("[VulkanPipeline] Layouts and Descriptor Sets configured.");
            createGraphicsPipelines(device);

            return true;
        }
    }

    private void initPipelineCache(VkDevice vkDevice) {
        ByteBuffer pInitialData = null;
        try (MemoryStack stack = stackPush()) {
            VkPipelineCacheCreateInfo cacheCreateInfo = VkPipelineCacheCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO);

            File cacheFile = new File(System.getProperty("user.dir"), CACHE_FILE_NAME);
            if (cacheFile.exists() && cacheFile.length() > 0) {
                try (FileInputStream fis = new FileInputStream(cacheFile)) {
                    byte[] cacheData = fis.readAllBytes();
                    pInitialData = MemoryUtil.memAlloc(cacheData.length);
                    pInitialData.put(cacheData).flip();
                    cacheCreateInfo.pInitialData(pInitialData);
                    System.out.println("[VulkanPipeline] Loaded disk VkPipelineCache (" + cacheData.length + " bytes).");
                } catch (Exception e) {
                    System.err.println("[VulkanPipeline] Failed to read VkPipelineCache file, creating new cache: " + e.getMessage());
                }
            }

            LongBuffer pCache = stack.mallocLong(1);
            if (vkCreatePipelineCache(vkDevice, cacheCreateInfo, null, pCache) == VK_SUCCESS) {
                pipelineCache = pCache.get(0);
            }
        } finally {
            if (pInitialData != null) {
                MemoryUtil.memFree(pInitialData);
            }
        }
    }

    private void savePipelineCache(VkDevice vkDevice) {
        if (pipelineCache == 0L) return;

        try (MemoryStack stack = stackPush()) {
            PointerBuffer pSize = stack.mallocPointer(1);
            if (vkGetPipelineCacheData(vkDevice, pipelineCache, pSize, null) == VK_SUCCESS) {
                long size = pSize.get(0);
                if (size > 0) {
                    ByteBuffer buffer = MemoryUtil.memAlloc((int) size);
                    if (vkGetPipelineCacheData(vkDevice, pipelineCache, pSize, buffer) == VK_SUCCESS) {
                        byte[] bytes = new byte[(int) size];
                        buffer.get(bytes);
                        File cacheFile = new File(System.getProperty("user.dir"), CACHE_FILE_NAME);
                        try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                            fos.write(bytes);
                            System.out.println("[VulkanPipeline] Saved disk VkPipelineCache (" + size + " bytes).");
                        } catch (Exception e) {
                            System.err.println("[VulkanPipeline] Failed to save VkPipelineCache to disk: " + e.getMessage());
                        }
                    }
                    MemoryUtil.memFree(buffer);
                }
            }
        }
    }

    private void createGraphicsPipelines(VulkanDevice device) {
        VkDevice vkDevice = device.getLogicalDevice();

        try (MemoryStack stack = stackPush()) {
            // 1A. Grid Pipeline (Triangle List for Floor Quad)
            long vertGridModule = VulkanShaderLoader.createShaderModule(vkDevice, "/shaders/vulkan/infinite_grid.vert.spv");
            long fragGridModule = VulkanShaderLoader.createShaderModule(vkDevice, "/shaders/vulkan/infinite_grid.frag.spv");

            if (vertGridModule != 0L && fragGridModule != 0L) {
                VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
                shaderStages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertGridModule).pName(stack.UTF8("main"));
                shaderStages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragGridModule).pName(stack.UTF8("main"));

                VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);
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
                if (vkCreateGraphicsPipelines(vkDevice, pipelineCache, pipelineInfo, null, pPipeline) == VK_SUCCESS) {
                    graphicsPipelineGrid = pPipeline.get(0);
                }

                // 1B. Grid Lines Pipeline (Line List for Vertical Y-Axis Pillar)
                VkPipelineInputAssemblyStateCreateInfo inputAssemblyLines = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO).topology(VK_PRIMITIVE_TOPOLOGY_LINE_LIST).primitiveRestartEnable(false);
                VkGraphicsPipelineCreateInfo.Buffer pipelineInfoLines = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO).pNext(renderingCreateInfo.address()).pStages(shaderStages).pVertexInputState(vertexInputInfo).pInputAssemblyState(inputAssemblyLines).pViewportState(viewportState).pRasterizationState(rasterizer).pMultisampleState(multisampling).pDepthStencilState(depthStencil).pColorBlendState(colorBlending).pDynamicState(dynamicState).layout(pipelineLayoutGrid).renderPass(VK_NULL_HANDLE).subpass(0);

                LongBuffer pPipelineLines = stack.mallocLong(1);
                if (vkCreateGraphicsPipelines(vkDevice, pipelineCache, pipelineInfoLines, null, pPipelineLines) == VK_SUCCESS) {
                    graphicsPipelineGridLines = pPipelineLines.get(0);
                }

                VulkanShaderLoader.destroyShaderModule(vkDevice, vertGridModule);
                VulkanShaderLoader.destroyShaderModule(vkDevice, fragGridModule);
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
                if (vkCreateGraphicsPipelines(vkDevice, pipelineCache, pipelineInfo3D, null, pPipeline3D) == VK_SUCCESS) {
                    graphicsPipeline3D = pPipeline3D.get(0);
                    System.out.println("[VulkanPipeline] Created Standard 3D Scene Blinn-Phong VkPipeline [0x" + Long.toHexString(graphicsPipeline3D) + "].");
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

                VkVertexInputBindingDescription.Buffer bindingDescriptionUI = VkVertexInputBindingDescription.calloc(1, stack).binding(0).stride(24).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
                VkVertexInputAttributeDescription.Buffer attributeDescriptionsUI = VkVertexInputAttributeDescription.calloc(2, stack);
                attributeDescriptionsUI.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
                attributeDescriptionsUI.get(1).binding(0).location(1).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(8);

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
                        .dstAlphaBlendFactor(VK_BLEND_FACTOR_ZERO)
                        .alphaBlendOp(VK_BLEND_OP_ADD);

                VkPipelineColorBlendStateCreateInfo colorBlendingUI = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).pAttachments(colorBlendAttachmentUI);
                IntBuffer pDynamicStates = stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR);
                VkPipelineDynamicStateCreateInfo dynamicStateUI = VkPipelineDynamicStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(pDynamicStates);

                VkPipelineRenderingCreateInfo renderingCreateInfoUI = VkPipelineRenderingCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO).pColorAttachmentFormats(stack.ints(VK_FORMAT_B8G8R8A8_UNORM));

                VkGraphicsPipelineCreateInfo.Buffer pipelineInfoUI = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO).pNext(renderingCreateInfoUI.address()).pStages(shaderStagesUI).pVertexInputState(vertexInputInfoUI).pInputAssemblyState(inputAssemblyUI).pViewportState(viewportStateUI).pRasterizationState(rasterizerUI).pMultisampleState(multisamplingUI).pDepthStencilState(depthStencilUI).pColorBlendState(colorBlendingUI).pDynamicState(dynamicStateUI).layout(pipelineLayoutUI).renderPass(VK_NULL_HANDLE).subpass(0);

                LongBuffer pPipelineUI = stack.mallocLong(1);
                if (vkCreateGraphicsPipelines(vkDevice, pipelineCache, pipelineInfoUI, null, pPipelineUI) == VK_SUCCESS) {
                    graphicsPipelineUI = pPipelineUI.get(0);
                    System.out.println("[VulkanPipeline] Created 2D UI Overlay VkPipeline [0x" + Long.toHexString(graphicsPipelineUI) + "].");
                }

                VulkanShaderLoader.destroyShaderModule(vkDevice, vertUIModule);
                VulkanShaderLoader.destroyShaderModule(vkDevice, fragUIModule);
            }
        }
    }

    public long getMinUniformBufferOffsetAlignment() { return minUniformBufferOffsetAlignment; }
    public long getDescriptorSetLayout3D() { return descriptorSetLayout3D; }
    public long getPipelineLayout3D() { return pipelineLayout3D; }
    public long getGraphicsPipeline3D() { return graphicsPipeline3D; }

    public long getDescriptorSetLayoutGrid() { return descriptorSetLayoutGrid; }
    public long getPipelineLayoutGrid() { return pipelineLayoutGrid; }
    public long getGraphicsPipelineGrid() { return graphicsPipelineGrid; }
    public long getGraphicsPipelineGridLines() { return graphicsPipelineGridLines; }

    public long getDescriptorSetLayoutUI() { return descriptorSetLayoutUI; }
    public long getPipelineLayoutUI() { return pipelineLayoutUI; }
    public long getGraphicsPipelineUI() { return graphicsPipelineUI; }

    public void cleanup(VulkanDevice device) {
        if (device == null || device.getLogicalDevice() == null) return;
        VkDevice vkDevice = device.getLogicalDevice();

        savePipelineCache(vkDevice);
        if (pipelineCache != 0L) { vkDestroyPipelineCache(vkDevice, pipelineCache, null); pipelineCache = 0L; }

        if (graphicsPipeline3D != 0L) { vkDestroyPipeline(vkDevice, graphicsPipeline3D, null); graphicsPipeline3D = 0L; }
        if (pipelineLayout3D != 0L) { vkDestroyPipelineLayout(vkDevice, pipelineLayout3D, null); pipelineLayout3D = 0L; }
        if (descriptorSetLayout3D != 0L) { vkDestroyDescriptorSetLayout(vkDevice, descriptorSetLayout3D, null); descriptorSetLayout3D = 0L; }

        if (graphicsPipelineGrid != 0L) { vkDestroyPipeline(vkDevice, graphicsPipelineGrid, null); graphicsPipelineGrid = 0L; }
        if (graphicsPipelineGridLines != 0L) { vkDestroyPipelineLayout(vkDevice, pipelineLayoutGrid, null); pipelineLayoutGrid = 0L; }
        if (pipelineLayoutGrid != 0L) { vkDestroyPipelineLayout(vkDevice, pipelineLayoutGrid, null); pipelineLayoutGrid = 0L; }
        if (descriptorSetLayoutGrid != 0L) { vkDestroyDescriptorSetLayout(vkDevice, descriptorSetLayoutGrid, null); descriptorSetLayoutGrid = 0L; }

        if (graphicsPipelineUI != 0L) { vkDestroyPipeline(vkDevice, graphicsPipelineUI, null); graphicsPipelineUI = 0L; }
        if (pipelineLayoutUI != 0L) { vkDestroyPipelineLayout(vkDevice, pipelineLayoutUI, null); pipelineLayoutUI = 0L; }
        if (descriptorSetLayoutUI != 0L) { vkDestroyDescriptorSetLayout(vkDevice, descriptorSetLayoutUI, null); descriptorSetLayoutUI = 0L; }
    }
}
