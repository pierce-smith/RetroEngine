package com.retro.engine.ecs.components;

/** Turns a character's head toward a target, with the neck and spine helping. See {@link BoneAimConstraint}. */
public class LookAtIK extends BoneAimConstraint {

    public LookAtIK() {
        chain = "Spine,Neck,Head";
    }

    @Override
    public String getComponentName() {
        return "Look At IK";
    }
}
