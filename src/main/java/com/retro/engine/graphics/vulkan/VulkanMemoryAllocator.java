package com.retro.engine.graphics.vulkan;

import com.retro.engine.core.Log;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * GPU memory for every Vulkan buffer and image. Drivers cap live {@code VkDeviceMemory} objects (about 4,096 on many
 * Windows drivers) and each allocation is slow, so resources are placed inside large blocks instead of getting one
 * allocation each. In-house, like physics: no VMA.
 *
 * <ul>
 *   <li>Blocks are per memory type, and buffers and optimal-tiling images never share a block, so
 *       {@code bufferImageGranularity} can never put the two on one page.</li>
 *   <li>A block is {@link #MAX_BLOCK_SIZE} or an eighth of its heap, whichever is smaller. A request over half a block
 *       gets its own allocation ("dedicated"), as do render targets that are freed and rebuilt on resize anyway.</li>
 *   <li>Host-visible blocks are mapped once and stay mapped; {@link #mappedAddress} gives a resource's CPU pointer.
 *       Never call {@code vkMapMemory} on allocator memory: a {@code VkDeviceMemory} can only be mapped once.</li>
 *   <li>An emptied block is released, except the last one of its kind, which is kept to avoid churn.</li>
 * </ul>
 *
 * <p>Handles are opaque non-zero longs (0 means none), stored where the raw {@code VkDeviceMemory} used to be.
 * Thread-safe: streamer threads register meshes and textures while the render thread draws.</p>
 */
public final class VulkanMemoryAllocator {

    static final long MAX_BLOCK_SIZE = 64L << 20;
    static final long MIN_BLOCK_SIZE = 4L << 20;

    /** What a resource is, which decides the blocks it may share. */
    public enum Kind { BUFFER, IMAGE, DEDICATED }

    private static final class Block {
        final long memory;
        final long mapped;
        final int poolKey;
        final BlockSuballocator ranges;

        Block(long memory, long mapped, int poolKey, long size) {
            this.memory = memory;
            this.mapped = mapped;
            this.poolKey = poolKey;
            this.ranges = new BlockSuballocator(size);
        }
    }

    private static final class Allocation {
        final Block block;    // null for a dedicated allocation
        final long memory;
        final long offset;
        final long size;
        final long mapped;    // CPU address of offset 0 of this allocation, or 0

        Allocation(Block block, long memory, long offset, long size, long mapped) {
            this.block = block;
            this.memory = memory;
            this.offset = offset;
            this.size = size;
            this.mapped = mapped;
        }
    }

    private final VulkanDevice device;
    private final int[] typeFlags;
    private final long[] heapSizeOfType;
    private final Map<Integer, List<Block>> pools = new HashMap<>();
    private final Map<Long, Allocation> live = new HashMap<>();
    private long nextHandle = 1L;
    private int deviceMemoryCount, peakDeviceMemoryCount, peakAllocationCount;

    VulkanMemoryAllocator(VulkanDevice device) {
        this.device = device;
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceMemoryProperties props = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), props);
            typeFlags = new int[props.memoryTypeCount()];
            heapSizeOfType = new long[props.memoryTypeCount()];
            for (int i = 0; i < typeFlags.length; i++) {
                typeFlags[i] = props.memoryTypes(i).propertyFlags();
                heapSizeOfType[i] = props.memoryHeaps(props.memoryTypes(i).heapIndex()).size();
            }
        }
    }

    /** Block size for a heap: big enough to hold many assets, small enough not to starve a small (e.g. 256 MB BAR) heap. */
    static long blockSizeForHeap(long heapSize) {
        return Math.max(MIN_BLOCK_SIZE, Math.min(MAX_BLOCK_SIZE, heapSize / 8L));
    }

    /** Whether a request goes in its own VkDeviceMemory instead of a shared block. */
    static boolean wantsDedicated(Kind kind, long size, long blockSize) {
        return kind == Kind.DEDICATED || size > blockSize / 2L;
    }

    /** Allocates memory for {@code buffer} and binds it. Returns the allocation handle, or 0 on failure (the buffer is left as is). */
    public long bindBuffer(long buffer, int properties) {
        VkDevice vkDevice = device.getLogicalDevice();
        try (MemoryStack stack = stackPush()) {
            VkMemoryRequirements reqs = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(vkDevice, buffer, reqs);
            long handle = allocate(reqs.size(), reqs.alignment(), reqs.memoryTypeBits(), properties, Kind.BUFFER);
            if (handle == 0L) return 0L;
            Allocation a = get(handle);
            if (!device.check(vkBindBufferMemory(vkDevice, buffer, a.memory, a.offset), "vkBindBufferMemory")) {
                free(handle);
                return 0L;
            }
            return handle;
        }
    }

    /**
     * Allocates device-local memory for {@code image} and binds it. {@code dedicated} is for render targets, which are
     * large and rebuilt on resize. Returns the allocation handle, or 0 on failure.
     */
    public long bindImage(long image, boolean dedicated) {
        VkDevice vkDevice = device.getLogicalDevice();
        try (MemoryStack stack = stackPush()) {
            VkMemoryRequirements reqs = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(vkDevice, image, reqs);
            long handle = allocate(reqs.size(), reqs.alignment(), reqs.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    dedicated ? Kind.DEDICATED : Kind.IMAGE);
            if (handle == 0L) return 0L;
            Allocation a = get(handle);
            if (!device.check(vkBindImageMemory(vkDevice, image, a.memory, a.offset), "vkBindImageMemory")) {
                free(handle);
                return 0L;
            }
            return handle;
        }
    }

    private synchronized long allocate(long size, long alignment, int typeBits, int properties, Kind kind) {
        int type = findType(typeBits, properties);
        if (type < 0) {
            Log.error("VulkanMemory", "No memory type matches filter 0x" + Integer.toHexString(typeBits)
                    + " with properties 0x" + Integer.toHexString(properties));
            return 0L;
        }
        boolean hostVisible = (typeFlags[type] & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0;
        long blockSize = blockSizeForHeap(heapSizeOfType[type]);

        if (!wantsDedicated(kind, size, blockSize)) {
            int poolKey = type * 2 + (kind == Kind.IMAGE ? 1 : 0);
            List<Block> pool = pools.computeIfAbsent(poolKey, k -> new ArrayList<>());
            for (Block block : pool) {
                long offset = block.ranges.allocate(size, alignment);
                if (offset >= 0L) return register(new Allocation(block, block.memory, offset, size, block.mapped == 0L ? 0L : block.mapped + offset));
            }
            Block block = newBlock(type, blockSize, hostVisible, poolKey);
            if (block != null) {
                pool.add(block);
                long offset = block.ranges.allocate(size, alignment);
                return register(new Allocation(block, block.memory, offset, size, block.mapped == 0L ? 0L : block.mapped + offset));
            }
            // Out of memory for a whole block: an exact-size allocation may still fit
        }

        long[] memoryAndMapped = allocateDeviceMemory(type, size, hostVisible);
        if (memoryAndMapped == null) return 0L;
        return register(new Allocation(null, memoryAndMapped[0], 0L, size, memoryAndMapped[1]));
    }

    private Block newBlock(int type, long blockSize, boolean hostVisible, int poolKey) {
        long[] memoryAndMapped = allocateDeviceMemory(type, blockSize, hostVisible);
        return memoryAndMapped == null ? null : new Block(memoryAndMapped[0], memoryAndMapped[1], poolKey, blockSize);
    }

    /** One real vkAllocateMemory, mapped for its whole life when host-visible. Returns {memory, mappedAddress} or null. */
    private long[] allocateDeviceMemory(int type, long size, boolean hostVisible) {
        VkDevice vkDevice = device.getLogicalDevice();
        try (MemoryStack stack = stackPush()) {
            VkMemoryAllocateInfo info = VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default()
                    .allocationSize(size)
                    .memoryTypeIndex(type);
            LongBuffer pMemory = stack.mallocLong(1);
            int result = vkAllocateMemory(vkDevice, info, null, pMemory);
            if (!device.check(result, "vkAllocateMemory")) {
                if (!device.isLost()) Log.warn("VulkanMemory", "vkAllocateMemory of " + size + " bytes (type " + type + ") failed: " + result);
                return null;
            }
            long memory = pMemory.get(0);
            long mapped = 0L;
            if (hostVisible) {
                PointerBuffer pData = stack.mallocPointer(1);
                if (!device.check(vkMapMemory(vkDevice, memory, 0L, VK_WHOLE_SIZE, 0, pData), "vkMapMemory")) {
                    vkFreeMemory(vkDevice, memory, null);
                    return null;
                }
                mapped = pData.get(0);
            }
            deviceMemoryCount++;
            peakDeviceMemoryCount = Math.max(peakDeviceMemoryCount, deviceMemoryCount);
            return new long[] { memory, mapped };
        }
    }

    private long register(Allocation allocation) {
        long handle = nextHandle++;
        live.put(handle, allocation);
        peakAllocationCount = Math.max(peakAllocationCount, live.size());
        return handle;
    }

    private synchronized Allocation get(long handle) {
        Allocation a = live.get(handle);
        if (a == null) throw new IllegalArgumentException("unknown allocation handle " + handle);
        return a;
    }

    /** Returns an allocation; 0 is ignored. The resource bound to it must already be destroyed or unused by the GPU. */
    public synchronized void free(long handle) {
        if (handle == 0L) return;
        Allocation a = live.remove(handle);
        if (a == null) {
            Log.warn("VulkanMemory", "free of unknown allocation handle " + handle);
            return;
        }
        if (a.block == null) {
            releaseDeviceMemory(a.memory);
            return;
        }
        a.block.ranges.free(a.offset, a.size);
        if (!a.block.ranges.isEmpty()) return;
        List<Block> pool = pools.get(a.block.poolKey);
        if (pool != null && pool.size() > 1) {
            pool.remove(a.block);
            releaseDeviceMemory(a.block.memory);
        }
    }

    private void releaseDeviceMemory(long memory) {
        // Freeing mapped memory unmaps it implicitly
        vkFreeMemory(device.getLogicalDevice(), memory, null);
        deviceMemoryCount--;
    }

    /** CPU address of the allocation's first byte while host-visible, else 0. Valid until the allocation is freed. */
    public synchronized long mappedAddress(long handle) {
        Allocation a = live.get(handle);
        return a != null ? a.mapped : 0L;
    }

    private int findType(int typeBits, int properties) {
        for (int i = 0; i < typeFlags.length; i++) {
            if ((typeBits & (1 << i)) != 0 && (typeFlags[i] & properties) == properties) return i;
        }
        return -1;
    }

    /** Live VkDeviceMemory objects (blocks plus dedicated allocations): what the driver's ~4,096 limit counts. */
    public synchronized int getDeviceMemoryCount() { return deviceMemoryCount; }
    public synchronized int getPeakDeviceMemoryCount() { return peakDeviceMemoryCount; }
    /** Live resources placed by the allocator. */
    public synchronized int getAllocationCount() { return live.size(); }

    /** Device shutdown: frees every block. Anything still allocated is reported as a leak. */
    synchronized void destroy() {
        if (!live.isEmpty()) {
            int dedicated = 0;
            for (Allocation a : live.values()) {
                if (a.block == null) {
                    dedicated++;
                    vkFreeMemory(device.getLogicalDevice(), a.memory, null);
                }
            }
            Log.warn("VulkanMemory", live.size() + " allocations were never freed (" + dedicated + " dedicated); released with the device.");
            live.clear();
        }
        for (List<Block> pool : pools.values()) {
            for (Block block : pool) vkFreeMemory(device.getLogicalDevice(), block.memory, null);
        }
        pools.clear();
        // Before pooling, every resource was its own VkDeviceMemory: the second number is what the driver would have seen
        Log.info("VulkanMemory", "Peak " + peakDeviceMemoryCount + " VkDeviceMemory objects for " + peakAllocationCount + " resources.");
        deviceMemoryCount = 0;
    }
}
