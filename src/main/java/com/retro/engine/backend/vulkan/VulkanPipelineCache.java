package com.retro.engine.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Single Responsibility: Manages disk-backed VkPipelineCache serialization and deserialization
 * (vulkan_pipeline_cache.bin) for instant Vulkan 1.3 shader pipeline compilation.
 */
public class VulkanPipelineCache {

    private static final String CACHE_FILE_NAME = "vulkan_pipeline_cache.bin";
    private long pipelineCache = 0L;

    public long getHandle() { return pipelineCache; }

    public boolean initialize(VkDevice vkDevice) {
        if (vkDevice == null) return false;

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
                    System.out.println("[VulkanPipelineCache] Loaded disk VkPipelineCache (" + cacheData.length + " bytes).");
                } catch (Exception e) {
                    System.err.println("[VulkanPipelineCache] Failed to read VkPipelineCache file, creating new cache: " + e.getMessage());
                }
            }

            LongBuffer pCache = stack.mallocLong(1);
            if (vkCreatePipelineCache(vkDevice, cacheCreateInfo, null, pCache) == VK_SUCCESS) {
                pipelineCache = pCache.get(0);
                return true;
            } else {
                System.err.println("[VulkanPipelineCache] ERROR: Failed to create VkPipelineCache.");
                return false;
            }
        } finally {
            if (pInitialData != null) {
                MemoryUtil.memFree(pInitialData);
            }
        }
    }

    public void saveCache(VkDevice vkDevice) {
        if (vkDevice == null || pipelineCache == 0L) return;

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
                            System.out.println("[VulkanPipelineCache] Saved disk VkPipelineCache (" + size + " bytes).");
                        } catch (Exception e) {
                            System.err.println("[VulkanPipelineCache] Failed to save VkPipelineCache to disk: " + e.getMessage());
                        }
                    }
                    MemoryUtil.memFree(buffer);
                }
            }
        }
    }

    public void cleanup(VkDevice vkDevice) {
        if (vkDevice == null || pipelineCache == 0L) return;

        saveCache(vkDevice);
        vkDestroyPipelineCache(vkDevice, pipelineCache, null);
        pipelineCache = 0L;
    }
}
