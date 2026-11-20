package com.retro.engine.ecs.components;

import com.retro.engine.api.PublicApi;
import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/**
 * Saves the game when the player walks into it. Put it on an entity with a trigger collider (a Box, Sphere or Capsule
 * Collider with IS TRIGGER on). The save happens at the end of the frame the player enters, into {@link #slot}.
 *
 * <p>The player is the entity named {@link #playerName}, or, when that is empty, any entity with a Character Controller
 * or Vehicle Controller. With {@link #onlyOnce} on, a checkpoint that has saved stays used, and that is saved too, so
 * loading the save does not trigger it again.</p>
 */
@PublicApi
public class Checkpoint extends Component {

    @InspectorProperty(order = 0, label = "SAVE SLOT", type = PropertyType.TEXT)
    public String slot = "autosave";

    @InspectorProperty(order = 1, label = "DISPLAY NAME", type = PropertyType.TEXT)
    public String displayName = "";

    @InspectorProperty(order = 2, label = "ONLY ONCE", type = PropertyType.TOGGLE)
    public boolean onlyOnce = true;

    @InspectorProperty(order = 3, label = "PLAYER NAME", type = PropertyType.TEXT)
    public String playerName = "";

    /** Set when the checkpoint saved. A scene property, so the save system carries it in the save. */
    @InspectorProperty(order = 4, label = "REACHED", hidden = true)
    public boolean reached = false;

    public Checkpoint() {}

    @Override
    public String getComponentName() {
        return "Checkpoint";
    }
}
