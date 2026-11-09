package com.retro.engine.graphics;

import com.retro.engine.api.Internal;
import com.retro.engine.api.PublicApi;
import com.retro.engine.assets.PixelBufferData;

import java.util.Random;

/**
 * Built-in decal images, generated once on first use, so blood and bullet holes work in a project with no art for them.
 * Pass these constants anywhere a decal takes a texture path. Each image keeps one color across its transparent areas,
 * so mipmaps don't pull a dark or light halo in from the background.
 */
@PublicApi
public final class DecalTextures {

    private static final String PREFIX = "builtin:decal/";
    public static final String BLOOD = PREFIX + "blood";
    public static final String BULLET_HOLE = PREFIX + "bullet_hole";
    public static final String SCORCH = PREFIX + "scorch";

    private static final int SIZE = 128;

    private DecalTextures() {}

    @Internal
    public static boolean isBuiltin(String path) {
        return path != null && path.startsWith(PREFIX);
    }

    /** The pixels behind a built-in path, or null for any other path. */
    @Internal
    public static PixelBufferData generate(String path) {
        if (BLOOD.equals(path)) return blood();
        if (BULLET_HOLE.equals(path)) return bulletHole();
        if (SCORCH.equals(path)) return scorch();
        return null;
    }

    /** A central pool with a ragged edge, thrown droplets and a darker core. */
    private static PixelBufferData blood() {
        Random random = new Random(7L);
        float[] field = new float[SIZE * SIZE];
        addBlob(field, 0.5f, 0.5f, 0.24f);
        for (int i = 0; i < 9; i++) {
            float angle = random.nextFloat() * 6.2832f, reach = 0.12f + random.nextFloat() * 0.1f;
            addBlob(field, 0.5f + (float) Math.cos(angle) * reach, 0.5f + (float) Math.sin(angle) * reach, 0.05f + random.nextFloat() * 0.07f);
        }
        for (int i = 0; i < 26; i++) {
            float angle = random.nextFloat() * 6.2832f, reach = 0.26f + random.nextFloat() * 0.19f;
            addBlob(field, 0.5f + (float) Math.cos(angle) * reach, 0.5f + (float) Math.sin(angle) * reach, 0.008f + random.nextFloat() * 0.022f);
        }
        byte[] pixels = new byte[SIZE * SIZE * 4];
        for (int i = 0; i < field.length; i++) {
            float coverage = smoothstep(0.85f, 1.05f, field[i]);
            float core = smoothstep(1.0f, 3.0f, field[i]);
            put(pixels, i, 0.42f - 0.16f * core, 0.025f, 0.03f, coverage);
        }
        return image("decal_blood", pixels);
    }

    /** A black hole inside a ring of chipped, pale material, with soot fading out around it. */
    private static PixelBufferData bulletHole() {
        Random random = new Random(21L);
        float[] edge = new float[32];
        for (int i = 0; i < edge.length; i++) edge[i] = 0.85f + random.nextFloat() * 0.3f;

        byte[] pixels = new byte[SIZE * SIZE * 4];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float dx = (x + 0.5f) / SIZE - 0.5f, dy = (y + 0.5f) / SIZE - 0.5f;
                float radius = (float) Math.sqrt(dx * dx + dy * dy);
                float around = (float) ((Math.atan2(dy, dx) / 6.2832 + 0.5) * edge.length);
                int slot = (int) around % edge.length;
                float t = around - (float) Math.floor(around);
                float jag = edge[slot] + (edge[(slot + 1) % edge.length] - edge[slot]) * t;

                float hole = 1.0f - smoothstep(0.11f, 0.135f, radius / jag);
                float rim = (1.0f - smoothstep(0.17f, 0.23f, radius / jag)) * (1.0f - hole);
                float soot = 1.0f - smoothstep(0.15f, 0.48f, radius);

                float shade = 0.02f + 0.38f * rim;
                put(pixels, y * SIZE + x, shade, shade * 0.97f, shade * 0.92f, Math.max(Math.max(hole, rim * 0.9f), soot * soot * 0.75f));
            }
        }
        return image("decal_bullet_hole", pixels);
    }

    /** A black burn that breaks up toward its edge. */
    private static PixelBufferData scorch() {
        Random random = new Random(33L);
        float[] field = new float[SIZE * SIZE];
        addBlob(field, 0.5f, 0.5f, 0.3f);
        for (int i = 0; i < 40; i++) {
            float angle = random.nextFloat() * 6.2832f, reach = 0.18f + random.nextFloat() * 0.24f;
            addBlob(field, 0.5f + (float) Math.cos(angle) * reach, 0.5f + (float) Math.sin(angle) * reach, 0.02f + random.nextFloat() * 0.06f);
        }
        byte[] pixels = new byte[SIZE * SIZE * 4];
        for (int i = 0; i < field.length; i++) {
            put(pixels, i, 0.035f, 0.03f, 0.028f, smoothstep(0.8f, 1.9f, field[i]) * 0.95f);
        }
        return image("decal_scorch", pixels);
    }

    /** Adds one metaball: 1.0 at {@code radius} from its center, rising inward and falling off outward. */
    private static void addBlob(float[] field, float cx, float cy, float radius) {
        float rSquared = radius * radius;
        for (int y = 0; y < SIZE; y++) {
            float dy = (y + 0.5f) / SIZE - cy;
            for (int x = 0; x < SIZE; x++) {
                float dx = (x + 0.5f) / SIZE - cx;
                float d = dx * dx + dy * dy;
                // Fade to nothing by the image edge, so the texture's repeat wrap never shows a seam
                float border = Math.min(Math.min(x, SIZE - 1 - x), Math.min(y, SIZE - 1 - y)) / 6.0f;
                field[y * SIZE + x] += rSquared / Math.max(d, 1e-5f) * Math.min(border, 1.0f) * 0.5f;
            }
        }
    }

    private static void put(byte[] pixels, int pixel, float r, float g, float b, float a) {
        int at = pixel * 4;
        pixels[at] = toByte(r);
        pixels[at + 1] = toByte(g);
        pixels[at + 2] = toByte(b);
        pixels[at + 3] = toByte(a);
    }

    private static byte toByte(float v) {
        return (byte) Math.round(Math.max(0.0f, Math.min(1.0f, v)) * 255.0f);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0.0f, Math.min(1.0f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }

    private static PixelBufferData image(String name, byte[] pixels) {
        return new PixelBufferData(name, "", SIZE, SIZE, 4, pixels);
    }
}
