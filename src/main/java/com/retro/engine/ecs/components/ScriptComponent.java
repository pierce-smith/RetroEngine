package com.retro.engine.ecs.components;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;
import com.retro.engine.scripting.EntityScript;

import java.util.HashMap;
import java.util.Map;

/**
 * Component holding script class path, script asset metadata,
 * active EntityScript instance, and reflected property overrides.
 */
public class ScriptComponent extends Component {

    @InspectorProperty(order = 0, label = "SCRIPT PATH", type = PropertyType.ASSET_PICKER, assetType = "SCRIPT")
    public String scriptAssetPath;

    @InspectorProperty(order = 1, label = "SCRIPT NAME", type = PropertyType.TEXT)
    public String scriptName;

    @InspectorProperty(order = 2, label = "ENABLED", type = PropertyType.TOGGLE)
    public boolean enabled = true;

    // Reflected field overrides for user script properties (key = fieldName, value = stringValue)
    @InspectorProperty(order = 3, label = "OVERRIDES", hidden = true, since = 2)
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

    /** Inspector card title: "Script (ClassName)", or "Script Component" while no script is assigned. */
    public String getInspectorTitle() {
        String raw = (scriptName != null && !scriptName.trim().isEmpty()) ? scriptName : scriptAssetPath;
        if (raw == null || raw.trim().isEmpty()) return "Script Component";
        String name = raw.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.toLowerCase().endsWith(".java")) name = name.substring(0, name.length() - 5);
        return "Script (" + name + ")";
    }
}
