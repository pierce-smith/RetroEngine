package com.retro.engine.graphics.vulkan;

import com.retro.engine.graphics.ShaderSources;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Robust SPIR-V resource loader and VkShaderModule instantiation module.
 */
public class VulkanShaderLoader {

    /**
     * Reads a pre-compiled SPIR-V binary (editor shader source root first, then classpath) into a direct ByteBuffer and creates a VkShaderModule.
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
        long module = createShaderModule(device, resourcePath, ShaderSources.read(resourcePath));
        if (module == 0L && ShaderSources.isModOverridden(resourcePath)) {
            // Fallback registry: a broken mod shader never takes the pipeline down
            System.err.println("[VulkanShaderLoader] Mod shader override for " + resourcePath + " is invalid; using the built-in shader.");
            module = createShaderModule(device, resourcePath, ShaderSources.readBuiltin(resourcePath));
        }
        return module;
    }

    private static long createShaderModule(VkDevice device, String resourcePath, byte[] bytes) {
        ByteBuffer buffer = toDirectBuffer(bytes);
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

    private static final int SPIRV_MAGIC = 0x07230203;
    private static final int SPIRV_HEADER_BYTES = 20;

    private static ByteBuffer toDirectBuffer(byte[] bytes) {
        // SPIR-V is a stream of 32-bit words behind a 5-word header; anything else (a truncated or half-written file,
        // GLSL saved with a .spv name) would make vkCreateShaderModule undefined behaviour, so reject it here
        if (bytes == null || bytes.length < SPIRV_HEADER_BYTES || bytes.length % 4 != 0) return null;
        int magic = (bytes[0] & 0xFF) | (bytes[1] & 0xFF) << 8 | (bytes[2] & 0xFF) << 16 | (bytes[3] & 0xFF) << 24;
        if (magic != SPIRV_MAGIC) return null;
        ByteBuffer directBuffer = MemoryUtil.memAlloc(bytes.length);
        directBuffer.put(bytes);
        directBuffer.flip();
        return directBuffer;
    }

    public static void destroyShaderModule(VkDevice device, long shaderModule) {
        if (device != null && shaderModule != 0L) {
            vkDestroyShaderModule(device, shaderModule, null);
        }
    }
}
