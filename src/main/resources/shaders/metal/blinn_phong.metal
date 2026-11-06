#include <metal_stdlib>
using namespace metal;

// Lit 3D scene pass: 22-float static / 30-float skinned vertex strides, multi-light Blinn-Phong or Gouraud shading
// (Directional, Point, Spot), tangent-space normal mapping, specular/roughness, lightmap/AO and emissive
// channels, 3x3 PCF directional shadows, the procedural infinite grid, and ground shadow blobs.
//
// Every draw reads its resources from a Metal 3 argument buffer table bound once per encoder at buffer(0);
// the draw's [[base_instance]] selects its DrawArgs entry, so draws issue no per-draw binding calls.

// Per-frame local light block, written once per frame by MetalUniformStream.writeLightBlock and referenced from every
// draw's Uniforms.localLights. Array sizes are SceneLights.MAX_POINT_LIGHTS / MAX_SPOT_LIGHTS.
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

// Byte offsets are locked to MetalUniformStream.OFFSET_*; shadow_depth.metal and entity_id.metal declare the same struct.
struct Uniforms {
    float4x4 mvp;             // 0
    float4x4 model;           // 64
    float4 dirLight;          // 128: directional light xyz = direction, w = intensity
    float4 numLights;         // 144: x = directional (1.0 = active), y = point count, z = spot count
    float4 dirLightColor;     // 160: rgb = directional light color
    float4 ambientColor;      // 176: rgb = ambient color * intensity (ProjectSettings)
    float4 fogColorDensity;   // 192: rgb = fogColor, w = opacity at fog end (1 = fog on, 0 = fog disabled)
    float4 matParams;         // 208: x = reflectStrength, y = shadowOpacity, z = shadowMapBias
    uint materialMask;        // 224: bit 0 albedo, 1 normal, 2 specular, 3 lightmap, 4 emissive
    uint isUnlit;             // 228: 0 = Lit Model, 1 = Procedural Grid, 2 = Billboard Particles, 3 = Unlit Model
    uint stride;              // 232
    uint passFlags;           // 236: bit 0 shadowBlob, bit 1 reflection, bit 4 skinned
    float4x4 lightSpace;      // 240: directional shadow map view-projection
    float4 surface;           // 304: x = specularStrength, y = roughness, z = normalStrength, w = emissiveIntensity
    float4 viewPos;           // 320: xyz = camera world position
    uint pickId;              // 336: Color-ID pick value (read only by entity_id.metal)
    constant LocalLights* localLights; // 344: gpuAddress of this frame's LocalLights block
    float4 fogParams;         // 352: x = fog start, y = fog end, z = ramp exponent, w = shading model (0 = Blinn-Phong, 1 = Gouraud; 368 bytes total)
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
    float3 gouraudLightColor; // Gouraud mode only: per-vertex ambient + diffuse + specular
    uint drawIndex [[flat]];
};

