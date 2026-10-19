package com.retro.engine.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages Vulkan 1.3 Graphics Pipelines for 3D mesh geometry and 2D UI vector streams using Dynamic Rendering.
 */
public class VulkanPipeline {

    private long pipelineLayout3D = 0L;
    private long graphicsPipeline3D = 0L;

    private long pipelineLayoutUI = 0L;
    private long graphicsPipelineUI = 0L;

    public boolean initialize(VulkanDevice device, VulkanSwapchain swapchain) {
        if (device == null || device.getLogicalDevice() == null || swapchain == null) return false;

        VkDevice vkDevice = device.getLogicalDevice();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Pipeline Layout (Push Constants for MVP matrix & Lighting)
            VkPushConstantRange.Buffer pushConstantRanges = VkPushConstantRange.calloc(1, stack);
            pushConstantRanges.get(0)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                    .offset(0)
                    .size(128); // 128 bytes for matrices and light vectors

            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pPushConstantRanges(pushConstantRanges);

            LongBuffer pLayout = stack.mallocLong(1);
            if (vkCreatePipelineLayout(vkDevice, layoutInfo, null, pLayout) != VK_SUCCESS) {
                System.err.println("[VulkanPipeline] Failed to create pipeline layout!");
                return false;
            }
            pipelineLayout3D = pLayout.get(0);
            pipelineLayoutUI = pipelineLayout3D;

            System.out.println("[VulkanPipeline] Vulkan 1.3 Dynamic Rendering Pipeline Layout created.");
            return true;
        }
    }

    public long getPipelineLayout3D() { return pipelineLayout3D; }
    public long getGraphicsPipeline3D() { return graphicsPipeline3D; }

    public void cleanup(VulkanDevice device) {
        shutdown(device);
    }

    public void shutdown(VulkanDevice device) {
        if (device == null || device.getLogicalDevice() == null) return;
        VkDevice vkDevice = device.getLogicalDevice();

        if (graphicsPipeline3D != 0L) { vkDestroyPipeline(vkDevice, graphicsPipeline3D, null); graphicsPipeline3D = 0L; }
        if (pipelineLayout3D != 0L) { vkDestroyPipelineLayout(vkDevice, pipelineLayout3D, null); pipelineLayout3D = 0L; }
    }
}
