package com.retro.engine.graphics;

import com.retro.engine.api.Experimental;
import com.retro.engine.api.PublicApi;

/**
 * One queued draw: opaque GPU handles from {@link IRenderBackend#registerMesh} / {@link IRenderBackend#registerTexture},
 * a world transform and flat material scalars. Pooled and reused by {@link RenderQueue}; backends read it and never
 * retain it past the frame. A 0L texture handle marks the channel absent.
 */
@Experimental
@PublicApi
public final class RenderCommand {

    public long sortKey;

    public long meshHandle;
    public long albedoTexture;
    public long normalTexture;
    /** R = specular mask, G = roughness multiplier. */
    public long specularTexture;
    /** Sampled with the mesh's second UV set; modulates ambient/indirect light (baked lightmap or AO). */
    public long lightmapTexture;
    public long emissiveTexture;

    public float px, py, pz;
    public float rx, ry, rz;
    public float sx, sy, sz;
    public float groundY;

    public float reflect;
    public float specularStrength;
    public float roughness;
    public float normalStrength;
    public float emissiveIntensity;
    /** Albedo is drawn as-is: no scene lights, ambient or received shadows. Fog, emissive and shadow casting still apply. */
    public boolean unlit;
    /** Blended over the surface after opaque geometry, with no depth writes, shadow casting or picking. */
    public boolean decal;
    public float decalOpacity;
    /** How far a decal has spread, 0..1: its image is scaled by this around the center of its UVs. */
    public float decalGrowth = 1.0f;

    /** Color-ID value written by the pick pass ({@link IRenderBackend#PICK_ID_NONE} = not pickable). */
    public int pickId;
    /** Column-major 4x4 bone matrices for skinned meshes, or null. Read during encoding, never copied. */
    public float[][] bonePalette;

    void reset() {
        sortKey = 0L;
        meshHandle = albedoTexture = normalTexture = specularTexture = lightmapTexture = emissiveTexture = 0L;
        px = py = pz = rx = ry = rz = 0.0f;
        sx = sy = sz = 1.0f;
        groundY = 0.0f;
        reflect = 0.0f;
        specularStrength = 0.0f;
        roughness = 0.6f;
        normalStrength = 1.0f;
        emissiveIntensity = 1.0f;
        unlit = false;
        decal = false;
        decalOpacity = 0.0f;
        decalGrowth = 1.0f;
        pickId = IRenderBackend.PICK_ID_NONE;
        bonePalette = null;
    }
}
