package com.retro.engine.scripting.user;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.MeshFilter;
import com.retro.engine.ecs.components.ScriptComponent;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.ecs.components.UIPanel;
import com.retro.engine.ecs.components.UIProgressBar;
import com.retro.engine.ecs.components.UIText;
import com.retro.engine.scripting.Input;
import com.retro.engine.scripting.framework.ScriptTask;
import com.retro.engine.scripting.framework.SimpleEntityScript;
import com.retro.engine.scripting.SceneManager;

/**
 * Infinite Driver game loop. The car (VehicleController + CarDriverScript) drives toward -Z; every frame its forward
 * travel is taken back out and applied to the world instead (floating origin), so the car stays at Z = 0 and the
 * highway is endless. RoadSeg* roots are recycled from behind the camera to the far end with re-rolled scenery, and
 * rows of obstacles from the Obstacle* pool are generated procedurally, always leaving a drivable gap that narrows
 * as the speed ramps up. Hitting an obstacle ends the run; the HUD shows distance, best, speed and prompts.
 */
public class HighwayGameScript extends SimpleEntityScript {

    public String carName = "Car";
    public float startSpeed = 18.0f;        // m/s
    public float maxSpeed = 55.0f;
    public float speedRamp = 0.5f;          // m/s gained per second
    public float segmentLength = 20.0f;
    public float recycleZ = 30.0f;          // segments past this (behind the camera) jump to the far end
    public float spawnZ = -150.0f;
    public float despawnZ = 15.0f;
    public float roadHalfWidth = 5.9f;
    public float easyRowSpacing = 30.0f;    // metres between obstacle rows at start
    public float hardRowSpacing = 14.0f;    // ... at max speed
    public float easyGap = 5.2f;            // guaranteed free width in every row at start
    public float hardGap = 3.4f;            // ... at max speed
    public float carHalfWidth = 0.8f;
    public float carHalfLength = 1.7f;
    public float countdown = 2.5f;
    public int nearMissBonus = 25;
    public float nearMissShake = 0.15f;
    public float autoRestartDelay = 0f;     // > 0 reloads the scene this many seconds after a crash

    // --- Audio mixer (buses: MASTER, BGM, SFX, VOICE; every bus feeds MASTER) ---
    // The soundtrack is the scene's looping Music entity (BGM bus). musicTrack adds a one-shot BGM clip on top, e.g. an intro sting
    public String musicTrack = "";          // optional, e.g. "Assets/Audio/intro_sting.ogg" (played once on the BGM bus)
    public String newBestStinger = "";      // optional jingle on a new best run (BGM bus)
    public float crashSfxBusVolume = 0.6f;  // SFX bus is pulled down while the crash screen is up
    public float masterStep = 0.1f;         // -/= keys nudge the MASTER bus by this much

    private static final int KEY_R = 82, KEY_SPACE = 32, KEY_ENTER = 257, KEY_M = 77, KEY_MINUS = 45, KEY_EQUAL = 61;
    private static final int READY = 0, DRIVING = 1, CRASHED = 2;
    private static final int MAX = 64;

    private final java.util.Random rng = new java.util.Random();
    private Transform carT;
    private Entity carEntity;
    private Object carScript;
    private java.lang.reflect.Method setSpeedM, crashM;

    private final Transform[] segs = new Transform[MAX];
    private int segCount;
    private final Transform[] trees = new Transform[MAX * 2];
    private final Transform[] treeSeg = new Transform[MAX * 2];
    private int treeCount;

    private final Transform[] obs = new Transform[MAX];
    private final float[] obsRestY = new float[MAX];
    private final boolean[] obsActive = new boolean[MAX];
    private final boolean[] obsPassed = new boolean[MAX];
    private final boolean[] obsRound = new boolean[MAX];
    private final float[] obsDrift = new float[MAX];
    private int obsCount;

    private int state = READY;
    private float stateTime;
    private float speed;
    private float runTime;
    private float distance;
    private float nextRowAt;
    private int bonus;
    private float bonusFlash;
    private int best;
    private boolean newBest;

    private UIText hudScore, hudBest, hudSpeed, hudMsg, hudSub, hudControls;
    private UIPanel msgPanel;
    private UIProgressBar speedBar;
    private final ScriptTask restartTask = new ScriptTask() {
        public void run() { SceneManager.reloadCurrentScene(); }
    };

