package com.retro.engine.save;

import java.util.LinkedHashMap;
import java.util.Map;

/** Everything one save slot holds: where the player is, the slot's values, and the remembered state of each level. */
final class SaveData {
    String scenePath = "";
    String displayName = "";
    long savedAtMillis;
    double playTimeSeconds;
    final SaveRecord values = new SaveRecord();
    /** Scene path (as given to SceneManager.loadScene) -> what changed in it. Always includes {@link #scenePath}. */
    final Map<String, SceneState> levels = new LinkedHashMap<>();
}
