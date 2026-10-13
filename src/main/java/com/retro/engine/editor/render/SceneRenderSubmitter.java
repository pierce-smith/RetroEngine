package com.retro.engine.editor.render;

import com.retro.engine.content.AssetStreamingService;
import com.retro.engine.content.mesh.ModelData;
import com.retro.engine.content.mesh.ModelLoader;
import com.retro.engine.content.texture.TextureData;
import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.ecs.components.MaterialComponent;
import com.retro.engine.ecs.components.MeshRendererComponent;
import com.retro.engine.ecs.components.TransformComponent;
import com.retro.engine.editor.EditorPanelManager;
import com.retro.engine.rhi.IRenderBackend;

import java.util.List;

/**
 * SceneRenderSubmitter orchestrates non-blocking iteration of scene entities,
 * queries Virtual Thread asset streaming caches, supplies default fallback geometry
 * ONLY if loading fails, and submits 3D draw items to the RHI queue.
 */
public class SceneRenderSubmitter {

    // Cached default fallback geometry for assets that failed to load
    private final ModelData fallbackCube = ModelLoader.createDefaultCube();

    /**
     * Drains virtual thread uploads, iterates active scene entities, resolves streaming assets,
     * and submits 3D draw calls to the active RHI backend.
     */
    public void submitFrame(EditorPanelManager editorManager, 
                         AssetStreamingService streamingService, 
                         IRenderBackend rhi) {
        if (editorManager == null || streamingService == null || rhi == null) return;

        // 1. Drain completed background Virtual Thread asset uploads into main thread cache
        streamingService.processCompletedUploads();

        // 2. Reset the multi-entity batch queue for the new frame
        rhi.clearSceneQueue();

        List<SceneEntity> entities = editorManager.getSceneEntities();
        if (entities == null || entities.isEmpty()) return;

        // 3. Traversal loop across active scene graph
        for (SceneEntity entity : entities) {
            TransformComponent tc = entity.getComponent(TransformComponent.class);
            MeshRendererComponent mc = entity.getComponent(MeshRendererComponent.class);
            MaterialComponent mat = entity.getComponent(MaterialComponent.class);

            if (tc == null || mc == null) continue;

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
                ModelData model = streamingService.getModel(meshPath);

                byte[] pixels = null;
                int texW = 0, texH = 0;
                if (texturePath != null) {
                    TextureData tex = streamingService.getTexture(texturePath);
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

                // Submit 9-float stride entity geometry to RHI execution queue
                rhi.submitEntity(
                        submitKey,
                        verticesToSubmit,
                        vertexCountToSubmit,
                        texturePath,
                        pixels, texW, texH,
                        tc.posX, tc.posY, tc.posZ,
                        tc.rotX, tc.rotY, tc.rotZ,
                        tc.scaleX, tc.scaleY, tc.scaleZ
                );
            }
        }
    }
}
