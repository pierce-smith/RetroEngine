package com.retro.engine.graphics.vulkan;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.graphics.GpuDeviceLostException;
import com.retro.engine.graphics.GraphicsAPI;

import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;

/** How the Vulkan device records a loss; the frame loop and Main's handling need a real GPU to fail. */
class DeviceLostTest {

    @Test
    void otherErrorsDoNotMarkTheDeviceLost() {
        VulkanDevice device = new VulkanDevice();
        assertTrue(device.check(VK10.VK_SUCCESS, "test"));
        assertFalse(device.check(VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY, "test"));
        assertFalse(device.check(KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR, "test"));
        assertFalse(device.isLost());
        assertDoesNotThrow(device::throwIfLost);
    }

    @Test
    void deviceLostIsRaisedOnTheNextFrameWithTheFirstReason() {
        VulkanDevice device = new VulkanDevice();
        assertFalse(device.check(VK10.VK_ERROR_DEVICE_LOST, "vkQueueSubmit2 (frame)"));
        device.markLost("a later report");
        assertTrue(device.isLost());

        GpuDeviceLostException lost = assertThrows(GpuDeviceLostException.class, device::throwIfLost);
        assertEquals(GraphicsAPI.VULKAN, lost.getApi());
        assertEquals("vkQueueSubmit2 (frame) returned VK_ERROR_DEVICE_LOST", lost.getReason());
        assertThrows(GpuDeviceLostException.class, device::throwIfLost); // every later frame too
    }

    @Test
    void lossCanBeRecordedFromAnyThread() throws InterruptedException {
        VulkanDevice device = new VulkanDevice();
        Thread upload = new Thread(() -> device.check(VK10.VK_ERROR_DEVICE_LOST, "vkQueueSubmit2 (texture upload)"));
        upload.start();
        upload.join();
        assertThrows(GpuDeviceLostException.class, device::throwIfLost);
    }

    @Test
    void userMessageIsPlainAndNamesTheApp() {
        GpuDeviceLostException lost = new GpuDeviceLostException(GraphicsAPI.VULKAN, "the GPU did not finish the previous frame within 10 seconds");
        String message = lost.userMessage("Hollow Beacon");
        assertTrue(message.startsWith("The graphics card stopped responding, so Hollow Beacon has to close."));
        assertTrue(message.contains("Details: the GPU did not finish"));
        assertFalse(message.contains("\"") || message.contains("'"), "tinyfd strips quotes; keep them out of the text");
        assertTrue(lost.userMessage("").contains("so The program has to close"));
    }
}
