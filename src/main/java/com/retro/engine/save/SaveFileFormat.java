package com.retro.engine.save;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * The {@code .rsave} file: one save slot. Big-endian ({@link DataOutputStream}):
 * <pre>
 *   int    magic 'RSAV'
 *   int    version
 *   text   scenePath, displayName
 *   long   savedAtMillis
 *   double playTimeSeconds
 *   record values
 *   int    levelCount, then per level: text scenePath, level
 *   int    CRC32 of every byte before it
 *
 *   level:     int n, text destroyedId x n
 *              int n, spawn x n        spawn: text prefabPath, text name, text meshPath, int n, text id x n
 *              int n, entity x n
 *   entity:    text id, text name, text parentId, int n, component x n, int n, script x n
 *   component: byte kind (0 changed, 1 added, 2 removed), text type, int index, int n, (text property, value) x n
 *   script:    int index, text scriptName, record fields, record custom
 *   record:    int n, (text key, text value) x n
 *   value:     byte tag, then 0 null | 1 float | 2 int | 3 bool | 4 text | 5 int n, (text, text) x n | 6 int n, bytes
 *   text:      int byteLength (-1 = null), UTF-8 bytes
 * </pre>
 * Any version change must keep reading every older version: players keep their saves across game updates.
 */
final class SaveFileFormat {

    static final int MAGIC = 0x52534156; // 'RSAV'
    static final int VERSION = 1;

    private static final int MAX_COUNT = 1 << 24;

    private SaveFileFormat() {}

    static byte[] write(SaveData data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        text(out, data.scenePath);
        text(out, data.displayName);
        out.writeLong(data.savedAtMillis);
        out.writeDouble(data.playTimeSeconds);
        record(out, data.values);
        out.writeInt(data.levels.size());
        for (Map.Entry<String, SceneState> level : data.levels.entrySet()) {
            text(out, level.getKey());
            level(out, level.getValue());
        }
        out.flush();
        CRC32 crc = new CRC32();
        crc.update(bytes.toByteArray());
        out.writeInt((int) crc.getValue());
        out.flush();
        return bytes.toByteArray();
    }

