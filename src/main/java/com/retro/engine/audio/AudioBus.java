package com.retro.engine.audio;

/**
 * Mixer bus a sound is routed through. Every bus feeds MASTER, so a clip's final gain is
 * {@code clipVolume * bus * master} (BGM is additionally scaled by the ducking envelope).
 *
 * Names are persisted (AudioSource.bus, ProjectSettings ordinals), so only append new buses.
 */
public enum AudioBus {
    MASTER, BGM, SFX, VOICE;

    private static final AudioBus[] VALUES = values();
    public static final int COUNT = VALUES.length;

    public static AudioBus fromOrdinal(int ordinal) {
        return (ordinal >= 0 && ordinal < COUNT) ? VALUES[ordinal] : SFX;
    }

    /** Case-insensitive name lookup without allocating; unknown names fall back to {@code fallback}. */
    public static AudioBus fromName(String name, AudioBus fallback) {
        if (name == null) return fallback;
        for (int i = 0; i < COUNT; i++) {
            if (VALUES[i].name().equalsIgnoreCase(name)) return VALUES[i];
        }
        return fallback;
    }
}
