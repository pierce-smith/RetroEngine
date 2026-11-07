package com.retro.engine.ecs;

import com.retro.engine.animation.PoseEditor;
import com.retro.engine.animation.Skeleton;
import com.retro.engine.ecs.SceneWorld.WorldTransform;
import com.retro.engine.ecs.components.AimIK;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.BoneAimConstraint;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.FootIK;
import com.retro.engine.ecs.components.LookAtIK;
import com.retro.engine.physics.PhysicsSystem;
import com.retro.engine.physics.RaycastHit;

import java.util.List;

/**
 * Applies {@link FootIK}, {@link AimIK} and {@link LookAtIK} to each animator's finished pose, in that order: the feet
 * settle the pelvis the upper body stands on, and the head has the last word on where it looks. Runs after animation and
 * before bone sockets, so held items land on the adjusted pose.
 *
 * <p>Only a pose animation produced this frame is touched. The palette is edited in place, so solving a held pose again
 * would stack, and that rules out a paused game. A stopped animator or a bind pose means something else owns the skeleton,
 * so it's left exactly as it is and the constraints fade back in when animation resumes.</p>
 */
public final class IKSystem {

    private static final float MIN_GROUND_NORMAL = 0.5f;   // steeper than 60 degrees is a wall
    private static final float START_INSIDE_MARGIN = 0.01f;
    private static final float SAME_SURFACE_TOLERANCE = 0.02f;
    private static final float PLANT_RANGE = 0.12f;        // ankle height over its rest that counts as off the ground
    private static final float MIN_EDIT = 1e-4f;
    private static final float GIVE_UP_MARGIN = 40.0f;     // degrees past maxAngle before a constraint lets go
    // A controller steps a stair in one frame, and walking a slope never moves it this far in one
    private static final float ROOT_JUMP = 0.08f;
    private static final int MAX_PARENT_DEPTH = 32;

    private final PoseEditor editor = new PoseEditor();
    private final RaycastHit ankleHit = new RaycastHit(), toeHit = new RaycastHit();

    private final double[] rig = new double[9], up = new double[3], forward = new double[3], bendHint = new double[3];
    private final double[] ankle = new double[3], target = new double[3], worldPoint = new double[3], normal = new double[3];
    private final double[] align = new double[9], before = new double[9], after = new double[9], footTurn = new double[9];
    private final double[] partial = new double[9], turn = new double[9];
    private final double[][] restAnkle = new double[2][3], restFoot = new double[2][9];
    private final double[] aimOrigin = new double[3], aimDirection = new double[3], wanted = new double[3], goal = new double[3];
    private final double[] current = new double[3], pivot = new double[3];
    private final float[] targetOffset = new float[2], ankleHeight = new float[2];
    private final double[] targetNormal = new double[6];

    public void update(List<Entity> entities, HierarchySystem hierarchy, PhysicsSystem physics, float deltaTime) {
        for (int i = 0; i < entities.size(); i++) {
            Entity entity = entities.get(i);
            if (entity == null) continue;
            AnimatorComponent animator = entity.getComponent(AnimatorComponent.class);
            if (animator == null) continue;
            FootIK feet = entity.getComponent(FootIK.class);
            AimIK aim = entity.getComponent(AimIK.class);
            LookAtIK look = entity.getComponent(LookAtIK.class);
            if (feet == null && aim == null && look == null) continue;

            if (!animator.isPlaying || animator.bindPoseApplied || animator.skeleton == null) {
                if (feet != null) feet.resetSolve();
                if (aim != null) aim.resetSolve();
                if (look != null) look.resetSolve();
                continue;
            }
            if (animator.poseRevision == animator.solvedPoseRevision) continue;
            animator.solvedPoseRevision = animator.poseRevision;

            WorldTransform world = hierarchy.getComputedWorldTransform(entity, entities);
            HierarchySystem.rotationMatrix(world.rx(), world.ry(), world.rz(), rig);
            if (feet != null && physics != null) solveFeet(entity, animator, feet, world, entities, physics, deltaTime);
            if (aim != null) solveAim(entity, animator, aim, world, entities, hierarchy, deltaTime);
            if (look != null) solveAim(entity, animator, look, world, entities, hierarchy, deltaTime);
        }
    }

    // --- Feet ---

