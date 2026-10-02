package com.retroengine;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

public class VulkanInstance {

    private VkInstance vkInstance;

    public void initialize() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Establish App Information Metadata
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("RetroEngineShell"))
                    .applicationVersion(VK10.VK_MAKE_VERSION(1, 0, 0))
                    .pEngineName(stack.UTF8("RetroCore"))
                    .engineVersion(VK10.VK_MAKE_VERSION(1, 0, 0))
                    .apiVersion(VK10.VK_API_VERSION_1_0); // Stable native baseline

            // 2. Query GLFW for the exact surface extensions your Mac requires
            PointerBuffer glfwExtensions = GLFWVulkan.glfwGetRequiredInstanceExtensions();
            if (glfwExtensions == null) {
                throw new RuntimeException("Vulkan is completely unavailable via your local desktop drivers.");
            }

            // 3. Create the Instance Definition Struct (Using crisp, standard windows layers)
            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(appInfo)
                    .ppEnabledExtensionNames(glfwExtensions); // GLFW routes native Cocoa/Metal surface tags automatically

            PointerBuffer pInstance = stack.mallocPointer(1);
            int result = VK10.vkCreateInstance(createInfo, null, pInstance);
            if (result != VK10.VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate global Vulkan Instance. Error: " + result);
            }

            vkInstance = new VkInstance(pInstance.get(0), createInfo);
            System.out.println("Vulkan Native Graphics Instance successfully bound to hardware registers.");
        }
    }

    public VkInstance getHandle() {
        return this.vkInstance;
    }

    public void destroy() {
        if (vkInstance != null) {
            VK10.vkDestroyInstance(vkInstance, null);
            System.out.println("Vulkan Graphics Instance cleanly unmounted.");
        }
    }
}