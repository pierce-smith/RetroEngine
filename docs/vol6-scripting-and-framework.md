# Volume 6: Scripting Engine & Logic Framework

> **Janino in-memory compilation, zero-allocation callbacks, and the `SimpleEntityScript` DSL.**
> Volume 1 explained how a script gets compiled and ticked. This volume covers what you write *inside* one: the
> framework that turns a bag of ECS components into gameplay, without giving up the engine's no-garbage rule.

---

## Design Philosophy

RenderWare never shipped a gameplay language. It gave studios a solid core and left the top layer to them, and
nearly every studio ended up writing the same helpers: "move this thing", "wait two seconds", "tell the HUD". Gamebryo
and Creation Engine went the other way. Papyrus made the top layer a first-class part of the engine, and modders
built a decade of content on it. Minecraft showed that this top layer can be plain Java, as long as the engine keeps
it fast.

The scripting framework (`com.retro.engine.scripting.framework`) is RetroEngine's version of that top layer. It sits
between the low-level ECS systems (Volume 1) and the people writing game logic, who are often modders who have never
opened `PhysicsSystem.java`.

### Friction in the Raw ECS API

`EntityScript` gives you everything, but in its rawest form. Under Janino (§1.2) it also makes you spell out every
type yourself:

```java
// Raw EntityScript: correct, but every line is bookkeeping
Transform t = (Transform) entity.getComponent(Transform.class);          // cast required under Janino
RigidBodyComponent rb = (RigidBodyComponent) entity.getComponent(RigidBodyComponent.class);
if (t != null && rb != null) { ... }
```

That's a component lookup and a cast on every call. Most new script authors end up doing it every frame, because
nothing tells them not to.

`SimpleEntityScript` swaps that for **cached, concretely typed getters**: `transform()`, `rigidbody()`, `light()`.
The first call does the lookup and later calls return a field. No casts, no repeated lookups, and the cache notices
when components are added or removed.

### The Rule That Doesn't Bend

> **Anything a script calls every frame or every physics step must not allocate.**

The physics solver steps at a fixed 60 Hz (Volume 4) and the frame loop targets a flat 16.67 ms line (Volume 5).
Every service in this volume (timers, tweens, events, input, debug drawing, camera shake) runs on preallocated arrays
and int handles. The few calls that *do* allocate (spawning prefabs, writing save data, scanning the scene by name)
say so in their Javadoc and in the tables below. Call them on events, not in `onUpdate`.

---

## 1. The Janino Runtime & Compilation Constraints

### 1.1 How a Script Becomes a Class

RetroEngine compiles scripts with **Janino 3.1.12** (`org.codehaus.janino:janino`). Janino is a Java compiler
written in Java that runs inside the engine process. It doesn't start `javac` and doesn't write `.class` files to
disk.

```java
SimpleCompiler compiler = new SimpleCompiler();
ClassLoader parent = scriptParentLoader();            // mod class-loader chain, else the engine's own loader
compiler.setParentClassLoader(parent);
thread.setContextClassLoader(parent);                 // Janino resolves some types through the context loader
compiler.cook(scriptFile.getAbsolutePath(), reader);  // parse + compile to bytecode, in memory
Class<?> c = compiler.getClassLoader().loadClass(name);
```

`ScriptEngine.compileAndLoadJavaFile` runs this once per script source:

1. **Locate** the source. The lookup order is: explicit `scriptAssetPath`, the raw name, `<project>/Assets/Scripts/`,
   anywhere under `<project>/Assets/`, then the working directory's `Assets/Scripts/`.
2. **Read the `package`** declaration so the right fully qualified name can be loaded.
3. **Cook** the source under an explicit parent class loader.
4. **Cache** the class in `compiledClassCache`. A failed compile goes into `failedScriptCache`, so a broken script
   isn't recompiled and re-logged every frame. The component is disabled until Play stops.

> **War story: the context class loader.** By default Janino resolves types through the thread's *context* class
> loader. In `gradle run` that loader can see every engine class. In a packaged app it may not, and a script that
> compiles in the editor can then fail to resolve `EntityScript` in the shipped game. The fix is the two explicit
> lines above. The engine never relies on Janino's defaults.

### 1.2 What Janino Doesn't Support

Janino covers most of Java, but not all of it. These are the gaps that affect gameplay code:

| Feature                       | Janino 3.1.12 | What to write instead |
|-------------------------------|---------------|-----------------------|
| Lambdas `() -> ...`           | ✗             | Anonymous class: `new ScriptTask() { public void run() { ... } }` |
| Method references `this::foo` | ✗             | Anonymous class or named inner class |
| Generic type inference        | ✗ (erased)    | Cast explicitly: `(BoxCollider) e.getComponent(BoxCollider.class)` |
| For-each over `List<Entity>`  | ✗             | Index loop with a cast: `Entity e = (Entity) list.get(i);` |
| Varargs call sugar            | Avoid         | Pass arrays explicitly, as the bundled scripts do: `new Class[] { int.class }` |
| Anonymous & inner classes     | ✓             | The main tool for callbacks and FSM states (§3) |
| Referencing another script's class | ✗ (isolated, §1.3) | `ScriptEvents` (§1.4) |

> **War story: the javac false positive.** Janino reads generic signatures and then drops the type arguments.
> `getComponent(X.class)` returns plain `Component`, and `for (Entity e : list)` won't compile. `javac` accepts
> both, so a "does every template script compile?" check built on `javac` reported the Starter Game template scripts
> as clean while Janino rejected them. (That template has since been replaced by **First Person Example** and
> **Infinite Driver**, Volume 2 §1.9. The rule applies to their scripts just the same.) **Check scripts by cooking them with
> `org.codehaus.janino.SimpleCompiler` against the real runtime classpath, never with `javac`.**

The framework is designed with these gaps in mind. Every `SimpleEntityScript` getter returns a concrete type, every
callback is a single-method interface (`ScriptTask`, `ScriptEventListener`), and every state is a plain abstract
class (`ScriptState`).

### 1.3 Class-Loader Isolation

**Each script source gets its own `SimpleCompiler`, so each script class lives in its own class loader.**

```
Bootstrap / platform
  └── Engine class loader             EntityScript, SimpleEntityScript, ScriptEvents, Transform, …
        └── Mod class-loader chain    mod components & ModScripts (Volume 4), when --mods is active
              ├── Janino loader A     PlayerCombatScript
              ├── Janino loader B     GameManagerScript
              └── Janino loader C     CollectibleScript
```

Here's what follows from that:

- **Every script can see the engine and loaded mods.** Mod component classes can be used directly.
- **Scripts can't see each other.** Loader A has no idea `GameManagerScript` exists, so
  `((GameManagerScript) other).addScore(1)` doesn't compile. This is deliberate: one broken script never stops another
  from loading.
- **Framework statics are shared.** `ScriptEvents`, `ScriptInput`, `ScriptSaveData` and `ScriptDebug` live in the
  engine loader, so all scripts see the same instance. That shared layer is how scripts talk to each other.

