package com.retro.engine.ecs;

import com.retro.engine.ecs.components.CameraComponent;
import com.retro.engine.ecs.components.LightComponent;
import com.retro.engine.ecs.components.MaterialComponent;
import com.retro.engine.ecs.components.MeshRendererComponent;
import com.retro.engine.ecs.components.TransformComponent;
import com.retro.engine.editor.assets.AssetEntry;
import com.retro.engine.editor.assets.AssetType;
import com.retro.engine.editor.dialogs.NativeFileDialog;
import com.retro.engine.serialization.SceneSerializer;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SceneManager handles central entity storage, selection, parent-child scene graph hierarchy,
 * scene persistence, and the Play/Pause runtime simulation state machine.
 */
public class SceneManager {
    public enum PlayState { EDITING, SIMULATING, PAUSED }

    public record WorldTransform(
            float px, float py, float pz,
            float rx, float ry, float rz,
            float sx, float sy, float sz
    ) {}

    private final List<SceneEntity> sceneEntities = new ArrayList<>();
    private SceneEntity selectedEntity = null;
    private String activeScenePath = "Assets/Scenes/Default.scene";

    private PlayState playState = PlayState.EDITING;
    private List<SceneEntity> sceneSnapshot = null;

    public SceneManager() {}

    public List<SceneEntity> getSceneEntities() { return Collections.unmodifiableList(sceneEntities); }
    public SceneEntity getSelectedEntity() { return selectedEntity; }

    public void selectEntity(SceneEntity entity) {
        for (SceneEntity e : sceneEntities) {
            e.selected = (e == entity);
        }
        this.selectedEntity = entity;
    }

    public String getActiveScenePath() { return activeScenePath; }
    public void setActiveScenePath(String path) { if (path != null) this.activeScenePath = path; }

    public PlayState getPlayState() { return playState; }

    public void play() {
        if (playState == PlayState.EDITING) {
            this.sceneSnapshot = cloneSceneEntities(sceneEntities);
            this.playState = PlayState.SIMULATING;
            System.out.println("[SceneManager] Simulation started.");
        } else if (playState == PlayState.PAUSED) {
            this.playState = PlayState.SIMULATING;
        }
    }

    public void pause() {
        if (playState == PlayState.SIMULATING) {
            this.playState = PlayState.PAUSED;
            System.out.println("[SceneManager] Simulation paused.");
        }
    }

    public void stop() {
        if (playState == PlayState.SIMULATING || playState == PlayState.PAUSED) {
            if (sceneSnapshot != null) {
                this.sceneEntities.clear();
                this.sceneEntities.addAll(sceneSnapshot);
                this.sceneSnapshot = null;
            }
            this.playState = PlayState.EDITING;
            System.out.println("[SceneManager] Simulation stopped; state restored.");
        }
    }

    public void update(float deltaTime) {
        if (playState == PlayState.SIMULATING) {
            for (SceneEntity entity : sceneEntities) {
                TransformComponent tc = entity.getTransform();
                if (tc != null) {
                    tc.rotY = (tc.rotY + 45.0f * deltaTime) % 360.0f;
                }
            }
        }
    }

    public SceneEntity findEntityById(String id) {
        if (id == null || id.isEmpty()) return null;
        for (SceneEntity e : sceneEntities) {
            if (e.id != null && e.id.equalsIgnoreCase(id)) return e;
        }
        return null;
    }

