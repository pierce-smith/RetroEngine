package com.retro.engine.core;

import com.retro.engine.audio.AudioBus;
import com.retro.engine.graphics.RenderEnvironment;
import com.retro.engine.graphics.ShadingModel;
import com.retro.engine.graphics.TextureFiltering;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * Per-project settings, stored as the settings section of the project file ({@link ProjectFile}): project identity, the startup scene, the
 * audio mixer (bus volumes / mutes, BGM ducking, reverb zones) read by {@link com.retro.engine.audio.AudioMixerService},
 * and the global render environment (shading model, ground shadow blobs, distance fog, ambient light) that
 * {@link com.retro.engine.graphics.SceneRenderPipeline} hands to the lit shaders every frame.
 *
 * <p>Section layout (big-endian, {@link DataOutputStream}), unchanged from the pre-.rproj ProjectSettings.bin:
 * <pre>
 *   int    magic 'RPRJ'
 *   int    version
 *   UTF    projectName, engineVersion
 *   long   createdAtMillis, lastOpenedAtMillis
 *   UTF    startupScene
 *   -- version 2 --
 *   bool   shadowBlobsEnabled
 *   bool   fogEnabled
 *   float  fogStart, fogEnd, fogDensity
 *   float  fogColor r, g, b
 *   float  ambientColor r, g, b
 *   float  ambientIntensity
 *   -- version 3 --
 *   UTF    shadingModel ({@link ShadingModel#name()}; unknown names load as the default)
 *   -- version 4 --
 *   int    busCount, then per bus (AudioBus ordinal order): float volume, bool muted
 *   bool   duckingEnabled
 *   float  duckLevel, duckAttack, duckRelease
 *   bool   reverbZonesEnabled
 *   -- version 5 --
 *   int    maxHeapMb
 *   -- version 6 --
 *   UTF    textureFiltering
 *   -- version 7 --
 *   bool   vsync
 *   int    frameRateCap (0 = no cap)
 * </pre>
 * Older files load the missing fields with the render defaults, which match the engine's previous look.
 */
public final class ProjectSettings {

    static final int MAGIC = 0x5250524A; // 'RPRJ'
    static final int VERSION = 7;

    // Editor UI ranges; values read from disk are clamped to these too
    public static final float FOG_DISTANCE_MAX = 2000.0f;
    public static final float AMBIENT_INTENSITY_MAX = 4.0f;
    public static final float DUCK_ATTACK_MAX = 2.0f;
    public static final float DUCK_RELEASE_MAX = 5.0f;

    /** Bounds and default for the exported game's Java heap; -Xms is 256 MB, so the floor stays above it. */
    public static final int MIN_MAX_HEAP_MB = 512;
    public static final int MAX_MAX_HEAP_MB = 32768;
    public static final int DEFAULT_MAX_HEAP_MB = 2048;

    /** Exported game frame cap: 0 means none, otherwise clamped to this range. */
    public static final int MIN_FRAME_RATE_CAP = 30;
    public static final int MAX_FRAME_RATE_CAP = 1000;

    public static final float DEFAULT_DUCK_LEVEL = 0.35f;
    public static final float DEFAULT_DUCK_ATTACK = 0.15f;
    public static final float DEFAULT_DUCK_RELEASE = 0.6f;

    public String projectName;
    public String engineVersion;
    public long createdAtMillis;
    public long lastOpenedAtMillis;
    /** Project-relative scene loaded when the project opens; empty for none. */
    public String startupScene;

    // --- Global render environment ---

    /** Per-vertex (Gouraud) or per-pixel (Blinn-Phong) lighting for every lit entity. Never null after {@link #sanitize()}. */
    public ShadingModel shadingModel = RenderEnvironment.DEFAULT_SHADING_MODEL;
    public TextureFiltering textureFiltering = RenderEnvironment.DEFAULT_TEXTURE_FILTERING;

    /** Dynamic downward-raycast ground shadow blobs under every draw. */
    public boolean shadowBlobsEnabled = RenderEnvironment.DEFAULT_SHADOW_BLOBS;

    public boolean fogEnabled = RenderEnvironment.DEFAULT_FOG_ENABLED;
    /** View distance (world units) where fog begins. */
    public float fogStart = RenderEnvironment.DEFAULT_FOG_START;
    /** View distance (world units) where fog turns fully opaque; it is also the draw distance (entities past it are culled). */
    public float fogEnd = RenderEnvironment.DEFAULT_FOG_END;
    /** Fog thickness (0..1): 0 ramps linearly from start to end, 1 closes in to a near-solid wall well before the end. */
    public float fogDensity = RenderEnvironment.DEFAULT_FOG_DENSITY;
    /** Linear RGB, 0..1. */
    public final float[] fogColor = { RenderEnvironment.DEFAULT_FOG_R, RenderEnvironment.DEFAULT_FOG_G, RenderEnvironment.DEFAULT_FOG_B };

    /** Linear RGB, 0..1; multiplied by {@link #ambientIntensity}. */
    public final float[] ambientColor = { RenderEnvironment.DEFAULT_AMBIENT_R, RenderEnvironment.DEFAULT_AMBIENT_G, RenderEnvironment.DEFAULT_AMBIENT_B };
    public float ambientIntensity = RenderEnvironment.DEFAULT_AMBIENT_INTENSITY;

    // --- Audio mixer (read every frame by AudioMixerService) ---

    /** Per-bus volume 0..1, indexed by {@link AudioBus#ordinal()}. */
    public final float[] busVolume = { 1.0f, 1.0f, 1.0f, 1.0f };
    public final boolean[] busMuted = new boolean[AudioBus.COUNT];
    /** Lower BGM while VOICE or critical ("ducks BGM") clips play. */
    public boolean duckingEnabled = true;
    /** BGM multiplier while ducked (0..1). */
    public float duckLevel = DEFAULT_DUCK_LEVEL;
    /** Seconds to ramp BGM down / back up. */
    public float duckAttack = DEFAULT_DUCK_ATTACK;
    public float duckRelease = DEFAULT_DUCK_RELEASE;
    /** Master switch for AudioReverbZone EFX reverb. */
    public boolean reverbZonesEnabled = true;

    // --- Exported game runtime ---

    /** The exported game's -Xmx in MB. It's fixed at launch, so a change only reaches the next export. */
    public int maxHeapMb = DEFAULT_MAX_HEAP_MB;

    /** Ask the GPU to wait for the display's refresh. Drivers can override this, which the frame limiter covers. */
    public boolean vsync = true;

    /** Highest frame rate the game runs at, in FPS; 0 means no cap beyond vsync. */
    public int frameRateCap = 0;

    public ProjectSettings(String projectName, String startupScene) {
        this.projectName = projectName;
        this.engineVersion = ProjectWorkspace.ENGINE_VERSION;
        this.createdAtMillis = System.currentTimeMillis();
        this.lastOpenedAtMillis = createdAtMillis;
        this.startupScene = (startupScene != null) ? startupScene : "";
    }

    /** Settings used while no project is bound (project launcher): defaults only. */
    public static ProjectSettings defaults() {
        return new ProjectSettings("", "");
    }

    // ------------------------------------------------------------------------------------------
    // Render environment
    // ------------------------------------------------------------------------------------------

    public ShadingModel getShadingModel() {
        return shadingModel;
    }

    /** Takes effect on the next rendered frame; null restores the default. */
    public void setShadingModel(ShadingModel model) {
        shadingModel = (model != null) ? model : RenderEnvironment.DEFAULT_SHADING_MODEL;
    }

    /** Copies the render settings into {@code env}. Plain field copies: safe to call every frame. */
    public void applyTo(RenderEnvironment env) {
        env.shadingModel = (shadingModel != null) ? shadingModel : RenderEnvironment.DEFAULT_SHADING_MODEL;
        env.textureFiltering = (textureFiltering != null) ? textureFiltering : RenderEnvironment.DEFAULT_TEXTURE_FILTERING;
        env.shadowBlobsEnabled = shadowBlobsEnabled;
        env.fogEnabled = fogEnabled;
        env.fogStart = fogStart;
        env.fogEnd = fogEnd;
        env.fogDensity = fogDensity;
        env.fogR = fogColor[0]; env.fogG = fogColor[1]; env.fogB = fogColor[2];
        env.ambientR = ambientColor[0]; env.ambientG = ambientColor[1]; env.ambientB = ambientColor[2];
        env.ambientIntensity = ambientIntensity;
    }

    /** Copies only the render settings from {@code other}; identity and startup scene are left alone. */
    public void copyRenderSettingsFrom(ProjectSettings other) {
        shadingModel = other.shadingModel;
        textureFiltering = other.textureFiltering;
        shadowBlobsEnabled = other.shadowBlobsEnabled;
        fogEnabled = other.fogEnabled;
        fogStart = other.fogStart;
        fogEnd = other.fogEnd;
        fogDensity = other.fogDensity;
        System.arraycopy(other.fogColor, 0, fogColor, 0, 3);
        System.arraycopy(other.ambientColor, 0, ambientColor, 0, 3);
        ambientIntensity = other.ambientIntensity;
    }

    public void resetRenderSettings() {
        copyRenderSettingsFrom(defaults());
    }

    /** Copies only the audio mixer settings from {@code other}. */
    public void copyAudioSettingsFrom(ProjectSettings other) {
        System.arraycopy(other.busVolume, 0, busVolume, 0, AudioBus.COUNT);
        System.arraycopy(other.busMuted, 0, busMuted, 0, AudioBus.COUNT);
        duckingEnabled = other.duckingEnabled;
        duckLevel = other.duckLevel;
        duckAttack = other.duckAttack;
        duckRelease = other.duckRelease;
        reverbZonesEnabled = other.reverbZonesEnabled;
    }

    public void resetAudioSettings() {
        copyAudioSettingsFrom(defaults());
    }

    /** Copies only the exported game runtime settings from {@code other}. */
    public void copyRuntimeSettingsFrom(ProjectSettings other) {
        maxHeapMb = other.maxHeapMb;
        vsync = other.vsync;
        frameRateCap = other.frameRateCap;
    }

    public void resetRuntimeSettings() {
        copyRuntimeSettingsFrom(defaults());
    }

    /** Clamps every render setting into its valid range (also repairs NaN from a corrupt file). */
    public void sanitize() {
        if (shadingModel == null) shadingModel = RenderEnvironment.DEFAULT_SHADING_MODEL;
        if (textureFiltering == null) textureFiltering = RenderEnvironment.DEFAULT_TEXTURE_FILTERING;
        fogStart = clamp(fogStart, 0.0f, FOG_DISTANCE_MAX);
        fogEnd = clamp(fogEnd, fogStart + 0.01f, FOG_DISTANCE_MAX + 0.01f);
        fogDensity = clamp(fogDensity, 0.0f, 1.0f);
        ambientIntensity = clamp(ambientIntensity, 0.0f, AMBIENT_INTENSITY_MAX);
        for (int i = 0; i < 3; i++) {
            fogColor[i] = clamp(fogColor[i], 0.0f, 1.0f);
            ambientColor[i] = clamp(ambientColor[i], 0.0f, 1.0f);
        }
        for (int i = 0; i < AudioBus.COUNT; i++) busVolume[i] = clamp(busVolume[i], 0.0f, 1.0f);
        duckLevel = clamp(duckLevel, 0.0f, 1.0f);
        duckAttack = clamp(duckAttack, 0.0f, DUCK_ATTACK_MAX);
        duckRelease = clamp(duckRelease, 0.0f, DUCK_RELEASE_MAX);
        maxHeapMb = Math.max(MIN_MAX_HEAP_MB, Math.min(MAX_MAX_HEAP_MB, maxHeapMb));
        frameRateCap = sanitizeFrameRateCap(frameRateCap);
    }

    /** 0 (or below) stays "no cap"; anything else is clamped to the supported range. */
    public static int sanitizeFrameRateCap(int fps) {
        if (fps <= 0) return 0;
        return Math.max(MIN_FRAME_RATE_CAP, Math.min(MAX_FRAME_RATE_CAP, fps));
    }

    private static float clamp(float v, float min, float max) {
        if (Float.isNaN(v)) return min;
        return Math.max(min, Math.min(max, v));
    }

    // ------------------------------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------------------------------

    /** Stores these settings as the project file's settings section (see {@link ProjectFile}), creating the file if needed. */
    public void write(File projectDir) throws IOException {
        ProjectFile.writeSection(projectDir, ProjectFile.SETTINGS, toPayload(projectDir), projectName);
    }

    /** The settings record: the layout documented on this class, which is also what ProjectSettings.bin held. */
    byte[] toPayload(File projectDir) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(bytes)) {
            dos.writeInt(MAGIC);
            dos.writeInt(VERSION);
            dos.writeUTF(projectName != null ? projectName : projectDir.getName());
            dos.writeUTF(engineVersion != null ? engineVersion : ProjectWorkspace.ENGINE_VERSION);
            dos.writeLong(createdAtMillis);
            dos.writeLong(lastOpenedAtMillis);
            dos.writeUTF(startupScene != null ? startupScene : "");

            dos.writeBoolean(shadowBlobsEnabled);
            dos.writeBoolean(fogEnabled);
            dos.writeFloat(fogStart);
            dos.writeFloat(fogEnd);
            dos.writeFloat(fogDensity);
            for (int i = 0; i < 3; i++) dos.writeFloat(fogColor[i]);
            for (int i = 0; i < 3; i++) dos.writeFloat(ambientColor[i]);
            dos.writeFloat(ambientIntensity);

            dos.writeUTF((shadingModel != null ? shadingModel : RenderEnvironment.DEFAULT_SHADING_MODEL).name());

            dos.writeInt(AudioBus.COUNT);
            for (int i = 0; i < AudioBus.COUNT; i++) {
                dos.writeFloat(busVolume[i]);
                dos.writeBoolean(busMuted[i]);
            }
            dos.writeBoolean(duckingEnabled);
            dos.writeFloat(duckLevel);
            dos.writeFloat(duckAttack);
            dos.writeFloat(duckRelease);
            dos.writeBoolean(reverbZonesEnabled);

            dos.writeInt(maxHeapMb);

            dos.writeUTF((textureFiltering != null ? textureFiltering : RenderEnvironment.DEFAULT_TEXTURE_FILTERING).name());

            dos.writeBoolean(vsync);
            dos.writeInt(frameRateCap);
        }
        return bytes.toByteArray();
    }

    /** Reads the project's settings; an unmigrated project's ProjectSettings.bin is read in place, unchanged. */
    public static ProjectSettings read(File projectDir) throws IOException {
        byte[] payload = ProjectFile.readSection(projectDir, ProjectFile.SETTINGS);
        if (payload == null) throw new IOException("No RetroEngine project file (" + ProjectFile.EXTENSION + ") in " + projectDir);
        return readPayload(payload, projectDir.getName());
    }

    static ProjectSettings readPayload(byte[] payload, String source) throws IOException {
        try (DataInputStream dis = new DataInputStream(new java.io.ByteArrayInputStream(payload))) {
            if (dis.readInt() != MAGIC) throw new IOException("Not RetroEngine project settings: " + source);
            int version = dis.readInt();
            if (version > VERSION) throw new IOException("Project settings version " + version + " in " + source + " is newer than this editor supports");
            ProjectSettings s = new ProjectSettings(dis.readUTF(), "");
            s.engineVersion = dis.readUTF();
            s.createdAtMillis = dis.readLong();
            s.lastOpenedAtMillis = dis.readLong();
            s.startupScene = dis.readUTF();

            if (version >= 2) {
                s.shadowBlobsEnabled = dis.readBoolean();
                s.fogEnabled = dis.readBoolean();
                s.fogStart = dis.readFloat();
                s.fogEnd = dis.readFloat();
                s.fogDensity = dis.readFloat();
                for (int i = 0; i < 3; i++) s.fogColor[i] = dis.readFloat();
                for (int i = 0; i < 3; i++) s.ambientColor[i] = dis.readFloat();
                s.ambientIntensity = dis.readFloat();
            }
            if (version >= 3) {
                s.shadingModel = ShadingModel.fromName(dis.readUTF(), RenderEnvironment.DEFAULT_SHADING_MODEL);
            }
            if (version >= 4) {
                int buses = dis.readInt();
                if (buses < 0 || buses > 64) throw new IOException("Project settings in " + source + " have an invalid audio bus count: " + buses);
                for (int i = 0; i < buses; i++) {
                    float vol = dis.readFloat();
                    boolean muted = dis.readBoolean();
                    if (i < AudioBus.COUNT) {
                        s.busVolume[i] = vol;
                        s.busMuted[i] = muted;
                    }
                }
                s.duckingEnabled = dis.readBoolean();
                s.duckLevel = dis.readFloat();
                s.duckAttack = dis.readFloat();
                s.duckRelease = dis.readFloat();
                s.reverbZonesEnabled = dis.readBoolean();
            }
            if (version >= 5) {
                s.maxHeapMb = dis.readInt();
            }
            if (version >= 6) {
                s.textureFiltering = TextureFiltering.fromName(dis.readUTF(), RenderEnvironment.DEFAULT_TEXTURE_FILTERING);
            }
            if (version >= 7) {
                s.vsync = dis.readBoolean();
                s.frameRateCap = dis.readInt();
            }
            s.sanitize();
            return s;
        }
    }
}
