package com.retro.engine.scene.components.gameplay;

import com.retro.engine.editor.ui.panels.InspectorProperty;
import com.retro.engine.editor.ui.panels.InspectorProperty.PropertyType;
import com.retro.engine.scene.components.Component;
import com.retro.engine.scripting.EntityScript;

import java.util.HashMap;
import java.util.Map;

/**
 * Component holding script class path, script asset metadata,
 * active EntityScript instance, and reflected property overrides.
 */
public class ScriptComponent implements Component {

    @InspectorProperty(label = "SCRIPT PATH", type = PropertyType.ASSET_PICKER, assetType = "SCRIPT")
    public String scriptAssetPath;

    @InspectorProperty(label = "SCRIPT NAME", type = PropertyType.TEXT)
    public String scriptName;

    @InspectorProperty(label = "ENABLED", type = PropertyType.TOGGLE)
    public boolean enabled = true;

    // Reflected field overrides for user script properties (key = fieldName, value = stringValue)
    public Map<String, String> propertyOverrides = new HashMap<>();

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
