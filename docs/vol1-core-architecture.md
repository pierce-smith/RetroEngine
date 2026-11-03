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
| `--headless`            | Dedicated server      | `RuntimeLoop` | No           |
| `--vulkan`              | Force Vulkan backend  | *(modifier)*  | MoltenVK on macOS |

Two things happen before any engine code runs, and both came from production failures:

```java
// LWJGL's default 64 KB MemoryStack overflows inside new VkInstance(...) on Windows NVIDIA drivers.
Configuration.STACK_SIZE.set(1024);

if (Boolean.getBoolean("retro.packaged")) {
    redirectOutputToLogFile(...);   // see Volume 5: OS logging
}
```

`main` builds one `EngineContext`, initializes it for the selected mode, runs the loop, and always calls
`context.shutdown()` in a `finally` block, so a fatal crash still releases GPU resources.

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

In `--headless` mode the loop never touches a window or GPU. It runs a fixed-rate simulation tick and sleeps off
the rest, which is the starting point for a dedicated server.

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

> **Candid note:** entity IDs are still `String`s and the sparse map is a `HashMap<String,Integer>`. Lookups by ID
> are not free. Dense iteration is the fast path, so write systems against it.

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
`STRING`, `READ_ONLY`) and `ValueKind` (`FLOAT`, `INT`, `BOOLEAN`, `ENUM`, `ASSET_PATH`, `STRING`, `STRING_MAP`, …).
That one descriptor drives the Inspector UI, `ComponentBinaryCodec` serialization, prefab saving, Play-mode
snapshots, and `.rpatch` property matching. Add a field and every one of those systems picks it up.

`@Component.LegacyNames` keeps old scenes loading after a component is renamed.

### 2.4 The 19 Built-In Component Types

| Category    | Components |
|-------------|------------|
| Core        | `Transform`, `MeshFilter`, `Material`, `Light`, `Camera`, `AudioSource`, `ScriptComponent` |
| Physics     | `RigidBodyComponent`, `BoxCollider`, `SphereCollider`, `CapsuleCollider` |
| Controllers | `CharacterControllerComponent`, `VehicleControllerComponent` |
| Animation   | `AnimatorComponent` |
| Game UI     | `UICanvasComponent`, `UIPanel`, `UIText`, `UIImage`, `UIProgressBar` |

(`UIElement` is the shared base for the UI widgets, not a registered type.) Mods add more at runtime. See
Volume 4.

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
