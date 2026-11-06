package com.retro.engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class FrameTimingsTest {

    private static final long MS = 1_000_000L;

    /** Runs frames of the given lengths, each spending 1 ms in UPDATE and the rest in PACE. */
    private static void run(FrameTimings timings, long startNs, float... frameMs) {
        long now = startNs;
        for (float ms : frameMs) {
            timings.beginAt(now);
            timings.markAt(FrameTimings.UPDATE, now + MS);
            now += (long) (ms * MS);
            timings.markAt(FrameTimings.PACE, now);
        }
        timings.beginAt(now);
    }

    @Test
    void captureReportsMedianPercentileAndMax() {
        FrameTimings timings = new FrameTimings();
        timings.startCapture(1000);
        float[] frames = new float[100];
        java.util.Arrays.fill(frames, 10.0f);
        frames[50] = 30.0f;
        frames[60] = 50.0f;
        run(timings, 1L, frames);

        FrameTimings.Capture capture = timings.finishCapture();
        assertNotNull(capture);
        assertEquals(100, capture.frames());
        assertEquals(10.0f, capture.medianMs(), 0.001f);
        assertEquals(30.0f, capture.p99Ms(), 0.001f);
        assertEquals(50.0f, capture.maxMs(), 0.001f);
        assertEquals(1.0, capture.phaseAverageMs()[FrameTimings.UPDATE], 0.001);
    }

    @Test
    void captureStopsAtItsLimitWithoutFailing() {
        FrameTimings timings = new FrameTimings();
        timings.startCapture(3);
        run(timings, 1L, 10.0f, 10.0f, 10.0f, 10.0f, 10.0f);
        assertEquals(3, timings.finishCapture().frames());
    }

    @Test
    void noCaptureMeansNoResult() {
        assertNull(new FrameTimings().finishCapture());
    }

    @Test
    void liveFiguresFollowTheHistory() {
        FrameTimings timings = new FrameTimings();
        float[] frames = new float[120];
        java.util.Arrays.fill(frames, 16.0f);
        run(timings, 1L, frames);
        assertEquals(16.0f, timings.getMedianMs(), 0.001f);
        assertEquals(16.0f, timings.getAverageFrameMs(), 0.01f);
        assertEquals(1.0f, timings.getPhaseAverageMs(FrameTimings.UPDATE), 0.01f);
    }

    @Test
    void percentileHandlesEdges() {
        assertEquals(0.0f, FrameTimings.percentile(new float[0], 0, 0.5f));
        assertEquals(7.0f, FrameTimings.percentile(new float[] { 7.0f }, 1, 0.99f));
    }
}
