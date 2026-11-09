package com.retro.engine.ecs.components;

import com.retro.engine.api.Internal;
import com.retro.engine.api.PublicApi;
import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

import com.retro.engine.audio.AudioBus;
import com.retro.engine.audio.AudioEngine;
import com.retro.engine.audio.AudioMixerService;
import com.retro.engine.audio.AudioReverbSystem;

import static org.lwjgl.openal.AL10.*;

/**
 * 3D positional audio source backed by an OpenAL source. The clip is decoded once (.ogg via STBVorbis, .wav/.aiff/.au
 * via javax.sound), downmixed to mono so OpenAL can spatialize it, and shared through {@link AudioEngine}'s buffer
 * cache. SimulationSystem pushes the entity's world position each tick; OpenAL applies linear-clamped attenuation
 * between MIN DIST (full volume) and MAX DIST, scaled by ATTENUATION (rolloff), plus panning against the listener.
 * The final gain is VOLUME times the BUS gain from {@link AudioMixerService} (bus * master, BGM ducking included).
 *
 * Persisted fields are written positionally by ComponentBinaryCodec: append new ones last, gated with since /
 * prefabSince and a format version bump.
 */
@PublicApi
public class AudioSource extends Component {
    public enum PlaybackState { PLAYING, PAUSED, STOPPED }

    @InspectorProperty(order = 0, label = "AUDIO CLIP", type = PropertyType.ASSET_PICKER, assetType = "AUDIO")
    public String audioAssetPath = "";

    @InspectorProperty(order = 1, label = "VOLUME", min = 0.0f, max = 2.0f, step = 0.05f)
    public float volume = 1.0f;       // User-configured base volume (0.0f to 2.0f; above 1 amplifies)

    @InspectorProperty(order = 2, label = "PITCH", min = 0.1f, max = 3.0f, step = 0.1f)
    public float pitch = 1.0f;        // 0.5f to 2.0f

    @InspectorProperty(order = 3, label = "LOOP", type = PropertyType.TOGGLE)
    public boolean loop = false;

    @InspectorProperty(order = 4, label = "MIN DIST", min = 0.1f, max = 100.0f, step = 0.5f)
    public float minDistance = 1.0f;  // Distance at which sound is at full volume

    @InspectorProperty(order = 5, label = "MAX DIST", min = 1.0f, max = 500.0f, step = 1.0f)
    public float maxDistance = 25.0f; // Distance beyond which sound is completely silent

    @InspectorProperty(order = 6, label = "ATTENUATION", min = 0.0f, max = 10.0f, step = 0.1f)
    public float attenuation = 1.0f;  // Rolloff factor

    @InspectorProperty(order = 7, label = "STATE", hidden = true)
    public PlaybackState state = PlaybackState.STOPPED;

    // Scenes saved before v6 / prefabs before v5 lack this field and load with the default (true)
    @InspectorProperty(order = 8, label = "PLAY ON START", type = PropertyType.TOGGLE, since = 6, prefabSince = 5)
    public boolean playOnStart = true;

    // Scenes saved before v8 / prefabs before v7 load on the SFX bus without ducking
    @InspectorProperty(order = 9, label = "BUS", since = 8, prefabSince = 7)
    public AudioBus bus = AudioBus.SFX;

    /** Critical clip: ducks the BGM bus while it plays (VOICE-bus sources always duck). */
    @InspectorProperty(order = 10, label = "DUCKS BGM", type = PropertyType.TOGGLE, since = 8, prefabSince = 7)
    public boolean ducksBgm = false;

    /** Matches the inspector's VOLUME slider range. */
    public static final float MAX_VOLUME = 2.0f;

    // OpenAL handles (0 = not created); buffers are owned by AudioEngine's cache, sources by this component
    private transient int sourceHandle = 0;
    private transient int bufferHandle = 0;
    private transient String loadedPath = null;

    public AudioSource() {}

    public AudioSource(String audioAssetPath) {
        this.audioAssetPath = (audioAssetPath != null) ? audioAssetPath : "";
    }

    /** OpenAL buffer name for the current clip, or 0 when nothing is loaded. */
    @Internal
    public int getBufferHandle() {
        return bufferHandle;
    }

    /** OpenAL source name, or 0 before the first play. */
    @Internal
    public int getSourceHandle() {
        return sourceHandle;
    }

    public void play() {
        if (audioAssetPath == null || audioAssetPath.trim().isEmpty()) return;
        if (!ensureSource()) {
            System.err.println("[AudioSource] Failed to load audio clip: " + audioAssetPath);
            return;
        }
        applySourceParams();
        if (state != PlaybackState.PAUSED) alSourceRewind(sourceHandle);
        alSourcePlay(sourceHandle);
        this.state = PlaybackState.PLAYING;
    }

    public void pause() {
        if (sourceHandle != 0) alSourcePause(sourceHandle);
        this.state = PlaybackState.PAUSED;
    }

