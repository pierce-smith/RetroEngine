package com.retro.engine.graphics;

/**
 * Frame draw list: a pooled, growable {@link RenderCommand} array plus a sorted index permutation, and the directional
 * light-space matrix fitted by the scene pipeline. Commands are ordered by a 64-bit key, compared unsigned:
 * <pre>
 *   bit 63      | 62..55  | 54..39      | 38..24      | 23..0
 *   translucent | pass ID | pipeline ID | material ID | depth
 * </pre>
 * Opaque draws therefore precede translucent ones. Callers encode depth front-to-back for opaque draws and
 * back-to-front for translucent ones ({@link #packKey} does this). Steady-state frames never allocate: the pool, key and
 * permutation arrays only grow.
 */
public final class RenderQueue {

    public static final int PASS_SCENE = 0;

    public static final int PIPELINE_STATIC = 0;
    public static final int PIPELINE_SKINNED = 1;

    public static final int PASS_BITS = 8, PIPELINE_BITS = 16, MATERIAL_BITS = 15, DEPTH_BITS = 24;
    private static final int DEPTH_SHIFT = 0;
    private static final int MATERIAL_SHIFT = DEPTH_SHIFT + DEPTH_BITS;
    private static final int PIPELINE_SHIFT = MATERIAL_SHIFT + MATERIAL_BITS;
    private static final int PASS_SHIFT = PIPELINE_SHIFT + PIPELINE_BITS;
    private static final int TRANSLUCENT_SHIFT = PASS_SHIFT + PASS_BITS;
    public static final int MAX_DEPTH = (1 << DEPTH_BITS) - 1;

    private RenderCommand[] commands;
    private int count;

    private long[] keys, keyScratch;
    private int[] order, orderScratch;
    private final int[] histograms = new int[8 * 256];

    /** Column-major orthographic light view-projection for the shadow map pass. */
    public final float[] lightSpaceMatrix = new float[16];

    public RenderQueue() {
        this(1024);
    }

    public RenderQueue(int initialCapacity) {
        int cap = Math.max(16, initialCapacity);
        commands = new RenderCommand[cap];
        for (int i = 0; i < cap; i++) commands[i] = new RenderCommand();
        keys = new long[cap];
        keyScratch = new long[cap];
        order = new int[cap];
        orderScratch = new int[cap];
    }

    public void clear() {
        count = 0;
    }

    /** Claims the next pooled command, reset to defaults. */
    public RenderCommand add() {
        if (count == commands.length) grow();
        RenderCommand cmd = commands[count++];
        cmd.reset();
        return cmd;
    }

    public int size() { return count; }

    /** Pooled command storage in submission order; only indices below {@link #size()} are live. */
    public RenderCommand[] commands() { return commands; }

    /** Submission index of the i-th command in key order; valid after {@link #sort()}. */
    public int sortedIndex(int i) { return order[i]; }

    /** The i-th command in key order; valid after {@link #sort()}. */
    public RenderCommand sorted(int i) { return commands[order[i]]; }

    /**
     * Orders the live commands by unsigned sort key with an LSD radix sort (stable, so equal keys keep submission order).
     * Byte positions every key shares are skipped, which with mostly-zero high fields keeps it to a few passes.
     */
    public void sort() {
        int n = count;
        for (int i = 0; i < n; i++) {
            keys[i] = commands[i].sortKey;
            order[i] = i;
        }
        if (n < 2) return;

        int[] hist = histograms;
        java.util.Arrays.fill(hist, 0);
        for (int i = 0; i < n; i++) {
            long k = keys[i];
            for (int b = 0; b < 8; b++) hist[(b << 8) | (int) ((k >>> (b << 3)) & 0xFF)]++;
        }

        long[] srcKeys = keys, dstKeys = keyScratch;
        int[] srcOrder = order, dstOrder = orderScratch;
        for (int b = 0; b < 8; b++) {
            int base = b << 8;
            int shift = b << 3;
            if (hist[base | (int) ((srcKeys[0] >>> shift) & 0xFF)] == n) continue;

            int sum = 0;
            for (int d = 0; d < 256; d++) {
                int c = hist[base | d];
                hist[base | d] = sum;
                sum += c;
            }
            for (int i = 0; i < n; i++) {
                long k = srcKeys[i];
                int pos = hist[base | (int) ((k >>> shift) & 0xFF)]++;
                dstKeys[pos] = k;
                dstOrder[pos] = srcOrder[i];
            }
            long[] tk = srcKeys; srcKeys = dstKeys; dstKeys = tk;
            int[] to = srcOrder; srcOrder = dstOrder; dstOrder = to;
        }
        keys = srcKeys; keyScratch = dstKeys;
        order = srcOrder; orderScratch = dstOrder;
    }

    /**
     * Packs a sort key. {@code depth} is a quantized view depth in [0, {@link #MAX_DEPTH}] (near = 0); translucent keys
     * store it inverted so farther draws sort first. Out-of-range fields are masked to their bit width.
     */
    public static long packKey(boolean translucent, int passId, int pipelineId, int materialId, int depth) {
        int d = Math.max(0, Math.min(MAX_DEPTH, depth));
        if (translucent) d = MAX_DEPTH - d;
        return ((translucent ? 1L : 0L) << TRANSLUCENT_SHIFT)
                | ((passId & ((1L << PASS_BITS) - 1)) << PASS_SHIFT)
                | ((pipelineId & ((1L << PIPELINE_BITS) - 1)) << PIPELINE_SHIFT)
                | ((materialId & ((1L << MATERIAL_BITS) - 1)) << MATERIAL_SHIFT)
                | ((long) d << DEPTH_SHIFT);
    }

    /** Linear view depth in [near, far] quantized to {@link #DEPTH_BITS} bits; values outside clamp to the ends. */
    public static int quantizeDepth(float viewDepth, float near, float far) {
        float range = far - near;
        if (!(range > 0.0f)) return 0;
        float t = (viewDepth - near) / range;
        if (!(t > 0.0f)) return 0;
        if (t >= 1.0f) return MAX_DEPTH;
        return (int) (t * MAX_DEPTH);
    }

    public static boolean isTranslucent(long key) { return (key >>> TRANSLUCENT_SHIFT) != 0L; }
    public static int passId(long key) { return (int) ((key >>> PASS_SHIFT) & ((1L << PASS_BITS) - 1)); }
    public static int pipelineId(long key) { return (int) ((key >>> PIPELINE_SHIFT) & ((1L << PIPELINE_BITS) - 1)); }
    public static int materialId(long key) { return (int) ((key >>> MATERIAL_SHIFT) & ((1L << MATERIAL_BITS) - 1)); }
    public static int depth(long key) { return (int) ((key >>> DEPTH_SHIFT) & MAX_DEPTH); }

    private void grow() {
        int cap = commands.length * 2;
        RenderCommand[] grown = java.util.Arrays.copyOf(commands, cap);
        for (int i = commands.length; i < cap; i++) grown[i] = new RenderCommand();
        commands = grown;
        keys = new long[cap];
        keyScratch = new long[cap];
        order = new int[cap];
        orderScratch = new int[cap];
    }
}