    private void solveFeet(Entity entity, AnimatorComponent animator, FootIK ik, WorldTransform world, List<Entity> entities,
                           PhysicsSystem physics, float deltaTime) {
        Skeleton skeleton = animator.skeleton;
        if (!resolveFeet(entity, ik, skeleton)) return;
        float[] joints = skeleton.getBindJointPositions();
        Entity controllerOwner = findController(entity, entities);
        Entity exclude = controllerOwner != null ? controllerOwner : entity;
        float rootY = world.py();

        up[0] = 0.0;
        up[1] = 1.0;
        up[2] = 0.0;
        PoseEditor.applyInverse(rig, up, up);
        double yaw = Math.toRadians(ik.forwardYaw);
        bendHint[0] = Math.sin(yaw);
        bendHint[1] = 0.0;
        bendHint[2] = Math.cos(yaw);
        PoseEditor.apply(rig, bendHint, forward);

        for (int foot = 0; foot < 2; foot++) {
            int bone = ik.legBones[foot * 3];
            PoseEditor.jointPosition(animator, joints, bone, restAnkle[foot]);
            PoseEditor.boneRotation(animator, bone, restFoot[foot]);
            toWorld(restAnkle[foot], world, worldPoint);
            float height = (float) worldPoint[1] - rootY;
            ankleHeight[foot] = height;
            float rest = Math.abs(joints[bone * 3 + 1] * world.sy());
            probeGround(ik, foot, (float) worldPoint[0], (float) worldPoint[2], rootY, Math.min(height, rest), exclude, physics);
        }

        // A stair lifts the root in one frame: keep the feet and pelvis where they were in the world and ease from there
        float rootJump = ik.warm ? rootY - ik.lastRootY : 0.0f;
        if (Math.abs(rootJump) > ROOT_JUMP && Math.abs(rootJump) <= ik.maxStep) {
            ik.footOffset[0] -= rootJump;
            ik.footOffset[1] -= rootJump;
            ik.pelvisOffset -= rootJump;
        }
        ik.lastRootY = rootY;

        float pelvisTarget = Math.max(-ik.maxStep, Math.min(ik.maxStep, Math.min(targetOffset[0], targetOffset[1])));
        boolean airborne = controllerOwner != null && !controllerOwner.getComponent(CharacterControllerComponent.class).isGrounded;
        float blendTarget = ik.active && !airborne ? 1.0f : 0.0f;
        float ease = 1.0f - (float) Math.exp(-ik.smoothing * deltaTime);
        if (!ik.warm) {
            ik.footOffset[0] = targetOffset[0];
            ik.footOffset[1] = targetOffset[1];
            ik.pelvisOffset = pelvisTarget;
            System.arraycopy(targetNormal, 0, ik.footNormal, 0, 6);
            ik.warm = true;
        } else {
            for (int foot = 0; foot < 2; foot++) ik.footOffset[foot] += (targetOffset[foot] - ik.footOffset[foot]) * ease;
            ik.pelvisOffset += (pelvisTarget - ik.pelvisOffset) * ease;
            for (int i = 0; i < 6; i++) ik.footNormal[i] += (targetNormal[i] - ik.footNormal[i]) * ease;
        }
        ik.blend += (blendTarget - ik.blend) * ease;

        float strength = Math.max(0.0f, Math.min(1.0f, ik.weight)) * ik.blend;
        if (strength < MIN_EDIT) return;

        float drop = ik.pelvisOffset * strength;
        if (Math.abs(drop) > MIN_EDIT) {
            modelOffset(drop, world, target);
            editor.translate(animator, ik.pelvisBone, target[0], target[1], target[2]);
        }

        for (int foot = 0; foot < 2; foot++) {
            int bone = ik.legBones[foot * 3], knee = ik.legBones[foot * 3 + 1], hip = ik.legBones[foot * 3 + 2];
            float lift = ik.footOffset[foot] * strength;
            float rest = Math.abs(joints[bone * 3 + 1] * world.sy());
            float plant = Math.max(0.0f, Math.min(1.0f, 1.0f - (ankleHeight[foot] - rest) / PLANT_RANGE));

            normal[0] = ik.footNormal[foot * 3];
            normal[1] = ik.footNormal[foot * 3 + 1];
            normal[2] = ik.footNormal[foot * 3 + 2];
            PoseEditor.normalize(normal);
            PoseEditor.applyInverse(rig, normal, normal);
            double slope = PoseEditor.angleBetween(up, normal);
            double limit = Math.toRadians(ik.maxFootAngle);
            double amount = (slope > limit ? limit / slope : 1.0) * plant * strength;

            if (Math.abs(lift) <= MIN_EDIT && Math.abs(drop) <= MIN_EDIT && slope * amount <= MIN_EDIT) continue;

            modelOffset(lift, world, target);
            target[0] += restAnkle[foot][0];
            target[1] += restAnkle[foot][1];
            target[2] += restAnkle[foot][2];
            editor.solveTwoBone(animator, hip, knee, bone, target, bendHint);

            // The leg carried the foot round with it: put it back to the clip's rotation, tilted onto the ground
            PoseEditor.rotationBetween(up, normal, amount, align);
            multiply(align, restFoot[foot], before);
            PoseEditor.boneRotation(animator, bone, after);
            multiplyTransposed(before, after, footTurn);
            PoseEditor.jointPosition(animator, joints, bone, ankle);
            editor.rotate(animator, bone, ankle, footTurn);
        }
    }

