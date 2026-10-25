package com.retro.engine.scene.components.rendering;

import com.retro.engine.editor.ui.panels.InspectorProperty;
import com.retro.engine.editor.ui.panels.InspectorProperty.PropertyType;
import com.retro.engine.scene.components.Component;

/**
 * Links an Entity to its shader and 2D texture resources.
 * Exposes specular reflection properties for RenderWare-style material rendering.
 */
public class Material implements Component {

    @InspectorProperty(label = "TEXTURE", type = PropertyType.ASSET_PICKER, assetType = "TEXTURE")
    public String textureAssetPath;

    @InspectorProperty(label = "TEX NAME", type = PropertyType.TEXT)
    public String textureName = "DEFAULT_CHECKER";

    @InspectorProperty(label = "SHADER", type = PropertyType.TEXT)
    public String shaderName = "RETRO_MSL_3D";

    @InspectorProperty(label = "REFLECT", min = 0.0f, max = 1.0f, step = 0.05f)
    public float reflect = 0.0f;

    // Runtime-only GPU handle cache filled by SceneRenderPipeline; never serialized or shown in the inspector
    private transient long gpuTextureHandle = 0L;
    private transient String gpuTextureKey;
    private transient int gpuHandleEpoch;

    /** Returns the cached GPU texture handle if it was resolved for this path in this epoch, otherwise 0L. */
    public long getCachedTextureHandle(String path, int epoch) {
        return (gpuTextureHandle != 0L && epoch == gpuHandleEpoch && path.equals(gpuTextureKey)) ? gpuTextureHandle : 0L;
    }

    public void cacheTextureHandle(String path, long handle, int epoch) {
        this.gpuTextureKey = path;
        this.gpuTextureHandle = handle;
        this.gpuHandleEpoch = epoch;
    }

    public Material() {}

    public Material(String textureAssetPath, String textureName) {
        this.textureAssetPath = textureAssetPath;
        this.textureName = textureName;
    }

    public Material(String textureAssetPath, String textureName, float reflect) {
        this.textureAssetPath = textureAssetPath;
        this.textureName = textureName;
        this.reflect = reflect;
    }

    @Override
    public String getComponentName() {
        return "Material & Shader";
    }
}
