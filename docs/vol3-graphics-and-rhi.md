# Volume 3: Graphics & RHI

> **Two native backends, one contract, and alignment rules enforced when classes load.**
> "Java can't do real graphics" was a reasonable thing to say before LWJGL 3 and JDK 25's Foreign Function &
> Memory API. It doesn't hold anymore.

---

## Design Philosophy

RenderWare's lasting idea was the **pipeline abstraction**: the game describes *what* to draw, and the platform
layer decides *how*. RetroEngine's Render Hardware Interface (RHI) works the same way:

- **The engine side** (`SceneRenderPipeline`, `RenderQueue`, `RenderCommand`) never imports a Vulkan or Metal class.
- **The backend side** (`graphics/vulkan/*`, `graphics/metal/*`) never touches an `Entity`.
- **Assets are uploaded once and referenced by opaque 64-bit handle** through `HandleRegistry`. A frame is a list
  of handles and matrices, not a pile of buffers.

---

## 1. Dual Backend RHI

### 1.1 The Contract: `IRenderBackend`

```java
public interface IRenderBackend {
    boolean initialize(ViewportSpec windowInfo);
    long registerMesh(String assetKey, float[] vertices, int vertexCount, int[] indices, int indexCount);
    long registerTexture(String assetKey, byte[] pixels, int width, int height);   // RGBA8
    void submitRenderQueue(RenderQueue queue);
    default void submitParticles(ParticleDrawList particles) {}       // billboard quads, copied during the call
    default void setPostProcess(PostProcessSettings settings) {}      // copied, never retained
    void beginFrame(); void endFrame(); void present();
    default void reloadShaders(List<String> changedShaderFiles) {}
    // ... camera, lights, picking, UI mesh, offscreen viewport texture
}
```

Every per-frame input follows the same ownership rule: the backend **copies** what it needs during the call and
never keeps the engine's object, so `SceneRenderPipeline` can reuse its snapshot storage the next frame.

`unregisterMesh` / `unregisterTexture` are **deferred**: GPU memory is freed only after every in-flight frame that
might reference it has finished.

### 1.2 Backend Selection (`BackendLoader`)

| Host            | Default                 | Fallback |
|-----------------|-------------------------|----------|
| macOS           | **Metal 3**             | Vulkan via MoltenVK if Metal init fails |
| Windows / Linux | **Vulkan 1.3**          | n/a |
| Any + `--vulkan`| Vulkan (MoltenVK on macOS) | n/a |

### 1.3 Vulkan 1.3: Dynamic Rendering, Bindless, Sync2

The Vulkan backend is written for core 1.3, with no legacy fallbacks:

- **Dynamic rendering** (`vkCmdBeginRendering`). No `VkRenderPass` or `VkFramebuffer` objects. Device creation
  **refuses** a GPU without `dynamicRendering` and names the missing feature.
- **Bindless set 0.** One descriptor set layout shared by every 3D pipeline, bound **once per pass**. Materials
  index into `texture2D u_Textures[]` with indices pushed per draw.
- **128-byte push constant block** per draw: model matrix, surface params, material channel and bone palette slot
  indices.
- **`synchronization2`** for every layout transition (`vkCmdPipelineBarrier2`) and **timeline semaphores** for async
  texture uploads.
- **3-slot ring buffers** (`VulkanRingBuffer`) for dynamic vertex data. They grow on demand.
- **Device-local meshes.** Registered meshes upload through the staging ring (`VulkanUploadRing.uploadBuffer`) into
  device-local memory, so discrete GPUs read vertices from VRAM. Disposal waits for the upload to finish.
- **Persistent `VkPipelineCache`** serialized to `.pipeline_cache.bin`, validated against vendor/device ID and
  `pipelineCacheUUID`, and written atomically so a crash can't leave a truncated cache.

### 1.4 Metal 3: Argument Buffers on Apple Silicon

The Metal backend talks to Objective-C directly through LWJGL's `objc_msgSend` bindings. There's no MoltenVK
translation in the hot path.

**Each draw costs one `objc_msgSend`.** Every frame:

1. One **64-byte `DrawArgs`** entry is written per draw: vertex buffer, uniform slot and bone palette
   `gpuAddress`, plus the albedo / normal / specular / lightmap / emissive `gpuResourceID`s.
2. The argument table is bound **once per encoder**.
3. Every indirectly referenced resource is made resident with **one** `useResources:count:usage:stages:` call.
4. Each draw is an instanced draw whose `baseInstance` selects its `DrawArgs` entry.

**There is no draw limit.** Uniform slots and `DrawArgs` hold one region per pass, sized for 1,024 draws to start. A
bigger scene grows them to the next power of two at the start of a frame, after every frame in flight has retired, and
the console logs `per-draw buffers grown to <n>`.

