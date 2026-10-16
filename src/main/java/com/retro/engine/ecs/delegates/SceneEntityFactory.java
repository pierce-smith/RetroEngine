package com.retro.engine.ecs.delegates;

import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.ecs.components.AudioSourceComponent;
import com.retro.engine.ecs.components.CameraComponent;
import com.retro.engine.ecs.components.LightComponent;
import com.retro.engine.ecs.components.MaterialComponent;
import com.retro.engine.ecs.components.TransformComponent;
import com.retro.engine.editor.assets.AssetEntry;
import com.retro.engine.editor.assets.AssetType;

import java.util.List;

/**
 * Delegate managing entity instantiation, duplication cloning, primitive spawning,
 * deletion reparenting, and asset component linking.
 */
public class SceneEntityFactory {

    public SceneEntity spawnDefaultCube(List<SceneEntity> sceneEntities) {
        String entityId = "entity_" + (sceneEntities.size() + 1);
        String name = "Cube_" + (sceneEntities.size() + 1);
        SceneEntity entity = new SceneEntity(entityId, name, "Assets/Models/cube.obj");
        sceneEntities.add(entity);
        return entity;
    }

    public SceneEntity spawnLightSource(List<SceneEntity> sceneEntities) {
        String entityId = "light_" + (sceneEntities.size() + 1);
        String name = "DirectionalLight_" + (sceneEntities.size() + 1);
        SceneEntity entity = new SceneEntity(entityId, name, null);
        entity.addComponent(new LightComponent());
        sceneEntities.add(entity);
        return entity;
    }

    public SceneEntity spawnAudioSource(List<SceneEntity> sceneEntities) {
        String entityId = "audio_" + (sceneEntities.size() + 1);
        String name = "AudioSource_" + (sceneEntities.size() + 1);
        SceneEntity entity = new SceneEntity(entityId, name, null);
        entity.addComponent(new AudioSourceComponent());
        sceneEntities.add(entity);
        return entity;
    }

    public SceneEntity spawnCameraEntity(List<SceneEntity> sceneEntities) {
        String entityId = "camera_" + (sceneEntities.size() + 1);
        String name = "Main Camera_" + (sceneEntities.size() + 1);
        SceneEntity entity = new SceneEntity(entityId, name, null);
        entity.addComponent(new CameraComponent());
        sceneEntities.add(entity);
        return entity;
    }

    public SceneEntity spawnEntityFromAsset(AssetEntry meshAsset, List<SceneEntity> sceneEntities) {
        if (meshAsset == null || meshAsset.type != AssetType.MESH_3D) return null;
        String entityId = "entity_" + (sceneEntities.size() + 1);
        String name = meshAsset.name.replaceAll("\\.[^.]+$", "");
        SceneEntity entity = new SceneEntity(entityId, name, meshAsset.absolutePath);
        sceneEntities.add(entity);
        return entity;
    }

