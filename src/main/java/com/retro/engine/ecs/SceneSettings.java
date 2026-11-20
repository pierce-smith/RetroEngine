package com.retro.engine.ecs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Settings that belong to one scene and are stored in its {@code .rscene} file (format 12 and later), shown in the
 * Inspector when nothing is selected.
 *
 * <p>Stored as a length-prefixed block of fields in order; a reader takes the fields it knows and skips the rest, so
 * fields can be appended without breaking older readers. Block version 1: {@code bool rememberWhenLeft}.</p>
 */
public final class SceneSettings {

    /** Save games: when the player leaves this scene its changes are kept and come back when they return. */
    public boolean rememberWhenLeft = true;

    public SceneSettings copy() {
        SceneSettings s = new SceneSettings();
        s.rememberWhenLeft = rememberWhenLeft;
        return s;
    }

    public void write(DataOutputStream out) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream block = new DataOutputStream(bytes)) {
            block.writeBoolean(rememberWhenLeft);
        }
        out.writeInt(bytes.size());
        bytes.writeTo(out);
    }

    public static SceneSettings read(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > in.available()) throw new IOException("The scene settings block is damaged (length " + length + ").");
        byte[] payload = new byte[length];
        in.readFully(payload);
        SceneSettings s = new SceneSettings();
        DataInputStream block = new DataInputStream(new ByteArrayInputStream(payload));
        if (block.available() >= 1) s.rememberWhenLeft = block.readBoolean();
        return s;
    }
}
