package com.retro.engine.core;

import java.util.Arrays;

/**
 * CPU frame timing, split into the loop's phases. A loop calls {@link #begin()} at the top of each frame and
 * {@link #mark} after each phase. Frame time is the start-to-start interval, so it includes vsync and pacing waits, and
 * RENDER includes any wait on the GPU. Nothing here allocates after construction, except {@link #startCapture}.
 *
 * <p>One thread only: the main loop. {@link #MAIN} is the instance both loops feed.
 */
public final class FrameTimings {

    public static final int POLL = 0, UPDATE = 1, SUBMIT = 2, RENDER = 3, PRESENT = 4, PACE = 5, PHASE_COUNT = 6;
    private static final String[] PHASE_NAMES = { "POLL", "UPDATE", "SUBMIT", "RENDER", "PRESENT", "PACE" };

    public static final FrameTimings MAIN = new FrameTimings();

    private static final int HISTORY = 512;
    private static final int PERCENTILE_REFRESH_FRAMES = 30;
    private static final float SMOOTHING = 0.05f;

    private final float[] history = new float[HISTORY];
    private final float[] sortScratch = new float[HISTORY];
    private int historyCount, historyNext, framesUntilRefresh;
    private float medianMs, p99Ms, averageFrameMs;

    private final long[] phaseNs = new long[PHASE_COUNT];
    private final float[] phaseAverageMs = new float[PHASE_COUNT];
    private long frameStartNs, lastMarkNs;

    private float[] capture;
    private int captureCount;
    private final double[] capturePhaseMs = new double[PHASE_COUNT];

    public static String phaseName(int phase) {
        return PHASE_NAMES[phase];
    }

    public void begin() {
        beginAt(System.nanoTime());
    }

    public void mark(int phase) {
        markAt(phase, System.nanoTime());
    }

    /** Closes the previous frame and opens a new one at {@code nowNs}. */
    void beginAt(long nowNs) {
        if (frameStartNs != 0L) finishFrame((nowNs - frameStartNs) / 1_000_000.0f);
        frameStartNs = nowNs;
        lastMarkNs = nowNs;
    }

    /** Charges the time since the last mark to {@code phase}. */
    void markAt(int phase, long nowNs) {
        phaseNs[phase] += nowNs - lastMarkNs;
        lastMarkNs = nowNs;
    }

    private void finishFrame(float frameMs) {
        history[historyNext] = frameMs;
        historyNext = (historyNext + 1) % HISTORY;
        if (historyCount < HISTORY) historyCount++;

        boolean first = historyCount == 1;
        averageFrameMs = first ? frameMs : averageFrameMs + (frameMs - averageFrameMs) * SMOOTHING;
        for (int i = 0; i < PHASE_COUNT; i++) {
            float ms = phaseNs[i] / 1_000_000.0f;
            phaseAverageMs[i] = first ? ms : phaseAverageMs[i] + (ms - phaseAverageMs[i]) * SMOOTHING;
            if (capture != null) capturePhaseMs[i] += ms;
            phaseNs[i] = 0L;
        }
        if (capture != null && captureCount < capture.length) capture[captureCount++] = frameMs;

        if (--framesUntilRefresh <= 0) {
            framesUntilRefresh = PERCENTILE_REFRESH_FRAMES;
            System.arraycopy(history, 0, sortScratch, 0, historyCount);
            Arrays.sort(sortScratch, 0, historyCount);
            medianMs = percentile(sortScratch, historyCount, 0.5f);
            p99Ms = percentile(sortScratch, historyCount, 0.99f);
        }
    }

    /** Nearest-rank percentile of the first {@code count} values of the sorted array. */
    static float percentile(float[] sorted, int count, float fraction) {
        if (count == 0) return 0.0f;
        int rank = (int) Math.ceil(fraction * count);
        return sorted[Math.max(0, Math.min(count - 1, rank - 1))];
    }

    public float getAverageFrameMs() { return averageFrameMs; }
    public float getMedianMs() { return medianMs; }
    public float getP99Ms() { return p99Ms; }
    public float getPhaseAverageMs(int phase) { return phaseAverageMs[phase]; }

    /** Starts recording every frame, up to {@code maxFrames} of them, for {@link #finishCapture}. */
    public void startCapture(int maxFrames) {
        capture = new float[maxFrames];
        captureCount = 0;
        Arrays.fill(capturePhaseMs, 0.0);
    }

    public int getCapturedFrames() {
        return captureCount;
    }

    /** Stops recording and returns the summary, or null when no capture ran or it saw no frames. */
    public Capture finishCapture() {
        float[] frames = capture;
        capture = null;
        if (frames == null || captureCount == 0) return null;
        Arrays.sort(frames, 0, captureCount);
        double[] phaseAverages = new double[PHASE_COUNT];
        for (int i = 0; i < PHASE_COUNT; i++) phaseAverages[i] = capturePhaseMs[i] / captureCount;
        return new Capture(captureCount, percentile(frames, captureCount, 0.5f), percentile(frames, captureCount, 0.99f),
                frames[captureCount - 1], phaseAverages);
    }

    public record Capture(int frames, float medianMs, float p99Ms, float maxMs, double[] phaseAverageMs) {}
}
