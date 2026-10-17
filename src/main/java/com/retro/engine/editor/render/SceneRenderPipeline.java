package com.retro.engine.editor.render;

import com.retro.engine.scene.SceneWorld;

import com.retro.engine.assets.AssetStreamer;
import com.retro.engine.assets.importers.MeshBufferData;
import com.retro.engine.assets.importers.MeshImporter;
import com.retro.engine.assets.importers.PixelBufferData;
import com.retro.engine.scene.Entity;
import com.retro.engine.scene.SceneWorld.WorldTransform;
import com.retro.engine.scene.components.Material;
import com.retro.engine.scene.components.MeshFilter;
import com.retro.engine.scene.components.Transform;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ui.EngineConsolePanel;
import com.retro.engine.rhi.IRenderBackend;

import java.util.List;

/**
 * SceneRenderPipeline orchestrates non-blocking iteration of scene entities,
 * evaluates hierarchical world matrix transformations (parent world x local),
 * queries Virtual Thread asset streaming caches, supplies default fallback geometry
 * ONLY if loading fails, and submits 3D draw items to the RHI queue.
 *
 * Updates EngineConsolePanel with live frame vertex counts and dynamic RHI draw call batches.
 */
public class SceneRenderPipeline {

    // Cached default fallback geometry for assets that failed to load
    private final MeshBufferData fallbackCube = MeshImporter.createDefaultCube();

    /**
     * Drains virtual thread uploads, iterates active scene entities, computes
     * hierarchical world transforms, resolves streaming assets, submits
     * 3D draw calls to the active RHI backend, and updates telemetry counters.
     */
    public void submitFrame(EditorWorkspace editorManager, AssetStreamer streamingService, IRenderBackend rhi) {
        if (editorManager == null || streamingService == null || rhi == null) return;

        // 1. Drain completed background Virtual Thread asset uploads into main thread cache
        streamingService.processCompletedUploads();

        // 2. Reset the multi-entity batch queue for the new frame
        rhi.clearSceneQueue();

        List<Entity> entities = editorManager.getSceneEntities();
        int totalActiveVertices = 0;
        int entityDrawCalls = 0;

        if (entities != null && !entities.isEmpty()) {
            // 3. Traversal loop across active scene graph
            for (Entity entity : entities) {
                Transform tc = entity.getComponent(Transform.class);
                MeshFilter mc = entity.getComponent(MeshFilter.class);
                Material mat = entity.getComponent(Material.class);

                if (tc == null || mc == null) continue;
                if (!mc.isVisible) continue;

                String meshPath = mc.meshAssetPath;
                String texturePath = (mat != null) ? mat.textureAssetPath : null;

                if (meshPath != null) {
                    // Request non-blocking async load on Virtual Threads if not present in cache
                    boolean isMeshLoadedInCache = streamingService.isMeshLoaded(meshPath);
                    if (!isMeshLoadedInCache) {
                        streamingService.requestModelAsync(meshPath);
                    }

                    if (texturePath != null && !streamingService.isTextureLoaded(texturePath)) {
                        streamingService.requestTextureAsync(texturePath);
                    }

                    // Query main-thread cache hits
                    MeshBufferData model = streamingService.getModel(meshPath);

                    byte[] pixels = null;
                    int texW = 0, texH = 0;
                    if (texturePath != null) {
                        PixelBufferData tex = streamingService.getTexture(texturePath);
                        if (tex != null) {
                            pixels = tex.pixels;
                            texW = tex.width;
                            texH = tex.height;
                        }
                    }

                    String submitKey;
                    float[] verticesToSubmit;
                    int vertexCountToSubmit;

                    if (model != null && model.vertices != null && model.vertices.length > 0) {
                        // Async load completed successfully
                        submitKey = meshPath;
                        verticesToSubmit = model.vertices;
                        vertexCountToSubmit = model.vertexCount;
                    } else if (isMeshLoadedInCache) {
                        // Async load finished, but failed or returned empty: show error fallback cube
                        submitKey = "[FALLBACK_CUBE]";
                        verticesToSubmit = fallbackCube.vertices;
                        vertexCountToSubmit = fallbackCube.vertexCount;
                    } else {
                        // Still actively streaming on background Virtual Threads: do NOT render fallback cube
                        continue;
                    }

                    // Accumulate actual rendered mesh vertices and 3D entity draw calls
                    totalActiveVertices += vertexCountToSubmit;
                    entityDrawCalls++;

                    // Compute accumulated hierarchical world transform
                    WorldTransform wt = editorManager.getSceneManager().getComputedWorldTransform(entity);

                    // Submit 9-float stride entity geometry to RHI execution queue using world transform
                    rhi.submitEntity(
                            submitKey,
                            verticesToSubmit,
                            vertexCountToSubmit,
                            texturePath,
                            pixels, texW, texH,
                            wt.px(), wt.py(), wt.pz(),
                            wt.rx(), wt.ry(), wt.rz(),
                            wt.sx(), wt.sy(), wt.sz()
                    );
                }
            }
        }

        // Add 3D grid vertices if enabled
        if (editorManager.is3DGridVisible()) {
            totalActiveVertices += editorManager.get3DGridVertexCount();
        }

        // Calculate total pipeline draw calls:
        // 3D entities + 3D Grid pass + Offscreen Viewport Quad Composition pass + 2D UI Overlay Batch
        int totalDrawCalls = entityDrawCalls + (editorManager.is3DGridVisible() ? 1 : 0) + 2;

        // Push live frame telemetry to EngineConsolePanel
        EngineConsolePanel.updateTelemetry(totalDrawCalls, totalActiveVertices);
    }
}
