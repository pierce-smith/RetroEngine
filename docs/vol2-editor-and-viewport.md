# Volume 2: Editor & Viewport

> **The workspace, the viewport, and the tools you touch a thousand times a day.**
> An engine lives or dies by its editor. Nobody remembers RenderWare Studio's internals. They remember whether it
> fought them.

---

## Design Philosophy

The editor is **built on the engine itself**. There's no Swing, no JavaFX, and no ImGui binding. Every panel, tab,
splitter and gizmo is geometry pushed through the same `UIGeometryBatch` and RHI that draw the game HUD. That costs
more up front and pays back in three ways:

1. **One renderer to debug.** If the editor draws correctly on Metal and Vulkan, the game UI does too.
2. **No toolkit fighting the frame loop.** The editor runs inside the 60 FPS pacing loop from Volume 1.
3. **Zero-allocation UI.** Panels write into reusable vertex buffers. Text, cards and lists use static scratch
   buffers instead of per-frame `String`/`List` churn.

---

## 1. Editor Workspace & DockSpace Architecture

### 1.1 The Ownership Chain

```
EngineContext
 └─ EditorWorkspace          scene state, play state, selection, CommandStack, gizmo state
     └─ UIContext
         └─ DockSpace        dock tree, tiles, tab bars, splitters, drag-and-drop
             └─ panels/*     HierarchyPanel, ViewportPanel, InspectorPanel, AssetBrowserPanel, EngineConsolePanel, …
```

### 1.2 The Dock Tree

`DockSpace` owns a binary tree of `DockNode`s. A node is either a **split** (horizontal/vertical with a ratio) or a
**leaf** holding a tab stack of `DockPanel`s. The default layout reproduces a classic editor arrangement:

```java
DockNode left   = DockNode.leaf(DockPanel.HIERARCHY, DockPanel.ANIMATOR_PARAMETERS);
DockNode center = DockNode.leaf(DockPanel.SCENE,     DockPanel.ANIMATOR);
DockNode right  = DockNode.leaf(DockPanel.INSPECTOR, DockPanel.ANIMATOR_INSPECTOR, DockPanel.NAVIGATION);
DockNode bottom = DockNode.leaf(DockPanel.ASSETS,    DockPanel.CONSOLE);
```

Each `DockPanel` has a tab title and a `PanelRegion`: `SCENE`, `ANIMATOR`, `HIERARCHY`, `INSPECTOR`, `PARAMETERS`,
`ANIM INSPECTOR`, `PROJECT` (the asset browser), `CONSOLE`, and `NAVIGATION` (the NavMesh baker, §1.8).

Drag a tab to stack it on another leaf or split a leaf left/right/top/bottom, and drag splitters to resize.
`resetLayout()` brings back the default.

**The key rule: panels lay themselves out against their tile.** Every frame, `DockSpace.layout()` writes each
visible panel's tile (screen NDC, below its tab bar) into `DockPanel.rect`, a `PanelRect`. Panels compute every edge
from that rect, never from fixed coordinates, so they reflow to whatever shape they're docked into. Panels used to be
authored in one fixed "design rect" that the dock stretched per axis. That squashed rows and text below readable
size whenever a panel moved somewhere with a different shape, so it's gone.

For reflowing panels the dock's mapping is the identity. `beginPanel()` / `endPanel()` only set the clip rect,
`toDesignX()` / `toDesignY()` return the screen point unchanged, and text draws at its authored size.

How each panel adapts:

| Panel | Narrow column | Wide / shallow strip |
|-------|---------------|----------------------|
| Inspector | Label column shrinks within limits; steppers and PICK/IMPORT buttons shrink | Content is capped at a maximum width instead of stretching rows |
| Project (asset browser) | One card column; header stacks the path above short-label buttons | Column count and card width come from the tile width |
| Hierarchy | Rows, indent budget and scrollbar follow the tile | Same |
| Console | Timestamp column is dropped | Shows as many log lines as fit |
| NavMesh Baker | Single column | Parameters flow into up to three columns (§1.8) |
| Animator canvas | Header breadcrumb and zoom readout yield to the buttons | The graph view stays centred on the canvas |
| Animator Parameters | Editor column shrinks within limits | Rows are capped at a maximum width |
| Animator Inspector | State details stack above the dope sheet | Details and dope sheet sit side by side, so the timeline gets the full height |

