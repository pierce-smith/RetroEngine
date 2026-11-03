# Volume 4: Physics & Modding

> **A deterministic 60 Hz solver, and a modding platform built into the engine from day one.**
> Minecraft became Minecraft because of its modders. Morrowind and Skyrim are still played two decades on for the
> same reason. Modding in RetroEngine is part of the core design, not a later add-on.

---

## 1. The 60 Hz Physics Solver

### 1.1 Fixed Timestep, Clamped Input

Rendering runs at whatever rate the display allows. Physics always steps at **exactly 1/60 s**, so a jump reaches the
same height on a 30 FPS laptop and a 144 Hz desktop.

```java
public static final float FIXED_TIMESTEP    = 1.0f / 60.0f;
public static final float GRAVITY           = -9.81f;
public static final int   MAX_PHYSICS_STEPS = 2;

accumulator += Math.min(deltaTime, 0.1f);           // dt clamp: never feed more than 100 ms
while (accumulator >= FIXED_TIMESTEP && steps < MAX_PHYSICS_STEPS) {
    fixedUpdate(FIXED_TIMESTEP);
    accumulator -= FIXED_TIMESTEP;
    steps++;
}
if (steps >= MAX_PHYSICS_STEPS) accumulator = 0.0f;  // drop the backlog instead of spiraling
```

There are **two layers of defense against the spiral of death**:

1. **`dt < 0.1 s` clamp.** It's applied in the engine loop (Volume 1) and again here. A debugger break can't
   inject ten seconds of simulation.
2. **Step cap of 2.** If the machine can't keep up, physics slows down gracefully instead of trying to catch up
   with more and more steps every frame.

`onPhysicsUpdate(fixedDeltaTime)` in scripts runs inside this fixed step. Put forces and velocity changes there,
not in `onUpdate`.

### 1.2 Bodies & Colliders

| Component                       | Key fields |
|---------------------------------|------------|
| `RigidBodyComponent`            | `motionType` (`STATIC` / `KINEMATIC` / `DYNAMIC`), `mass`, `useGravity`, `linearDrag`, `angularDrag`, `friction`, `restitution` |
| `BoxCollider` / `SphereCollider` / `CapsuleCollider` | Shape dimensions, `isTrigger` |
| `CharacterControllerComponent`  | Move/sprint speed, step offset, `isGrounded` |
| `VehicleControllerComponent`    | 4-wheel raycast suspension, `wheelRadius` |

Only `DYNAMIC` bodies (plus character and vehicle controllers) write their results back to `Transform`. Static and
kinematic bodies are read-only to the solver.

### 1.3 Solver Stages

| Stage        | Class                       | Job |
|--------------|-----------------------------|-----|
| Broadphase   | `BroadphaseSolver`          | Dynamic AABB tree (BVH) with SAH insertion, AVL balancing, 20 cm fat-AABB margin |
| Narrowphase  | `NarrowphaseSolver`         | Box / sphere / capsule manifolds, trigger volumes, positional separation |
| Characters   | `CharacterControllerSolver` | Ground probe, step snapping, gravity, friction. Classic third-person feel, gamepad-first |
| Vehicles     | `VehicleSuspensionSolver`   | Per-wheel raycast suspension |
| Contacts     | `ContactPairSet`            | Enter / Stay / Exit tracking for collision and trigger callbacks |

The fat-AABB margin means a body that moves a few centimeters doesn't reinsert itself into the tree every frame.

### 1.4 Raycasts

```java
// Convenience: allocates one RaycastHit
RaycastHit hit = physics.raycast(ox, oy, oz, dx, dy, dz, 50f);

// Hot path: reuse your own RaycastHit, skip your own entity
private final RaycastHit scratch = new RaycastHit();
physics.raycastExcludingInPlace(self, ox, oy, oz, dx, dy, dz, 50f, scratch);
if (scratch.hit) { /* scratch.entity, scratch.distance, scratch.point*, scratch.normal* */ }
```

Rays are pruned hierarchically through the BVH. In anything that runs every frame, **use the `InPlace` variant.**

---

## 2. Priority-Stacked VFS & `.rpatch` Delta Patching

### 2.1 The Overlay Stack

`ModVirtualFileSystem` stacks mod asset folders on top of vanilla `Assets/`, ordered by `load_priority`:

```
Mods/super_weapons/Assets/   load_priority 200   ← checked first, wins conflicts
Mods/hd_textures/Assets/     load_priority 100
Assets/                      vanilla             ← fallback
```

Scenes keep referencing `Assets/Textures/Wall.png`. `resolveOverride()` swaps in the highest mod layer that ships a
file at that path, so **vanilla scenes pick up modded textures, meshes and scripts with no edits**. For new files
that only your mod uses, address them with `mod://<mod_id>/<path>`.

