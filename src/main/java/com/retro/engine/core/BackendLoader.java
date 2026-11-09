package com.retro.engine.core;

import com.retro.engine.graphics.IRenderBackend;
import com.retro.engine.graphics.ViewportSpec;

public class BackendLoader {

    private static final String VULKAN_CLASS = "com.retro.engine.graphics.vulkan.VulkanBackend";
    private static final String METAL_CLASS  = "com.retro.engine.graphics.metal.MetalBackend";

    private IRenderBackend activeBackend = null;

    public boolean loadTargetBackend() {
        return loadTargetBackend(null);
    }

    public boolean loadTargetBackend(String[] cliArgs) {
        boolean forceVulkan = isVulkanForced(cliArgs);
        String os = System.getProperty("os.name").toLowerCase();
        String targetClassName;

        if (forceVulkan) {
            Log.info("HostBoot", isMac(os) ? "--vulkan: selecting the Vulkan backend (MoltenVK)..." : "--vulkan: selecting the Vulkan backend...");
            targetClassName = VULKAN_CLASS;
        } else if (os.contains("mac") || os.contains("darwin")) {
            Log.info("HostBoot", "OS Detected: macOS. Selecting default Metal backend...");
        if (com.retro.engine.graphics.GpuDebug.isEnabled()) {
            Log.warn("GpuDebug", "--gpu-debug validates Vulkan, so add --vulkan. For Metal, launch with MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=1.");
        }
            targetClassName = METAL_CLASS;
        } else if (os.contains("win") || os.contains("nux") || os.contains("nix")) {
            Log.info("HostBoot", "OS Detected: Windows/Linux. Selecting Vulkan backend...");
            targetClassName = VULKAN_CLASS;
        } else {
            Log.error("HostBoot", "Unsupported OS: " + os);
            return false;
        }

        try {
            Log.info("HostBoot", "Instantiating RHI class: " + targetClassName);
            Class<?> backendClass = Class.forName(targetClassName);
            Object instance = backendClass.getDeclaredConstructor().newInstance();

            if (instance instanceof IRenderBackend backend) {
                this.activeBackend = backend;
                Log.info("HostBoot", "Loaded backend: " + backend.getDeviceName());
                return true;
            } else {
                Log.error("HostBoot", "Class does not implement IRenderBackend!");
                return false;
            }

        } catch (ClassNotFoundException e) {
            Log.error("HostBoot", "Backend class not found: " + targetClassName);
            e.printStackTrace();
            return false;
        } catch (Exception e) {
            Log.error("HostBoot", "Failed to instantiate backend!");
            e.printStackTrace();
            return false;
        }
    }

    public boolean initializeWithFallback(ViewportSpec windowInfo, String[] cliArgs) {
        boolean forceVulkan = isVulkanForced(cliArgs);
        String os = System.getProperty("os.name").toLowerCase();

        if (forceVulkan || (!os.contains("mac") && !os.contains("darwin"))) {
            if (forceVulkan) {
                Log.info("HostBoot", isMac(os) ? "--vulkan: selecting the Vulkan backend (MoltenVK)..." : "--vulkan: selecting the Vulkan backend...");
            } else {
                Log.info("HostBoot", "OS Detected: Windows/Linux. Selecting Vulkan backend...");
            }
            return tryInitializeBackend(VULKAN_CLASS, windowInfo);
        }

        // On macOS: Try default Metal backend first
        Log.info("HostBoot", "OS Detected: macOS. Selecting default Metal backend...");
        if (tryInitializeBackend(METAL_CLASS, windowInfo)) {
            return true;
        }

        Log.info("HostBoot", "Metal backend failed initialization. Attempting automatic fallback to Vulkan (MoltenVK)...");
        if (activeBackend != null) {
            try {
                activeBackend.shutdown();
            } catch (Exception ignored) {}
            activeBackend = null;
        }

        if (tryInitializeBackend(VULKAN_CLASS, windowInfo)) {
            Log.info("HostBoot", "Automatic fallback to Vulkan (MoltenVK) succeeded!");
            return true;
        }

        Log.error("HostBoot", "Both Metal and Vulkan backends failed to initialize!");
        return false;
    }

    private static boolean isMac(String os) {
        return os.contains("mac") || os.contains("darwin");
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
            Log.info("HostBoot", "Instantiating RHI class: " + className);
            Class<?> backendClass = Class.forName(className);
            Object instance = backendClass.getDeclaredConstructor().newInstance();

            if (instance instanceof IRenderBackend backend) {
                Log.info("HostBoot", "Initializing backend instance...");
                if (backend.initialize(windowInfo)) {
                    this.activeBackend = backend;
                    Log.info("HostBoot", "Successfully initialized backend: " + backend.getDeviceName());
                    return true;
                } else {
                    Log.error("HostBoot", "Backend initialization returned false for " + className);
                    try { backend.shutdown(); } catch (Exception ignored) {}
                    return false;
                }
            } else {
                Log.error("HostBoot", "Class does not implement IRenderBackend: " + className);
                return false;
            }
        } catch (ClassNotFoundException e) {
            Log.error("HostBoot", "Backend class not found: " + className);
            e.printStackTrace();
            return false;
        } catch (Exception e) {
            Log.error("HostBoot", "Failed to instantiate or initialize backend: " + className);
            e.printStackTrace();
            return false;
        }
    }

    public IRenderBackend getBackend() {
        return activeBackend;
    }
}
