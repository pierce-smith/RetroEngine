# Volume 4: Physics & Modding

> **A fixed-timestep 60 Hz solver, and a modding platform built into the engine from day one.**
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
public static final int   MAX_PHYSICS_STEPS = 6;

accumulator += Math.min(deltaTime, 0.1f);           // dt clamp: never feed more than 100 ms
while (accumulator >= FIXED_TIMESTEP && steps < MAX_PHYSICS_STEPS) {
    fixedUpdate(FIXED_TIMESTEP);
    accumulator -= FIXED_TIMESTEP;
    steps++;
}
if (steps >= MAX_PHYSICS_STEPS && accumulator >= FIXED_TIMESTEP) accumulator = 0.0f;  // drop the backlog instead of spiraling
```

There are **two layers of defense against the spiral of death**:

1. **`dt < 0.1 s` clamp.** It's applied in the engine loop (Volume 1) and again here. A debugger break can't
   inject ten seconds of simulation.
2. **Step cap of 6.** That matches the clamp, so physics keeps pace with scripts down to 10 FPS. Below that, both
   lose the same time rather than drifting apart.

`onPhysicsUpdate(fixedDeltaTime)` in scripts runs inside this fixed step. Put forces and velocity changes there,
not in `onUpdate`.

### 1.2 Bodies & Colliders

| Component                       | Key fields |
|---------------------------------|------------|
| `RigidBodyComponent`            | `motionType` (`STATIC` / `KINEMATIC` / `DYNAMIC`), `mass`, `useGravity`, `linearDrag`, `angularDrag`, `friction`, `restitution` |
| `BoxCollider` / `SphereCollider` / `CapsuleCollider` | Shape dimensions, offset, `isTrigger`, `collisionLayer`, `collidesWith` |
| `MeshCollider`                  | `meshPath` (empty uses the entity's Mesh Filter), `collisionLayer`, `collidesWith`. Physics never pushes it. It moves only with its Transform |
| `CharacterControllerComponent`  | `moveSpeed`, `sprintSpeedMultiplier`, `jumpForce`, `stepOffset`, `slopeLimit`, `gravityMultiplier`, `isGrounded`, `inputEnabled` |
| `VehicleControllerComponent`    | 4-wheel raycast suspension: `engineTorque`, `maxSpeed`, `steeringAngle`, `brakeForce`, `suspensionStiffness` / `Damping` / `RestLength`, `wheelRadius`, `wheelTrackWidth`, `wheelBase` |

**`inputEnabled`** (runtime only) suspends a character's movement, look and jump input without touching physics:
gravity, ground snapping and collisions keep working. Cutscenes use it through
`SimpleEntityScript.setPlayerInputEnabled(false)`. It isn't saved, and Stop resets it to `true`. The keyboard and gamepad
drive one character: the first with a camera on its own transform tree, else the first in the scene. Others move only
through their velocity or a `NavMeshAgent`. A character that also
has a `NavMeshAgent` is **nav-driven** (`PhysicsBody.navDriven`). Its horizontal velocity comes from the agent
(§1.5), so player move and jump input is ignored, but look input still applies.

Only `DYNAMIC` bodies (plus character and vehicle controllers) write their results back to `Transform`. Static and
kinematic bodies are read-only to the solver.

**Shapes are real.** Boxes rotate with their entity, spheres and capsules are round, and colliders honor their offset
and their parents. A capsule's axis is its local Y. Non-uniform scale can't make a sphere an ellipsoid, so a sphere uses
its largest scale. Character colliders always stay upright, whatever the entity's rotation.

**Dynamic bodies** tumble, use `mass` in every contact, and use `friction` (the square root of the two bodies' values, and
a collider with no `RigidBody` counts as 0.5). Impacts under 1 m/s don't bounce, so bodies can settle. There are no joints
and no sleeping, so a settled body still costs solver time. A rough cost is 3 µs per box per step.

**Mesh colliders** are for level geometry. Their triangles load when Play starts, from the same cooked package the
renderer uses, and their scale is read once. Physics never pushes them, even with a `DYNAMIC` `RigidBody`, but they follow
their Transform. Give one a `KINEMATIC` `RigidBody` to make a lift or platform. Characters, spheres and capsules
get exact contacts. A box checks corners only, so two edges crossing with no corner inside either shape make no contact.
Navmesh baking reads them (§1.5), and the editor doesn't draw them yet. Keep triangles wound outward: only upward-facing
triangles are walkable, and the space between a downward face and the next upward face above it bakes as solid.

**Moving platforms.** A grounded character moves and turns with whatever it stands on, as long as that is kinematic,
dynamic or a vehicle. A `STATIC` collider moved by its Transform carries nothing. Walking, wall contacts and scripts that
move the character all still apply, and a wall the platform passes pushes the character off it. Carrying stops the moment
the character leaves the ground, so a jump doesn't keep the platform's speed. Move platforms from a spline or before
physics runs, because a script that moves one in `onUpdate` lands a frame late. Bodies have no sweep, so a wall moving
faster than about 24 m/s against a character can pass through it. NavMesh agents can't path on a moving platform.

**Collision layers.** `collisionLayer` is 0 to 31. `collidesWith` lists layers, such as `0,3`, and empty means all of
them. Both bodies have to agree, so either side can opt out. Triggers, character ground probes and vehicle wheels follow
the same rules.

**Fast bodies.** A dynamic body that would move more than half its smallest radius in a step sweeps ahead and stops at
the first immovable surface. Two fast dynamic bodies can still pass through each other.

### 1.3 Solver Stages

| Stage        | Class                       | Job |
|--------------|-----------------------------|-----|
| Broadphase   | `BroadphaseSolver`          | Dynamic AABB tree (BVH) with SAH insertion, AVL balancing, 20 cm fat-AABB margin |
| Narrowphase  | `NarrowphaseSolver`         | Contact points between oriented boxes, spheres, capsules and triangle meshes |
| Solver       | `ContactSolver`             | Warm-started sequential impulses: mass, friction, restitution, then a split-impulse pass for overlap |
| Characters   | `CharacterControllerSolver` | Ground probe, slopes, step snapping, gravity, friction. Classic third-person feel, gamepad-first |
| Vehicles     | `VehicleSuspensionSolver`   | Per-wheel raycast suspension |
| Contacts     | `ContactPairSet`            | Enter / Stay / Exit tracking for collision and trigger callbacks |

The fat-AABB margin means a body that moves a few centimeters doesn't reinsert itself into the tree every frame.

**Step-up contacts.** When a **grounded** character (not moving upward) touches the side of a static or kinematic
obstacle whose top is no more than `stepOffset` above its feet (plus 2 cm of float slack, so a 0.3 m step with a
0.3 m offset still counts), the character solver **lifts the character onto it** instead of pushing it back. The ground
probe snaps it to the new surface on the next step. Dynamic bodies and other characters are never stepped onto.
This is what lets characters and nav agents climb stairs without ramps.

**Slopes.** A surface no steeper than `slopeLimit` holds a character up, and standing on it doesn't slide them. Anything
steeper can't lift them: they are pushed sideways and gravity takes them down it.

**Characters and dynamic bodies.** The solver treats a character as immovable, so a character pushes a crate at walking
speed whatever the crate weighs. A crate pinned against a wall doesn't stop the character either.

**Vehicles.** A vehicle with no dynamic `RigidBody` doesn't stop at walls. It raises collision events and pushes dynamic
bodies aside.

### 1.4 Raycasts

Rays hit solid colliders only, on their true surface. They pass through triggers, and so does every character's ground
probe. A ray that starts inside a box, sphere or capsule ignores that shape. Mesh colliders are hit from either side.

```java
// Convenience: allocates one RaycastHit
RaycastHit hit = physics.raycast(ox, oy, oz, dx, dy, dz, 50f);