`gpuAddress` and `gpuResourceID` require `MTLGPUFamilyMetal3` (macOS 13+). On older GPUs the backend logs a clear
error at startup instead of failing later.

> **War story:** Metal's texture-to-buffer copy (used for pick readback) takes 11 pointer-sized arguments, which is
> more than LWJGL's JNI invokers cover. `MetalPassEncoder` calls `objc_msgSend` through **libffi** with a prepared
> call interface instead.

---

## 2. The Frame Pipeline

The backends call it the "4-phase pipeline." Counting every distinct render target, it's **five passes**:

| # | Pass                         | Target                         | Notes |
|---|------------------------------|--------------------------------|-------|
| 0 | **Directional shadow depth** | 2048×2048 depth                | Left in `SHADER_READ_ONLY_OPTIMAL` for sampling |
| 1 | **Offscreen 3D scene**       | Viewport color + depth         | Infinite grid → lit static + **skinned** meshes → ground shadow blobs → **billboard particles** (§2.4) |
| 2 | **Color-ID pick**            | Pick target                    | Entity + gizmo IDs; one probed texel read back (editor only) |
| 3 | **Camera preview**           | PiP color target               | Selected Camera's view, no grid (editor only) |
| 4 | **Swapchain composition**    | Swapchain                      | Viewport blit, **or** a fullscreen post-process draw (§2.5) → **`UIGeometryBatch`** 2D overlay |

### 2.0 Textures and Ground Shadows

**Mipmaps.** `MipChain` builds every level on the CPU when a texture is registered, and both backends upload that same
data, so filtering matches. Its box filter weights color by alpha, so cutouts don't pick up dark fringes. The chain is
built at load time, not cook time. Only the material samplers use mips; UI sampling stays on the full-size level.

**Ground shadow blobs.** `GroundHeightIndex` indexes every visible mesh once per frame in a hashed grid, so finding the
surface under a draw only looks at its neighbors. Objects wider than two grid cells, such as floors, go in a short list
that every query checks.

### 2.1 Lighting: Honest Labels

The lit shader is **Blinn-Phong with multi-channel materials** (`blinn_phong.vert/.frag`, `blinn_phong.metal`),
**not** a full PBR BRDF. Materials do carry PBR-style channels (albedo, normal, specular/roughness, lightmap/AO,
emissive) and there are environment reflections, so a metallic-roughness BRDF can be added later without changing
the asset format. Supported lights: one directional sun, **32 point** and **8 spot** lights, linear fog, and
**3×3 PCF** shadow filtering.

**Global shading model.** *Project Settings → Render* (Volume 2 §1.7) chooses one of two looks for the whole
project. `ShadingModel.shaderValue()` is written into the lit shaders' uniforms (`LightBuffer.shadingModel` on
Vulkan, `Uniforms.fogParams.w` on Metal):

| Model | Shader value | Look |
|-------|--------------|------|
| `BLINN_PHONG` | `0` | Per-pixel Blinn-Phong with normal/specular maps, lightmaps and shadow maps (the default) |
| `GOURAUD` | `1` | Lighting computed **per vertex** and interpolated across each triangle: the faceted, low-poly look of fixed-function hardware |

Blinn-Phong is `0` on purpose, so a zeroed uniform slot keeps the per-pixel look. An unknown persisted name (from a
newer editor) falls back to the default.

### 2.1a Unlit Meshes

A model whose materials are all unlit (glTF `KHR_materials_unlit`, or the same no-shading model from another format)
draws its albedo with no lights, ambient or received shadows. It still takes vertex color, emissive and fog, and still
casts shadows. The flag is per mesh (`MeshBufferData.unlit`), comes from the importer and survives cooking in a trailer
after the `.rpack` payload. It reaches the shaders as `isUnlit = 3` on Metal and `surface.w` on Vulkan. Sub-meshes merge
into one draw, so a model that mixes unlit and lit materials draws lit and logs a warning.

### 2.2 Skeletal Skinning

| Layout   | Stride            | Pipeline |
|----------|-------------------|----------|
| Static   | 22 floats / 88 B  | `blinn_phong` |
| Skinned  | 30 floats / 120 B | `blinn_phong_skinned` |

Bone palettes are **64 column-major `mat4`s** per skinned entity, packed into one `std430` storage buffer
(binding 3), for up to 64 skinned entities per frame. A 30-float mesh is **never** drawn with the 22-float pipeline,
because that would misread the vertex stride and draw garbage.

If a skinned draw has no palette (an unanimated rig) or there's no palette slot free (more than 64 skinned entities
this frame), it is still drawn through the **skinned** pipeline, against an identity-seeded slot, so it renders in
bind pose. The draw is skipped only if the skinned pipeline or the palette buffer itself is missing.

