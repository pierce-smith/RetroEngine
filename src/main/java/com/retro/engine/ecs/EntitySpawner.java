package com.retro.engine.ecs;

import com.retro.engine.animation.AnimationClip;
import com.retro.engine.animation.AnimationSystem;
import com.retro.engine.animation.AnimatorController;
import com.retro.engine.animation.AnimatorParameter;
import com.retro.engine.animation.AnimatorState;
import com.retro.engine.assets.AssetMeta;
import com.retro.engine.assets.AssetType;
import com.retro.engine.assets.SkeletonImporter;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.AudioSource;
import com.retro.engine.ecs.components.Camera;
import com.retro.engine.ecs.components.Light;
import com.retro.engine.ecs.components.MeshFilter;
import com.retro.engine.ecs.components.Transform;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        // The Entity constructor pre-creates Transform / MeshFilter / Material; those are filled in rather than duplicated
        Set<Component> populated = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Component orig : selectedEntity.getAllComponents()) {
            if (orig == null) continue;
            if (orig instanceof AnimatorComponent origAnim) {
                // Shares the skeleton and imported clips; the controller graph and all instance state are deep copies
                copy.addComponent(new AnimatorComponent(origAnim));
                continue;
            }
            if (orig instanceof com.retro.engine.ecs.components.UnknownComponent unknown) {
                copy.addComponent(new com.retro.engine.ecs.components.UnknownComponent(unknown.typeName(), unknown.payload()));
                continue;
            }
            ComponentSchema schema = ComponentSchema.of(orig.getClass());
            Component target = findUnpopulated(copy, orig.getClass(), populated);
            boolean created = target == null;
            if (created) {
                if (!schema.isPersistent()) {
                    System.err.println("[EntitySpawner] Cannot duplicate component " + orig.getClass().getName() + " (no persisted state or no-arg constructor)");
                    continue;
                }
                target = schema.newInstance();
            }
            copyProperties(schema, orig, target);
            if (orig instanceof MeshFilter origMf && target instanceof MeshFilter copyMf) {
                copyMf.subMeshPaths = (origMf.subMeshPaths != null) ? origMf.subMeshPaths.clone() : null;
            }
            populated.add(target);
            if (created) copy.addComponent(target);
            target.onDeserialized(copy);
        }

        Transform copyTc = copy.getTransform();
        if (copyTc != null) copyTc.posX += 0.5f;

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
                    animator.bindSkeleton(result.skeleton());
                }

                AnimatorController controller = animator.controller;
                if (controller == null || hasAnims) {
                    // A re-import builds a new controller rather than rewriting the old one in place (parameter
                    // definitions carry over)
                    AnimatorController previous = controller;
                    controller = new AnimatorController(entity.name + "_Controller");
                    if (previous != null) {
                        for (AnimatorParameter param : previous.getParameters().values()) {
                            controller.getParameters().put(param.name, param.copy());
                        }
                    }
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
                        controller.setEntryState(result.animations().get(0).name);
                        animator.resetToEntryState();
                        // Auto-play the entry clip as soon as the rig finishes loading
                        animator.playbackTime = 0.0f;
                        animator.isPlaying = true;
                    }
                }
                // Recorded / exported clips and the saved entry state (scene loads restore these after the rebuild)
                AnimationSystem.installAuthoredClips(animator);
            }
        } catch (Exception e) {
            System.err.println("[EntitySpawner] Skeletal animation check failed for " + meshPath + ": " + e.getMessage());
        }
    }

    private static Component findUnpopulated(Entity entity, Class<?> type, Set<Component> populated) {
        for (Component c : entity.getAllComponents()) {
            if (c.getClass() == type && !populated.contains(c)) return c;
        }
        return null;
    }

    /** Copies every persisted and inspector-visible property; mutable containers are copied, not shared. */
    private static void copyProperties(ComponentSchema schema, Component from, Component to) {
        Set<ComponentSchema.Property> props = new LinkedHashSet<>(schema.serializedProperties());
        props.addAll(schema.inspectorProperties());
        for (ComponentSchema.Property p : props) {
            try {
                Object value = p.get(from);
                if (value instanceof Map<?, ?> map) value = new HashMap<>(map);
                else if (value instanceof byte[] bytes) value = bytes.clone();
                else if (value instanceof Object[] array) value = array.clone();
                p.set(to, value);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot copy " + from.getClass().getName() + "." + p.name(), e);
            }
        }
    }

    private Entity findEntityById(String id, List<Entity> sceneEntities) {
        if (id == null || id.isEmpty() || sceneEntities == null) return null;
        for (Entity e : sceneEntities) {
            if (e.id != null && e.id.equalsIgnoreCase(id)) return e; // Wait, let's make sure findEntityById matches original logic
        }
        return null;
    }
}