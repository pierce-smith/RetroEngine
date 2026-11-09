package com.retro.engine.graphics.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.vulkan.KHRSurface.*;

import org.junit.jupiter.api.Test;

class PresentModeTest {

    private static final int[] ALL = { VK_PRESENT_MODE_IMMEDIATE_KHR, VK_PRESENT_MODE_MAILBOX_KHR,
            VK_PRESENT_MODE_FIFO_KHR, VK_PRESENT_MODE_FIFO_RELAXED_KHR };

    @Test
    void vsyncAlwaysUsesFifo() {
        assertEquals(VK_PRESENT_MODE_FIFO_KHR, VulkanSwapchain.choosePresentMode(true, ALL));
    }

    @Test
    void vsyncOffPrefersMailbox() {
        assertEquals(VK_PRESENT_MODE_MAILBOX_KHR, VulkanSwapchain.choosePresentMode(false, ALL));
    }

    @Test
    void vsyncOffFallsBackToImmediateWithoutMailbox() {
        int[] modes = { VK_PRESENT_MODE_FIFO_KHR, VK_PRESENT_MODE_IMMEDIATE_KHR };
        assertEquals(VK_PRESENT_MODE_IMMEDIATE_KHR, VulkanSwapchain.choosePresentMode(false, modes));
    }

    @Test
    void fifoOnlySurfaceKeepsFifo() {
        assertEquals(VK_PRESENT_MODE_FIFO_KHR, VulkanSwapchain.choosePresentMode(false, new int[] { VK_PRESENT_MODE_FIFO_KHR }));
    }

    @Test
    void failedQueryKeepsFifo() {
        assertEquals(VK_PRESENT_MODE_FIFO_KHR, VulkanSwapchain.choosePresentMode(false, null));
    }
}
