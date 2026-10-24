package com.retro.engine.scene.components.media;

import com.retro.engine.animation.clips.AnimationClip;
import com.retro.engine.animation.state.AnimatorController;
import com.retro.engine.animation.skeleton.Skeleton;
import com.retro.engine.scene.components.Component;

/**
 * Entity Component storing skeleton reference, active animation clip, state machine controller,
 * playback state, and pre-allocated 64-matrix bone palette buffer for zero per-frame JVM allocations.
 */
public class AnimatorComponent implements Component {

    public static final int MAX_BONES = 64;

    public String activeClipName = "";
    public AnimationClip currentClip = null;
    public Skeleton skeleton = null;

    public AnimatorController controller = null;

    public float playbackTime = 0.0f;
    public float playbackSpeed = 1.0f;
    public boolean isPlaying = true;

    // 64 pre-allocated column-major 4x4 matrix arrays for direct GPU VBO/UBO streaming
    public final float[][] bonePaletteMatrices = new float[MAX_BONES][16];

    private boolean dirty = true;

    public AnimatorComponent() {
        initIdentityPalette();
    }

    public AnimatorComponent(Skeleton skeleton, AnimationClip initialClip) {
        this.skeleton = skeleton;
        this.currentClip = initialClip;
        if (initialClip != null) {
            this.activeClipName = initialClip.name;
        }
        initIdentityPalette();
    }

    public AnimatorComponent(Skeleton skeleton, AnimatorController controller) {
        this.skeleton = skeleton;
        this.controller = controller;
        initIdentityPalette();
    }

    private void initIdentityPalette() {
        for (int b = 0; b < MAX_BONES; b++) {
            for (int i = 0; i < 16; i++) {
                bonePaletteMatrices[b][i] = (i % 5 == 0) ? 1.0f : 0.0f;
            }
        }
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    @Override
    public String getComponentName() {
        return "Animator Component";
    }
}
