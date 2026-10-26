package com.retro.engine.core;

import com.retro.engine.rhi.IRenderBackend;
import com.retro.engine.rhi.ViewportSpec;

public class BackendLoader {

    private static final String VULKAN_CLASS = "com.retro.engine.backend.vulkan.VulkanBackend";
    private static final String METAL_CLASS  = "com.retro.engine.backend.metal.MetalBackend";

    private IRenderBackend activeBackend = null;

    public boolean loadTargetBackend() {
        return loadTargetBackend(null);
    }

    public boolean loadTargetBackend(String[] cliArgs) {
        boolean forceVulkan = isVulkanForced(cliArgs);
        String os = System.getProperty("os.name").toLowerCase();
        String targetClassName;

        if (forceVulkan) {
            System.out.println("[HostBoot] Driver Override Triggered: Selecting Vulkan backend (MoltenVK active on macOS)...");
            targetClassName = VULKAN_CLASS;
        } else if (os.contains("mac") || os.contains("darwin")) {
            System.out.println("[HostBoot] OS Detected: macOS. Selecting default Metal backend...");
            targetClassName = METAL_CLASS;
        } else if (os.contains("win") || os.contains("nux") || os.contains("nix")) {
            System.out.println("[HostBoot] OS Detected: Windows/Linux. Selecting Vulkan backend...");
            targetClassName = VULKAN_CLASS;
        } else {
            System.err.println("[HostBoot] ERROR: Unsupported OS: " + os);
            return false;
        }

        try {
            System.out.println("[HostBoot] Instantiating RHI class: " + targetClassName);
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

    public boolean initializeWithFallback(ViewportSpec windowInfo, String[] cliArgs) {
        boolean forceVulkan = isVulkanForced(cliArgs);
        String os = System.getProperty("os.name").toLowerCase();

        if (forceVulkan || (!os.contains("mac") && !os.contains("darwin"))) {
            if (forceVulkan) {
                System.out.println("[HostBoot] Driver Override Triggered: Selecting Vulkan backend (MoltenVK active on macOS)...");
            } else {
                System.out.println("[HostBoot] OS Detected: Windows/Linux. Selecting Vulkan backend...");
            }
            return tryInitializeBackend(VULKAN_CLASS, windowInfo);
        }

        // On macOS: Try default Metal backend first
        System.out.println("[HostBoot] OS Detected: macOS. Selecting default Metal backend...");
        if (tryInitializeBackend(METAL_CLASS, windowInfo)) {
            return true;
        }

        System.out.println("[HostBoot] Metal backend failed initialization. Attempting automatic fallback to Vulkan (MoltenVK)...");
        if (activeBackend != null) {
            try {
                activeBackend.shutdown();
            } catch (Exception ignored) {}
            activeBackend = null;
        }

        if (tryInitializeBackend(VULKAN_CLASS, windowInfo)) {
            System.out.println("[HostBoot] Automatic fallback to Vulkan (MoltenVK) succeeded!");
            return true;
        }

        System.err.println("[HostBoot] FATAL ERROR: Both Metal and Vulkan backends failed to initialize!");
        return false;
    }

    private boolean isVulkanForced(String[] cliArgs) {
        if (cliArgs != null) {
            for (String arg : cliArgs) {
                if (arg != null && ("--vulkan".equalsIgnoreCase(arg.trim()) || 
                                    "-vulkan".equalsIgnoreCase(arg.trim()) || 
                                    "vulkan".equalsIgnoreCase(arg.trim()))) {
                    return true;
                }
            }
        }

        String propertyRhi = System.getProperty("retro.rhi", "").toLowerCase();
        String propertyVulkan = System.getProperty("retro.vulkan", "").toLowerCase();
        if ("vulkan".equals(propertyRhi) || "true".equals(propertyVulkan) || "1".equals(propertyVulkan)) {
            return true;
        }

        String envRhi = System.getenv("RETRO_RHI");
        String envVulkan = System.getenv("RETRO_VULKAN");
        if ("vulkan".equalsIgnoreCase(envRhi) || "true".equalsIgnoreCase(envVulkan) || "1".equalsIgnoreCase(envVulkan)) {
            return true;
        }

        return false;
    }

    private boolean tryInitializeBackend(String className, ViewportSpec windowInfo) {
        try {
            System.out.println("[HostBoot] Instantiating RHI class: " + className);
            Class<?> backendClass = Class.forName(className);
            Object instance = backendClass.getDeclaredConstructor().newInstance();

            if (instance instanceof IRenderBackend backend) {
                System.out.println("[HostBoot] Initializing backend instance...");
                if (backend.initialize(windowInfo)) {
                    this.activeBackend = backend;
                    System.out.println("[HostBoot] Successfully initialized backend: " + backend.getDeviceName());
                    return true;
                } else {
                    System.err.println("[HostBoot] Backend initialization returned false for " + className);
                    try { backend.shutdown(); } catch (Exception ignored) {}
                    return false;
                }
            } else {
                System.err.println("[HostBoot] ERROR: Class does not implement IRenderBackend: " + className);
                return false;
            }
        } catch (ClassNotFoundException e) {
            System.err.println("[HostBoot] ERROR: Backend class not found: " + className);
            e.printStackTrace();
            return false;
        } catch (Exception e) {
            System.err.println("[HostBoot] ERROR: Failed to instantiate or initialize backend: " + className);
            e.printStackTrace();
            return false;
        }
    }

    public IRenderBackend getBackend() {
        return activeBackend;
    }
}