**Writing a panel:**

1. Read `DockPanel.<YOUR_PANEL>.rect` and derive every x/y from it. Keep size-independent values (row heights,
   paddings, button widths) as constants.
2. Put the geometry in one place, either accessor methods or a `layout()` that fills static fields, and call it from
   both render and input. Drawing and hit-testing must read the **same** numbers.
3. Check `rect.contains(x, y)` before acting on a click. A hidden panel's rect is flagged `visible = false`, so a
   stale tile never captures input. This matters for the Animator side panels, which share one click handler.
4. Clamp proportions (for example a label column of 40% of the width, held between a minimum and a maximum), cap
   content width in very wide tiles, and use `addTruncatedText` / `addWrappedText` rather than hard-cut text.
5. Stay zero-allocation: reuse `StringBuilder` scratch buffers instead of `subSequence` or `String` concatenation in
   the frame loop.

**The Scene viewport is the one exception.** It keeps `reflows = false`. Its design rect is stretched over the tile
so the offscreen 3D image and the gizmo overlay stay aligned, and the tile reports its real pixel aspect, so the 3D
projection is **never stretched** no matter how you dock it.

### 1.3 The Core Panels

| Panel                | Role |
|----------------------|------|
| `HierarchyPanel`     | Scene graph tree, parenting, selection |
| `ViewportPanel`      | Offscreen 3D render target, gizmos, Play camera lock |
| `InspectorPanel`     | Auto-generated from `ComponentSchema` (Volume 1). Every `@InspectorProperty` shows up with no extra UI code |
| `AssetBrowserPanel`  | Card grid rooted at the project's `Assets/`. Per-type tint and badge, zero-allocation filtering |
| `EngineConsolePanel` | Ring-buffer log viewer + live telemetry |
| Animator panels      | State-machine graph, parameters, and state inspector |
| `NavMeshBakerPanel`  | Agent/voxel bake settings, BAKE and CLEAR (§1.8) |

**Inspector number fields.** `float` and `int` fields both use the `NUMBER` control. An `int` field (for example
`SplineComponent`'s RESOLUTION and POINT COUNT, or an `AudioReverbZone`'s PRIORITY) drags in whole steps, displays
without decimals, opens an integer text edit, and only pushes an undo command when the rounded value changes.
Before this change, `int` fields were shown read-only.

**Menus.** The main menu bar has these entries:

| Menu   | Items |
|--------|-------|
| FILE   | New Project…, New Scene, Open Scene…, Save Scene…, Project Settings…, Open/Close Mod Workspace, Package .rmod…, Export Standalone Build…, Recent Projects ▸, Quit |
| EDIT   | Undo, Redo, Delete Selected |
| ENTITY | Spawn 3D Cube, Spawn Light Source, Spawn Camera, Save Selected as Prefab… |
| VIEW   | Reset Viewport Camera, Show/Hide 3D Grid, Reset Dock Layout, **NavMesh Baker** |

### 1.4 `EngineConsolePanel`

The console redirects `System.out` and `System.err` into a ring buffer, so every `[ScriptEngine]`, `[VulkanBackend]`
and script `logInfo()` line shows up in-editor. It also renders **frame time, FPS, draw calls, vertex counts and JVM
heap** using a reused `StringBuilder` instead of string concatenation.

Toggle it with <kbd>~</kbd> (grave accent).

### 1.5 Transactional Undo: `CommandStack`

Every edit is an `ICommand`. Commands from UI and input are **enqueued**, then flushed at the start of the next
frame (`flushPendingCommands()`, step 0 of the editor loop). Edits never land halfway through a render or simulation
pass, and undo/redo order is fixed.

Spline knot drags (§3.5) follow the same model. A drag edits the curve live, and releasing the mouse commits the
whole drag as **one** `SplineEditCommand` that swaps the before/after knot blobs.

### 1.6 Autosave: `AutoSaveSystem`

