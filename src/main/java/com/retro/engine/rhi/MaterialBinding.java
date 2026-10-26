package com.retro.engine.rhi;

/**
 * Per-draw material description passed to {@link IRenderBackend#submitEntity(long, MaterialBinding, float, float, float,
 * float, float, float, float, float, float, float, float[][])}. Texture fields are opaque handles from
 * {@link IRenderBackend#registerTexture}; 0L marks the channel as absent. Mutable and pooled by callers so frame
 * submission stays allocation-free; backends copy what they need and never retain the instance.
 */
public final class MaterialBinding {

    public long albedoTexture;
    public long normalTexture;
    /** R = specular mask, G = roughness multiplier. */
    public long specularTexture;
    /** Sampled with the mesh's second UV set; modulates ambient/indirect light (baked lightmap or AO). */
    public long lightmapTexture;
    public long emissiveTexture;

    public float reflect;
    public float specularStrength;
    public float roughness = 0.6f;
    public float normalStrength = 1.0f;
    public float emissiveIntensity = 1.0f;

    public void reset() {
        albedoTexture = normalTexture = specularTexture = lightmapTexture = emissiveTexture = 0L;
        reflect = 0.0f;
        specularStrength = 0.0f;
        roughness = 0.6f;
        normalStrength = 1.0f;
        emissiveIntensity = 1.0f;
    }
}