### 1.4 `ScriptEvents`: Talking Across the Boundary

Before the framework existed, scripts talked to each other through reflection. This is the old `CollectibleScript`:

```java
// BEFORE: brittle, allocating, and silently breaks when GameManager is renamed
Object inst = sc.activeScriptInstance;
inst.getClass().getMethod("addScore", new Class[] { int.class })
    .invoke(inst, new Object[] { Integer.valueOf(points) });       // boxing + Object[] on every pickup
```

Now it publishes an event, and anything that cares subscribes to it:

```java
// Publisher (CollectibleScript)
publishEvent("collectible_collected", null, points);       // source = this entity, text = null, number = points

// Subscriber (GameManagerScript)
public class GameManagerScript extends SimpleEntityScript implements ScriptEventListener {
    public void onStart() { subscribeEvents(this); }       // auto-unsubscribed when this script is destroyed

    public void onScriptEvent(String eventName, Entity source, String text, float number) {
        if ("collectible_collected".equals(eventName)) addScore((int) number);
    }
}
```

`ScriptEventListener` is an engine interface, so every Janino loader sees the same type. The payload is
`(eventName, source, text, number)` as plain arguments, and there is **no event object to allocate**.

| Behavior | Detail |
|----------|--------|
| Delivery | Synchronous, main thread, subscription order. Every listener gets every event and filters by name. |
| Allocation | None per publish. The listener array grows (by doubling) only when you subscribe more listeners than it holds. |
| Re-entrancy | Subscribe, unsubscribe and publish are all safe inside a callback. Unsubscribing nulls the slot, and the list is compacted when the outermost `publish` returns. Listeners added during a publish start with the next event. |
| Fault isolation | A listener that throws is logged and skipped. The other listeners still get the event. |
| Lifetime | `subscribeEvents(listener)` ties the subscription to the script, which unsubscribes on destroy or hot reload. All subscriptions are dropped when Play stops. |

> Use `public static final String` constants for event names (`CollectibleScript.EVENT_COLLECTED`), but remember
> that another script can't import them (§1.3). Copy the literal and keep a single list in your project notes.

### 1.5 Hot Reload During Play

Volume 1 §3.3 covers how the reload is queued. These are the parts that matter for the framework:

```
file saved → asset-folder-watcher → ScriptEngine.invalidateScript(path)   (queued, never mid-frame)
next script tick → recompile once → for each live instance:
    old.onDestroy() → old.onFrameworkDestroy() → new.init() → overrides re-bound → new.onStart()
```

- `onFrameworkDestroy()` **cancels the old instance's timers and tweens and unsubscribes its listeners.** A reload
  never leaves a dead class's `ScriptTask` firing, or a stale listener receiving events.
- **Instance state resets.** Fields go back to their initializers and Inspector overrides are re-applied. If a value
  has to survive a reload, keep it in a component, in `ScriptSaveData`, or recompute it in `onStart`.
- **Static fields of the script class reset too**, because the reloaded class is a new class in a new loader.
- **A failed compile keeps the old version running.** The error goes to the console.

---

## 2. `SimpleEntityScript` Architecture & Spatial DSL

### 2.1 Lifecycle Wiring

`SimpleEntityScript extends EntityScript` and adds two engine hooks that `ScriptEngine` calls on every script:

| Hook | When | What the framework does |
|------|------|-------------------------|
| `init(entity, world)` | Instantiation | Binds the `ComponentCache` **before** `onAwake`, so the getters work there |
| `onFrameworkUpdate(dt)` | Every frame, just **before** `onUpdate` | Ticks `tasks` and `tweens` |
| `onFrameworkDestroy()` | Right **after** `onDestroy` (and on hot reload) | Cancels all tasks and tweens, unsubscribes owned listeners |

You don't need to call `super.onUpdate()`. Override `onUpdate` and the framework still ticks.

> Inspector property overrides are injected before `init()`, so `onAwake` already sees Inspector-tuned values.

### 2.2 The `ComponentCache` Model

```java
public Transform transform() {
    if (!validate()) return null;                         // unbound → null
    if (transform == null) transform = entity.getTransform();
    return transform;                                     // every later call: one field read
}

private boolean validate() {
    if (entity == null) return false;                     // not bound yet
    int n = entity.getAllComponents().size();
    if (n != componentCount) { invalidate(); componentCount = n; }   // component added/removed → drop refs
    return true;
}
```

| Accessor | Returns | Notes |
|----------|---------|-------|
| `transform()` | `Transform` | |
| `rigidbody()` | `RigidBodyComponent` | The component (authoring data). The live simulation body is reached through `setVelocity` etc. |
| `collider()` | `Component` | First of box → sphere → capsule. Use the typed accessors below for fields. |
| `boxCollider()` / `sphereCollider()` / `capsuleCollider()` | concrete type | |
| `animator()` | `AnimatorComponent` | |
| `light()` | `Light` | |
| `camera()` | `Camera` | Also changes `lookAt` / `turnTowards` conventions (§2.4) |
| `audio()` | `AudioSource` | |
| `material()` / `meshFilter()` | concrete type | |
| `characterController()` / `vehicleController()` | concrete type | |
| `particleSystem()` | `ParticleSystem` | Used by `play()` / `stop()` / `emit()` (§2.6) |
| `navMeshAgent()` | `NavMeshAgent` | Used by `setDestination` and the other navigation helpers (§2.6) |

How the cache behaves:

- **Lazy.** Nothing is looked up until you ask for it.
- **Misses aren't cached.** If a component is missing, the getter returns `null` and checks again on the next call, so
  a component added later is found.
- **Self-invalidating.** If the entity's component *count* changes, every cached reference is dropped.
- **Manual escape hatch.** If you remove one component and add another in the same frame (same count, different
  object), call `invalidateComponentCache()`.

### 2.3 Side by Side: Verbose vs. Clean

The same behavior written both ways: bob up and down, face the player, and push away from them on contact.

```java
// ---- EntityScript ----------------------------------------------------------------------------
public class BobberScript extends EntityScript {
    public float bobHeight = 0.25f;
    private float time, baseY;
    private Entity player;

    public void onStart() {
        baseY = getPositionY();
        java.util.List all = sceneWorld.getSceneEntities();
        for (int i = 0; i < all.size(); i++) {                               // no for-each under Janino
            Entity e = (Entity) all.get(i);
            if (e.getComponent(CharacterControllerComponent.class) != null) { player = e; break; }
        }
    }

    public void onUpdate(float dt) {
        time += dt;
        Transform t = (Transform) entity.getComponent(Transform.class);    // lookup + cast, every frame
        t.posY = baseY + (float) Math.sin(time * 6.28f) * bobHeight;
        if (player != null) {
            Transform pt = (Transform) player.getComponent(Transform.class);
            float dx = pt.posX - t.posX, dz = pt.posZ - t.posZ;            // local, not world, position
            t.rotY = (float) Math.toDegrees(Math.atan2(-dx, dz));          // snaps; no turn speed
        }
    }
}

// ---- SimpleEntityScript ----------------------------------------------------------------------
public class BobberScript extends SimpleEntityScript {
    public float bobHeight = 0.25f;
    public float turnSpeed = 180.0f;
    private float time, bobOffset;
    private Entity player;

    public void onStart() { player = findPlayer("Player"); }               // scan once, cache

    public void onUpdate(float dt) {
        time += dt;
        float offset = (float) Math.sin(time * 6.28f) * bobHeight;
        move(0.0f, offset - bobOffset, 0.0f);                                // dy = world up
        bobOffset = offset;
        if (player != null) lookAtFlat(player);                              // world-space, parent-aware
    }
}
```

