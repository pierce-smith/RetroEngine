#include <metal_stdlib>
using namespace metal;

// Window-space passes into the drawable: the offscreen scene composite quad and the 2D editor UI batch.
// Neither pass has a depth attachment.

struct UIVertexOut {
    float4 position [[position]];
    float4 color;
};

// 6-float stride: xy = NDC position, rgba = color
vertex UIVertexOut uiVertexMain(uint vertexID [[vertex_id]],
                                constant float* vertices [[buffer(0)]]) {
    UIVertexOut out;
    uint idx = vertexID * 6;
    out.position = float4(vertices[idx], vertices[idx+1], 0.0, 1.0);
    out.color = float4(vertices[idx+2], vertices[idx+3], vertices[idx+4], vertices[idx+5]);
    return out;
}

fragment float4 uiFragmentMain(UIVertexOut in [[stage_in]]) {
    float a = in.color.a;
    if (a > 0.0f && a < 1.0f) {
        a = pow(a, 0.70f);
    }
    return float4(in.color.rgb, a);
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
