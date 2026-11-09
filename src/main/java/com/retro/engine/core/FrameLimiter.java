package com.retro.engine.core;

import java.util.concurrent.locks.LockSupport;

/**
 * Caps the standalone game's frame rate on the CPU, independent of the GPU's present mode.
 *
 * <p>Vsync alone isn't a reliable cap: GPU control panels (common on Windows) can force it off, and then a game would
 * run flat out on one core. So when vsync is requested and no explicit cap is set, the limiter acts as a backstop a
 * little above the monitor's refresh rate. While vsync works, frames already take one refresh interval and the
 * limiter never waits. An explicit cap always applies.
 *
 * <p>Waiting parks for most of the remaining time, then spins the last stretch, because a park or sleep can overshoot
 * by a millisecond or more (much more on some Windows timer settings).
 */
public final class FrameLimiter {

    /** Backstop sits this far above the refresh rate, so normal vsync jitter never triggers a wait. */
    static final double BACKSTOP_HEADROOM = 1.05;
    /** Refresh rate assumed when the monitor doesn't report one. */
    static final int FALLBACK_REFRESH_HZ = 60;
    private static final long SPIN_THRESHOLD_NS = 1_500_000L;

    private final long intervalNs;
    private long lastFrameStartNs;

    private FrameLimiter(long intervalNs) {
        this.intervalNs = intervalNs;
    }

    /**
     * @param vsync        whether vsync was requested
     * @param frameRateCap explicit cap in FPS, or 0 for none
     * @param refreshHz    the monitor's refresh rate, or 0 if unknown
     */
    public static FrameLimiter create(boolean vsync, int frameRateCap, int refreshHz) {
        return new FrameLimiter(intervalNs(vsync, frameRateCap, refreshHz));
    }

    /** The minimum start-to-start frame time in nanoseconds, or 0 when frames are unlimited. */
    static long intervalNs(boolean vsync, int frameRateCap, int refreshHz) {
        if (frameRateCap > 0) return 1_000_000_000L / frameRateCap;
        if (!vsync) return 0L;
        int hz = refreshHz > 0 ? refreshHz : FALLBACK_REFRESH_HZ;
        return (long) (1_000_000_000.0 / (hz * BACKSTOP_HEADROOM));
    }

    public long getIntervalNs() { return intervalNs; }

    public boolean isActive() { return intervalNs > 0L; }

    /** Call once per frame, at the same point each time. Waits until at least one interval has passed since the last call. */
    public void waitForNextFrame() {
        if (intervalNs <= 0L) return;
        long now = System.nanoTime();
        if (lastFrameStartNs != 0L) {
            long deadline = lastFrameStartNs + intervalNs;
            long remaining = deadline - now;
            if (remaining > SPIN_THRESHOLD_NS) {
                LockSupport.parkNanos(remaining - SPIN_THRESHOLD_NS);
            }
            while ((now = System.nanoTime()) < deadline) {
                Thread.onSpinWait();
            }
            // A long frame resets the schedule instead of letting later frames rush to catch up
            if (now - deadline > intervalNs) deadline = now;
            lastFrameStartNs = deadline;
        } else {
            lastFrameStartNs = now;
        }
    }
}
