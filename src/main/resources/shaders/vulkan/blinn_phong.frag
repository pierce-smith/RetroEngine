#version 450
#extension GL_EXT_nonuniform_qualifier : require

layout(location = 0) in vec3 vFragPos;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in vec2 vTexCoord;
layout(location = 3) in vec4 vColor;
layout(location = 4) in vec3 vTangent;
layout(location = 5) in vec3 vBitangent;
layout(location = 6) in float vViewDist;
layout(location = 7) in vec4 vLightSpacePos;
layout(location = 8) in vec2 vLightmapUV;

layout(location = 0) out vec4 outFragColor;

struct DirectionalLight {
    vec4 direction; // xyz = direction, w = intensity
    vec4 color;     // rgb = color, a = intensity
};

struct PointLight {
    vec4 position;  // xyz = position, w = padding
    vec4 color;     // rgb = color, a = intensity
    vec4 params;    // x = constant, y = linear, z = quadratic, w = padding
};

struct SpotLight {
    vec4 position;  // xyz = position, w = innerCutoff
    vec4 direction; // xyz = direction, w = outerCutoff
    vec4 color;     // rgb = color, a = intensity
    vec4 params;    // x = constant, y = linear, z = quadratic, w = padding
};

layout(std140, set = 0, binding = 0) uniform LightBuffer {
    DirectionalLight dirLight;
    PointLight pointLights;
    SpotLight spotLights;
    vec4 numLights;   // x = numDir, y = numPoint, z = numSpot, w = shadow map slope bias
    vec4 viewPos;     // xyz = camera position, w = groundY
    mat4 view;        // byte offset 176
    mat4 proj;        // byte offset 240
    mat4 lightSpaceMatrix; // byte offset 304: directional shadow map view-projection (368 bytes total)
} u_Lights;

// Shared linear/repeat sampler for every material channel
layout(set = 0, binding = 1) uniform sampler u_MaterialSampler;
// 2048x2048 D32 directional shadow map; comparison sampler (LESS_OR_EQUAL, clamp-to-border opaque white)
layout(set = 0, binding = 2) uniform sampler2DShadow u_ShadowMap;
// Bindless material texture table (partially bound). Slots come from push constants, so every index is
// dynamically uniform and needs no nonuniformEXT qualifier.
layout(set = 0, binding = 4) uniform texture2D u_Textures[];

// 128 bytes: must stay byte-identical to the block in blinn_phong.vert
layout(push_constant) uniform MeshPushConstants {
    mat4 model;
    vec4 params;       // x = passFlags (1.0 = shadow blob, 2.0 = shadow map depth), y = heightFade, z = reflection, w = emissiveIntensity
    vec4 surface;      // x = specularStrength, y = roughness, z = normalStrength, w = unused
    ivec4 texIndices;  // bindless slots: x = albedo, y = normal, z = specular/roughness, w = lightmap/AO (-1 = channel absent)
    ivec4 texIndices2; // x = emissive slot, y = bone palette slot, zw = unused
} u_PushConstants;

// Returns the fallback for absent channels; the branch is uniform across the draw, so implicit LOD stays valid
vec4 sampleChannel(int slot, vec2 uv, vec4 fallback) {
    if (slot < 0) return fallback;
    return texture(sampler2D(u_Textures[slot], u_MaterialSampler), uv);
}

// 3x3 PCF over the directional shadow map: 1.0 = fully lit, 0.0 = fully shadowed
float computeDirectionalShadow(float NdotL) {
    vec3 proj = vLightSpacePos.xyz / vLightSpacePos.w;
    if (proj.z > 1.0) return 1.0;

    // The shadow pass uses a positive-height viewport, so NDC maps straight to texture space
    vec2 uv = proj.xy * 0.5 + 0.5;
    float baseBias = u_Lights.numLights.w;
    float bias = max(baseBias * (1.0 - NdotL), baseBias * 0.2);
    float refDepth = proj.z - bias;

    vec2 texel = 1.0 / vec2(textureSize(u_ShadowMap, 0));
    float lit = 0.0;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            lit += texture(u_ShadowMap, vec3(uv + vec2(x, y) * texel, refDepth));
        }
    }
    return lit / 9.0;
}

// Tangent-space normal mapping. The tangent is Gram-Schmidt re-orthogonalized against the interpolated normal and
// the bitangent only contributes its handedness, so mirrored UV islands shade correctly. Meshes without tangent
// data (zero vectors) keep the geometric normal.
vec3 computeShadingNormal(vec3 N) {
    int normalIdx = u_PushConstants.texIndices.y;
    if (normalIdx < 0) return N;

    vec3 T = vTangent - N * dot(N, vTangent);
    if (dot(T, T) < 1e-8) return N;
    T = normalize(T);

    float handedness = (dot(cross(N, T), vBitangent) < 0.0) ? -1.0 : 1.0;
    vec3 B = cross(N, T) * handedness;

    vec3 tangentNormal = texture(sampler2D(u_Textures[normalIdx], u_MaterialSampler), vTexCoord).xyz * 2.0 - 1.0;
    tangentNormal.xy *= u_PushConstants.surface.z;
    return normalize(mat3(T, B, N) * tangentNormal);
}