Every **5 minutes** a daemon timer raises a flag. On the next editor tick, if a command has executed, undone or
redone since the last backup, the scene is written to `[SceneName].rscene.bak` next to the scene file. The timer
only sets the flag. Serialization runs on the main thread in `tick()`, so it never races ECS mutation (Volume 1
§1.5).

The last **5** backups are kept beside the scene: `[SceneName].rscene.bak` is the newest, then `.1.bak` through
`.4.bak`. **Save Scene** adds the previous version of the file to the same rotation, unless it matches the newest
backup, so saving repeatedly without changes doesn't use up history. To restore one, copy it and rename it to
`.rscene`. Backups are hidden from the Asset Browser, and exports and mod packages skip them.

**When a scene can't be read.** If a scene is damaged, was made by a newer engine, or isn't a scene at all, a dialog
says so and the scene you had open stays open. That file is then protected: saving over it is refused until it loads
successfully, so a blank scene can never replace something recoverable. Save under a new name instead. A failed save
also shows a dialog and leaves the existing file as it was.

### 1.7 Project Settings (*File → Project Settings…*)

A modal with two tabs. Edits apply live to the workspace's `ProjectSettings`, which the renderer and the audio
mixer read every frame. **SAVE** writes `ProjectSettings.bin`, **CANCEL** restores the values the modal opened with,
and **RESET DEFAULTS** resets the fields.

| Tab    | Section | Controls |
|--------|---------|----------|
| RENDER | Shading | **Global shading model**: `BLINN-PHONG` (per-pixel) or `GOURAUD` (per-vertex, faceted). See Volume 3 §2.1 |
|        | Shadows | Ground shadow blobs on/off |
|        | Distance fog | Enabled, start, end, density, color |
|        | Ambient light | Color, intensity |
| AUDIO  | Mixer buses | Volume + MUTE for MASTER, BGM (MUSIC), SFX, VOICE (Volume 1 §4.2) |
|        | Ducking | Enabled, ducked BGM level, attack and release times |
|        | Spatial reverb | **REVERB ZONES** master switch for `AudioReverbZone` (Volume 1 §4.3) |

Sliders drag on press. Clicking the value box next to a slider opens a prompt for an exact number. Values are
formatted into a reused `StringBuilder`, so an open modal adds no per-frame allocation.

### 1.8 NavMesh Baker (`NAVIGATION` tab, *View → NavMesh Baker*)

The baker lives in its own dock tab, next to the Inspector by default:

| Setting | Default | Meaning |
|---------|---------|---------|
| AGENT HEIGHT | 2.0 | Minimum clearance above a walkable surface |
| AGENT RADIUS | 0.4 | Walkable cells closer than this to an edge or drop-off are eroded |
| MAX SLOPE | 45° | Steepest walkable surface |
| STEP HEIGHT | 0.4 | Largest height difference between connected cells |
| CELL SIZE | 0.2 | Horizontal voxel size |
| CELL HEIGHT | 0.1 | Vertical voxel size |

Values step with the **–** / **+** buttons. **BAKE** rasterizes the scene's static colliders (Volume 4 §1.5),
installs the mesh for Play, and writes `<Scene>.navmesh` next to the scene file. That file is reloaded whenever the
scene is opened. An unsaved scene still gets the in-memory mesh, but the console asks you to save and bake again
to write the file. **CLEAR** removes the mesh and deletes its file.

The panel reflows with its tile (§1.2). In the default right column the settings stack in one column. In a wider
tile, such as the bottom strip, they flow into two or three columns, with BAKE / CLEAR and the mesh info beside
them. Bake results wrap over as many lines as the tile has room for (up to six) instead of being cut off.

### 1.9 Project Templates

*File → New Project…* opens the `TemplatePickerOverlay`. Click a row to select it, or double-click to create
straight away. Each template lives under `/templates/<id>/` with a `project.json` manifest. It is installed on top
of the shared starter content (meshes, material, checker texture), and its text scene descriptor is baked into a
binary `.rscene` once the project's `AssetDatabase` is bound.

