package com.retro.engine.scene.components;

import com.retro.engine.scripting.EntityScript;

/**
 * Component holding script class path, script asset metadata, and active EntityScript instance.
 */
public class ScriptComponent implements Component {

    public String scriptAssetPath;
    public String scriptName;
    public boolean enabled = true;
    public transient EntityScript activeScriptInstance;

    public ScriptComponent() {
        this(null, null);
    }

    public ScriptComponent(String scriptAssetPath, String scriptName) {
        this.scriptAssetPath = scriptAssetPath;
        this.scriptName = scriptName;
        this.enabled = true;
    }

    @Override
    public String getComponentName() {
        return "ScriptComponent";
    }
}
