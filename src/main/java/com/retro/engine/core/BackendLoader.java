package com.retro.engine.core;

import com.retro.engine.rhi.IRenderBackend;

public class BackendLoader {

    private static final String VULKAN_CLASS = "com.retro.engine.backend.vulkan.VulkanBackend";
    private static final String METAL_CLASS  = "com.retro.engine.backend.metal.MetalBackend";

    private IRenderBackend activeBackend = null;

    public boolean loadTargetBackend() {
        return loadTargetBackend(null);
    }

    public boolean loadTargetBackend(String[] cliArgs) {
        boolean forceVulkan = false;

        // 1. Check CLI flags for explicit Vulkan override (--vulkan / -vulkan / vulkan)
        if (cliArgs != null) {
            for (String arg : cliArgs) {
                if (arg != null && ("--vulkan".equalsIgnoreCase(arg.trim()) || 
                                    "-vulkan".equalsIgnoreCase(arg.trim()) || 
                                    "vulkan".equalsIgnoreCase(arg.trim()))) {
                    forceVulkan = true;
                    break;
                }
            }
        }

        // 2. Fallback to JVM System Properties (-Dretro.vulkan=true or -Dretro.rhi=vulkan)
        if (!forceVulkan) {
            String propertyRhi = System.getProperty("retro.rhi", "").toLowerCase();
            String propertyVulkan = System.getProperty("retro.vulkan", "").toLowerCase();
            if ("vulkan".equals(propertyRhi) || "true".equals(propertyVulkan) || "1".equals(propertyVulkan)) {
                forceVulkan = true;
            }
        }

        // 3. Fallback to Environment Variable (RETRO_RHI=vulkan or RETRO_VULKAN=true)
        if (!forceVulkan) {
            String envRhi = System.getenv("RETRO_RHI");
            String envVulkan = System.getenv("RETRO_VULKAN");
            if ("vulkan".equalsIgnoreCase(envRhi) || "true".equalsIgnoreCase(envVulkan) || "1".equalsIgnoreCase(envVulkan)) {
                forceVulkan = true;
            }
        }

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

    public IRenderBackend getBackend() {
        return activeBackend;
    }
}