| Template | What it shows |
|----------|---------------|
| **First Person Example** (`first_person_example`) | `CharacterController` FPS arms prefab, punch/guard animation blending, collectibles, a NavMesh-driven `ChaserScript` enemy, and an `OpeningCutsceneScript` flyover that rides a spline. Scenes with a `NavMeshAgent` get their `.navmesh` baked with default settings when the project is created |
| **Third Person Example** (`third_person_example`) | `ThirdPersonController` prefab on the built-in `CharacterController`: orbit camera that pulls in front of walls, walk/sprint/sneak animation that follows ground speed, and a NavMesh `ThugPatrolScript` NPC that patrols and backs away from the player |
| **Infinite Driver** (`infinite_driver`) | `VehicleController` endless highway with procedurally spawned obstacles, and optional BGM/SFX routed through the mixer buses |

---

## 2. 3D Viewport Navigation & Input Routing

### 2.1 Camera Controls (`ViewportCamera`)

Flight is **gated on a held mouse button**, the same model Unreal and Unity users already know:

| Input                                  | Action |
|----------------------------------------|--------|
| **RMB / MMB held** + <kbd>W</kbd>/<kbd>S</kbd> | Fly forward / backward |
| **RMB / MMB held** + <kbd>A</kbd>/<kbd>D</kbd> | Strafe left / right |
| **RMB / MMB held** + <kbd>Q</kbd>/<kbd>E</kbd> | Descend / ascend |
| RMB drag, or <kbd>Alt</kbd> + LMB drag | Orbit (yaw / pitch) |
| MMB drag, or <kbd>Shift</kbd> + RMB drag | Pan |
| Scroll wheel                           | Zoom |
| <kbd>F</kbd> / <kbd>Shift</kbd>+<kbd>F</kbd> | Frame selected / reset to startup view |

Flight speed scales with `deltaTime` (`3.5 units/s` strafe, 4× that for depth), so it feels the same at any frame
rate. Editor projection defaults are **50° FOV, 0.1 near, 500 far**. Frame Selected glides instead of snapping, and
any manual input cancels the glide.

**Gamepad** works with no button hold: left stick flies, right stick orbits, triggers zoom, bumpers change
elevation, R3 resets. Input only applies while the editor window has focus, because joysticks report even when
you're in another app.

### 2.2 The Same Keys, Two Meanings

With **no** mouse button held, <kbd>W</kbd>/<kbd>E</kbd>/<kbd>R</kbd> (or <kbd>1</kbd>/<kbd>2</kbd>/<kbd>3</kbd>)
switch the gizmo between **Translate / Rotate / Scale**. Holding RMB or MMB turns those same keys into flight
controls. One hand on the mouse, one on WASD, no mode switching.

### 2.3 `HotkeyRouter` & Text-Field Input Locking

A focused Inspector text field **owns the keyboard**. Without that rule, typing `"Wall"` into a name field would
switch the gizmo to Translate, and Backspace would delete the selected entity.

```java
boolean typing = InspectorInputDelegate.isEditingText();
Input.setKeyboardSuppressed(typing);          // gameplay scripts see no keys either, even in Play
if (typing) {
    cameraController.update(dt, false, false, false, false, false, false);   // freeze flight
    latchHeldKeys(window);                    // keys held now count as "already handled"
    return;
}
```

`latchHeldKeys` handles a subtle case: if you're still holding Backspace when the field commits (Enter or a click
away), the next frame would read it as a new Delete press and destroy your entity. Latching marks it as consumed.

Other routing rules:

- **Scene-editing commands** (save, open, duplicate, delete) are **disabled outside `EDITING`**, so gameplay keys
  can't save or delete your scene during Play.
- Chords (<kbd>Cmd/Ctrl</kbd>+<kbd>S</kbd>, `+D`, `+Z`, `+O`): `HotkeyRouter` treats Ctrl and Cmd (Super) as the
  same primary modifier on every platform. `PlatformShortcuts` only formats the menu labels (`⌘S` on macOS,
  `Ctrl + S` elsewhere).
- Shift is read from the **press event's modifier bits**, not polled state, so a stuck modifier can't trigger the
  wrong action.

---

## 3. Spatial Transform Gizmos & Debug Wireframes

### 3.1 Gizmo Architecture

The gizmo is split into four single-responsibility classes:

