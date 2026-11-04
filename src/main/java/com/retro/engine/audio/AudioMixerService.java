package com.retro.engine.audio;

import com.retro.engine.core.ProjectSettings;

/**
 * Process-wide bus mixer: MASTER, BGM, SFX and VOICE gains, mutes and BGM ducking.
 *
 * <p>Two layers are multiplied together: the project mix (copied from {@link ProjectSettings} every frame, edited in
 * Project Settings > Audio) and a runtime mix that scripts drive through {@code setBusVolume} / {@code setBusMuted}.
 * The runtime layer resets to unity whenever Play stops, so a script cannot leave the editor muted.
 *
 * <p>Ducking: while any VOICE clip or a clip flagged as "ducks BGM" is audible, the BGM bus eases toward
 * {@code duckLevel} over {@code duckAttack} seconds, and back to full over {@code duckRelease} seconds once they stop.
 * Players report duck triggers during the frame ({@link #reportDuckTrigger()}); {@link #endFrame(float)} advances the
 * envelope. Everything here is primitive arrays and fields: no allocation per frame.
 */
public final class AudioMixerService {

    public static final float MAX_BUS_VOLUME = 1.0f;

    // Project mix (overwritten from ProjectSettings each frame)
    private static final float[] projectVolume = { 1.0f, 1.0f, 1.0f, 1.0f };
    private static final boolean[] projectMuted = new boolean[AudioBus.COUNT];
    private static boolean duckingEnabled = true;
    private static float duckLevel = 0.35f;
    private static float duckAttack = 0.15f;
    private static float duckRelease = 0.6f;
    private static boolean reverbEnabled = true;

    // Runtime (script) mix
    private static final float[] runtimeVolume = { 1.0f, 1.0f, 1.0f, 1.0f };
    private static final boolean[] runtimeMuted = new boolean[AudioBus.COUNT];

    // Ducking envelope
    private static float duckGain = 1.0f;
    private static boolean duckTriggeredThisFrame = false;

    private AudioMixerService() {}

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    /** Copies the project's audio mix. Plain field copies: safe to call every frame. */
    public static void applySettings(ProjectSettings s) {
        if (s == null) return;
        for (int i = 0; i < AudioBus.COUNT; i++) {
            projectVolume[i] = s.busVolume[i];
            projectMuted[i] = s.busMuted[i];
        }
        duckingEnabled = s.duckingEnabled;
        duckLevel = s.duckLevel;
        duckAttack = s.duckAttack;
        duckRelease = s.duckRelease;
        reverbEnabled = s.reverbZonesEnabled;
    }

    public static boolean isReverbEnabled() {
        return reverbEnabled;
    }

    // -------------------------------------------------------------------------
    // Runtime (script) controls
    // -------------------------------------------------------------------------

    /** Runtime bus volume 0..1, multiplied with the project mix. */
    public static void setBusVolume(AudioBus bus, float volume) {
        if (bus == null || Float.isNaN(volume)) return;
        runtimeVolume[bus.ordinal()] = Math.max(0.0f, Math.min(MAX_BUS_VOLUME, volume));
    }

    public static float getBusVolume(AudioBus bus) {
        return (bus != null) ? runtimeVolume[bus.ordinal()] : 1.0f;
    }

    public static void setBusMuted(AudioBus bus, boolean muted) {
        if (bus != null) runtimeMuted[bus.ordinal()] = muted;
    }

    public static boolean isBusMuted(AudioBus bus) {
        if (bus == null) return false;
        int i = bus.ordinal();
        return runtimeMuted[i] || projectMuted[i];
    }

    /** Restores the runtime layer to unity and releases any duck (called when Play stops). */
    public static void resetRuntime() {
        for (int i = 0; i < AudioBus.COUNT; i++) {
            runtimeVolume[i] = 1.0f;
            runtimeMuted[i] = false;
        }
        duckGain = 1.0f;
        duckTriggeredThisFrame = false;
    }

    // -------------------------------------------------------------------------
    // Gain evaluation
    // -------------------------------------------------------------------------

    /** Gain of one bus on its own (project * runtime, 0 when muted), excluding MASTER and ducking. */
    private static float rawBusGain(int i) {
        if (projectMuted[i] || runtimeMuted[i]) return 0.0f;
        return projectVolume[i] * runtimeVolume[i];
    }

    /** Final multiplier for a clip routed to {@code bus}: bus * master, with the duck envelope on BGM. */
    public static float busGain(AudioBus bus) {
        int master = AudioBus.MASTER.ordinal();
        float g = rawBusGain(master);
        if (bus == null || bus == AudioBus.MASTER) return g;
        g *= rawBusGain(bus.ordinal());
        if (bus == AudioBus.BGM) g *= duckGain;
        return g;
    }

    /** Current BGM duck multiplier (1 = not ducked). */
    public static float getDuckGain() {
        return duckGain;
    }

    // -------------------------------------------------------------------------
    // Ducking
    // -------------------------------------------------------------------------

    /** A VOICE clip or a "ducks BGM" clip is audible this frame. */
    public static void reportDuckTrigger() {
        duckTriggeredThisFrame = true;
    }

    /** True when a clip on {@code bus} with the given flag should duck BGM while it plays. */
    public static boolean triggersDuck(AudioBus bus, boolean ducksBgm) {
        return bus == AudioBus.VOICE || (ducksBgm && bus != AudioBus.BGM);
    }

    /** Advances the duck envelope from this frame's triggers, then clears them. */
    public static void endFrame(float deltaTime) {
        boolean ducking = duckingEnabled && duckTriggeredThisFrame;
        duckTriggeredThisFrame = false;
        float target = ducking ? Math.max(0.0f, Math.min(1.0f, duckLevel)) : 1.0f;
        float time = ducking ? duckAttack : duckRelease;
        if (!(deltaTime > 0.0f) || time <= 0.0001f) {
            duckGain = target;
            return;
        }
        // Linear ramp: full 0..1 swing takes `time` seconds
        float step = deltaTime / time;
        if (duckGain < target) duckGain = Math.min(target, duckGain + step);
        else duckGain = Math.max(target, duckGain - step);
    }
}
