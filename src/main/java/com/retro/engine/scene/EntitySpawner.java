package com.retro.engine.scene;

import com.retro.engine.Main;

import com.retro.engine.scene.Entity;
import com.retro.engine.scene.components.AudioSource;
import com.retro.engine.scene.components.Camera;
import com.retro.engine.scene.components.Light;
import com.retro.engine.scene.components.Material;
import com.retro.engine.scene.components.Transform;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;

import java.util.List;

/**
 * Delegate managing entity instantiation, duplication cloning, primitive spawning,
 * deletion reparenting, and asset component linking.
 */
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

    private Entity findEntityById(String id, List<Entity> sceneEntities) {
        if (id == null || id.isEmpty() || sceneEntities == null) return null;
        for (Entity e : sceneEntities) {
            if (e.id != null && e.id.equalsIgnoreCase(id)) return e;
        }
        return null;
    }
}
