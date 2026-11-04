package com.retro.engine.audio;

import com.retro.engine.ecs.components.AudioReverbZone;
import com.retro.engine.ecs.components.AudioReverbZone.ReverbPreset;

import static org.lwjgl.openal.AL10.AL_NO_ERROR;
import static org.lwjgl.openal.AL10.alGetError;
import static org.lwjgl.openal.EXTEfx.*;

/**
 * Spatial reverb driven by {@link AudioReverbZone}s through one OpenAL EFX reverb effect and auxiliary slot.
 *
 * <p>Per frame: {@link #beginFrame} clears the zone list, the simulation {@link #addZone adds} every active zone at
 * its world pose, {@link #resolve} picks the zone around the listener (or, when the listener is outside every zone,
 * the zone that held an emitter last frame) and eases the wet / dry levels toward it, and {@link #route} attaches each
 * source's auxiliary send and dry filter. A source is routed when the listener is in the active zone or the source
 * itself is. Effect parameters are only re-uploaded when the zone or its settings change.
 *
 * <p>Without ALC_EXT_EFX (or with reverb disabled in Project Settings) everything is a no-op. Zone storage is
 * preallocated arrays that only grow when a scene exceeds the capacity: no allocation in steady state.
 */
public final class AudioReverbSystem {

    private static final float FADE_PER_SECOND = 4.0f;

    private static boolean efxChecked = false;
    private static boolean efxAvailable = false;
    private static int effect = 0;
    private static int slot = 0;
    private static int dryFilter = 0;

    // Zones gathered this frame
    private static AudioReverbZone[] zones = new AudioReverbZone[8];
    private static float[] zoneData = new float[8 * 6]; // cx, cy, cz, sx, sy, sz
    private static int zoneCount = 0;

    // Resolved state
    private static int activeIndex = -1;
    private static boolean listenerInActive = false;
    private static AudioReverbZone emitterCandidate = null;
    private static AudioReverbZone nextEmitterCandidate = null;
    private static float currentWet = 0.0f;
    private static float currentDry = 1.0f;
    private static float lastDry = -1.0f;
    // The listener was routing every source last frame; keeps sends attached while the wet level fades out
    private static boolean listenerWasRouting = false;

    // Uploaded effect parameters (re-upload only on change)
    private static ReverbPreset uploadedPreset = null;
    private static float uploadedDecay = -1.0f;

    private AudioReverbSystem() {}

    /** Lazily creates the EFX objects once the AL context exists. */
    private static boolean ensureEfx() {
        if (efxChecked) return efxAvailable;
        if (!AudioEngine.isAvailable()) return false;
        efxChecked = true;
        try {
            if (!AudioEngine.isEfxPresent()) {
                System.out.println("[AudioReverb] ALC_EXT_EFX unavailable; reverb zones disabled.");
                return false;
            }
            alGetError();
            effect = alGenEffects();
            alEffecti(effect, AL_EFFECT_TYPE, AL_EFFECT_REVERB);
            slot = alGenAuxiliaryEffectSlots();
            alAuxiliaryEffectSlotf(slot, AL_EFFECTSLOT_GAIN, 0.0f);
            alAuxiliaryEffectSloti(slot, AL_EFFECTSLOT_AUXILIARY_SEND_AUTO, 1);
            dryFilter = alGenFilters();
            alFilteri(dryFilter, AL_FILTER_TYPE, AL_FILTER_LOWPASS);
            alFilterf(dryFilter, AL_LOWPASS_GAINHF, 1.0f);
            alFilterf(dryFilter, AL_LOWPASS_GAIN, 1.0f);
            efxAvailable = alGetError() == AL_NO_ERROR;
            if (!efxAvailable) {
                System.err.println("[AudioReverb] EFX object creation failed; reverb zones disabled.");
                releaseNative();
            }
        } catch (Throwable t) {
            System.err.println("[AudioReverb] EFX init failed: " + t.getMessage());
            efxAvailable = false;
        }
        return efxAvailable;
    }

    // -------------------------------------------------------------------------
    // Zone gathering
    // -------------------------------------------------------------------------

    public static void beginFrame() {
        zoneCount = 0;
    }

    /** Registers an active zone at its world centre and world scale for this frame. */
    public static void addZone(AudioReverbZone zone, float cx, float cy, float cz, float sx, float sy, float sz) {
        if (zone == null || !zone.active) return;
        if (zoneCount == zones.length) {
            AudioReverbZone[] grownZones = new AudioReverbZone[zones.length * 2];
            System.arraycopy(zones, 0, grownZones, 0, zoneCount);
            zones = grownZones;
            float[] grownData = new float[zoneData.length * 2];
            System.arraycopy(zoneData, 0, grownData, 0, zoneCount * 6);
            zoneData = grownData;
        }
        zones[zoneCount] = zone;
        int o = zoneCount * 6;
        zoneData[o] = cx; zoneData[o + 1] = cy; zoneData[o + 2] = cz;
        zoneData[o + 3] = sx; zoneData[o + 4] = sy; zoneData[o + 5] = sz;
        zoneCount++;
    }

