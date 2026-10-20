package com.retro.engine.backend.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Robust SPIR-V resource loader and VkShaderModule instantiation module.
 */
public class VulkanShaderLoader {

    /**
     * Reads a pre-compiled SPIR-V binary from classpath into a direct ByteBuffer and creates a VkShaderModule.
     *
     * @param device       Active VkDevice handle
     * @param resourcePath Path to .spv file in resources
     * @return Valid VkShaderModule long handle, or 0L on failure
     */
    public static long createShaderModule(VkDevice device, String resourcePath) {
        if (device == null || resourcePath == null) {
            System.err.println("[VulkanShaderLoader] ERROR: Null device or resource path.");
            return 0L;
        }

        ByteBuffer buffer = loadResourceToDirectBuffer(resourcePath);
        if (buffer == null) {
            System.err.println("[VulkanShaderLoader] ERROR: Could not load bytecode from " + resourcePath);
            return 0L;
        }

        try (MemoryStack stack = stackPush()) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(buffer);

            LongBuffer pShaderModule = stack.mallocLong(1);
            int result = vkCreateShaderModule(device, createInfo, null, pShaderModule);

            MemoryUtil.memFree(buffer);

            if (result != VK_SUCCESS) {
                System.err.println("[VulkanShaderLoader] ERROR: vkCreateShaderModule failed (" + result + ") for " + resourcePath);
                return 0L;
            }

            long handle = pShaderModule.get(0);
            System.out.println("[VulkanShaderLoader] Created VkShaderModule [0x" + Long.toHexString(handle) + "] for " + resourcePath);
            return handle;
        }
    }

    private static ByteBuffer loadResourceToDirectBuffer(String resourcePath) {
        try (InputStream is = VulkanShaderLoader.class.getResourceAsStream(resourcePath)) {
            InputStream stream = is;
            if (stream == null) {
                stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath);
            }
            if (stream == null) return null;

            byte[] bytes = stream.readAllBytes();
            ByteBuffer directBuffer = MemoryUtil.memAlloc(bytes.length);
            directBuffer.put(bytes);
            directBuffer.flip();
            return directBuffer;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void destroyShaderModule(VkDevice device, long shaderModule) {
        if (device != null && shaderModule != 0L) {
            vkDestroyShaderModule(device, shaderModule, null);
        }
    }
}