The second version is about half as long. It also uses **world positions** (so it still works when the entity is
parented under a moving platform), it's correct for cameras, and it does no per-frame lookups.

### 2.4 Spatial Helpers

| Method | Space | Behavior |
|--------|-------|----------|
| `translate(dx, dy, dz)` | World axes | Inherited from `EntityScript`. Adds to the local position. |
| `rotate(drx, dry, drz)` | Euler degrees | Inherited. Adds to the local rotation. |
| `move(dx, dy, dz)` | **Facing-relative** | `dx` along the right vector, `dy` along **world up**, `dz` along forward (for cameras, the view direction). |
| `moveInPlace(Vector3)` | Facing-relative | `move` taking a reusable vector (x = right, y = up, z = forward). |
| `lookAt(Entity)` / `lookAt(x, y, z)` | World | Sets pitch and yaw so forward points at the target. Keeps roll. |
| `lookAtFlat(Entity)` | World, yaw only | For characters that must stay upright. |
| `turnTowards(x, z, maxDeg)` | World, yaw only | Turns at most `maxDeg` this call and **returns the remaining angle**. Pass `speed * dt`. |
| `getWorldPosition(out)` / `getWorldX/Y/Z()` | World | Includes parents, read from `SceneWorld`'s computed world transforms. |
| `getDistanceTo(e)` / `getFlatDistanceTo(e)` | World | `Float.MAX_VALUE` for `null`. The flat version ignores height. |
| `getDistanceTo(x, y, z)` | World | Distance from this entity's world position to a point. |
| `findEntity(name)` | Scene scan | First entity with that name. **Scans the scene, so cache the result.** |
| `findPlayer(name)` | Scene scan | Entity named `name`, else the first entity with a `CharacterController`. **Scans the scene, so cache the result.** |
| `isPlayer(other, name)` | No scan | The same test for one entity. Use it in trigger callbacks. |

**Forward conventions.** Models face **+Z**. Cameras look down **−Z**. `lookAt` and `turnTowards` check
`camera()` and pick the right formula, so the same call aims a turret mesh or a security camera. Both subtract the
parent's world pitch and yaw, which makes them exact for root entities and for children whose parents only yaw or
pitch.

**Physics shortcuts.** `move` and `translate` write the `Transform` directly. That's correct for kinematic props
and wrong for anything the solver owns:

```java
applyImpulse(0, 6.0f, 0);          // velocity += impulse / mass on a DYNAMIC body; false if there isn't one
setVelocity(vx, vy, vz);           // overwrite linear velocity (m/s); keeps gravity & collisions working
getVelocity(scratch);              // read into a reusable Vector3
raycastInPlace(20f, hit);          // from world position along forward, ignoring self, into a reused RaycastHit
```

> **Player movement belongs to `CharacterController`.** Don't `move()` a player or `setVelocity()` a
> character. Feed the controller from input and let its ground probe, step snapping and friction (Volume 4 §1.3)
> do the work. The same goes for cars and `VehicleController`.

### 2.5 Entity Lifecycle & Spawning

#### Deferred destruction

```java
destroySelf();                  // this entity + all descendants, at end of frame
destroy(otherEntity);
if (isBeingDestroyed()) return; // queued this frame and not yet removed
```

`SceneWorld.requestEntityDestroy` **queues** the entity. Nothing is removed while scripts or physics are
iterating. The queue is flushed after the simulation step:

```
ScriptRuntime.beginFrame   → strip last frame's camera shake, age debug lines
navigation + splines       → agents steer, followers advance (before physics, Volume 1 §1.4.1)
physics + scripts          → destroy() calls land in pendingDestroys
audio + particles          → spatial audio sync, particle simulation
flushPendingDestroys()     → breadth-first collect descendants
                             → onDestroy + onFrameworkDestroy for each script
                             → PhysicsSystem.removeRuntimeEntity (bodies list + BVH leaf)
                             → deleteEntity, children before parents
ScriptRuntime.endFrame     → apply this frame's camera shake
render
```

- **Safe from any callback**, including `onTriggerEnter` in the middle of a contact pass.
- **No orphans.** Destroying a parent takes its whole subtree with it.
- **Stop restores it.** Destruction is a Play-mode change like any other, so the Play snapshot brings the entity back.
- Outside Play, `requestEntityDestroy` deletes immediately. No frame is running, so there's nothing to defer.

#### Mid-play prefab instantiation

```java
Entity coin = spawnPrefab("Prefabs/Coin.prefab.bin", x, y, z);   // returns the root, or null
Entity fx   = spawnPrefab("Prefabs/Spark.prefab.bin");          // overload: spawn at the prefab's saved position
```

`SceneWorld.instantiatePrefabRuntime` was written for spawning during gameplay, not for the editor's "drag prefab
into scene":

| Step | Behavior |
|------|----------|
| Path | Resolved through the project `AssetDatabase` / mod VFS (Volume 4 §2.1), so mods can override the prefab. |
| Selection | The editor selection is saved and restored, and the editor spawn sound doesn't play. |
| Mods | `ModLoader.onPrefabSpawned` runs, so `EntitySpawnEvent` veto handlers still apply. |
| Physics | Each spawned physics entity is passed to `PhysicsSystem.registerRuntimeEntity`. **The body is created at the start of the next physics step**, so no solver loop sees the body list change while it iterates. |
| Scripts | Scripts on the spawned entities are instantiated by the script tick (in the same pass if the new entity was appended after the spawner, otherwise on the next frame). |

> **Cost:** this reads the prefab from disk and allocates entities and components. It's fine for a pickup
> dropping or a wave spawning. It isn't fine 60 times a second. For bullets, pool a fixed set of entities and
> re-position them.

### 2.6 Gameplay System Shortcuts

Newer engine systems (Volume 1 §1.4.1) each come with a small set of `SimpleEntityScript` helpers. They all work
on **this entity's** component through the `ComponentCache`. Without the component they do nothing and return a
neutral default, so a script can be attached to entities with or without it. None of them allocate.

#### Navigation (`NavMeshAgent`, Volume 4 §1.5)

