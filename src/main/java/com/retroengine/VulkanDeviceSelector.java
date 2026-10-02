package com.retroengine;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.IntBuffer;

public class VulkanDeviceSelector {

    public long pickPhysicalDevice(VkInstance instance) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer deviceCount = stack.ints(0);
            VK10.vkEnumeratePhysicalDevices(instance, deviceCount, null);

            if (deviceCount.get(0) == 0) {
                throw new RuntimeException("CRITICAL: Failed to find any graphics hardware with Vulkan support.");
            }

            PointerBuffer devices = stack.mallocPointer(deviceCount.get(0));
            VK10.vkEnumeratePhysicalDevices(instance, deviceCount, devices);

            System.out.println("--- Detecting Available GPU Hardware Nodes ---");
            long selectedDevice = 0;

            for (int i = 0; i < deviceCount.get(0); i++) {
                long deviceHandle = devices.get(i);
                VkPhysicalDevice physicalDevice = new VkPhysicalDevice(deviceHandle, instance);

                VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
                VK10.vkGetPhysicalDeviceProperties(physicalDevice, properties);

                String deviceName = properties.deviceNameString();
                int deviceType = properties.deviceType();
                System.out.println("Found GPU [" + i + "]: " + deviceName);

                if (deviceType == VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ||
                        deviceType == VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU) {
                    selectedDevice = deviceHandle;
                }
            }

            if (selectedDevice == 0) {
                System.out.println("Warning: Defaulting to first available rendering node.");
                selectedDevice = devices.get(0);
            }

            return selectedDevice;
        }
    }
}