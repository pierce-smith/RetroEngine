package com.retro.engine.scene.entity;

import com.retro.engine.animation.clips.AnimationClip;
import com.retro.engine.animation.state.AnimatorController;
import com.retro.engine.animation.state.AnimatorState;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.assets.importers.SkeletonImporter;
import com.retro.engine.scene.components.media.AnimatorComponent;
import com.retro.engine.scene.components.media.AudioSource;
import com.retro.engine.scene.components.rendering.Camera;
import com.retro.engine.scene.components.rendering.Light;
import com.retro.engine.scene.components.rendering.Material;
import com.retro.engine.scene.components.transform.Transform;

import java.util.List;

public class EntitySpawner {

    public Entity spawnDefaultCube(List<Entity> sceneEntities) {
        String entityId = "entity_" + (sceneEntities.size() + 1);
        String name = "Cube_" + (sceneEntities.size() + 1);
        Entity entity = new Entity(entityId, name, "Assets/Models/cube.obj");
        sceneEntities.add(entity);
        return entity;
    }

    public Entity spawnLightSource(List<Entity> sceneEntities) {
        String entityId = "light_" + (sceneEntities.size() + 1);
        String name = "DirectionalLight_" + (sceneEntities.size() + 1);
        Entity entity = new Entity(entityId, name, null);
        entity.addComponent(new Light());
        sceneEntities.add(entity);
        return entity;
    }

    public Entity spawnAudioSource(List<Entity> sceneEntities) {
        String entityId = "audio_" + (sceneEntities.size() + 1);
        String name = "AudioSource_" + (sceneEntities.size() + 1);
        Entity entity = new Entity(entityId, name, null);
        entity.addComponent(new AudioSource());
        sceneEntities.add(entity);
        return entity;
    }

    public Entity spawnCameraEntity(List<Entity> sceneEntities) {
        String entityId = "camera_" + (sceneEntities.size() + 1);
        String name = "Main Camera_" + (sceneEntities.size() + 1);
        Entity entity = new Entity(entityId, name, null);
        entity.addComponent(new Camera());
        sceneEntities.add(entity);
        return entity;
    }

    public Entity spawnEntityFromAsset(AssetMeta meshAsset, List<Entity> sceneEntities) {
        if (meshAsset == null || meshAsset.type != AssetType.MESH_3D) return null;
        String entityId = "entity_" + (sceneEntities.size() + 1);
        String name = meshAsset.name.replaceAll("\\.[^.]+$", "");
        Entity entity = new Entity(entityId, name, meshAsset.absolutePath);
        
        attachAnimatorIfRigged(entity, meshAsset.absolutePath);

        sceneEntities.add(entity);
        return entity;
    }

    public Entity duplicateEntity(Entity selectedEntity, List<Entity> sceneEntities) {
        if (selectedEntity == null) {
            return spawnDefaultCube(sceneEntities);
        }
        String entityId = "entity_" + System.currentTimeMillis();
        String name = selectedEntity.name + "_Copy";
        Entity copy = new Entity(entityId, name, selectedEntity.getMeshAssetPath());
        copy.parentId = selectedEntity.parentId;
        if (copy.parentId != null) {
            Entity p = findEntityById(copy.parentId, sceneEntities);
            if (p != null) p.addChildId(copy.id);
        }

        Transform origTc = selectedEntity.getTransform();
        Transform copyTc = copy.getTransform();
        if (origTc != null && copyTc != null) {
            copyTc.posX = origTc.posX + 0.5f; copyTc.posY = origTc.posY; copyTc.posZ = origTc.posZ;
            copyTc.rotX = origTc.rotX; copyTc.rotY = origTc.rotY; copyTc.rotZ = origTc.rotZ;
            copyTc.scaleX = origTc.scaleX; copyTc.scaleY = origTc.scaleY; copyTc.scaleZ = origTc.scaleZ;
        }

        Material origMat = selectedEntity.getComponent(Material.class);
        if (origMat != null) {
            Material copyMat = copy.getComponent(Material.class);
            if (copyMat == null) { copyMat = new Material(); copy.addComponent(copyMat); }
            copyMat.textureAssetPath = origMat.textureAssetPath;
            copyMat.textureName = origMat.textureName;
            copyMat.shaderName = origMat.shaderName;
        }

        Light origLc = selectedEntity.getComponent(Light.class);
        if (origLc != null) {
            Light copyLc = new Light();
            copyLc.type = origLc.type; copyLc.intensity = origLc.intensity;
            copyLc.r = origLc.r; copyLc.g = origLc.g; copyLc.b = origLc.b;
            copy.addComponent(copyLc);
        }

        Camera origCc = selectedEntity.getComponent(Camera.class);
        if (origCc != null) {
            Camera copyCc = new Camera(origCc.fov, origCc.nearPlane, origCc.farPlane);
            copyCc.isPrimary = origCc.isPrimary;
            copy.addComponent(copyCc);
        }

        AudioSource origAc = selectedEntity.getComponent(AudioSource.class);
        if (origAc != null) {
            AudioSource copyAc = new AudioSource(origAc.audioAssetPath);
            copyAc.setVolume(origAc.volume);
            copyAc.setPitch(origAc.pitch);
            copyAc.loop = origAc.loop;
            copyAc.setDistances(origAc.minDistance, origAc.maxDistance);
            copyAc.attenuation = origAc.attenuation;
            copyAc.state = origAc.state;
            copy.addComponent(copyAc);
        }

        AnimatorComponent origAnim = selectedEntity.getComponent(AnimatorComponent.class);
        if (origAnim != null) {
            AnimatorComponent copyAnim = new AnimatorComponent();
            copyAnim.skeleton = origAnim.skeleton;
            copyAnim.controller = origAnim.controller;
            copyAnim.currentClip = origAnim.currentClip;
            copyAnim.activeClipName = origAnim.activeClipName;
            copy.addComponent(copyAnim);
        }

        sceneEntities.add(copy);
        return copy;
    }

