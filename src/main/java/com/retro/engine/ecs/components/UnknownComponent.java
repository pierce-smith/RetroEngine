package com.retro.engine.ecs.components;

import com.retro.engine.ecs.Component;

/**
 * Stands in for a saved component whose type isn't available, usually because its mod isn't installed. It keeps the
 * original bytes and writes them back on save, so opening and saving a scene without a mod doesn't destroy that mod's
 * data. When the mod returns, the next load restores the real component.
 */
public final class UnknownComponent extends Component {

    private final String typeName;
    private final byte[] payload;

    public UnknownComponent(String typeName, byte[] payload) {
        this.typeName = typeName;
        this.payload = payload;
    }

    /** The type name stored in the file. */
    public String typeName() { return typeName; }

    /** The saved bytes. Never modified, so copies can share them. */
    public byte[] payload() { return payload; }

    @Override
    public String getComponentName() {
        return "Missing: " + typeName;
    }
}
