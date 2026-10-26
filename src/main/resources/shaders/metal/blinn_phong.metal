#include <metal_stdlib>
using namespace metal;

// Lit 3D scene pass: 22-float static / 30-float skinned vertex strides, multi-light Blinn-Phong shading
// (Directional, Point, Spot), tangent-space normal mapping, specular/roughness, lightmap/AO and emissive
// channels, 3x3 PCF directional shadows, the procedural infinite grid, and ground shadow blobs.
//
// Every draw reads its resources from a Metal 3 argument buffer table bound once per encoder at buffer(0);
// the draw's [[base_instance]] selects its DrawArgs entry, so draws issue no per-draw binding calls.

// Byte offsets are locked to MetalUniformStream.OFFSET_*; shadow_depth.metal declares the same struct.
struct Uniforms {
    float4x4 mvp;             // 0
    float4x4 model;           // 64
    float4 lightDirIntensity; // 128: xyz = lightDir, w = intensity
    float4 lightPosType;      // 144: xyz = lightPos, w = lightType (0 = DIR, 1 = POINT, 2 = SPOT)
    float4 lightColor;        // 160: rgb = lightColor
    float4 ambientColor;      // 176: rgb = ambient
    float4 fogColorDensity;   // 192: rgb = fogColor, w = fogDensity
    float4 matParams;         // 208: x = reflectStrength, y = shadowOpacity, z = shadowMapBias
    uint materialMask;        // 224: bit 0 albedo, 1 normal, 2 specular, 3 lightmap, 4 emissive
    uint isUnlit;             // 228: 0 = Lit Model, 1 = Procedural Grid
    uint stride;              // 232
    uint passFlags;           // 236: bit 0 shadowBlob, bit 1 reflection, bit 4 skinned
    float4x4 lightSpace;      // 240: directional shadow map view-projection
    float4 surface;           // 304: x = specularStrength, y = roughness, z = normalStrength, w = emissiveIntensity
    float4 viewPos;           // 320: xyz = camera world position
};

// 64-byte argument buffer entry written by MetalPassEncoder (MetalUniformStream.DRAW_ARGS_*).
// Pointers are MTLBuffer.gpuAddress values; textures are MTLTexture.gpuResourceID values. Absent material
// channels point at the default white texture and are skipped through Uniforms.materialMask.
struct DrawArgs {
    device const float* vertices;   // 0
    constant Uniforms* uniforms;    // 8
    constant float4x4* bones;       // 16
    texture2d<float> albedo;        // 24
    texture2d<float> normalMap;     // 32
    texture2d<float> specularMap;   // 40
    texture2d<float> lightmap;      // 48
    texture2d<float> emissive;      // 56
};

constant uint MAT_ALBEDO   = 1u;
constant uint MAT_NORMAL   = 2u;
constant uint MAT_SPECULAR = 4u;
constant uint MAT_LIGHTMAP = 8u;
constant uint MAT_EMISSIVE = 16u;

struct VertexOut {
    float4 position [[position]];
    float3 worldNormal;
    float3 worldPos;
    float4 color;
    float2 uv;
    float2 uv2;
    float3 tangent;
    float3 bitangent;
    float viewDist;
    float4 lightSpacePos;
    uint drawIndex [[flat]];
};

// 3x3 PCF over the directional shadow map: 1.0 = fully lit, 0.0 = fully shadowed.
// Metal NDC y points up while texture v points down, hence the flipped v.
float computeDirectionalShadow(float4 lightSpacePos, float NdotL, float baseBias,
                               depth2d<float> shadowMap, sampler shadowSmp) {
    float3 proj = lightSpacePos.xyz / lightSpacePos.w;
    if (proj.z > 1.0) return 1.0;

    float2 uv = float2(proj.x * 0.5 + 0.5, 0.5 - proj.y * 0.5);
    float bias = max(baseBias * (1.0 - NdotL), baseBias * 0.2);
    float refDepth = proj.z - bias;

    float2 texel = 1.0 / float2(shadowMap.get_width(), shadowMap.get_height());
    float lit = 0.0;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            lit += shadowMap.sample_compare(shadowSmp, uv + float2(x, y) * texel, refDepth);
        }
    }
    return lit / 9.0;
}

