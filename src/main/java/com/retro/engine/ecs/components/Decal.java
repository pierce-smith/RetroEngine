package com.retro.engine.ecs.components;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/**
 * A decal placed by hand: projects a texture along this entity's -Y axis onto every mesh inside its box, so a fresh
 * entity stamps straight down onto the floor. The image's right is the entity's +X and its top is -Z. Entity scale
 * multiplies the box. For decals made during play, such as bullet holes, scripts use spawnDecal instead.
 *
 * <p>Geometry is projected once when Play starts, then follows the meshes it landed on. In the editor it rebuilds
 * whenever the decal or a surface under it moves.</p>
 */
public class Decal extends Component {

    public enum Style { BLOOD, BULLET_HOLE, SCORCH, SOFT, TEXTURE }

    /** The built-in images, a soft round spot, or TEXTURE for your own. */
    @InspectorProperty(order = 0, label = "STYLE", type = PropertyType.BUTTON_GROUP)
    public Style style = Style.BLOOD;

    /** Used when STYLE is TEXTURE. Alpha is the decal's shape. */
    @InspectorProperty(order = 1, label = "TEXTURE", type = PropertyType.ASSET_PICKER, assetType = "TEXTURE")
    public String texturePath;

    /** Optional tangent-space normal map, for surface detail such as a dented bullet hole. */
    @InspectorProperty(order = 2, label = "NORMAL MAP", type = PropertyType.ASSET_PICKER, assetType = "TEXTURE")
    public String normalPath;

    @InspectorProperty(order = 3, label = "WIDTH", min = 0.01f, max = 20.0f, step = 0.05f)
    public float width = 1.0f;
    @InspectorProperty(order = 4, label = "HEIGHT", min = 0.01f, max = 20.0f, step = 0.05f)
    public float height = 1.0f;
    /** How far the box reaches along the projection axis, centered on the entity. */
    @InspectorProperty(order = 5, label = "DEPTH", min = 0.01f, max = 20.0f, step = 0.05f)
    public float depth = 1.0f;

    @InspectorProperty(order = 6, label = "RED", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float colorR = 1.0f;
    @InspectorProperty(order = 7, label = "GREEN", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float colorG = 1.0f;
    @InspectorProperty(order = 8, label = "BLUE", min = 0.0f, max = 1.0f, step = 0.05f, type = PropertyType.COLOR)
    public float colorB = 1.0f;
    @InspectorProperty(order = 9, label = "OPACITY", min = 0.0f, max = 1.0f, step = 0.05f)
    public float opacity = 1.0f;

    @InspectorProperty(order = 10, label = "SPECULAR", min = 0.0f, max = 2.0f, step = 0.05f)
    public float specularStrength = 0.1f;
    @InspectorProperty(order = 11, label = "ROUGHNESS", min = 0.0f, max = 1.0f, step = 0.05f)
    public float roughness = 0.6f;
    @InspectorProperty(order = 12, label = "NORMAL STRENGTH", min = 0.0f, max = 4.0f, step = 0.05f)
    public float normalStrength = 1.0f;

    /** Surfaces turned further than this from the projection axis get no decal, which prevents long smears. */
    @InspectorProperty(order = 13, label = "MAX ANGLE", min = 1.0f, max = 90.0f, step = 1.0f)
    public float maxAngle = 75.0f;

    /** Whether rigged meshes receive this decal. It then bends with them as they animate. */
    @InspectorProperty(order = 14, label = "AFFECT SKINNED", type = PropertyType.TOGGLE)
    public boolean affectSkinned = true;

    /** Name of the only entity to project onto. Blank projects onto everything in the box. */
    @InspectorProperty(order = 15, label = "TARGET ENTITY", type = PropertyType.TEXT)
    public String targetEntity = "";

    public Decal() {}

    @Override
    public String getComponentName() {
        return "Decal";
    }
}
