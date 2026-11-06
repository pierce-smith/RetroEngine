# Volume 5: Deployment & QA

> **Getting a game off your machine and onto someone else's, and proving it works when it gets there.**
> A month-long sprint gets you an engine. Shipping something a stranger can double-click takes a lot more work.

---

## 1. Standalone Exporter & Multi-Platform Packaging

### 1.1 Two Different Things Get Packaged

| What                  | Tool                                   | Output |
|-----------------------|----------------------------------------|--------|
| **Your game**         | Editor → Build overlay (`StandaloneExporter`) | Native app with an embedded runtime |
| **The editor itself** | `./gradlew packageEditor`              | `.app` / `.dmg` / `.pkg` / `.exe` / `.msi` / `.deb` / `.rpm` |

### 1.2 Game Exports

`StandaloneExporter` cooks the active project and stages a self-contained payload:

```
retro-engine.jar + dependency jars     LWJGL JNI + target-OS natives only
natives/                               MoltenVK (macOS)
Game_Data/ProjectSettings.bin          startup scene rewritten to your choice
Game_Data/Assets/                      scenes, scripts, materials, audio
Game_Data/.retro/                      UUID registry, so asset references resolve unchanged
Game_Data/Cooked/                      .rpack packages from RpackCooker
Game_Data/Mods/                        empty, with a README. Players drop mods here (Volume 4)
```

The project's own dev `Mods/` folder is **never** shipped.

What `Game_Data/Assets/` leaves out:

- **Editor-only files**: internal editor resources, `*.tmp`, `*~`, `*.bak` (including autosave `.rscene.bak`
  files) and `*.swp`.
- **Raw meshes and textures that have a cooked `.rpack`**, since the runtime reads the package instead. **Rigged**
  meshes (any non-`.obj` source with a skeleton or clips) are the exception. Their source is kept, and the log
  prints `Keeping rigged source mesh`.

Everything else ships, including the `<Scene>.navmesh` files the NavMesh baker writes next to scenes (Volume 4
§1.5).

**What you get per platform:**

| Target  | Same-OS export (via `jpackage`)          | Without `jpackage` (cross-OS via `jlink` + target jmods, or a packaged editor with no JDK installed) |
|---------|------------------------------------------|--------------------------------------------------|
| macOS   | `.app` with embedded jlinked runtime      | `.app` bundle with script launcher |
| Windows | `.exe` launcher with embedded runtime     | Same `.exe` launcher with embedded runtime |
| Linux   | ELF launcher, plus **`.AppImage`** if `appimagetool` is on `PATH` | Script launcher |

> **Candid note:** game exports produce app images, **not** `.dmg` installers or `.tar.gz` archives. For a
> download, zip/tar the export folder yourself (`tar czf MyGame-linux.tar.gz MyGame/`), or wrap the `.app` with
> `hdiutil create`. macOS and Linux native launchers can only be built on their own OS. That's a `jpackage`
> limitation, not ours. Windows is the exception: the exporter assembles the same app image `jpackage` would, from
> the launcher in the target JDK's `jdk.jpackage.jmod` (or the copy in the packaged Windows editor).

**Cross-OS target JDK.** Pick a folder with the target platform's `.jmod` files, either a JDK home containing
`jmods/` or a standalone jmods package. JDK 24+ builds such as Temurin leave `jmods/` out of the JDK download and
offer the jmods package separately.

Export options (`StandaloneExporter.Request`): executable name, target platform, startup scene, target JDK (only for
cross-OS builds), **`includeHaptics`**, and **`iconFile`**. Haptics adds SDL for gamepad rumble at a cost of a few MB. Without it,
vibration calls do nothing.

- **Haptics default.** The Build overlay pre-checks haptics when `projectUsesHaptics` finds a script that calls
  `vibrate` / `setVibration`, or a scene or prefab containing a Character Controller (its solver rumbles on jump and
  landing). An unreadable file counts as "uses haptics", so rumble is never dropped silently.
- **Application icon.** **APPLICATION ICON** defaults to `src/main/resources/icons/icon.(icns|ico|png)` for the
  target platform and is passed to `jpackage --icon` (embedded directly in the `.exe` when building for Windows
  without `jpackage`). macOS needs `.icns`, Windows `.ico`, Linux `.png`. The
  overlay warns about the wrong format, and the export fails with a clear error instead of shipping a broken icon.
