package com.retro.engine.editor.render;

import com.retro.engine.assets.AssetStreamer;
import com.retro.engine.assets.importers.MeshBufferData;
import com.retro.engine.assets.importers.MeshImporter;
import com.retro.engine.assets.importers.PixelBufferData;
import com.retro.engine.backend.metal.MetalBackend;
import com.retro.engine.editor.EditorWorkspace;
import com.retro.engine.editor.ui.EngineConsolePanel;
import com.retro.engine.rhi.IRenderBackend;
import com.retro.engine.scene.Entity;
import com.retro.engine.scene.SceneWorld.WorldTransform;
import com.retro.engine.scene.components.Light;
import com.retro.engine.scene.components.Material;
import com.retro.engine.scene.components.MeshFilter;
import com.retro.engine.scene.components.Transform;

import java.util.List;

/**
 * SceneRenderPipeline submits 14-float mesh geometry, queries Light components,
 * passes material reflection parameters, and performs downward surface raycasting to determine entity shadow projection heights.
 */
public class SceneRenderPipeline {

    private final MeshBufferData fallbackCube = MeshImporter.createDefaultCube();

    public void submitFrame(EditorWorkspace editorManager, AssetStreamer streamingService, IRenderBackend rhi) {
        if (editorManager == null || streamingService == null || rhi == null) return;

        streamingService.processCompletedUploads();
        rhi.clearSceneQueue();

        List<Entity> entities = editorManager.getSceneEntities();
        int totalActiveVertices = 0;
        int entityDrawCalls = 0;

        if (entities != null && !entities.isEmpty()) {

            // Scan for active Light component in scene
            for (Entity entity : entities) {
                Light light = entity.getComponent(Light.class);
                Transform lightTransform = entity.getTransform();

                if (light != null && lightTransform != null) {
                    WorldTransform wt = editorManager.getSceneManager().getComputedWorldTransform(entity);
                    float posX = wt.px();
                    float posY = wt.py();
                    float posZ = wt.pz();

                    float radPitch = (float) Math.toRadians(lightTransform.rotX);
                    float radYaw = (float) Math.toRadians(lightTransform.rotY);

                    float dirX = (float) (Math.sin(radYaw) * Math.cos(radPitch));
                    float dirY = (float) (-Math.sin(radPitch));
                    float dirZ = (float) (-Math.cos(radYaw) * Math.cos(radPitch));

                    int lightType = switch (light.type) {
                        case DIRECTIONAL -> 0;
                        case POINT -> 1;
                        case SPOT -> 2;
                    };

                    if (rhi instanceof MetalBackend metalRhi) {
                        metalRhi.setSceneLight(lightType, posX, posY, posZ, dirX, dirY, dirZ, light.r, light.g, light.b, light.intensity);
                    }
                    break;
                }
            }

            // Traversal loop across active 3D entities
            for (Entity entity : entities) {
                Transform tc = entity.getComponent(Transform.class);
                MeshFilter mc = entity.getComponent(MeshFilter.class);
                Material mat = entity.getComponent(Material.class);

                if (tc == null || mc == null) continue;
                if (!mc.isVisible) continue;

                String meshPath = mc.meshAssetPath;
                String texturePath = (mat != null) ? mat.textureAssetPath : null;

                if (meshPath != null) {
                    if (!streamingService.isMeshLoaded(meshPath)) {
                        streamingService.requestModelAsync(meshPath);
                    }

                    if (texturePath != null && !streamingService.isTextureLoaded(texturePath)) {
                        streamingService.requestTextureAsync(texturePath);
                    }

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
                        submitKey = meshPath;
                        verticesToSubmit = model.vertices;
                        vertexCountToSubmit = model.vertexCount;
                    } else if (streamingService.isMeshLoaded(meshPath)) {
                        submitKey = "[FALLBACK_CUBE]";
                        verticesToSubmit = fallbackCube.vertices;
                        vertexCountToSubmit = fallbackCube.vertexCount;
                    } else {
                        continue;
                    }

                    totalActiveVertices += vertexCountToSubmit;
                    entityDrawCalls++;

                    WorldTransform wt = editorManager.getSceneManager().getComputedWorldTransform(entity);
                    float groundY = findGroundSurfaceY(entity, wt, entities, editorManager);

                    if (rhi instanceof MetalBackend metalRhi) {
                        float reflectVal = (mat != null) ? mat.reflect : 0.0f;
                        metalRhi.submitEntityWithShadow(
                                submitKey,
                                verticesToSubmit,
                                vertexCountToSubmit,
                                texturePath,
                                pixels, texW, texH,
                                wt.px(), wt.py(), wt.pz(),
                                wt.rx(), wt.ry(), wt.rz(),
                                wt.sx(), wt.sy(), wt.sz(),
                                groundY, reflectVal
                        );
                    } else {
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
        }

        if (editorManager.is3DGridVisible()) {
            totalActiveVertices += editorManager.get3DGridVertexCount();
        }

        int totalDrawCalls = entityDrawCalls + (editorManager.is3DGridVisible() ? 1 : 0) + 2;
        EngineConsolePanel.updateTelemetry(totalDrawCalls, totalActiveVertices);
    }

    private float findGroundSurfaceY(Entity currentEntity, WorldTransform currentWt, List<Entity> allEntities, EditorWorkspace workspace) {
        float rayX = currentWt.px();
        float rayY = currentWt.py();
        float rayZ = currentWt.pz();

        float bestGroundY = 0.0f; // Default ground floor grid height

        if (allEntities != null) {
            for (Entity other : allEntities) {
                if (other == currentEntity) continue;
                MeshFilter otherMc = other.getComponent(MeshFilter.class);
                if (otherMc == null || !otherMc.isVisible) continue;

                WorldTransform otherWt = workspace.getSceneManager().getComputedWorldTransform(other);
                float otherX = otherWt.px();
                float otherY = otherWt.py();
                float otherZ = otherWt.pz();
                float otherSx = Math.max(0.2f, otherWt.sx());
                float otherSy = Math.max(0.2f, otherWt.sy());
                float otherSz = Math.max(0.2f, otherWt.sz());

                float dx = rayX - otherX;
                float dz = rayZ - otherZ;
                float radiusSq = dx * dx + dz * dz;
                float maxRadius = Math.max(otherSx, otherSz) * 1.1f;

                // Check if downward ray intersects entity footprint
                if (radiusSq <= maxRadius * maxRadius) {
                    float topY = otherY + otherSy;
                    if (topY < rayY + 0.05f && topY > bestGroundY) {
                        bestGroundY = topY;
                    }
                }
            }
        }
        return bestGroundY;
    }
}
