# RetroEngine Modding Guide

A mod is a folder (or a packaged `.rmod` file) with a `mod.json` manifest. A mod can:

- override vanilla assets and shaders,
- patch vanilla scenes without editing them,
- run Java code, compiled in memory by javac, that registers new ECS components and listens to engine events.

Start from `StarterPack/example_mod/`, or create a new mod from the editor (see [Creation Kit mode](#creation-kit-mode)).

```
Mods/example_mod/
  mod.json                  manifest
  Scripts/ModScript.java    entry point (extends com.retro.engine.modding.ModScript)
  Scripts/WobbleScript.java EntityScript used by the patch
  Patches/example.rpatch    scene delta for Assets/Scenes/Main.rscene (hand-written)
  Patches/*.editor.rpatch   scene deltas saved by Creation Kit mode
  Assets/                   VFS overlay: files here replace vanilla Assets/ files at the same path
  Shaders/vulkan|metal/     shader overrides
```

## Where mods load

| Host | Mods folder | When |
|------|-------------|------|
| Exported game | `Game_Data/Mods/` (the exporter creates it, with a README) | Always (start with `--no-mods` to disable) |
| Editor, Creation Kit mode | `<Project>/Mods/`, only the authored mod and its dependencies | **File > Open Mod Workspace...** |
| Editor | `<Project>/Mods/` | Only when launched with `--mods` |

- **What `Mods/` can contain:** mod folders and `.rmod` packages. Packages are extracted once into
  `~/.retroengine/cache/` and re-extracted when the file changes.
- **Dev mods aren't shipped:** a project's own `Mods/` folder is not copied into exports.
- **Saving under `--mods`:** scene saving is disabled, because the open scene contains patched content and saving
  would bake it into the vanilla `.rscene`. Use Creation Kit mode to author scene changes instead.

## mod.json

| Key | Default | Meaning |
|-----|---------|---------|
| `mod_id` (required) | | `a-z 0-9 _ . -`, unique. Also the `mod://<mod_id>/` prefix. |
| `name`, `version`, `author` | | Display only; `version` is compared by dependencies. |
| `target_engine` | | Engine version the mod was built for. A different major version (e.g. `2.0.0` on a 1.x engine) is refused; other differences are only logged. Leave it out to skip the check. |
| `load_priority` | `0` | Higher loads later and wins conflicts. Aliases: `priority`, `loadOrder`. |
| `dependencies` | `[]` | `"other_mod"` or `{"mod_id": "...", "version": "1.2.0", "optional": true}`. |
| `entry_point` | | Fully qualified `ModScript` subclass. |
| `script_sources` | `[]` | Java files compiled in this order. A file can use classes from files before it. |
| `asset_overlays` | `["Assets/"]` | VFS overlay roots inside the mod folder. |
| `patches` | `["Patches/"]` | `.rpatch` files, or folders scanned recursively for them. |
| `shaders` | `["Shaders/"]` | Shader override roots, mirroring the engine's `shaders/` folder. |

`//` line comments are allowed in `mod.json` and `.rpatch` files.

camelCase aliases are accepted too: `id` for `mod_id` (also inside dependency objects), `targetEngine`,
`entryPoint`, `scriptSources` and `assetOverlays`.

## Load order and the VFS

1. **Discover**: every `Mods/*/mod.json` is parsed. A malformed manifest skips only that mod.
2. **Resolve order**: dependencies always load first. Among mods that are ready to load, lower `load_priority`
   goes first, with ties broken by `mod_id`. A missing or too-old required dependency, or a dependency cycle,
   disables the affected mods and everything that requires them.
3. **Compile**: each mod's `script_sources` compile on one class-loader chain that runs through all mods in load
   order, so a mod can use classes from its dependencies.
4. **Mount**: overlays stack in load order, with the last mod on top:

   ```
   Mods/super_weapons/Assets/   load_priority 200   checked first
   Mods/hd_textures/Assets/     load_priority 100
   Assets/                      vanilla             fallback
   ```

   When a scene or prefab references `Assets/Textures/Wall.png`, the top-most mod that ships
   `<overlay>/Textures/Wall.png` wins. Vanilla scenes pick up the override with no edits. Use
   `mod://<mod_id>/<path>` for new files that only your mod uses.
5. **Initialize**: each `entry_point` runs `onInitialize(ModContext)` in load order. The default implementation
   calls `onModInit()`. Once every mod is up, each runs `onModEnable()`, then `ModsLoadedEvent` fires.
   On unload, mods run `onModDisable()` and then `onShutdown()`, in reverse order. If a mod throws during
   initialization, it is unloaded (its handlers and components are removed) along with its dependents.
6. **Shaders**: overrides are mounted and the pipelines are rebuilt (see [Shaders](#shaders)).
7. **Patch**: `.rpatch` files are applied each time their target scene loads, in mod load order. Within one mod they
   run in file-path order, with `*.editor.rpatch` files last.

**Navigation meshes follow the scene file.** A scene's baked `<Scene>.navmesh` is loaded from next to the
*resolved* scene file. If your overlay replaces a whole scene, ship its `.navmesh` beside it (bake it in the editor,
see Volume 2 §1.8). A vanilla scene changed only by `.rpatch` keeps vanilla's navmesh, so geometry you patch in
doesn't change where AI can walk.

Mod assets stream in and are evicted exactly like vanilla ones. An asset that no live entity has referenced for 600
frames (about 10 s at 60 FPS) is unloaded from VRAM.

## Scene delta patches (.rpatch)

```json
{
  "format": "rpatch",
  "version": 1,
  "target_scene": "Assets/Scenes/Main.rscene",
  "operations": [
    { "op": "ADD_ENTITY", "id": "my_mod.chest", "name": "Chest", "parent": "entity_1",
      "components": [ { "type": "Transform", "properties": { "posX": 2 } },
                      { "type": "MeshFilter", "properties": { "meshAssetPath": "mod://my_mod/Meshes/Chest.obj" } } ] },
    { "op": "MODIFY_COMPONENT", "entity": "light_1", "component": "Light", "properties": { "intensity": 2.0 } },
    { "op": "ATTACH_COMPONENT", "entity_name": "Cube", "component": "WobbleComponent", "properties": { "speed": 3 } },
    { "op": "DETACH_COMPONENT", "entity": "entity_2", "component": "ScriptComponent", "index": 0 },
    { "op": "MODIFY_ENTITY", "entity": "my_mod.chest", "name": "Big Chest", "parent": null },
    { "op": "REMOVE_ENTITY", "entity": "entity_3" }
  ]
}
```

- **Targeting**: `"entity"` matches an entity id; `"entity_name"` matches the first entity with that name.
  `REMOVE_ENTITY` also removes the entity's descendants. `"index"` selects among several components of the same
  type and defaults to 0.
- **Components**: you can name built-ins (`Transform`, `Light`, `MeshFilter`, `Material`, `ScriptComponent`, …),
  their `…Component` aliases, or mod components you registered. The newer built-ins work the same way:
  `ParticleSystem`, `AudioReverbZone`, `NavMeshAgent`, `SplineComponent` and `SplineFollower`. For example,
  `{ "op": "ATTACH_COMPONENT", "entity": "torch_1", "component": "ParticleSystem", "properties": { "emissionRate": 80 } }`.
- **Properties**: keys are `@InspectorProperty` field names or Inspector labels, matched case-insensitively.
  Values can be numbers, booleans, strings, enum names (`"point"`), or objects for string maps. Asset fields
  resolve through the VFS. **Binary (`byte[]`) properties take a standard Base64 string** (or `null` to clear).
  Whitespace and line breaks inside the string are ignored, so long values can be wrapped. Any other non-Base64
  character makes the operation fail, which is logged and skipped like any other bad op. The main binary property
  is `SplineComponent.splineData`, which holds a spline's knots (see [Spline knots](#spline-knots) below).
- **New entities**: every new entity starts with a `Transform`. Listing `Transform` in `components` edits it rather
  than adding a second one. Prefix new ids with your `mod_id` so they never collide with vanilla ids or other mods.
- **Errors**: a bad operation (unknown entity, component or property, or a type mismatch) is logged and skipped,
  and the rest of the patch still applies. Patches from several mods stack. Each one sees the result of the patches
  that ran before it.

### Spline knots

A spline's knots live in its hidden `splineData` property. Patches can modify a vanilla spline's knots or attach a
spline with a custom path:

```json
{ "op": "ATTACH_COMPONENT", "entity": "my_mod.rail", "component": "SplineComponent",
  "properties": { "curveType": "CATMULL_ROM", "loop": false,
                  "splineData": "AAAAAQAAAAUAAAAAAAAAAAAA..." } }   // Base64 shortened here for readability
```

You rarely need to write the Base64 by hand. Edit the spline in [Creation Kit mode](#creation-kit-mode) and copy
the value from the generated `.editor.rpatch`. If you do generate it (from a tool or a script), the decoded bytes are
big-endian, in `java.io.DataOutputStream` order:

| Field | Type | Notes |
|-------|------|-------|
| version | `int` | `1`. Data with a newer version is ignored |
| count | `int` | Knot count, clamped to 2..256 |
| points | `count × 3` `float` | Knot `x, y, z`, relative to the owner's `Transform` position (rotation and scale aren't applied) |
| handles | `count × 6` `float` | Bezier in-handle `x, y, z` then out-handle `x, y, z`, as offsets from the knot. Ignored by `CATMULL_ROM` but still required |

Total size: `8 + count × 36` bytes. Leave out `pointCount`. It isn't persisted, and it's derived from the knot data.

## Custom components

```java
public static class WobbleComponent extends Component {
    @InspectorProperty(order = 0, label = "AMPLITUDE", min = 0f, max = 5f)
    public float amplitude = 0.25f;
    public String getComponentName() { return "Wobble"; }
}

public void onInitialize(ModContext context) {
    context.registerComponent(WobbleComponent.class);   // now usable as "WobbleComponent" / "Wobble"
}
```

- A component needs a public no-arg constructor and a unique `order` for each `@InspectorProperty`.
- Supported field types are `float`, `int`, `boolean`, enums, `String` (asset paths use
  `type = PropertyType.ASSET_PICKER`), `Map<String,String>`, and `byte[]` (a persisted blob, usually with
  `hidden = true`; in a `.rpatch` it's a Base64 string). `int` fields are editable in the Inspector and drag in whole
  steps.
- Fields are serialized in `order` sequence. Once a mod has shipped, only **append** fields with a new `order`.
  Appended fields keep their defaults in older scenes. Reordering or removing fields breaks scenes and prefabs saved
  with the old layout.
- If a player removes your mod, scenes and prefabs still load. Your components show as `Missing: <type>`, and their
  saved data is kept, so it comes back when the mod is reinstalled. This covers files saved with this engine version
  or later, because older files can't skip a component they don't recognize.
- Registering a name that is already taken is rejected, so pick names that won't collide.
- Components only hold data. Per-frame logic goes in an `EntityScript` attached through a `ScriptComponent`.
  EntityScripts compile against the mod class-loader chain, so they can reference your component classes directly
  (see `WobbleScript.java`).

## Events

Register handlers inside `onInitialize`. Handlers run in mod load order on the main thread. A handler that throws
is logged and skipped.

```java
context.registerEvents(this);                     // public @SubscribeEvent methods

@SubscribeEvent
public void onSpawn(EntitySpawnEvent e) { if ("Boss".equals(e.entity.name)) e.setCanceled(true); }

context.eventBus().subscribe(ModsLoadedEvent.class, new ModEventListener() {
    public void onEvent(Object event) { ModsLoadedEvent e = (ModsLoadedEvent) event; }
});
```

| Event | Fired | What you can do |
|-------|-------|-----------------|
| `ModsLoadedEvent` | Once, after every mod has initialized | Read `loadOrder` |
| `SceneLoadEvent` | After a scene is deserialized and patched, before its entities join the world | Edit the mutable `entities` list |
| `EntitySpawnEvent` | Once per entity on scene load, prefab spawn and `SceneWorld.spawnEntity` (`source` says which) | Cancel to drop the entity and its descendants, or add components |
| `CollisionEvent` | Every solid contact in a 60 Hz physics step, before the bodies are separated | Change `restitution`, or cancel so the bodies pass through |
| `TriggerEvent` | Each trigger pair per step, with `phase` ENTER, STAY or EXIT | React to `trigger` / `other` |
| `RenderPassEvent` | Once per frame, after draws and lights are collected, before sorting | `drawMesh(...)` extra meshes, add `lights`, tweak this frame's `environment` (fog, ambient) |

- **Your own events:** any object can be posted with `eventBus().post(obj)`, which lets mods talk to each other.
  `@SubscribeEvent` methods receive any event class. Only `ModEvent` subclasses can be canceled.
- **Reused events:** the engine reuses event instances, so don't keep references to them after your handler returns.
- **Per-frame events:** `CollisionEvent`, `TriggerEvent` and `RenderPassEvent` run every frame or every physics step.
  Keep their handlers allocation-free.
- **Authoring:** in Creation Kit mode, `SceneLoadEvent` and `EntitySpawnEvent` don't fire, so the scene you edit is
  pure patch content.

## Shaders

A file in a mod's `Shaders/` folder replaces the engine shader with the same name. Higher-priority mods win per
file.

| Backend | File | Build |
|---------|------|-------|
| Vulkan | `Shaders/vulkan/<name>.vert` / `.frag` | Compiled to SPIR-V with glslc on a background thread. Results are cached in `~/.retroengine/cache/`. Alternatively ship `<name>.frag.spv`. |
| Metal | `Shaders/metal/<name>.metal` | Compiled by the Metal driver when the pipelines rebuild |

- **Applying:** after the mods load, the renderer rebuilds its pipelines at the next frame boundary, the same path
  the editor uses for shader hot reload. Unloading the mods restores the built-in shaders.
- **Failure:** a shader that fails to compile, or that the GPU rejects, is logged, and the engine falls back to its
  built-in version.
- **Compatibility:** start from a copy of the engine's shader and keep its vertex inputs, uniform and push-constant
  blocks, and entry points.
- **Post-processing:** the engine has **one** fullscreen post-process pass, driven by the Main Camera's POST FX
  fields (vignette, scanlines, chromatic aberration, saturation, contrast) and active only through a game camera.
  To change or add a full-screen effect, override `Shaders/vulkan/postprocess.frag` (keep its push-constant block:
  a 16-byte scene slot followed by the 8 `PostProcessSettings` floats) and `Shaders/metal/postprocess.metal` (keep
  `postVertexMain` / `postFragmentMain`). The pass only runs while the camera's **POST FX** toggle is on. Effects
  that need to run inside the lit shading itself are still done by overriding `blinn_phong`.
- **Overridable shaders:** `blinn_phong`, `blinn_phong_skinned`, `shadow_depth`, `entity_id`, `pick_overlay`,
  `infinite_grid`, `particle`, `postprocess` and `ui` on Vulkan. On Metal: `blinn_phong.metal` (which also draws
  particles), `shadow_depth`, `entity_id`, `postprocess` and `ui`.

## Creation Kit mode

The editor doubles as the modding tool:

1. Choose **Open Mod Workspace...** from the project launcher or the FILE menu.
2. Pick a mod folder inside `<Project>/Mods/`. To make a new mod, pick an empty folder there (or `Mods/` itself).
   The editor writes a mod skeleton with `mod.json`, `Assets/`, `Patches/`, `Scripts/` and `Shaders/`.
3. The project opens with only that mod and its dependencies loaded. The title bar shows `[MOD <id>]`.
4. Edit the vanilla scene normally, then save it. The scene file is not overwritten. Instead the editor writes the
   difference to `Patches/<Scene>.editor.rpatch`. That delta is computed against vanilla plus every other patch,
   including your hand-written ones, so it holds only your edits. Saving an unchanged scene deletes the file.
5. Scenes saved *inside* the mod folder are ordinary mod-owned `.rscene` files. Saving to any other vanilla path is
   refused.
6. Choose **File > Close Mod Workspace** to go back to vanilla.

Limitations:

- Only scenes are protected. Assets you import through the asset browser still land in the vanilla `Assets/` folder.
  Put mod assets in the mod's `Assets/` overlay or another mod subfolder instead.
- New ids are written as you created them. Prefix them with your `mod_id` so they can't clash with other mods.
- Spline edits are recorded like any other property. Knot and handle drags are written as a Base64 `splineData`
  value next to curve type, loop and resolution. A blob that's byte-identical to the baseline isn't written, so
  merely selecting or re-saving a spline doesn't add an operation.
- Baking a NavMesh writes `<Scene>.navmesh` next to the vanilla scene file, so it isn't part of your mod. Bake into
  a scene your mod owns or overrides instead.

## Packaging (.rmod)

Use **File > Package .rmod...**, or **PACKAGE .RMOD...** in the Export Standalone Build window. A folder picker
opens straight away (starting in the mod you are authoring, else `<Project>/Mods/`). Pick the mod folder and it is
packaged in the background in these steps; a dialog reports the finished file or the first error:

1. Validates `mod.json` and requires that the mod's dependencies are installed next to it.
2. Compiles `script_sources` against the dependencies' classes and checks that the entry point extends `ModScript`.
   Every other `.java` file (your EntityScripts) must compile too.
3. Parses every `.rpatch` file.
4. Cooks each mesh and texture into a `.rpack` sidecar for fast loading. The raw files are kept.
5. Writes a canonical `mod.json` with comments stripped.
6. Zips everything into `<Project>/Build/Mods/<mod_id>-<version>.rmod`, leaving out dot-files, `.tmp` and `.bak`.

Players drop the `.rmod` into the game's `Game_Data/Mods/`.

## API reference

The public API is every class or member marked `@PublicApi` (package `com.retro.engine.api`). The full list of
signatures is [`api/retroengine.api`](../api/retroengine.api), and CI fails when it changes without that file being
updated. Within 1.x, `@PublicApi` signatures keep working. `@Experimental` ones (currently `RenderPassEvent` and the
render types it hands you) may change in a minor release, with a changelog note. Public engine classes that aren't
marked, and members marked `@Internal`, can change in any release.


| Class | Methods |
|-------|---------|
| `ModScript` | `onInitialize(ModContext)`, `onModInit()`, `onModEnable()`, `onModDisable()`, `onShutdown()`, `context()`, `logInfo/Warn/Error` |
| `ModContext` | `registerComponent(Class)`, `registerEvents(Object)`, `eventBus()`, `vfs()`, `modFile(path)`, `modUri(path)`, `manifest()`, `logInfo/Warn/Error` |
| `ModEventBus` | `register(Object)`, `subscribe(Class, ModEventListener)`, `unregister(Object)`, `post(Object)`, `hasSubscribers(Class)` |
| `ModVirtualFileSystem` | `resolveOverride(path)` returns the file a path resolves to through `mod://`, the overlays, then vanilla. `findInOverlays(path)`, `resolveModUri(uri)`, `baseAssetsDir()`. `layers()` is experimental |
| `SceneWorld` | `spawnEntity(id, name, meshPath)`, `refreshComponents(entity)` after adding components, `instantiatePrefabRuntime(path)`, `requestEntityDestroy(entity)`, `findEntityByName/ById`, `getSceneEntities()`, `setParent(child, parent)`, `getComputedWorldTransform(entity)`, `raycastExcluding(...)`, `get/setVelocity`, `setAngularVelocity`, `sampleAnimatorPose(animator, time)`. The rest of `SceneWorld` is engine code |
| `EntityScript` | `getTransform()`, `getComponent(Class)`, `raycast(...)`, `loadScene(path)`, `playSound(...)` (with `AudioBus` routing), lifecycle and collision/trigger callbacks. `onMouse*` and `onDrawGizmos` are declared but not dispatched yet |
| `SimpleEntityScript` | Everything in `EntityScript`, plus cached getters, timers/tweens, `ScriptEvents`, and the system helpers: `setDestination` / `stopNavigation`, `followSpline` / `get/setSplineProgress`, `play` / `stop` / `emit` (particles), `crossFadeAnimation` / `playAnimation`, `setBusVolume` / `setBusMuted`, `setPlayerInputEnabled`. See Volume 6 |

## How mod code is compiled

Mod and script sources are compiled in memory by javac (`ScriptCompiler`, Volume 6 §1), with the full Java language of
the engine's runtime: lambdas, method references, `var`, records and generics all work.

- Each `script_sources` file is compiled on its own, on top of the files before it, so list them in dependency order.
- A mod's classes are visible to the mods loaded after it and to entity scripts.
- Before Beta 5, mods were compiled with Janino. Mods written for it still compile, casts included.