| Class             | Responsibility |
|-------------------|----------------|
| `GizmoState`      | Mode, active axis, drag start vectors, grid proxy geometry |
| `GizmoInteractor` | Screen-to-world mouse deltas and snapping |
| `GizmoRenderer`   | Draws handles into the UI batch and captures pick geometry |
| `GizmoMath`       | Anchor and projection math |

### 3.2 Pixel-Exact GPU Picking

There are no ray-vs-cylinder tests against handle meshes. Gizmo handles are drawn a second time into a **Color-ID
pick target** with reserved IDs:

```java
public static final int PICK_ID_AXIS_X = 0xFFFE0001;   // PICK_OVERLAY_ID_BASE | low16
public static final int PICK_ID_AXIS_Y = 0xFFFE0002;
public static final int PICK_ID_AXIS_Z = 0xFFFE0003;
public static final int PICK_ID_CENTER = 0xFFFE0004;
```

Entity pick IDs are always positive and overlay IDs sit in the `0xFFFE....` range, so they can't collide. Thin
handles get wider **pick-only hitboxes** that go into the pick target but are never drawn, so they're easy to grab
without looking chunky.

### 3.3 Snapping

Snapping is **on by default**. Holding <kbd>Cmd/Ctrl</kbd> **inverts** it for as long as the key is down:

| Mode      | Step |
|-----------|------|
| Translate | 0.5 units |
| Rotate    | 15° |
| Scale     | 0.25× |

Snapping uses **unquantized accumulators**: the raw drag keeps accumulating and only the displayed result is
snapped. Slow drags never lose small deltas, and toggling snap mid-drag doesn't jump.

### 3.4 Color Language

Axes follow the industry convention: **X = red, Y = green, Z = blue**. The active axis turns **yellow** while
hovered or dragged.

`ColliderDebugRenderer` draws physics shapes as immediate-mode wireframes using the same color code:

| Wireframe                     | Color          | Meaning |
|-------------------------------|----------------|---------|
| Box / Sphere / Capsule        | Green          | Solid collider |
| …selected                     | Light blue     | Selected solid collider (brighter alpha) |
| …trigger                      | Yellow         | `isTrigger = true`, so it fires events and doesn't block |
| Character controller ray      | Green / Red    | Grounded / airborne |
| Vehicle wheel rays + rings    | Green / Red    | Per-wheel suspension contact |

You can tell at a glance whether a wheel is touching the ground or why a door trigger isn't firing, without a
debugger.

### 3.5 Spline Authoring (`SplineGizmo`)

`SplineComponent` curves are edited directly in the Scene viewport:

| Element | Color | Shown |
|---------|-------|-------|
| Curve line (every spline in the scene) | Cyan | Editing and Paused. Hidden while the simulation runs or a modal is open |
| Knots of the **edited** spline | White, **gold** while active | When the spline's entity is selected, or a selected entity's `SplineFollower` targets it |
| Bezier tangent handles | Pink | Only for `BEZIER` curves |

- **Dragging.** A left press on a knot or handle is checked **before** the GPU pick (§3.2), so it doesn't select
  whatever is behind it. The point moves in the camera plane at its original depth. Holding <kbd>Alt</kbd> skips the
  spline hit-test, so Alt+LMB still orbits.
- **Undo.** Releasing the mouse commits the drag as one undoable command (§1.5).
- **Knot count.** Editing **POINT COUNT** in the Inspector appends knots (extrapolated from the last segment) or
  removes them from the end, up to `MAX_POINTS = 256`.
- **Space.** Knots are stored relative to the owner's `Transform` position. Rotation and scale are **not** applied,
  so put splines on root entities.
- **Cost.** Drawing is allocation-free. Arc-length tables are rebuilt only when the curve changes.

### 3.6 Skeleton Joints

Entities with an animated skeleton draw their bones and joints in the current pose while editing or paused. They
are hidden while the simulation runs. Each joint goes into the pick target with its own ID. The selected entity's
skeleton is drawn last, so it wins where skeletons overlap, and the transform gizmo is drawn after the joints, so its
handles still win over them. While editing, a bone edit rebuilds that animator's palette straight away
(`SceneWorld.refreshEditPose`), so the skinned mesh deforms in the same frame.
