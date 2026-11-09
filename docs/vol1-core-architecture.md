# Volume 1: Core Architecture

> **Engine loop, ECS kernel, and the Janino scripting runtime.**
> These three pieces carry everything else. If you only read one volume, read this one.

---

## Design Philosophy

RenderWare taught a generation of engine programmers that a good engine is mostly a good *contract*: a clean loop, a
predictable data model, and a hard line between what the engine owns and what the game owns. Gamebryo and Creation
Engine showed what happens when you let that contract grow into a moddable platform. Minecraft proved that Java can
carry one of the most-played, most-modded games ever made.

RetroEngine takes all of that and holds itself to one rule from modern Java performance work:

> **The frame loop must not feed the garbage collector.**

JDK 25's collectors are excellent, but the cheapest GC pause is the one you never trigger. Hot paths here index into
pre-sized arrays, reuse scratch objects, and keep `new` out of anything that runs every frame.

---

## 1. Engine Loop & Runtime

### 1.1 Entry Point: `Main`

`com.retro.engine.Main` picks one of three modes from the command line:

| Flag                    | Mode                  | Loop          | Window / GPU |
|-------------------------|-----------------------|---------------|--------------|
| *(none)*                | Editor                | `EngineLoop`  | Yes          |
| `--runtime` / `--game`  | Standalone game       | `RuntimeLoop` | Yes          |
| `--headless`            | Headless host         | `RuntimeLoop` | No           |
| `--vulkan`              | Force Vulkan backend  | *(modifier)*  | MoltenVK on macOS |

`EngineContext` reads a few more flags after the mode is picked:

| Flag                    | Effect |
|-------------------------|--------|
| `--project <dir>` / `--project=<dir>` | Editor: open that project instead of showing the project launcher. Runtime: use it as the game-data folder |
| `--mods`                | Editor only: load `<Project>/Mods/` (scene saving is then disabled, see Volume 4) |
| `--no-mods`             | Runtime only: skip `Game_Data/Mods/` |
| `--frames <n>` / `--frames=<n>` | Headless only: run `n` frames, stop the scene and exit (see §1.4) |

`BackendLoader` also accepts `-vulkan` or a bare `vulkan` argument, the `-Dretro.rhi=vulkan` / `-Dretro.vulkan=true`
system properties, and the `RETRO_RHI=vulkan` / `RETRO_VULKAN=1` environment variables as equivalents of `--vulkan`.

Without `--project`, a runtime build looks for its game data in this order: the `-Dretro.gameData` system property set by
the exported launcher, `./Game_Data/`, then the working directory itself. If none of them holds a
project file (`<Name>.rproj`), it starts with an empty scene.

Two things happen before any engine code runs, and both came from production failures:

```java
// LWJGL's default 64 KB MemoryStack overflows inside new VkInstance(...) on Windows NVIDIA drivers.
Configuration.STACK_SIZE.set(1024);

if (Boolean.getBoolean("retro.packaged")) {
    redirectOutputToLogFile(...);   // see Volume 5: OS logging
}
```

`main` builds one `EngineContext`, initializes it for the selected mode, runs the loop, and always calls
`context.shutdown()` in a `finally` block, so a fatal crash still releases GPU resources. A default uncaught-exception
handler logs `[FATAL] Uncaught exception on thread <name>` for every thread, and a fatal loop error is printed before
`shutdown()` runs, so the trace reaches the log file first.

### 1.2 `EngineContext`, `DisplayService` and `WindowHost`

There is no god-object window class. Responsibilities are split:

- **`WindowHost`**: the raw GLFW window. Input callbacks, cursor capture, fullscreen toggle, gamepad polling via
  `GamepadManager`, and a `setTextInputCapture(BooleanSupplier)` hook that the editor uses to lock the keyboard.
- **`DisplayService`**: the display-side view of the window: framebuffer size, content/monitor DPI scale, and a
  `DisplayMetrics` record for systems that need all of it at once.
- **`EngineContext`**: the composition root. It owns the window, the `IRenderBackend` (RHI), `AssetStreamer`,
  `SceneRenderPipeline`, `EditorWorkspace`, `ViewportCamera` and `HotkeyRouter`, and hands them to the loop.