- **Executable name.** 1 to 64 characters from `A-Z a-z 0-9 space _ . -`. It must start with a letter or digit
  and can't end with a dot.
- **Export folder.** Must be **outside** the project's `Assets/` folder, so the copy can't recurse into its own
  output.

The jlinked runtime contains only what the engine needs:
`java.base, java.desktop, java.logging, jdk.unsupported, jdk.zipfs`.

### 1.3 Asset Cooking

```bash
./gradlew cookAssets      # Assets/ → Cooked/*.rpack via RpackCookerCli (also runs before ./gradlew run)
./gradlew compileShaders  # GLSL → SPIR-V (glslc) and, on macOS, .metal → .metallib (xcrun)
```

`compileShaders` runs the `compileVulkanShaders` and `compileMetalShaders` tasks.

If a mesh or texture has a cooked `.rpack`, the runtime never reads the raw source file.

### 1.4 Packaging the Editor

```bash
./gradlew packageEditor                                  # build/dist/RetroEngine(.app)
./gradlew packageEditor -PinstallerType=dmg              # dmg | pkg | msi | exe | deb | rpm
./gradlew packageEditor -PwinConsole                     # Windows build with a visible console
./gradlew notarizeEditor -PmacSignIdentity="Developer ID Application: Name (TEAMID)" -PinstallerType=dmg
```

`notarizeEditor` signs with hardened runtime, submits to Apple's notary service, staples the ticket and runs a
Gatekeeper check. It also signs the dylibs inside LWJGL's jars, which ship unsigned or ad-hoc signed. One-time setup:
`xcrun notarytool store-credentials RetroEngine-notary --apple-id <id> --team-id <TEAMID>`. To use a different
keychain profile name, pass `-PnotaryProfile=<name>`. Without `-PmacSignIdentity`, `packageEditor` ad-hoc signs the
app and warns that Gatekeeper will block it on other Macs.

---

## 2. Production Hardening & OS Logging

### 2.1 Logs Where the OS Expects Them

Packaged builds (`-Dretro.packaged=true`) have no visible console, so `Main` tees `stdout` and `stderr` to a file:

