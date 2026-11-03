package com.retro.engine.modding.events;

import com.retro.engine.assets.AssetStreamer;
import com.retro.engine.editor.render.SceneRenderPipeline;
import com.retro.engine.rhi.RenderCommand;
import com.retro.engine.rhi.RenderEnvironment;
import com.retro.engine.rhi.RenderQueue;
import com.retro.engine.rhi.SceneLights;

/**
 * Posted by SceneRenderPipeline once per frame, after the scene's draws and lights are collected and before the
 * draw list is sorted and the shadow frustum fitted. Subscribers can queue extra mesh draws with
 * {@link #drawMesh}, add lights through {@link #lights}, or tweak this frame's {@link #environment} (fog, ambient,
 * shadow blobs) without touching ProjectSettings. Custom pipeline shaders are supplied as mod shader overrides
 * (a mod's {@code Shaders/} folder), not through this event.
 *
 * <p>Runs every frame: one reused instance, and handlers must not allocate if the game is to stay hitch-free.
 */
public final class RenderPassEvent extends ModEvent {

    /** This frame's draw list (unsorted while the event runs). Prefer {@link #drawMesh} over editing commands. */
    public RenderQueue queue;
    /** This frame's lights; {@code lights.add(SceneLights.POINT)} claims a free slot or returns null when full. */
    public SceneLights lights;
    /** This frame's render environment, already filled from ProjectSettings. */
    public RenderEnvironment environment;
    /** True when rendering the standalone game (exported build or fullscreen play), false for the editor viewport. */
    public boolean standalone;

    private SceneRenderPipeline pipeline;
    private AssetStreamer streamer;

    /** Engine use: binds the pipeline that services {@link #drawMesh} for this post. */
    public void bind(SceneRenderPipeline pipeline, AssetStreamer streamer) {
        this.pipeline = pipeline;
        this.streamer = streamer;
    }

    /**
     * Queues one static (non-skinned) mesh draw for this frame. Assets stream in asynchronously like scene assets, so
     * this returns null until the mesh is resident; call it every frame. {@code texturePath} may be null.
     * Paths resolve through the mod VFS ({@code mod://} URIs and overlays). The returned command can be adjusted
     * (material scalars, textures) for this frame only.
     */
    public RenderCommand drawMesh(String meshPath, String texturePath,
                                  float px, float py, float pz, float rx, float ry, float rz, float sx, float sy, float sz) {
        if (pipeline == null) return null;
        return pipeline.queueCustomDraw(streamer, meshPath, texturePath, px, py, pz, rx, ry, rz, sx, sy, sz);
    }
}