The VFS is **immutable once built** and lookups are plain existence checks. Mod assets stream and evict exactly like
vanilla ones: anything unreferenced for 600 frames (~10 s) is dropped from VRAM.

### 2.2 Non-Destructive Scene Patches

This is the Creation Engine idea done in JSON. Mods **never edit a vanilla `.rscene`**. They ship deltas:

```json
{
  "format": "rpatch", "version": 1,
  "target_scene": "Assets/Scenes/Main.rscene",
  "operations": [
    { "op": "ADD_ENTITY", "id": "my_mod.chest", "name": "Chest",
      "components": [ { "type": "MeshFilter", "properties": { "meshAssetPath": "mod://my_mod/Meshes/Chest.obj" } } ] },
    { "op": "MODIFY_COMPONENT", "entity": "light_1", "component": "Light", "properties": { "intensity": 2.0 } },
    { "op": "REMOVE_ENTITY", "entity": "entity_3" }
  ]
}
```

Operations: `ADD_ENTITY`, `REMOVE_ENTITY`, `MODIFY_ENTITY`, `MODIFY_COMPONENT`, `ATTACH_COMPONENT`,
`DETACH_COMPONENT`.

- **Applied every time the target scene loads**, in mod load order. Each mod sees the result of the mods before it.
- **Fault-tolerant:** a bad operation is logged and skipped, and the rest of the patch still applies.
- **Property keys** come from `ComponentSchema` (Volume 1): `@InspectorProperty` field names or Inspector labels,
  case-insensitive.
- **Creation Kit mode** (*File → Open Mod Workspace…*) lets you edit visually. `SceneDeltaWriter` diffs your changes
  against vanilla and saves `*.editor.rpatch` files.
- When the editor runs with `--mods`, **scene saving is disabled**, so patched content can never get baked into
  vanilla files.

Full reference: [`ModSDK/MODDING_GUIDE.md`](../ModSDK/MODDING_GUIDE.md).

---

## 3. `ModEventBus` & Dynamic Component Registration

### 3.1 Lifecycle

A mod's `entry_point` extends `ModScript`. Mods load in dependency order, then by `load_priority`, then by `mod_id`:

```
onInitialize(ModContext) → onModInit() → [all mods up] → onModEnable() → ModsLoadedEvent
                                                 … game runs …
                     onModDisable() → onShutdown()          (reverse order)
```

If a mod throws during init, it's unloaded along with every mod that depends on it, and its handlers and components
are removed. Everything else keeps loading.

### 3.2 Events

```java
@Override public void onInitialize(ModContext ctx) {
    ctx.registerEvents(this);
}

@SubscribeEvent
public void onSpawn(EntitySpawnEvent e) {
    if ("Boss".equals(e.entity.name)) e.setCanceled(true);   // veto the spawn
}
```

Built-in events: `EntitySpawnEvent`, `SceneLoadEvent`, `CollisionEvent`, `TriggerEvent`, `RenderPassEvent` (gives
access to the frame's `RenderQueue`, `SceneLights` and `RenderEnvironment`), and `ModsLoadedEvent`.

How the bus behaves:

- **Synchronous, main thread, mod load order.** No threading surprises.
- **Zero-allocation `post()`.** Handlers live in per-type arrays that are rebuilt only when subscriptions change, and
  engine hot paths reuse one event instance per type. *Don't keep a reference to an event after your handler
  returns.*
- **Subclass-aware.** Subscribing to `ModEvent` receives every event.
- **Any object can be posted**, so mods can define their own event classes to talk to each other.
- **Crash-isolated.** A throwing handler is logged with its mod ID and skipped.

### 3.3 Registering New Components

```java
public static class WobbleComponent extends Component {
    @InspectorProperty(order = 0, label = "AMPLITUDE", min = 0f, max = 5f)
    public float amplitude = 0.25f;
    public String getComponentName() { return "Wobble"; }
}

ctx.registerComponent(WobbleComponent.class);   // usable as "WobbleComponent" or "Wobble"
```

`ComponentSchema.registerModComponent` tags the type with its owning mod ID. From then on it works everywhere a
built-in does: the Inspector, serialization, prefabs, and `.rpatch` `ATTACH_COMPONENT`. When the mod unloads,
`unregisterModComponents(modId)` removes it again.

The rules:

- Public no-arg constructor, and a unique `order` for each `@InspectorProperty`.
- Supported fields: `float`, `int`, `boolean`, enums, `String`, `Map<String,String>`.
- **Names that are already taken are rejected**, so prefix yours.
- **Components stay pure data** (Volume 1, §2.1). Behavior goes in an `EntityScript`, which compiles against the
  mod class-loader chain and can reference your component classes directly.