    /** Fills in where {@code foot}'s sole should stand, relative to the origin. No ground means the clip's own height. */
    private void probeGround(FootIK ik, int foot, float x, float z, float rootY, float soleHeight, Entity exclude, PhysicsSystem physics) {
        float top = rootY + ik.maxStep, reach = ik.maxStep * 2.0f;
        boolean underAnkle = castDown(x, top, z, reach, exclude, physics, ankleHit);
        float dx = (float) forward[0] * ik.footLength, dz = (float) forward[2] * ik.footLength;
        boolean underToe = ik.footLength > 0.0f && castDown(x + dx, top, z + dz, reach, exclude, physics, toeHit);

        RaycastHit ground = underAnkle ? ankleHit : (underToe ? toeHit : null);
        if (underAnkle && underToe) {
            // A slope carries on to the toe by itself. Only a toe above that plane has met the next stair
            float plane = ankleHit.pointY - (ankleHit.normalX * dx + ankleHit.normalZ * dz) / ankleHit.normalY;
            if (toeHit.pointY > plane + SAME_SURFACE_TOLERANCE) ground = toeHit;
        }

        int at = foot * 3;
        if (ground == null) {
            targetOffset[foot] = 0.0f;
            targetNormal[at] = 0.0;
            targetNormal[at + 1] = 1.0;
            targetNormal[at + 2] = 0.0;
            return;
        }
        // A tilted foot pivots on the ankle, so on a slope the ankle has to sit further above the point beneath it
        float tilt = Math.max(0.0f, soleHeight) * (1.0f / ground.normalY - 1.0f);
        targetOffset[foot] = ground.pointY - rootY + tilt;
        targetNormal[at] = ground.normalX;
        targetNormal[at + 1] = ground.normalY;
        targetNormal[at + 2] = ground.normalZ;
    }

    private static boolean castDown(float x, float top, float z, float reach, Entity exclude, PhysicsSystem physics, RaycastHit hit) {
        physics.raycastExcludingInPlace(exclude, x, top, z, 0.0f, -1.0f, 0.0f, reach, hit);
        if (!hit.hit || hit.normalY < MIN_GROUND_NORMAL || hit.pointY > top - START_INSIDE_MARGIN) return false;
        return hit.entity == null || hit.entity.getComponent(CharacterControllerComponent.class) == null;
    }

    private static boolean resolveFeet(Entity entity, FootIK ik, Skeleton skeleton) {
        if (ik.resolvedSkeleton != skeleton || !ik.leftFoot.equals(ik.resolvedLeft) || !ik.rightFoot.equals(ik.resolvedRight)
                || !ik.pelvis.equals(ik.resolvedPelvis)) {
            ik.resolvedSkeleton = skeleton;
            ik.resolvedLeft = ik.leftFoot;
            ik.resolvedRight = ik.rightFoot;
            ik.resolvedPelvis = ik.pelvis;
            ik.pelvisBone = skeleton.getBoneIndex(ik.pelvis);
            ik.resolved = resolveLeg(skeleton, ik.leftFoot, ik.legBones, 0) && resolveLeg(skeleton, ik.rightFoot, ik.legBones, 3)
                    && posable(ik.pelvisBone);
            if (!ik.resolved && !ik.warned) {
                ik.warned = true;
                System.err.println("[FootIK] '" + entity.name + "': needs a pelvis '" + ik.pelvis + "' and feet '" + ik.leftFoot + "', '"
                        + ik.rightFoot + "' each two bones below a parent");
            }
        }
        return ik.resolved;
    }

