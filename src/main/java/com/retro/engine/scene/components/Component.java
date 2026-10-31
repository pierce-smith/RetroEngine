package com.retro.engine.scene.components;

import com.retro.engine.scene.entity.Entity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Universal base class for modular entity components in Retro Engine ECS.
 * Persisted and inspected state is declared with {@code @InspectorProperty} fields; see {@link ComponentSchema}.
 */
public abstract class Component {
    /** Editor-only: Inspector card shows just its header bar (not persisted, not copied on duplicate). */
    public boolean isInspectorCollapsed = false;

    public abstract String getComponentName();

    /** Called before a serializer reads this component's persisted fields, so derived state can be encoded into them. */
    public void onBeforeSerialize() {}

    /** Called after a deserializer has populated this component's fields and attached it to its owner. */
    public void onDeserialized(Entity owner) {}

    /** Historical type names accepted when loading scenes/prefabs written by older engine builds. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface LegacyNames {
        String[] value();
    }
}