    public WorldTransform getComputedWorldTransform(SceneEntity entity) {
        if (entity == null) return new WorldTransform(0,0,0, 0,0,0, 1,1,1);
        TransformComponent tc = entity.getTransform();
        if (tc == null) return new WorldTransform(0,0,0, 0,0,0, 1,1,1);

        if (entity.parentId == null || entity.parentId.isEmpty()) {
            return new WorldTransform(tc.posX, tc.posY, tc.posZ, tc.rotX, tc.rotY, tc.rotZ, tc.scaleX, tc.scaleY, tc.scaleZ);
        }

        SceneEntity parent = findEntityById(entity.parentId);
        if (parent == null || parent == entity) {
            return new WorldTransform(tc.posX, tc.posY, tc.posZ, tc.rotX, tc.rotY, tc.rotZ, tc.scaleX, tc.scaleY, tc.scaleZ);
        }

        WorldTransform parentWt = getComputedWorldTransform(parent);

        float sx = parentWt.sx() * tc.scaleX;
        float sy = parentWt.sy() * tc.scaleY;
        float sz = parentWt.sz() * tc.scaleZ;

        float rx = (parentWt.rx() + tc.rotX) % 360.0f;
        float ry = (parentWt.ry() + tc.rotY) % 360.0f;
        float rz = (parentWt.rz() + tc.rotZ) % 360.0f;

        float scaledLx = tc.posX * parentWt.sx();
        float scaledLy = tc.posY * parentWt.sy();
        float scaledLz = tc.posZ * parentWt.sz();

        float[] rot = rotateVector(scaledLx, scaledLy, scaledLz, parentWt.rx(), parentWt.ry(), parentWt.rz());

        float px = parentWt.px() + rot[0];
        float py = parentWt.py() + rot[1];
        float pz = parentWt.pz() + rot[2];

        return new WorldTransform(px, py, pz, rx, ry, rz, sx, sy, sz);
    }

    private static float[] rotateVector(float lx, float ly, float lz, float rx, float ry, float rz) {
        float radX = (float) Math.toRadians(rx);
        float radY = (float) Math.toRadians(ry);
        float radZ = (float) Math.toRadians(rz);

        float cx = (float) Math.cos(radX), sx = (float) Math.sin(radX);
        float cy = (float) Math.cos(radY), sy = (float) Math.sin(radY);
        float cz = (float) Math.cos(radZ), sz = (float) Math.sin(radZ);

        float y1 = cx * ly - sx * lz;
        float z1 = sx * ly + cx * lz;
        float x1 = lx;

        float x2 = cy * x1 + sy * z1;
        float z2 = -sy * x1 + cy * z1;
        float y2 = y1;

        float x3 = cz * x2 - sz * y2;
        float y3 = sz * x2 + cz * y2;
        float z3 = z2;

        return new float[]{ x3, y3, z3 };
    }

    public boolean setParent(SceneEntity child, SceneEntity parent) {
        if (child == null) return false;
        if (parent != null && (parent == child || parent.isDescendantOf(child, sceneEntities))) {
            System.out.println("[SceneManager] Circular parenting prevented!");
            return false;
        }

        if (child.parentId != null) {
            SceneEntity oldParent = findEntityById(child.parentId);
            if (oldParent != null) {
                oldParent.removeChildId(child.id);
            }
        }

        if (parent == null) {
            child.parentId = null;
            System.out.println("[SceneManager] Unparented entity '" + child.name + "'");
        } else {
            child.parentId = parent.id;
            parent.addChildId(child.id);
            System.out.println("[SceneManager] Parented '" + child.name + "' to '" + parent.name + "'");
        }
        return true;
    }

