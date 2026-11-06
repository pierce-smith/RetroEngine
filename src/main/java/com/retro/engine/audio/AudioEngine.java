package com.retro.engine.audio;

import com.retro.engine.modding.ModLoader;
import com.retro.engine.scripting.ScriptEngine;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.openal.AL10.*;

/**
 * Process-wide OpenAL device/context, decoded-buffer cache, listener state and a small pool of fire-and-forget
 * one-shot sources. Opens lazily on first use; when no audio device is available every call becomes a no-op.
 *
 * OpenAL only accepts raw PCM: .ogg is decoded with STBVorbis and .wav/.aiff/.au with javax.sound. OpenAL only
 * spatializes mono buffers, so spatial requests are downmixed to mono at load; non-spatial loads keep stereo.
 *
 * One-shots are routed through an {@link AudioBus}: {@link #updateOneShots(float)} re-applies each pooled source's
 * bus gain from {@link AudioMixerService}, reports ducking triggers and attaches reverb sends. The pool is fixed-size
 * primitive arrays, so the per-frame update allocates nothing.
 */
public final class AudioEngine {

    private static final int MAX_ONE_SHOTS = 32;

    private static long device = 0L;
    private static long context = 0L;
    private static boolean initialized = false;
    private static boolean initFailed = false;

    /** Cache key is path + "#mono" or "#native"; values are AL buffer names. */
    private static final Map<String, Integer> bufferCache = new HashMap<>();
    private static final Set<String> missingClips = new HashSet<>();

    // One-shot pool (parallel arrays indexed by pool slot)
    private static final int[] oneShotSources = new int[MAX_ONE_SHOTS];
    private static final long[] oneShotSequence = new long[MAX_ONE_SHOTS];
    private static final AudioBus[] oneShotBus = new AudioBus[MAX_ONE_SHOTS];
    private static final float[] oneShotVolume = new float[MAX_ONE_SHOTS];
    private static final boolean[] oneShotSpatial = new boolean[MAX_ONE_SHOTS];
    private static final boolean[] oneShotDucks = new boolean[MAX_ONE_SHOTS];
    private static final float[] oneShotPos = new float[MAX_ONE_SHOTS * 3];
    private static int oneShotCount = 0;
    private static long nextSequence = 1L;

    private static boolean efxPresent = false;
    private static float listenerX, listenerY, listenerZ;

    private AudioEngine() {}

    /** Opens the default device. Returns false (once logged) when audio is unavailable. */
    public static synchronized boolean ensureInitialized() {
        if (initialized) return true;
        if (initFailed) return false;
        try {
            device = ALC10.alcOpenDevice((ByteBuffer) null);
            if (device == 0L) throw new IllegalStateException("alcOpenDevice returned NULL");
            ALCCapabilities alcCaps = ALC.createCapabilities(device);
            context = ALC10.alcCreateContext(device, (IntBuffer) null);
            if (context == 0L) throw new IllegalStateException("alcCreateContext returned NULL");
            ALC10.alcMakeContextCurrent(context);
            AL.createCapabilities(alcCaps);
            efxPresent = alcCaps.ALC_EXT_EFX;
            // Full volume inside REFERENCE_DISTANCE, linear fade to silence at MAX_DISTANCE (rolloff 1)
            alDistanceModel(org.lwjgl.openal.AL11.AL_LINEAR_DISTANCE_CLAMPED);
            initialized = true;
            System.out.println("[AudioEngine] OpenAL initialized: " + alGetString(AL_RENDERER));
        } catch (Throwable t) {
            initFailed = true;
            System.err.println("[AudioEngine] Audio disabled, OpenAL init failed: " + t.getMessage());
            releaseNative();
        }
        return initialized;
    }

    public static boolean isAvailable() {
        return initialized;
    }

    /** True when the device exposes ALC_EXT_EFX (auxiliary effect slots, used by reverb zones). */
    public static boolean isEfxPresent() {
        return initialized && efxPresent;
    }