```java
setDestination(playerPos);                 // or (x, y, z). Planned on the next navigation tick
if (isPathPending()) return;               // true until that tick runs
if (!hasPath()) { /* arrived, stopped, or no path */ }
float left = remainingDistance();          // +∞ while pending, 0 with no path
stopNavigation();                          // decelerate to a stop
```

`stop()` already means "stop particle emission" (below), so the navigation version is `stopNavigation()`. Re-send
the destination only when the target has actually moved. The First Person Example's `ChaserScript` re-paths when
the player has moved more than `repathDistance`, instead of every frame.

#### Splines (`SplineFollower`, Volume 4 §1.6)

| Method | Behavior |
|--------|----------|
| `followSpline(spline, speed)` | Starts moving along `spline` at `speed` world units/s. Adds a `SplineFollower` (wrap mode `LOOP`) if the entity has none. That one-time `addComponent` is the only allocation |
| `getSplineProgress()` / `setSplineProgress(t)` | Normalized arc-length progress, 0..1. A jump is applied on the next spline tick |
| `pauseSpline()` / `resumeSpline()` | Hold position, or continue (also after a `CLAMP` follower reaches the end) |

#### Particles (`ParticleSystem`, Volume 3 §2.4)

`play()` starts continuous emission. `stop()` stops it, and live particles finish their lifetime. `emit(count)`
spawns a burst on the next simulation step, even while stopped. Particles are simulated after scripts, so calls made
in `onUpdate` take effect the same frame.

#### Decals (Volume 3 §2.4a)

`spawnDecal` is on `EntityScript`. It stamps an image onto the meshes at a point, and the mark stays on them as they
move or animate. Decals are cleared when Play stops.

```java
private final RaycastHit hit = new RaycastHit();

if (raycast(eyeX, eyeY, eyeZ, dirX, dirY, dirZ, 100.0f, hit)) {
    spawnDecal(DecalTextures.BULLET_HOLE, hit, 0.15f);   // texture, where, width. Rotation is random
}
```

For anything else, fill a `DecalSpec`. It is copied on spawn, so keep one per weapon and reuse it:

```java
DecalSpec blood = new DecalSpec(DecalTextures.BLOOD, 0.8f);
blood.setHit(hit);
blood.target = hit.entity;     // only this entity. blood.exclude skips one instead
blood.depth = 1.0f;            // colliders sit off the skin, so reach further on characters
blood.roughness = 0.25f;       // wet
blood.lifetime = 30.0f;        // seconds; 0 = until the 256-decal budget evicts it
blood.growTime = 4.0f;         // seconds to spread out from nothing; 0 = appears at full size
spawnDecal(blood);
```

A capsule hit lands on the capsule, not the body inside it, so a small box can miss the mesh. Raise `depth` for characters.
If the mesh is on a child of the entity that owns the collider, set `target` to that child, not `hit.entity`.
Scripts import `com.retro.engine.graphics.DecalSpec` and `DecalTextures`.

#### Animation (`AnimatorComponent`, Volume 3 §2.2.1)

```java
crossFadeAnimation("Run", 0.2f);   // blend over 0.2 s. Safe to call every frame
playAnimation("Idle");             // switch instantly
setAnimationSpeed(0.5f);           // playback multiplier: 1 = authored, 0 = frozen
```

`crossFadeAnimation` takes a controller state name, or the clip a single-clip state plays, and returns `false` if
there's no animator or no such clip. For an animator on a **child** entity (FPS arms under the camera, for
example), call `animator.crossFade(name, duration)` on that `AnimatorComponent` directly, as
`PlayerCombatScript` does.

#### Player Control (cutscenes)

```java
setPlayerInputEnabled(false);      // every CharacterController ignores move/look/jump input
...
setPlayerInputEnabled(true);
boolean free = isPlayerInputEnabled();
```

Physics keeps running while input is off, so the player still falls and collides. Both calls scan the scene, which
doesn't allocate but isn't free, so call them at the start and end of a cutscene, not every frame. Stop always
turns input back on.

**Putting it together: `OpeningCutsceneScript`** (First Person Example). A `CutsceneCamera` entity has a `Camera`
and a `SplineFollower` that rides a `CutsceneTrack` spline. In `onStart` the script turns player input off and makes
its camera the primary one. While the follower carries the camera along the track, the script keeps it aimed at a
focus point. When `getSplineProgress() >= 1` (or a safety timer runs out, or the player presses Space / Enter), it
eases into the player camera's pose over `blendTime` seconds, hands the primary camera back, turns input back on,
and calls `destroySelf()`. Everything it needs is resolved or allocated once in `onStart`.

---

## 3. Allocation-Free Timers, Tweens & State Machines

### 3.1 Why Not `java.util.Timer`, Threads, or Closures?

| Tempting tool | What goes wrong in a game loop |
|---------------|-------------------------------|
| `java.util.Timer` / `ScheduledExecutorService` | Fires on **another thread**. Touching ECS state from it races the frame (Volume 1 §1.5: workers produce, the main thread consumes). It also runs on wall-clock time, so it keeps going while the game is paused. |
| `Thread.sleep` in a script | Blocks the **frame thread**. Every entity freezes. |
| `CompletableFuture.delayedExecutor` | Same threading problem, plus a task object and a future per call. |
| A new anonymous class per call | One allocation every time you schedule. If that happens in `onUpdate`, it's garbage every frame. |
| `float timer; timer -= dt;` fields | Works, but becomes unmanageable at five timers, and nothing cleans them up on destroy. |

The framework's answer: **game-time schedulers on the main thread, using pooled slots and int handles, ticked by
the framework, and cleaned up automatically.**

### 3.2 `ScriptTaskQueue`

```java
private ScriptTask closeDoor;                      // create once…
private int closeHandle;

public void onStart() {
    closeDoor = new ScriptTask() { public void run() { close(); } };
}

public void onTriggerEnter(Entity other) {
    open();
    cancelTask(closeHandle);                       // stale/invalid handles are ignored safely
    closeHandle = runAfter(3.0f, closeDoor);       // …reuse forever
}
```

| API | Behavior |
|-----|----------|
| `runAfter(delay, task)` | Runs once after `delay` seconds of game time (`0` = next tick). |
| `runEvery(interval, task)` | Repeats. The first run is one interval from now. Fires **at most once per tick**. |
| `tasks.runEvery(interval, initialDelay, task)` | Repeats, with its own first delay. Useful for staggering many enemies. |
| `cancelTask(handle)` / `tasks.cancel(h)` | Returns `false` if the handle is stale. |
| `tasks.isScheduled(h)`, `getTimeRemaining(h)`, `restart(h, delay)` | Inspect or reset a pending timer. |

**Internals.**

- **Parallel arrays instead of entry objects:** `ScriptTask[]`, `float[] remaining`, `float[] interval`,
  `boolean[] repeating`, `boolean[] active`, `int[] generation`. There's one allocation per array and none per timer.
