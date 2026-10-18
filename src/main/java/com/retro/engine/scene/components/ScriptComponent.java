package com.retro.engine.scene.components;

import com.retro.engine.editor.ui.InspectorProperty;
import com.retro.engine.editor.ui.InspectorProperty.PropertyType;
import com.retro.engine.scripting.EntityScript;

/**
 * Component holding script class path, script asset metadata, and active EntityScript instance.
 */
public class ScriptComponent implements Component {

    @InspectorProperty(label = "SCRIPT PATH", type = PropertyType.ASSET_PICKER, assetType = "SCRIPT")
    public String scriptAssetPath;

    @InspectorProperty(label = "SCRIPT NAME", type = PropertyType.TEXT)
    public String scriptName;

    @InspectorProperty(label = "ENABLED", type = PropertyType.TOGGLE)
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