    private static boolean zoneContains(int i, float x, float y, float z) {
        int o = i * 6;
        return zones[i].contains(x, y, z, zoneData[o], zoneData[o + 1], zoneData[o + 2], zoneData[o + 3], zoneData[o + 4], zoneData[o + 5]);
    }

    /** Index of the winning zone containing the point (priority, then smallest), or -1. */
    private static int zoneAt(float x, float y, float z) {
        int best = -1;
        float bestVolume = 0.0f;
        for (int i = 0; i < zoneCount; i++) {
            if (!zoneContains(i, x, y, z)) continue;
            int o = i * 6;
            float vol = zones[i].volume(zoneData[o + 3], zoneData[o + 4], zoneData[o + 5]);
            if (best < 0 || zones[i].priority > zones[best].priority
                    || (zones[i].priority == zones[best].priority && vol < bestVolume)) {
                best = i;
                bestVolume = vol;
            }
        }
        return best;
    }

    // -------------------------------------------------------------------------
    // Resolve + route
    // -------------------------------------------------------------------------

    /** Picks this frame's active zone from the listener position and eases wet / dry toward it. */
    public static void resolve(float deltaTime) {
        if (!ensureEfx()) return;
        emitterCandidate = nextEmitterCandidate;
        nextEmitterCandidate = null;

        if (!AudioMixerService.isReverbEnabled()) {
            activeIndex = -1;
            listenerInActive = false;
        } else {
            activeIndex = zoneAt(AudioEngine.getListenerX(), AudioEngine.getListenerY(), AudioEngine.getListenerZ());
            listenerInActive = activeIndex >= 0;
            if (activeIndex < 0 && emitterCandidate != null) {
                for (int i = 0; i < zoneCount; i++) {
                    if (zones[i] == emitterCandidate) { activeIndex = i; break; }
                }
            }
        }

        AudioReverbZone zone = (activeIndex >= 0) ? zones[activeIndex] : null;
        float targetWet = (zone != null) ? zone.wetLevel : 0.0f;
        float targetDry = (zone != null) ? zone.dryLevel : 1.0f;
        float step = (deltaTime > 0.0f) ? deltaTime * FADE_PER_SECOND : 1.0f;
        currentWet = approach(currentWet, targetWet, step);
        currentDry = approach(currentDry, targetDry, step);

        if (zone != null) uploadEffect(zone);
        alAuxiliaryEffectSlotf(slot, AL_EFFECTSLOT_GAIN, currentWet);
        if (currentDry != lastDry) {
            alFilterf(dryFilter, AL_LOWPASS_GAIN, currentDry);
            lastDry = currentDry;
        }
    }

    /**
     * Attaches or detaches the reverb send and dry filter on one source emitting at (x, y, z). Non-spatial
     * (listener-relative) sources pass {@code spatial = false} and are only routed when the listener is in a zone.
     */
    public static void route(int source, boolean spatial, float x, float y, float z) {
        if (source == 0 || !efxAvailable) return;
        boolean wet = false;
        if (AudioMixerService.isReverbEnabled() && zoneCount > 0) {
            if (spatial) {
                int emitterZone = zoneAt(x, y, z);
                if (emitterZone >= 0 && nextEmitterCandidate == null) nextEmitterCandidate = zones[emitterZone];
                wet = activeIndex >= 0 && (listenerInActive || emitterZone == activeIndex);
            } else {
                wet = activeIndex >= 0 && listenerInActive;
            }
        }
        // Keep the fade-out audible after leaving a zone
        if (!wet && activeIndex < 0 && currentWet > 0.001f && listenerWasRouting) wet = true;
        if (wet) {
            org.lwjgl.openal.AL11.alSource3i(source, AL_AUXILIARY_SEND_FILTER, slot, 0, AL_FILTER_NULL);
            org.lwjgl.openal.AL10.alSourcei(source, AL_DIRECT_FILTER, dryFilter); // Copies the filter's current gain
        } else {
            org.lwjgl.openal.AL11.alSource3i(source, AL_AUXILIARY_SEND_FILTER, AL_EFFECTSLOT_NULL, 0, AL_FILTER_NULL);
            org.lwjgl.openal.AL10.alSourcei(source, AL_DIRECT_FILTER, AL_FILTER_NULL);
        }
    }

    /** Called after every source has been routed this frame. */
    public static void endFrame() {
        if (!efxAvailable) return;
        if (activeIndex >= 0) listenerWasRouting = listenerInActive;
        else if (currentWet <= 0.001f) listenerWasRouting = false;
    }