    @Override
    public void onStart() {
        Entity car = findEntity(carName);
        if (car == null) { logError("HighwayGameScript: no entity named '" + carName + "'"); return; }
        carT = car.getTransform();
        carEntity = car;

        for (Entity e : sceneWorld.getSceneEntities()) {
            Transform t = e.getComponent(Transform.class);
            if (t == null || e.name == null) continue;
            boolean root = e.parentId == null || e.parentId.length() == 0; // loaded scenes store roots as ""
            if (root && e.name.startsWith("RoadSeg") && segCount < MAX) segs[segCount++] = t;
            if (root && e.name.startsWith("Obstacle") && obsCount < MAX) {
                MeshFilter mf = (MeshFilter) e.getComponent(MeshFilter.class);
                obsRound[obsCount] = mf != null && mf.meshAssetPath != null && mf.meshAssetPath.indexOf("Sphere") >= 0;
                obsRestY[obsCount] = t.posY;
                obs[obsCount++] = t;
                park(obsCount - 1);
            }
        }
        for (Entity e : sceneWorld.getSceneEntities()) { // tree groups, for re-rolling scenery on recycle
            if (e.name == null || !(e.name.endsWith("TreeL") || e.name.endsWith("TreeR")) || treeCount >= trees.length) continue;
            Entity parent = (e.parentId != null && e.parentId.length() > 0) ? sceneWorld.findEntityById(e.parentId) : null;
            if (parent == null) continue;
            trees[treeCount] = (Transform) e.getComponent(Transform.class);
            treeSeg[treeCount++] = (Transform) parent.getComponent(Transform.class);
        }

        hudScore = text("HUD Score");
        hudBest = text("HUD Best");
        hudSpeed = text("HUD Speed");
        hudMsg = text("HUD Message");
        hudSub = text("HUD SubMessage");
        hudControls = text("HUD Controls");
        Entity mp = sceneWorld.findEntityByName("HUD MsgPanel");
        msgPanel = mp != null ? (UIPanel) mp.getComponent(UIPanel.class) : null;
        Entity sb = sceneWorld.findEntityByName("HUD SpeedBar");
        speedBar = sb != null ? (UIProgressBar) sb.getComponent(UIProgressBar.class) : null;
        if (speedBar != null) speedBar.maxValue = maxSpeed;
        if (hudControls != null) hudControls.text = "A/D OR ARROWS: STEER    H: HORN    R: RESTART    M: MUSIC    -/=: VOLUME";

        // Runtime bus volumes multiply the Project Settings mix and reset when Play stops. Restore SFX after a crash
        // (scene reloads keep runtime bus state), then start the soundtrack on the BGM bus.
        setBusVolume("SFX", 1.0f);
        if (musicTrack != null && musicTrack.length() > 0) playGlobalSound(musicTrack, "BGM", 0.7f);

        logInfo("Infinite Driver: " + segCount + " road segments, " + obsCount + " obstacles, " + treeCount + " trees");

        try { best = Integer.parseInt(System.getProperty("retro.infiniteDriver.best", "0")); } catch (Exception ex) { best = 0; }
        nextRowAt = 40.0f;
        for (int i = 0; i < segCount; i++) for (int k = 0; k < treeCount; k++) if (treeSeg[k] == segs[i]) rollTree(k);
    }

    @Override
    public void onUpdate(float dt) {
        if (carT == null || dt <= 0.0f) return;
        if (carScript == null) resolveCarScript();
        dt = Math.min(dt, 0.05f);
        stateTime += dt;
        updateMixerKeys();

        if (Input.isKeyPressed(KEY_R) || (state == CRASHED && stateTime > 0.8f
                && (Input.isKeyPressed(KEY_SPACE) || Input.isKeyPressed(KEY_ENTER)))) {
            SceneManager.reloadCurrentScene();
            return;
        }

        if (state == READY && stateTime >= countdown) {
            state = DRIVING;
            stateTime = 0.0f;
        }
        if (state == DRIVING) {
            runTime += dt;
            speed = Math.min(maxSpeed, startSpeed + speedRamp * runTime);
        } else if (state == CRASHED) {
            speed = 0.0f;
        }
        callCar(setSpeedM, Float.valueOf(speed));

        // Floating origin: the VehicleController moved the car along -Z; move the world by that much instead
        float shift = -carT.posZ;
        carT.posZ = 0.0f;
        if (shift != 0.0f) scroll(shift);

        if (state == DRIVING) {
            if (distance >= nextRowAt) {
                float d = difficulty();
                spawnRow(d);
                nextRowAt += easyRowSpacing + (hardRowSpacing - easyRowSpacing) * d;
            }
            updateObstacles(dt);
        }
        updateHud(dt);
    }

    // --- World ---

