package com.retro.engine.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO;

/**
 * Manages Vulkan 1.3 Graphics Pipelines for offscreen 3D Blinn-Phong geometry (88-byte 22-float stride),
 * GPU-skinned geometry (120-byte 30-float stride with bone palette storage buffer at binding 3),
 * infinite 3D grid rendering (36-byte 9-float stride VBO from GizmoState matching Metal backend),
 * dynamic ground shadow blobs, depth-only directional shadow map passes, vertical Y-axis pillar lines, and swapchain 2D UI composition
 * using Dynamic Rendering (VK_KHR_dynamic_rendering).
 * Delegates binary pipeline cache disk serialization to VulkanPipelineCache.
 */
public class VulkanPipeline {

    private final VulkanPipelineCache pipelineCacheManager = new VulkanPipelineCache();

    private long minUniformBufferOffsetAlignment = 64L;

    private long descriptorSetLayout3D = 0L;
    private long pipelineLayout3D = 0L;
    private long graphicsPipeline3D = 0L;
    private long graphicsPipelineShadow = 0L;
    private long graphicsPipelineSkinned = 0L;
    // Depth-only directional shadow map pipelines (static 88 B and skinned 120 B strides)
    private long graphicsPipelineShadowDepth = 0L;
    private long graphicsPipelineShadowDepthSkinned = 0L;

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

        pipelineCacheManager.initialize(vkDevice);

        try (MemoryStack stack = stackPush()) {
            // 1. 3D Scene Pass Layout
            VkDescriptorSetLayoutBinding.Buffer bindings3D = VkDescriptorSetLayoutBinding.calloc(4, stack);
            bindings3D.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings3D.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings3D.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings3D.get(3).binding(3).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC).descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT);

            VkDescriptorSetLayoutCreateInfo dsCreateInfo3D = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings3D);

            LongBuffer pDSLayout3D = stack.mallocLong(1);
            if (vkCreateDescriptorSetLayout(vkDevice, dsCreateInfo3D, null, pDSLayout3D) != VK_SUCCESS) return false;
            descriptorSetLayout3D = pDSLayout3D.get(0);

            VkPushConstantRange.Buffer pushConstants3D = VkPushConstantRange.calloc(1, stack);
            pushConstants3D.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(80); // mat4 model + vec4 params; view/proj come from the binding 0 UBO

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

    private void createGraphicsPipelines(VulkanDevice device) {
        VkDevice vkDevice = device.getLogicalDevice();
        long cacheHandle = pipelineCacheManager.getHandle();

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
                    VulkanShaderLoader.destroyShaderModule(vkDevice, vertSkinnedModule);
                }

                // 2D. Directional Shadow Map Depth Pipeline (88-Byte Stride, no color attachments)
                graphicsPipelineShadowDepth = createShadowDepthPipeline(vkDevice, cacheHandle, vert3DModule, vertexInputInfo3D, inputAssembly, viewportState, rasterizer, multisampling, dynamicState3D);
                if (graphicsPipelineShadowDepth != 0L) {
                    System.out.println("[VulkanPipeline] Created Directional Shadow Map Depth VkPipeline [0x" + Long.toHexString(graphicsPipelineShadowDepth) + "].");
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
                if (vkCreateGraphicsPipelines(vkDevice, cacheHandle, pipelineInfoUI, null, pPipelineUI) == VK_SUCCESS) {
                    graphicsPipelineUI = pPipelineUI.get(0);
                    System.out.println("[VulkanPipeline] Created 2D UI Overlay VkPipeline [0x" + Long.toHexString(graphicsPipelineUI) + "].");
                }

                VulkanShaderLoader.destroyShaderModule(vkDevice, vertUIModule);
                VulkanShaderLoader.destroyShaderModule(vkDevice, fragUIModule);
            }
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

    public long getMinUniformBufferOffsetAlignment() { return minUniformBufferOffsetAlignment; }
    public long getDescriptorSetLayout3D() { return descriptorSetLayout3D; }
    public long getPipelineLayout3D() { return pipelineLayout3D; }
    public long getGraphicsPipeline3D() { return graphicsPipeline3D; }
    public long getGraphicsPipelineShadow() { return graphicsPipelineShadow != 0L ? graphicsPipelineShadow : graphicsPipeline3D; }
    public long getGraphicsPipelineSkinned() { return graphicsPipelineSkinned; }
    public long getGraphicsPipelineShadowDepth() { return graphicsPipelineShadowDepth; }
    public long getGraphicsPipelineShadowDepthSkinned() { return graphicsPipelineShadowDepthSkinned; }

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

        pipelineCacheManager.cleanup(vkDevice);

        if (graphicsPipeline3D != 0L) { vkDestroyPipeline(vkDevice, graphicsPipeline3D, null); graphicsPipeline3D = 0L; }
        if (graphicsPipelineShadow != 0L) { vkDestroyPipeline(vkDevice, graphicsPipelineShadow, null); graphicsPipelineShadow = 0L; }
        if (graphicsPipelineSkinned != 0L) { vkDestroyPipeline(vkDevice, graphicsPipelineSkinned, null); graphicsPipelineSkinned = 0L; }
        if (graphicsPipelineShadowDepth != 0L) { vkDestroyPipeline(vkDevice, graphicsPipelineShadowDepth, null); graphicsPipelineShadowDepth = 0L; }
        if (graphicsPipelineShadowDepthSkinned != 0L) { vkDestroyPipeline(vkDevice, graphicsPipelineShadowDepthSkinned, null); graphicsPipelineShadowDepthSkinned = 0L; }
        if (pipelineLayout3D != 0L) { vkDestroyPipelineLayout(vkDevice, pipelineLayout3D, null); pipelineLayout3D = 0L; }
        if (descriptorSetLayout3D != 0L) { vkDestroyDescriptorSetLayout(vkDevice, descriptorSetLayout3D, null); descriptorSetLayout3D = 0L; }

        if (graphicsPipelineGrid != 0L) { vkDestroyPipeline(vkDevice, graphicsPipelineGrid, null); graphicsPipelineGrid = 0L; }
        if (graphicsPipelineGridLines != 0L) { vkDestroyPipeline(vkDevice, graphicsPipelineGridLines, null); graphicsPipelineGridLines = 0L; }
        if (pipelineLayoutGrid != 0L) { vkDestroyPipelineLayout(vkDevice, pipelineLayoutGrid, null); pipelineLayoutGrid = 0L; }
        if (descriptorSetLayoutGrid != 0L) { vkDestroyDescriptorSetLayout(vkDevice, descriptorSetLayoutGrid, null); descriptorSetLayoutGrid = 0L; }

        if (graphicsPipelineUI != 0L) { vkDestroyPipeline(vkDevice, graphicsPipelineUI, null); graphicsPipelineUI = 0L; }
        if (pipelineLayoutUI != 0L) { vkDestroyPipelineLayout(vkDevice, pipelineLayoutUI, null); pipelineLayoutUI = 0L; }
        if (descriptorSetLayoutUI != 0L) { vkDestroyDescriptorSetLayout(vkDevice, descriptorSetLayoutUI, null); descriptorSetLayoutUI = 0L; }
    }
}
