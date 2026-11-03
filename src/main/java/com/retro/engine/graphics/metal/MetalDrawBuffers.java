package com.retro.engine.graphics.metal;

/**
 * Raw Metal objects for one frame's draws, in RenderQueue key order and resolved once at submission so the pass encoder
 * never touches a handle table. Draw d reads its transform and material scalars from
 * {@code commands[commandIndex[d]]}; commands whose mesh was not live are dropped. Arrays only grow.
 */
final class MetalDrawBuffers {

    static final int CHANNELS = MetalUniformStream.MATERIAL_CHANNELS;

    int count;
    int[] commandIndex;
    long[] vertexBuffer;     // id<MTLBuffer>, made resident
    long[] vertexAddress;    // gpuAddress written into DrawArgs.vertices
    int[] vertexCount;
    long[] indexBuffer;      // 0L = non-indexed draw
    int[] indexCount;
    long[] indexType;        // MTLIndexType (0 = UInt16, 1 = UInt32)
    int[] floatStride;
    // Per channel (albedo, normal, specular, lightmap, emissive) at [d * CHANNELS + c]; 0L = absent
    long[] textures;         // id<MTLTexture>, made resident
    long[] resourceIds;      // gpuResourceID written into DrawArgs.textures
    int[] materialMask;      // MetalUniformStream.MATERIAL_* bits for the present channels
    int[] skinnedSlot;       // bone palette slot assigned during encoding, -1 = not skinned

    MetalDrawBuffers() {
        allocate(256);
    }

    void reset(int capacity) {
        count = 0;
        if (commandIndex.length < capacity) allocate(Math.max(capacity, commandIndex.length * 2));
    }

    private void allocate(int cap) {
        commandIndex = new int[cap];
        vertexBuffer = new long[cap];
        vertexAddress = new long[cap];
        vertexCount = new int[cap];
        indexBuffer = new long[cap];
        indexCount = new int[cap];
        indexType = new long[cap];
        floatStride = new int[cap];
        textures = new long[cap * CHANNELS];
        resourceIds = new long[cap * CHANNELS];
        materialMask = new int[cap];
        skinnedSlot = new int[cap];
    }
}
