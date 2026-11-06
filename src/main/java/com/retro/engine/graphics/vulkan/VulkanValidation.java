package com.retro.engine.graphics.vulkan;

import com.retro.engine.core.Log;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkLayerProperties;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashSet;
import java.util.Set;

/**
 * Receives validation layer messages for {@code --gpu-debug}. A problem in a draw repeats every frame, so each distinct
 * message is logged once and only counted after that. Distinct means distinct text: one message ID covers every instance
 * of a rule, and MoltenVK reports everything under ID 0, so an ID alone would hide all but the first of them.
 * {@link #destroy} logs the totals.
 */
final class VulkanValidation {

    static final String LAYER = "VK_LAYER_KHRONOS_validation";
    private static final String TAG = "GpuDebug";

    /** Stops a message with per-frame text, such as a changing handle, from filling the log. */
    private static final int MAX_LOGGED_MESSAGES = 200;

    // MoltenVK 1.4.2 tests only the format's blend capability, never blendEnable, so it reports every integer color
    // attachment. Only the Color-ID pick target is one, and its pipelines all set blendEnable to false.
    private static final String MOLTENVK_INTEGER_BLEND_MESSAGE =
            "VK_ERROR_FEATURE_NOT_PRESENT: Blending is enabled for attachment with format VK_FORMAT_R8G8B8A8_UINT";

    private final Set<String> seen = new HashSet<>();
    private int errors, warnings, ignored;
    private VkDebugUtilsMessengerCallbackEXT callback;
    private long messenger;

    static boolean isLayerAvailable(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        VK10.vkEnumerateInstanceLayerProperties(count, null);
        if (count.get(0) == 0) return false;
        try (VkLayerProperties.Buffer layers = VkLayerProperties.malloc(count.get(0))) {
            VK10.vkEnumerateInstanceLayerProperties(count, layers);
            for (int i = 0; i < layers.capacity(); i++) {
                if (LAYER.equals(layers.get(i).layerNameString())) return true;
            }
        }
        return false;
    }

    /** Also goes on VkInstanceCreateInfo's pNext, so problems during instance creation are reported. */
    VkDebugUtilsMessengerCreateInfoEXT createInfo(MemoryStack stack) {
        if (callback == null) callback = VkDebugUtilsMessengerCallbackEXT.create(this::onMessage);
        return VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                .sType$Default()
                .messageSeverity(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                        | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                .messageType(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                        | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                        | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                .pfnUserCallback(callback);
    }

    void attach(VkInstance instance) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer handle = stack.mallocLong(1);
            int result = EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance, createInfo(stack), null, handle);
            if (result == VK10.VK_SUCCESS) {
                messenger = handle.get(0);
                Log.info(TAG, "Validation layer active.");
            } else {
                Log.warn(TAG, "Could not create the debug messenger (VkResult " + result + ").");
            }
        }
    }

    private int onMessage(int severity, int types, long callbackData, long userData) {
        VkDebugUtilsMessengerCallbackDataEXT data = VkDebugUtilsMessengerCallbackDataEXT.create(callbackData);
        boolean error = (severity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0;
        String message = data.pMessageString();
        if (isKnownFalsePositive(message)) {
            synchronized (this) { ignored++; }
            return VK10.VK_FALSE;
        }
        boolean firstTime;
        synchronized (this) {
            if (error) errors++;
            else warnings++;
            firstTime = seen.size() < MAX_LOGGED_MESSAGES && seen.add(message);
        }
        if (firstTime) {
            if (error) Log.error(TAG, message);
            else Log.warn(TAG, message);
        }
        return VK10.VK_FALSE;
    }

    static boolean isKnownFalsePositive(String message) {
        return message != null && message.startsWith(MOLTENVK_INTEGER_BLEND_MESSAGE);
    }

    void destroy(VkInstance instance) {
        if (messenger != 0L) EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance, messenger, null);
        messenger = 0L;
        if (callback != null) callback.free();
        callback = null;
        synchronized (this) {
            Log.info(TAG, "Validation finished: " + errors + " error(s), " + warnings + " warning(s), " + seen.size() + " distinct, "
                    + ignored + " known MoltenVK false positive(s) ignored.");
        }
    }
}