void main() {
    // Dynamic Ground Shadow Blob Pass (PassFlags == 1.0)
    if (u_PushConstants.params.x > 0.5) {
        vec2 uvCentered = vTexCoord - vec2(0.5, 0.5);
        float distSq = dot(uvCentered, uvCentered) * 4.0;
        if (distSq >= 1.0) {
            discard;
        }
        float heightFade = u_PushConstants.params.y;
        float alpha = max(0.0, 1.0 - distSq) * heightFade * 0.75;
        outFragColor = vec4(0.02, 0.02, 0.05, alpha);
        return;
    }

    vec4 texColor = sampleChannel(u_PushConstants.texIndices.x, vTexCoord, vec4(1.0));

    // Foliage / Leaf Billboard Alpha Cutout Masking (Discard transparent empty space)
    if (texColor.a < 0.25) {
        discard;
    }

    vec4 baseColor = vec4(texColor.rgb, texColor.a);
    if (length(vColor.rgb) > 0.001) {
        baseColor.rgb *= vColor.rgb;
    }

    vec3 geomN = (dot(vNormal, vNormal) > 1e-8) ? normalize(vNormal) : vec3(0.0, 1.0, 0.0);
    vec3 N = computeShadingNormal(geomN);

    // Specular/roughness map: R = specular mask, G = roughness multiplier (absent map = scalar material values)
    vec4 specSample = sampleChannel(u_PushConstants.texIndices.z, vTexCoord, vec4(1.0));
    float specularStrength = u_PushConstants.surface.x * specSample.r;
    float roughness = clamp(u_PushConstants.surface.y * specSample.g, 0.0, 1.0);
    float shininess = exp2(10.0 * (1.0 - roughness) + 1.0); // 2 (rough) .. 2048 (mirror-like)

    // Lightmap / AO on the second UV set modulates the ambient (indirect) term
    vec3 lightmap = sampleChannel(u_PushConstants.texIndices.w, vLightmapUV, vec4(1.0)).rgb;

    vec3 V = normalize(u_Lights.viewPos.xyz - vFragPos);

    vec3 L = vec3(0.0, 1.0, 0.0);
    float attenuation = 1.0;
    float shadow = 1.0;
    vec3 lightColor = vec3(1.0, 1.0, 1.0);
    float intensity = 1.0;
    bool hasLight = false;

    if (u_Lights.numLights.z > 0.5) {
        // Spot Light
        vec3 lightVector = u_Lights.spotLights.position.xyz - vFragPos;
        float dist = length(lightVector);
        L = normalize(lightVector);
        attenuation = 1.0 / (1.0 + 0.1 * dist + 0.02 * dist * dist);

        vec3 spotDir = normalize(-u_Lights.spotLights.direction.xyz);
        float theta = dot(L, spotDir);
        float innerCutoff = u_Lights.spotLights.position.w > 0.0 ? u_Lights.spotLights.position.w : 0.93;
        float outerCutoff = u_Lights.spotLights.direction.w > 0.0 ? u_Lights.spotLights.direction.w : 0.82;
        float spotIntensity = clamp((theta - outerCutoff) / max(innerCutoff - outerCutoff, 0.0001), 0.0, 1.0);
        attenuation *= spotIntensity;

        lightColor = u_Lights.spotLights.color.rgb;
        intensity = u_Lights.spotLights.color.a;
        hasLight = true;
    } else if (u_Lights.numLights.y > 0.5) {
        // Point Light
        vec3 lightVector = u_Lights.pointLights.position.xyz - vFragPos;
        float dist = length(lightVector);
        L = normalize(lightVector);
        attenuation = 1.0 / (1.0 + 0.1 * dist + 0.02 * dist * dist);

        lightColor = u_Lights.pointLights.color.rgb;
        intensity = u_Lights.pointLights.color.a;
        hasLight = true;
    } else if (u_Lights.numLights.x > 0.5) {
        // Directional Light: the bias uses the geometric normal so normal-map detail never causes acne
        L = normalize(-u_Lights.dirLight.direction.xyz);
        shadow = computeDirectionalShadow(clamp(dot(geomN, L), 0.0, 1.0));
        lightColor = u_Lights.dirLight.color.rgb;
        intensity = u_Lights.dirLight.color.a;
        hasLight = true;
    }

    vec3 radiance = lightColor * intensity * attenuation * shadow;

    float diff = max(dot(N, L), 0.0);
    vec3 diffuse = diff * radiance;

    // Blinn-Phong specular, gated on the lit hemisphere so back faces never pick up highlights
    vec3 specular = vec3(0.0);
    if (hasLight && diff > 0.0 && specularStrength > 0.0) {
        vec3 H = normalize(L + V);
        specular = pow(max(dot(N, H), 0.0), shininess) * specularStrength * radiance;
    }

    vec3 ambient = max(u_Lights.dirLight.color.rgb * 0.35, vec3(0.35, 0.35, 0.38)) * lightmap;

    vec3 finalRGB = (ambient + diffuse) * baseColor.rgb + specular;

    // Hemisphere Sky/Ground Environment Reflection Pass
    float reflectStrength = u_PushConstants.params.z;
    if (reflectStrength > 0.0) {
        vec3 R = reflect(-V, N);
        vec3 envSky = vec3(0.85, 0.90, 1.0);
        vec3 envGround = vec3(0.15, 0.20, 0.30);
        vec3 envColor = mix(envGround, envSky, clamp(R.y * 0.5 + 0.5, 0.0, 1.0));
        finalRGB = mix(finalRGB, envColor, clamp(reflectStrength, 0.0, 1.0) * 0.4);
    }

    // Emissive is unlit and unshadowed, but still fogs with distance like everything else
    finalRGB += sampleChannel(u_PushConstants.texIndices2.x, vTexCoord, vec4(0.0)).rgb * u_PushConstants.params.w;

    // Distance Fog Attenuation (20m to 150m)
    float fogStart = 20.0;
    float fogEnd = 150.0;
    float fogFactor = clamp((vViewDist - fogStart) / (fogEnd - fogStart), 0.0, 1.0);
    vec3 fogColor = vec3(0.12, 0.13, 0.16);
    finalRGB = mix(finalRGB, fogColor, fogFactor);

    outFragColor = vec4(finalRGB, baseColor.a);
}