    private void scroll(float dz) {
        distance += dz;
        float loop = segCount * segmentLength;
        for (int i = 0; i < segCount; i++) {
            segs[i].posZ += dz;
            if (segs[i].posZ > recycleZ) {
                segs[i].posZ -= loop;
                for (int k = 0; k < treeCount; k++) if (treeSeg[k] == segs[i]) rollTree(k);
            }
        }
        for (int i = 0; i < obsCount; i++) {
            if (!obsActive[i]) continue;
            obs[i].posZ += dz;
            if (obsRound[i]) obs[i].rotX -= dz / (obs[i].scaleY * 0.5f) * 57.3f; // round ones roll
        }
    }

    /** Re-rolls a roadside tree: side offset, size and occasional removal, so each lap of the road looks different. */
    private void rollTree(int k) {
        Transform t = trees[k];
        float side = t.posX < 0.0f ? -1.0f : 1.0f;
        boolean show = rng.nextFloat() < 0.8f;
        float s = 0.7f + rng.nextFloat() * 0.7f;
        t.posX = side * (9.0f + rng.nextFloat() * 14.0f);
        t.posZ = (rng.nextFloat() - 0.5f) * segmentLength * 0.8f;
        t.scaleX = show ? s : 0.0f;
        t.scaleY = show ? s : 0.0f;
        t.scaleZ = show ? s : 0.0f;
    }

    private float difficulty() {
        return Math.max(0.0f, Math.min(1.0f, (speed - startSpeed) / (maxSpeed - startSpeed)));
    }

    /** One row of 1-3 obstacles placed freely across the road (no lanes) around a guaranteed gap. */
    private void spawnRow(float d) {
        int count = 1;
        if (rng.nextFloat() < 0.35f + 0.45f * d) count++;
        if (d > 0.45f && rng.nextFloat() < 0.35f * d) count++;
        float gap = easyGap + (hardGap - easyGap) * d;
        float gapC = -roadHalfWidth + gap * 0.5f + rng.nextFloat() * (2.0f * roadHalfWidth - gap);
        float[] placedX = new float[3];
        float[] placedW = new float[3];
        int placed = 0;
        for (int n = 0; n < count; n++) {
            int idx = freeObstacle();
            if (idx < 0) return;
            float hw = obs[idx].scaleX * 0.5f;
            for (int attempt = 0; attempt < 6; attempt++) {
                boolean left = rng.nextBoolean();
                float lo = left ? -roadHalfWidth : gapC + gap * 0.5f;
                float hi = left ? gapC - gap * 0.5f : roadHalfWidth;
                if (hi - lo < hw * 2.0f) continue;
                float x = lo + hw + rng.nextFloat() * (hi - lo - hw * 2.0f);
                boolean overlaps = false;
                for (int p = 0; p < placed; p++) if (Math.abs(placedX[p] - x) < placedW[p] + hw + 0.3f) overlaps = true;
                if (overlaps) continue;
                placedX[placed] = x;
                placedW[placed++] = hw;
                activate(idx, x, spawnZ - rng.nextFloat() * 3.0f);
                if (count == 1 && d > 0.25f && rng.nextFloat() < 0.4f * d) {
                    obsDrift[idx] = (rng.nextBoolean() ? 1.0f : -1.0f) * (1.0f + 2.0f * d); // slides across the road
                }
                break;
            }
        }
    }

    private void updateObstacles(float dt) {
        float cx = carT.posX;
        for (int i = 0; i < obsCount; i++) {
            if (!obsActive[i]) continue;
            Transform o = obs[i];
            float hw = o.scaleX * 0.5f, hl = o.scaleZ * 0.5f;
            if (obsDrift[i] != 0.0f) {
                o.posX += obsDrift[i] * dt;
                if (o.posX > roadHalfWidth - hw || o.posX < -roadHalfWidth + hw) {
                    obsDrift[i] = -obsDrift[i];
                    o.posX = Math.max(-roadHalfWidth + hw, Math.min(roadHalfWidth - hw, o.posX));
                }
            }
            float dx = Math.abs(o.posX - cx);
            if (dx < hw + carHalfWidth && Math.abs(o.posZ) < hl + carHalfLength) { crash(); return; }
            if (!obsPassed[i] && o.posZ > carHalfLength + hl) {
                obsPassed[i] = true;
                if (dx < hw + carHalfWidth + 0.9f) { bonus += nearMissBonus; bonusFlash = 1.0f; shakeCamera(nearMissShake, 0.15f); }
            }
            if (o.posZ > despawnZ) park(i);
        }
    }

    private int freeObstacle() {
        int start = rng.nextInt(Math.max(1, obsCount));
        for (int n = 0; n < obsCount; n++) {
            int i = (start + n) % obsCount;
            if (!obsActive[i]) return i;
        }
        return -1;
    }