    public void stop() {
        if (sourceHandle != 0) alSourceStop(sourceHandle);
        this.state = PlaybackState.STOPPED;
    }

    @Internal
    public void dispose() {
        if (sourceHandle != 0 && AudioEngine.isAvailable()) {
            alSourceStop(sourceHandle);
            AudioReverbSystem.detach(sourceHandle);
            alSourcei(sourceHandle, AL_BUFFER, 0);
            alDeleteSources(sourceHandle);
        }
        sourceHandle = 0;
        bufferHandle = 0;
        loadedPath = null;
        this.state = PlaybackState.STOPPED;
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(MAX_VOLUME, volume));
        assert (!Float.isNaN(this.volume) && !Float.isInfinite(this.volume))
            : "[FATAL] Invalid volume assigned to AudioSource: " + volume;
        if (sourceHandle != 0) applySourceParams();
    }

    public void setPitch(float pitch) {
        this.pitch = Math.max(0.5f, Math.min(2.0f, pitch));
        assert (!Float.isNaN(this.pitch) && !Float.isInfinite(this.pitch))
            : "[FATAL] Invalid pitch assigned to AudioSource: " + pitch;
        if (sourceHandle != 0) alSourcef(sourceHandle, AL_PITCH, this.pitch);
    }

    public void setDistances(float minDistance, float maxDistance) {
        this.minDistance = Math.max(0.1f, minDistance);
        this.maxDistance = Math.max(this.minDistance, maxDistance);
        assert (this.maxDistance >= this.minDistance)
            : "[FATAL] AudioSource maxDistance (" + this.maxDistance + ") cannot be less than minDistance (" + this.minDistance + ")";
        if (sourceHandle != 0) applySourceParams();
    }

    /**
     * Per-tick sync: moves the source to the entity's world position, re-applies inspector/script edits
     * (volume, pitch, loop, distances) and moves state to STOPPED once a non-looping clip finishes.
     */
    @Internal
    public void updateSpatial(float worldX, float worldY, float worldZ) {
        if (sourceHandle == 0) return;
        if (!Float.isFinite(worldX + worldY + worldZ)) return;
        alSource3f(sourceHandle, AL_POSITION, worldX, worldY, worldZ);
        applySourceParams();
        if (state == PlaybackState.PLAYING) {
            if (alGetSourcei(sourceHandle, AL_SOURCE_STATE) == AL_STOPPED) {
                state = PlaybackState.STOPPED;
            } else {
                if (AudioMixerService.triggersDuck(bus, ducksBgm)) AudioMixerService.reportDuckTrigger();
                AudioReverbSystem.route(sourceHandle, true, worldX, worldY, worldZ);
            }
        }
    }

    private void applySourceParams() {
        float min = Math.max(0.1f, minDistance);
        alSourcef(sourceHandle, AL_MAX_GAIN, MAX_VOLUME); // OpenAL clamps source gain to 1.0 by default
        float mixed = Math.max(0.0f, Math.min(MAX_VOLUME, volume)) * AudioMixerService.busGain(bus);
        alSourcef(sourceHandle, AL_GAIN, Math.min(MAX_VOLUME, mixed));
        alSourcef(sourceHandle, AL_PITCH, Math.max(0.1f, pitch));
        alSourcei(sourceHandle, AL_LOOPING, loop ? AL_TRUE : AL_FALSE);
        alSourcef(sourceHandle, AL_REFERENCE_DISTANCE, min);
        alSourcef(sourceHandle, AL_MAX_DISTANCE, Math.max(min, maxDistance));
        alSourcef(sourceHandle, AL_ROLLOFF_FACTOR, Math.max(0.0f, attenuation));
    }

    /** Creates the AL source on demand and (re)binds the buffer when the clip path changed. */
    private boolean ensureSource() {
        if (!AudioEngine.ensureInitialized()) return false;
        if (sourceHandle == 0) {
            sourceHandle = alGenSources();
            if (alGetError() != AL_NO_ERROR) {
                sourceHandle = 0;
                return false;
            }
        }
        if (bufferHandle == 0 || !audioAssetPath.equals(loadedPath)) {
            alSourceStop(sourceHandle);
            bufferHandle = AudioEngine.getBuffer(audioAssetPath, true);
            loadedPath = (bufferHandle != 0) ? audioAssetPath : null;
            alSourcei(sourceHandle, AL_BUFFER, bufferHandle);
            if (state == PlaybackState.PAUSED) state = PlaybackState.STOPPED;
        }
        return bufferHandle != 0;
    }

    @Override
    public void onDeserialized(Entity owner) {
        // Route loaded values through the clamping setters
        setVolume(volume);
        setPitch(pitch);
        setDistances(minDistance, maxDistance);
        if (bus == null) bus = AudioBus.SFX;
    }

    @Override
    public String getComponentName() {
        return "Audio Source";
    }
}