    public SceneEntity duplicateEntity(SceneEntity selectedEntity, List<SceneEntity> sceneEntities) {
        if (selectedEntity == null) {
            return spawnDefaultCube(sceneEntities);
        }
        String entityId = "entity_" + System.currentTimeMillis();
        String name = selectedEntity.name + "_Copy";
        SceneEntity copy = new SceneEntity(entityId, name, selectedEntity.getMeshAssetPath());
        copy.parentId = selectedEntity.parentId;
        if (copy.parentId != null) {
            SceneEntity p = findEntityById(copy.parentId, sceneEntities);
            if (p != null) p.addChildId(copy.id);
        }

        TransformComponent origTc = selectedEntity.getTransform();
        TransformComponent copyTc = copy.getTransform();
        if (origTc != null && copyTc != null) {
            copyTc.posX = origTc.posX + 0.5f; copyTc.posY = origTc.posY; copyTc.posZ = origTc.posZ;
            copyTc.rotX = origTc.rotX; copyTc.rotY = origTc.rotY; copyTc.rotZ = origTc.rotZ;
            copyTc.scaleX = origTc.scaleX; copyTc.scaleY = origTc.scaleY; copyTc.scaleZ = origTc.scaleZ;
        }

        MaterialComponent origMat = selectedEntity.getComponent(MaterialComponent.class);
        if (origMat != null) {
            MaterialComponent copyMat = copy.getComponent(MaterialComponent.class);
            if (copyMat == null) { copyMat = new MaterialComponent(); copy.addComponent(copyMat); }
            copyMat.textureAssetPath = origMat.textureAssetPath;
            copyMat.textureName = origMat.textureName;
            copyMat.shaderName = origMat.shaderName;
        }

        LightComponent origLc = selectedEntity.getComponent(LightComponent.class);
        if (origLc != null) {
            LightComponent copyLc = new LightComponent();
            copyLc.type = origLc.type; copyLc.intensity = origLc.intensity;
            copyLc.r = origLc.r; copyLc.g = origLc.g; copyLc.b = origLc.b;
            copy.addComponent(copyLc);
        }

        CameraComponent origCc = selectedEntity.getComponent(CameraComponent.class);
        if (origCc != null) {
            CameraComponent copyCc = new CameraComponent(origCc.fov, origCc.nearPlane, origCc.farPlane);
            copyCc.isPrimary = origCc.isPrimary;
            copy.addComponent(copyCc);
        }

        AudioSourceComponent origAc = selectedEntity.getComponent(AudioSourceComponent.class);
        if (origAc != null) {
            AudioSourceComponent copyAc = new AudioSourceComponent(origAc.audioAssetPath);
            copyAc.setVolume(origAc.volume);
            copyAc.setPitch(origAc.pitch);
            copyAc.loop = origAc.loop;
            copyAc.setDistances(origAc.minDistance, origAc.maxDistance);
            copyAc.attenuation = origAc.attenuation;
            copyAc.state = origAc.state;
            copy.addComponent(copyAc);
        }

        sceneEntities.add(copy);
        return copy;
    }

    public void deleteEntity(SceneEntity selectedEntity, List<SceneEntity> sceneEntities) {
        if (selectedEntity != null && sceneEntities != null) {
            for (SceneEntity e : sceneEntities) {
                if (selectedEntity.id.equalsIgnoreCase(e.parentId)) {
                    e.parentId = null;
                }
            }
            if (selectedEntity.parentId != null) {
                SceneEntity p = findEntityById(selectedEntity.parentId, sceneEntities);
                if (p != null) p.removeChildId(selectedEntity.id);
            }
            sceneEntities.remove(selectedEntity);
        }
    }

    public void assignTextureToSelectedEntity(SceneEntity selectedEntity, AssetEntry textureAsset) {
        if (selectedEntity == null || textureAsset == null || textureAsset.type != AssetType.TEXTURE) return;
        selectedEntity.setTextureAsset(textureAsset.absolutePath, textureAsset.name);
    }

    public void assignMeshToSelectedEntity(SceneEntity selectedEntity, AssetEntry meshAsset) {
        if (selectedEntity == null || meshAsset == null || meshAsset.type != AssetType.MESH_3D) return;
        selectedEntity.setMeshAssetPath(meshAsset.absolutePath);
    }

    public void assignAudioToSelectedEntity(SceneEntity selectedEntity, AssetEntry audioAsset) {
        if (selectedEntity == null || audioAsset == null || audioAsset.type != AssetType.AUDIO) return;
        AudioSourceComponent ac = selectedEntity.getComponent(AudioSourceComponent.class);
        if (ac == null) {
            ac = new AudioSourceComponent();
            selectedEntity.addComponent(ac);
        }
        ac.audioAssetPath = audioAsset.absolutePath;
    }

    private SceneEntity findEntityById(String id, List<SceneEntity> sceneEntities) {
        if (id == null || id.isEmpty() || sceneEntities == null) return null;
        for (SceneEntity e : sceneEntities) {
            if (e.id != null && e.id.equalsIgnoreCase(id)) return e;
        }
        return null;
    }
}
