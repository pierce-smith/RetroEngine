package com.retro.engine.ecs.components;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/**
 * CPU-simulated billboard particle emitter. Particles live in a fixed pool of {@link #maxParticles} slots
 * (structure-of-arrays, allocated once and only re-allocated when maxParticles changes), are simulated in world space
 * by {@code SimulationSystem} during Play, and are drawn by {@code SceneRenderPipeline} as camera-facing quads.
 * {@link #simulate} and the render extraction never allocate.
 *
 * <p>The emitter axis is the entity's local +Y: CONE sprays within {@link #shapeAngle} of it from a disc of
 * {@link #shapeRadius}, SPHERE sprays outward from a ball of that radius, BOX spawns inside a cube of that half-extent
 * moving along +Y.</p>
 */
public class ParticleSystem extends Component {

    public enum EmitterShape { CONE, SPHERE, BOX }
    public enum BlendMode { ADDITIVE, ALPHA }

    /** Hard cap on live particles (a whole number; the inspector edits floats). */
    @InspectorProperty(order = 0, label = "MAX PARTICLES", min = 1.0f, max = 4096.0f, step = 16.0f)
    public float maxParticles = 256.0f;

    /** Particles spawned per second while playing (0 = bursts only, via emit()). */
    @InspectorProperty(order = 1, label = "EMISSION RATE", min = 0.0f, max = 2000.0f, step = 5.0f)
    public float emissionRate = 40.0f;

    /** Seconds each particle lives. */
    @InspectorProperty(order = 2, label = "LIFETIME", min = 0.05f, max = 30.0f, step = 0.05f)
    public float lifetime = 1.0f;

    @InspectorProperty(order = 3, label = "START SIZE", min = 0.0f, max = 20.0f, step = 0.05f)
    public float startSize = 0.3f;

    @InspectorProperty(order = 4, label = "END SIZE", min = 0.0f, max = 20.0f, step = 0.05f)
    public float endSize = 0.05f;

