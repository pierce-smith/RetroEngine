package com.retro.engine.scripting.framework;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.AudioSource;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.ecs.components.Camera;
import com.retro.engine.ecs.components.CapsuleCollider;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.Light;
import com.retro.engine.ecs.components.Material;
import com.retro.engine.ecs.components.MeshFilter;
import com.retro.engine.ecs.components.RigidBodyComponent;
import com.retro.engine.ecs.components.SphereCollider;
import com.retro.engine.ecs.components.Transform;
import com.retro.engine.ecs.components.VehicleControllerComponent;

/**
 * Lazily resolved, cached references to one entity's core components, with concrete return types (so scripts
 * need no casts). Each getter looks the component up on first use and remembers it; a missing component is looked up
 * again on the next call, so components added later are found.
 *
 * <p>The cache drops itself automatically when the entity's component count changes (a component was added or
 * removed). Call {@link #invalidate()} after swapping one component for another of the same count.</p>
 */
public final class ComponentCache {

    private Entity entity;
    private int componentCount = -1;

    private Transform transform;
    private RigidBodyComponent rigidBody;
    private BoxCollider boxCollider;
    private SphereCollider sphereCollider;
    private CapsuleCollider capsuleCollider;
    private AnimatorComponent animator;
    private Light light;
    private Camera camera;
    private AudioSource audio;
    private Material material;
    private MeshFilter meshFilter;
    private CharacterControllerComponent characterController;
    private VehicleControllerComponent vehicleController;
    private com.retro.engine.ecs.components.ParticleSystem particleSystem;
    private com.retro.engine.ecs.components.NavMeshAgent navMeshAgent;

    public void bind(Entity target) {
        this.entity = target;
        invalidate();
    }

    public Entity getEntity() {
        return entity;
    }

    public void invalidate() {
        componentCount = -1;
        transform = null;
        rigidBody = null;
        boxCollider = null;
        sphereCollider = null;
        capsuleCollider = null;
        animator = null;
        light = null;
        camera = null;
        audio = null;
        material = null;
        meshFilter = null;
        characterController = null;
        vehicleController = null;
        particleSystem = null;
        navMeshAgent = null;
    }

    /** False when unbound; otherwise drops stale references if the entity's components changed. */
    private boolean validate() {
        if (entity == null) return false;
        int n = entity.getAllComponents().size();
        if (n != componentCount) {
            Entity e = entity;
            invalidate();
            entity = e;
            componentCount = n;
        }
        return true;
    }

    public Transform transform() {
        if (!validate()) return null;
        if (transform == null) transform = entity.getTransform();
        return transform;
    }

    public RigidBodyComponent rigidbody() {
        if (!validate()) return null;
        if (rigidBody == null) rigidBody = entity.getComponent(RigidBodyComponent.class);
        return rigidBody;
    }

    public BoxCollider boxCollider() {
        if (!validate()) return null;
        if (boxCollider == null) boxCollider = entity.getComponent(BoxCollider.class);
        return boxCollider;
    }

    public SphereCollider sphereCollider() {
        if (!validate()) return null;
        if (sphereCollider == null) sphereCollider = entity.getComponent(SphereCollider.class);
        return sphereCollider;
    }

    public CapsuleCollider capsuleCollider() {
        if (!validate()) return null;
        if (capsuleCollider == null) capsuleCollider = entity.getComponent(CapsuleCollider.class);
        return capsuleCollider;
    }

    /** The entity's first collider (box, then sphere, then capsule), or null. */
    public Component collider() {
        BoxCollider box = boxCollider();
        if (box != null) return box;
        SphereCollider sphere = sphereCollider();
        if (sphere != null) return sphere;
        return capsuleCollider();
    }

    public AnimatorComponent animator() {
        if (!validate()) return null;
        if (animator == null) animator = entity.getComponent(AnimatorComponent.class);
        return animator;
    }

    public Light light() {
        if (!validate()) return null;
        if (light == null) light = entity.getComponent(Light.class);
        return light;
    }

    public Camera camera() {
        if (!validate()) return null;
        if (camera == null) camera = entity.getComponent(Camera.class);
        return camera;
    }

    public AudioSource audio() {
        if (!validate()) return null;
        if (audio == null) audio = entity.getComponent(AudioSource.class);
        return audio;
    }

    public Material material() {
        if (!validate()) return null;
        if (material == null) material = entity.getComponent(Material.class);
        return material;
    }

    public MeshFilter meshFilter() {
        if (!validate()) return null;
        if (meshFilter == null) meshFilter = entity.getComponent(MeshFilter.class);
        return meshFilter;
    }

    public CharacterControllerComponent characterController() {
        if (!validate()) return null;
        if (characterController == null) characterController = entity.getComponent(CharacterControllerComponent.class);
        return characterController;
    }

    public com.retro.engine.ecs.components.ParticleSystem particleSystem() {
        if (!validate()) return null;
        if (particleSystem == null) particleSystem = entity.getComponent(com.retro.engine.ecs.components.ParticleSystem.class);
        return particleSystem;
    }

    public com.retro.engine.ecs.components.NavMeshAgent navMeshAgent() {
        if (!validate()) return null;
        if (navMeshAgent == null) navMeshAgent = entity.getComponent(com.retro.engine.ecs.components.NavMeshAgent.class);
        return navMeshAgent;
    }

    public VehicleControllerComponent vehicleController() {
        if (!validate()) return null;
        if (vehicleController == null) vehicleController = entity.getComponent(VehicleControllerComponent.class);
        return vehicleController;
    }
}