    private static boolean resolveLeg(Skeleton skeleton, String footName, int[] out, int at) {
        int foot = skeleton.getBoneIndex(footName);
        int knee = foot >= 0 ? skeleton.getEvaluationParent(foot) : -1;
        int hip = knee >= 0 ? skeleton.getEvaluationParent(knee) : -1;
        out[at] = foot;
        out[at + 1] = knee;
        out[at + 2] = hip;
        return posable(foot) && posable(knee) && posable(hip);
    }

    private static boolean posable(int bone) {
        return bone >= 0 && bone < AnimatorComponent.MAX_BONES;
    }

    /** The Character Controller carrying {@code entity}: on it or on an ancestor, or null. */
    private static Entity findController(Entity entity, List<Entity> entities) {
        Entity at = entity;
        for (int depth = 0; at != null && depth < MAX_PARENT_DEPTH; depth++) {
            if (at.getComponent(CharacterControllerComponent.class) != null) return at;
            String parent = at.parentId;
            at = null;
            if (parent == null || parent.isEmpty()) break;
            for (int i = 0; i < entities.size(); i++) {
                Entity candidate = entities.get(i);
                if (candidate != null && parent.equals(candidate.id)) {
                    at = candidate;
                    break;
                }
            }
        }
        return null;
    }

    // --- Aim and look ---

    private void solveAim(Entity entity, AnimatorComponent animator, BoneAimConstraint ik, WorldTransform world, List<Entity> entities,
                          HierarchySystem hierarchy, float deltaTime) {
        Skeleton skeleton = animator.skeleton;
        if (!resolveChain(entity, ik, skeleton)) return;
        float[] joints = skeleton.getBindJointPositions();
        PoseEditor.jointPosition(animator, joints, ik.aimBoneIndex, aimOrigin);
        PoseEditor.boneDirection(animator, ik.aimBoneIndex, ik.axisX, ik.axisY, ik.axisZ, aimDirection);

        boolean tracking = ik.active && findTarget(entity, ik, entities, hierarchy);
        if (tracking) {
            toModel(worldPoint, world, wanted);
            wanted[0] -= aimOrigin[0];
            wanted[1] -= aimOrigin[1];
            wanted[2] -= aimOrigin[2];
            tracking = PoseEditor.normalize(wanted) > 0.0;
        }
        if (tracking) {
            double angle = PoseEditor.angleBetween(aimDirection, wanted);
            double limit = Math.toRadians(ik.maxAngle);
            tracking = angle <= limit + Math.toRadians(GIVE_UP_MARGIN);
            if (tracking) {
                PoseEditor.rotationBetween(aimDirection, wanted, angle > limit ? limit / angle : 1.0, turn);
                PoseEditor.apply(turn, aimDirection, goal);
                PoseEditor.apply(rig, goal, goal);
            }
        }

        float ease = 1.0f - (float) Math.exp(-ik.speed * deltaTime);
        if (tracking) {
            if (!ik.warm || ik.blend < MIN_EDIT) {
                System.arraycopy(goal, 0, ik.direction, 0, 3);
                ik.warm = true;
            } else {
                for (int i = 0; i < 3; i++) ik.direction[i] += (goal[i] - ik.direction[i]) * ease;
                PoseEditor.normalize(ik.direction);
            }
        }
        ik.blend += ((tracking ? 1.0f : 0.0f) - ik.blend) * ease;

        float strength = Math.max(0.0f, Math.min(1.0f, ik.weight)) * ik.blend;
        if (strength < MIN_EDIT || !ik.warm) return;

        PoseEditor.applyInverse(rig, ik.direction, goal);
        PoseEditor.rotationBetween(aimDirection, goal, strength, turn);
        PoseEditor.apply(turn, aimDirection, goal);

        // Each bone takes its share of what's left, weighted toward the tip, so the last one lands it exactly
        int count = ik.chainBones.length;
        for (int i = 0; i < count; i++) {
            int remaining = 0;
            for (int j = i; j < count; j++) remaining += j + 1;
            PoseEditor.boneDirection(animator, ik.aimBoneIndex, ik.axisX, ik.axisY, ik.axisZ, current);
            PoseEditor.rotationBetween(current, goal, (i + 1.0) / remaining, turn);
            PoseEditor.jointPosition(animator, joints, ik.chainBones[i], pivot);
            editor.rotate(animator, ik.chainBones[i], pivot, turn);
        }
    }

