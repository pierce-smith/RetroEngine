package com.retro.engine.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.graphics.metal.MetalUniformStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The arithmetic behind Metal's growing draw buffers. Whether draws reach the screen needs a real GPU: see the render check scene. */
class MetalDrawCapacityTest {

    @ParameterizedTest
    @CsvSource({ "0,1024", "1,1024", "256,1024", "1024,1024", "1025,2048", "2048,2048", "2049,4096", "100000,131072" })
    void capacityFitsEveryDrawCount(int draws, int expected) {
        assertEquals(expected, MetalUniformStream.capacityFor(draws));
    }

    @Test
    void freshStreamNeedsNoGrowthForNormalScenes() {
        MetalUniformStream stream = new MetalUniformStream();
        assertFalse(stream.needsGrowth(MetalUniformStream.INITIAL_DRAW_CAPACITY));
        assertTrue(stream.needsGrowth(MetalUniformStream.INITIAL_DRAW_CAPACITY + 1));
    }

    @Test
    void perDrawRegionsNeverOverlap() {
        MetalUniformStream stream = new MetalUniformStream();
        int capacity = MetalUniformStream.INITIAL_DRAW_CAPACITY;
        int[] bases = { stream.slotBaseShadowBlobs(), stream.slotBaseEntities(), stream.slotBaseShadowCasters(),
                stream.slotBasePreviewShadowBlobs(), stream.slotBasePreviewEntities(), stream.slotBasePickEntities(), stream.slotBaseParticles() };
        assertEquals(1, bases[0], "slot 0 is the grid");
        for (int i = 1; i < bases.length; i++) assertEquals(capacity, bases[i] - bases[i - 1], "region " + i);
        assertTrue(stream.slotBaseParticles() + ParticleDrawList.MAX_BATCHES <= MetalUniformStream.slotsPerFrame(capacity));
    }
}
