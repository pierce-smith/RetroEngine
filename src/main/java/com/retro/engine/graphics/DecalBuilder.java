package com.retro.engine.graphics;

import com.retro.engine.assets.MeshBufferData;
import com.retro.engine.ecs.HierarchySystem;
import com.retro.engine.ecs.SceneWorld.WorldTransform;

import java.util.Arrays;

/**
 * Cuts the piece of a mesh that lies inside a decal's box. The result is ordinary geometry in the target's local space,
 * so it is drawn with the target's own transform and bone palette, and takes that surface's lights, shadows, lightmap
 * and fog from the normal lit shader.
 *
 * <p>Static meshes are clipped exactly to the box. Skinned meshes keep whole triangles instead: a clipped vertex can't
 * reproduce the blended skinning of the corners around it, so it would sink into a bending limb. Triangles are tested
 * in the current pose but written in bind pose with their original bone weights, and the shader discards everything
 * outside the 0..1 UV range. Not thread-safe; scratch arrays are reused between builds.</p>
 */
public final class DecalBuilder {

    private static final int STATIC_STRIDE = MeshBufferData.STATIC_FLOATS_PER_VERTEX;
    private static final int SKINNED_STRIDE = MeshBufferData.SKINNED_FLOATS_PER_VERTEX;
    private static final int OFFSET_COLOR = 6, OFFSET_UV = 10, OFFSET_TANGENT = 14, OFFSET_BITANGENT = 17;

    // A polygon vertex under clipping: the mesh attributes, then its decal-space position and how squarely it faces
    private static final int POLY_X = STATIC_STRIDE, POLY_FACING = STATIC_STRIDE + 3, POLY_STRIDE = STATIC_STRIDE + 4;
    private static final int MAX_POLY_VERTICES = 9; // a triangle gains at most one vertex per clip plane
    private static final int MAX_OUTPUT_VERTICES = 60000;

    // Decal frame: origin and orthonormal axes (u = image right, v = image up, n = out of the surface)
    private final double[] origin = new double[3];
    private final double[] axisU = new double[3], axisV = new double[3], axisN = new double[3];
    private double halfWidth, halfHeight, halfDepth, cosMaxAngle;
    private float tintR, tintG, tintB;

    private final double[] rotation = new double[9];
    private final double[] toDecal = new double[9], toDecalOffset = new double[3], normalToDecal = new double[9];
    private final float[] localU = new float[3], localV = new float[3];

    private float[] decalPositions = new float[0], decalNormals = new float[0];
    private float[] polyA = new float[MAX_POLY_VERTICES * POLY_STRIDE], polyB = new float[MAX_POLY_VERTICES * POLY_STRIDE];
    private float[] output = new float[STATIC_STRIDE * 256];
    private int outputVertices;

    private float boundsMinX, boundsMinY, boundsMinZ, boundsMaxX, boundsMaxY, boundsMaxZ;

    /** Radius of a sphere around the decal's origin that encloses its box. */
    public double boundingRadius() {
        return Math.sqrt(halfWidth * halfWidth + halfHeight * halfHeight + halfDepth * halfDepth);
    }

    /** Sets the box every following {@link #build} cuts with. Returns false when the spec has no usable size or normal. */
    public boolean setFrame(DecalSpec spec) {
        double nx = spec.normalX, ny = spec.normalY, nz = spec.normalZ;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!(length > 1e-6) || !(spec.width > 0.0f) || !(spec.height > 0.0f) || !(spec.depth > 0.0f)) return false;
        axisN[0] = nx / length; axisN[1] = ny / length; axisN[2] = nz / length;

