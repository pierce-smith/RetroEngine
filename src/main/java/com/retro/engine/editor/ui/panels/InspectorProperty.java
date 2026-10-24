package com.retro.engine.editor.ui.panels;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for automated ECS component property reflection in InspectorPanel.
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
}
