package com.retro.engine.scene;

import com.retro.engine.scene.Entity;
import com.retro.engine.scene.SceneWorld.PlayState;
import com.retro.engine.scene.components.AnimatorComponent;
import com.retro.engine.scene.components.AudioSource;
import com.retro.engine.scene.components.Camera;
import com.retro.engine.scene.components.Light;
import com.retro.engine.scene.components.Material;
import com.retro.engine.scene.components.Transform;
import com.retro.engine.scene.components.ScriptComponent;
import com.retro.engine.scripting.ScriptEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * Delegate managing runtime PlayState simulation lifecycle (EDITING, SIMULATING, PAUSED),
 * taking scene snapshot clones on Play, restoring state on Stop, and evaluating 60 Hz
 * spatial audio 3D Euclidean distance attenuation and Gameplay Script ticks during runtime tick.
 */
public class SimulationSystem {

    private PlayState playState = PlayState.EDITING;
    private List<Entity> sceneSnapshot = null;

    public PlayState getPlayState() {
        return playState;
    }

    public void play(List<Entity> activeEntities) {
        if (playState == PlayState.EDITING) {
            this.sceneSnapshot = cloneSceneEntities(activeEntities);
            this.playState = PlayState.SIMULATING;
            ScriptEngine.initializeScripts(activeEntities, null);
            System.out.println("[SimulationSystem] Simulation started; gameplay scripts initialized.");
        } else if (playState == PlayState.PAUSED) {
            this.playState = PlayState.SIMULATING;
        }
    }

    public void pause() {
        if (playState == PlayState.SIMULATING) {
            this.playState = PlayState.PAUSED;
            System.out.println("[SimulationSystem] Simulation paused.");
        }
    }

    public List<Entity> stop(List<Entity> activeEntities) {
        if (playState == PlayState.SIMULATING || playState == PlayState.PAUSED) {
            ScriptEngine.destroyScripts(activeEntities);
            List<Entity> restored = null;
            if (sceneSnapshot != null) {
                restored = sceneSnapshot;
                this.sceneSnapshot = null;
            }
            this.playState = PlayState.EDITING;
            System.out.println("[SimulationSystem] Simulation stopped; gameplay scripts destroyed and state restored.");
            return restored;
        }
        return null;
    }

    public void update(float deltaTime, List<Entity> activeEntities) {
        if (playState == PlayState.SIMULATING && activeEntities != null) {
            // 1. Execute gameplay script frame ticks
            ScriptEngine.updateScripts(activeEntities, deltaTime, null);

            // 2. Find active listener position (Primary Camera or default origin)
            float listenerX = 0.0f, listenerY = 0.0f, listenerZ = 0.0f;
            boolean listenerFound = false;

            for (Entity entity : activeEntities) {
                Camera cam = entity.getComponent(Camera.class);
                if (cam != null && cam.isPrimary) {
                    Transform tc = entity.getTransform();
                    if (tc != null) {
                        listenerX = tc.posX;
                        listenerY = tc.posY;
                        listenerZ = tc.posZ;
                        listenerFound = true;
                        break;
                    }
                }
            }

            if (!listenerFound) {
                for (Entity entity : activeEntities) {
                    Camera cam = entity.getComponent(Camera.class);
                    if (cam != null) {
                        Transform tc = entity.getTransform();
                        if (tc != null) {
                            listenerX = tc.posX;
                            listenerY = tc.posY;
                            listenerZ = tc.posZ;
                            break;
                        }
                    }
                }
            }

            // 3. Continuous 3D Spatial Audio Attenuation Tick
            for (Entity entity : activeEntities) {
                Transform tc = entity.getTransform();
                AudioSource audioSource = entity.getComponent(AudioSource.class);
                if (audioSource != null && tc != null) {
                    audioSource.applySpatialGain(
                        listenerX, listenerY, listenerZ,
                        tc.posX, tc.posY, tc.posZ
                    );
                }
            }
        }
    }