    private void activate(int i, float x, float z) {
        obsActive[i] = true;
        obsPassed[i] = false;
        obsDrift[i] = 0.0f;
        obs[i].posX = x;
        obs[i].posY = obsRestY[i];
        obs[i].posZ = z;
        obs[i].rotY = obsRound[i] ? rng.nextFloat() * 90.0f : 0.0f;
    }

    private void park(int i) {
        obsActive[i] = false;
        obs[i].posY = -100.0f;
    }

    private void crash() {
        state = CRASHED;
        stateTime = 0.0f;
        callCar(crashM, null);
        if (autoRestartDelay > 0.0f) runAfter(autoRestartDelay, restartTask);
        int score = score();
        if (score > best) {
            best = score;
            newBest = true;
            System.setProperty("retro.infiniteDriver.best", Integer.toString(best));
            if (newBestStinger != null && newBestStinger.length() > 0) playGlobalSound(newBestStinger, "BGM");
        }
        setBusVolume("SFX", crashSfxBusVolume); // muffle engine / screech under the crash screen
    }

    /** Bus volume controls: M toggles the BGM bus, -/= step the MASTER bus (allocation-free). */
    private void updateMixerKeys() {
        if (Input.isKeyPressed(KEY_M)) setBusMuted("BGM", !isBusMuted("BGM"));
        if (Input.isKeyPressed(KEY_MINUS)) setBusVolume("MASTER", Math.max(0.0f, getBusVolume("MASTER") - masterStep));
        if (Input.isKeyPressed(KEY_EQUAL)) setBusVolume("MASTER", Math.min(1.0f, getBusVolume("MASTER") + masterStep));
    }

    private int score() { return (int) distance + bonus; }

    // --- HUD ---

    private void updateHud(float dt) {
        if (hudScore != null) hudScore.text = score() + " M";
        bonusFlash = Math.max(0.0f, bonusFlash - dt);
        if (hudBest != null) hudBest.text = bonusFlash > 0.0f ? "NEAR MISS +" + nearMissBonus : "BEST " + best + " M";
        if (hudSpeed != null) hudSpeed.text = Math.round(speed * 3.6f) + " KM/H";
        if (speedBar != null) speedBar.value = speed;

        String msg = "", sub = "";
        if (state == READY) {
            int n = (int) Math.ceil(countdown - stateTime);
            msg = n > 0 ? Integer.toString(n) : "GO!";
            sub = "DODGE THE OBSTACLES - STEER WITH A/D OR ARROWS";
        } else if (state == DRIVING && stateTime < 0.7f) {
            msg = "GO!";
        } else if (state == CRASHED) {
            msg = newBest ? "NEW BEST!" : "CRASHED!";
            sub = score() + " M  -  PRESS SPACE OR R TO DRIVE AGAIN";
        }
        boolean show = msg.length() > 0;
        if (hudMsg != null) { hudMsg.text = msg; hudMsg.visible = show; }
        if (hudSub != null) { hudSub.text = sub; hudSub.visible = show && sub.length() > 0; }
        if (msgPanel != null) msgPanel.visible = show && sub.length() > 0;
        if (hudControls != null) hudControls.visible = state != CRASHED;
    }

    // --- Helpers ---

    private UIText text(String name) {
        Entity e = sceneWorld.findEntityByName(name);
        return e != null ? (UIText) e.getComponent(UIText.class) : null;
    }

    /** The car's script may start after this one, so its instance is looked up lazily. */
    private void resolveCarScript() {
        ScriptComponent sc = (ScriptComponent) carEntity.getComponent(ScriptComponent.class);
        carScript = sc != null ? sc.activeScriptInstance : null;
        if (carScript == null) return;
        try {
            setSpeedM = carScript.getClass().getMethod("setForwardSpeed", new Class[]{float.class});
            crashM = carScript.getClass().getMethod("crash", new Class[]{});
        } catch (Exception ex) { logError("CarDriverScript API missing: " + ex); }
    }

    private boolean warnedNoCar;

    private void callCar(java.lang.reflect.Method m, Object arg) {
        if (m == null || carScript == null) {
            if (!warnedNoCar && state == DRIVING) { warnedNoCar = true; logWarning("HighwayGameScript: CarDriverScript not linked yet"); }
            return;
        }
        try {
            m.invoke(carScript, arg == null ? new Object[]{} : new Object[]{arg});
        } catch (Exception ex) {
            logError("CarDriverScript call failed: " + ex);
        }
    }
}