        // Right edge: the requested tangent, or level with the world so an unrotated decal reads upright on a wall
        double tx = spec.tangentX, ty = spec.tangentY, tz = spec.tangentZ;
        if (tx * tx + ty * ty + tz * tz < 1e-10) {
            boolean facingVertical = Math.abs(axisN[1]) > 0.99;
            tx = facingVertical ? 1.0 : -axisN[2];
            ty = 0.0;
            tz = facingVertical ? 0.0 : axisN[0];
        }
        double along = tx * axisN[0] + ty * axisN[1] + tz * axisN[2];
        tx -= axisN[0] * along; ty -= axisN[1] * along; tz -= axisN[2] * along;
        double tangentLength = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (!(tangentLength > 1e-6)) return false;
        tx /= tangentLength; ty /= tangentLength; tz /= tangentLength;

        double bx = axisN[1] * tz - axisN[2] * ty, by = axisN[2] * tx - axisN[0] * tz, bz = axisN[0] * ty - axisN[1] * tx;
        double angle = Math.toRadians(spec.rotation), c = Math.cos(angle), s = Math.sin(angle);
        axisU[0] = tx * c + bx * s; axisU[1] = ty * c + by * s; axisU[2] = tz * c + bz * s;
        axisV[0] = bx * c - tx * s; axisV[1] = by * c - ty * s; axisV[2] = bz * c - tz * s;