    @InspectorProperty(order = 5, label = "START RED", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float startColorR = 1.0f;
    @InspectorProperty(order = 6, label = "START GREEN", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float startColorG = 0.8f;
    @InspectorProperty(order = 7, label = "START BLUE", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float startColorB = 0.3f;
    @InspectorProperty(order = 8, label = "START ALPHA", min = 0.0f, max = 1.0f, step = 0.05f)
    public float startColorA = 1.0f;

    @InspectorProperty(order = 9, label = "END RED", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float endColorR = 1.0f;
    @InspectorProperty(order = 10, label = "END GREEN", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float endColorG = 0.2f;
    @InspectorProperty(order = 11, label = "END BLUE", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float endColorB = 0.0f;
    @InspectorProperty(order = 12, label = "END ALPHA", min = 0.0f, max = 1.0f, step = 0.05f)
    public float endColorA = 0.0f;

    /** Initial speed in units per second along the emission direction. */
    @InspectorProperty(order = 13, label = "SPEED", min = 0.0f, max = 100.0f, step = 0.1f)
    public float speed = 1.5f;

    /** Multiplier on world gravity (9.81 down); negative values make particles rise. */
    @InspectorProperty(order = 14, label = "GRAVITY SCALE", min = -5.0f, max = 5.0f, step = 0.05f)
    public float gravityScale = 0.0f;

    /** Optional sprite; empty = a soft round particle. */
    @InspectorProperty(order = 15, label = "TEXTURE", type = PropertyType.ASSET_PICKER, assetType = "TEXTURE")
    public String texturePath;

    @InspectorProperty(order = 16, label = "SHAPE", type = PropertyType.BUTTON_GROUP)
    public EmitterShape emitterShape = EmitterShape.CONE;

    @InspectorProperty(order = 17, label = "BLEND", type = PropertyType.BUTTON_GROUP)
    public BlendMode blendMode = BlendMode.ADDITIVE;

    /** CONE half-angle in degrees. */
    @InspectorProperty(order = 18, label = "CONE ANGLE", min = 0.0f, max = 90.0f, step = 1.0f)
    public float shapeAngle = 20.0f;

    /** CONE base radius, SPHERE radius or BOX half-extent. */
    @InspectorProperty(order = 19, label = "SHAPE RADIUS", min = 0.0f, max = 50.0f, step = 0.05f)
    public float shapeRadius = 0.1f;

    @InspectorProperty(order = 20, label = "PLAY ON START", type = PropertyType.TOGGLE)
    public boolean playOnStart = true;

    // --- Runtime pool (never serialized) ---
    private transient int capacity;
    private transient int aliveCount;
    public transient float[] posX, posY, posZ, velX, velY, velZ, age, life;
    /** Draw order scratch used by the renderer for back-to-front sorting of ALPHA systems. */
    public transient int[] drawOrder;
    public transient float[] drawDepth;

    private transient boolean playing;
    private transient boolean started;
    private transient float emitAccumulator;
    private transient int pendingBurst;
    private transient int rng = 0x9E3779B9;

    // Runtime GPU texture handle cache, filled by SceneRenderPipeline
    private transient long gpuTextureHandle;
    private transient String gpuTextureKey;
    private transient int gpuTextureEpoch;

    public ParticleSystem() {}

    @Override
    public String getComponentName() {
        return "Particle System";
    }

    // =========================================================================
    // Control
    // =========================================================================

    /** Starts continuous emission at {@link #emissionRate}. */
    public void play() { playing = true; started = true; }

    /** Stops continuous emission; live particles finish their lifetime. */
    public void stop() { playing = false; started = true; emitAccumulator = 0.0f; }

    /** Queues {@code count} particles to spawn on the next simulation step (works while stopped). */
    public void emit(int count) { if (count > 0) pendingBurst += count; }

    /** Removes every live particle immediately. */
    public void clear() { aliveCount = 0; pendingBurst = 0; emitAccumulator = 0.0f; }

    public boolean isPlaying() { return playing; }

    public int getAliveCount() { return aliveCount; }

    /** Restores the pre-Play state (called when Play stops). */
    public void resetRuntime() {
        clear();
        playing = false;
        started = false;
    }

    // =========================================================================
    // Simulation (allocation-free once the pool exists)
    // =========================================================================

    /**
     * Advances the system by {@code dt} seconds with the emitter at world position (ox, oy, oz) and world rotation
     * (rx, ry, rz) in degrees.
     */
    public void simulate(float dt, float ox, float oy, float oz, float rxDeg, float ryDeg, float rzDeg) {
        ensurePool();
        if (!started) {
            started = true;
            playing = playOnStart;
        }
        if (dt <= 0.0f) return;

        // Age, integrate, and compact dead particles by swapping in the last live one
        float gravity = -9.81f * gravityScale * dt;
        int n = aliveCount;
        for (int i = 0; i < n; ) {
            float a = age[i] + dt;
            if (a >= life[i]) {
                n--;
                copy(n, i);
                continue;
            }
            age[i] = a;
            velY[i] += gravity;
            posX[i] += velX[i] * dt;
            posY[i] += velY[i] * dt;
            posZ[i] += velZ[i] * dt;
            i++;
        }
        aliveCount = n;

        int spawn = pendingBurst;
        pendingBurst = 0;
        if (playing && emissionRate > 0.0f) {
            emitAccumulator += emissionRate * dt;
            int continuous = (int) emitAccumulator;
            emitAccumulator -= continuous;
            spawn += continuous;
        }
        if (spawn <= 0) return;

        // Emitter basis: rotate local axes by Z, then X, then Y (degrees)
        float cx = (float) Math.cos(Math.toRadians(rxDeg)), sx = (float) Math.sin(Math.toRadians(rxDeg));
        float cy = (float) Math.cos(Math.toRadians(ryDeg)), sy = (float) Math.sin(Math.toRadians(ryDeg));
        float cz = (float) Math.cos(Math.toRadians(rzDeg)), sz = (float) Math.sin(Math.toRadians(rzDeg));
        float coneCos = (float) Math.cos(Math.toRadians(Math.max(0.0f, Math.min(90.0f, shapeAngle))));
        float lifeSpan = Math.max(0.01f, lifetime);

        for (int k = 0; k < spawn && aliveCount < capacity; k++) {
            float lx, ly, lz, dx, dy, dz;
            switch (emitterShape) {
                case SPHERE -> {
                    // Uniform direction on the unit sphere, spawned at a random depth inside the ball
                    float z = rand() * 2.0f - 1.0f;
                    float phi = rand() * 6.2831855f;
                    float r = (float) Math.sqrt(Math.max(0.0f, 1.0f - z * z));
                    dx = r * (float) Math.cos(phi); dy = z; dz = r * (float) Math.sin(phi);
                    float depth = shapeRadius * rand();
                    lx = dx * depth; ly = dy * depth; lz = dz * depth;
                }
                case BOX -> {
                    lx = (rand() * 2.0f - 1.0f) * shapeRadius;
                    ly = (rand() * 2.0f - 1.0f) * shapeRadius;
                    lz = (rand() * 2.0f - 1.0f) * shapeRadius;
                    dx = 0.0f; dy = 1.0f; dz = 0.0f;
                }
                default -> { // CONE around +Y from a disc
                    float cosT = 1.0f - rand() * (1.0f - coneCos);
                    float sinT = (float) Math.sqrt(Math.max(0.0f, 1.0f - cosT * cosT));
                    float phi = rand() * 6.2831855f;
                    float cp = (float) Math.cos(phi), sp = (float) Math.sin(phi);
                    dx = sinT * cp; dy = cosT; dz = sinT * sp;
                    float rr = shapeRadius * (float) Math.sqrt(rand());
                    lx = rr * cp; ly = 0.0f; lz = rr * sp;
                }
            }
            int i = aliveCount++;
            // Rotate the spawn offset and the direction into world space (Z, then X, then Y)
            float t;
            t = lx * cz - ly * sz; ly = lx * sz + ly * cz; lx = t;
            t = ly * cx - lz * sx; lz = ly * sx + lz * cx; ly = t;
            t = lx * cy + lz * sy; lz = -lx * sy + lz * cy; lx = t;
            t = dx * cz - dy * sz; dy = dx * sz + dy * cz; dx = t;
            t = dy * cx - dz * sx; dz = dy * sx + dz * cx; dy = t;
            t = dx * cy + dz * sy; dz = -dx * sy + dz * cy; dx = t;

            posX[i] = ox + lx; posY[i] = oy + ly; posZ[i] = oz + lz;
            float v = speed * (0.75f + 0.5f * rand());
            velX[i] = dx * v; velY[i] = dy * v; velZ[i] = dz * v;
            age[i] = 0.0f;
            life[i] = lifeSpan * (0.8f + 0.4f * rand());
        }
    }

    private void ensurePool() {
        int want = Math.max(1, Math.min(4096, Math.round(maxParticles)));
        if (want == capacity && posX != null) return;
        // Only reached on first use or when MAX PARTICLES changes, never per frame
        posX = new float[want]; posY = new float[want]; posZ = new float[want];
        velX = new float[want]; velY = new float[want]; velZ = new float[want];
        age = new float[want]; life = new float[want];
        drawOrder = new int[want];
        drawDepth = new float[want];
        capacity = want;
        aliveCount = 0;
    }

    private void copy(int from, int to) {
        posX[to] = posX[from]; posY[to] = posY[from]; posZ[to] = posZ[from];
        velX[to] = velX[from]; velY[to] = velY[from]; velZ[to] = velZ[from];
        age[to] = age[from]; life[to] = life[from];
    }

    /** xorshift32 in [0, 1). */
    private float rand() {
        int x = rng;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        rng = x;
        return (x >>> 8) * (1.0f / 16777216.0f);
    }

    // =========================================================================
    // Renderer texture cache
    // =========================================================================

    public long getCachedTextureHandle(String path, int epoch) {
        return (gpuTextureHandle != 0L && epoch == gpuTextureEpoch && path.equals(gpuTextureKey)) ? gpuTextureHandle : 0L;
    }

    public void cacheTextureHandle(String path, long handle, int epoch) {
        gpuTextureKey = path;
        gpuTextureHandle = handle;
        gpuTextureEpoch = epoch;
    }
}