### 2.2.1 Animation Blending

`AnimationSystem` samples keyframes, steps each `AnimatorController` state machine and builds the bone palette for
every `AnimatorComponent`. All per-entity values it advances (state-machine position, state times, crossfade,
parameter values, the palette) live on the component. Skeletons, clips and controllers are shared read-only assets,
so duplicated models sharing one rig animate independently.

- **Crossfades.** `AnimatorComponent.crossFade(name, duration)` blends from the current pose into another state
  (with a controller) or another clip (without one) over `duration` seconds. `playAnimation(name)` is the same call
  with `duration = 0`. Asking for the clip that's already playing, or already being faded into, does nothing, so
  scripts can call it every frame. Interrupting a fade past its halfway point promotes the target to the new
  source, which avoids a visible pop. `isBlending()` reports an active fade.
- **Blend trees.** A state can own a `BlendTree`. `SIMPLE_1D` blends the two motions on either side of a float
  parameter by threshold. `FREEFORM_2D` blends all motions by inverse-distance weighting over two float parameters.
  Weights go into a preallocated array, so evaluating a blend tree doesn't allocate.
- **Playback speed.** `AnimatorComponent.playbackSpeed` scales time (1 = authored speed, 0 = frozen).

Scripts reach these through `SimpleEntityScript.crossFadeAnimation`, `playAnimation` and `setAnimationSpeed`
(Volume 6 §2.6).

### 2.3 `UIGeometryBatch`

Editor chrome, gizmos, collider wireframes and the game HUD all go through one batcher: quads, borders, lines,
circles, arrowheads, and SDF-atlas text with a bitmap fallback.

The routines are **stateless statics**. All mutable state (vertices, clip rect, panel mapping, text scratch) lives
in the `Buffer` you pass in. Separate buffers can be filled on separate threads. A single buffer is not
thread-safe.

### 2.4 Billboard Particles

`ParticleSystem` is a CPU-simulated emitter (Volume 1 §2.4). Particles live in a fixed **structure-of-arrays** pool
of `maxParticles` slots (1 to 4,096, default 256). The pool is allocated once and only reallocated when
`maxParticles` changes. `SimulationSystem` simulates them in **world space** during Play, after scripts have run.
Dead particles are compacted by swapping the last live one into their slot.

