package com.retro.engine.graphics;

import com.retro.engine.core.Log;

import org.lwjgl.system.Configuration;

import java.io.File;
import java.util.List;

/**
 * The {@code --gpu-debug} switch: turns on the Khronos validation layer for the Vulkan backend and logs what it reports.
 * The layer comes from the Vulkan SDK. Without one, the engine says so and runs normally.
 *
 * <p>macOS normally loads MoltenVK directly, which has no layer support, so this switches to the SDK's Vulkan loader.
 * Must run before anything touches Vulkan. Metal has its own validation, which is enabled by environment variables
 * before launch: {@code MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=1}.
 *
 * <p>{@code --simulate-gpu-loss N} makes either backend report a lost device after N frames, to check the
 * {@link GpuDeviceLostException} path (log, editor scene backup, dialog, exit code) without crashing a driver.</p>
 */
public final class GpuDebug {

    public static final String FLAG = "--gpu-debug";
    private static final String TAG = "GpuDebug";

    public static final String SIMULATE_LOSS_FLAG = "--simulate-gpu-loss";

    private static boolean enabled;
    private static long simulatedLossFrames = -1L;
    private static long framesBegun;

    private GpuDebug() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void configure(String[] args) {
        if (args == null) return;
        for (int i = 0; i < args.length; i++) {
            if (FLAG.equalsIgnoreCase(args[i])) enabled = true;
            if (SIMULATE_LOSS_FLAG.equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                try {
                    simulatedLossFrames = Math.max(0L, Long.parseLong(args[i + 1]));
                    Log.warn(TAG, "The GPU device will report lost after " + simulatedLossFrames + " frames (" + SIMULATE_LOSS_FLAG + ").");
                } catch (NumberFormatException e) {
                    Log.warn(TAG, SIMULATE_LOSS_FLAG + " needs a frame count, got " + args[i + 1]);
                }
            }
        }
        if (enabled && System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) useVulkanLoader();
    }

    /** Backends call this once per beginFrame; true from the frame {@code --simulate-gpu-loss} names onward. */
    public static boolean simulatedLossDue() {
        return simulatedLossFrames >= 0L && framesBegun++ >= simulatedLossFrames;
    }

    private static void useVulkanLoader() {
        String sdk = System.getenv("VULKAN_SDK");
        List<String> candidates = sdk != null
                ? List.of(sdk + "/lib/libvulkan.dylib", "/usr/local/lib/libvulkan.dylib", "/opt/homebrew/lib/libvulkan.dylib")
                : List.of("/usr/local/lib/libvulkan.dylib", "/opt/homebrew/lib/libvulkan.dylib");
        for (String path : candidates) {
            if (new File(path).isFile()) {
                Configuration.VULKAN_LIBRARY_NAME.set(path);
                Log.info(TAG, "Using the Vulkan loader at " + path);
                return;
            }
        }
        Log.warn(TAG, "No Vulkan loader found, so validation is unavailable. Install the Vulkan SDK.");
    }
}
