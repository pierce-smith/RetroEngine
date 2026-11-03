package com.retro.engine.graphics.vulkan;

/**
 * Raw Vulkan handles and bindless slots for one frame's draws, in RenderQueue key order, resolved by
 * {@link VulkanGpuCache#prepareScene} before recording so the pass encoder never touches a handle table.
 * Draw d reads its transform and material scalars from {@code commands[commandIndex[d]]}; commands whose mesh was
 * not live are dropped. Arrays only grow.
 */
final class VulkanDrawBuffers {

    static final int CHANNELS = VulkanGpuCache.CHANNEL_COUNT;

    int count;
    int[] commandIndex;
    long[] vertexBuffer;     // VkBuffer
    int[] vertexCount;
    long[] indexBuffer;      // 0L = non-indexed draw
    int[] indexCount;
    int[] indexType;         // VkIndexType
    boolean[] skinnedLayout; // 30-float vertices: must use the skinned pipelines
    int[] textureSlots;      // bindless slot per channel at [d * CHANNELS + c]; NO_TEXTURE = absent
    int[] paletteSlot;       // bone palette slot (0 = identity)

    VulkanDrawBuffers() {
        allocate(256);
    }

    void reset(int capacity) {
        count = 0;
        if (commandIndex.length < capacity) allocate(Math.max(capacity, commandIndex.length * 2));
    }

    private void allocate(int cap) {
        commandIndex = new int[cap];
        vertexBuffer = new long[cap];
        vertexCount = new int[cap];
        indexBuffer = new long[cap];
        indexCount = new int[cap];
        indexType = new int[cap];
        skinnedLayout = new boolean[cap];
        textureSlots = new int[cap * CHANNELS];
        paletteSlot = new int[cap];
    }
}