- **Generational handles:** `handle = (generation << 16) | slot`. A slot's generation increases when the slot is
  released, so an old handle can never cancel the timer that reuses its slot. This is the same idea as
  `EntityHandle` (Volume 1 §2.2). `0` is never a valid handle.
- **Repeat drift:** a repeating timer carries its overshoot forward, so the average rate holds. It never queues
  catch-up fires after a hitch.
- **Scheduling from inside a task** is safe. The new timer starts counting on the *next* tick.
- **Capacity:** `SimpleEntityScript` creates its queue with 8 slots. The pool doubles when it's full, and that
  growth is the only allocation. The hard cap is 65,536 slots.
- **Exceptions** thrown by a task propagate, and `ScriptEngine` disables that one script, as with any other
  callback.

### 3.3 `ScriptTweener`

```java
int h = tweenPositionTo(0, 3, 0, 1.5f, ScriptTweener.EASE_OUT_BACK);
tweens.setDelay(h, 0.25f);
tweens.setOnComplete(h, openLidTask);

int glow = tweens.tweenLightColor(light(), 1.0f, 0.4f, 0.1f, 3.0f, 0.8f, ScriptTweener.EASE_IN_OUT_SINE);
tweens.setPingPong(glow, true);                    // forever; onComplete never fires
```

| Channel | Start method | Target |
|---------|--------------|--------|
| Position | `tweenPositionTo` / `tweens.tweenPosition(t, …)` | `Transform` local position |
| Rotation | `tweenRotationTo` / `tweens.tweenRotation` | Local Euler degrees, **shortest way around on each axis** |
| Scale | `tweenScaleTo` / `tweens.tweenScale` | Local scale |
| Light color | `tweens.tweenLightColor(light, r, g, b, intensity, …)` | `Light` color + intensity |
| UI color | `tweens.tweenUIColor(element, r, g, b, alpha, …)` | `UIElement` color + alpha |
| Value | `tweens.tweenValue(from, to, …)` | Not bound to anything. Read it with `tweens.getValue(h)`. |

**The 14 easing curves** (`ScriptTweener.*`):

| Family | Constants |
|--------|-----------|
| Linear | `LINEAR`, `SMOOTHSTEP` |
| Quad | `EASE_IN_QUAD`, `EASE_OUT_QUAD`, `EASE_IN_OUT_QUAD` |
| Cubic | `EASE_IN_CUBIC`, `EASE_OUT_CUBIC`, `EASE_IN_OUT_CUBIC` |
| Sine | `EASE_IN_SINE`, `EASE_OUT_SINE`, `EASE_IN_OUT_SINE` |
| Character | `EASE_OUT_BACK` (overshoot), `EASE_OUT_ELASTIC` (spring), `EASE_OUT_BOUNCE` |

Rules the tweener enforces:

- **One tween per target and channel.** Starting a position tween on a `Transform` that already has one replaces
  the old tween. Two tweens never fight over the same property.
- **The start value is sampled when the tween begins**, after its delay, not when you call the method. A delayed
  tween picks up from wherever the object is by then.
- `onComplete` fires **once, after the final value is applied**. It doesn't fire on cancel or replace, and never for
  ping-pong tweens.
- A tween started from inside a completion callback begins on the next tick.
- Storage is `float[4]` per slot for `from`, `to` and `value`, using the same generational handles and doubling
  pool as the task queue. `SimpleEntityScript` starts with 4 slots.

The easing math is also available as static helpers, which you can use in your own update code:

```java
ScriptTweener.ease(EASE_OUT_CUBIC, t);             // shape a 0..1 parameter
ScriptTweener.lerpAngle(a, b, t);                  // shortest arc, degrees
ScriptTweener.deltaAngle(a, b);                    // signed shortest difference, -180..180
ScriptTweener.damp(current, target, 8f, dt);       // frame-rate-independent smoothing (use instead of lerp(…, 0.1f))
```

### 3.4 `ScriptStateMachine`

Enemy AI is where gameplay code usually turns into a mess of `if (state == 2 && !wasAttacking)`. The FSM gives each
state its own class. Under Janino those are **non-static inner classes**, so they can read the script's fields
directly:

```java
public class EnemyAIStateScript extends SimpleEntityScript {
    final ScriptStateMachine fsm = new ScriptStateMachine();
    ScriptState idle, patrol, chase, attack;      // package-private, not private: see the note below

    class ChaseState extends ScriptState {
        ChaseState() { super("Chase"); }
        public void onEnter() { playSpatialSound(alertSound, 1.0f); }
        public void onUpdate(float dt) {
            if (getFlatDistanceTo(player) <= attackRange) { fsm.changeState(attack); return; }
            getWorldPositionOf(player, targetPos);
            moveTowards(targetPos.x, targetPos.z, chaseSpeed, dt);   // helper defined in the example script
        }
    }

    public void onStart() {
        idle = new IdleState(); patrol = new PatrolState();     // each state created ONCE
        chase = new ChaseState(); attack = new AttackState();
        fsm.changeState(idle);
    }

    public void onUpdate(float dt)        { fsm.update(dt); }          // the owner drives the machine
    public void onPhysicsUpdate(float dt) { fsm.physicsUpdate(dt); }   // only if states use physics
}
```

| API | Behavior |
|-----|----------|
| `changeState(next)` | `current.onExit()`, then `next.onEnter()`. Changing to the current state does nothing. |
| `restartState()` | Runs exit and enter again on the current state and resets its timer. |
| `revertToPrevious()` | Goes back to the previous state ("stunned → whatever I was doing"). |
| `getTimeInState()` | Game seconds in the current state. Replaces hand-written `stateTimer` fields. |
| `getCurrentStateName()` / `isInState(s)` | For HUDs, debug overlays and conditions. Doesn't allocate. |
| `getTransitionCount()` | If this climbs quickly, two states are flickering between each other. |

**Queued transitions.** If `changeState` is called *during* a transition (for example from `onEnter`), the
request is queued and applied once the current transition finishes. `onExit` and `onEnter` calls always come in
pairs. Chains of transitions are capped at **16**. If two states keep handing control back and forth, the machine
logs the problem and stops in the last state instead of hanging the frame.

> The bundled example keeps shared fields **package-private** instead of `private`, so the inner state classes can
> access them reliably under Janino. Do the same in your own FSM scripts.

The full working example is in `scripting/framework/examples/EnemyAIStateScript.java`. It runs perception on a 5 Hz
staggered timer instead of every frame, checks line of sight with a raycast, and
draws its state with `ScriptDebug`.

---

## 4. Input, Audio, Debugging & Persistence

### 4.1 `ScriptInput`: Actions, Not Key Codes

```java
if (ScriptInput.isActionPressed("Jump")) jump();               // went down this frame
boolean sprint = ScriptInput.isActionDown("Sprint");           // held
float h = ScriptInput.getAxis("Horizontal");                   // -1..1, keys and stick merged
ScriptInput.getMouseDelta(lookScratch);                        // into a reused Vector3 (or float[2])
```

