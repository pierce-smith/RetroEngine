package com.retro.engine.ecs.components;

import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.InspectorProperty;
import com.retro.engine.ecs.InspectorProperty.PropertyType;
import com.retro.engine.spline.SplineEvaluator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * A curve through control knots, authored in the Scene viewport (drag knots and Bezier handles while the entity is
 * selected) and evaluated by {@link SplineEvaluator}. Knots live in the owner's local space, offset by the owner's
 * Transform position (rotation and scale are not applied; splines are expected on root entities).
 *
 * <p>Knot data is persisted through the hidden {@link #splineData} blob. That array is never mutated in place: every
 * committed edit installs a fresh array, so scene snapshots, duplicates and undo can share or swap it and the
 * component re-decodes lazily when the reference changes. Arc-length tables are re-baked only when the curve changes
 * (edit time); per-frame queries are allocation-free.</p>
 */
public class SplineComponent extends Component {

    public enum CurveType { CATMULL_ROM, BEZIER }

    public static final int MAX_POINTS = 256;
    private static final int DATA_VERSION = 1;

    @InspectorProperty(order = 0, label = "CURVE TYPE")
    public CurveType curveType = CurveType.CATMULL_ROM;

    @InspectorProperty(order = 1, label = "LOOP", type = PropertyType.TOGGLE)
    public boolean loop = false;

    /** Samples per span used for drawing and the arc-length table. */
    @InspectorProperty(order = 2, label = "RESOLUTION", min = 2, max = 64, step = 1)
    public int resolution = 16;

    /** Knot count; editing it in the inspector appends (extrapolated) or removes knots at the end. */
    @InspectorProperty(order = 3, label = "POINT COUNT", min = 2, max = MAX_POINTS, step = 1, serialized = false)
    public int pointCount = 4;

    // Persisted knots + handles (see class doc)
    @InspectorProperty(order = 4, hidden = true)
    public byte[] splineData = null;

    // --- Live knot data: xyz per knot; Bezier handles are offsets from their knot (in xyz, out xyz) ---
    public float[] points = new float[MAX_POINTS * 3];
    public float[] handles = new float[MAX_POINTS * 6];
    private int count;
    private byte[] decodedFrom;
    private boolean decodedOnce;

    /** Bumped on every knot edit; part of the bake signature. */
    private int editVersion;
    private long bakedSignature = Long.MIN_VALUE;

    // --- Arc-length table (owned by SplineEvaluator) ---
    public float[] lutT = new float[0];
    public float[] lutLength = new float[0];
    public int lutCount;
    public float totalLength;

    /** Entity carrying this spline; bound by SplineSystem / the viewport gizmo (supplies the world offset). */
    public transient Entity owner;

    public SplineComponent() {
        count = 4;
        for (int i = 0; i < count; i++) {
            points[i * 3] = i * 2.0f;
            setDefaultHandles(i);
        }
    }

    public int getCount() {
        ensureUpToDate();
        return count;
    }

    /** Live knot count without syncing (for the evaluator's bake). */
    public int getCountRaw() { return count; }

    /** Syncs the live knots with the persisted blob and the inspector's point count, then re-bakes if the curve changed. */
    public void ensureUpToDate() {
        if (splineData != decodedFrom || !decodedOnce) {
            decodedOnce = true;
            decodedFrom = splineData;
            if (splineData != null) decode(splineData);
            pointCount = count;
            editVersion++;
        }
        if (pointCount != count) {
            resize(pointCount);
            commit();
        }
        if (resolution < 2) resolution = 2; else if (resolution > 64) resolution = 64;
        long sig = ((long) editVersion << 8) ^ ((long) resolution << 2) ^ (loop ? 2 : 0) ^ curveType.ordinal();
        if (sig != bakedSignature) {
            bakedSignature = sig;
            SplineEvaluator.bake(this);
        }
    }

    /** Marks live knots as edited (re-bakes on next query) without persisting yet; used during viewport drags. */
    public void markEdited() { editVersion++; }

    /** Persists the live knots into a fresh {@link #splineData} array (edit time only; allocates). */
    public void commit() {
        splineData = encode();
        decodedFrom = splineData;
        decodedOnce = true;
        pointCount = count;
        editVersion++;
    }

    @Override
    public void onBeforeSerialize() {
        ensureUpToDate();
        if (splineData == null) commit();
    }

    @Override
    public void onDeserialized(Entity owner) {
        this.owner = owner;
        ensureUpToDate();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Knot editing (edit time)
    // ---------------------------------------------------------------------------------------------------------------

    public void setPoint(int i, float x, float y, float z) {
        if (i < 0 || i >= count) return;
        points[i * 3] = x; points[i * 3 + 1] = y; points[i * 3 + 2] = z;
        editVersion++;
    }

    /** Sets a Bezier handle offset; {@code out} selects the outgoing handle. The opposite handle is mirrored. */
    public void setHandle(int i, boolean out, float dx, float dy, float dz) {
        if (i < 0 || i >= count) return;
        int o = i * 6 + (out ? 3 : 0), m = i * 6 + (out ? 0 : 3);
        handles[o] = dx; handles[o + 1] = dy; handles[o + 2] = dz;
        handles[m] = -dx; handles[m + 1] = -dy; handles[m + 2] = -dz;
        editVersion++;
    }

    /** Replaces the knots with {@code n} xyz triples from {@code xyz} (default handles) and persists them. Edit time. */
    public void setPoints(float[] xyz, int n) {
        n = Math.max(2, Math.min(MAX_POINTS, n));
        System.arraycopy(xyz, 0, points, 0, n * 3);
        for (int i = 0; i < n; i++) setDefaultHandles(i);
        count = n;
        commit();
    }

    private void resize(int n) {
        if (n < 2) n = 2; else if (n > MAX_POINTS) n = MAX_POINTS;
        for (int i = count; i < n; i++) {
            int a = (i - 1) * 3, b = (i - 2) * 3, c = i * 3;
            for (int k = 0; k < 3; k++) points[c + k] = points[a + k] + (points[a + k] - points[b + k]);
            if (points[c] == points[a] && points[c + 1] == points[a + 1] && points[c + 2] == points[a + 2]) points[c] += 2.0f;
            setDefaultHandles(i);
        }
        count = n;
    }

    private void setDefaultHandles(int i) {
        int h = i * 6;
        handles[h] = -0.66f; handles[h + 1] = 0; handles[h + 2] = 0;
        handles[h + 3] = 0.66f; handles[h + 4] = 0; handles[h + 5] = 0;
    }

    private byte[] encode() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 + count * 36);
        try (DataOutputStream dos = new DataOutputStream(bytes)) {
            dos.writeInt(DATA_VERSION);
            dos.writeInt(count);
            for (int i = 0; i < count * 3; i++) dos.writeFloat(points[i]);
            for (int i = 0; i < count * 6; i++) dos.writeFloat(handles[i]);
        } catch (IOException e) {
            throw new IllegalStateException(e); // in-memory stream
        }
        return bytes.toByteArray();
    }

    private void decode(byte[] data) {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            if (dis.readInt() > DATA_VERSION) return;
            int n = Math.max(0, Math.min(MAX_POINTS, dis.readInt()));
            for (int i = 0; i < n * 3; i++) points[i] = dis.readFloat();
            for (int i = 0; i < n * 6; i++) handles[i] = dis.readFloat();
            count = n;
            if (count < 2) resize(2);
        } catch (IOException e) {
            System.err.println("[SplineComponent] Discarding unreadable spline data: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Convenience queries (world space when an owner is bound; allocation-free)
    // ---------------------------------------------------------------------------------------------------------------

    public void getPositionAt(float t, float[] out) { SplineEvaluator.getPositionAt(this, t, out); }
    public void getTangentAt(float t, float[] out) { SplineEvaluator.getTangentAt(this, t, out); }
    public float getPointAtDistance(float distance, float[] out) { return SplineEvaluator.getPointAtDistance(this, distance, out); }

    public float getLength() {
        ensureUpToDate();
        return totalLength;
    }

    @Override
    public String getComponentName() {
        return "Spline";
    }
}