    public static float getListenerX() { return listenerX; }
    public static float getListenerY() { return listenerY; }
    public static float getListenerZ() { return listenerZ; }

    // -------------------------------------------------------------------------
    // Listener
    // -------------------------------------------------------------------------

    /** Positions the listener; forward/up need not be normalized. Called by the render pipeline every frame. */
    public static void updateListener(float px, float py, float pz,
                                      float fx, float fy, float fz,
                                      float ux, float uy, float uz) {
        if (!initialized) return;
        if (!Float.isFinite(px + py + pz + fx + fy + fz + ux + uy + uz)) return;
        alListener3f(AL_POSITION, px, py, pz);
        listenerX = px; listenerY = py; listenerZ = pz;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            alListenerfv(AL_ORIENTATION, stack.floats(fx, fy, fz, ux, uy, uz));
        }
    }

    // -------------------------------------------------------------------------
    // Buffers
    // -------------------------------------------------------------------------

    /** Returns a cached AL buffer for the file, decoding it on first request; 0 when it cannot be loaded. */
    public static synchronized int getBuffer(String path, boolean forceMono) {
        if (path == null || path.isBlank() || !ensureInitialized()) return 0;
        String file = resolveClipPath(path);
        String key = file + (forceMono ? "#mono" : "#native");
        Integer cached = bufferCache.get(key);
        if (cached != null) return cached;

        int buffer = decodeToBuffer(file, forceMono);
        if (buffer != 0) bufferCache.put(key, buffer);
        return buffer;
    }

    /**
     * File behind a clip path: mod overrides first, then the project's Assets/ folder, so scripts can write
     * {@code Assets/Audio/x.ogg} or {@code Audio/x.ogg} wherever the game runs from. Other paths come back unchanged.
     */
    public static String resolveClipPath(String path) {
        String resolved = ModLoader.resolveAssetPath(path);
        if (!resolved.equals(path) || new File(path).isAbsolute()) return resolved;
        String assetsRoot = ScriptEngine.getProjectAssetsRoot();
        if (assetsRoot == null) return path;
        String relative = path.trim().replace('\\', '/');
        if (relative.startsWith("Assets/")) relative = relative.substring("Assets/".length());
        File inProject = new File(assetsRoot, relative);
        return inProject.isFile() ? inProject.getAbsolutePath() : path;
    }

    private static int decodeToBuffer(String path, boolean forceMono) {
        if (!new File(path).isFile()) {
            // Once per clip: a script usually asks again every shot
            if (missingClips.add(path)) ScriptEngine.reportFailure("[AudioEngine] Audio file not found: " + path);
            return 0;
        }
        String lower = path.toLowerCase();
        ShortBuffer pcm = null;
        try {
            int channels;
            int sampleRate;
            if (lower.endsWith(".ogg")) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer ch = stack.mallocInt(1);
                    IntBuffer sr = stack.mallocInt(1);
                    pcm = STBVorbis.stb_vorbis_decode_filename(path, ch, sr);
                    if (pcm == null) {
                        System.err.println("[AudioEngine] STBVorbis failed to decode: " + path);
                        return 0;
                    }
                    channels = ch.get(0);
                    sampleRate = sr.get(0);
                }
            } else if (lower.endsWith(".wav") || lower.endsWith(".aiff") || lower.endsWith(".au")) {
                try (AudioInputStream src = AudioSystem.getAudioInputStream(new File(path))) {
                    AudioFormat in = src.getFormat();
                    AudioFormat target = new AudioFormat(in.getSampleRate(), 16, in.getChannels(), true, false);
                    try (AudioInputStream conv = AudioSystem.getAudioInputStream(target, src)) {
                        byte[] bytes = conv.readAllBytes();
                        pcm = MemoryUtil.memAllocShort(bytes.length / 2);
                        pcm.put(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()).flip();
                    }
                    channels = in.getChannels();
                    sampleRate = (int) in.getSampleRate();
                }
            } else {
                System.err.println("[AudioEngine] Unsupported audio format: " + path);
                return 0;
            }

            if (channels < 1 || channels > 2) {
                System.err.println("[AudioEngine] Unsupported channel count " + channels + ": " + path);
                return 0;
            }
            if (forceMono && channels == 2) {
                ShortBuffer mono = MemoryUtil.memAllocShort(pcm.remaining() / 2);
                while (pcm.remaining() >= 2) mono.put((short) ((pcm.get() + pcm.get()) / 2));
                mono.flip();
                MemoryUtil.memFree(pcm);
                pcm = mono;
                channels = 1;
            }

            int buffer = alGenBuffers();
            alBufferData(buffer, channels == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16, pcm, sampleRate);
            if (alGetError() != AL_NO_ERROR) {
                alDeleteBuffers(buffer);
                System.err.println("[AudioEngine] alBufferData failed: " + path);
                return 0;
            }
            return buffer;
        } catch (Exception e) {
            System.err.println("[AudioEngine] Failed to decode " + path + ": " + e.getMessage());
            return 0;
        } finally {
            if (pcm != null) MemoryUtil.memFree(pcm);
        }
    }

    // -------------------------------------------------------------------------
    // One-shots
    // -------------------------------------------------------------------------

    /**
     * Plays a clip once on a pooled source through the SFX bus. Spatial clips play mono at the world position;
     * non-spatial clips play listener-relative at the origin (no attenuation or panning). Returns false when the
     * clip could not play.
     */
    public static boolean playOneShot(String path, float volume, boolean spatial,
                                      float x, float y, float z,
                                      float minDistance, float maxDistance) {
        return playOneShot(path, volume, spatial, x, y, z, minDistance, maxDistance, AudioBus.SFX, false);
    }

    /**
     * Plays a clip once routed through {@code bus}. {@code ducksBgm} marks a critical clip that ducks the BGM bus
     * while it plays (VOICE clips always do).
     */
    public static synchronized boolean playOneShot(String path, float volume, boolean spatial,
                                                   float x, float y, float z,
                                                   float minDistance, float maxDistance,
                                                   AudioBus bus, boolean ducksBgm) {
        int buffer = getBuffer(path, spatial);
        if (buffer == 0) return false;
        int slotIndex = acquireOneShotSlot();
        if (slotIndex < 0) return false;
        int source = oneShotSources[slotIndex];
        AudioBus routed = (bus != null) ? bus : AudioBus.SFX;
        float base = Math.max(0.0f, Math.min(2.0f, volume));

        oneShotSequence[slotIndex] = nextSequence++;
        oneShotBus[slotIndex] = routed;
        oneShotVolume[slotIndex] = base;
        oneShotSpatial[slotIndex] = spatial;
        oneShotDucks[slotIndex] = ducksBgm;
        oneShotPos[slotIndex * 3] = x;
        oneShotPos[slotIndex * 3 + 1] = y;
        oneShotPos[slotIndex * 3 + 2] = z;

        alSourcei(source, AL_BUFFER, buffer);
        alSourcef(source, AL_MAX_GAIN, 2.0f); // OpenAL clamps source gain to 1.0 by default
        alSourcef(source, AL_GAIN, Math.min(2.0f, base * AudioMixerService.busGain(routed)));
        alSourcef(source, AL_PITCH, 1.0f);
        alSourcei(source, AL_LOOPING, AL_FALSE);
        if (spatial) {
            alSourcei(source, AL_SOURCE_RELATIVE, AL_FALSE);
            alSource3f(source, AL_POSITION, x, y, z);
            alSourcef(source, AL_REFERENCE_DISTANCE, Math.max(0.1f, minDistance));
            alSourcef(source, AL_MAX_DISTANCE, Math.max(minDistance, maxDistance));
            alSourcef(source, AL_ROLLOFF_FACTOR, 1.0f);
        } else {
            alSourcei(source, AL_SOURCE_RELATIVE, AL_TRUE);
            alSource3f(source, AL_POSITION, 0.0f, 0.0f, 0.0f);
            alSourcef(source, AL_ROLLOFF_FACTOR, 0.0f);
        }
        AudioReverbSystem.route(source, spatial, x, y, z);
        if (AudioMixerService.triggersDuck(routed, ducksBgm)) AudioMixerService.reportDuckTrigger();
        alSourcePlay(source);
        return true;
    }

    /**
     * Per-frame mix pass over the one-shot pool: re-applies bus gains (live volume / mute / ducking), reports duck
     * triggers from audible VOICE / critical clips and refreshes reverb routing. Indexed loop, no allocation.
     */
    public static synchronized void updateOneShots() {
        if (!initialized) return;
        for (int i = 0; i < oneShotCount; i++) {
            int s = oneShotSources[i];
            int st = alGetSourcei(s, AL_SOURCE_STATE);
            if (st != AL_PLAYING && st != AL_PAUSED) continue;
            AudioBus bus = oneShotBus[i];
            alSourcef(s, AL_GAIN, Math.min(2.0f, oneShotVolume[i] * AudioMixerService.busGain(bus)));
            if (st == AL_PLAYING && AudioMixerService.triggersDuck(bus, oneShotDucks[i])) AudioMixerService.reportDuckTrigger();
            AudioReverbSystem.route(s, oneShotSpatial[i], oneShotPos[i * 3], oneShotPos[i * 3 + 1], oneShotPos[i * 3 + 2]);
        }
    }

    /** Reuses a finished pooled source, grows the pool up to the cap, or steals the oldest. Returns the slot or -1. */
    private static int acquireOneShotSlot() {
        for (int i = 0; i < oneShotCount; i++) {
            int st = alGetSourcei(oneShotSources[i], AL_SOURCE_STATE);
            if (st == AL_STOPPED || st == AL_INITIAL) return i;
        }
        if (oneShotCount < MAX_ONE_SHOTS) {
            int s = alGenSources();
            if (alGetError() != AL_NO_ERROR) return -1;
            oneShotSources[oneShotCount] = s;
            return oneShotCount++;
        }
        int oldest = 0;
        for (int i = 1; i < oneShotCount; i++) {
            if (oneShotSequence[i] < oneShotSequence[oldest]) oldest = i;
        }
        alSourceStop(oneShotSources[oldest]);
        return oldest;
    }

    public static synchronized void stopAllOneShots() {
        if (!initialized) return;
        for (int i = 0; i < oneShotCount; i++) alSourceStop(oneShotSources[i]);
    }

    public static synchronized void setOneShotsPaused(boolean paused) {
        if (!initialized) return;
        for (int i = 0; i < oneShotCount; i++) {
            int s = oneShotSources[i];
            int st = alGetSourcei(s, AL_SOURCE_STATE);
            if (paused && st == AL_PLAYING) alSourcePause(s);
            else if (!paused && st == AL_PAUSED) alSourcePlay(s);
        }
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------

    /** Releases every pooled source and cached buffer, then the context and device. Component sources must be
     *  disposed first (OpenAL refuses to delete buffers still attached to a source). */
    public static synchronized void shutdown() {
        if (!initialized) return;
        for (int i = 0; i < oneShotCount; i++) {
            int s = oneShotSources[i];
            alSourceStop(s);
            AudioReverbSystem.detach(s);
            alDeleteSources(s);
            oneShotSources[i] = 0;
            oneShotBus[i] = null;
        }
        oneShotCount = 0;
        AudioReverbSystem.shutdown();
        for (int b : bufferCache.values()) alDeleteBuffers(b);
        bufferCache.clear();
        releaseNative();
        initialized = false;
        System.out.println("[AudioEngine] OpenAL shut down.");
    }

    private static void releaseNative() {
        if (context != 0L) {
            ALC10.alcMakeContextCurrent(0L);
            ALC10.alcDestroyContext(context);
            context = 0L;
        }
        if (device != 0L) {
            ALC10.alcCloseDevice(device);
            device = 0L;
        }
    }
}
