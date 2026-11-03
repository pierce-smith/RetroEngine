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

**What you get per platform:**

| Target  | Same-OS export (via `jpackage`)          | Cross-OS export (via `jlink` + target JDK jmods) |
|---------|------------------------------------------|--------------------------------------------------|
| macOS   | `.app` with embedded jlinked runtime      | `.app` bundle with script launcher |
| Windows | `.exe` launcher with embedded runtime     | Script launcher |
| Linux   | ELF launcher, plus **`.AppImage`** if `appimagetool` is on `PATH` | Script launcher |

> **Candid note:** game exports produce app images, **not** `.dmg` installers or `.tar.gz` archives. For a
> download, zip/tar the export folder yourself (`tar czf MyGame-linux.tar.gz MyGame/`), or wrap the `.app` with
> `hdiutil create`. Native launchers can only be built on their own OS. That's a `jpackage` limitation, not ours.

Export options (`StandaloneExporter.Request`): executable name, target platform, startup scene, target JDK (only for
cross-OS builds), and **`includeHaptics`**. Haptics adds SDL for gamepad rumble at a cost of a few MB. Without it,
vibration calls do nothing.

The jlinked runtime contains only what the engine needs:
`java.base, java.desktop, java.logging, jdk.unsupported, jdk.zipfs`.

### 1.3 Asset Cooking

```bash
./gradlew cookAssets      # Assets/ → Cooked/*.rpack via RpackCookerCli (also runs before ./gradlew run)
./gradlew compileShaders  # GLSL → SPIR-V (glslc) and, on macOS, .metal → .metallib (xcrun)
```

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
`xcrun notarytool store-credentials RetroEngine-notary --apple-id <id> --team-id <TEAMID>`.

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
| Long frame / debugger break              | `dt` clamped to 0.1 s, physics capped at 2 steps |
| NVIDIA `VkInstance` stack overflow       | LWJGL `MemoryStack` raised to 1 MB before any thread starts |
| Crash mid-write of pipeline cache        | Atomic write. Validated against device UUID on load |
| GPU lacks Vulkan 1.3 / Metal 3 features  | Clear startup error naming the missing feature. macOS falls back from Metal to MoltenVK |

---

## 3. QA Verification Checklist & Benchmark Protocols

> **Candid note:** RetroEngine has **no automated test suite** yet (`src/test/` doesn't exist). Everything below is
> manual, and it's the gate for merging into `main`. Adding JUnit coverage for `ComponentSchema`,
> `SceneDeltaPatcher`, `ModVirtualFileSystem` and `BroadphaseSolver` is the most useful contribution you could make
> right now.

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

**Simulation & Scripting**
- [ ] Play → Stop restores the scene exactly (positions, disabled scripts re-enabled).
- [ ] Edit a script during Play: it hot-reloads. Introduce a syntax error: the old version keeps running.
- [ ] Throw from `onUpdate`: only that script stops and the console shows a trimmed trace.
- [ ] Rigid bodies come to rest. Triggers fire Enter/Exit once each. Raycasts hit what they should.

**Rendering**
- [ ] Shadows, point/spot lights and skinned meshes render identically on Metal and Vulkan.
- [ ] Edit a shader: it hot-reloads. Break it: the previous pipeline stays.
- [ ] Resize the window and Scene tile: no stretching, and picking stays pixel-accurate.

**Modding**
- [ ] `ModSDK/StarterPack/example_mod` loads with `--mods`: overlay, patch, and component all apply.
- [ ] A broken `mod.json` skips only that mod. A missing dependency disables its dependents.
- [ ] Scene saving is disabled under `--mods`.

**Export**
- [ ] Export for the host OS. Launch it on a machine **without a JDK installed**.
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
4. **Capture.** Read frame time, draw calls, vertices and heap from `EngineConsolePanel` (<kbd>~</kbd>) over a
   **60-second window**. Report the **median and 99th percentile frame time**, not average FPS.
5. **GC check.** Run with `-Xlog:gc:file=gc.log`. In a steady-state scene, **any young-generation GC during the
   capture window is a regression** against the zero-allocation goal. Track down the allocation with JFR
   (`-XX:StartFlightRecording`, *Allocation by Class*).
6. **Compare backends.** On macOS, run each scene on Metal and again with `--vulkan`. A gap of more than 15%
   between backends on the same hardware is worth investigating.

> The target is a **flat 16.67 ms** frame line with **no GC spikes**. If you see sawtooth heap growth in the
> console, the frame loop is allocating somewhere.
