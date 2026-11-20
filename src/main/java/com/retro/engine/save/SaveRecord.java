package com.retro.engine.save;

import com.retro.engine.api.PublicApi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Named values in a save game, stored as text and read back with the typed getters. A script fills one in
 * {@link com.retro.engine.scripting.EntityScript#onSave(SaveRecord)} and reads it in
 * {@link com.retro.engine.scripting.EntityScript#onLoad(SaveRecord)}; {@link SaveGame#values()} is the one shared by the
 * whole save slot (quest flags, keys found, chapter reached).
 */
@PublicApi
public final class SaveRecord {

    private final Map<String, String> values = new LinkedHashMap<>();

    public SaveRecord() {}

    /** Stores {@code value} as text ({@code String.valueOf}); null removes the key. */
    public SaveRecord set(String key, Object value) {
        if (key == null || key.isEmpty()) return this;
        if (value == null) values.remove(key);
        else values.put(key, String.valueOf(value));
        return this;
    }

    public void remove(String key) { values.remove(key); }

    public void clear() { values.clear(); }

    public boolean has(String key) { return key != null && values.containsKey(key); }

    public boolean isEmpty() { return values.isEmpty(); }

    /** The stored keys, in the order they were first set. */
    public Set<String> keys() { return Collections.unmodifiableSet(values.keySet()); }

    public String getString(String key, String defaultValue) {
        String v = key != null ? values.get(key) : null;
        return v != null ? v : defaultValue;
    }

    /** "true" / "false" (any case), or "1" / "0". Anything else returns {@code defaultValue}. */
    public boolean getBool(String key, boolean defaultValue) {
        String v = getString(key, null);
        if (v == null) return defaultValue;
        if (v.equalsIgnoreCase("true") || v.equals("1")) return true;
        if (v.equalsIgnoreCase("false") || v.equals("0")) return false;
        return defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        String v = getString(key, null);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            try {
                return (int) Double.parseDouble(v.trim());
            } catch (NumberFormatException e2) {
                return defaultValue;
            }
        }
    }

    public float getFloat(String key, float defaultValue) {
        String v = getString(key, null);
        if (v == null) return defaultValue;
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Copy of every key and value (for writing to disk). */
    Map<String, String> asMap() { return new LinkedHashMap<>(values); }

    void putAll(Map<String, String> source) { values.putAll(source); }

    void copyFrom(SaveRecord other) {
        values.clear();
        if (other != null) values.putAll(other.values);
    }

    @Override
    public String toString() { return values.toString(); }
}
