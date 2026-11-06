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
layout(location = 9) in vec3 v_GouraudLightColor; // per-vertex lighting, read only in Gouraud mode

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
    PointLight pointLights[32]; // byte offset 32: SceneLights.MAX_POINT_LIGHTS
    SpotLight spotLights[8];    // byte offset 1568: SceneLights.MAX_SPOT_LIGHTS
    vec4 numLights;   // byte offset 2080: x = directional (1.0 = active), y = point count, z = spot count, w = shadow map slope bias
    vec4 viewPos;     // byte offset 2096: xyz = camera position, w = groundY
    mat4 view;        // byte offset 2112
    mat4 proj;        // byte offset 2176
    mat4 lightSpaceMatrix; // byte offset 2240: directional shadow map view-projection
    mat4 previewViewProj;  // byte offset 2304: selected Camera entity's view-projection (picture-in-picture pass)
    vec4 previewViewPos;   // byte offset 2368: xyz = that camera's world position
    vec4 ambientColor;     // byte offset 2384: rgb = ProjectSettings ambient color * intensity
    vec4 fogColorDensity;  // byte offset 2400: rgb = fog color, w = opacity at fog end (1 = fog on, 0 = off)
    vec3 fogParams;        // byte offset 2416: x = fog start, y = fog end, z = ramp exponent
    float shadingModel;    // byte offset 2428: u_ShadingModel, ProjectSettings.getShadingModel() (0 = Blinn-Phong, 1 = Gouraud; 2432 bytes total)
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
    vec4 surface;      // x = specularStrength, y = roughness, z = normalStrength, w = unlit (1.0 = skip scene lighting)
    ivec4 texIndices;  // bindless slots: x = albedo, y = normal, z = specular/roughness, w = lightmap/AO (-1 = channel absent)
    ivec4 texIndices2; // x = emissive slot, y = bone palette slot, z = camera view (0 = editor, 1 = camera preview), w = unused
} u_PushConstants;

// Returns the fallback for absent channels; the branch is uniform across the draw, so implicit LOD stays valid
vec4 sampleChannel(int slot, vec2 uv, vec4 fallback) {
    if (slot < 0) return fallback;
    return texture(sampler2D(u_Textures[slot], u_MaterialSampler), uv);
}

