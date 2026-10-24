package com.retro.engine.scene.serialization;

import com.retro.engine.scene.components.gameplay.CharacterControllerComponent;
import com.retro.engine.scene.components.gameplay.ScriptComponent;
import com.retro.engine.scene.components.gameplay.VehicleControllerComponent;
import com.retro.engine.scene.components.media.AnimatorComponent;
import com.retro.engine.scene.components.media.AudioSource;
import com.retro.engine.scene.components.physics.BoxCollider;
import com.retro.engine.scene.components.physics.CapsuleCollider;
import com.retro.engine.scene.components.physics.RigidBodyComponent;
import com.retro.engine.scene.components.physics.SphereCollider;
import com.retro.engine.scene.components.rendering.Camera;
import com.retro.engine.scene.components.rendering.Light;
import com.retro.engine.scene.components.rendering.Material;
import com.retro.engine.scene.components.rendering.MeshFilter;
import com.retro.engine.scene.components.transform.Transform;
import com.retro.engine.scene.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Delegate managing zero-allocation in-place entity state backups,
 * state restorations on simulation stop, and deep scene cloning.
 */
public class EntityStateBackupManager {

    public static class EntityStateBackup {
        public Entity targetEntity;
        public String name;
        public String parentId;
        public boolean selected;
        public boolean expandedInHierarchy;

        // Transform
        public boolean hasTransform;
        public float posX, posY, posZ;
        public float rotX, rotY, rotZ;
        public float scaleX, scaleY, scaleZ;

        // RigidBody
        public boolean hasRigidBody;
        public RigidBodyComponent.MotionType rbMotionType;
        public float rbMass;
        public boolean rbUseGravity;
        public float rbLinearDrag, rbAngularDrag, rbFriction, rbRestitution;

        // CharacterController
        public boolean hasCharacterController;
        public float charMoveSpeed, charJumpForce, charStepOffset, charSlopeLimit, charGravityMultiplier;
        public boolean charIsGrounded;

        // VehicleController
        public boolean hasVehicleController;
        public float vehEngineTorque, vehMaxSpeed, vehSteeringAngle, vehBrakeForce;
        public float vehSuspensionStiffness, vehSuspensionDamping, vehSuspensionRestLength;
        public float vehWheelRadius, vehWheelTrackWidth, vehWheelBase;

        // AudioSource
        public boolean hasAudioSource;
        public float audioVolume, audioPitch;
        public boolean audioLoop;
        public float audioMinDist, audioMaxDist, audioAttenuation;
        public AudioSource.PlaybackState audioState;

        // Animator
        public boolean hasAnimator;
        public String animActiveClipName;
        public float animPlaybackTime, animPlaybackSpeed;
        public boolean animIsPlaying;

        // Light
        public boolean hasLight;
        public Light.LightType lightType;
        public float lightIntensity, lightR, lightG, lightB;

        // Camera
        public boolean hasCamera;
        public float cameraFov, cameraNearPlane, cameraFarPlane;
        public boolean cameraIsPrimary;

        // Material
        public boolean hasMaterial;
        public String matTextureAssetPath, matTextureName, matShaderName;
        public float matReflect;

        // MeshFilter
        public boolean hasMeshFilter;
        public String meshAssetPath;
        public boolean meshIsVisible;

        // BoxCollider
        public boolean hasBoxCollider;
        public float boxSizeX, boxSizeY, boxSizeZ;
        public float boxOffsetX, boxOffsetY, boxOffsetZ;
        public boolean boxIsTrigger;

        // SphereCollider
        public boolean hasSphereCollider;
        public float sphereRadius;
        public float sphereOffsetX, sphereOffsetY, sphereOffsetZ;
        public boolean sphereIsTrigger;

        // CapsuleCollider
        public boolean hasCapsuleCollider;
        public float capsuleRadius, capsuleHeight;
        public float capsuleOffsetX, capsuleOffsetY, capsuleOffsetZ;
        public boolean capsuleIsTrigger;

