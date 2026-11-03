package com.retro.engine.core;

import com.retro.engine.graphics.RenderEnvironment;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Per-project settings persisted as {@code <Project>/ProjectSettings.bin}: project identity, the startup scene, and
 * the global render environment (ground shadow blobs, distance fog, ambient light) that
 * {@link com.retro.engine.graphics.SceneRenderPipeline} hands to the lit shaders every frame.
 *
 * <p>Binary layout (big-endian, {@link DataOutputStream}):
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
 * </pre>
 * Version 1 files load with the render defaults, which match the engine's previous hard-coded look.
 */
public final class ProjectSettings {

    public static final String FILE_NAME = "ProjectSettings.bin";

    static final int MAGIC = 0x5250524A; // 'RPRJ'
    static final int VERSION = 2;

    // Editor UI ranges; values read from disk are clamped to these too
    public static final float FOG_DISTANCE_MAX = 2000.0f;
    public static final float AMBIENT_INTENSITY_MAX = 4.0f;

    public String projectName;
    public String engineVersion;
    public long createdAtMillis;
    public long lastOpenedAtMillis;
    /** Project-relative scene loaded when the project opens; empty for none. */
    public String startupScene;

    // --- Global render environment ---

    /** Dynamic downward-raycast ground shadow blobs under every draw. */
    public boolean shadowBlobsEnabled = RenderEnvironment.DEFAULT_SHADOW_BLOBS;

    public boolean fogEnabled = RenderEnvironment.DEFAULT_FOG_ENABLED;
    /** View distance (world units) where fog begins. */
    public float fogStart = RenderEnvironment.DEFAULT_FOG_START;
    /** View distance (world units) where fog reaches {@link #fogDensity}. */
    public float fogEnd = RenderEnvironment.DEFAULT_FOG_END;
    /** Maximum fog opacity (0..1), reached at {@link #fogEnd}. */
    public float fogDensity = RenderEnvironment.DEFAULT_FOG_DENSITY;
    /** Linear RGB, 0..1. */
    public final float[] fogColor = { RenderEnvironment.DEFAULT_FOG_R, RenderEnvironment.DEFAULT_FOG_G, RenderEnvironment.DEFAULT_FOG_B };

    /** Linear RGB, 0..1; multiplied by {@link #ambientIntensity}. */
    public final float[] ambientColor = { RenderEnvironment.DEFAULT_AMBIENT_R, RenderEnvironment.DEFAULT_AMBIENT_G, RenderEnvironment.DEFAULT_AMBIENT_B };
    public float ambientIntensity = RenderEnvironment.DEFAULT_AMBIENT_INTENSITY;

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

    /** Copies the render settings into {@code env}. Plain field copies: safe to call every frame. */
    public void applyTo(RenderEnvironment env) {
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

    /** Clamps every render setting into its valid range (also repairs NaN from a corrupt file). */
    public void sanitize() {
        fogStart = clamp(fogStart, 0.0f, FOG_DISTANCE_MAX);
        fogEnd = clamp(fogEnd, fogStart + 0.01f, FOG_DISTANCE_MAX + 0.01f);
        fogDensity = clamp(fogDensity, 0.0f, 1.0f);
        ambientIntensity = clamp(ambientIntensity, 0.0f, AMBIENT_INTENSITY_MAX);
        for (int i = 0; i < 3; i++) {
            fogColor[i] = clamp(fogColor[i], 0.0f, 1.0f);
            ambientColor[i] = clamp(ambientColor[i], 0.0f, 1.0f);
        }
    }

    private static float clamp(float v, float min, float max) {
        if (Float.isNaN(v)) return min;
        return Math.max(min, Math.min(max, v));
    }

    // ------------------------------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------------------------------

    public static File fileIn(File projectDir) {
        return new File(projectDir, FILE_NAME);
    }

    /** Writes {@code <projectDir>/ProjectSettings.bin} through a temp file, so a failed write never truncates it. */
    public void write(File projectDir) throws IOException {
        File file = fileIn(projectDir);
        File tmp = new File(projectDir, FILE_NAME + ".tmp");
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp.toPath())))) {
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
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static ProjectSettings read(File projectDir) throws IOException {
        File file = fileIn(projectDir);
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(file.toPath())))) {
            if (dis.readInt() != MAGIC) throw new IOException("Not a RetroEngine " + FILE_NAME + ": " + file);
            int version = dis.readInt();
            if (version > VERSION) throw new IOException(FILE_NAME + " version " + version + " is newer than this editor supports");
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
                s.sanitize();
            }
            return s;
        }
    }
}
