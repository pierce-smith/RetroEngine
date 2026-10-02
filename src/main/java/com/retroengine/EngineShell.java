package com.retroengine;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryUtil;

public class EngineShell {

    private long windowHandle = MemoryUtil.NULL;
    private final VulkanInstance vulkanGraphics = new VulkanInstance();
    private final String windowTitle = "Neon Miami 1980";

    public void run() {
        GLFWErrorCallback.createPrint(System.err).set();

        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("Failed to initialize system GLFW drivers.");
        }

        // Force a raw window layout with zero underlying legacy OpenGL context
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);

        windowHandle = GLFW.glfwCreateWindow(1280, 720, windowTitle, MemoryUtil.NULL, MemoryUtil.NULL);
        if (windowHandle == MemoryUtil.NULL) {
            throw new RuntimeException("Failed to allocate OS native window container.");
        }

        System.out.println("System Base Activated Successfully.");

        if (!GLFWVulkan.glfwVulkanSupported()) {
            throw new IllegalStateException("Vulkan Core Driver Layer missing from host system hardware.");
        }

        // Fire up our validated Vulkan Instance pipeline
        vulkanGraphics.initialize();

        // Crawl and discover our physical hardware processors
        VulkanDeviceSelector selector = new VulkanDeviceSelector();
        selector.pickPhysicalDevice(vulkanGraphics.getHandle());

        // Pure, whisper-quiet Vulkan Window Execution Loop
        while (!GLFW.glfwWindowShouldClose(windowHandle)) {
            // Poll OS interrupts, mouse clicks, keys, and gamepad vectors smoothly
            GLFW.glfwPollEvents();
        }

        cleanup();
    }

    private void cleanup() {
        vulkanGraphics.destroy();
        if (windowHandle != MemoryUtil.NULL) {
            GLFW.glfwDestroyWindow(windowHandle);
        }
        GLFW.glfwTerminate();
        var callback = GLFW.glfwSetErrorCallback(null);
        if (callback != null) {
            callback.free();
        }
        System.out.println("Engine shell cleanly unmounted from physical memory.");
    }
}
