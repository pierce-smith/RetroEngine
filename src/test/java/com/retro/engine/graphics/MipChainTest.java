package com.retro.engine.graphics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;

class MipChainTest {

    private static byte[] solid(int w, int h, int r, int g, int b, int a) {
        byte[] p = new byte[w * h * 4];
        for (int i = 0; i < p.length; i += 4) {
            p[i] = (byte) r;
            p[i + 1] = (byte) g;
            p[i + 2] = (byte) b;
            p[i + 3] = (byte) a;
        }
        return p;
    }

    private static int[] texel(MipChain mips, int level, int x, int y) {
        int at = mips.offsets[level] + (y * mips.widths[level] + x) * 4;
        return new int[] { mips.pixels[at] & 0xFF, mips.pixels[at + 1] & 0xFF, mips.pixels[at + 2] & 0xFF, mips.pixels[at + 3] & 0xFF };
    }

    @ParameterizedTest
    @CsvSource({ "1,1,1", "2,2,2", "256,256,9", "1024,512,11", "300,200,9", "5,3,3", "1,64,7" })
    void chainReachesOneByOne(int w, int h, int levels) {
        MipChain mips = MipChain.generate(solid(w, h, 1, 2, 3, 255), w, h);
        assertEquals(levels, mips.levels());
        assertEquals(levels, MipChain.levelCountFor(w, h));
        assertEquals(1, mips.widths[levels - 1]);
        assertEquals(1, mips.heights[levels - 1]);
    }

    @ParameterizedTest
    @CsvSource({ "256,256", "300,200", "5,3", "1,64" })
    void levelsArePackedBackToBackWithoutOverlap(int w, int h) {
        MipChain mips = MipChain.generate(solid(w, h, 9, 9, 9, 255), w, h);
        int expected = 0;
        for (int i = 0; i < mips.levels(); i++) {
            assertEquals(expected, mips.offsets[i], "level " + i);
            assertEquals(0, mips.offsets[i] % 4);
            expected += mips.widths[i] * mips.heights[i] * 4;
        }
        assertEquals(expected, mips.pixels.length);
    }

    @Test
    void baseLevelIsTheOriginalImage() {
        byte[] image = solid(4, 4, 10, 20, 30, 40);
        image[5] = 99;
        MipChain mips = MipChain.generate(image, 4, 4);
        assertArrayEquals(image, Arrays.copyOf(mips.pixels, image.length));
    }

    @Test
    void solidColorStaysExactAtEveryLevel() {
        MipChain mips = MipChain.generate(solid(37, 19, 200, 100, 50, 255), 37, 19);
        for (int level = 0; level < mips.levels(); level++) {
            assertArrayEquals(new int[] { 200, 100, 50, 255 }, texel(mips, level, 0, 0), "level " + level);
        }
    }

    /** The exact case mipmaps exist for: a fine checker has to fade to its average, not stay black and white. */
    @Test
    void fineCheckerAveragesToGray() {
        int size = 64;
        byte[] checker = new byte[size * size * 4];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int v = ((x + y) & 1) == 0 ? 255 : 0;
                int at = (y * size + x) * 4;
                checker[at] = checker[at + 1] = checker[at + 2] = (byte) v;
                checker[at + 3] = (byte) 255;
            }
        }
        MipChain mips = MipChain.generate(checker, size, size);
        int[] gray = texel(mips, 1, 5, 5);
        assertEquals(128, gray[0], 1);
        assertEquals(255, gray[3]);
    }

    @Test
    void transparentNeighborsDoNotDarkenACutout() {
        byte[] image = new byte[2 * 2 * 4];
        image[0] = (byte) 255; // Top-left texel: opaque red. The other three are transparent black
        image[3] = (byte) 255;
        int[] out = texel(MipChain.generate(image, 2, 2), 1, 0, 0);
        assertEquals(255, out[0]);
        assertEquals(64, out[3], 1);
    }

    @Test
    void fullyTransparentAreasKeepTheirColor() {
        int[] out = texel(MipChain.generate(solid(2, 2, 90, 80, 70, 0), 2, 2), 1, 0, 0);
        assertArrayEquals(new int[] { 90, 80, 70, 0 }, out);
    }

    @Test
    void oddEdgesDoNotReadOutOfBounds() {
        MipChain mips = MipChain.generate(solid(3, 3, 7, 7, 7, 255), 3, 3);
        assertTrue(mips.levels() == 2);
        assertArrayEquals(new int[] { 7, 7, 7, 255 }, texel(mips, 1, 0, 0));
    }
}