        origin[0] = spec.posX; origin[1] = spec.posY; origin[2] = spec.posZ;
        halfWidth = spec.width * 0.5;
        halfHeight = spec.height * 0.5;
        halfDepth = spec.depth * 0.5;
        cosMaxAngle = Math.cos(Math.toRadians(Math.max(0.0f, Math.min(90.0f, spec.maxAngle))));
        // The shaders only tint with a non-black vertex color, so a pure black decal stays just above that test
        tintR = Math.max(spec.colorR, 0.002f);
        tintG = Math.max(spec.colorG, 0.002f);
        tintB = Math.max(spec.colorB, 0.002f);
        return true;
    }

    /**
     * Geometry for the part of {@code mesh} inside the box, or null when nothing lands. {@code palette} is the target's
     * current pose; null treats a skinned mesh as bind pose.
     */
    public MeshBufferData build(MeshBufferData mesh, float[][] palette, WorldTransform world) {
        if (mesh == null || mesh.vertices == null || mesh.vertexCount < 3) return null;
        int stride = mesh.stride();
        if (stride < OFFSET_UV) return null;
        if (world.sx() == 0.0f || world.sy() == 0.0f || world.sz() == 0.0f) return null;
        boolean skinned = stride >= SKINNED_STRIDE;

        prepareTransforms(world);
        projectVertices(mesh, stride, skinned ? palette : null);

        outputVertices = 0;
        boundsMinX = boundsMinY = boundsMinZ = Float.POSITIVE_INFINITY;
        boundsMaxX = boundsMaxY = boundsMaxZ = Float.NEGATIVE_INFINITY;

        int[] indices = mesh.isIndexed() ? mesh.indices : null;
        int corners = indices != null ? mesh.indexCount : mesh.vertexCount;
        for (int t = 0; t + 2 < corners && outputVertices < MAX_OUTPUT_VERTICES; t += 3) {
            int a = indices != null ? indices[t] : t;
            int b = indices != null ? indices[t + 1] : t + 1;
            int c = indices != null ? indices[t + 2] : t + 2;
            if (outsideBox(a, b, c)) continue;
            float facing = faceFacing(a, b, c);
            if (facing < cosMaxAngle) continue;
            if (skinned) emitWholeTriangle(mesh.vertices, stride, a, b, c, facing);
            else clipTriangle(mesh.vertices, stride, a, b, c, facing);
        }
        if (outputVertices == 0) return null;
        int outStride = skinned ? SKINNED_STRIDE : STATIC_STRIDE;
        return new MeshBufferData(Arrays.copyOf(output, outputVertices * outStride), outputVertices);
    }

    /** Center of the last build's local bounds, written into {@code out}; returns the enclosing radius. */
    public float lastBounds(float[] out) {
        out[0] = (boundsMinX + boundsMaxX) * 0.5f;
        out[1] = (boundsMinY + boundsMaxY) * 0.5f;
        out[2] = (boundsMinZ + boundsMaxZ) * 0.5f;
        float dx = boundsMaxX - out[0], dy = boundsMaxY - out[1], dz = boundsMaxZ - out[2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void prepareTransforms(WorldTransform world) {
        HierarchySystem.rotationMatrix(world.rx(), world.ry(), world.rz(), rotation);
        double[] scale = { world.sx(), world.sy(), world.sz() };
        double[] offset = { world.px() - origin[0], world.py() - origin[1], world.pz() - origin[2] };
        double[][] axes = { axisU, axisV, axisN };
        for (int row = 0; row < 3; row++) {
            double[] axis = axes[row];
            for (int col = 0; col < 3; col++) {
                // Axis dotted with column col of the rotation, which is the row-major matrix's col-th column
                double d = axis[0] * rotation[col] + axis[1] * rotation[3 + col] + axis[2] * rotation[6 + col];
                toDecal[row * 3 + col] = d * scale[col];
                normalToDecal[row * 3 + col] = d / scale[col];
            }
            toDecalOffset[row] = axis[0] * offset[0] + axis[1] * offset[1] + axis[2] * offset[2];
        }
        // Rows of normalToDecal are the decal axes in mesh space (S^-1 R^T axis), so the model matrix maps them back
        for (int col = 0; col < 3; col++) {
            localU[col] = (float) normalToDecal[col];
            localV[col] = (float) normalToDecal[3 + col];
        }
    }

    /** Fills the scratch arrays with every vertex's decal-space position and unit normal, posed when a palette is given. */
    private void projectVertices(MeshBufferData mesh, int stride, float[][] palette) {
        int count = mesh.vertexCount;
        if (decalPositions.length < count * 3) {
            decalPositions = new float[count * 3];
            decalNormals = new float[count * 3];
        }
        float[] v = mesh.vertices;
        for (int i = 0; i < count; i++) {
            int at = i * stride;
            float px = v[at], py = v[at + 1], pz = v[at + 2];
            float nx = v[at + 3], ny = v[at + 4], nz = v[at + 5];

            if (palette != null) {
                // Same blend as the skinning vertex shaders
                float w0 = v[at + 26], w1 = v[at + 27], w2 = v[at + 28], w3 = v[at + 29];
                float total = w0 + w1 + w2 + w3;
                if (total > 0.001f) {
                    float sx = 0, sy = 0, sz = 0, tx = 0, ty = 0, tz = 0;
                    for (int k = 0; k < 4; k++) {
                        float w = v[at + 26 + k] / total;
                        if (w == 0.0f) continue;
                        int bone = Math.min(Float.floatToRawIntBits(v[at + 22 + k]), 63);
                        if (bone < 0 || bone >= palette.length || palette[bone] == null) continue;
                        float[] m = palette[bone];
                        sx += w * (m[0] * px + m[4] * py + m[8] * pz + m[12]);
                        sy += w * (m[1] * px + m[5] * py + m[9] * pz + m[13]);
                        sz += w * (m[2] * px + m[6] * py + m[10] * pz + m[14]);
                        tx += w * (m[0] * nx + m[4] * ny + m[8] * nz);
                        ty += w * (m[1] * nx + m[5] * ny + m[9] * nz);
                        tz += w * (m[2] * nx + m[6] * ny + m[10] * nz);
                    }
                    px = sx; py = sy; pz = sz;
                    nx = tx; ny = ty; nz = tz;
                }
            }

            int o = i * 3;
            for (int row = 0; row < 3; row++) {
                int r = row * 3;
                decalPositions[o + row] = (float) (toDecal[r] * px + toDecal[r + 1] * py + toDecal[r + 2] * pz + toDecalOffset[row]);
                decalNormals[o + row] = (float) (normalToDecal[r] * nx + normalToDecal[r + 1] * ny + normalToDecal[r + 2] * nz);
            }
            float nLength = (float) Math.sqrt(decalNormals[o] * decalNormals[o] + decalNormals[o + 1] * decalNormals[o + 1] + decalNormals[o + 2] * decalNormals[o + 2]);
            if (nLength > 1e-8f) {
                decalNormals[o] /= nLength; decalNormals[o + 1] /= nLength; decalNormals[o + 2] /= nLength;
            }
        }
    }

    private boolean outsideBox(int a, int b, int c) {
        float[] p = decalPositions;
        int ia = a * 3, ib = b * 3, ic = c * 3;
        return outsideAxis(p[ia], p[ib], p[ic], halfWidth)
                || outsideAxis(p[ia + 1], p[ib + 1], p[ic + 1], halfHeight)
                || outsideAxis(p[ia + 2], p[ib + 2], p[ic + 2], halfDepth);
    }

    private static boolean outsideAxis(float a, float b, float c, double half) {
        return (a > half && b > half && c > half) || (a < -half && b < -half && c < -half);
    }

    /** Cosine between the triangle and the decal normal. Vertex normals pick the side, because mesh winding isn't reliable. */
    private float faceFacing(int a, int b, int c) {
        float[] p = decalPositions;
        int ia = a * 3, ib = b * 3, ic = c * 3;
        float e1x = p[ib] - p[ia], e1y = p[ib + 1] - p[ia + 1], e1z = p[ib + 2] - p[ia + 2];
        float e2x = p[ic] - p[ia], e2y = p[ic + 1] - p[ia + 1], e2z = p[ic + 2] - p[ia + 2];
        float fx = e1y * e2z - e1z * e2y, fy = e1z * e2x - e1x * e2z, fz = e1x * e2y - e1y * e2x;
        float length = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (!(length > 1e-12f)) return -1.0f;

        float[] n = decalNormals;
        float side = fx * (n[ia] + n[ib] + n[ic]) + fy * (n[ia + 1] + n[ib + 1] + n[ic + 1]) + fz * (n[ia + 2] + n[ib + 2] + n[ic + 2]);
        float cosine = fz / length;
        if (side == 0.0f) return Math.abs(cosine); // no vertex normals: treat the surface as two-sided
        return side < 0.0f ? -cosine : cosine;
    }

    private float vertexFacing(int vertex, float faceFacing) {
        int o = vertex * 3;
        float z = decalNormals[o + 2];
        boolean hasNormal = decalNormals[o] != 0.0f || decalNormals[o + 1] != 0.0f || z != 0.0f;
        return hasNormal ? z : faceFacing;
    }

    private void clipTriangle(float[] vertices, int stride, int a, int b, int c, float facing) {
        int copied = Math.min(stride, STATIC_STRIDE);
        Arrays.fill(polyA, 0, 3 * POLY_STRIDE, 0.0f);
        int[] corners = { a, b, c };
        for (int i = 0; i < 3; i++) {
            int to = i * POLY_STRIDE, vertex = corners[i];
            System.arraycopy(vertices, vertex * stride, polyA, to, copied);
            System.arraycopy(decalPositions, vertex * 3, polyA, to + POLY_X, 3);
            polyA[to + POLY_FACING] = vertexFacing(vertex, facing);
        }

        int count = 3;
        float[] in = polyA, out = polyB;
        for (int plane = 0; plane < 6 && count >= 3; plane++) {
            int axis = plane >> 1;
            float sign = (plane & 1) == 0 ? 1.0f : -1.0f;
            float limit = (float) (axis == 0 ? halfWidth : axis == 1 ? halfHeight : halfDepth);
            count = clipAgainstPlane(in, count, out, POLY_X + axis, sign, limit);
            float[] swap = in; in = out; out = swap;
        }
        if (count < 3) return;

        ensureOutput((count - 2) * 3, STATIC_STRIDE);
        for (int i = 1; i + 1 < count; i++) {
            emitPolyVertex(in, 0);
            emitPolyVertex(in, i);
            emitPolyVertex(in, i + 1);
        }
    }

    /** One Sutherland-Hodgman pass keeping {@code sign * coordinate <= limit}; every attribute is interpolated. */
    private static int clipAgainstPlane(float[] in, int count, float[] out, int coordinate, float sign, float limit) {
        int written = 0;
        for (int i = 0; i < count; i++) {
            int current = i * POLY_STRIDE, next = ((i + 1) % count) * POLY_STRIDE;
            float dCurrent = limit - sign * in[current + coordinate];
            float dNext = limit - sign * in[next + coordinate];
            boolean currentInside = dCurrent >= 0.0f, nextInside = dNext >= 0.0f;
            if (currentInside) {
                System.arraycopy(in, current, out, written * POLY_STRIDE, POLY_STRIDE);
                written++;
            }
            if (currentInside != nextInside) {
                float t = dCurrent / (dCurrent - dNext);
                int to = written * POLY_STRIDE;
                for (int f = 0; f < POLY_STRIDE; f++) out[to + f] = in[current + f] + (in[next + f] - in[current + f]) * t;
                written++;
            }
        }
        return written;
    }

    private void emitPolyVertex(float[] poly, int index) {
        int from = index * POLY_STRIDE, to = outputVertices * STATIC_STRIDE;
        System.arraycopy(poly, from, output, to, STATIC_STRIDE);
        writeDecalAttributes(to, poly[from + POLY_X], poly[from + POLY_X + 1], poly[from + POLY_FACING]);
        includeBounds(output[to], output[to + 1], output[to + 2]);
        outputVertices++;
    }

    private void emitWholeTriangle(float[] vertices, int stride, int a, int b, int c, float facing) {
        ensureOutput(3, SKINNED_STRIDE);
        int[] corners = { a, b, c };
        for (int vertex : corners) {
            int to = outputVertices * SKINNED_STRIDE, o = vertex * 3;
            System.arraycopy(vertices, vertex * stride, output, to, SKINNED_STRIDE);
            writeDecalAttributes(to, decalPositions[o], decalPositions[o + 1], vertexFacing(vertex, facing));
            includeBounds(output[to], output[to + 1], output[to + 2]);
            outputVertices++;
        }
    }

    /**
     * Replaces a copied vertex's UV, color and tangent frame with the decal's. Vertex alpha fades surfaces turning away.
     * Only quantities that are real per vertex can go here: clipped vertices all lie on the box, so a fade toward the
     * box's ends would read near zero at every corner and wash out the whole polygon.
     */
    private void writeDecalAttributes(int to, float x, float y, float facing) {
        output[to + OFFSET_UV] = (float) (x / (2.0 * halfWidth)) + 0.5f;
        output[to + OFFSET_UV + 1] = 0.5f - (float) (y / (2.0 * halfHeight));
        output[to + OFFSET_COLOR] = tintR;
        output[to + OFFSET_COLOR + 1] = tintG;
        output[to + OFFSET_COLOR + 2] = tintB;
        output[to + OFFSET_COLOR + 3] = smoothstep((float) cosMaxAngle, (float) Math.min(1.0, cosMaxAngle + 0.3), facing);
        System.arraycopy(localU, 0, output, to + OFFSET_TANGENT, 3);
        System.arraycopy(localV, 0, output, to + OFFSET_BITANGENT, 3);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        if (edge1 <= edge0) return x >= edge1 ? 1.0f : 0.0f;
        float t = Math.max(0.0f, Math.min(1.0f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }

    private void includeBounds(float x, float y, float z) {
        if (x < boundsMinX) boundsMinX = x;
        if (y < boundsMinY) boundsMinY = y;
        if (z < boundsMinZ) boundsMinZ = z;
        if (x > boundsMaxX) boundsMaxX = x;
        if (y > boundsMaxY) boundsMaxY = y;
        if (z > boundsMaxZ) boundsMaxZ = z;
    }

    private void ensureOutput(int moreVertices, int stride) {
        int needed = (outputVertices + moreVertices) * stride;
        if (needed > output.length) output = Arrays.copyOf(output, Math.max(needed, output.length * 2));
    }
}
