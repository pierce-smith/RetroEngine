package com.retro.engine.ecs.components;

/**
 * Turns a character's upper body so a held weapon points at a target. Set AIM BONE to the hand and the axis to the way
 * the weapon points in the bind pose. See {@link BoneAimConstraint}.
 */
public class AimIK extends BoneAimConstraint {

    public AimIK() {
        chain = "Spine";
        maxAngle = 60.0f;
    }

    @Override
    public String getComponentName() {
        return "Aim IK";
    }
}