// 3x3 PCF over the directional shadow map: 1.0 = fully lit, 0.0 = fully shadowed.
// Metal NDC y points up while texture v points down, hence the flipped v.
// The receiver is pushed along the geometric normal before projection (normal-offset bias). That moves it clear of
// its own depth texels on large, coarse faces like decimated meshes, where a depth-only bias either leaves acne or
// detaches the shadow. The offset is about one shadow texel in world units, scaled up at grazing angles.
float computeDirectionalShadow(float3 worldPos, float3 geomN, float4x4 lightSpace, float NdotL, float baseBias,
                               depth2d<float> shadowMap, sampler shadowSmp) {
    float2 mapSize = float2(shadowMap.get_width(), shadowMap.get_height());
    // The orthographic light matrix scales x by 1/radius, so its first row length gives the world-space texel size
    float invRadius = length(float3(lightSpace[0][0], lightSpace[1][0], lightSpace[2][0]));
    float texelWorld = 2.0 / (max(invRadius, 1e-6) * mapSize.x);
    float sinTheta = sqrt(max(1.0 - NdotL * NdotL, 0.0));
    float3 offsetPos = worldPos + geomN * (texelWorld * (0.5 + 1.5 * sinTheta));

    float4 lightSpacePos = lightSpace * float4(offsetPos, 1.0);
    float3 proj = lightSpacePos.xyz / lightSpacePos.w;
    if (proj.z > 1.0) return 1.0;

    float2 uv = float2(proj.x * 0.5 + 0.5, 0.5 - proj.y * 0.5);
    float bias = max(baseBias * (1.0 - NdotL), baseBias * 0.2);
    float refDepth = proj.z - bias;

    float2 texel = 1.0 / mapSize;
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

// Inverse-transpose of the model's upper 3x3, identical to blinn_phong.vert's transpose(inverse(mat3(model))).
// Columns of the cofactor matrix are the cross products of the model's columns; dividing by the determinant keeps the
// same magnitude as Vulkan, so the fragment stage's zero-length test behaves the same on tiny or huge scales.
static float3x3 normalMatrixOf(float4x4 model) {
    float3 c0 = model[0].xyz, c1 = model[1].xyz, c2 = model[2].xyz;
    float3 r0 = cross(c1, c2);
    float det = dot(c0, r0);
    if (det == 0.0) return float3x3(c0, c1, c2);
    return float3x3(r0, cross(c2, c0), cross(c0, c1)) * (1.0 / det);
}

// Adds one light's Lambert diffuse and Blinn-Phong specular. Specular is gated on the lit hemisphere so back faces
// never pick up highlights.
static void accumulateLight(float3 N, float3 V, float3 L, float3 radiance, float specularStrength, float shininess,
                            thread float3& diffuse, thread float3& specular) {
    float diff = max(dot(N, L), 0.0);
    diffuse += diff * radiance;
    if (diff > 0.0 && specularStrength > 0.0) {
        float3 H = normalize(L + V);
        specular += pow(max(dot(N, H), 0.0), shininess) * specularStrength * radiance;
    }
}

// Gouraud (per-vertex) lighting, matching blinn_phong.vert's computeGouraudLight: ambient + Lambert diffuse +
// Blinn-Phong specular over the same lights and attenuation as fragmentMain, evaluated once per vertex. A vertex cannot
// sample textures, so normal, specular/roughness and lightmap maps are skipped (scalar material values only) and the
// sun is unshadowed. Two-sided: a normal pointing away from the camera is turned toward it.
static float3 computeGouraudLight(float3 worldPos, float3 worldNormal, constant Uniforms& u) {
    float3 toEye = u.viewPos.xyz - worldPos;
    float3 V = (dot(toEye, toEye) > 1e-12) ? normalize(toEye) : float3(0.0, 1.0, 0.0);
    float3 N = (dot(worldNormal, worldNormal) > 1e-8) ? normalize(worldNormal) : float3(0.0, 1.0, 0.0);
    if (dot(N, V) < 0.0) N = -N;

    float specularStrength = u.surface.x;
    float roughness = clamp(u.surface.y, 0.0, 1.0);
    float shininess = exp2(10.0 * (1.0 - roughness) + 1.0);

    float3 diffuse = float3(0.0);
    float3 specular = float3(0.0);

    if (u.numLights.x > 0.5) {
        float3 L = normalize(-u.dirLight.xyz);
        accumulateLight(N, V, L, u.dirLightColor.rgb * u.dirLight.w, specularStrength, shininess, diffuse, specular);
    }

    constant LocalLights& localLights = *u.localLights;

    int pointCount = min(int(u.numLights.y), 32);
    for (int i = 0; i < pointCount; i++) {
        constant PointLight& light = localLights.pointLights[i];
        float3 lightVector = light.position.xyz - worldPos;
        float dist = length(lightVector);
        float3 L = normalize(lightVector);
        float attenuation = 1.0 / (1.0 + 0.1 * dist + 0.02 * dist * dist);
        accumulateLight(N, V, L, light.color.rgb * light.color.a * attenuation, specularStrength, shininess, diffuse, specular);
    }

    int spotCount = min(int(u.numLights.z), 8);
    for (int i = 0; i < spotCount; i++) {
        constant SpotLight& light = localLights.spotLights[i];
        float3 lightVector = light.position.xyz - worldPos;
        float dist = length(lightVector);
        float3 L = normalize(lightVector);
        float attenuation = 1.0 / (1.0 + 0.1 * dist + 0.02 * dist * dist);

        float3 spotDir = normalize(-light.direction.xyz);
        float theta = dot(L, spotDir);
        float innerCutoff = 0.93;
        float outerCutoff = 0.82;
        attenuation *= clamp((theta - outerCutoff) / (innerCutoff - outerCutoff), 0.0, 1.0);

        accumulateLight(N, V, L, light.color.rgb * light.color.a * attenuation, specularStrength, shininess, diffuse, specular);
    }

    // Same ambient floor as fragmentMain. Specular is folded into the light color, so the fragment stage's albedo
    // multiply tints highlights (fixed-function GL_MODULATE behaviour)
    float3 ambient = max(u.dirLightColor.rgb * 0.35, u.ambientColor.rgb);
    return ambient + diffuse + specular;
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

        // Left unnormalized, exactly like Vulkan: normalizing per vertex turns a zero normal into NaN under Metal's
        // fast math, and the fragment stage already normalizes after its zero-length test
        out.worldNormal = normalMatrixOf(uniforms.model) * localNorm;
        out.color = float4(vertices[idx+6], vertices[idx+7], vertices[idx+8], vertices[idx+9]);
        out.uv = float2(vertices[idx+10], vertices[idx+11]);
        out.uv2 = float2(vertices[idx+12], vertices[idx+13]);
        // Not normalized here: zero tangents mark meshes without tangent data for computeShadingNormal
        out.tangent = (uniforms.model * float4(localTan, 0.0)).xyz;
        out.bitangent = (uniforms.model * float4(localBitan, 0.0)).xyz;
    } else if (vertStride == 14) {
        float3 localNorm = float3(vertices[idx+3], vertices[idx+4], vertices[idx+5]);
        out.worldNormal = normalMatrixOf(uniforms.model) * localNorm;
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
    out.drawIndex = drawIndex;

    // Lighting is only needed by lit meshes in Gouraud mode; shadow blobs and the procedural grid skip it
    bool gouraudLit = uniforms.fogParams.w > 0.5 && (uniforms.passFlags & 1u) == 0u && uniforms.isUnlit == 0u;
    out.gouraudLightColor = gouraudLit ? computeGouraudLight(out.worldPos, out.worldNormal, uniforms) : float3(0.0);

    return out;
}

// Distance fog from ProjectSettings. Fog end is also the draw distance (entities beyond it are culled and the scene
// clears to the fog color), so the ramp is fully opaque there; the exponent (1 = linear .. 5 = thick, from fog
// density) eases it out so thick fog closes in well before the end.
static inline float fogAmount(constant Uniforms& uniforms, float viewDist) {
    float t = clamp((viewDist - uniforms.fogParams.x) / (uniforms.fogParams.y - uniforms.fogParams.x), 0.0, 1.0);
    float k = max(uniforms.fogParams.z, 1.0);
    return (1.0 - pow(max(1.0 - t, 1e-6), k)) * uniforms.fogColorDensity.w;
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
        // Blobs fade out with the fog so they never leave dark patches on it
        float alpha = max(0.0, 1.0 - distSq) * uniforms.matParams.y * (1.0 - fogAmount(uniforms, in.viewDist));
        return float4(0.02, 0.02, 0.05, alpha * 0.75);
    }

    // Mode 2: Camera-facing billboard particles (ParticleDrawList). Vertex color is premultiplied: (rgb * a, a) for
    // alpha blending, (rgb * a, 0) for additive; the pipeline blends ONE / ONE_MINUS_SRC_ALPHA.
    if (uniforms.isUnlit == 2u) {
        float4 tex;
        if ((uniforms.materialMask & MAT_ALBEDO) != 0u) {
            tex = args.albedo.sample(smp, in.uv);
        } else {
            float2 c = in.uv * 2.0 - 1.0;
            float soft = saturate(1.0 - dot(c, c));
            tex = float4(1.0, 1.0, 1.0, soft * soft);
        }
        float coverage = tex.a * (1.0 - fogAmount(uniforms, in.viewDist));
        if (coverage < 0.002) {
            discard_fragment();
        }
        return float4(in.color.rgb * tex.rgb * coverage, in.color.a * coverage);
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

    // Modes 0 and 3: Standard Opaque 3D Mesh, lit or unlit
    // Same rules as blinn_phong.frag: albedo alpha drives the cutout and output alpha, and vertex color tints rgb
    // only when it is non-black
    uint mask = uniforms.materialMask;
    float4 texColor = ((mask & MAT_ALBEDO) != 0u) ? args.albedo.sample(smp, in.uv) : float4(1.0);
    if (texColor.a < 0.25) {
        discard_fragment();
    }
    float4 baseColor = texColor;
    if (length(in.color.rgb) > 0.001) {
        baseColor.rgb *= in.color.rgb;
    }

    float3 V = normalize(uniforms.viewPos.xyz - in.worldPos);

    float3 geomN = (dot(in.worldNormal, in.worldNormal) > 1e-8) ? normalize(in.worldNormal) : float3(0.0, 1.0, 0.0);
    // Two-sided lighting, identical to blinn_phong.frag: the true face normal, turned toward the camera, decides
    // which side is being shaded, so a single leaf card or wall lights correctly from behind on both backends
    float3 faceN = cross(dfdx(in.worldPos), dfdy(in.worldPos));
    if (dot(faceN, faceN) > 1e-20) {
        if (dot(faceN, V) < 0.0) faceN = -faceN;
        if (dot(geomN, faceN) < 0.0) geomN = -geomN;
    }
    // Shading model (ProjectSettings.getShadingModel()) in fogParams.w: 0 = per-pixel Blinn-Phong, 1 = per-vertex Gouraud
    bool gouraud = uniforms.fogParams.w > 0.5;
    float3 N = geomN;
    float3 finalRGB;

    if (uniforms.isUnlit == 3u) {
        // Shading is authored into the texture (glTF KHR_materials_unlit); scene lights and ambient don't apply
        finalRGB = baseColor.rgb;
    } else if (gouraud) {
        // The vertex stage already lit the surface; albedo modulates the interpolated light color
        finalRGB = baseColor.rgb * in.gouraudLightColor;
    } else {
        N = computeShadingNormal(geomN, in, uniforms, args.normalMap, smp);

        // Specular/roughness map: R = specular mask, G = roughness multiplier (absent map = scalar material values)
        float4 specSample = ((mask & MAT_SPECULAR) != 0u) ? args.specularMap.sample(smp, in.uv) : float4(1.0);
        float specularStrength = uniforms.surface.x * specSample.r;
        float roughness = clamp(uniforms.surface.y * specSample.g, 0.0, 1.0);
        float shininess = exp2(10.0 * (1.0 - roughness) + 1.0); // 2 (rough) .. 2048 (mirror-like)

        // Lightmap / AO on the second UV set modulates the ambient (indirect) term
        float3 lightmap = ((mask & MAT_LIGHTMAP) != 0u) ? args.lightmap.sample(smp, in.uv2).rgb : float3(1.0);

        // Every active light accumulates into the same diffuse/specular sums in this single forward pass
        float3 numLights = uniforms.numLights.xyz;
        float3 diffuse = float3(0.0);
        float3 specular = float3(0.0);

        if (numLights.x > 0.5) {
            // Directional Light: both biases use the geometric normal so normal-map detail never causes acne
            float3 L = normalize(-uniforms.dirLight.xyz);
            float shadow = computeDirectionalShadow(in.worldPos, geomN, uniforms.lightSpace, clamp(dot(geomN, L), 0.0, 1.0),
                                                    uniforms.matParams.z, shadowMap, shadowSmp);
            float3 radiance = uniforms.dirLightColor.rgb * uniforms.dirLight.w * shadow;
            accumulateLight(N, V, L, radiance, specularStrength, shininess, diffuse, specular);
        }

        constant LocalLights& localLights = *uniforms.localLights;

        int pointCount = min(int(numLights.y), 32);
        for (int i = 0; i < pointCount; i++) {
            // Point Light
            constant PointLight& light = localLights.pointLights[i];
            float3 lightVector = light.position.xyz - in.worldPos;
            float dist = length(lightVector);
            float3 L = normalize(lightVector);
            float attenuation = 1.0 / (1.0 + 0.1 * dist + 0.02 * dist * dist);
            float3 radiance = light.color.rgb * light.color.a * attenuation;
            accumulateLight(N, V, L, radiance, specularStrength, shininess, diffuse, specular);
        }

        int spotCount = min(int(numLights.z), 8);
        for (int i = 0; i < spotCount; i++) {
            // Spot Light
            constant SpotLight& light = localLights.spotLights[i];
            float3 lightVector = light.position.xyz - in.worldPos;
            float dist = length(lightVector);
            float3 L = normalize(lightVector);
            float attenuation = 1.0 / (1.0 + 0.1 * dist + 0.02 * dist * dist);

            float3 spotDir = normalize(-light.direction.xyz);
            float theta = dot(L, spotDir);
            float innerCutoff = 0.93;
            float outerCutoff = 0.82;
            float spotIntensity = clamp((theta - outerCutoff) / (innerCutoff - outerCutoff), 0.0, 1.0);
            attenuation *= spotIntensity;

            float3 radiance = light.color.rgb * light.color.a * attenuation;
            accumulateLight(N, V, L, radiance, specularStrength, shininess, diffuse, specular);
        }

        // Matches blinn_phong.frag: the project ambient is the floor, and a bright sun lifts it (dirLightColor is zero
        // when there is no sun)
        float3 ambient = max(uniforms.dirLightColor.rgb * 0.35, uniforms.ambientColor.rgb) * lightmap;

        finalRGB = (ambient + diffuse) * baseColor.rgb + specular;
    }

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

    finalRGB = mix(finalRGB, uniforms.fogColorDensity.rgb, fogAmount(uniforms, in.viewDist));

    return float4(finalRGB, baseColor.a);
}
