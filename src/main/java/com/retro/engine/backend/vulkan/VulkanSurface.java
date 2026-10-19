package com.retro.engine.backend.vulkan;

import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VkInstance;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

/**
 * Handles creation and lifecycle management of the GLFW window surface (VkSurfaceKHR).
 */
public class VulkanSurface {

    private long surfaceHandle = 0L;

    public boolean createSurface(VkInstance instance, long windowHandle) {
        if (instance == null || windowHandle == 0L) return false;

        try (MemoryStack stack = stackPush()) {
            LongBuffer pSurface = stack.mallocLong(1);
            int err = GLFWVulkan.glfwCreateWindowSurface(instance, windowHandle, null, pSurface);
            if (err != VK_SUCCESS) {
                System.err.println("[VulkanSurface] ERROR: Failed to create GLFW Vulkan surface! Code: " + err);
                return false;
            }
            surfaceHandle = pSurface.get(0);
            System.out.println("[VulkanSurface] VkSurfaceKHR created successfully.");
            return true;
        }
    }

    public long getSurfaceHandle() { return surfaceHandle; }

    public void cleanup(VulkanDevice device) {
        if (device != null) {
            cleanup(device.getInstance());
        }
    }

    public void cleanup(VkInstance instance) {
        shutdown(instance);
    }

    public void shutdown(VkInstance instance) {
        if (surfaceHandle != 0L && instance != null) {
            KHRSurface.vkDestroySurfaceKHR(instance, surfaceHandle, null);
            surfaceHandle = 0L;
            System.out.println("[VulkanSurface] VkSurfaceKHR destroyed.");
        }
    }
}