    public void deleteEntity(Entity selectedEntity, List<Entity> sceneEntities) {
        if (selectedEntity != null && sceneEntities != null) {
            for (Entity e : sceneEntities) {
                if (selectedEntity.id.equalsIgnoreCase(e.parentId)) {
                    e.parentId = null;
                }
            }
            if (selectedEntity.parentId != null) {
                Entity p = findEntityById(selectedEntity.parentId, sceneEntities);
                if (p != null) p.removeChildId(selectedEntity.id);
            }
            sceneEntities.remove(selectedEntity);
        }
    }

    public void assignTextureToSelectedEntity(Entity selectedEntity, AssetMeta textureAsset) {
        if (selectedEntity == null || textureAsset == null || textureAsset.type != AssetType.TEXTURE) return;
        selectedEntity.setTextureAsset(textureAsset.absolutePath, textureAsset.name);
    }

    public void assignMeshToSelectedEntity(Entity selectedEntity, AssetMeta meshAsset) {
        if (selectedEntity == null || meshAsset == null || meshAsset.type != AssetType.MESH_3D) return;
        selectedEntity.setMeshAssetPath(meshAsset.absolutePath);
        attachAnimatorIfRigged(selectedEntity, meshAsset.absolutePath);
    }

    public void assignAudioToSelectedEntity(Entity selectedEntity, AssetMeta audioAsset) {
        if (selectedEntity == null || audioAsset == null || audioAsset.type != AssetType.AUDIO) return;
        AudioSource ac = selectedEntity.getComponent(AudioSource.class);
        if (ac == null) {
            ac = new AudioSource();
            selectedEntity.addComponent(ac);
        }
        ac.audioAssetPath = audioAsset.absolutePath;
    }

        public static void attachAnimatorIfRigged(Entity entity, String meshPath) {
        if (entity == null || meshPath == null || meshPath.trim().isEmpty()) return;
        try {
            SkeletonImporter.SkeletonImportResult result = SkeletonImporter.loadSkeletonAndAnimations(meshPath);
            boolean hasBones = (result != null && result.skeleton() != null && result.skeleton().getBoneCount() > 0);
            boolean hasAnims = (result != null && result.animations() != null && !result.animations().isEmpty());

            if (hasBones || hasAnims) {
                AnimatorComponent animator = entity.getComponent(AnimatorComponent.class);
                if (animator == null) {
                    animator = new AnimatorComponent();
                    entity.addComponent(animator);
                }
                if (hasBones) {
                    animator.skeleton = result.skeleton();
                }

                AnimatorController controller = animator.controller;
                if (controller == null) {
                    controller = new AnimatorController(entity.name + "_Controller");
                    animator.controller = controller;
                }

                if (hasAnims) {
                    // Clear default mock states (Idle, Walk, Run, Jump) if real imported clips exist
                    controller.clearStates();

                    float gridX = -0.20f;
                    float gridY = 0.65f;
                    int col = 0;

                    for (AnimationClip clip : result.animations()) {
                        if (controller.getState(clip.name) == null) {
                            AnimatorState state = new AnimatorState(clip.name, clip);
                            state.graphX = gridX + (col % 3) * 0.38f;
                            state.graphY = gridY - (col / 3) * 0.25f;
                            controller.addState(state);
                            col++;
                        }
                    }
                    if (!result.animations().isEmpty()) {
                        animator.currentClip = result.animations().get(0);
                        animator.activeClipName = animator.currentClip.name;
                        controller.setInitialState(result.animations().get(0).name);
                        // Auto-play the entry clip as soon as the rig finishes loading
                        animator.playbackTime = 0.0f;
                        animator.isPlaying = true;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[EntitySpawner] Skeletal animation check failed for " + meshPath + ": " + e.getMessage());
        }
    }

private Entity findEntityById(String id, List<Entity> sceneEntities) {
        if (id == null || id.isEmpty() || sceneEntities == null) return null;
        for (Entity e : sceneEntities) {
            if (e.id != null && e.id.equalsIgnoreCase(id)) return e;
        }
        return null;
    }
}
