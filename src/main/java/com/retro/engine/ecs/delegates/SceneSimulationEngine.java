package com.retro.engine.ecs.delegates;

import com.retro.engine.ecs.SceneEntity;
import com.retro.engine.ecs.SceneManager.PlayState;
import com.retro.engine.ecs.components.AudioSourceComponent;
import com.retro.engine.ecs.components.CameraComponent;
import com.retro.engine.ecs.components.LightComponent;
import com.retro.engine.ecs.components.MaterialComponent;
import com.retro.engine.ecs.components.TransformComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Delegate managing runtime PlayState simulation lifecycle (EDITING, SIMULATING, PAUSED),
 * taking scene snapshot clones on Play, and restoring state on Stop.
 */
public class SceneSimulationEngine {

    private PlayState playState = PlayState.EDITING;
    private List<SceneEntity> sceneSnapshot = null;

    public PlayState getPlayState() {
        return playState;
    }

    public void play(List<SceneEntity> activeEntities) {
        if (playState == PlayState.EDITING) {
            this.sceneSnapshot = cloneSceneEntities(activeEntities);
            this.playState = PlayState.SIMULATING;
            System.out.println("[SceneSimulationEngine] Simulation started.");
        } else if (playState == PlayState.PAUSED) {
            this.playState = PlayState.SIMULATING;
        }
    }

    public void pause() {
        if (playState == PlayState.SIMULATING) {
            this.playState = PlayState.PAUSED;
            System.out.println("[SceneSimulationEngine] Simulation paused.");
        }
    }

    public List<SceneEntity> stop(List<SceneEntity> activeEntities) {
        if (playState == PlayState.SIMULATING || playState == PlayState.PAUSED) {
            List<SceneEntity> restored = null;
            if (sceneSnapshot != null) {
                restored = sceneSnapshot;
                this.sceneSnapshot = null;
            }
            this.playState = PlayState.EDITING;
            System.out.println("[SceneSimulationEngine] Simulation stopped; state restored.");
            return restored;
        }
        return null;
    }

    public void update(float deltaTime, List<SceneEntity> activeEntities) {
        if (playState == PlayState.SIMULATING && activeEntities != null) {
            for (SceneEntity entity : activeEntities) {
                TransformComponent tc = entity.getTransform();
                if (tc != null) {
                    tc.rotY = (tc.rotY + 45.0f * deltaTime) % 360.0f;
                }
            }
        }
    }

    public List<SceneEntity> cloneSceneEntities(List<SceneEntity> source) {
        if (source == null) return new ArrayList<>();
        List<SceneEntity> copy = new ArrayList<>();
        for (SceneEntity orig : source) {
            SceneEntity cloned = new SceneEntity(orig.id + "_sim", orig.name, orig.getMeshAssetPath());
            cloned.id = orig.id;
            cloned.selected = orig.selected;
            cloned.parentId = orig.parentId;
            cloned.expandedInHierarchy = orig.expandedInHierarchy;

            TransformComponent origTc = orig.getTransform();
            TransformComponent cloneTc = cloned.getTransform();
            if (origTc != null && cloneTc != null) {
                cloneTc.posX = origTc.posX; cloneTc.posY = origTc.posY; cloneTc.posZ = origTc.posZ;
                cloneTc.rotX = origTc.rotX; cloneTc.rotY = origTc.rotY; cloneTc.rotZ = origTc.rotZ;
                cloneTc.scaleX = origTc.scaleX; cloneTc.scaleY = origTc.scaleY; cloneTc.scaleZ = origTc.scaleZ;
            }

            MaterialComponent origMat = orig.getComponent(MaterialComponent.class);
            if (origMat != null) {
                MaterialComponent cloneMat = cloned.getComponent(MaterialComponent.class);
                if (cloneMat == null) { cloneMat = new MaterialComponent(); cloned.addComponent(cloneMat); }
                cloneMat.textureAssetPath = origMat.textureAssetPath;
                cloneMat.textureName = origMat.textureName;
                cloneMat.shaderName = origMat.shaderName;
            }

            LightComponent origLc = orig.getComponent(LightComponent.class);
            if (origLc != null) {
                LightComponent cloneLc = cloned.getComponent(LightComponent.class);
                if (cloneLc == null) { cloneLc = new LightComponent(); cloned.addComponent(cloneLc); }
                cloneLc.type = origLc.type; cloneLc.intensity = origLc.intensity;
                cloneLc.r = origLc.r; cloneLc.g = origLc.g; cloneLc.b = origLc.b;
            }

            CameraComponent origCc = orig.getComponent(CameraComponent.class);
            if (origCc != null) {
                CameraComponent cloneCc = cloned.getComponent(CameraComponent.class);
                if (cloneCc == null) { cloneCc = new CameraComponent(); cloned.addComponent(cloneCc); }
                cloneCc.fov = origCc.fov; cloneCc.nearPlane = origCc.nearPlane;
                cloneCc.farPlane = origCc.farPlane; cloneCc.isPrimary = origCc.isPrimary;
            }

            AudioSourceComponent origAc = orig.getComponent(AudioSourceComponent.class);
            if (origAc != null) {
                AudioSourceComponent cloneAc = new AudioSourceComponent(origAc.audioAssetPath);
                cloneAc.setVolume(origAc.volume);
                cloneAc.setPitch(origAc.pitch);
                cloneAc.loop = origAc.loop;
                cloneAc.setDistances(origAc.minDistance, origAc.maxDistance);
                cloneAc.attenuation = origAc.attenuation;
                cloneAc.state = origAc.state;
                cloned.addComponent(cloneAc);
            }

            copy.add(cloned);
        }
        return copy;
    }
}