    /** Reads a save file, or throws with a message fit for the player's log. */
    static SaveData read(byte[] bytes) throws IOException {
        if (bytes.length < 12) throw new IOException("The save file is empty or cut short.");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != MAGIC) throw new IOException("It is not a RetroEngine save file.");
        int version = in.readInt();
        if (version > VERSION) throw new IOException("It was made by a newer version of the game (save format " + version + ", this version reads up to " + VERSION + ").");
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length - 4);
        int stored = ((bytes[bytes.length - 4] & 0xFF) << 24) | ((bytes[bytes.length - 3] & 0xFF) << 16)
                | ((bytes[bytes.length - 2] & 0xFF) << 8) | (bytes[bytes.length - 1] & 0xFF);
        if (stored != (int) crc.getValue()) throw new IOException("The save file is damaged (checksum mismatch).");
        try {
            SaveData data = new SaveData();
            data.scenePath = orEmpty(text(in));
            data.displayName = orEmpty(text(in));
            data.savedAtMillis = in.readLong();
            data.playTimeSeconds = in.readDouble();
            data.values.putAll(recordMap(in));
            int levels = count(in);
            for (int i = 0; i < levels; i++) {
                String path = orEmpty(text(in));
                data.levels.put(path, level(in));
            }
            return data;
        } catch (EOFException e) {
            throw new IOException("The save file is damaged or incomplete.");
        }
    }

    // --- level ---

    private static void level(DataOutputStream out, SceneState s) throws IOException {
        out.writeInt(s.destroyedIds.size());
        for (String id : s.destroyedIds) text(out, id);
        out.writeInt(s.spawns.size());
        for (SceneState.Spawn sp : s.spawns) {
            text(out, sp.prefabPath);
            text(out, sp.name);
            text(out, sp.meshPath);
            out.writeInt(sp.ids.size());
            for (String id : sp.ids) text(out, id);
        }
        out.writeInt(s.entities.size());
        for (SceneState.EntityChange e : s.entities) {
            text(out, e.id);
            text(out, e.name);
            text(out, e.parentId);
            out.writeInt(e.components.size());
            for (SceneState.ComponentChange c : e.components) {
                out.writeByte(c.kind.ordinal());
                text(out, c.type);
                out.writeInt(c.index);
                out.writeInt(c.properties.size());
                for (Map.Entry<String, Object> p : c.properties.entrySet()) {
                    text(out, p.getKey());
                    value(out, p.getValue());
                }
            }
            out.writeInt(e.scripts.size());
            for (SceneState.ScriptState st : e.scripts) {
                out.writeInt(st.index);
                text(out, st.scriptName);
                record(out, st.fields);
                record(out, st.custom);
            }
        }
    }

    private static SceneState level(DataInputStream in) throws IOException {
        SceneState s = new SceneState();
        int n = count(in);
        for (int i = 0; i < n; i++) s.destroyedIds.add(orEmpty(text(in)));
        n = count(in);
        for (int i = 0; i < n; i++) {
            SceneState.Spawn sp = new SceneState.Spawn();
            sp.prefabPath = text(in);
            sp.name = text(in);
            sp.meshPath = text(in);
            int ids = count(in);
            for (int k = 0; k < ids; k++) sp.ids.add(orEmpty(text(in)));
            s.spawns.add(sp);
        }
        n = count(in);
        for (int i = 0; i < n; i++) {
            SceneState.EntityChange e = new SceneState.EntityChange();
            e.id = orEmpty(text(in));
            e.name = text(in);
            e.parentId = text(in);
            int comps = count(in);
            for (int k = 0; k < comps; k++) {
                SceneState.ComponentChange c = new SceneState.ComponentChange();
                int kind = in.readByte();
                SceneState.ComponentChange.Kind[] kinds = SceneState.ComponentChange.Kind.values();
                if (kind < 0 || kind >= kinds.length) throw new IOException("The save file has an unknown component change kind " + kind + ".");
                c.kind = kinds[kind];
                c.type = orEmpty(text(in));
                c.index = in.readInt();
                int props = count(in);
                for (int q = 0; q < props; q++) c.properties.put(orEmpty(text(in)), value(in));
                e.components.add(c);
            }
            int scripts = count(in);
            for (int k = 0; k < scripts; k++) {
                SceneState.ScriptState st = new SceneState.ScriptState();
                st.index = in.readInt();
                st.scriptName = orEmpty(text(in));
                st.fields.putAll(recordMap(in));
                st.custom.putAll(recordMap(in));
                e.scripts.add(st);
            }
            s.entities.add(e);
        }
        return s;
    }

    // --- values ---

    private static void value(DataOutputStream out, Object v) throws IOException {
        if (v == null) {
            out.writeByte(0);
        } else if (v instanceof Float f) {
            out.writeByte(1);
            out.writeFloat(f);
        } else if (v instanceof Integer i) {
            out.writeByte(2);
            out.writeInt(i);
        } else if (v instanceof Boolean b) {
            out.writeByte(3);
            out.writeBoolean(b);
        } else if (v instanceof String s) {
            out.writeByte(4);
            text(out, s);
        } else if (v instanceof Map<?, ?> m) {
            out.writeByte(5);
            out.writeInt(m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                text(out, String.valueOf(e.getKey()));
                text(out, e.getValue() != null ? String.valueOf(e.getValue()) : null);
            }
        } else if (v instanceof byte[] b) {
            out.writeByte(6);
            out.writeInt(b.length);
            out.write(b);
        } else {
            throw new IOException("Unsupported value type " + v.getClass().getName());
        }
    }

    private static Object value(DataInputStream in) throws IOException {
        int tag = in.readByte();
        return switch (tag) {
            case 0 -> null;
            case 1 -> in.readFloat();
            case 2 -> in.readInt();
            case 3 -> in.readBoolean();
            case 4 -> text(in);
            case 5 -> {
                int n = count(in);
                Map<String, String> m = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) m.put(orEmpty(text(in)), text(in));
                yield m;
            }
            case 6 -> {
                int n = count(in);
                byte[] b = new byte[n];
                in.readFully(b);
                yield b;
            }
            default -> throw new IOException("The save file has an unknown value tag " + tag + ".");
        };
    }

    private static void record(DataOutputStream out, SaveRecord r) throws IOException {
        Map<String, String> m = r.asMap();
        out.writeInt(m.size());
        for (Map.Entry<String, String> e : m.entrySet()) {
            text(out, e.getKey());
            text(out, e.getValue());
        }
    }

    private static Map<String, String> recordMap(DataInputStream in) throws IOException {
        int n = count(in);
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) m.put(orEmpty(text(in)), orEmpty(text(in)));
        return m;
    }

    private static void text(DataOutputStream out, String s) throws IOException {
        if (s == null) {
            out.writeInt(-1);
            return;
        }
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String text(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n == -1) return null;
        if (n < 0 || n > in.available()) throw new IOException("The save file is damaged (bad text length).");
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static int count(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_COUNT) throw new IOException("The save file is damaged (bad count " + n + ").");
        return n;
    }

    private static String orEmpty(String s) { return s != null ? s : ""; }
}