| OS      | Log folder |
|---------|-----------|
| macOS   | `~/Library/Logs/RetroEngine/` (also visible in Console.app) |
| Windows | `%LOCALAPPDATA%\RetroEngine\logs\` |
| Linux   | `$XDG_STATE_HOME/retroengine/` or `~/.local/state/retroengine/` |

- **File name:** `editor.log`, `game.log`, or **your executable's name** (`-Dretro.logName`), so two exported games
  never share a log.
- **One rotation:** the previous session moves to `<name>.log.1` at launch. The run that crashed is always kept
  when the player relaunches.
- **File first, console second.** If the console is gone (`javaw`, a Finder launch), writes to it fail silently
  while the file write still succeeds.
- **Uncaught exceptions on any thread** are logged with the thread name, and fatal loop errors are logged *before*
  `shutdown()` runs.

When a player reports a crash, ask for `<name>.log.1`.

### 2.2 Hardening Already in Place

| Failure                                  | Mitigation |
|------------------------------------------|------------|
| Script throws                            | That script instance is disabled. Everything else runs (Volume 1) |
| Scene update throws in the editor        | Play stops and the editor stays open with unsaved work |
| Bad shader edit                          | Previous pipelines stay live (Volume 3) |
| UBO layout drift                         | `ExceptionInInitializerError` at class load (Volume 3) |
| Mod init throws / bad `.rpatch` op       | Mod unloaded + dependents / op skipped (Volume 4) |
| Long frame / debugger break              | `dt` clamped to 0.1 s, physics capped at 6 steps |
| NVIDIA `VkInstance` stack overflow       | LWJGL `MemoryStack` raised to 1 MB before any thread starts |
| Crash mid-write of pipeline cache        | Atomic write. Validated against device UUID on load |
| GPU lacks Vulkan 1.3 / Metal 3 features  | Clear startup error naming the missing feature. macOS falls back from Metal to MoltenVK |
| More than 64 skinned entities / unanimated rig | Drawn in bind pose through the skinned pipeline, never with the wrong vertex stride (Volume 3 §2.2) |
| No audio device / no `ALC_EXT_EFX`       | Audio calls become no-ops. Reverb zones switch off (Volume 1 §4) |
| `loadScene` with a bad path              | Transition aborted before the running scene is torn down (Volume 1 §1.4.1) |
| Script leaves a bus muted / input suspended | Stop resets the runtime mixer layer and `CharacterController.inputEnabled` |
| Editor crashes between saves             | `AutoSaveSystem` writes `[Scene].rscene.bak` every 5 minutes when there are changes, keeping the last 5 (Volume 2 §1.6) |
| Crash, full disk or bad component during a save | Saves are atomic, so the existing file is untouched, and a dialog reports the failure (Volume 1 §2.3) |
| Scene file damaged or from a newer engine | Detected by checksum and version. The open scene stays, and the file can't be saved over (Volume 2 §1.6) |
| Mod removed from a project                | Its components load as `Missing: <type>` and keep their data through saves |

---

## 3. QA Verification Checklist & Benchmark Protocols

### 3.0 Automated Tests

`./gradlew test` runs the JUnit suite in `src/test/` with no window or GPU, and CI runs it on macOS, Windows and Linux
(`.github/workflows/ci.yml`). It covers physics, the simulation's allocation budget, `ComponentSchema`, scene
saving and loading, `.rpatch`, the mod VFS, `.rmod` packages, and compiling every shipped script with Janino. It also
creates each project template and plays it for 300 frames in a separate process with `--headless --frames`
(Volume 1 §1.4). It can't see anything that needs a GPU, a window or a person, so the manual checklist below is still the gate for merging
into `main`.

Three parts of the suite work differently from a normal test:

- **`@KnownIssue`.** The test describes correct behavior the engine doesn't have yet. It reports as skipped with the
  reason. Once it passes, **the build fails** until you remove the annotation, so every fix leaves a regression test
  behind. Only assertion failures count as expected. Any other exception is a real failure.
- **Binary layout.** `ComponentSchemaTest` compares every persisted field with
  `src/test/resources/component-binary-layout.txt`, which enforces the rule in Volume 1 §2.3. If you added a field on
  purpose, gate it with `since` / `prefabSince`, then copy `build/component-binary-layout.actual.txt` over that file.
  Otherwise, undo the change.
- **`perf` tag.** Timing tests are too noisy for shared CI machines. Run them with `./gradlew perfTest` on a quiet
  machine.

### 3.1 Pre-Merge Checklist

Run on **at least one Metal host and one Vulkan host** (Windows/Linux, or macOS with `--vulkan`).

**Boot & Core**
- [ ] `./gradlew run` launches the editor. The log shows the expected backend and device name.
- [ ] `--runtime` and `--headless` both start and exit cleanly.
- [ ] No `ExceptionInInitializerError` (alignment guards) in the log.

**Editor**
- [ ] Re-dock every panel (stack + split on all four sides), then reset the layout.
- [ ] Type into an Inspector field, including Backspace, then press Enter: **the selected entity must not be
      deleted** and the gizmo mode must not change.
- [ ] RMB + WASDQE flies. W/E/R without RMB switches gizmo mode.
- [ ] Translate / rotate / scale with snapping on, then with Cmd/Ctrl held. Undo/redo each.
- [ ] Collider wireframes show the expected colors (solid / selected / trigger, grounded / airborne).
- [ ] Edit an `int` field (e.g. a spline's RESOLUTION) by dragging and by typing. Undo restores it.
- [ ] Drag spline knots and Bezier handles. One undo reverts the whole drag. Alt+LMB still orbits over a knot.
- [ ] Open a deliberately damaged `.rscene`: the dialog appears, the open scene stays, and saving over that file is refused.
- [ ] *Project Settings*: switch Blinn-Phong ↔ Gouraud, change a bus volume, CANCEL restores, SAVE persists.

**Simulation & Scripting**
- [ ] Play → Stop restores the scene exactly (positions, disabled scripts re-enabled).
- [ ] Edit a script during Play: it hot-reloads. Introduce a syntax error: the old version keeps running.
- [ ] Throw from `onUpdate`: only that script stops and the console shows a trimmed trace.
- [ ] Rigid bodies come to rest. Triggers fire Enter/Exit once each. Raycasts hit what they should.
- [ ] Characters climb steps up to `stepOffset` without ramps.
- [ ] Bake a NavMesh, then Play: agents reach their destination, stop within `stoppingDistance`, and climb stairs.
      Reopen the scene and confirm the `.navmesh` reloads.
- [ ] A `SplineFollower` runs in every wrap mode. The First Person Example's opening cutscene suspends player
      input and gives it back.
- [ ] `crossFadeAnimation` blends with no pop, including when interrupted mid-fade.
- [ ] A VOICE clip ducks BGM. A reverb zone is audible when entered. Stop un-mutes any bus a script muted.

**Rendering**
- [ ] Open the repo as a project and press Play: `Assets/Scenes/RenderCheck.rscene` tours 1,600 cubes, ground shadows and
      a worst-case floor, and shows what to check at each stop. Run it on Metal and again with `--vulkan`.
      `RenderCheckScene` in `src/test/java` generates the scene.
- [ ] Shadows, point/spot lights and skinned meshes render identically on Metal and Vulkan.
- [ ] Particles (additive and alpha, textured and untextured) and Main Camera post FX match on both backends.
      Post FX is off in the editor fly camera.
- [ ] Edit a shader: it hot-reloads. Break it: the previous pipeline stays.
- [ ] Resize the window and Scene tile: no stretching, and picking stays pixel-accurate.

**Modding**
- [ ] `ModSDK/StarterPack/example_mod` loads with `--mods`: overlay, patch, and component all apply.
- [ ] A broken `mod.json` skips only that mod. A missing dependency disables its dependents.
- [ ] Scene saving is disabled under `--mods`.
- [ ] In Creation Kit mode, drag a vanilla spline's knots and save. The `.editor.rpatch` holds a Base64
      `splineData`, the path replays on reload, and re-saving without changes doesn't add an operation.

**Export**
- [ ] Export for the host OS. Launch it on a machine **without a JDK installed**.
- [ ] The custom icon appears, and a wrong-format icon is rejected with a clear error.
- [ ] A scene with agents navigates in the export (its `.navmesh` shipped).
- [ ] The log appears in the OS log folder. Force a crash, relaunch, and confirm `.log.1` holds the crash.
- [ ] `--no-mods` disables mod loading in the exported game.

### 3.2 Benchmark Protocol

Repeatable numbers need a repeatable setup:

1. **Environment.** Release build (`packageEditor` or an export), not `gradle run`. Plugged in, high-performance
   power mode, no screen recording. Record OS, GPU, driver, and backend.
2. **Warm-up.** Let the scene run **60 seconds** before measuring, so JIT compilation (C2) and asset streaming
   settle.
3. **Scenes.** Use fixed, committed scenes:
   - *Empty:* camera + sun. This is the floor cost of the loop and editor.
   - *Static load:* 1,000 meshes, 1 material. Tests draw submission.
   - *Light load:* 32 point + 8 spot lights. Tests the lighting path.
   - *Physics load:* 500 dynamic boxes dropped on a plane. Tests the solver and broadphase.
   - *Skinning load:* 64 animated skinned characters (the per-frame palette cap).
   - *Particle load:* 32 systems (the per-frame batch cap) × 4,096 particles, half additive, half alpha.
   - *Navigation load:* 100 `NavMeshAgent`s re-pathing across a baked mesh. `findPath` must not allocate.
4. **Capture.** Read frame time, draw calls, vertices and heap from `EngineConsolePanel` (<kbd>~</kbd>) over a
   **60-second window**. Report the **median and 99th percentile frame time**, not average FPS.
5. **GC check.** Run with `-Xlog:gc:file=gc.log`. In a steady-state scene, **any young-generation GC during the
   capture window is a regression** against the zero-allocation goal. Track down the allocation with JFR
   (`-XX:StartFlightRecording`, *Allocation by Class*).
6. **Compare backends.** On macOS, run each scene on Metal and again with `--vulkan`. A gap of more than 15%
   between backends on the same hardware is worth investigating.

> The target is a **flat 16.67 ms** frame line with **no GC spikes**. If you see sawtooth heap growth in the
> console, the frame loop is allocating somewhere.