`BackendLoader` chooses the RHI at startup: Metal 3 on macOS by default, Vulkan 1.3 everywhere else or when
`--vulkan` is passed.

### 1.3 The Editor Frame (`EngineLoop`)

Fixed 60 FPS target, explicit ordering, nothing hidden:

```java
float frameDelta = Math.min(elapsedSeconds, MAX_FRAME_DELTA);   // MAX_FRAME_DELTA = 0.1f

window.pollEvents();
commandHistory.flushPendingCommands();          // 0. undo/redo transactions land at the frame boundary
hotkeyRouter.processInput(...);                 // 1. hotkeys, gizmo snapping, fly camera
ViewportPanel.syncPlayCamera(...);              // 2. Play mode follows the scene's Main Camera
rhi.setCameraProjection(...); rhi.setCameraTransform(...);
editorManager.update(frameDelta);               // 3. simulation (crash here = stop Play, NOT close the editor)
renderSubmitter.submitFrame(...);               // 4. build the render queue
rhi.beginFrame(); rhi.endFrame(); rhi.present();
```

**Delta clamping.** Any frame longer than 100 ms (debugger break, window drag, disk stall) gets treated as 100 ms.
Without this, one long hitch lets physics and scripts take a giant step and objects tunnel through the floor.

**Crash containment.** A `RuntimeException` or `StackOverflowError` thrown from scene simulation stops Play mode
and logs the trace. Your unsaved scene survives. This one change saved more work during the sprint than any other.

**Frame pacing.** The loop sleeps through most of the remaining budget, then spin-waits the last ~1 ms with
`Thread.onSpinWait()`. Plain `Thread.sleep` overshoots by 1 to 2 ms on most OSes, and you'd see that overshoot as
judder.

### 1.4 The Game Frame (`RuntimeLoop`)

Exported games run the same scene simulation without editor overlays. The scene is forced into `SIMULATING`, the
viewport is set fullscreen through `GameView.setFullscreen`, the grid mesh is cleared, and only the game HUD reaches
the UI stream. Delta is clamped on both ends: `[0.0001s, 0.1s]`.

In `--headless` mode the loop never touches a window or GPU. The project loads the same way it does in an exported
game, and scripts, physics, navigation and animation all run. Nothing is drawn, so this can't catch rendering bugs.

This mode is the foundation for **networked games**: a process that runs the game with no player at the machine. The
networking layer itself doesn't exist yet, so for now a headless process can't talk to anything. It's also how a game
gets run automatically:

```bash
./gradlew installDist
build/install/retro-engine/bin/retro-engine --headless --project <dir> --frames 600
```

Add `--scene <path>` to start on a project-relative scene other than the project's startup scene. It works for
`--runtime` too, and nothing is written back to the project.

With `--frames`, every frame uses the fixed 1/60 s tick with no sleeping, so runs are repeatable and fast. The scene
is stopped at the end, so `onDestroy` runs too. Without it, the loop ticks at 60 Hz until the process is killed.

| Exit code | Meaning |
|-----------|---------|
| `0` | Every frame ran, with no script errors |
| `1` | The engine crashed with an uncaught exception |
| `2` | Bad arguments, the project couldn't be opened, or the startup scene is empty or failed to load |
| `3` | `--frames` only: a script failed to compile or threw, an event listener threw, or a script asked for a clip, prefab or scene that doesn't exist |

An empty scene counts as a failure because a run that tested nothing mustn't report success. The automated suite uses
this to run each template in a fresh JVM (Volume 5 §3.0).

### 1.4.1 Inside a Simulation Tick

Both loops end up in `SceneWorld.update(dt)`. While Play is running, one tick does this, in order:

```
HierarchySystem.invalidateEntityIndex()      // cheap check, rebuilt only on a real change (Volume 6 §5.1)
ScriptRuntime.beginFrame                     // remove last frame's camera shake, age debug lines
SimulationSystem.update
  0.  NavMeshSystem.update                   // resolve path requests, steer agents (Volume 4 §1.5)
  0b. SplineSystem.update                    // advance Spline Followers (Volume 4 §1.6)
  1.  PhysicsSystem.step                     // fixed 60 Hz, at most 6 steps (Volume 4 §1.1)
  2.  ScriptEngine.updateScripts             // onFrameworkUpdate + onUpdate per script
  2b. PhysicsSystem.carryAfterScripts        // riders follow a platform a script just moved (Volume 4 §1.2)
  3.  spatial audio                          // source positions, bus mix, ducking, reverb zones (§4)
  4.  particle simulation                    // after scripts, so this frame's play()/stop()/emit() apply now
  Input.endFrame()                           // clear this frame's "pressed" edges
flushPendingDestroys()                       // deferred destroy() calls (Volume 6 §2.5)
ScriptRuntime.endFrame                       // apply this frame's camera shake
AnimationSystem.update per AnimatorComponent // runs in every play state, not just Play
IKSystem.update                              // Play only: feet, aim and look adjust the finished pose
BoneSocketSystem.update                      // Play only: entities on bones land on this frame's pose
applyPendingSceneLoad()                      // loadScene() requests land here, after everything else
```

Navigation and splines run **before** physics on purpose. Character Controller velocities that an agent sets are
integrated in the same frame instead of one frame late. `Input.endFrame()` runs outside Play as well, so editor
clicks don't show up as a burst of presses on the first Play frame.

Scene transitions are queued too. `loadScene(path)` only records the request. The swap happens after the frame's
physics, script and animation work has finished. A path that can't be resolved through the project VFS aborts the
transition before anything is torn down. The first transition during a Play session parks the scene Play started
in, so Stop still returns you to it.

### 1.5 Where Virtual Threads Actually Run

Java 25 virtual threads handle **blocking I/O off the frame thread**. They never touch the GPU or ECS state:

| Subsystem              | Thread                                          | Job                                    |
|------------------------|-------------------------------------------------|----------------------------------------|
| `AssetStreamer`        | `Executors.newVirtualThreadPerTaskExecutor()`   | Async disk reads for meshes/textures   |
| `AssetDatabase`        | `asset-folder-watcher`                          | Watches `Assets/` for hot reload       |
| `VulkanShaderCompiler` | `glslc-hot-reload`                              | GLSL → SPIR-V recompilation            |
| `ModShaders`           | `mod-shader-build`                              | Building mod shader overrides          |
| `UISoundPlayer`        | per-sound virtual thread                        | Fire-and-forget UI audio               |

```java
private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
```

A few jobs use ordinary **platform** threads instead. They follow the same produce/consume rule:

| Subsystem                    | Thread                    | Job |
|------------------------------|---------------------------|-----|
| `AutoSaveSystem`             | `RetroEngine-AutoSave` (daemon timer) | Raises a flag every 5 minutes. The `.rscene.bak` write itself happens on the main thread in `tick()` |
| `EditorWorkspace`            | `RetroEngine-NativeDialog` | Native open/save dialogs, so the frame loop keeps drawing while they're open |
| Build exporter / asset import | worker `Thread`s          | Long-running export, `.rmod` packaging and import jobs, with progress reported back to the UI |
| `ScriptSaveData`             | `ScriptSaveData-flush` (shutdown hook) | Final save-data write when the JVM exits (Volume 6 §4.4) |

**The rule:** workers *produce* data (byte buffers, compiled SPIR-V, file-change events). The main thread *consumes*
it at a frame boundary and does all GPU uploads and ECS mutation. No locks on the hot path and no surprise
cross-thread writes into component arrays.

---

## 2. Zero-Allocation ECS Kernel

### 2.1 Data Is Data, Logic Is Logic

The strictest rule in the engine:

> **Components hold state. Systems and scripts hold behavior. A component never has an `update()` method.**

```java
public abstract class Component {
    public abstract String getComponentName();
    public void onBeforeSerialize() {}               // encode derived state before save
    public void onDeserialized(Entity owner) {}      // rebuild derived state after load
}
```

The only hooks are serialization hooks. Per-frame behavior lives in systems (`PhysicsSystem`, `AnimationSystem`,
`HierarchySystem`, `SimulationSystem`) or in an `EntityScript` attached through a `ScriptComponent`.

