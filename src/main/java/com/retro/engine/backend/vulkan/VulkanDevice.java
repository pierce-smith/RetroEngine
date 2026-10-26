package com.retro.engine.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.vulkan.*;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Handles Vulkan Instance creation, physical GPU selection, queue family discovery,
 * and logical VkDevice instantiation (Vulkan 1.3 dynamic rendering + synchronization2, Vulkan 1.2 timeline semaphores
 * and descriptor indexing for the bindless material texture table).
 * Isolated for cross-platform execution across macOS (MoltenVK) and Windows/Linux (Native Vulkan).
 */
public class VulkanDevice {

    private VkInstance instance;
    private VkPhysicalDevice physicalDevice;
    private VkDevice device;

    private VkQueue graphicsQueue;
    private VkQueue presentQueue;

    private int graphicsQueueFamilyIndex = -1;
    private int presentQueueFamilyIndex = -1;

    // VkQueue access must be externally synchronized: frame submission/present on the render thread
    // and asset uploads from AssetStreamer virtual threads all serialize on this lock.
    private final Object queueLock = new Object();

    // Vulkan 1.2 feature gating the depth-only VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL layout
    private boolean separateDepthStencilLayouts = false;

    public String getDriverName() {
        return (Platform.get() == Platform.MACOSX)
                ? "Modular Vulkan 1.3 Driver (MoltenVK / Apple Silicon)"
                : "Modular Vulkan 1.3 Driver (Native Desktop GPU)";
    }

    public boolean initialize(long windowHandle) {
        return createInstance();
    }

    public boolean createInstance() {
        try (MemoryStack stack = stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationName(stack.UTF8("Retro Engine"))
                    .applicationVersion(VK10.VK_MAKE_VERSION(1, 0, 0))
                    .pEngineName(stack.UTF8("Retro Engine Shell"))
                    .engineVersion(VK10.VK_MAKE_VERSION(1, 0, 0))
                    .apiVersion(VK13.VK_API_VERSION_1_3);

            PointerBuffer glfwExtensions = GLFWVulkan.glfwGetRequiredInstanceExtensions();
            if (glfwExtensions == null) {
                System.err.println("[VulkanDevice] ERROR: Failed to get required GLFW instance extensions!");
                return false;
            }

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationInfo(appInfo);

            if (Platform.get() == Platform.MACOSX) {
                Set<String> supportedExtensions = getSupportedInstanceExtensions(stack);
                List<String> requiredExtensions = new ArrayList<>();
                for (int i = 0; i < glfwExtensions.capacity(); i++) {
                    requiredExtensions.add(glfwExtensions.getStringUTF8(i));
                }

                boolean hasPortabilityEnum = supportedExtensions.contains(KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);
                if (hasPortabilityEnum) {
                    requiredExtensions.add(KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);
                }
                if (supportedExtensions.contains(KHRGetPhysicalDeviceProperties2.VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME)) {
                    requiredExtensions.add(KHRGetPhysicalDeviceProperties2.VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME);
                }

                PointerBuffer ppEnabledExtensions = stack.mallocPointer(requiredExtensions.size());
                for (String ext : requiredExtensions) {
                    ppEnabledExtensions.put(stack.UTF8(ext));
                }
                ppEnabledExtensions.flip();

                createInfo.ppEnabledExtensionNames(ppEnabledExtensions);

                if (hasPortabilityEnum) {
                    createInfo.flags(KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR);
                }
            } else {
                // Windows / Linux: bypass portability settings completely and pass required GLFW extensions directly
                createInfo.ppEnabledExtensionNames(glfwExtensions);
            }

            PointerBuffer pInstance = stack.mallocPointer(1);
            int err = VK10.vkCreateInstance(createInfo, null, pInstance);
            if (err != VK10.VK_SUCCESS) {
                System.err.println("[VulkanDevice] ERROR: vkCreateInstance failed with code: " + err);
                return false;
            }

            instance = new VkInstance(pInstance.get(0), createInfo);
            System.out.println("[VulkanDevice] " + getDriverName() + " VkInstance created successfully.");
            return true;
        }
    }

