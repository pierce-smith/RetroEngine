package com.retro.engine.scene.components;

import com.retro.engine.scene.entity.Entity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Universal interface for modular entity components in Retro Engine ECS.
 * Persisted and inspected state is declared with {@code @InspectorProperty} fields; see {@link ComponentSchema}.
 */
public interface Component {
    String getComponentName();

    /** Called after a deserializer has populated this component's fields and attached it to its owner. */
    default void onDeserialized(Entity owner) {}

    /** Historical type names accepted when loading scenes/prefabs written by older engine builds. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface LegacyNames {
        String[] value();
    }
}