        public void capture(Entity entity) {
            this.targetEntity = entity;
            if (entity == null) return;

            this.name = entity.name;
            this.parentId = entity.parentId;
            this.selected = entity.selected;
            this.expandedInHierarchy = entity.expandedInHierarchy;

            Transform tc = entity.getTransform();
            this.hasTransform = (tc != null);
            if (hasTransform) {
                this.posX = tc.posX; this.posY = tc.posY; this.posZ = tc.posZ;
                this.rotX = tc.rotX; this.rotY = tc.rotY; this.rotZ = tc.rotZ;
                this.scaleX = tc.scaleX; this.scaleY = tc.scaleY; this.scaleZ = tc.scaleZ;
            }

            RigidBodyComponent rb = entity.getComponent(RigidBodyComponent.class);
            this.hasRigidBody = (rb != null);
            if (hasRigidBody) {
                this.rbMotionType = rb.motionType;
                this.rbMass = rb.mass;
                this.rbUseGravity = rb.useGravity;
                this.rbLinearDrag = rb.linearDrag;
                this.rbAngularDrag = rb.angularDrag;
                this.rbFriction = rb.friction;
                this.rbRestitution = rb.restitution;
            }

            CharacterControllerComponent charC = entity.getComponent(CharacterControllerComponent.class);
            this.hasCharacterController = (charC != null);
            if (hasCharacterController) {
                this.charMoveSpeed = charC.moveSpeed;
                this.charJumpForce = charC.jumpForce;
                this.charStepOffset = charC.stepOffset;
                this.charSlopeLimit = charC.slopeLimit;
                this.charGravityMultiplier = charC.gravityMultiplier;
                this.charIsGrounded = charC.isGrounded;
            }

            VehicleControllerComponent veh = entity.getComponent(VehicleControllerComponent.class);
            this.hasVehicleController = (veh != null);
            if (hasVehicleController) {
                this.vehEngineTorque = veh.engineTorque;
                this.vehMaxSpeed = veh.maxSpeed;
                this.vehSteeringAngle = veh.steeringAngle;
                this.vehBrakeForce = veh.brakeForce;
                this.vehSuspensionStiffness = veh.suspensionStiffness;
                this.vehSuspensionDamping = veh.suspensionDamping;
                this.vehSuspensionRestLength = veh.suspensionRestLength;
                this.vehWheelRadius = veh.wheelRadius;
                this.vehWheelTrackWidth = veh.wheelTrackWidth;
                this.vehWheelBase = veh.wheelBase;
            }

            AudioSource audio = entity.getComponent(AudioSource.class);
            this.hasAudioSource = (audio != null);
            if (hasAudioSource) {
                this.audioVolume = audio.volume;
                this.audioPitch = audio.pitch;
                this.audioLoop = audio.loop;
                this.audioMinDist = audio.minDistance;
                this.audioMaxDist = audio.maxDistance;
                this.audioAttenuation = audio.attenuation;
                this.audioState = audio.state;
            }

            AnimatorComponent anim = entity.getComponent(AnimatorComponent.class);
            this.hasAnimator = (anim != null);
            if (hasAnimator) {
                this.animActiveClipName = anim.activeClipName;
                this.animPlaybackTime = anim.playbackTime;
                this.animPlaybackSpeed = anim.playbackSpeed;
                this.animIsPlaying = anim.isPlaying;
            }

            Light light = entity.getComponent(Light.class);
            this.hasLight = (light != null);
            if (hasLight) {
                this.lightType = light.type;
                this.lightIntensity = light.intensity;
                this.lightR = light.r; this.lightG = light.g; this.lightB = light.b;
            }

            Camera cam = entity.getComponent(Camera.class);
            this.hasCamera = (cam != null);
            if (hasCamera) {
                this.cameraFov = cam.fov;
                this.cameraNearPlane = cam.nearPlane;
                this.cameraFarPlane = cam.farPlane;
                this.cameraIsPrimary = cam.isPrimary;
            }

            Material mat = entity.getComponent(Material.class);
            this.hasMaterial = (mat != null);
            if (hasMaterial) {
                this.matTextureAssetPath = mat.textureAssetPath;
                this.matTextureName = mat.textureName;
                this.matShaderName = mat.shaderName;
                this.matReflect = mat.reflect;
            }

            MeshFilter mf = entity.getComponent(MeshFilter.class);
            this.hasMeshFilter = (mf != null);
            if (hasMeshFilter) {
                this.meshAssetPath = mf.meshAssetPath;
                this.meshIsVisible = mf.isVisible;
            }

            BoxCollider box = entity.getComponent(BoxCollider.class);
            this.hasBoxCollider = (box != null);
            if (hasBoxCollider) {
                this.boxSizeX = box.sizeX; this.boxSizeY = box.sizeY; this.boxSizeZ = box.sizeZ;
                this.boxOffsetX = box.offsetX; this.boxOffsetY = box.offsetY; this.boxOffsetZ = box.offsetZ;
                this.boxIsTrigger = box.isTrigger;
            }

            SphereCollider sphere = entity.getComponent(SphereCollider.class);
            this.hasSphereCollider = (sphere != null);
            if (hasSphereCollider) {
                this.sphereRadius = sphere.radius;
                this.sphereOffsetX = sphere.offsetX; this.sphereOffsetY = sphere.offsetY; this.sphereOffsetZ = sphere.offsetZ;
                this.sphereIsTrigger = sphere.isTrigger;
            }

            CapsuleCollider capsule = entity.getComponent(CapsuleCollider.class);
            this.hasCapsuleCollider = (capsule != null);
            if (hasCapsuleCollider) {
                this.capsuleRadius = capsule.radius; this.capsuleHeight = capsule.height;
                this.capsuleOffsetX = capsule.offsetX; this.capsuleOffsetY = capsule.offsetY; this.capsuleOffsetZ = capsule.offsetZ;
                this.capsuleIsTrigger = capsule.isTrigger;
            }
        }