Gameplay code asks for **named actions and axes**. Each name can be bound to up to six keys, mouse buttons and
gamepad buttons, so one line of code covers keyboard, mouse and controller.

| Default action | Keyboard / mouse | Gamepad |
|----------------|------------------|---------|
| `Jump` | Space | A |
| `Interact` | E | X |
| `Fire` / `AltFire` | Left / right mouse | Right / left trigger |
| `Sprint` | Left Shift | Left stick click |
| `Crouch` | Left Ctrl | B |
| `Reload` | R | Y |
| `Pause` | Escape | Start |

| Default axis | Keys | Gamepad |
|--------------|------|---------|
| `Horizontal` | A/D, ←/→ | Left stick X |
| `Vertical` | S/W, ↓/↑ (up = +1) | Left stick Y (inverted, since GLFW reports up as −1) |
| `LookX` / `LookY` | n/a | Right stick |

- **Axis merging:** keys contribute −1, 0 or +1, the stick contributes its dead-zoned value, and **the larger
  magnitude wins**. Holding W while pushing the stick doesn't add up to double speed.
- `getAxisRaw` snaps the value to −1, 0 or +1 for menus and grid movement.
- **Mouse delta is 0 while the cursor isn't captured**, so editor clicks don't spin the player camera.
  `setMouseSensitivity` scales the delta.
- **Rebinding** (`bindKey`, `bindMouseButton`, `bindGamepadButton`, `bindAxisKeys`, `bindAxisGamepad`, `unbind`,
  `resetToDefaults`) may allocate. Do it once, for example in a game manager's `onStart` or from a settings menu.
- **Queries don't allocate.** Lookups are `HashMap.get` on string literals, which cache their hash. An unknown name
  returns `false` or `0` and **logs a warning once**, so a typo like `"jump"` shows up in the console without spamming
  it every frame.

### 4.2 Audio & Camera Feedback

```java
playSpatialSound("Assets/Audio/pickup.wav", 0.8f);     // 3D-attenuated, from this entity's position
playGlobalSound("Assets/Audio/stinger.wav");           // non-spatial, full volume (UI, music stingers)
shakeCamera(0.35f, 0.25f);                              // intensity ~0..1, seconds
```

Clip paths resolve through mod overrides and then the project's `Assets/` folder, so `Assets/Audio/x.ogg` and
`Audio/x.ogg` both work wherever the game runs from. A clip that can't be found logs one error and fails a headless
`--frames` run.

**Mixer buses** (Volume 1 §4.2). The calls above go to the `SFX` bus. Each one has overloads that take an
`AudioBus` or a bus name (`"MASTER"`, `"BGM"`, `"SFX"`, `"VOICE"`, case-insensitive, unknown names fall back to
`SFX`):

```java
playGlobalSound(musicTrack, AudioBus.BGM, 0.7f);       // music on the BGM bus
playSpatialSound(line, "VOICE");                       // VOICE clips always duck BGM
playCriticalSound(alarm, 1.0f);                        // non-spatial SFX that ducks BGM while it plays

setBusVolume(AudioBus.BGM, 0.4f);                      // runtime layer, 0..1, multiplied with Project Settings
setBusMuted("SFX", true);
float v = getBusVolume(AudioBus.BGM);  boolean m = isBusMuted("SFX");
```

The runtime bus layer **resets to unity when Play stops**, so a pause-menu mute can't stick in the editor. Lower
level, `EntityScript` also offers `playSound(path, volume, spatial, AudioBus, ducksBgm)`, `playSoundAt(path,
volume, x, y, z[, AudioBus])` and `stopAllSounds()`. One-shots share a pool of 32 sources (Volume 1 §4.1).

**Camera shake keeps the logical pose and the rendered pose separate.** This matters more than it looks. A
naive shake writes an offset into the camera transform, and then the mouse-look script reads that offset back,
adds to it, and the camera slowly drifts off. `ScriptCameraShake` avoids that by only touching the transform
between script ticks:

```
beginFrame:  remove last frame's offset            ← scripts & physics now see the clean pose
[physics + every script's onUpdate]
endFrame:    add this frame's offset to Main Camera ← only the renderer sees the shaken pose
```

| Property | Value |
|----------|-------|
| Max translation | `MAX_OFFSET` = 0.25 m at intensity 1 |
| Max rotation | `MAX_ANGLE` = 4° pitch and roll (yaw is half) |
| Fade | Quadratic over the duration |
| Overlap | A stronger request replaces the current shake. A weaker one during a strong shake is ignored. |
| Noise | Sums of sines at incommensurate frequencies. Looks smooth and non-repeating, uses no RNG state and allocates nothing. |
| Play stop | `reset()` removes any applied offset **before** the Play snapshot is restored |

### 4.3 `ScriptDebug`: Immediate-Mode Gizmos

```java
ScriptDebug.drawRay(ox, oy, oz, fx, fy, fz, 10f, ScriptDebug.CYAN);
ScriptDebug.drawRaycast(ox, oy, oz, fx, fy, fz, 10f, hit);           // green to hit + yellow normal, or red miss
ScriptDebug.drawSphere(x, y, z, detectRadius, ScriptDebug.ORANGE);
ScriptDebug.drawCircleXZ(x, y, z, patrolRadius, ScriptDebug.BLUE);   // ground-plane ring
ScriptDebug.drawBox(cx, cy, cz, 2f, 1f, 2f, ScriptDebug.GREEN);      // full edge lengths, like BoxCollider
ScriptDebug.drawLine(a, b, ScriptDebug.WHITE);
ScriptDebug.drawCross(x, y, z, 0.3f, ScriptDebug.YELLOW);          // point marker (always one frame)
ScriptDebug.drawRay(ox, oy, oz, fx, fy, fz, 10f, ScriptDebug.RED, 2.0f); // duration overload: stays up 2 s
```

This is **immediate mode**: call it every frame you want the shape visible. With no duration, a shape lasts exactly
one frame. With the `durationSeconds` overloads (`drawLine`, `drawRay`, `drawSphere`, `drawBox`) set to `> 0`, it stays up for that much game time, which is handy for "where did that
bullet hit?".

| Detail | Value |
|--------|-------|
| Storage | Fixed `float[MAX_LINES * 6]`, `int[]` colors, `float[]` lifetimes. `MAX_LINES` = 8,192. |
| Cost per shape | Line/ray = 1 line, box = 12, cross = 3, sphere = 3 great circles × 16 segments = 48 |
| Overflow | Extra lines are dropped and **one** warning is logged |
| Colors | `0xRRGGBB` ints: `RED`, `GREEN`, `BLUE`, `YELLOW`, `CYAN`, `MAGENTA`, `ORANGE`, `WHITE`, `GRAY` |
| Where it shows | **The editor's Scene viewport only, while playing or paused.** `ScriptDebugRenderer` projects the lines with the editor camera into the UI batch. The standalone runtime ignores them. |
| Aging | `beginFrame` drops expired lines and compacts the rest in place. No allocation. |

