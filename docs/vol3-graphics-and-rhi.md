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
    void beginFrame(); void endFrame(); void present();
    default void reloadShaders(List<String> changedShaderFiles) {}
    // ... camera, lights, picking, UI mesh, offscreen viewport texture
}
```

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
| 1 | **Offscreen 3D scene**       | Viewport color + depth         | Infinite grid → lit static + **skinned** meshes → ground shadow blobs |
| 2 | **Color-ID pick**            | Pick target                    | Entity + gizmo IDs; one probed texel read back (editor only) |
| 3 | **Camera preview**           | PiP color target               | Selected Camera's view, no grid (editor only) |
| 4 | **Swapchain composition**    | Swapchain                      | Viewport blit + **`UIGeometryBatch`** 2D overlay |

### 2.1 Lighting: Honest Labels

The lit shader is **Blinn-Phong with multi-channel materials** (`blinn_phong.vert/.frag`, `blinn_phong.metal`),
**not** a full PBR BRDF. Materials do carry PBR-style channels (albedo, normal, specular/roughness, lightmap/AO,
emissive) and there are environment reflections, so a metallic-roughness BRDF can be added later without changing
the asset format. Supported lights: one directional sun, **32 point** and **8 spot** lights, linear fog, and
**3×3 PCF** shadow filtering.

### 2.2 Skeletal Skinning

| Layout   | Stride            | Pipeline |
|----------|-------------------|----------|
| Static   | 22 floats / 88 B  | `blinn_phong` |
| Skinned  | 30 floats / 120 B | `blinn_phong_skinned` |

Bone palettes are **64 column-major `mat4`s** per skinned entity, packed into one `std430` storage buffer
(binding 3), for up to 64 skinned entities per frame. A 30-float mesh is **never** drawn with the 22-float pipeline.
If there's no palette slot free, the draw is skipped, because drawing it anyway would read garbage vertices.

### 2.3 `UIGeometryBatch`

Editor chrome, gizmos, collider wireframes and the game HUD all go through one batcher: quads, borders, lines,
circles, arrowheads, and SDF-atlas text with a bitmap fallback.

The routines are **stateless statics**. All mutable state (vertices, clip rect, panel mapping, text scratch) lives
in the `Buffer` you pass in. Separate buffers can be filled on separate threads. A single buffer is not
thread-safe.

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

// MetalUniformStream: float4 / float4x4 members on 16-byte offsets, 256-byte slot stride
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
