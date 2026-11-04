package com.retro.engine.ecs.components;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;

/**
 * Trigger volume that applies an OpenAL EFX reverb while the listener (or a sound emitter) is inside it. The volume
 * is centred on the entity's world position and scaled by its world scale; boxes are axis-aligned (entity rotation is
 * ignored). Where zones overlap the higher PRIORITY wins, then the smaller zone.
 *
 * Persisted fields are written positionally by ComponentBinaryCodec: append new ones last, gated with since /
 * prefabSince and a format version bump.
 */
public class AudioReverbZone extends Component {

    public enum Shape { BOX, SPHERE }

    public enum ReverbPreset { CAVE, ROOM, HALL, OUTDOOR }

    @InspectorProperty(order = 0, label = "SHAPE", type = PropertyType.BUTTON_GROUP)
    public Shape shape = Shape.BOX;

    @InspectorProperty(order = 1, label = "SIZE X", min = 0.1f, max = 500.0f, step = 0.5f)
    public float sizeX = 10.0f;

    @InspectorProperty(order = 2, label = "SIZE Y", min = 0.1f, max = 500.0f, step = 0.5f)
    public float sizeY = 5.0f;

    @InspectorProperty(order = 3, label = "SIZE Z", min = 0.1f, max = 500.0f, step = 0.5f)
    public float sizeZ = 10.0f;

    @InspectorProperty(order = 4, label = "RADIUS", min = 0.1f, max = 500.0f, step = 0.5f)
    public float radius = 8.0f;

    @InspectorProperty(order = 5, label = "REVERB PRESET")
    public ReverbPreset reverbPreset = ReverbPreset.ROOM;

    /** Seconds; 0 uses the preset's own decay. */
    @InspectorProperty(order = 6, label = "DECAY (0=PRESET)", min = 0.0f, max = 20.0f, step = 0.1f)
    public float decayTime = 0.0f;

    @InspectorProperty(order = 7, label = "WET LEVEL", min = 0.0f, max = 1.0f, step = 0.05f)
    public float wetLevel = 0.6f;

    @InspectorProperty(order = 8, label = "DRY LEVEL", min = 0.0f, max = 1.0f, step = 0.05f)
    public float dryLevel = 1.0f;

    @InspectorProperty(order = 9, label = "PRIORITY", min = 0.0f, max = 10.0f, step = 1.0f)
    public int priority = 0;

    @InspectorProperty(order = 10, label = "ACTIVE", type = PropertyType.TOGGLE)
    public boolean active = true;

    /** Inside test against a zone centred at (cx, cy, cz) with the entity's world scale (sx, sy, sz). */
    public boolean contains(float x, float y, float z, float cx, float cy, float cz, float sx, float sy, float sz) {
        float dx = x - cx, dy = y - cy, dz = z - cz;
        if (shape == Shape.SPHERE) {
            float r = radius * Math.max(Math.abs(sx), Math.max(Math.abs(sy), Math.abs(sz)));
            return dx * dx + dy * dy + dz * dz <= r * r;
        }
        return Math.abs(dx) <= sizeX * Math.abs(sx) * 0.5f
            && Math.abs(dy) <= sizeY * Math.abs(sy) * 0.5f
            && Math.abs(dz) <= sizeZ * Math.abs(sz) * 0.5f;
    }

    /** World-space volume, used to prefer the tighter of two equal-priority zones. */
    public float volume(float sx, float sy, float sz) {
        if (shape == Shape.SPHERE) {
            float r = radius * Math.max(Math.abs(sx), Math.max(Math.abs(sy), Math.abs(sz)));
            return 4.18879f * r * r * r;
        }
        return sizeX * sizeY * sizeZ * Math.abs(sx * sy * sz);
    }

    @Override
    public void onDeserialized(Entity owner) {
        sizeX = Math.max(0.1f, sizeX);
        sizeY = Math.max(0.1f, sizeY);
        sizeZ = Math.max(0.1f, sizeZ);
        radius = Math.max(0.1f, radius);
        decayTime = Float.isNaN(decayTime) ? 0.0f : Math.max(0.0f, Math.min(20.0f, decayTime));
        wetLevel = Float.isNaN(wetLevel) ? 0.0f : Math.max(0.0f, Math.min(1.0f, wetLevel));
        dryLevel = Float.isNaN(dryLevel) ? 1.0f : Math.max(0.0f, Math.min(1.0f, dryLevel));
        if (shape == null) shape = Shape.BOX;
        if (reverbPreset == null) reverbPreset = ReverbPreset.ROOM;
    }

    @Override
    public String getComponentName() {
        return "Audio Reverb Zone";
    }
}
