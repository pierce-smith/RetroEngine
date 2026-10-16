package com.retro.engine.core;

import com.retro.engine.backend.metal.MetalBackend;
import com.retro.engine.backend.vulkan.VulkanBackend;

import com.retro.engine.rhi.IRenderBackend;

public class BackendLoader {

    private static final String VULKAN_CLASS = "com.retro.engine.backend.vulkan.VulkanBackend";
    private static final String METAL_CLASS  = "com.retro.engine.backend.metal.MetalBackend";

    private IRenderBackend activeBackend = null;

    public boolean loadTargetBackend() {
        String os = System.getProperty("os.name").toLowerCase();
        String targetClassName;

        if (os.contains("mac") || os.contains("darwin")) {
            System.out.println("[HostBoot] OS Detected: macOS. Selecting Metal backend...");
            targetClassName = METAL_CLASS;
        } else if (os.contains("win") || os.contains("nux") || os.contains("nix")) {
            System.out.println("[HostBoot] OS Detected: Windows/Linux. Selecting Vulkan backend...");
            targetClassName = VULKAN_CLASS;
        } else {
            System.err.println("[HostBoot] ERROR: Unsupported OS: " + os);
            return false;
        }

        try {
            System.out.println("[HostBoot] Instantiating class: " + targetClassName);
            Class<?> backendClass = Class.forName(targetClassName);
            Object instance = backendClass.getDeclaredConstructor().newInstance();

            if (instance instanceof IRenderBackend backend) {
                this.activeBackend = backend;
                System.out.println("[HostBoot] Loaded backend: " + backend.getDeviceName());
                return true;
            } else {
                System.err.println("[HostBoot] ERROR: Class does not implement IRenderBackend!");
                return false;
            }

        } catch (ClassNotFoundException e) {
            System.err.println("[HostBoot] ERROR: Backend class not found: " + targetClassName);
            e.printStackTrace();
            return false;
        } catch (Exception e) {
            System.err.println("[HostBoot] ERROR: Failed to instantiate backend!");
            e.printStackTrace();
            return false;
        }
    }

    public IRenderBackend getBackend() {
        return activeBackend;
    }
}