    public List<Entity> cloneSceneEntities(List<Entity> source) {
        if (source == null) return new ArrayList<>();
        List<Entity> copy = new ArrayList<>();
        for (Entity orig : source) {
            Entity cloned = new Entity(orig.id + "_sim", orig.name, orig.getMeshAssetPath());
            cloned.id = orig.id;
            cloned.selected = orig.selected;
            cloned.parentId = orig.parentId;
            cloned.expandedInHierarchy = orig.expandedInHierarchy;

            Transform origTc = orig.getTransform();
            Transform cloneTc = cloned.getTransform();
            if (origTc != null && cloneTc != null) {
                cloneTc.posX = origTc.posX; cloneTc.posY = origTc.posY; cloneTc.posZ = origTc.posZ;
                cloneTc.rotX = origTc.rotX; cloneTc.rotY = origTc.rotY; cloneTc.rotZ = origTc.rotZ;
                cloneTc.scaleX = origTc.scaleX; cloneTc.scaleY = origTc.scaleY; cloneTc.scaleZ = origTc.scaleZ;
            }

            Material origMat = orig.getComponent(Material.class);
            if (origMat != null) {
                Material cloneMat = cloned.getComponent(Material.class);
                if (cloneMat == null) { cloneMat = new Material(); cloned.addComponent(cloneMat); }
                cloneMat.textureAssetPath = origMat.textureAssetPath;
                cloneMat.textureName = origMat.textureName;
                cloneMat.shaderName = origMat.shaderName;
            }

            Light origLc = orig.getComponent(Light.class);
            if (origLc != null) {
                Light cloneLc = cloned.getComponent(Light.class);
                if (cloneLc == null) { cloneLc = new Light(); cloned.addComponent(cloneLc); }
                cloneLc.type = origLc.type; cloneLc.intensity = origLc.intensity;
                cloneLc.r = origLc.r; cloneLc.g = origLc.g; cloneLc.b = origLc.b;
            }

            Camera origCc = orig.getComponent(Camera.class);
            if (origCc != null) {
                Camera cloneCc = cloned.getComponent(Camera.class);
                if (cloneCc == null) { cloneCc = new Camera(); cloned.addComponent(cloneCc); }
                cloneCc.fov = origCc.fov; cloneCc.nearPlane = origCc.nearPlane;
                cloneCc.farPlane = origCc.farPlane; cloneCc.isPrimary = origCc.isPrimary;
            }

            AudioSource origAc = orig.getComponent(AudioSource.class);
            if (origAc != null) {
                AudioSource cloneAc = new AudioSource(origAc.audioAssetPath);
                cloneAc.setVolume(origAc.volume);
                cloneAc.setPitch(origAc.pitch);
                cloneAc.loop = origAc.loop;
                cloneAc.setDistances(origAc.minDistance, origAc.maxDistance);
                cloneAc.attenuation = origAc.attenuation;
                cloneAc.state = origAc.state;
                cloned.addComponent(cloneAc);
            }

            ScriptComponent origSc = orig.getComponent(ScriptComponent.class);
            if (origSc != null) {
                ScriptComponent cloneSc = new ScriptComponent(origSc.scriptAssetPath, origSc.scriptName);
                cloneSc.enabled = origSc.enabled;
                cloned.addComponent(cloneSc);
            }

            AnimatorComponent origAnim = orig.getComponent(AnimatorComponent.class);
            if (origAnim != null) {
                AnimatorComponent cloneAnim = new AnimatorComponent();
                cloneAnim.skeleton = origAnim.skeleton;
                cloneAnim.controller = origAnim.controller;
                cloneAnim.currentClip = origAnim.currentClip;
                cloneAnim.activeClipName = origAnim.activeClipName;
                cloneAnim.playbackTime = origAnim.playbackTime;
                cloneAnim.playbackSpeed = origAnim.playbackSpeed;
                cloneAnim.isPlaying = origAnim.isPlaying;
                cloned.addComponent(cloneAnim);
            }

            copy.add(cloned);
        }
        return copy;
    }
}
