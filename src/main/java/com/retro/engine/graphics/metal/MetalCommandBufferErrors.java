package com.retro.engine.graphics.metal;

/**
 * Which failed Metal command buffers mean the GPU is lost. Kept apart from {@link MetalBackend}, whose class load
 * touches the Objective-C runtime, so it can be tested on any OS.
 */
final class MetalCommandBufferErrors {

    /** MTLCommandBufferStatusError. */
    static final long STATUS_ERROR = 5L;
    /** MTLCommandBufferError codes that mean the device itself is gone, not just one bad frame. */
    static final long ACCESS_REVOKED = 4L, DEVICE_REMOVED = 11L;
    /** Errored frames in a row (timeouts, page faults) after which the GPU is treated as lost rather than recovering. */
    static final int MAX_CONSECUTIVE_FAILED_FRAMES = 60;

    private MetalCommandBufferErrors() {}

    /**
     * Whether a failed command buffer means the device is gone: removed (eGPU unplugged), access revoked (macOS blocked the
     * process after repeated GPU faults), or {@link #MAX_CONSECUTIVE_FAILED_FRAMES} failures in a row. Returns the reason,
     * or null when the GPU should recover on its own (one timeout or page fault drops just that frame).
     */
    static String fatalReason(long code, int consecutiveFailures) {
        if (code == DEVICE_REMOVED) return "the GPU was removed (MTLCommandBufferErrorDeviceRemoved)";
        if (code == ACCESS_REVOKED) return "macOS revoked GPU access after repeated GPU faults (MTLCommandBufferErrorAccessRevoked)";
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILED_FRAMES) {
            return consecutiveFailures + " frames in a row failed on the GPU (last MTLCommandBufferError " + code + ")";
        }
        return null;
    }
}
