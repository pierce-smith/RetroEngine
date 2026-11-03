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
DockNode right  = DockNode.leaf(DockPanel.INSPECTOR, DockPanel.ANIMATOR_INSPECTOR);
DockNode bottom = DockNode.leaf(DockPanel.ASSETS,    DockPanel.CONSOLE);
```

Drag a tab to stack it on another leaf or split a leaf left/right/top/bottom, and drag splitters to resize.
`resetLayout()` brings back the default.

**The key trick: design space.** Panels are authored once in a fixed design-space NDC and never deal with their
docked size. `DockSpace.beginPanel()` / `endPanel()` apply a batch transform and clip rect when drawing, and
`toDesignX()` / `toDesignY()` map the mouse back for hit-testing. That's why a panel can move from the right column
to the bottom strip without any changes to its code.

The Scene tile reports its real pixel aspect via `DockSpace`, so the 3D projection is **never stretched** no matter
how you dock it.

### 1.3 The Core Panels

| Panel                | Role |
|----------------------|------|
| `HierarchyPanel`     | Scene graph tree, parenting, selection |
| `ViewportPanel`      | Offscreen 3D render target, gizmos, Play camera lock |
| `InspectorPanel`     | Auto-generated from `ComponentSchema` (Volume 1). Every `@InspectorProperty` shows up with no extra UI code |
| `AssetBrowserPanel`  | Card grid rooted at the project's `Assets/`. Per-type tint and badge, zero-allocation filtering |
| `EngineConsolePanel` | Ring-buffer log viewer + live telemetry |
| Animator panels      | State-machine graph, parameters, and state inspector |

### 1.4 `EngineConsolePanel`

The console redirects `System.out` and `System.err` into a ring buffer, so every `[ScriptEngine]`, `[VulkanBackend]`
and script `logInfo()` line shows up in-editor. It also renders **frame time, FPS, draw calls, vertex counts and JVM
heap** using a reused `StringBuilder` instead of string concatenation.

Toggle it with <kbd>~</kbd> (grave accent).

### 1.5 Transactional Undo: `CommandStack`

Every edit is an `ICommand`. Commands from UI and input are **enqueued**, then flushed at the start of the next
frame (`flushPendingCommands()`, step 0 of the editor loop). Edits never land halfway through a render or simulation
pass, and undo/redo order is fixed.

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