// Tangent-space normal mapping. The tangent is Gram-Schmidt re-orthogonalized against the interpolated normal and
// the bitangent only contributes its handedness, so mirrored UV islands shade correctly. Meshes without tangent
// data (zero vectors) keep the geometric normal.
float3 computeShadingNormal(float3 N, VertexOut in, constant Uniforms& u,
                            texture2d<float> normalMap, sampler smp) {
    if ((u.materialMask & MAT_NORMAL) == 0u) return N;

    float3 T = in.tangent - N * dot(N, in.tangent);
    if (dot(T, T) < 1e-8) return N;
    T = normalize(T);

    float handedness = (dot(cross(N, T), in.bitangent) < 0.0) ? -1.0 : 1.0;
    float3 B = cross(N, T) * handedness;

    float3 tangentNormal = normalMap.sample(smp, in.uv).xyz * 2.0 - 1.0;
    tangentNormal.xy *= u.surface.z;
    return normalize(float3x3(T, B, N) * tangentNormal);
}

vertex VertexOut vertexMain(uint vertexID [[vertex_id]],
                            uint drawIndex [[base_instance]],
                            constant DrawArgs* draws [[buffer(0)]]) {
    constant DrawArgs& args = draws[drawIndex];
    constant Uniforms& uniforms = *args.uniforms;
    device const float* vertices = args.vertices;

    VertexOut out;
    uint vertStride = uniforms.stride > 0 ? uniforms.stride : 22;
    uint idx = vertexID * vertStride;

    float4 localPos = float4(vertices[idx], vertices[idx+1], vertices[idx+2], 1.0);

    if (vertStride == 22 || vertStride == 30) {
        float3 localNorm = float3(vertices[idx+3], vertices[idx+4], vertices[idx+5]);
        float3 localTan = float3(vertices[idx+14], vertices[idx+15], vertices[idx+16]);
        float3 localBitan = float3(vertices[idx+17], vertices[idx+18], vertices[idx+19]);

        // Linear blend skinning: bone indices are stored as raw uint bit patterns in floats 22..25
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
                skin = skin * (1.0 / totalWeight);
                localPos = skin * localPos;
                localNorm = (skin * float4(localNorm, 0.0)).xyz;
                localTan = (skin * float4(localTan, 0.0)).xyz;
                localBitan = (skin * float4(localBitan, 0.0)).xyz;
            }
        }

        out.worldNormal = normalize((uniforms.model * float4(localNorm, 0.0)).xyz);
        out.color = float4(vertices[idx+6], vertices[idx+7], vertices[idx+8], vertices[idx+9]);
        out.uv = float2(vertices[idx+10], vertices[idx+11]);
        out.uv2 = float2(vertices[idx+12], vertices[idx+13]);
        // Not normalized here: zero tangents mark meshes without tangent data for computeShadingNormal
        out.tangent = (uniforms.model * float4(localTan, 0.0)).xyz;
        out.bitangent = (uniforms.model * float4(localBitan, 0.0)).xyz;
    } else if (vertStride == 14) {
        float3 localNorm = float3(vertices[idx+3], vertices[idx+4], vertices[idx+5]);
        out.worldNormal = normalize((uniforms.model * float4(localNorm, 0.0)).xyz);
        out.color = float4(vertices[idx+6], vertices[idx+7], vertices[idx+8], vertices[idx+9]);
        out.uv = float2(vertices[idx+10], vertices[idx+11]);
        out.uv2 = float2(vertices[idx+12], vertices[idx+13]);
        out.tangent = float3(0.0);
        out.bitangent = float3(0.0);
    } else {
        out.worldNormal = float3(0.0, 1.0, 0.0);
        out.color = float4(vertices[idx+3], vertices[idx+4], vertices[idx+5], vertices[idx+6]);
        out.uv = float2(vertices[idx+7], vertices[idx+8]);
        out.uv2 = out.uv;
        out.tangent = float3(0.0);
        out.bitangent = float3(0.0);
    }

    out.position = uniforms.mvp * localPos;
    out.worldPos = (uniforms.model * localPos).xyz;
    out.viewDist = out.position.w;
    out.lightSpacePos = uniforms.lightSpace * float4(out.worldPos, 1.0);
    out.drawIndex = drawIndex;

    return out;
}