    /** Leaves the target's world position in {@link #worldPoint}. Returns false when there is none. */
    private boolean findTarget(Entity entity, BoneAimConstraint ik, List<Entity> entities, HierarchySystem hierarchy) {
        if (ik.hasTargetPoint()) {
            worldPoint[0] = ik.targetPointX();
            worldPoint[1] = ik.targetPointY();
            worldPoint[2] = ik.targetPointZ();
            return true;
        }
        if (ik.targetEntity == null || ik.targetEntity.isEmpty()) return false;
        for (int i = 0; i < entities.size(); i++) {
            Entity candidate = entities.get(i);
            if (candidate == null || candidate == entity || !ik.targetEntity.equals(candidate.name)) continue;
            WorldTransform at = hierarchy.getComputedWorldTransform(candidate, entities);
            worldPoint[0] = at.px();
            worldPoint[1] = at.py() + ik.targetHeight;
            worldPoint[2] = at.pz();
            return true;
        }
        return false;
    }

    private static boolean resolveChain(Entity entity, BoneAimConstraint ik, Skeleton skeleton) {
        if (ik.resolvedSkeleton != skeleton || !ik.chain.equals(ik.resolvedChain) || !ik.aimBone.equals(ik.resolvedAimBone)) {
            ik.resolvedSkeleton = skeleton;
            ik.resolvedChain = ik.chain;
            ik.resolvedAimBone = ik.aimBone;
            String[] names = ik.chain.split(",");
            ik.chainBones = new int[names.length];
            boolean valid = !ik.chain.isBlank();
            String missing = ik.chain;
            for (int i = 0; i < names.length; i++) {
                ik.chainBones[i] = skeleton.getBoneIndex(names[i].trim());
                if (!posable(ik.chainBones[i])) {
                    valid = false;
                    missing = names[i].trim();
                }
            }
            if (valid) {
                ik.aimBoneIndex = ik.aimBone.isBlank() ? ik.chainBones[names.length - 1] : skeleton.getBoneIndex(ik.aimBone.trim());
                if (!posable(ik.aimBoneIndex)) {
                    valid = false;
                    missing = ik.aimBone;
                }
            }
            ik.resolved = valid;
            if (!valid && !ik.warned) {
                ik.warned = true;
                System.err.println("[" + ik.getComponentName() + "] '" + entity.name + "': no bone '" + missing + "'");
            }
        }
        return ik.resolved;
    }

    // --- Spaces ---

    private void toWorld(double[] model, WorldTransform world, double[] out) {
        out[0] = model[0] * world.sx();
        out[1] = model[1] * world.sy();
        out[2] = model[2] * world.sz();
        PoseEditor.apply(rig, out, out);
        out[0] += world.px();
        out[1] += world.py();
        out[2] += world.pz();
    }

    private void toModel(double[] point, WorldTransform world, double[] out) {
        out[0] = point[0] - world.px();
        out[1] = point[1] - world.py();
        out[2] = point[2] - world.pz();
        PoseEditor.applyInverse(rig, out, out);
        out[0] /= nonZero(world.sx());
        out[1] /= nonZero(world.sy());
        out[2] /= nonZero(world.sz());
    }

    /** A world height change as a model-space offset. */
    private void modelOffset(float height, WorldTransform world, double[] out) {
        out[0] = 0.0;
        out[1] = height;
        out[2] = 0.0;
        PoseEditor.applyInverse(rig, out, out);
        out[0] /= nonZero(world.sx());
        out[1] /= nonZero(world.sy());
        out[2] /= nonZero(world.sz());
    }

    private static float nonZero(float scale) {
        return Math.abs(scale) > 1e-6f ? scale : 1.0f;
    }

    private void multiply(double[] a, double[] b, double[] out) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                partial[row * 3 + column] = a[row * 3] * b[column] + a[row * 3 + 1] * b[3 + column] + a[row * 3 + 2] * b[6 + column];
            }
        }
        System.arraycopy(partial, 0, out, 0, 9);
    }

    /** out = a * transpose(b). */
    private void multiplyTransposed(double[] a, double[] b, double[] out) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                partial[row * 3 + column] = a[row * 3] * b[column * 3] + a[row * 3 + 1] * b[column * 3 + 1] + a[row * 3 + 2] * b[column * 3 + 2];
            }
        }
        System.arraycopy(partial, 0, out, 0, 9);
    }
}