Newer systems follow the same split. `NavMeshSystem` steers `NavMeshAgent`s, `SplineSystem` moves
`SplineFollower`s, and `AudioReverbSystem` / `AudioMixerService` consume `AudioReverbZone` and `AudioSource` data.
All of them are owned or driven by `SimulationSystem`. The one partial exception is `ParticleSystem`. It holds a
`simulate(...)` method and its own particle pool, but only `SimulationSystem` calls it, once per Play frame, so the
component still never updates itself.

### 2.2 Dense Pools & Generational Handles

- **`ComponentPool<T>`**: one per component class. Components sit in a packed `T[]` alongside a parallel
  `String[]` of owning entity IDs, with a sparse map from ID to dense index. Removal swaps the last element into the
  hole, so iteration is always a tight `0..size` loop with no gaps. Capacity doubles only on growth.
- **`EntityHandle`**: a 64-bit generational handle `record(int index, int generation)`. Deleting an entity bumps the
  generation, so stale handles fail `isValidHandle()` and never point at a recycled slot.

```java
ComponentPool<Transform> pool = entityManager.getComponentPool(Transform.class);
Transform[] dense = pool.getDenseComponents();
for (int i = 0, n = pool.getSize(); i < n; i++) {
    Transform t = dense[i];   // no iterator, no boxing, no allocation
}
```

> **Candid note:** entity IDs are still `String`s and the sparse map is a `HashMap<String,Integer>`, so lookups by ID
> are not free. Dense iteration would avoid that, but no engine system uses it yet, and a pool can go stale, so treat
> it as an unproven path rather than the way to write systems.

### 2.3 `ComponentSchema`: Reflection Done Once

Reflection is slow, so it runs once per class and gets cached. Every persisted or inspectable field is tagged:

```java
public class Light extends Component {
    @InspectorProperty(order = 2, label = "INTENSITY", min = 0f, max = 10f)
    public float intensity = 1.0f;
    public String getComponentName() { return "Light"; }
}
```

`ComponentSchema.of(type)` builds a descriptor with each field's `Control` (`ASSET`, `TOGGLE`, `CHOICE`, `NUMBER`,
`STRING`, `READ_ONLY`) and `ValueKind` (`FLOAT`, `INT`, `BOOLEAN`, `ENUM`, `ASSET_PATH`, `STRING`, `STRING_MAP`,
`BYTES`, `UNSUPPORTED`).
That one descriptor drives the Inspector UI, `ComponentBinaryCodec` serialization, prefab saving, Play-mode
snapshots, and `.rpatch` property matching. Add a field and every one of those systems picks it up.

`@Component.LegacyNames` keeps old scenes loading after a component is renamed.

**How a field picks its control.** `ASSET_PICKER` → `ASSET`. `boolean` or `TOGGLE` → `TOGGLE`. Enums or
`BUTTON_GROUP` → `CHOICE`. `float`, **`int`**, or `COLOR` → `NUMBER`. `String` → `STRING`. Anything else is
`READ_ONLY`. `int` fields used to show up read-only. They are now editable `NUMBER` controls that drag in whole
steps (step rounded to at least 1), display without decimals, and only push an undo command when the rounded value
actually changes.

**The rest of `@InspectorProperty`.** Besides `order`, `label`, `min`, `max` and `type`, the annotation carries:

| Attribute | Meaning |
|-----------|---------|
| `step` | Drag/stepper increment (default `0.1`) |
| `assetType` | Filters the asset picker |
| `hidden` | Persisted but not shown in the Inspector (e.g. `SplineComponent.splineData`, a `byte[]` blob) |
| `serialized` | `false` = shown in the Inspector but never saved (e.g. `SplineComponent.pointCount`) |
| `since` / `prefabSince` | Scene and prefab format version that introduced the field |

> **Binary layout rule:** fields are serialized **in `order` sequence**. Never reorder, insert or remove annotated
> fields. Append new ones with the next `order`, and gate them with `since` / `prefabSince` and a format version bump.

**File format.** Scenes (format 10) and prefabs (format 9) share these guarantees, and older files keep loading:

- **Saves are all-or-nothing.** The file is built in memory, then swapped in atomically by `AtomicFile`. Anything that
  goes wrong along the way leaves the old file untouched.