    /** Detaches a source before it is deleted or reused for an unrelated clip. */
    public static void detach(int source) {
        if (source == 0 || !efxAvailable) return;
        org.lwjgl.openal.AL11.alSource3i(source, AL_AUXILIARY_SEND_FILTER, AL_EFFECTSLOT_NULL, 0, AL_FILTER_NULL);
        org.lwjgl.openal.AL10.alSourcei(source, AL_DIRECT_FILTER, AL_FILTER_NULL);
    }

    /** Drops zone references and silences the send (Play stopped / scene changed). */
    public static void reset() {
        for (int i = 0; i < zoneCount; i++) zones[i] = null;
        zoneCount = 0;
        activeIndex = -1;
        emitterCandidate = nextEmitterCandidate = null;
        listenerInActive = listenerWasRouting = false;
        currentWet = 0.0f;
        currentDry = 1.0f;
        if (efxAvailable) {
            alAuxiliaryEffectSlotf(slot, AL_EFFECTSLOT_GAIN, 0.0f);
            alFilterf(dryFilter, AL_LOWPASS_GAIN, 1.0f);
            lastDry = 1.0f;
        }
    }

    private static float approach(float v, float target, float step) {
        if (v < target) return Math.min(target, v + step);
        return Math.max(target, v - step);
    }

    // -------------------------------------------------------------------------
    // Presets
    // -------------------------------------------------------------------------

    // density, diffusion, gain, gainHF, decay, decayHFRatio, reflectionsGain, reflectionsDelay, lateGain, lateDelay
    // (standard EFX reverb values adapted from the EAX environment presets)
    private static final float[][] PRESETS = {
        /* CAVE    */ { 1.0000f, 1.0000f, 0.3162f, 1.0000f, 2.91f, 1.30f, 0.5000f, 0.015f, 0.7063f, 0.022f },
        /* ROOM    */ { 0.4287f, 1.0000f, 0.3162f, 0.5929f, 0.40f, 0.83f, 0.1503f, 0.002f, 1.0629f, 0.003f },
        /* HALL    */ { 1.0000f, 1.0000f, 0.3162f, 0.5623f, 3.92f, 0.70f, 0.2427f, 0.020f, 0.9977f, 0.029f },
        /* OUTDOOR */ { 0.4287f, 0.2100f, 0.3162f, 0.1000f, 1.49f, 0.50f, 0.0585f, 0.179f, 0.1089f, 0.100f },
    };

    private static void uploadEffect(AudioReverbZone zone) {
        ReverbPreset preset = (zone.reverbPreset != null) ? zone.reverbPreset : ReverbPreset.ROOM;
        float[] p = PRESETS[preset.ordinal()];
        float decay = (zone.decayTime > 0.0f) ? zone.decayTime : p[4];
        decay = Math.max(AL_REVERB_MIN_DECAY_TIME, Math.min(AL_REVERB_MAX_DECAY_TIME, decay));
        if (preset == uploadedPreset && decay == uploadedDecay) return;

        alEffectf(effect, AL_REVERB_DENSITY, p[0]);
        alEffectf(effect, AL_REVERB_DIFFUSION, p[1]);
        alEffectf(effect, AL_REVERB_GAIN, p[2]);
        alEffectf(effect, AL_REVERB_GAINHF, p[3]);
        alEffectf(effect, AL_REVERB_DECAY_TIME, decay);
        alEffectf(effect, AL_REVERB_DECAY_HFRATIO, p[5]);
        alEffectf(effect, AL_REVERB_REFLECTIONS_GAIN, p[6]);
        alEffectf(effect, AL_REVERB_REFLECTIONS_DELAY, p[7]);
        alEffectf(effect, AL_REVERB_LATE_REVERB_GAIN, p[8]);
        alEffectf(effect, AL_REVERB_LATE_REVERB_DELAY, p[9]);
        // The slot holds a copy of the effect: re-attach to apply the new parameters
        alAuxiliaryEffectSloti(slot, AL_EFFECTSLOT_EFFECT, effect);
        uploadedPreset = preset;
        uploadedDecay = decay;
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------

    /** Frees the EFX objects; call before the AL context is destroyed (sources must already be detached). */
    public static void shutdown() {
        reset();
        releaseNative();
        efxChecked = false;
        efxAvailable = false;
        uploadedPreset = null;
        uploadedDecay = -1.0f;
    }

    private static void releaseNative() {
        if (slot != 0) {
            alAuxiliaryEffectSloti(slot, AL_EFFECTSLOT_EFFECT, AL_EFFECT_NULL);
            alDeleteAuxiliaryEffectSlots(slot);
            slot = 0;
        }
        if (effect != 0) { alDeleteEffects(effect); effect = 0; }
        if (dryFilter != 0) { alDeleteFilters(dryFilter); dryFilter = 0; }
    }
}
