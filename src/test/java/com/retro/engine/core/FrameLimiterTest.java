package com.retro.engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FrameLimiterTest {

    @Test
    void explicitCapWins() {
        assertEquals(1_000_000_000L / 30, FrameLimiter.intervalNs(true, 30, 144));
        assertEquals(1_000_000_000L / 30, FrameLimiter.intervalNs(false, 30, 144));
    }

    @Test
    void vsyncWithoutCapGetsABackstopJustAboveRefresh() {
        long interval = FrameLimiter.intervalNs(true, 0, 60);
        assertTrue(interval < 16_666_667L, "must not throttle a working 60 Hz vsync");
        assertTrue(interval > 15_000_000L, "must still stop a runaway loop near 60 FPS");
    }

    @Test
    void unknownRefreshAssumes60() {
        assertEquals(FrameLimiter.intervalNs(true, 0, 60), FrameLimiter.intervalNs(true, 0, 0));
    }

    @Test
    void vsyncOffWithoutCapIsUnlimited() {
        assertEquals(0L, FrameLimiter.intervalNs(false, 0, 60));
        assertFalse(FrameLimiter.create(false, 0, 60).isActive());
    }

    @Test
    void capHoldsAnEmptyLoopToTheTargetRate() {
        FrameLimiter limiter = FrameLimiter.create(false, 100, 60); // 10 ms
        limiter.waitForNextFrame();
        long start = System.nanoTime();
        for (int i = 0; i < 10; i++) limiter.waitForNextFrame();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs >= 95, "10 frames at 100 FPS took only " + elapsedMs + " ms");
        assertTrue(elapsedMs < 250, "10 frames at 100 FPS took " + elapsedMs + " ms");
    }
}