    private List<SceneEntity> cloneSceneEntities(List<SceneEntity> source) {
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

            copy.add(cloned);
        }
        return copy;
    }

    public void newScene() { stop(); this.sceneEntities.clear(); this.selectedEntity = null; this.activeScenePath = "Assets/Scenes/Untitled.scene"; }
    public void promptOpenSceneWithNativeDialog() { String defaultDir = new File("Assets/Scenes").getAbsolutePath(); String importedPath = NativeFileDialog.openFileDialog("Open Scene File", defaultDir, "Retro Scene (*.scene)", "scene"); if (importedPath != null && !importedPath.trim().isEmpty()) { loadSceneFromFile(importedPath); } }
    public void promptSaveSceneWithNativeDialog(List<AssetEntry> assetDirectory) { String defaultDir = new File("Assets/Scenes").getAbsolutePath(); String targetPath = NativeFileDialog.saveFileDialog("Save Scene File", defaultDir, "Retro Scene (*.scene)", "scene"); if (targetPath != null && !targetPath.trim().isEmpty()) { saveActiveScene(targetPath, assetDirectory); } }

    public void saveActiveScene(String filePath, List<AssetEntry> assetDirectory) { if (filePath == null || filePath.trim().isEmpty()) return; SceneSerializer.saveScene(filePath, sceneEntities, assetDirectory); this.activeScenePath = filePath; }
    public SceneSerializer.SceneLoadData loadSceneFromFile(String filePath) { stop(); this.activeScenePath = filePath; sceneEntities.clear(); selectedEntity = null; SceneSerializer.SceneLoadData data = SceneSerializer.loadSceneData(filePath); if (data != null && data.entities() != null) { sceneEntities.addAll(data.entities()); selectEntity(sceneEntities.isEmpty() ? null : sceneEntities.get(0)); } return data; }

    public void deleteSelectedEntity() {
        if (selectedEntity != null) {
            // Unparent children of deleted entity
            for (SceneEntity e : sceneEntities) {
                if (selectedEntity.id.equalsIgnoreCase(e.parentId)) {
                    e.parentId = null;
                }
            }
            if (selectedEntity.parentId != null) {
                SceneEntity p = findEntityById(selectedEntity.parentId);
                if (p != null) p.removeChildId(selectedEntity.id);
            }
            sceneEntities.remove(selectedEntity);
            selectedEntity = null;
        }
    }

    public void duplicateSelectedEntity() {
        if (selectedEntity == null) { spawnDefaultCube(); return; }
        String entityId = "entity_" + System.currentTimeMillis();
        String name = selectedEntity.name + "_Copy";
        SceneEntity copy = new SceneEntity(entityId, name, selectedEntity.getMeshAssetPath());
        copy.parentId = selectedEntity.parentId;
        if (copy.parentId != null) {
            SceneEntity p = findEntityById(copy.parentId);
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

        sceneEntities.add(copy);
        selectEntity(copy);
    }

    public void spawnDefaultCube() { String entityId = "entity_" + (sceneEntities.size() + 1); String name = "Cube_" + (sceneEntities.size() + 1); SceneEntity entity = new SceneEntity(entityId, name, "Assets/Models/cube.obj"); sceneEntities.add(entity); selectEntity(entity); }
    public void spawnLightSource() { String entityId = "light_" + (sceneEntities.size() + 1); String name = "DirectionalLight_" + (sceneEntities.size() + 1); SceneEntity entity = new SceneEntity(entityId, name, null); entity.addComponent(new LightComponent()); sceneEntities.add(entity); selectEntity(entity); }
    public void spawnCameraEntity() { String entityId = "camera_" + (sceneEntities.size() + 1); String name = "Main Camera_" + (sceneEntities.size() + 1); SceneEntity entity = new SceneEntity(entityId, name, null); entity.addComponent(new CameraComponent()); sceneEntities.add(entity); selectEntity(entity); }
    public void spawnEntityFromAsset(AssetEntry meshAsset) { if (meshAsset == null || meshAsset.type != AssetType.MESH_3D) return; String entityId = "entity_" + (sceneEntities.size() + 1); String name = meshAsset.name.replaceAll("\\.[^.]+$", ""); SceneEntity entity = new SceneEntity(entityId, name, meshAsset.absolutePath); sceneEntities.add(entity); selectEntity(entity); }
    public void assignTextureToSelectedEntity(AssetEntry textureAsset) { if (selectedEntity == null || textureAsset == null || textureAsset.type != AssetType.TEXTURE) return; selectedEntity.setTextureAsset(textureAsset.absolutePath, textureAsset.name); }
    public void assignMeshToSelectedEntity(AssetEntry meshAsset) { if (selectedEntity == null || meshAsset == null || meshAsset.type != AssetType.MESH_3D) return; selectedEntity.setMeshAssetPath(meshAsset.absolutePath); }
}
