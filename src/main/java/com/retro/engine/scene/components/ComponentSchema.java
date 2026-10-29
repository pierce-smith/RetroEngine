package com.retro.engine.scene.components;

import com.retro.engine.editor.ui.panels.InspectorProperty;
import com.retro.engine.editor.ui.panels.InspectorProperty.PropertyType;
import com.retro.engine.scene.components.gameplay.CharacterControllerComponent;
import com.retro.engine.scene.components.gameplay.ScriptComponent;
import com.retro.engine.scene.components.gameplay.VehicleControllerComponent;
import com.retro.engine.scene.components.media.AnimatorComponent;
import com.retro.engine.scene.components.media.AudioSource;
import com.retro.engine.scene.components.physics.BoxCollider;
import com.retro.engine.scene.components.physics.CapsuleCollider;
import com.retro.engine.scene.components.physics.RigidBodyComponent;
import com.retro.engine.scene.components.physics.SphereCollider;
import com.retro.engine.scene.components.rendering.Camera;
import com.retro.engine.scene.components.rendering.Light;
import com.retro.engine.scene.components.rendering.Material;
import com.retro.engine.scene.components.rendering.MeshFilter;
import com.retro.engine.scene.components.transform.Transform;
import com.retro.engine.scene.components.ui.UICanvasComponent;
import com.retro.engine.scene.components.ui.UIImage;
import com.retro.engine.scene.components.ui.UIPanel;
import com.retro.engine.scene.components.ui.UIProgressBar;
import com.retro.engine.scene.components.ui.UIText;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cached reflection descriptor for a component class, built from its {@link InspectorProperty} fields.
 * Single source of truth for the inspector UI and the binary scene/prefab serializers.
 */
public final class ComponentSchema {

    /** Every component type that can be reconstructed from a scene or prefab. New components register here once. */
    private static final List<Class<? extends Component>> REGISTERED = List.of(
            Transform.class, MeshFilter.class, Material.class, Light.class, AudioSource.class, Camera.class,
            ScriptComponent.class, RigidBodyComponent.class, BoxCollider.class, SphereCollider.class,
            CapsuleCollider.class, CharacterControllerComponent.class, VehicleControllerComponent.class,
            AnimatorComponent.class, UICanvasComponent.class, UIPanel.class, UIText.class, UIImage.class,
            UIProgressBar.class
    );

    /** How the inspector presents and edits a property. */
    public enum Control { ASSET, TOGGLE, CHOICE, NUMBER, STRING, READ_ONLY }

    /** Binary encoding of a persisted property. */
    public enum ValueKind { FLOAT, INT, BOOLEAN, ENUM, ASSET_PATH, STRING, STRING_MAP, UNSUPPORTED }

    public static final class Property {
        public final Field field;
        public final InspectorProperty meta;
        public final String label;
        public final Control control;
        public final ValueKind kind;

        private Property(Field field, InspectorProperty meta) {
            this.field = field;
            this.meta = meta;
            this.label = meta.label().isEmpty() ? field.getName().toUpperCase(Locale.ROOT) : meta.label();
            this.control = controlOf(field.getType(), meta);
            this.kind = kindOf(field.getType(), meta);
        }

        public String name() { return field.getName(); }

        public boolean isColor() { return meta.type() == PropertyType.COLOR; }

        /** True when this property is part of the binary layout for the given format profile and version. */
        public boolean isPersistedIn(boolean prefab, int version) {
            return meta.serialized() && version >= meta.since() && (!prefab || version >= meta.prefabSince());
        }

        public Object get(Component c) throws IllegalAccessException { return field.get(c); }

        public void set(Component c, Object value) throws IllegalAccessException { field.set(c, value); }
    }

    private static final ClassValue<ComponentSchema> CACHE = new ClassValue<>() {
        @Override
        protected ComponentSchema computeValue(Class<?> type) {
            return new ComponentSchema(type);
        }
    };

    private static final Map<String, Class<? extends Component>> BY_SERIALIZED_NAME = new HashMap<>();
    static {
        for (Class<? extends Component> type : REGISTERED) {
            String name = type.getSimpleName();
            register(name, type);
            register(name.endsWith("Component") ? name.substring(0, name.length() - "Component".length()) : name + "Component", type);
            Component.LegacyNames legacy = type.getAnnotation(Component.LegacyNames.class);
            if (legacy != null) {
                for (String alias : legacy.value()) register(alias, type);
            }
        }
    }

    private final Class<?> type;
    private final List<Property> inspectorProperties;
    private final List<Property> serializedProperties;
    private final Constructor<?> constructor;