fragment float4 fragmentMain(VertexOut in [[stage_in]],
                             constant DrawArgs* draws [[buffer(0)]],
                             sampler smp [[sampler(0)]],
                             depth2d<float> shadowMap [[texture(1)]],
                             sampler shadowSmp [[sampler(1)]]) {
    constant DrawArgs& args = draws[in.drawIndex];
    constant Uniforms& uniforms = *args.uniforms;

    // Pass Flag Bit 0: Ground Dynamic Shadow Blob Pass
    if ((uniforms.passFlags & 1u) != 0u) {
        float2 uvCentered = in.uv - float2(0.5, 0.5);
        float distSq = dot(uvCentered, uvCentered) * 4.0;
        if (distSq >= 1.0) {
            discard_fragment();
        }
        float alpha = max(0.0, 1.0 - distSq) * uniforms.matParams.y;
        return float4(0.02, 0.02, 0.05, alpha * 0.75);
    }

    // Mode 1: Procedural Ray-Plane Anti-Aliased Infinite Shader Grid
    if (uniforms.isUnlit == 1u) {
        if (in.worldPos.y > 0.05) {
            return float4(0.2, 1.0, 0.2, 1.0); // Vibrant Green Y-Axis Line
        }

        float2 coord = in.worldPos.xz;
        float2 der = fwidth(coord);
        float2 safeDer = max(der, float2(0.00001, 0.00001));

        // 1-Meter Minor Grid Lines
        float2 grid1 = abs(fract(coord - 0.5) - 0.5) / safeDer;
        float line1 = min(grid1.x, grid1.y);
        float c1 = 1.0 - min(line1, 1.0);

        // 10-Meter Major Grid Subdivisions
        float2 grid10 = abs(fract((coord / 10.0) - 0.5) - 0.5) / (safeDer / 10.0);
        float line10 = min(grid10.x, grid10.y);
        float c10 = 1.0 - min(line10, 1.0);

        // Primary Axes (Red X-Axis at Z=0, Blue Z-Axis at X=0)
        bool isXAxis = abs(in.worldPos.z) < max(der.y * 1.5, 0.08);
        bool isZAxis = abs(in.worldPos.x) < max(der.x * 1.5, 0.08);

        float4 gridColor = float4(0.45, 0.48, 0.55, 0.35 * c1); // Minor 1m lines
        if (c10 > 0.01) {
            gridColor = mix(gridColor, float4(0.75, 0.80, 0.90, 0.70), c10); // Major 10m lines
        }
        if (isXAxis) {
            gridColor = float4(0.95, 0.25, 0.25, 0.95); // Red X-Axis
        }
        if (isZAxis) {
            gridColor = float4(0.25, 0.55, 0.95, 0.95); // Blue Z-Axis
        }

        if (gridColor.a < 0.01) {
            discard_fragment();
        }

        // Distance Horizon Fog Attenuation
        float dist = length(in.worldPos.xz);
        float fade = clamp(1.0 - (dist / 450.0), 0.0, 1.0);
        gridColor.a *= fade;

        return gridColor;
    }

    // Mode 0: Standard Opaque 3D Mesh Lighting
    uint mask = uniforms.materialMask;
    float4 baseColor = in.color;
    if ((mask & MAT_ALBEDO) != 0u) {
        float4 texColor = args.albedo.sample(smp, in.uv);
        if (texColor.a < 0.25) {
            discard_fragment();
        }
        baseColor *= texColor;
    }

    float3 geomN = (dot(in.worldNormal, in.worldNormal) > 1e-8) ? normalize(in.worldNormal) : float3(0.0, 1.0, 0.0);
    float3 N = computeShadingNormal(geomN, in, uniforms, args.normalMap, smp);

    // Specular/roughness map: R = specular mask, G = roughness multiplier (absent map = scalar material values)
    float4 specSample = ((mask & MAT_SPECULAR) != 0u) ? args.specularMap.sample(smp, in.uv) : float4(1.0);
    float specularStrength = uniforms.surface.x * specSample.r;
    float roughness = clamp(uniforms.surface.y * specSample.g, 0.0, 1.0);
    float shininess = exp2(10.0 * (1.0 - roughness) + 1.0); // 2 (rough) .. 2048 (mirror-like)

    // Lightmap / AO on the second UV set modulates the ambient (indirect) term
    float3 lightmap = ((mask & MAT_LIGHTMAP) != 0u) ? args.lightmap.sample(smp, in.uv2).rgb : float3(1.0);

    float3 V = normalize(uniforms.viewPos.xyz - in.worldPos);

    int lightType = int(uniforms.lightPosType.w);
    float3 L;
    float attenuation = 1.0;
    float shadow = 1.0;

    if (lightType == 0) {
        // The bias uses the geometric normal so normal-map detail never causes acne
        L = normalize(-uniforms.lightDirIntensity.xyz);
        shadow = computeDirectionalShadow(in.lightSpacePos, clamp(dot(geomN, L), 0.0, 1.0),
                                          uniforms.matParams.z, shadowMap, shadowSmp);
    } else {
        float3 lightPos = uniforms.lightPosType.xyz;
        float3 lightVector = lightPos - in.worldPos;
        float dist = length(lightVector);
        L = normalize(lightVector);
        attenuation = 1.0 / (1.0 + 0.1 * dist + 0.02 * dist * dist);

        if (lightType == 2) {
            float3 spotDir = normalize(-uniforms.lightDirIntensity.xyz);
            float theta = dot(L, spotDir);
            float innerCutoff = 0.93;
            float outerCutoff = 0.82;
            float spotIntensity = clamp((theta - outerCutoff) / (innerCutoff - outerCutoff), 0.0, 1.0);
            attenuation *= spotIntensity;
        }
    }

    float3 radiance = uniforms.lightColor.rgb * uniforms.lightDirIntensity.w * attenuation * shadow;

    float diff = max(dot(N, L), 0.0);
    float3 diffuse = diff * radiance;

    // Blinn-Phong specular, gated on the lit hemisphere so back faces never pick up highlights
    float3 specular = float3(0.0);
    if (diff > 0.0 && specularStrength > 0.0) {
        float3 H = normalize(L + V);
        specular = pow(max(dot(N, H), 0.0), shininess) * specularStrength * radiance;
    }

    float3 ambient = max(uniforms.ambientColor.rgb, float3(0.35, 0.35, 0.38)) * lightmap;

    float3 finalRGB = (ambient + diffuse) * baseColor.rgb + specular;

    // Hemisphere Sky/Ground Environment Reflection Pass
    if ((uniforms.passFlags & 2u) != 0u || uniforms.matParams.x > 0.0) {
        float3 R = reflect(-V, N);
        float3 envSky = float3(0.85, 0.90, 1.0);
        float3 envGround = float3(0.15, 0.20, 0.30);
        float3 envColor = mix(envGround, envSky, clamp(R.y * 0.5 + 0.5, 0.0, 1.0));
        float reflectStrength = clamp(uniforms.matParams.x, 0.0, 1.0);
        finalRGB = mix(finalRGB, envColor, reflectStrength * 0.4);
    }

    // Emissive is unlit and unshadowed, but still fogs with distance like everything else
    if ((mask & MAT_EMISSIVE) != 0u) {
        finalRGB += args.emissive.sample(smp, in.uv).rgb * uniforms.surface.w;
    }

    float fogStart = 20.0;
    float fogEnd = 150.0;
    float fogFactor = clamp((in.viewDist - fogStart) / (fogEnd - fogStart), 0.0, 1.0);
    float3 fogColor = float3(0.12, 0.13, 0.16);
    finalRGB = mix(finalRGB, fogColor, fogFactor);

    return float4(finalRGB, baseColor.a);
}
