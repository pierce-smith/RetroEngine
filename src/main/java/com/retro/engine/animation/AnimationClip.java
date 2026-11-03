package com.retro.engine.animation;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents a named skeletal animation sequence (e.g., "Idle", "Run", "Attack"),
 * mapping bone names to their respective keyframe tracks.
 */
public class AnimationClip {

    public enum LoopMode {
        ONCE,
        LOOP,
        PING_PONG
    }

    public String name;
    public float durationSeconds = 1.0f;
    public float ticksPerSecond = 25.0f;
    public LoopMode loopMode = LoopMode.LOOP;

    private final Map<String, KeyframeTrack> tracks = new HashMap<>();

    public AnimationClip(String name, float durationSeconds) {
        this.name = name;
        this.durationSeconds = Math.max(0.01f, durationSeconds);
    }

    public void addTrack(KeyframeTrack track) {
        if (track != null && track.boneName != null) {
            tracks.put(track.boneName, track);
        }
    }

    public KeyframeTrack getTrack(String boneName) {
        return tracks.get(boneName);
    }

    public Collection<KeyframeTrack> getTracks() {
        return tracks.values();
    }

    public float normalizeTime(float rawTime) {
        if (durationSeconds <= 0.0f) return 0.0f;

        switch (loopMode) {
            case ONCE:
                return Math.min(Math.max(rawTime, 0.0f), durationSeconds);

            case PING_PONG:
                float cycle = rawTime / durationSeconds;
                int count = (int) cycle;
                float rem = rawTime - (count * durationSeconds);
                return (count % 2 == 0) ? rem : (durationSeconds - rem);

            case LOOP:
            default:
                float mod = rawTime % durationSeconds;
                return (mod < 0.0f) ? (mod + durationSeconds) : mod;
        }
    }
}