> **Candid note:** segments are clipped against the camera's near plane, but a segment with an endpoint outside
> the Scene tile is currently **dropped** rather than clipped to the viewport edge. Long rays that leave the screen
> disappear completely. Shorten them, or split them with `drawLine`, if you need to see them.

Use `ScriptDebug.setEnabled(false)` to switch off a noisy overlay without touching the scripts that draw it.

### 4.4 `ScriptSaveData`: Persistence

```java
setSaveData("best_lap", lapTime);                          // formats to text; event-time only
float best = getSaveDataFloat("best_lap", 999f);           // parse on each call; cache it in a field
if (!getSaveDataBool("intro_seen", false)) playIntro();
saveGame();                                                 // force a write now (e.g. at a checkpoint)
```

| Aspect | Behavior |
|--------|----------|
| File | `~/.retroengine/saves/<project>.properties`. `<project>` is the folder containing `Assets/` (for an exported game, the project's name, because every export's folder is `Game_Data`), sanitized to `[A-Za-z0-9-_.]`. |
| Format | `java.util.Properties`: plain text that's easy to diff. Values are stored as strings and read back with the typed getters (`getBool` accepts `true`/`false`/`1`/`0`). |
| When it writes | `saveGame()`, **when Play stops in the editor**, and from a **JVM shutdown hook** when the game exits. It writes only when something changed. |
| Crash safety | **Write-then-rename:** the data goes to `<file>.tmp` first and then replaces the save, so a crash mid-write never leaves a truncated file. |
| Project switch | The previous project's pending changes are saved before the new project's file is loaded. |
| Threading | All methods are `synchronized` (the shutdown hook runs on its own thread). |

> **Candid note:** Play-mode writes are **real writes**. Stop restores the scene snapshot, not your save file.
> Testing "first launch" behavior in the editor means calling `ScriptSaveData.deleteAll()` (then `saveGame()`) or
> deleting the file. This is intentional: high-score and unlock logic behaves the same in the editor as in the
> shipped game.

---

## 5. Engine Runtime Fixes & Zero-Allocation Case Studies

Building the framework meant pushing real gameplay scripts through engine paths that had seen little use before:
runtime spawning, runtime destruction, and long-running Play sessions. Reviewing those paths turned up two engine
bugs worth writing down. Neither one crashes. One wastes memory and the other corrupts state silently, which is why
they're easy to miss.

### 5.1 Case Study: The `HierarchySystem` Per-Frame Map Rebuild

**Symptom.** In a steady-state scene with nothing spawning or despawning, the hierarchy pass still produced a
steady stream of `java.util.HashMap$Node` garbage. That's the heap sawtooth Volume 5 §3.2 counts as a regression,
and JFR's *Allocation by Class* view is where it shows up.

**Cause.** `HierarchySystem` keeps an ID → `Entity` index (`entityMapScratch`) for resolving parents. `SceneWorld.update`
correctly calls `invalidateEntityIndex()` once per frame, because entities can be added, removed or re-ID'd at any
time. But "invalid" was treated as "rebuild":

```java
// BEFORE: every frame, for every entity
entityMapScratch.clear();                         // drops every node
for (...) entityMapScratch.put(e.id, e);          // allocates a fresh HashMap.Node per entity
```

A `HashMap.Node` takes **32 bytes** on a 64-bit JVM with compressed oops (12-byte header + `hash` + `key` + `value` +
`next`, padded to 8). The map was pre-sized, so the table itself never grew, but the nodes were rebuilt anyway:

| Entities | Garbage / frame | Garbage / second @ 60 FPS |
|----------|-----------------|---------------------------|
| 250 | 8 KB | 480 KB |
| 1,000 | 32 KB | 1.9 MB |
| 5,000 | 160 KB | 9.4 MB |

**Fix.** Treat the per-frame invalidation as a request to **check** the index, and rebuild only if the check fails:

```java
private void ensureEntityIndex(List<Entity> sceneEntities) {
    if (!indexDirty && indexedList == sceneEntities && indexedSize == sceneEntities.size()) return;
    if (!indexMatches(sceneEntities)) { /* clear + put: only on a real change */ }
    ...
}

private boolean indexMatches(List<Entity> sceneEntities) {
    int indexed = 0;
    for (int i = 0; i < sceneEntities.size(); i++) {
        Entity e = sceneEntities.get(i);
        if (e == null || e.id == null) continue;
        if (entityMapScratch.get(e.id) != e) return false;   // lookups only: zero allocation
        indexed++;
    }
    return indexed == entityMapScratch.size();               // equal counts ⇒ no stale keys left behind
}
```

The check is a pass of `get()` calls, and `String` caches its hash code, so this costs pointer comparisons rather
than re-hashing. Matching counts plus "every live ID resolves to *this exact* entity" proves there are no stale keys
from removed or renamed entities. **In steady state, the hierarchy pass now allocates nothing.**

> **The lesson:** "invalidate every frame" is a fine *safety* policy, as long as invalidating doesn't automatically
> mean rebuilding. Make the cheap check first.

### 5.2 Case Study: `BroadphaseSolver.updateLeaf` and the Stale Node ID

**Background.** Each `PhysicsBody` stores `treeNodeId`, its leaf in the dynamic AABB tree (Volume 4 §1.3). When a
body moves outside its 20 cm fat AABB, `updateLeaf` removes the leaf and inserts it again with the new bounds.

**The bug.** `updateLeaf` threw away the ID returned by `insertLeaf`:

```java
// BEFORE
removeLeaf(leafId);
insertLeaf(body, minX, minY, minZ, maxX, maxY, maxZ);     // return value ignored
```

The code assumed the re-inserted leaf would land in the same node slot, so `body.treeNodeId` would still be correct.
Nothing in the API promised that. Whether it holds depends on the order `removeLeaf` returns nodes to the
LIFO free list and the order `insertLeaf` takes them back out (leaf first, then a new parent). If either changes,
the body's handle points at an **internal node**. After that:

- The next `updateLeaf` reads an internal node's AABB, decides the body "moved out", and removes the wrong subtree.
- `removeRuntimeEntity` (new in this release, §2.5) frees the wrong node, which corrupts the tree.
- Pair queries silently miss the body. Objects fall through floors or ignore triggers, and nothing gets logged.

Runtime spawning and destruction (§2.5) made this much more likely to happen: until now, bodies were only ever
inserted once in `initWorld`, and the free list rarely saw mixed traffic.

**Fix.** Use the ID that `insertLeaf` actually returns:

```java
// AFTER
int newLeafId = insertLeaf(body, minX, minY, minZ, maxX, maxY, maxZ);
if (body != null) body.treeNodeId = newLeafId;
```

> **Candid note:** with today's free-list order, `removeLeaf` frees the parent and then the leaf, and `insertLeaf`
> takes the leaf slot first, so the slot is *usually* reused and the old code *usually* worked. That's exactly why it
> got past testing. The rule isn't specific to this tree: **if a function returns a handle, store it. Never rely on
> the allocator's ordering.**