- **Damage is detected.** A CRC-32 in the header covers the rest of the file. A bad checksum, a truncated file, or a
  file from a newer engine is reported as a load failure. It's never treated as an empty scene.
- **Components are self-contained.** Each one is stored as its type name, its byte length, then a payload that refers
  to nothing else in the file. An unknown type, usually from a missing mod, loads as an `UnknownComponent`. It shows
  in the Inspector as `Missing: <type>` and writes its bytes back unchanged on save, so the data returns with the mod.
- **Short payloads are allowed.** Fields missing from the end of a payload keep their defaults.

When you bump a format version, add a real file from the old version to `src/test/resources/formats/` **before**
changing the writer. Once the writer moves on, you can't make one any more.

### 2.4 The 25 Built-In Component Types

| Category    | Components |
|-------------|------------|
| Core        | `Transform`, `MeshFilter`, `Material`, `Light`, `Camera`, `AudioSource`, `ScriptComponent` |
| Physics     | `RigidBodyComponent`, `BoxCollider`, `SphereCollider`, `CapsuleCollider` |
| Controllers | `CharacterControllerComponent`, `VehicleControllerComponent` |
| Animation   | `AnimatorComponent`, `BoneSocket`, `FootIK`, `LookAtIK`, `AimIK` |
| Game UI     | `UICanvasComponent`, `UIPanel`, `UIText`, `UIImage`, `UIProgressBar` |
| Effects     | `ParticleSystem` (Volume 3 §2.4), `Decal` (Volume 3 §2.4a) |
| Audio       | `AudioReverbZone` (§4) |
| Navigation  | `NavMeshAgent`, `NavMeshGate` (Volume 4 §1.5) |
| Paths       | `SplineComponent`, `SplineFollower` (Volume 4 §1.6) |

(`UIElement` is the shared base for the UI widgets, not a registered type.) Mods add more at runtime. See
Volume 4.

Several components carry **runtime-only state** next to their persisted fields: an agent's corner list and
velocity, a follower's distance and resolved spline, a particle pool. That state is not annotated, so it is never
saved. `resetRuntime()` clears it: `NavMeshSystem` and `SplineSystem` call it on their components when Play starts
and stops, and the Play snapshot restore calls it on `ParticleSystem`, `BoneSocket` and the IK components.

`FootIK`, `AimIK` and `LookAtIK` adjust the finished pose after animation, so clips never change. They go on the
entity with the `AnimatorComponent`, and they only touch a pose animation produced this frame: a stopped animator is
left alone, so a script that takes over a skeleton gets exactly the frames it evaluates. `FootIK` needs the entity's
origin at the soles. It probes under each ankle and toe, drops the pelvis so the lower leg reaches, and eases out the
jump a Character Controller makes when it steps up a stair. `LookAtIK` and `AimIK` turn a comma-separated bone chain
so one bone points at `targetEntity` or a `setTargetPoint` position. Their axis is the direction to point, as it lies
in the bind pose. Past `maxAngle` they stop turning, and 40 degrees further on they ease back to the clip.

`BoneSocket` holds its entity on a bone of a rigged entity: world = target world × bone model × offset. Set
`targetEntity` (blank means the parent), `boneName` and the offset, which is in the bone's space. It runs after
animation, so the entity never trails the pose, which a script can't manage since scripts run before animation.
While `active`, it owns the entity's position and rotation, and scripts attach and drop by flipping that flag. It only
runs in Play, so the Transform you author in the editor is left alone.

---

## 3. Janino Script Engine

### 3.1 Why Janino

Gameplay iteration dies when every tweak means "rebuild, relaunch, navigate back to the bug." Janino compiles plain
Java source **in memory**, in milliseconds, with no `javac` process and no `.class` files on disk. You save a script
in your editor of choice and the running game picks it up.

### 3.2 Writing a Script

```java
public class RotateObjectScript extends EntityScript {
    private float rotationSpeed = 60.0f;   // degrees per second; overridable per-entity from the Inspector

    @Override public void onUpdate(float deltaTime) {
        rotate(0.0f, rotationSpeed * deltaTime, 0.0f);
    }
}
```

