package com.retro.engine.ecs;

import com.retro.engine.animation.Skeleton;
import com.retro.engine.ecs.SceneWorld.WorldTransform;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.BoneSocket;
import com.retro.engine.ecs.components.Transform;

import java.util.List;

/**
 * Places every active {@link BoneSocket} on its bone: world = target world * bone model * offset. It has to run after
 * animation, because scripts run before it and would only ever see the previous frame's pose. Allocation-free.
 *
 * <p>The target is looked up every frame rather than cached, so a destroyed or renamed target can't leave a socket
 * holding a dead entity. That costs one list scan per active socket, which is nothing at the handful a scene has.</p>
 */
public final class BoneSocketSystem {

    private final float[] boneModel = new float[16];
    private final float[] offset = new float[3];
    private final float[] euler = new float[3];
    private final double[] target = new double[9], bone = new double[9], local = new double[9];
    private final double[] partial = new double[9], world = new double[9];

    public void update(List<Entity> entities, HierarchySystem hierarchy) {
        for (int i = 0; i < entities.size(); i++) {
            Entity entity = entities.get(i);
            if (entity == null) continue;
            BoneSocket socket = entity.getComponent(BoneSocket.class);
            if (socket != null && socket.active) place(entity, socket, entities, hierarchy);
        }
    }

    private void place(Entity entity, BoneSocket socket, List<Entity> entities, HierarchySystem hierarchy) {
        Entity rig = findTarget(entity, socket, entities);
        AnimatorComponent animator = rig != null ? rig.getComponent(AnimatorComponent.class) : null;
        if (animator == null || animator.skeleton == null) {
            warnOnce(entity, socket, "no rigged target '" + socket.targetEntity + "'");
            return;
        }
        if (!resolveBone(socket, animator.skeleton)) {
            warnOnce(entity, socket, "no bone '" + socket.boneName + "' on '" + rig.name + "'");
            return;
        }
        if (!animator.skeleton.computeBoneModelTransform(socket.boneIndex, animator.bonePaletteMatrices, boneModel)) return;

        WorldTransform rigWorld = hierarchy.getComputedWorldTransform(rig, entities);
        float rigX = rigWorld.px(), rigY = rigWorld.py(), rigZ = rigWorld.pz();
        float rigRx = rigWorld.rx(), rigRy = rigWorld.ry(), rigRz = rigWorld.rz();
        float rigSx = rigWorld.sx(), rigSy = rigWorld.sy(), rigSz = rigWorld.sz();

        float[] m = boneModel;
        float ox = socket.offsetPosX, oy = socket.offsetPosY, oz = socket.offsetPosZ;
        float x = m[0] * ox + m[4] * oy + m[8] * oz + m[12];
        float y = m[1] * ox + m[5] * oy + m[9] * oz + m[13];
        float z = m[2] * ox + m[6] * oy + m[10] * oz + m[14];
        hierarchy.rotateVector(x * rigSx, y * rigSy, z * rigSz, rigRx, rigRy, rigRz, offset);

        HierarchySystem.rotationMatrix(rigRx, rigRy, rigRz, target);
        boneRotation(m, bone);
        HierarchySystem.rotationMatrix(socket.offsetRotX, socket.offsetRotY, socket.offsetRotZ, local);
        multiply(target, bone, partial);
        multiply(partial, local, world);
        HierarchySystem.matrixToEuler(world, euler);

        hierarchy.setWorldPose(entity, entities, rigX + offset[0], rigY + offset[1], rigZ + offset[2], euler[0], euler[1], euler[2]);
        Transform transform = entity.getTransform();
        if (transform != null) transform.markDirty();
    }

    private static Entity findTarget(Entity entity, BoneSocket socket, List<Entity> entities) {
        boolean byName = socket.targetEntity != null && !socket.targetEntity.isEmpty();
        if (!byName && (entity.parentId == null || entity.parentId.isEmpty())) return null;
        for (int i = 0; i < entities.size(); i++) {
            Entity candidate = entities.get(i);
            if (candidate == null || candidate == entity) continue;
            if (byName ? socket.targetEntity.equals(candidate.name) : entity.parentId.equals(candidate.id)) return candidate;
        }
        return null;
    }

    private static boolean resolveBone(BoneSocket socket, Skeleton skeleton) {
        if (socket.resolvedSkeleton != skeleton || !socket.boneName.equals(socket.resolvedBone)) {
            socket.resolvedSkeleton = skeleton;
            socket.resolvedBone = socket.boneName;
            socket.boneIndex = skeleton.getBoneIndex(socket.boneName);
        }
        return socket.boneIndex >= 0 && socket.boneIndex < AnimatorComponent.MAX_BONES;
    }

    private static void warnOnce(Entity entity, BoneSocket socket, String problem) {
        if (socket.warned) return;
        socket.warned = true;
        System.err.println("[BoneSocket] '" + entity.name + "': " + problem);
    }

    /** Row-major rotation of a column-major matrix, with any bone scale divided out. */
    private static void boneRotation(float[] m, double[] out) {
        for (int column = 0; column < 3; column++) {
            double x = m[column * 4], y = m[column * 4 + 1], z = m[column * 4 + 2];
            double length = Math.sqrt(x * x + y * y + z * z);
            double scale = length > 1e-9 ? 1.0 / length : 0.0;
            out[column] = x * scale;
            out[3 + column] = y * scale;
            out[6 + column] = z * scale;
        }
    }

    private static void multiply(double[] a, double[] b, double[] out) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                out[row * 3 + column] = a[row * 3] * b[column] + a[row * 3 + 1] * b[3 + column] + a[row * 3 + 2] * b[6 + column];
            }
        }
    }
}