// 3x3 PCF over the directional shadow map: 1.0 = fully lit, 0.0 = fully shadowed.
// The receiver is pushed along the geometric normal before projection (normal-offset bias). That moves it clear of
// its own depth texels on large, coarse faces like decimated meshes, where a depth-only bias either leaves acne or
// detaches the shadow. The offset is about one shadow texel in world units, scaled up at grazing angles.
float computeDirectionalShadow(vec3 worldPos, vec3 geomN, float NdotL) {
    vec2 mapSize = vec2(textureSize(u_ShadowMap, 0));
    mat4 lightSpace = u_Lights.lightSpaceMatrix;
    // The orthographic light matrix scales x by 1/radius, so its first row length gives the world-space texel size
    float invRadius = length(vec3(lightSpace[0][0], lightSpace[1][0], lightSpace[2][0]));
    float texelWorld = 2.0 / (max(invRadius, 1e-6) * mapSize.x);
    float sinTheta = sqrt(max(1.0 - NdotL * NdotL, 0.0));
    vec3 offsetPos = worldPos + geomN * (texelWorld * (0.5 + 1.5 * sinTheta));

    vec4 lightSpacePos = lightSpace * vec4(offsetPos, 1.0);
    vec3 proj = lightSpacePos.xyz / lightSpacePos.w;
    if (proj.z > 1.0) return 1.0;

    // The shadow pass uses a positive-height viewport, so NDC maps straight to texture space
    vec2 uv = proj.xy * 0.5 + 0.5;
    float baseBias = u_Lights.numLights.w;
    float bias = max(baseBias * (1.0 - NdotL), baseBias * 0.2);
    float refDepth = proj.z - bias;

    vec2 texel = 1.0 / mapSize;
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

// Adds one light's Lambert diffuse and Blinn-Phong specular. Specular is gated on the lit hemisphere so back faces
// never pick up highlights.
void accumulateLight(vec3 N, vec3 V, vec3 L, vec3 radiance, float specularStrength, float shininess,
                     inout vec3 diffuse, inout vec3 specular) {
    float diff = max(dot(N, L), 0.0);
    diffuse += diff * radiance;
    if (diff > 0.0 && specularStrength > 0.0) {
        vec3 H = normalize(L + V);
        specular += pow(max(dot(N, H), 0.0), shininess) * specularStrength * radiance;
    }
}

// Distance fog from ProjectSettings. Fog end is also the draw distance (entities beyond it are culled and the scene
// clears to the fog color), so the ramp is fully opaque there; the exponent (1 = linear .. 5 = thick, from fog
// density) eases it out so thick fog closes in well before the end.
float fogAmount(float viewDist) {
    float t = clamp((viewDist - u_Lights.fogParams.x) / (u_Lights.fogParams.y - u_Lights.fogParams.x), 0.0, 1.0);
    float k = max(u_Lights.fogParams.z, 1.0);
    return (1.0 - pow(max(1.0 - t, 1e-6), k)) * u_Lights.fogColorDensity.w;
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
        // Blobs fade out with the fog so they never leave dark patches on it
        float alpha = max(0.0, 1.0 - distSq) * heightFade * 0.75 * (1.0 - fogAmount(vViewDist));
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

    vec3 eyePos = (u_PushConstants.texIndices2.z == 1) ? u_Lights.previewViewPos.xyz : u_Lights.viewPos.xyz;
    vec3 V = normalize(eyePos - vFragPos);

    vec3 geomN = (dot(vNormal, vNormal) > 1e-8) ? normalize(vNormal) : vec3(0.0, 1.0, 0.0);
    // Two-sided lighting: the true face normal, turned toward the camera, decides which side is being shaded, so a
    // single leaf card or wall lights correctly from behind. Independent of winding, so both backends agree.
    vec3 faceN = cross(dFdx(vFragPos), dFdy(vFragPos));
    if (dot(faceN, faceN) > 1e-20) {
        if (dot(faceN, V) < 0.0) faceN = -faceN;
        if (dot(geomN, faceN) < 0.0) geomN = -geomN;
    }
    // u_ShadingModel (ProjectSettings.getShadingModel()): 0 = per-pixel Blinn-Phong, 1 = per-vertex Gouraud
    bool gouraud = u_Lights.shadingModel > 0.5;
    vec3 N = geomN;
    vec3 finalRGB;

    if (u_PushConstants.surface.w > 0.5) {
        // Shading is authored into the texture (glTF KHR_materials_unlit); scene lights and ambient don't apply
        finalRGB = baseColor.rgb;
    } else if (gouraud) {
        // The vertex stage already lit the surface; albedo modulates the interpolated light color
        finalRGB = baseColor.rgb * v_GouraudLightColor;
    } else {
        N = computeShadingNormal(geomN);

        // Specular/roughness map: R = specular mask, G = roughness multiplier (absent map = scalar material values)
        vec4 specSample = sampleChannel(u_PushConstants.texIndices.z, vTexCoord, vec4(1.0));
        float specularStrength = u_PushConstants.surface.x * specSample.r;
        float roughness = clamp(u_PushConstants.surface.y * specSample.g, 0.0, 1.0);
        float shininess = exp2(10.0 * (1.0 - roughness) + 1.0); // 2 (rough) .. 2048 (mirror-like)

        // Lightmap / AO on the second UV set modulates the ambient (indirect) term
        vec3 lightmap = sampleChannel(u_PushConstants.texIndices.w, vLightmapUV, vec4(1.0)).rgb;

        // Every active light accumulates into the same diffuse/specular sums in this single forward pass
        vec3 diffuse = vec3(0.0);
        vec3 specular = vec3(0.0);

        if (u_Lights.numLights.x > 0.5) {
            // Directional Light: both biases use the geometric normal so normal-map detail never causes acne
            vec3 L = normalize(-u_Lights.dirLight.direction.xyz);
            float shadow = computeDirectionalShadow(vFragPos, geomN, clamp(dot(geomN, L), 0.0, 1.0));
            vec3 radiance = u_Lights.dirLight.color.rgb * u_Lights.dirLight.color.a * shadow;
            accumulateLight(N, V, L, radiance, specularStrength, shininess, diffuse, specular);
        }

        int pointCount = min(int(u_Lights.numLights.y), 32);
        for (int i = 0; i < pointCount; i++) {
            // Point Light
            PointLight light = u_Lights.pointLights[i];
            vec3 lightVector = light.position.xyz - vFragPos;
            float dist = length(lightVector);
            vec3 L = normalize(lightVector);
            float attenuation = 1.0 / (1.0 + 0.1 * dist + 0.02 * dist * dist);
            vec3 radiance = light.color.rgb * light.color.a * attenuation;
            accumulateLight(N, V, L, radiance, specularStrength, shininess, diffuse, specular);
        }

        int spotCount = min(int(u_Lights.numLights.z), 8);
        for (int i = 0; i < spotCount; i++) {
            // Spot Light
            SpotLight light = u_Lights.spotLights[i];
            vec3 lightVector = light.position.xyz - vFragPos;
            float dist = length(lightVector);
            vec3 L = normalize(lightVector);
            float attenuation = 1.0 / (1.0 + 0.1 * dist + 0.02 * dist * dist);

            vec3 spotDir = normalize(-light.direction.xyz);
            float theta = dot(L, spotDir);
            float innerCutoff = light.position.w > 0.0 ? light.position.w : 0.93;
            float outerCutoff = light.direction.w > 0.0 ? light.direction.w : 0.82;
            float spotIntensity = clamp((theta - outerCutoff) / max(innerCutoff - outerCutoff, 0.0001), 0.0, 1.0);
            attenuation *= spotIntensity;

            vec3 radiance = light.color.rgb * light.color.a * attenuation;
            accumulateLight(N, V, L, radiance, specularStrength, shininess, diffuse, specular);
        }

        // Project ambient is the floor; a bright sun lifts it (dirLight.color is zero when there is no sun)
        vec3 ambient = max(u_Lights.dirLight.color.rgb * 0.35, u_Lights.ambientColor.rgb) * lightmap;

        finalRGB = (ambient + diffuse) * baseColor.rgb + specular;
    }

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

    finalRGB = mix(finalRGB, u_Lights.fogColorDensity.rgb, fogAmount(vViewDist));

    outFragColor = vec4(finalRGB, baseColor.a);
}