        public void restore() {
            if (targetEntity == null) return;

            targetEntity.name = this.name;
            targetEntity.parentId = this.parentId;
            targetEntity.selected = this.selected;
            targetEntity.expandedInHierarchy = this.expandedInHierarchy;

            if (hasTransform) {
                Transform tc = targetEntity.getTransform();
                if (tc != null) {
                    tc.posX = this.posX; tc.posY = this.posY; tc.posZ = this.posZ;
                    tc.rotX = this.rotX; tc.rotY = this.rotY; tc.rotZ = this.rotZ;
                    tc.scaleX = this.scaleX; tc.scaleY = this.scaleY; tc.scaleZ = this.scaleZ;
                }
            }

            if (hasRigidBody) {
                RigidBodyComponent rb = targetEntity.getComponent(RigidBodyComponent.class);
                if (rb != null) {
                    rb.motionType = this.rbMotionType;
                    rb.mass = this.rbMass;
                    rb.useGravity = this.rbUseGravity;
                    rb.linearDrag = this.rbLinearDrag;
                    rb.angularDrag = this.rbAngularDrag;
                    rb.friction = this.rbFriction;
                    rb.restitution = this.rbRestitution;
                }
            }

            if (hasCharacterController) {
                CharacterControllerComponent charC = targetEntity.getComponent(CharacterControllerComponent.class);
                if (charC != null) {
                    charC.moveSpeed = this.charMoveSpeed;
                    charC.jumpForce = this.charJumpForce;
                    charC.stepOffset = this.charStepOffset;
                    charC.slopeLimit = this.charSlopeLimit;
                    charC.gravityMultiplier = this.charGravityMultiplier;
                    charC.isGrounded = this.charIsGrounded;
                }
            }

            if (hasVehicleController) {
                VehicleControllerComponent veh = targetEntity.getComponent(VehicleControllerComponent.class);
                if (veh != null) {
                    veh.engineTorque = this.vehEngineTorque;
                    veh.maxSpeed = this.vehMaxSpeed;
                    veh.steeringAngle = this.vehSteeringAngle;
                    veh.brakeForce = this.vehBrakeForce;
                    veh.suspensionStiffness = this.vehSuspensionStiffness;
                    veh.suspensionDamping = this.vehSuspensionDamping;
                    veh.suspensionRestLength = this.vehSuspensionRestLength;
                    veh.wheelRadius = this.vehWheelRadius;
                    veh.wheelTrackWidth = this.vehWheelTrackWidth;
                    veh.wheelBase = this.vehWheelBase;
                }
            }

            if (hasAudioSource) {
                AudioSource audio = targetEntity.getComponent(AudioSource.class);
                if (audio != null) {
                    audio.setVolume(this.audioVolume);
                    audio.setPitch(this.audioPitch);
                    audio.loop = this.audioLoop;
                    audio.setDistances(this.audioMinDist, this.audioMaxDist);
                    audio.attenuation = this.audioAttenuation;
                    audio.state = this.audioState;
                    if (this.audioState != AudioSource.PlaybackState.PLAYING) {
                        audio.stop();
                    }
                }
            }

            if (hasAnimator) {
                AnimatorComponent anim = targetEntity.getComponent(AnimatorComponent.class);
                if (anim != null) {
                    anim.activeClipName = this.animActiveClipName;
                    anim.playbackTime = this.animPlaybackTime;
                    anim.playbackSpeed = this.animPlaybackSpeed;
                    anim.isPlaying = this.animIsPlaying;
                }
            }

            if (hasLight) {
                Light light = targetEntity.getComponent(Light.class);
                if (light != null) {
                    light.type = this.lightType;
                    light.intensity = this.lightIntensity;
                    light.r = this.lightR; light.g = this.lightG; light.b = this.lightB;
                }
            }

            if (hasCamera) {
                Camera cam = targetEntity.getComponent(Camera.class);
                if (cam != null) {
                    cam.fov = this.cameraFov;
                    cam.nearPlane = this.cameraNearPlane;
                    cam.farPlane = this.cameraFarPlane;
                    cam.isPrimary = this.cameraIsPrimary;
                }
            }

            if (hasMaterial) {
                Material mat = targetEntity.getComponent(Material.class);
                if (mat != null) {
                    mat.textureAssetPath = this.matTextureAssetPath;
                    mat.textureName = this.matTextureName;
                    mat.shaderName = this.matShaderName;
                    mat.reflect = this.matReflect;
                }
            }

            if (hasMeshFilter) {
                MeshFilter mf = targetEntity.getComponent(MeshFilter.class);
                if (mf != null) {
                    mf.meshAssetPath = this.meshAssetPath;
                    mf.isVisible = this.meshIsVisible;
                }
            }

            if (hasBoxCollider) {
                BoxCollider box = targetEntity.getComponent(BoxCollider.class);
                if (box != null) {
                    box.sizeX = this.boxSizeX; box.sizeY = this.boxSizeY; box.sizeZ = this.boxSizeZ;
                    box.offsetX = this.boxOffsetX; box.offsetY = this.boxOffsetY; box.offsetZ = this.boxOffsetZ;
                    box.isTrigger = this.boxIsTrigger;
                }
            }

            if (hasSphereCollider) {
                SphereCollider sphere = targetEntity.getComponent(SphereCollider.class);
                if (sphere != null) {
                    sphere.radius = this.sphereRadius;
                    sphere.offsetX = this.sphereOffsetX; sphere.offsetY = this.sphereOffsetY; sphere.offsetZ = this.sphereOffsetZ;
                    sphere.isTrigger = this.sphereIsTrigger;
                }
            }

            if (hasCapsuleCollider) {
                CapsuleCollider capsule = targetEntity.getComponent(CapsuleCollider.class);
                if (capsule != null) {
                    capsule.radius = this.capsuleRadius; capsule.height = this.capsuleHeight;
                    capsule.offsetX = this.capsuleOffsetX; capsule.offsetY = this.capsuleOffsetY; capsule.offsetZ = this.capsuleOffsetZ;
                    capsule.isTrigger = this.capsuleIsTrigger;
                }
            }
        }
    }

