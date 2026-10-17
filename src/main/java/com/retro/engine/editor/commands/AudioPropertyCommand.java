package com.retro.engine.editor.commands;

import com.retro.engine.scene.Entity;
import com.retro.engine.scene.components.AudioSource;

/**
 * Encapsulates AudioSource component edits (volume, pitch, loop, clip path) for Undo/Redo.
 */
public class AudioPropertyCommand implements ICommand {
    private final Entity entity;
    private final String oldPath, newPath;
    private final float oldVolume, newVolume;
    private final float oldPitch, newPitch;
    private final boolean oldLoop, newLoop;

    public AudioPropertyCommand(Entity entity,
                                String oldPath, String newPath,
                                float oldVolume, float newVolume,
                                float oldPitch, float newPitch,
                                boolean oldLoop, boolean newLoop) {
        this.entity = entity;
        this.oldPath = oldPath; this.newPath = newPath;
        this.oldVolume = oldVolume; this.newVolume = newVolume;
        this.oldPitch = oldPitch; this.newPitch = newPitch;
        this.oldLoop = oldLoop; this.newLoop = newLoop;
    }

    @Override
    public void execute() {
        if (entity == null) return;
        AudioSource ac = entity.getComponent(AudioSource.class);
        if (ac != null) {
            if (newPath != null) ac.audioAssetPath = newPath;
            ac.setVolume(newVolume);
            ac.setPitch(newPitch);
            ac.loop = newLoop;
        }
    }

    @Override
    public void undo() {
        if (entity == null) return;
        AudioSource ac = entity.getComponent(AudioSource.class);
        if (ac != null) {
            if (oldPath != null) ac.audioAssetPath = oldPath;
            ac.setVolume(oldVolume);
            ac.setPitch(oldPitch);
            ac.loop = oldLoop;
        }
    }

    @Override
    public String getName() {
        return "Modify AudioSource on '" + (entity != null ? entity.name : "null") + "'";
    }
}
