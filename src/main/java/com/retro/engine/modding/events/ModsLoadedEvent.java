package com.retro.engine.modding.events;

import com.retro.engine.api.PublicApi;
import com.retro.engine.modding.ModManifest;

import java.util.List;

/** Posted once after every mod's {@code onInitialize} ran, with the final load order (lowest priority first). */
@PublicApi
public final class ModsLoadedEvent extends ModEvent {

    public final List<ModManifest> loadOrder;

    public ModsLoadedEvent(List<ModManifest> loadOrder) {
        this.loadOrder = loadOrder;
    }
}