    private Set<String> getSupportedInstanceExtensions(MemoryStack stack) {
        Set<String> extensions = new HashSet<>();
        IntBuffer pCount = stack.mallocInt(1);
        VK10.vkEnumerateInstanceExtensionProperties((CharSequence) null, pCount, null);
        int count = pCount.get(0);
        if (count > 0) {
            // Allocate off-heap on native heap to prevent LWJGL MemoryStack overflow on Windows GPUs with 100+ extensions
            try (VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count)) {
                VK10.vkEnumerateInstanceExtensionProperties((CharSequence) null, pCount, props);
                for (int i = 0; i < props.capacity(); i++) {
                    extensions.add(props.get(i).extensionNameString());
                }
            }
        }
        return extensions;
    }

    public boolean selectPhysicalDeviceAndCreateLogicalDevice(long surfaceHandle) {
        return createLogicalDevice(surfaceHandle);
    }

    public boolean createLogicalDevice(long surfaceHandle) {
        try (MemoryStack stack = stackPush()) {
            if (!selectPhysicalDevice(stack, surfaceHandle)) {
                return false;
            }

            FloatBuffer queuePriorities = stack.floats(1.0f);
            Set<Integer> uniqueFamilies = new HashSet<>();
            uniqueFamilies.add(graphicsQueueFamilyIndex);
            uniqueFamilies.add(presentQueueFamilyIndex);

            VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(uniqueFamilies.size(), stack);
            int queueIdx = 0;
            for (int family : uniqueFamilies) {
                queueCreateInfos.get(queueIdx++)
                        .sType$Default()
                        .queueFamilyIndex(family)
                        .pQueuePriorities(queuePriorities);
            }

            Set<String> supportedDeviceExtensions = getSupportedDeviceExtensions(stack, physicalDevice);
            List<String> enabledDeviceExtensions = new ArrayList<>();
            enabledDeviceExtensions.add(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME);

            if (Platform.get() == Platform.MACOSX && supportedDeviceExtensions.contains("VK_KHR_portability_subset")) {
                enabledDeviceExtensions.add("VK_KHR_portability_subset");
            }

            PointerBuffer ppExtensions = stack.mallocPointer(enabledDeviceExtensions.size());
            for (String ext : enabledDeviceExtensions) {
                ppExtensions.put(stack.UTF8(ext));
            }
            ppExtensions.flip();

            VkPhysicalDeviceVulkan13Features supported13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default();
            VkPhysicalDeviceVulkan12Features supported12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                    .sType$Default()
                    .pNext(supported13.address());
            VkPhysicalDeviceFeatures2 supportedFeatures = VkPhysicalDeviceFeatures2.calloc(stack)
                    .sType$Default()
                    .pNext(supported12.address());
            VK11.vkGetPhysicalDeviceFeatures2(physicalDevice, supportedFeatures);
            separateDepthStencilLayouts = supported12.separateDepthStencilLayouts();

            // Bindless material table (descriptor indexing), sync2 barriers/submits and the timeline-semaphore upload
            // ring are hard requirements of the renderer; fail loudly instead of hitting undefined behaviour later.
            List<String> missing = new ArrayList<>();
            if (!supported12.runtimeDescriptorArray()) missing.add("runtimeDescriptorArray");
            if (!supported12.descriptorBindingPartiallyBound()) missing.add("descriptorBindingPartiallyBound");
            if (!supportedFeatures.features().shaderSampledImageArrayDynamicIndexing()) missing.add("shaderSampledImageArrayDynamicIndexing");
            if (!supported12.timelineSemaphore()) missing.add("timelineSemaphore");
            if (!supported13.synchronization2()) missing.add("synchronization2");
            if (!supported13.dynamicRendering()) missing.add("dynamicRendering");
            if (!missing.isEmpty()) {
                System.err.println("[VulkanDevice] ERROR: GPU lacks required Vulkan features: " + String.join(", ", missing)
                        + (Platform.get() == Platform.MACOSX ? " (MoltenVK needs Metal argument buffers enabled for descriptor indexing)" : ""));
                return false;
            }

            VkPhysicalDeviceVulkan12Features features12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                    .sType$Default()
                    .separateDepthStencilLayouts(separateDepthStencilLayouts)
                    .runtimeDescriptorArray(true)
                    .descriptorBindingPartiallyBound(true)
                    .timelineSemaphore(true);

            VkPhysicalDeviceVulkan13Features features13 = VkPhysicalDeviceVulkan13Features.calloc(stack)
                    .sType$Default()
                    .pNext(features12.address())
                    .dynamicRendering(true)
                    .synchronization2(true);

            VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack)
                    .sType$Default()
                    .pNext(features13.address());
            features2.features().shaderSampledImageArrayDynamicIndexing(true);

            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pNext(features2.address())
                    .pQueueCreateInfos(queueCreateInfos)
                    .ppEnabledExtensionNames(ppExtensions);

            PointerBuffer pDevice = stack.mallocPointer(1);
            int err = VK10.vkCreateDevice(physicalDevice, createInfo, null, pDevice);
            if (err != VK10.VK_SUCCESS) {
                System.err.println("[VulkanDevice] ERROR: vkCreateDevice failed with code: " + err);
                return false;
            }

            device = new VkDevice(pDevice.get(0), physicalDevice, createInfo);

            PointerBuffer pQueue = stack.mallocPointer(1);
            VK10.vkGetDeviceQueue(device, graphicsQueueFamilyIndex, 0, pQueue);
            graphicsQueue = new VkQueue(pQueue.get(0), device);

            VK10.vkGetDeviceQueue(device, presentQueueFamilyIndex, 0, pQueue);
            presentQueue = new VkQueue(pQueue.get(0), device);

            System.out.println("[VulkanDevice] " + getDriverName() + " Logical VkDevice & Queues created successfully.");
            return true;
        }
    }

    private boolean selectPhysicalDevice(MemoryStack stack, long surfaceHandle) {
        IntBuffer pCount = stack.mallocInt(1);
        VK10.vkEnumeratePhysicalDevices(instance, pCount, null);
        if (pCount.get(0) == 0) {
            System.err.println("[VulkanDevice] ERROR: No GPUs with Vulkan support found!");
            return false;
        }

        PointerBuffer pDevices = stack.mallocPointer(pCount.get(0));
        VK10.vkEnumeratePhysicalDevices(instance, pCount, pDevices);

        for (int i = 0; i < pDevices.capacity(); i++) {
            VkPhysicalDevice pd = new VkPhysicalDevice(pDevices.get(i), instance);
            if (findQueueFamilies(stack, pd, surfaceHandle)) {
                physicalDevice = pd;
                break;
            }
        }

        if (physicalDevice == null) {
            System.err.println("[VulkanDevice] ERROR: Failed to find a suitable GPU!");
            return false;
        }

        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
        VK10.vkGetPhysicalDeviceProperties(physicalDevice, props);
        System.out.println("[VulkanDevice] Selected GPU: " + props.deviceNameString());
        return true;
    }

    private boolean findQueueFamilies(MemoryStack stack, VkPhysicalDevice pd, long surfaceHandle) {
        IntBuffer pCount = stack.mallocInt(1);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(pd, pCount, null);
        if (pCount.get(0) == 0) return false;

        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(pCount.get(0), stack);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(pd, pCount, families);

        IntBuffer pPresent = stack.mallocInt(1);
        int gfx = -1;
        int present = -1;

        for (int i = 0; i < families.capacity(); i++) {
            if ((families.get(i).queueFlags() & VK10.VK_QUEUE_GRAPHICS_BIT) != 0) {
                gfx = i;
            }
            if (surfaceHandle != 0L) {
                KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR(pd, i, surfaceHandle, pPresent);
                if (pPresent.get(0) == VK10.VK_TRUE) {
                    present = i;
                }
            } else {
                present = gfx;
            }
            if (gfx >= 0 && present >= 0) break;
        }

        if (gfx >= 0 && present >= 0) {
            graphicsQueueFamilyIndex = gfx;
            presentQueueFamilyIndex = present;
            return true;
        }
        return false;
    }

    private Set<String> getSupportedDeviceExtensions(MemoryStack stack, VkPhysicalDevice pd) {
        Set<String> extensions = new HashSet<>();
        IntBuffer pCount = stack.mallocInt(1);
        VK10.vkEnumerateDeviceExtensionProperties(pd, (CharSequence) null, pCount, null);
        int count = pCount.get(0);
        if (count > 0) {
            // Allocate off-heap on native heap to prevent LWJGL MemoryStack overflow
            try (VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count)) {
                VK10.vkEnumerateDeviceExtensionProperties(pd, (CharSequence) null, pCount, props);
                for (int i = 0; i < props.capacity(); i++) {
                    extensions.add(props.get(i).extensionNameString());
                }
            }
        }
        return extensions;
    }

    public void cleanup() {
        shutdown();
    }

    public void shutdown() {
        if (device != null) {
            VK10.vkDestroyDevice(device, null);
            device = null;
        }
        if (instance != null) {
            VK10.vkDestroyInstance(instance, null);
            instance = null;
        }
        System.out.println("[VulkanDevice] VulkanDevice resources released.");
    }

    public VkInstance getInstance() { return instance; }
    public VkPhysicalDevice getPhysicalDevice() { return physicalDevice; }
    public VkDevice getDevice() { return device; }
    public VkDevice getLogicalDevice() { return device; }
    public VkQueue getGraphicsQueue() { return graphicsQueue; }
    public VkQueue getPresentQueue() { return presentQueue; }
    public int getGraphicsQueueFamilyIndex() { return graphicsQueueFamilyIndex; }
    public int getPresentQueueFamilyIndex() { return presentQueueFamilyIndex; }
    public Object getQueueLock() { return queueLock; }
    public boolean supportsSeparateDepthStencilLayouts() { return separateDepthStencilLayouts; }
}
