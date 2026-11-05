package com.retro.engine.ecs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.ecs.ComponentSchema.Control;
import com.retro.engine.ecs.ComponentSchema.Property;
import com.retro.engine.ecs.ComponentSchema.ValueKind;
import com.retro.engine.ecs.components.Light;
import com.retro.engine.ecs.components.MeshFilter;
import com.retro.engine.ecs.components.RigidBodyComponent;
import com.retro.engine.ecs.components.Transform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ComponentSchemaTest {

    private static final Path LAYOUT_FILE = Path.of("src/test/resources/component-binary-layout.txt");

    /** Built-in component types, read from the engine so a new component is covered automatically. */
    @SuppressWarnings("unchecked")
    public static List<Class<? extends Component>> builtInComponents() throws ReflectiveOperationException {
        Field registered = ComponentSchema.class.getDeclaredField("REGISTERED");
        registered.setAccessible(true);
        return (List<Class<? extends Component>>) registered.get(null);
    }

    @ParameterizedTest
    @MethodSource("builtInComponents")
    void builtInComponentHasAValidSchema(Class<? extends Component> type) {
        ComponentSchema schema = ComponentSchema.of(type);
        assertInstanceOf(type, schema.newInstance());
        assertSame(schema, ComponentSchema.forSerializedName(type.getSimpleName()));
        for (Property p : schema.serializedProperties()) {
            assertTrue(p.kind != ValueKind.UNSUPPORTED, p.name());
        }
    }

    /**
     * Scenes are read back in field order, so changing a persisted field breaks every saved scene. If this fails on
     * purpose (a new field, gated with since / prefabSince and a format version bump), copy the printed actual file
     * over the expected one. If it fails by accident, undo the reorder.
     */
    @Test
    void binaryLayoutOnlyChangesOnPurpose() throws Exception {
        List<String> actual = new ArrayList<>();
        for (Class<? extends Component> type : builtInComponents()) {
            for (Property p : ComponentSchema.of(type).serializedProperties()) {
                actual.add(type.getSimpleName() + " " + p.meta.order() + " " + p.name() + " " + p.kind
                        + " since=" + p.meta.since() + " prefabSince=" + p.meta.prefabSince());
            }
        }
        Path actualFile = Path.of("build/component-binary-layout.actual.txt");
        Files.createDirectories(actualFile.getParent());
        Files.write(actualFile, actual);
        assertEquals(expectedLayout(), actual, "Binary layout changed. Actual layout written to " + actualFile);
    }

    private static List<String> expectedLayout() throws IOException {
        return Files.readAllLines(LAYOUT_FILE);
    }

    @Test
    void namesResolveByAliasAndIgnoringCase() {
        assertSame(RigidBodyComponent.class, ComponentSchema.forSerializedName("RigidBody").type());
        assertSame(Light.class, ComponentSchema.forSerializedName("lightcomponent").type());
        assertSame(Transform.class, ComponentSchema.forSerializedName("TRANSFORM").type());
    }

    @Test
    void legacyNamesKeepOldScenesLoading() {
        assertSame(MeshFilter.class, ComponentSchema.forSerializedName("MeshRendererComponent").type());
    }

    @Test
    void unknownNamesResolveToNull() {
        assertNull(ComponentSchema.forSerializedName("NoSuchComponent"));
        assertNull(ComponentSchema.forSerializedName(null));
    }

    public enum Mode { ONE, TWO }

    public static class Sample extends Component {
        @InspectorProperty(order = 0, label = "RATE") public float rate = 1.0f;
        @InspectorProperty(order = 1) public int count = 2;
        @InspectorProperty(order = 2) public boolean on = true;
        @InspectorProperty(order = 3) public Mode mode = Mode.ONE;
        @InspectorProperty(order = 4) public String text = "x";
        @InspectorProperty(order = 5, type = InspectorProperty.PropertyType.ASSET_PICKER) public String asset = "";
        @InspectorProperty(order = 6, hidden = true) public byte[] blob = new byte[0];
        @InspectorProperty(order = 7, serialized = false) public int derived = 0;
        public int plainField = 9;
        @Override public String getComponentName() { return "Sample"; }
    }

    private static Property property(String name) {
        for (Property p : ComponentSchema.of(Sample.class).inspectorProperties()) {
            if (p.name().equals(name)) return p;
        }
        throw new AssertionError("no inspector property " + name);
    }

    @Test
    void fieldTypesPickTheirControlAndEncoding() {
        assertEquals(Control.NUMBER, property("rate").control);
        assertEquals(ValueKind.FLOAT, property("rate").kind);
        assertEquals(Control.NUMBER, property("count").control); // int fields are editable
        assertEquals(ValueKind.INT, property("count").kind);
        assertEquals(Control.TOGGLE, property("on").control);
        assertEquals(Control.CHOICE, property("mode").control);
        assertEquals(ValueKind.ENUM, property("mode").kind);
        assertEquals(ValueKind.STRING, property("text").kind);
        assertEquals(Control.ASSET, property("asset").control);
        assertEquals(ValueKind.ASSET_PATH, property("asset").kind);
    }

    @Test
    void labelsDefaultToTheUpperCaseFieldName() {
        assertEquals("RATE", property("rate").label);
        assertEquals("COUNT", property("count").label);
    }

    @Test
    void hiddenAndUnserializedFieldsAreSplitCorrectly() {
        ComponentSchema schema = ComponentSchema.of(Sample.class);
        List<String> inspector = schema.inspectorProperties().stream().map(Property::name).toList();
        List<String> saved = schema.serializedProperties().stream().map(Property::name).toList();
        assertFalse(inspector.contains("blob"));
        assertTrue(saved.contains("blob"));
        assertTrue(inspector.contains("derived"));
        assertFalse(saved.contains("derived"));
        assertFalse(saved.contains("plainField"));
    }

    @Test
    void serializedPropertiesFollowOrderSequence() {
        List<Integer> orders = ComponentSchema.of(Sample.class).serializedProperties().stream().map(p -> p.meta.order()).toList();
        assertEquals(orders.stream().sorted().toList(), orders);
    }

    @Test
    void propertiesAreGatedByFormatVersion() {
        class Gated extends Component {
            @InspectorProperty(order = 0, since = 5, prefabSince = 7) public float value;
            @Override public String getComponentName() { return "Gated"; }
        }
        Property p = ComponentSchema.of(Gated.class).serializedProperties().get(0);
        assertFalse(p.isPersistedIn(false, 4));
        assertTrue(p.isPersistedIn(false, 5));
        assertFalse(p.isPersistedIn(true, 6));
        assertTrue(p.isPersistedIn(true, 7));
    }

    @Test
    void newPrefabsFollowPrefabSinceAloneButOldOnesAreReadAsWritten() {
        class Newer extends Component {
            @InspectorProperty(order = 0, since = 12, prefabSince = 7) public float value;
            @Override public String getComponentName() { return "Newer"; }
        }
        Property p = ComponentSchema.of(Newer.class).serializedProperties().get(0);
        assertFalse(p.isPersistedIn(true, 7), "version 7 prefabs left these fields out");
        assertTrue(p.isPersistedIn(true, 8));
    }

    public static class DuplicateOrder extends Component {
        @InspectorProperty(order = 0) public float a;
        @InspectorProperty(order = 0) public float b;
        @Override public String getComponentName() { return "DuplicateOrder"; }
    }

    public static class UnsupportedField extends Component {
        @InspectorProperty(order = 0) public double[] values;
        @Override public String getComponentName() { return "UnsupportedField"; }
    }

    @Test
    void invalidSchemasAreRejected() {
        assertThrows(IllegalStateException.class, () -> ComponentSchema.of(DuplicateOrder.class));
        assertThrows(IllegalStateException.class, () -> ComponentSchema.of(UnsupportedField.class));
    }

    public static class ModProbeComponent extends Component {
        @InspectorProperty(order = 0) public float value = 1.0f;
        @Override public String getComponentName() { return "ModProbe"; }
    }

    /** Same simple name as a built-in. */
    public static class Light2 { public static class Light extends Component {
        @Override public String getComponentName() { return "Clash"; }
    } }

    @Test
    void modComponentsRegisterUnderBothNamesAndUnregister() {
        try {
            ComponentSchema.registerModComponent(ModProbeComponent.class, "schema_test");
            assertNotNull(ComponentSchema.forSerializedName("ModProbeComponent"));
            assertNotNull(ComponentSchema.forSerializedName("ModProbe"));
            assertEquals("schema_test", ComponentSchema.modOwnerOf(ModProbeComponent.class));
        } finally {
            ComponentSchema.unregisterModComponents("schema_test");
        }
        assertNull(ComponentSchema.forSerializedName("ModProbeComponent"));
        assertNull(ComponentSchema.modOwnerOf(ModProbeComponent.class));
    }

    @Test
    void modComponentsCannotTakeABuiltInName() {
        assertThrows(IllegalArgumentException.class, () -> ComponentSchema.registerModComponent(Light2.Light.class, "schema_test"));
        assertSame(Light.class, ComponentSchema.forSerializedName("Light").type());
    }
}
