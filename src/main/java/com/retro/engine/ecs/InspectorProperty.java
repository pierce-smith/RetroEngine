package com.retro.engine.ecs;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Component field metadata: drives InspectorPanel rendering/editing and binary .rscene/.rprefab serialization.
 * Serialized fields are written in {@link #order()} sequence, so never reorder, insert or remove annotated fields
 * without bumping the format version and gating the change with {@link #since()} / {@link #prefabSince()}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface InspectorProperty {
    enum PropertyType { AUTO, SLIDER, TOGGLE, COLOR, TEXT, ASSET_PICKER, BUTTON_GROUP }

    String label() default "";
    float min() default 0.0f;
    float max() default 100.0f;
    float step() default 0.1f;
    PropertyType type() default PropertyType.AUTO;
    String assetType() default "";

    /** Persisted in scenes/prefabs but not shown in the inspector. */
    boolean hidden() default false;
    /** Shown in the inspector but never persisted. */
    boolean serialized() default true;
    /** Scene and prefab format version that introduced this field. */
    int since() default 1;
    /** Prefab format version that introduced this field (prefabs historically stored a reduced layout). */
    int prefabSince() default 1;
    /** Binary layout and inspector position; must be unique per component class. */
    int order() default 0;
}
