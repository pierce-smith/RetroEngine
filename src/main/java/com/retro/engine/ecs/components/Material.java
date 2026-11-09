package com.retro.engine.ecs.components;

import com.retro.engine.api.Internal;
import com.retro.engine.api.PublicApi;
import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/**
 * Links an Entity to its shader and multi-channel texture resources: albedo/diffuse, tangent-space normal map,
 * specular/roughness (R = specular mask, G = roughness multiplier), lightmap/AO (sampled with UV2) and emissive.
 * Exposes Blinn-Phong surface properties for RenderWare-style material rendering.
 */
@PublicApi
public class Material extends Component {

    // Channel indices for the per-channel path accessors and GPU handle cache
    public static final int CHANNEL_ALBEDO = 0;
    public static final int CHANNEL_NORMAL = 1;
    public static final int CHANNEL_SPECULAR = 2;
    public static final int CHANNEL_LIGHTMAP = 3;
    public static final int CHANNEL_EMISSIVE = 4;
    public static final int CHANNEL_COUNT = 5;

    /** Albedo / diffuse channel. Field name kept for serializer and editor compatibility. */
    @InspectorProperty(order = 0, label = "ALBEDO", type = PropertyType.ASSET_PICKER, assetType = "TEXTURE")
    public String textureAssetPath;

    @InspectorProperty(order = 1, label = "NORMAL MAP", type = PropertyType.ASSET_PICKER, assetType = "TEXTURE", prefabSince = 3)
    public String normalMapAssetPath;

    @InspectorProperty(order = 2, label = "SPEC / ROUGH", type = PropertyType.ASSET_PICKER, assetType = "TEXTURE", prefabSince = 3)
    public String specularMapAssetPath;

    @InspectorProperty(order = 3, label = "LIGHTMAP / AO", type = PropertyType.ASSET_PICKER, assetType = "TEXTURE", prefabSince = 3)
    public String lightmapAssetPath;

    @InspectorProperty(order = 4, label = "EMISSIVE", type = PropertyType.ASSET_PICKER, assetType = "TEXTURE", prefabSince = 3)
    public String emissiveMapAssetPath;

    @InspectorProperty(order = 5, label = "TEX NAME", type = PropertyType.TEXT)
    public String textureName = "DEFAULT_CHECKER";

    @InspectorProperty(order = 6, label = "SHADER", type = PropertyType.TEXT)
    public String shaderName = "RETRO_MSL_3D";

    @InspectorProperty(order = 7, label = "REFLECT", min = 0.0f, max = 1.0f, step = 0.05f, prefabSince = 3)
    public float reflect = 0.0f;

    /** Scales the specular map's R channel (or acts alone without a map); 0 disables highlights. */
    @InspectorProperty(order = 8, label = "SPECULAR", min = 0.0f, max = 2.0f, step = 0.05f, prefabSince = 3)
    public float specularStrength = 0.0f;

    /** Scales the specular map's G channel (or acts alone without a map); 0 = mirror-sharp, 1 = fully rough. */
    @InspectorProperty(order = 9, label = "ROUGHNESS", min = 0.0f, max = 1.0f, step = 0.05f, prefabSince = 3)
    public float roughness = 0.6f;

    @InspectorProperty(order = 10, label = "NORMAL STR", min = 0.0f, max = 2.0f, step = 0.05f, prefabSince = 3)
    public float normalStrength = 1.0f;

    @InspectorProperty(order = 11, label = "EMISSIVE STR", min = 0.0f, max = 10.0f, step = 0.1f, prefabSince = 3)
    public float emissiveIntensity = 1.0f;

    // Runtime-only GPU handle cache per channel, filled by SceneRenderPipeline; never serialized or shown in the inspector
    private final transient long[] gpuTextureHandles = new long[CHANNEL_COUNT];
    private final transient String[] gpuTextureKeys = new String[CHANNEL_COUNT];
    private final transient int[] gpuHandleEpochs = new int[CHANNEL_COUNT];

    /** Asset path assigned to the given channel, or null when the channel is empty. */
    public String getChannelPath(int channel) {
        return switch (channel) {
            case CHANNEL_ALBEDO -> textureAssetPath;
            case CHANNEL_NORMAL -> normalMapAssetPath;
            case CHANNEL_SPECULAR -> specularMapAssetPath;
            case CHANNEL_LIGHTMAP -> lightmapAssetPath;
            case CHANNEL_EMISSIVE -> emissiveMapAssetPath;
            default -> null;
        };
    }

    /** Returns the cached GPU texture handle if it was resolved for this channel's path in this epoch, otherwise 0L. */
    @Internal
    public long getCachedChannelHandle(int channel, String path, int epoch) {
        long handle = gpuTextureHandles[channel];
        return (handle != 0L && epoch == gpuHandleEpochs[channel] && path.equals(gpuTextureKeys[channel])) ? handle : 0L;
    }

    @Internal
    public void cacheChannelHandle(int channel, String path, long handle, int epoch) {
        gpuTextureKeys[channel] = path;
        gpuTextureHandles[channel] = handle;
        gpuHandleEpochs[channel] = epoch;
    }

    /** Returns the cached albedo GPU texture handle if it was resolved for this path in this epoch, otherwise 0L. */
    @Internal
    public long getCachedTextureHandle(String path, int epoch) {
        return getCachedChannelHandle(CHANNEL_ALBEDO, path, epoch);
    }

    @Internal
    public void cacheTextureHandle(String path, long handle, int epoch) {
        cacheChannelHandle(CHANNEL_ALBEDO, path, handle, epoch);
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
