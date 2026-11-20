package com.retro.engine.save;

import com.retro.engine.api.Internal;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.components.CharacterControllerComponent;
import com.retro.engine.ecs.components.Checkpoint;
import com.retro.engine.ecs.components.VehicleControllerComponent;

/** Turns a player entering a {@link Checkpoint} trigger into a save at the end of the frame. */
@Internal
public final class CheckpointSystem {

    private CheckpointSystem() {}

    /** Called for every trigger-enter pair, from the physics step. */
    public static void onTriggerEnter(Entity trigger, Entity other) {
        if (trigger == null || other == null) return;
        Checkpoint cp = trigger.getComponent(Checkpoint.class);
        if (cp == null || (cp.onlyOnce && cp.reached) || !isPlayer(cp, other)) return;
        SaveSystem s = SaveSystem.active();
        if (s == null) return;
        cp.reached = true;
        String name = (cp.displayName != null && !cp.displayName.isBlank()) ? cp.displayName : trigger.name;
        s.requestSave(cp.slot != null && !cp.slot.isBlank() ? cp.slot : "autosave", name);
    }

    static boolean isPlayer(Checkpoint cp, Entity other) {
        if (cp.playerName != null && !cp.playerName.isBlank()) return cp.playerName.equals(other.name);
        return other.getComponent(CharacterControllerComponent.class) != null
                || other.getComponent(VehicleControllerComponent.class) != null;
    }
}
