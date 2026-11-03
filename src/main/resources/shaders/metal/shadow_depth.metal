#include <metal_stdlib>
using namespace metal;

// Directional shadow map depth pass into the 2048x2048 Depth32Float shadow map. Depth comes from
// mvp = lightSpace * model; the fragment stage only applies the foliage alpha cutout so leaf cards cast
// shaped shadows. The shadow map itself is never bound here; the lit pass samples it at texture(1).

// Must stay byte-identical to the light structs in blinn_phong.metal
struct PointLight {
    float4 position;          // xyz = position
    float4 color;             // rgb = color, a = intensity
};

struct SpotLight {
    float4 position;          // xyz = position
    float4 direction;         // xyz = direction
    float4 color;             // rgb = color, a = intensity
};

struct LocalLights {
    PointLight pointLights[32]; // 0
    SpotLight spotLights[8];    // 1024 (1408 bytes total)
};

// Must stay byte-identical to the struct in blinn_phong.metal (offsets locked to MetalUniformStream.OFFSET_*)
struct Uniforms {
    float4x4 mvp;
    float4x4 model;
    float4 dirLight;
    float4 numLights;
    float4 dirLightColor;
    float4 ambientColor;
    float4 fogColorDensity;
    float4 matParams;
    uint materialMask;
    uint isUnlit;
    uint stride;
    uint passFlags;
    float4x4 lightSpace;
    float4 surface;
    float4 viewPos;
    uint pickId;
    constant LocalLights* localLights;
    float4 fogParams;
};

// Must stay byte-identical to the struct in blinn_phong.metal
struct DrawArgs {
    device const float* vertices;
    constant Uniforms* uniforms;
    constant float4x4* bones;
    texture2d<float> albedo;
    texture2d<float> normalMap;
    texture2d<float> specularMap;
    texture2d<float> lightmap;
    texture2d<float> emissive;
};

struct ShadowVertexOut {
    float4 position [[position]];
    float2 uv;
    uint drawIndex [[flat]];
};

vertex ShadowVertexOut shadowVertexMain(uint vertexID [[vertex_id]],
                                        uint drawIndex [[base_instance]],
                                        constant DrawArgs* draws [[buffer(0)]]) {
    constant DrawArgs& args = draws[drawIndex];
    constant Uniforms& uniforms = *args.uniforms;
    device const float* vertices = args.vertices;

    uint vertStride = uniforms.stride > 0 ? uniforms.stride : 22;
    uint idx = vertexID * vertStride;
    float4 localPos = float4(vertices[idx], vertices[idx+1], vertices[idx+2], 1.0);

    // Same linear blend skinning as vertexMain so skinned casters match their lit silhouettes
    if (vertStride == 30 && (uniforms.passFlags & 16u) != 0u) {
        device const uint* boneIds = reinterpret_cast<device const uint*>(vertices);
        constant float4x4* bones = args.bones;
        float4 w = float4(vertices[idx+26], vertices[idx+27], vertices[idx+28], vertices[idx+29]);
        float totalWeight = w.x + w.y + w.z + w.w;
        if (totalWeight > 0.001) {
            float4x4 skin = bones[min(boneIds[idx+22], 63u)] * w.x
                          + bones[min(boneIds[idx+23], 63u)] * w.y
                          + bones[min(boneIds[idx+24], 63u)] * w.z
                          + bones[min(boneIds[idx+25], 63u)] * w.w;
            localPos = (skin * (1.0 / totalWeight)) * localPos;
        }
    }

    ShadowVertexOut out;
    out.position = uniforms.mvp * localPos;
    if (vertStride == 22 || vertStride == 30 || vertStride == 14) {
        out.uv = float2(vertices[idx+10], vertices[idx+11]);
    } else {
        out.uv = float2(vertices[idx+7], vertices[idx+8]);
    }
    out.drawIndex = drawIndex;
    return out;
}

fragment void shadowFragmentMain(ShadowVertexOut in [[stage_in]],
                                 constant DrawArgs* draws [[buffer(0)]],
                                 sampler smp [[sampler(0)]]) {
    constant DrawArgs& args = draws[in.drawIndex];
    if ((args.uniforms->materialMask & 1u) != 0u && args.albedo.sample(smp, in.uv).a < 0.25) {
        discard_fragment();
    }
}