Related physics changes in this release:

- `PhysicsSystem.getBody(entity)` exposes the live body, which `SimpleEntityScript.applyImpulse` and `setVelocity` use.
- `registerRuntimeEntity` / `flushPendingBodies` delay body creation until the start of the next step, so no solver
  loop sees `activeBodies` change during iteration.
- `isPhysicsEntity` is now the single definition of "the solver cares about this entity", shared by `initWorld` and
  runtime spawning.

---

## 6. Content Creator Rules & Best Practices

### 6.1 Do's and Don'ts

| ✓ Do | ✗ Don't | Why |
|------|---------|-----|
| Extend `SimpleEntityScript` | Extend raw `EntityScript` for gameplay | Cached getters, auto-ticked timers and tweens, auto-cleanup |
| Cache `findPlayer` / `findEntity` in `onStart` | Call them in `onUpdate` | Both scan the whole scene |
| Create `ScriptTask`s and states once, as fields | Write `new ScriptTask() {…}` in `onUpdate` | Every anonymous-class instance is an allocation |
| Use `runAfter` / `runEvery` | Use `Thread`, `Timer`, `sleep`, executors | Off-thread ECS writes race the frame. `sleep` freezes everything. |
| Talk to other scripts with `ScriptEvents` | Use reflection or casts to another script's class | Scripts are class-isolated (§1.3) |
| Use `subscribeEvents(this)` | Use raw `ScriptEvents.subscribe` without unsubscribing | The owned version cleans up on destroy and reload |
| Use `destroySelf()` / `destroy(e)` | Hide entities by parking them at y = −10000 | Deferred destroy removes scripts, bodies and children correctly |
| Use `setVelocity` / `applyImpulse` on dynamic bodies | Use `move()` on a dynamic rigidbody | Writing the transform fights the solver |
| Feed `CharacterController` from `ScriptInput` | Hand-roll player movement | The controller handles ground, steps and friction |
| Use `ScriptInput` action names | Use raw `GLFW_KEY_*` checks in gameplay | One binding covers keyboard, mouse and gamepad, and can be rebound |
| Put forces in `onPhysicsUpdate` | Put forces in `onUpdate` | Physics runs at a fixed 60 Hz (Volume 4 §1.1) |
| Use `raycast(..., outHit)` / `raycastInPlace` | Use the convenience `raycast` that returns a new hit | One `RaycastHit` field per script, reused |
| Write save data on events | Call `setSaveData` every frame | It formats strings and marks the file dirty |
| Use `ScriptTweener.damp` for smoothing | Use `lerp(a, b, 0.1f)` per frame | The fixed-factor lerp behaves differently at 30 FPS and 144 FPS |
| Check scripts by cooking them with Janino | Check scripts with `javac` | `javac` accepts generics that Janino erases (§1.2) |
| Pool frequently spawned objects | Call `spawnPrefab` 60× a second | Spawning reads from disk and allocates entities |
| Drive AI with `NavMeshAgent` + `setDestination` | Steer enemies with hand-written `move()` toward the player | The agent paths around obstacles, brakes, and feeds the `CharacterController` |
| Re-send a destination only when the target moved | Call `setDestination` every frame | Each call re-plans with A\* on the next tick |
| Use `crossFadeAnimation` | Swap clips by hand mid-pose | It blends, ignores repeat requests, and handles interruption |
| Use `setPlayerInputEnabled(false)` for cutscenes | Disable or remove the player's controller | Physics keeps running, and Stop restores input |
| Route audio to the right bus (`BGM`, `VOICE`, …) | Play music and dialogue on the default `SFX` bus | Bus volumes, mutes and ducking only work if sounds are routed |
| Put "late" logic at the end of `onUpdate` | Rely on `onLateUpdate`, `onEnable`/`onDisable`, `onMouse*`, `onDrawGizmos` | Declared but never dispatched (Volume 1 §3.2) |

### 6.2 Scratch Objects vs. Allocation in Update Loops

`Vector3` is **mutable on purpose**. Every operation writes into `this` (or an explicit `out`) and returns it for
chaining. Use it the same way the engine uses `RaycastHit`: **declare a few as fields and reuse them.**

```java
public class TurretScript extends SimpleEntityScript {
    // ✓ Scratch objects: allocated once with the script
    private final Vector3 myPos = new Vector3();
    private final Vector3 toTarget = new Vector3();
    private final RaycastHit hit = new RaycastHit();
    private Entity target;

    public void onStart() { target = findPlayer("Player"); }

    public void onUpdate(float dt) {
        if (target == null) return;
        getWorldPosition(myPos);
        getWorldPositionOf(target, toTarget).sub(myPos);        // toTarget = target - me, in place
        if (toTarget.lengthSquared() > 400f) return;            // 20 m; no sqrt needed for a range check
        toTarget.normalize();
        if (raycast(myPos.x, myPos.y, myPos.z, toTarget.x, toTarget.y, toTarget.z, 20f, hit)
                && hit.entity == target) {
            turnTowards(hit.pointX, hit.pointZ, 120f * dt);
        }
    }
}
```

```java
// ✗ The same logic, allocating every frame
public void onUpdate(float dt) {
    Vector3 me = new Vector3(getWorldX(), getWorldY(), getWorldZ());          // garbage
    Vector3 dir = new Vector3(...).sub(me);                                   // garbage
    RaycastHit h = raycast(me.x, me.y, me.z, dir.x, dir.y, dir.z, 20f);       // garbage (convenience overload)
    logInfo("dist " + dir.length());                                          // string concat: garbage
    runAfter(1f, new ScriptTask() { public void run() { fire(); } });         // garbage + a new timer every frame
}
```

Rules of thumb:

1. **`new` belongs in field initializers and `onStart`.** If you see `new` inside `onUpdate`,
   `onPhysicsUpdate` or a callback that runs every frame, move it out.
2. **Prefer `out` parameters.** `getWorldPosition(out)`, `getVelocity(out)`, `getForward(float[] out)`,
   `ScriptInput.getMouseDelta(out)`. Overloads without an `out` parameter exist for convenience and allocate.
3. **Compare squared lengths** (`lengthSquared`, `distanceSquaredTo`) for range checks.
4. **Strings are allocations.** Concatenation in `logInfo` or `Vector3.toString()` is fine for one-off logs, not
   every frame. Comparing event names with `"name".equals(eventName)` is free.
5. **Size the pools if you need to.** `tasks` starts at 8 slots and `tweens` at 4. If a script keeps dozens of timers
   in flight, the first burst grows the arrays once. Schedule the worst case during `onStart` to get that growth out
   of the way before gameplay.

> **The check:** run your scene with `-Xlog:gc` as described in Volume 5 §3.2. If a young-generation GC shows up
> during steady-state gameplay, something is allocating every frame. JFR's *Allocation by Class* will point you to
> the exact script line.