The lifecycle matches what Unity and Minecraft modders already know:

`onAwake → onEnable → onStart → onUpdate → onLateUpdate → onPhysicsUpdate (60 Hz) → onDisable → onDestroy`

Plus `onCollision{Enter,Stay,Exit}`, `onTrigger{Enter,Stay,Exit}`, `onMouse{Enter,Exit,Down,Up}` and `onDrawGizmos`.

> **Candid note: not every hook is dispatched yet.** `EntityScript` declares all of the callbacks above, but the
> engine currently calls only these:
>
> | Called by the engine | When |
> |----------------------|------|
> | `onAwake` | From `init()`, when the instance is created |
> | `onEnable` | Before `onStart`, and on the next tick after `ScriptComponent.enabled` turns back on |
> | `onStart` | Right after `init()` and the Inspector overrides are applied |
> | `onUpdate` | Every simulation frame (after the framework's `onFrameworkUpdate`) |
> | `onLateUpdate` | Every simulation frame, after physics, every `onUpdate`, animation, IK and bone sockets, and before camera shake is applied and this frame's key/button presses clear. Use it for follow cameras and anything that must see final poses |
> | `onPhysicsUpdate` | Every fixed 60 Hz physics step |
> | `onCollision*` / `onTrigger*` | From the physics contact pass |
> | `onDisable` | On the next tick after `enabled` turns off, and before `onDestroy`. A script that threw doesn't get it |
> | `onDestroy` | On Stop, on deferred destroy, and on hot reload |
>
> `onMouse{Enter,Exit,Down,Up}` and `onDrawGizmos` are declared but **never invoked**. You can override them
> without errors, but they won't run. Draw debug shapes with `ScriptDebug` (Volume 6 §4.3).

`EntityScript` also has ready-made helpers that every script inherits:

| Group | Methods |
|-------|---------|
| Transform | `getPositionX/Y/Z`, `setPosition`, `translate`, `getRotationX/Y/Z`, `setRotation`, `rotate`, `getScaleX/Y/Z`, `setScale`, `getForward/Right/Up` (plus `float[] out` overloads for `getForward` / `getRight`) |
| Components | `getComponent`, `hasComponent`, `getOrAddComponent`, and typed getters (`getTransform`, `getRigidBody`, `getMeshFilter`, `getMaterial`, `getLight`, `getCamera`, `getAudioSource`, `getCharacterController`, `getVehicleController`, `getAnimator`) |
| Audio | `playSound(path)`, `playSound(path, volume, spatial)`, `playSound(path, volume, spatial, AudioBus, ducksBgm)`, `playSoundAt(path, volume, x, y, z[, AudioBus])`, `stopAllSounds()` |
| Physics | `raycast(...)` (convenience and `RaycastHit` out-parameter overloads) |
| Haptics | `vibrate(low, high, duration)`, `stopVibration()` |
| Scenes & logging | `loadScene(path)` (queued, §1.4.1), `logInfo` / `logWarning` / `logError` |

### 3.3 Hot Reloading

1. The `asset-folder-watcher` virtual thread sees a `.java` change.
2. `ScriptEngine.invalidateScript(path)` drops the cached class and **queues** the reload. It never swaps
   mid-frame.
3. At the next script tick, the class is recompiled **once** and every live instance is replaced:
   old `onDestroy()` → new `init()` → property overrides re-bound → new `onStart()`.
4. If the new source doesn't compile, **the old version keeps running** and the error goes to the console.

### 3.4 Zero-Allocation Tick Callbacks

`updateScripts` indexes the live entity and component lists directly. It does not use iterators or
`Entity.getComponents()`, which would build a new list. Two benefits:

- No per-frame garbage from the script dispatcher itself.
- A script that spawns entities or adds components mid-tick can't trigger a `ConcurrentModificationException`, and
  newly spawned entities are picked up in the same pass.

**Fault isolation:** a script that throws is disabled *by itself*, and the console gets a trimmed stack trace.
Every other script keeps running. The `enabled` flag is part of the Play snapshot, so pressing Stop brings it back.

> Your scripts can still allocate. The engine keeps its own dispatch allocation-free, but a `new Vector3()` inside
> `onUpdate` is on you. Cache scratch objects in fields.

---

## 4. Audio Runtime

Audio is a core runtime service like streaming or scripting. Like every other hot path, the per-frame audio
update runs on preallocated primitive arrays.

### 4.1 `AudioEngine`: OpenAL Device, Buffers, One-Shots

- **Lazy and optional.** The OpenAL device and context open on first use. With no audio device, every call is a
  silent no-op, so headless servers and CI machines run unchanged.
- **Decoding.** OpenAL only accepts raw PCM. `.ogg` is decoded with STBVorbis, and `.wav` / `.aiff` / `.au` with
  `javax.sound`. Decoded buffers are cached per path.
- **Mono for 3D.** OpenAL only spatializes mono buffers, so spatial requests are downmixed to mono at load time.
  Non-spatial loads keep stereo.
- **One-shot pool.** Fire-and-forget sounds (`playSound`, `playSpatialSound`, …) use a fixed pool of
  **32** sources. `updateOneShots()` reapplies each one's bus gain every frame, reports ducking triggers and attaches
  reverb sends.
- **Play-mode integration.** Pause pauses the one-shots and every `AudioSource` the pause button stopped, and Resume
  restarts only those (not sources a script paused itself). Stop stops all one-shots and resets the mixer's runtime
  layer and the reverb state. `AudioSource`s with **PLAY ON START** start *before* scripts' `onAwake` / `onStart`,
  so a script can stop or retune them.

### 4.2 Mixer Buses: `AudioBus` & `AudioMixerService`

Every sound is routed through one of four buses: `MASTER`, `BGM`, `SFX`, `VOICE`. Every bus feeds `MASTER`:

```
final gain = clipVolume × bus × MASTER        (BGM additionally × the ducking envelope)
```

Two layers are multiplied for each bus:

| Layer | Set by | Lifetime |
|-------|--------|----------|
| **Project mix** | *Project Settings → Audio* (Volume 2 §1.7), saved in the project file (`<Name>.rproj`) | Persistent; copied into the mixer every frame |
| **Runtime mix** | Scripts: `setBusVolume(bus, v)` (0..1) / `setBusMuted(bus, m)` | Reset to unity **when Play stops**, so a script can't leave the editor muted |

**Ducking.** While any `VOICE` clip, or any clip flagged **DUCKS BGM** (`AudioSource.ducksBgm`,
`playCriticalSound`), is audible, the BGM bus eases toward `duckLevel` over `duckAttack` seconds and back to full
over `duckRelease` seconds once those clips stop. The project defaults are 0.35, 0.15 s and 0.6 s. A BGM clip never
ducks itself.

`AudioBus` names are persisted (`AudioSource.bus`, the `ProjectSettings` ordinals), so **only append** new buses.

### 4.3 Spatial Reverb: `AudioReverbZone` & `AudioReverbSystem`

An `AudioReverbZone` is a box (axis-aligned, so entity rotation is ignored) or sphere centered on the entity's world
position and scaled by its world scale. While the listener, or a sound emitter, is inside one, an OpenAL **EFX
reverb** is applied:

| Field | Meaning |
|-------|---------|
| `shape` | `BOX` (`sizeX/Y/Z`) or `SPHERE` (`radius`) |
| `reverbPreset` | `CAVE`, `ROOM`, `HALL`, `OUTDOOR` |
| `decayTime` | Seconds. `0` uses the preset's own decay |
| `wetLevel` / `dryLevel` | Reverb send level and direct-path level, 0..1 |
| `priority` | Where zones overlap, the higher priority wins, then the **smaller** zone |
| `active` | Off = ignored |

Per frame: `beginFrame` clears the zone list, `SimulationSystem` adds every active zone at its world pose, and
`resolve` picks the zone around the listener. If the listener is outside every zone, it uses the zone that held an
emitter last frame. Wet and dry levels ease toward the target instead of snapping. A source is routed into the
reverb when the listener is in the active zone or the source itself is. Effect parameters are re-uploaded only when
the zone or its settings change.

Without `ALC_EXT_EFX`, or with **REVERB ZONES** turned off in Project Settings, the whole system is a no-op.