// Hot path: reuse your own RaycastHit, skip your own entity
private final RaycastHit scratch = new RaycastHit();
physics.raycastExcludingInPlace(self, ox, oy, oz, dx, dy, dz, 50f, scratch);
if (scratch.hit) { /* scratch.entity, scratch.distance, scratch.point*, scratch.normal* */ }
```

To see only some layers, add a mask with one bit per layer: `(1 << 0) | (1 << 3)` sees layers 0 and 3.

Rays are pruned hierarchically through the BVH. In anything that runs every frame, **use the `InPlace` variant.**

### 1.5 NavMesh Navigation

The `navigation` package gives AI characters baked pathfinding. Like the solver, it runs at a frame boundary on the
main thread, and its per-frame work doesn't allocate.

**Baking (`NavMeshBaker`, editor only).** The input is the scene's **static colliders**: box, sphere and capsule
colliders on entities with no `RigidBody` or a `STATIC` one, and mesh colliders unless they are `KINEMATIC`. Triggers,
characters, vehicles and agents are skipped.

1. **Rasterize.** Every grid column casts a vertical line through each collider (oriented boxes and spheres;
   capsules as boxes with non-walkable tops) and records the solid intervals and the slope of each top face. Mesh
   triangles are clipped to every column they touch instead, so a wall with no thickness still blocks. Only
   upward-facing triangles are walkable, and a closed mesh is solid inside.
2. **Filter.** A solid top is walkable if its slope is within `maxSlope` and the gap to the next solid above fits
   `agentHeight`. Neighboring walkable cells connect if their heights differ by at most `stepHeight`.
3. **Erode.** Cells closer than `agentRadius` to an unwalkable edge or drop-off are removed.
4. **Polygonize.** Cells are greedily merged into flat, axis-aligned rectangles, and shared edges become portals.

The baker allocates freely, because it runs once from the editor (Volume 2 §1.8), never in a frame. The result is
saved as `<Scene>.navmesh` next to the scene file and loaded automatically when that scene opens.

**The mesh (`NavMesh`).** Walkable rectangles, each at a single height, linked by edge portals, plus a column grid
for O(1) point location (`findPoly`, `findNearestPoly`). It is **immutable** after construction, so queries are
read-only.

**Queries (`NavMeshQuery`).** `findPath` runs **A\*** over the polygon graph, then **funnel (string-pulling)**
smoothing, and writes corner points into a caller-supplied `float[]`. All scratch storage is sized to the mesh once
in the constructor, so `findPath` never allocates. Start and end points snap to the nearest polygon within
`SNAP_EXTENT_XZ = 3` / `SNAP_EXTENT_Y = 4` units. Flags report whether the path is **partial** (destination
unreachable, so it goes to the closest reachable point) or **truncated** (more corners than fit). One instance
per mesh, simulation thread only.

**Agents (`NavMeshAgent` + `NavMeshSystem`).**

| Field | Default | Meaning |
|-------|---------|---------|
| `speed` | 3.5 | Top speed (m/s) |
| `angularSpeed` | 360 | Turn rate (°/s). The agent yaws toward its velocity (yaw 0 faces −Z, matching the character solver) |
| `acceleration` | 8 | m/s². Also used for braking |
| `stoppingDistance` | 0.1 | Arrival radius |
| `autoBraking` | on | Slow down so the agent stops at the goal instead of overshooting |
| `baseOffset` | 0 | Height of the entity's origin above the mesh surface (Transform-driven agents) |

Each frame `NavMeshSystem.update` runs **before physics** (Volume 1 §1.4.1):

1. A pending `setDestination` is planned with `findPath`. Until then, `pathPending` is true.
2. The agent advances past intermediate corners it has reached, recomputes `remainingDistance`, and accelerates
   toward the next corner. With auto-braking, speed is capped at `sqrt(2·a·d)` on the final leg and never
   overshoots within a frame.
3. **With a Character Controller**, the agent writes `velocityX/Z` into the controller, and the solver integrates
   it with gravity, ground snapping and step-ups in the **same** frame. **Without one**, the agent moves the
   `Transform` and keeps it on the mesh surface, snapping back to the nearest polygon if it leaves the mesh.
4. A path longer than `MAX_CORNERS = 64` is followed to its last corner and then **re-planned** from there.

Agents and their target are expected on **root entities**, since their `Transform` is treated as world space.
Runtime state (path, corners, velocity) is reset when Play starts and stops. Scripts use
`setDestination`, `stopNavigation`, `hasPath`, `isPathPending` and `remainingDistance` (Volume 6 §2.6).

### 1.6 Spline Paths

A `SplineComponent` is a curve through up to `MAX_POINTS = 256` knots, authored in the Scene viewport (Volume 2
§3.5) and evaluated by `SplineEvaluator`:

| Field | Meaning |
|-------|---------|
| `curveType` | `CATMULL_ROM` (passes through every knot; ends are reflected) or `BEZIER` (per-knot in/out tangent handles) |
| `loop` | Closes the curve |
| `resolution` | Samples per span for drawing and the arc-length table (2..64, default 16) |
| `pointCount` | Knot count. Not saved; derived from the knot data |

Knots are persisted in the hidden `splineData` blob, which is **never mutated in place**. Every committed edit
installs a new array, so Play snapshots, duplicates and undo can share or swap it safely. The **arc-length table**
is rebuilt only when the curve changes. Per-frame queries (`getPositionAt(t)`, `getTangentAt(t)`,
`getPointAtDistance(d)`, `distanceToT`, `tToDistance`) binary-search it and don't allocate.

A `SplineFollower` moves its entity along a spline at a **constant world speed**:

| Field | Meaning |
|-------|---------|
| `splineEntity` | Name of the entity holding the spline. Blank = the spline on this entity's **parent** |
| `speed` | World units per second. Negative travels backward |
| `wrapMode` | `CLAMP` (stop at the end), `LOOP`, `PING_PONG` |
| `alignToTangent` | Rotate to face the direction of travel |
| `autoPlay` | Start moving when Play begins |
| `startProgress` | Normalized (0..1) arc-length position at Play start |

`SplineSystem.update` runs each simulation frame **before physics**. It resolves each follower's spline once,
advances its arc-length distance with the wrap mode, and writes position (and, optionally, tangent-aligned
rotation) into the `Transform`. Like agents, followers are expected on root entities. Scripts can also bind a
spline directly with `followSpline(spline, speed)` and control it with `get/setSplineProgress`, `pauseSpline` and
`resumeSpline` (Volume 6 §2.6). The First Person Example's `OpeningCutsceneScript` uses this for a camera flyover.

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

Scene files resolve through the same stack. The `.navmesh` that `NavMeshSystem` loads is the one **next to the
resolved scene file**. A mod that overrides a whole scene in its overlay should ship the matching `.navmesh` beside
it. A vanilla scene changed only by `.rpatch` keeps vanilla's navmesh, so patched-in geometry doesn't change
navigation.

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
- **Binary properties travel as Base64.** `BYTES` fields, such as `SplineComponent.splineData` (the knots), are
  written by `SceneDeltaWriter` as standard Base64 strings and decoded by `SceneDeltaPatcher` (whitespace ignored,
  `null` clears, any other non-Base64 character fails the op). The writer compares blobs **by content**, so a
  snapshot or undo copy with identical bytes produces no operation. Dragging a vanilla spline's knots in Creation
  Kit mode therefore lands in the `.editor.rpatch`, and a patch can attach a spline with a custom path. The blob
  layout is documented in the Modding Guide.
- When the editor runs with `--mods`, **scene saving is disabled**, so patched content can never get baked into
  vanilla files.

Full reference: [`MODDING_GUIDE.md`](MODDING_GUIDE.md).

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
- Supported fields: `float`, `int`, `boolean`, enums, `String`, `Map<String,String>`, and `byte[]` (persisted
  as a binary blob, normally combined with `hidden = true` the way `SplineComponent.splineData` is). `int` fields
  are editable in the Inspector like `float`s (Volume 1 §2.3).
  In a `.rpatch`, `byte[]` values are Base64 strings, and Creation Kit deltas include them.
- **Names that are already taken are rejected**, so prefix yours.
- **Components stay pure data** (Volume 1, §2.1). Behavior goes in an `EntityScript`, which compiles against the
  mod class-loader chain and can reference your component classes directly.
