package com.retroengine;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

public class EngineShell {

    private long windowHandle = MemoryUtil.NULL;
    private final VulkanInstance vulkanGraphics = new VulkanInstance();
    private final String windowTitle = "Neon Miami 1980";

    public void run() {
        GLFWErrorCallback.createPrint(System.err).set();

        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("Failed to initialize system GLFW drivers.");
        }

        // Force raw window layout clear of old OpenGL configurations
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_FALSE);

        windowHandle = GLFW.glfwCreateWindow(1280, 720, windowTitle, MemoryUtil.NULL, MemoryUtil.NULL);
        if (windowHandle == MemoryUtil.NULL) {
            throw new RuntimeException("Failed to allocate OS native window container.");
        }

        // Apply our custom cross-platform image assets to the window framework
        applyApplicationIcon();

        System.out.println("System Base Activated Successfully.");

        if (!GLFWVulkan.glfwVulkanSupported()) {
            throw new IllegalStateException("Vulkan Core Driver Layer missing from host system hardware.");
        }

        vulkanGraphics.initialize();

        VulkanDeviceSelector selector = new VulkanDeviceSelector();
        selector.pickPhysicalDevice(vulkanGraphics.getHandle());

        // Pure Vulkan presentation listener loop
        while (!GLFW.glfwWindowShouldClose(windowHandle)) {
            GLFW.glfwPollEvents();
        }

        cleanup();
    }

    private void applyApplicationIcon() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // FIX: Uses ClassLoader to find icon.png inside the embedded JAR
            // instead of relying on local OS file path strings which break inside bundles.
            InputStream is = EngineShell.class.getClassLoader().getResourceAsStream("icon.png");
            if (is == null) {
                System.out.println("Note: icon.png not found in resources archive. Skipping step.");
                return;
            }

            byte[] streamBytes = is.readAllBytes();
            ByteBuffer nativeBuffer = MemoryUtil.memAlloc(streamBytes.length);
            nativeBuffer.put(streamBytes).flip();

            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);

            ByteBuffer decodedPixels = STBImage.stbi_load_from_memory(nativeBuffer, width, height, channels, 4);
            MemoryUtil.memFree(nativeBuffer);

            if (decodedPixels != null) {
                GLFWImage.Buffer iconBuffer = GLFWImage.malloc(1, stack);
                iconBuffer.get(0)
                        .width(width.get(0))
                        .height(height.get(0))
                        .pixels(decodedPixels);

                GLFW.glfwSetWindowIcon(windowHandle, iconBuffer);
                STBImage.stbi_image_free(decodedPixels);
                System.out.println("Custom window asset successfully mapped onto screen registries.");
            }
        } catch (Exception e) {
            System.err.println("Warning: Custom icon loader skipped due to allocation error: " + e.getMessage());
        }
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