    private ComponentSchema(Class<?> type) {
        this.type = type;
        List<Property> inspector = new ArrayList<>();
        List<Property> serialized = new ArrayList<>();
        Map<Integer, Field> byOrder = new HashMap<>();
        for (Field field : declaredFieldsInOrder(type)) {
            InspectorProperty meta = field.getAnnotation(InspectorProperty.class);
            int mod = field.getModifiers();
            if (meta == null || Modifier.isStatic(mod) || Modifier.isTransient(mod)) continue;
            Field clash = byOrder.putIfAbsent(meta.order(), field);
            if (clash != null) {
                throw new IllegalStateException("Duplicate @InspectorProperty order " + meta.order() + " on "
                        + type.getName() + "." + clash.getName() + " and " + field.getName());
            }

            field.setAccessible(true);
            Property p = new Property(field, meta);
            if (!meta.hidden()) inspector.add(p);
            if (meta.serialized()) {
                if (p.kind == ValueKind.UNSUPPORTED) {
                    throw new IllegalStateException("Unsupported serialized field type " + field.getType().getName()
                            + " for " + type.getName() + "." + field.getName() + " (set serialized = false or add codec support)");
                }
                serialized.add(p);
            }
        }
        this.inspectorProperties = Collections.unmodifiableList(inspector);
        this.serializedProperties = Collections.unmodifiableList(serialized);

        Constructor<?> ctor = null;
        try {
            ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
        } catch (NoSuchMethodException ignored) {}
        this.constructor = ctor;
    }

    public static ComponentSchema of(Class<?> type) {
        return CACHE.get(type);
    }

    /** Resolves a type name stored in a scene/prefab (including legacy aliases, case-insensitive), or null if unknown. */
    public static ComponentSchema forSerializedName(String typeName) {
        if (typeName == null) return null;
        Class<? extends Component> type = BY_SERIALIZED_NAME.get(typeName.toLowerCase(Locale.ROOT));
        return (type != null) ? of(type) : null;
    }

    public Class<?> type() { return type; }

    /** Inspector-visible properties in order() sequence. */
    public List<Property> inspectorProperties() { return inspectorProperties; }

    /** All persisted properties in order() (= binary layout) sequence. */
    public List<Property> serializedProperties() { return serializedProperties; }

    /** Components without persisted properties are runtime-only and are rebuilt by systems rather than loaded. */
    public boolean isPersistent() { return !serializedProperties.isEmpty() && constructor != null; }

    public Component newInstance() {
        try {
            return (Component) constructor.newInstance();
        } catch (ReflectiveOperationException | NullPointerException e) {
            throw new IllegalStateException("Component " + type.getName() + " needs a no-arg constructor to be deserialized", e);
        }
    }

    private static void register(String name, Class<? extends Component> type) {
        BY_SERIALIZED_NAME.putIfAbsent(name.toLowerCase(Locale.ROOT), type);
    }

    // Sorted by @InspectorProperty.order (the binary layout order)
    private static List<Field> declaredFieldsInOrder(Class<?> type) {
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) chain.add(0, c);
        List<Field> fields = new ArrayList<>();
        for (Class<?> c : chain) Collections.addAll(fields, c.getDeclaredFields());
        fields.sort(Comparator.comparingInt(f -> {
            InspectorProperty meta = f.getAnnotation(InspectorProperty.class);
            return meta != null ? meta.order() : 0;
        }));
        return fields;
    }

    private static Control controlOf(Class<?> t, InspectorProperty meta) {
        if (meta.type() == PropertyType.ASSET_PICKER) return Control.ASSET;
        if (meta.type() == PropertyType.TOGGLE || t == boolean.class) return Control.TOGGLE;
        if (meta.type() == PropertyType.BUTTON_GROUP || t.isEnum()) return Control.CHOICE;
        if (meta.type() == PropertyType.COLOR || t == float.class || t == Float.class) return Control.NUMBER;
        if (t == String.class) return Control.STRING;
        return Control.READ_ONLY;
    }

    private static ValueKind kindOf(Class<?> t, InspectorProperty meta) {
        if (t == float.class) return ValueKind.FLOAT;
        if (t == int.class) return ValueKind.INT;
        if (t == boolean.class) return ValueKind.BOOLEAN;
        if (t.isEnum()) return ValueKind.ENUM;
        if (t == String.class) return meta.type() == PropertyType.ASSET_PICKER ? ValueKind.ASSET_PATH : ValueKind.STRING;
        if (Map.class.isAssignableFrom(t)) return ValueKind.STRING_MAP;
        return ValueKind.UNSUPPORTED;
    }
}
