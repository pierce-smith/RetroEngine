#include <metal_stdlib>
using namespace metal;

// Window-space passes into the drawable: the offscreen scene composite quad and the 2D editor UI batch.
// Neither pass has a depth attachment.

struct UIVertexOut {
    float4 position [[position]];
    float4 color;
    float2 uv;
};

// 8-float stride: xy = NDC position, rgba = color, uv = font atlas coordinate (< -1 for solid geometry,
// > 1.5 for camera preview quads, which sample the preview target at uv - 2)
vertex UIVertexOut uiVertexMain(uint vertexID [[vertex_id]],
                                constant float* vertices [[buffer(0)]]) {
    UIVertexOut out;
    uint idx = vertexID * 8;
    out.position = float4(vertices[idx], vertices[idx+1], 0.0, 1.0);
    out.color = float4(vertices[idx+2], vertices[idx+3], vertices[idx+4], vertices[idx+5]);
    out.uv = float2(vertices[idx+6], vertices[idx+7]);
    return out;
}

// Glyphs sample a signed distance field (0.5 = outline); fwidth keeps the edge ~1 device pixel wide at any scale
fragment float4 uiFragmentMain(UIVertexOut in [[stage_in]],
                               texture2d<float> fontAtlas [[texture(0)]],
                               texture2d<float> cameraPreview [[texture(1)]],
                               sampler smp [[sampler(0)]]) {
    float a = in.color.a;
    if (a > 0.0f && a < 1.0f) {
        a = pow(a, 0.70f);
    }
    float dist = fontAtlas.sample(smp, in.uv).r;
    float w = max(fwidth(dist) * 0.7f, 1e-4f);
    // Explicit LOD: the branch is per-fragment, so implicit derivatives are undefined inside it
    if (in.uv.x > 1.5f) {
        return float4(cameraPreview.sample(smp, in.uv - 2.0f, level(0.0f)).rgb, a);
    }
    float coverage = smoothstep(0.5f - w, 0.5f + w, dist);
    return float4(in.color.rgb, in.uv.x < -1.0f ? a : a * coverage);
}

struct CompositeVertexOut {
    float4 position [[position]];
    float2 uv;
};

// 9-float stride: xyz = NDC position, rgba = color (unused), uv; samples the offscreen scene color 1:1
vertex CompositeVertexOut compositeVertexMain(uint vertexID [[vertex_id]],
                                              constant float* vertices [[buffer(0)]]) {
    CompositeVertexOut out;
    uint idx = vertexID * 9;
    out.position = float4(vertices[idx], vertices[idx+1], 0.0, 1.0);
    out.uv = float2(vertices[idx+7], vertices[idx+8]);
    return out;
}

fragment float4 compositeFragmentMain(CompositeVertexOut in [[stage_in]],
                                      texture2d<float> sceneColor [[texture(0)]],
                                      sampler smp [[sampler(0)]]) {
    return sceneColor.sample(smp, in.uv);
}