| Field | Meaning |
|-------|---------|
| `emissionRate`, `lifetime`, `speed`, `gravityScale` | Continuous spawn rate (per second), seconds alive, launch speed, fraction of −9.81 m/s² |
| `startSize` → `endSize`, `startColor` → `endColor` (RGBA) | Interpolated over each particle's life |
| `emitterShape` | `CONE` (within `shapeAngle` of the entity's local **+Y**, from a disc of `shapeRadius`), `SPHERE` (outward from a ball), `BOX` (inside a cube, moving along +Y) |
| `blendMode` | `ADDITIVE` or `ALPHA` |
| `texturePath` | Optional sprite. Without one, the shader draws a soft round falloff |
| `playOnStart` | Start emitting when Play begins |

Scripts drive it with `play()`, `stop()` (live particles finish their lifetime), `emit(count)` (a burst on the next
step, even while stopped) and `clear()`.

**Rendering.** Each frame `SceneRenderPipeline` builds a `ParticleDrawList` of world-space, camera-facing quads:
9 floats per vertex (`x, y, z, r, g, b, a, u, v`), 6 vertices per particle. Then it calls `submitParticles`.

- **One pipeline for both blend modes.** Colors are **premultiplied** and blended with
  `src = ONE, dst = ONE_MINUS_SRC_ALPHA`. Alpha particles carry `(rgb·a, a)`, and additive ones carry `(rgb·a, 0)`.
- **Ordering.** Systems are sorted back to front, and particles *inside* an `ALPHA` system are sorted back to
  front too. Additive systems skip the per-particle sort, since their order doesn't matter.
- **Batches.** Each system is one contiguous vertex range with one texture. A frame holds at most
  `MAX_BATCHES = 32` systems. When there are more, the **nearest** 32 are kept.
- **Depth.** Particles draw after opaque geometry with depth testing **on** and depth writes **off**.
- **Shaders.** Vulkan uses `particle.vert/.frag`. Metal reuses the lit pipeline's `blinn_phong.metal` with a
  dedicated particle mode and premultiplied blend state.
- **Cost.** Simulation and extraction don't allocate. The draw list's storage only grows.

### 2.5 Camera Post-Processing

The scene image can be composited through **one fullscreen post-process pass** instead of a plain blit. It is
controlled from the scene's **Main Camera** `Camera` component:

| Field | Range | Effect |
|-------|-------|--------|
| `postProcessEnabled` (**POST FX**) | on/off | Master switch (default off) |
| `vignetteIntensity` | 0..1 | Darkened corners |
| `scanlineIntensity`, `scanlineSpeed` | 0..1, lines/s | Scrolling CRT scanlines with a 2-pixel period |
| `chromaticOffset` | pixels at the screen edge | Chromatic aberration |
| `saturation`, `contrast` | 0..2 in the Inspector | Color grading (1 = unchanged) |

- **When it applies.** Only when the scene is seen through a game camera: in Play/Pause, or when the editor
  viewport is showing a camera entity's view. The editor fly camera always sees the raw image.
- **Plumbing.** `SceneRenderPipeline` fills a `PostProcessSettings` (plain fields, so copying it into the render
  snapshot doesn't allocate) and passes it to `setPostProcess`. Vulkan pushes it as
  `POST_PUSH_CONSTANT_SIZE = 16 + 8 × 4` bytes (scene texture slot + `PostProcessSettings.pack()`). Metal packs the
  same 8 floats and binds them with `setFragmentBytes`.
- **Swapchain step.** When post-processing is active, the offscreen color is moved to a shader-readable layout and
  sampled by a fullscreen triangle over the Scene rect, instead of `vkCmdBlitImage` (Vulkan) or the composite blit
  (Metal). The UI overlay is then drawn on top as usual.
- **Shaders.** `postprocess.vert/.frag` on Vulkan, `postprocess.metal` on Metal. Like every engine shader, mods can
  override them (see the Modding Guide).

---

## 3. Shader Hot-Reloading & Memory Alignment

### 3.1 The Hot-Reload Path

```
edit .vert/.frag ─▶ asset watcher ─▶ VulkanShaderCompiler (virtual thread, glslc)
                                         │ atomic move of the new .spv
                                         ▼
                    asset watcher sees one complete write ─▶ SceneRenderPipeline
                                         ▼
                    rhi.reloadShaders(changed)   // render thread, between frames
```

- **Vulkan:** GLSL is recompiled to SPIR-V with `glslc` (from `VULKAN_SDK`, common install paths, or `PATH`),
  using the same layout as the `compileVulkanShaders` Gradle task.
- **Shader files.** Vulkan: `blinn_phong.vert/.frag`, `blinn_phong_skinned.vert`, `shadow_depth.frag`,
  `entity_id.frag`, `pick_overlay.vert/.frag`, `infinite_grid.vert/.frag`, `particle.vert/.frag`,
  `postprocess.vert/.frag`, `ui.vert/.frag`. Metal: `blinn_phong.metal` (lit, skinned and particle modes),
  `shadow_depth.metal`, `entity_id.metal`, `postprocess.metal`, `ui.metal`.
- **Metal:** a new `MetalPipeline` is built from the edited `.metal` sources and swapped in whole. Shipped builds
  load a precompiled `.metallib` **only if** its recorded SHA-256 matches the shipped source. Otherwise the source
  is compiled at runtime.
- **All or nothing:** if any part of the rebuild fails, the previous pipelines stay live. A bad shader shows up as
  a console error, not a black screen.

### 3.2 16-Byte `std140` Enforcement

GPU struct alignment bugs are the worst kind: no crash, no validation error, just wrong colors. RetroEngine checks
layouts **in static initializers**, so a mismatch fails the moment the class loads:

```java
// VulkanGpuCache: every LightBuffer member is vec4 / mat4 / vec4-only struct
if (index % 4 != 0)
    throw new ExceptionInInitializerError("LightBuffer member at float " + index + " breaks std140 16-byte alignment");

// MetalUniformStream: float4 / float4x4 members on 16-byte offsets, 512-byte slot stride (256-byte aligned)
if (offset % 16 != 0)
    throw new ExceptionInInitializerError("Metal uniform offset " + offset + " is not 16-byte aligned");
```

The same guards also check **array sizes against the compiled shaders** (`MAX_POINT_LIGHTS == 32`,
`MAX_SPOT_LIGHTS == 8`). If you raise a light limit in Java and forget the shader, the engine refuses to start
instead of silently reading past the end of the struct.

**Rules for contributors:**

1. Declare UBO members as `vec4` / `mat4` only. Pack scalars into `.xyzw`. Never use bare `vec3` in a `std140` block.
2. Mirror every offset as a named constant on the Java side, and add it to the guard array.
3. Uniform slot strides respect the device's `minUniformBufferOffsetAlignment` (queried at init) and Metal's
   256-byte constant buffer alignment.
4. Storage buffers (bone palettes) use `std430`. Don't mix the two rule sets.
