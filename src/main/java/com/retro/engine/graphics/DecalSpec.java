package com.retro.engine.graphics;

import com.retro.engine.api.PublicApi;
import com.retro.engine.ecs.Entity;
import com.retro.engine.physics.RaycastHit;

/**
 * Everything needed to stamp one decal. Scripts fill the public fields and pass it to {@code spawnDecal}. The spec is
 * copied at that point, so one instance can be reused for every shot.
 *
 * <p>The decal is a box centered on the position. Its depth axis is the surface normal, and every mesh surface inside
 * the box that faces the normal receives the image.</p>
 */
@PublicApi
public final class DecalSpec {

    /** Texture path, or one of the built-in {@link DecalTextures} images. Null draws a soft round spot in the tint color. */
    public String texturePath;
    /** Optional tangent-space normal map. */
    public String normalPath;

    public float posX, posY, posZ;
    /** Direction the decal faces, pointing out of the surface. Need not be normalized. */
    public float normalX, normalY = 1.0f, normalZ;
    /** Optional direction of the image's right edge. Left at zero, the engine picks one from world up. */
    public float tangentX, tangentY, tangentZ;
    /** Degrees around the normal. */
    public float rotation;

    public float width = 0.25f, height = 0.25f;
    /** Full box depth along the normal. Raise it when the surface may sit behind the position, as with capsule hits. */
    public float depth = 0.3f;

    public float colorR = 1.0f, colorG = 1.0f, colorB = 1.0f;
    public float opacity = 1.0f;

    public float specularStrength = 0.1f;
    public float roughness = 0.6f;
    public float normalStrength = 1.0f;

    /** Surfaces turned further than this many degrees from the normal get no decal. */
    public float maxAngle = 75.0f;

    /** Seconds until the decal is removed; 0 keeps it until the budget evicts it. */
    public float lifetime;
    /** Seconds of fade at the end of {@link #lifetime}. */
    public float fadeTime = 1.0f;
    /** Seconds to spread out from nothing at its center, slowing as it goes, like a pool of blood. 0 appears at full size. */
    public float growTime;

    public boolean affectSkinned = true;
    /** When set, only this entity receives the decal. */
    public Entity target;
    /** When set, this entity never receives the decal, for example the shooter's own arms. */
    public Entity exclude;

    public DecalSpec() {}

    public DecalSpec(String texturePath, float size) {
        this.texturePath = texturePath;
        setSize(size);
    }

    /** Square decal, with a box deep enough to catch a surface that isn't perfectly flat. */
    public DecalSpec setSize(float size) {
        width = size;
        height = size;
        depth = Math.max(size, 0.3f);
        return this;
    }

    public DecalSpec setPosition(float x, float y, float z, float nx, float ny, float nz) {
        posX = x; posY = y; posZ = z;
        normalX = nx; normalY = ny; normalZ = nz;
        return this;
    }

    /** Places the decal where a raycast landed. */
    public DecalSpec setHit(RaycastHit hit) {
        return setPosition(hit.pointX, hit.pointY, hit.pointZ, hit.normalX, hit.normalY, hit.normalZ);
    }

    public DecalSpec setColor(float r, float g, float b) {
        colorR = r; colorG = g; colorB = b;
        return this;
    }

    public DecalSpec copy() {
        DecalSpec s = new DecalSpec();
        s.texturePath = texturePath; s.normalPath = normalPath;
        s.posX = posX; s.posY = posY; s.posZ = posZ;
        s.normalX = normalX; s.normalY = normalY; s.normalZ = normalZ;
        s.tangentX = tangentX; s.tangentY = tangentY; s.tangentZ = tangentZ;
        s.rotation = rotation;
        s.width = width; s.height = height; s.depth = depth;
        s.colorR = colorR; s.colorG = colorG; s.colorB = colorB; s.opacity = opacity;
        s.specularStrength = specularStrength; s.roughness = roughness; s.normalStrength = normalStrength;
        s.maxAngle = maxAngle; s.lifetime = lifetime; s.fadeTime = fadeTime; s.growTime = growTime;
        s.affectSkinned = affectSkinned; s.target = target; s.exclude = exclude;
        return s;
    }
}
