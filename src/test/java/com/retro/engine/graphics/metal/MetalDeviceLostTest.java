package com.retro.engine.graphics.metal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Which failed Metal command buffers end the game, and which just drop one frame. */
class MetalDeviceLostTest {

    private static final long TIMEOUT = 2L, PAGE_FAULT = 3L;

    @Test
    void removedGpuOrRevokedAccessIsFatalAtOnce() {
        assertNotNull(MetalCommandBufferErrors.fatalReason(MetalCommandBufferErrors.DEVICE_REMOVED, 1));
        assertNotNull(MetalCommandBufferErrors.fatalReason(MetalCommandBufferErrors.ACCESS_REVOKED, 1));
    }

    @Test
    void aSingleTimeoutOrPageFaultOnlyDropsThatFrame() {
        assertNull(MetalCommandBufferErrors.fatalReason(TIMEOUT, 1));
        assertNull(MetalCommandBufferErrors.fatalReason(PAGE_FAULT, MetalCommandBufferErrors.MAX_CONSECUTIVE_FAILED_FRAMES - 1));
    }

    @Test
    void aGpuThatKeepsFailingIsTreatedAsLost() {
        String reason = MetalCommandBufferErrors.fatalReason(TIMEOUT, MetalCommandBufferErrors.MAX_CONSECUTIVE_FAILED_FRAMES);
        assertNotNull(reason);
        assertTrue(reason.contains(MetalCommandBufferErrors.MAX_CONSECUTIVE_FAILED_FRAMES + " frames in a row"));
    }
}
