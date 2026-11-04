#include <metal_stdlib>
using namespace metal;

// Single-pass camera post-processing: replaces the plain viewport composite while the Main Camera has POST FX on.
// Vignette, CRT scanlines, chromatic aberration, saturation / contrast. Uniform layout = PostProcessSettings.pack().

struct PostFX {
    float time;
    float vignette;
    float scanlines;
    float scanlineSpeed;
    float chromatic;   // pixels at the screen edge
    float saturation;
    float contrast;
    float pad;
};

struct PostVertexOut {
    float4 position [[position]];
    float2 uv;
};

// Same 9-float viewport quad as compositeVertexMain: xyz = NDC position, rgba unused, uv
vertex PostVertexOut postVertexMain(uint vertexID [[vertex_id]],
                                    constant float* vertices [[buffer(0)]]) {
    PostVertexOut out;
    uint idx = vertexID * 9;
    out.position = float4(vertices[idx], vertices[idx+1], 0.0, 1.0);
    out.uv = float2(vertices[idx+7], vertices[idx+8]);
    return out;
}

fragment float4 postFragmentMain(PostVertexOut in [[stage_in]],
                                 texture2d<float> sceneColor [[texture(0)]],
                                 sampler smp [[sampler(0)]],
                                 constant PostFX& fx [[buffer(0)]]) {
    float2 size = float2(sceneColor.get_width(), sceneColor.get_height());
    float2 uv = in.uv;
    float2 dir = uv - 0.5;

    // Chromatic aberration: red/blue pulled apart radially, strongest at the edges
    float2 off = dir * 2.0 * fx.chromatic / size;
    float3 col;
    col.r = sceneColor.sample(smp, clamp(uv + off, 0.0, 1.0)).r;
    col.g = sceneColor.sample(smp, uv).g;
    col.b = sceneColor.sample(smp, clamp(uv - off, 0.0, 1.0)).b;

    float luma = dot(col, float3(0.299, 0.587, 0.114));
    col = mix(float3(luma), col, fx.saturation);
    col = (col - 0.5) * fx.contrast + 0.5;

    // Scanlines: 2-pixel period, scrolling scanlineSpeed lines per second
    float s = 0.5 + 0.5 * sin(M_PI_F * (uv.y * size.y + 2.0 * fx.time * fx.scanlineSpeed));
    col *= 1.0 - fx.scanlines * (1.0 - s);

    float v = smoothstep(0.25, 0.75, length(dir) * 1.41421356);
    col *= 1.0 - fx.vignette * v;

    return float4(saturate(col), 1.0);
}
