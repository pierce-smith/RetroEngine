package com.retro.engine.editor.commands;

import com.retro.engine.scene.components.Component;
import java.lang.reflect.Field;

/**
 * Universal reflection-driven ICommand for undo/redo property mutations across all ECS components.
 */
public class ModifyPropertyCommand implements ICommand {
    private final Component component;
    private final Field field;
    private final Object oldValue;
    private final Object newValue;
    private final String propertyLabel;

    public ModifyPropertyCommand(Component component, Field field, Object oldValue, Object newValue, String propertyLabel) {
        this.component = component;
        this.field = field;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.propertyLabel = propertyLabel;
        if (this.field != null) {
            this.field.setAccessible(true);
        }
    }

    @Override
    public void execute() {
        setValue(newValue);
    }

    @Override
    public void undo() {
        setValue(oldValue);
    }

    private void setValue(Object val) {
        if (component == null || field == null) return;
        try {
            field.set(component, val);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getName() {
        String compName = (component != null) ? component.getComponentName() : "Component";
        return "Modify " + compName + " [" + propertyLabel + "]";
    }
}
