package com.retro.engine.ecs.components;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/**
 * Static triangle collider for level geometry. It never moves under physics, whatever RigidBody the entity has, and its
 * scale is read once when Play starts. Leave the mesh empty to use the entity's own Mesh Filter.
 */
public class MeshCollider extends Component {

    @InspectorProperty(order = 0, label = "MESH", type = PropertyType.ASSET_PICKER, assetType = "MESH_3D", since = 10, prefabSince = 9)
    public String meshPath = "";

    @InspectorProperty(order = 1, label = "LAYER", min = 0, max = 31, step = 1, since = 10, prefabSince = 9)
    public int collisionLayer = 0;

    @InspectorProperty(order = 2, label = "COLLIDES WITH", since = 10, prefabSince = 9)
    public String collidesWith = "";

    public MeshCollider() {}

    @Override
    public String getComponentName() {
        return "Mesh Collider";
    }
}