    private final List<EntityStateBackup> backupStatePool = new ArrayList<>();
    private final List<Entity> initialEntitiesList = new ArrayList<>();
    private int activeBackupCount = 0;

    public void captureSnapshot(List<Entity> activeEntities) {
        if (activeEntities == null) return;
        initialEntitiesList.clear();
        initialEntitiesList.addAll(activeEntities);

        activeBackupCount = 0;
        for (int i = 0; i < activeEntities.size(); i++) {
            Entity entity = activeEntities.get(i);
            EntityStateBackup backup;
            if (activeBackupCount < backupStatePool.size()) {
                backup = backupStatePool.get(activeBackupCount);
            } else {
                backup = new EntityStateBackup();
                backupStatePool.add(backup);
            }
            backup.capture(entity);
            activeBackupCount++;
        }
    }

    public void restoreSnapshot(List<Entity> activeEntities) {
        if (activeEntities == null) return;

        // Remove any entities spawned dynamically during runtime simulation
        activeEntities.removeIf(e -> !initialEntitiesList.contains(e));

        // Re-add any entities deleted during simulation
        for (int i = 0; i < initialEntitiesList.size(); i++) {
            Entity orig = initialEntitiesList.get(i);
            if (!activeEntities.contains(orig)) {
                activeEntities.add(orig);
            }
        }

        // Restore saved transform & component parameters in-place
        for (int i = 0; i < activeBackupCount; i++) {
            backupStatePool.get(i).restore();
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

            RigidBodyComponent origRb = orig.getComponent(RigidBodyComponent.class);
            if (origRb != null) {
                RigidBodyComponent cloneRb = new RigidBodyComponent();
                cloneRb.motionType = origRb.motionType;
                cloneRb.mass = origRb.mass;
                cloneRb.useGravity = origRb.useGravity;
                cloneRb.linearDrag = origRb.linearDrag;
                cloneRb.angularDrag = origRb.angularDrag;
                cloneRb.friction = origRb.friction;
                cloneRb.restitution = origRb.restitution;
                cloned.addComponent(cloneRb);
            }

            BoxCollider origBox = orig.getComponent(BoxCollider.class);
            if (origBox != null) {
                BoxCollider cloneBox = new BoxCollider();
                cloneBox.sizeX = origBox.sizeX; cloneBox.sizeY = origBox.sizeY; cloneBox.sizeZ = origBox.sizeZ;
                cloneBox.offsetX = origBox.offsetX; cloneBox.offsetY = origBox.offsetY; cloneBox.offsetZ = origBox.offsetZ;
                cloneBox.isTrigger = origBox.isTrigger;
                cloned.addComponent(cloneBox);
            }

            SphereCollider origSphere = orig.getComponent(SphereCollider.class);
            if (origSphere != null) {
                SphereCollider cloneSphere = new SphereCollider();
                cloneSphere.radius = origSphere.radius;
                cloneSphere.offsetX = origSphere.offsetX; cloneSphere.offsetY = origSphere.offsetY; cloneSphere.offsetZ = origSphere.offsetZ;
                cloneSphere.isTrigger = origSphere.isTrigger;
                cloned.addComponent(cloneSphere);
            }

            CapsuleCollider origCapsule = orig.getComponent(CapsuleCollider.class);
            if (origCapsule != null) {
                CapsuleCollider cloneCapsule = new CapsuleCollider();
                cloneCapsule.radius = origCapsule.radius; cloneCapsule.height = origCapsule.height;
                cloneCapsule.offsetX = origCapsule.offsetX; cloneCapsule.offsetY = origCapsule.offsetY; cloneCapsule.offsetZ = origCapsule.offsetZ;
                cloneCapsule.isTrigger = origCapsule.isTrigger;
                cloned.addComponent(cloneCapsule);
            }

            CharacterControllerComponent origChar = orig.getComponent(CharacterControllerComponent.class);
            if (origChar != null) {
                CharacterControllerComponent cloneChar = new CharacterControllerComponent();
                cloneChar.moveSpeed = origChar.moveSpeed;
                cloneChar.jumpForce = origChar.jumpForce;
                cloneChar.stepOffset = origChar.stepOffset;
                cloneChar.slopeLimit = origChar.slopeLimit;
                cloneChar.gravityMultiplier = origChar.gravityMultiplier;
                cloneChar.isGrounded = origChar.isGrounded;
                cloned.addComponent(cloneChar);
            }

            VehicleControllerComponent origVeh = orig.getComponent(VehicleControllerComponent.class);
            if (origVeh != null) {
                VehicleControllerComponent cloneVeh = new VehicleControllerComponent();
                cloneVeh.engineTorque = origVeh.engineTorque;
                cloneVeh.maxSpeed = origVeh.maxSpeed;
                cloneVeh.steeringAngle = origVeh.steeringAngle;
                cloneVeh.brakeForce = origVeh.brakeForce;
                cloneVeh.suspensionStiffness = origVeh.suspensionStiffness;
                cloneVeh.suspensionDamping = origVeh.suspensionDamping;
                cloneVeh.suspensionRestLength = origVeh.suspensionRestLength;
                cloneVeh.wheelRadius = origVeh.wheelRadius;
                cloneVeh.wheelTrackWidth = origVeh.wheelTrackWidth;
                cloneVeh.wheelBase = origVeh.wheelBase;
                cloned.addComponent(cloneVeh);
            }

            copy.add(cloned);
        }
        return copy;
    }
}
