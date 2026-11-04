#version 450

// 30-float skinned vertex layout (120-byte stride): blinn_phong.vert attributes + 4 bone indices + 4 bone weights
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec4 aColor;
layout(location = 3) in vec2 aTexCoord;
layout(location = 4) in vec2 aLightmapUV;
layout(location = 5) in vec3 aTangent;
layout(location = 6) in vec3 aBitangent;
layout(location = 7) in uvec4 aBoneIds;    // raw int bit patterns written via Float.intBitsToFloat
layout(location = 8) in vec4 aBoneWeights;

layout(location = 0) out vec3 vFragPos;
layout(location = 1) out vec3 vNormal;
layout(location = 2) out vec2 vTexCoord;
layout(location = 3) out vec4 vColor;
layout(location = 4) out vec3 vTangent;
layout(location = 5) out vec3 vBitangent;
layout(location = 6) out float vViewDist;
layout(location = 7) out vec4 vLightSpacePos;
layout(location = 8) out vec2 vLightmapUV;
layout(location = 9) out vec3 v_GouraudLightColor; // Gouraud mode only: per-vertex ambient + diffuse + specular

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

// Must stay byte-identical to the LightBuffer in blinn_phong.frag (and the other blinn_phong vertex shader)
layout(std140, set = 0, binding = 0) uniform LightBuffer {
    DirectionalLight dirLight;
    PointLight pointLights[32]; // byte offset 32: SceneLights.MAX_POINT_LIGHTS
    SpotLight spotLights[8];    // byte offset 1568: SceneLights.MAX_SPOT_LIGHTS
    vec4 numLights;   // byte offset 2080: x = directional (1.0 = active), y = point count, z = spot count, w = shadowMapBias
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

// 128 bytes: must stay byte-identical to the block in blinn_phong.vert
layout(push_constant) uniform MeshPushConstants {
    mat4 model;
    vec4 params;       // x = passFlags (1.0 = shadow blob, 2.0 = shadow map depth), y = heightFade, z = reflection, w = emissiveIntensity
    vec4 surface;      // x = specularStrength, y = roughness, z = normalStrength, w = unused
    ivec4 texIndices;  // bindless slots: x = albedo, y = normal, z = specular/roughness, w = lightmap/AO (-1 = channel absent)
    ivec4 texIndices2; // x = emissive slot, y = bone palette slot, z = camera view (0 = editor, 1 = camera preview), w = unused
} u_PushConstants;

// Every skinned entity's 64-bone palette packed back to back; the push constant slot selects this draw's palette,
// so the descriptor set never needs rebinding (or a dynamic offset) between draws.
const uint BONES_PER_PALETTE = 64u;
layout(std430, set = 0, binding = 3) readonly buffer BonePalette {
    mat4 u_Bones[];
};

// Adds one light's Lambert diffuse and Blinn-Phong specular, identical to blinn_phong.frag
void accumulateLight(vec3 N, vec3 V, vec3 L, vec3 radiance, float specularStrength, float shininess,
                     inout vec3 diffuse, inout vec3 specular) {
    float diff = max(dot(N, L), 0.0);
    diffuse += diff * radiance;
    if (diff > 0.0 && specularStrength > 0.0) {
        vec3 H = normalize(L + V);
        specular += pow(max(dot(N, H), 0.0), shininess) * specularStrength * radiance;
    }
}

// Gouraud (per-vertex) lighting: ambient + Lambert diffuse + Blinn-Phong specular over the same lights, attenuation
// and spot cones as blinn_phong.frag, evaluated once per vertex and interpolated across the triangle. A vertex cannot
// sample textures, so normal, specular/roughness and lightmap maps are skipped (scalar material values only) and the
// sun is unshadowed: the fixed-function look. Two-sided like the fragment path: there is no face normal per vertex,
// so a normal pointing away from the camera is turned toward it, which lights single-sided cards from behind.
// Must stay identical in blinn_phong.vert and blinn_phong_skinned.vert.
vec3 computeGouraudLight(vec3 worldPos, vec3 worldNormal) {
    vec3 eyePos = (u_PushConstants.texIndices2.z == 1) ? u_Lights.previewViewPos.xyz : u_Lights.viewPos.xyz;
    vec3 toEye = eyePos - worldPos;
    vec3 V = (dot(toEye, toEye) > 1e-12) ? normalize(toEye) : vec3(0.0, 1.0, 0.0);
    vec3 N = (dot(worldNormal, worldNormal) > 1e-8) ? normalize(worldNormal) : vec3(0.0, 1.0, 0.0);
    if (dot(N, V) < 0.0) N = -N;

    float specularStrength = u_PushConstants.surface.x;
    float roughness = clamp(u_PushConstants.surface.y, 0.0, 1.0);
    float shininess = exp2(10.0 * (1.0 - roughness) + 1.0);

    vec3 diffuse = vec3(0.0);
    vec3 specular = vec3(0.0);

    if (u_Lights.numLights.x > 0.5) {
        vec3 L = normalize(-u_Lights.dirLight.direction.xyz);
        accumulateLight(N, V, L, u_Lights.dirLight.color.rgb * u_Lights.dirLight.color.a, specularStrength, shininess, diffuse, specular);
    }

    int pointCount = min(int(u_Lights.numLights.y), 32);
    for (int i = 0; i < pointCount; i++) {
        PointLight light = u_Lights.pointLights[i];
        vec3 lightVector = light.position.xyz - worldPos;
        float dist = length(lightVector);
        vec3 L = normalize(lightVector);
        float attenuation = 1.0 / (1.0 + 0.1 * dist + 0.02 * dist * dist);
        accumulateLight(N, V, L, light.color.rgb * light.color.a * attenuation, specularStrength, shininess, diffuse, specular);
    }

    int spotCount = min(int(u_Lights.numLights.z), 8);
    for (int i = 0; i < spotCount; i++) {
        SpotLight light = u_Lights.spotLights[i];
        vec3 lightVector = light.position.xyz - worldPos;
        float dist = length(lightVector);
        vec3 L = normalize(lightVector);
        float attenuation = 1.0 / (1.0 + 0.1 * dist + 0.02 * dist * dist);

        vec3 spotDir = normalize(-light.direction.xyz);
        float theta = dot(L, spotDir);
        float innerCutoff = light.position.w > 0.0 ? light.position.w : 0.93;
        float outerCutoff = light.direction.w > 0.0 ? light.direction.w : 0.82;
        attenuation *= clamp((theta - outerCutoff) / max(innerCutoff - outerCutoff, 0.0001), 0.0, 1.0);

        accumulateLight(N, V, L, light.color.rgb * light.color.a * attenuation, specularStrength, shininess, diffuse, specular);
    }

    // Same ambient floor as the fragment path. Specular is folded into the light color, so the fragment stage's
    // albedo multiply tints highlights (fixed-function GL_MODULATE behaviour)
    vec3 ambient = max(u_Lights.dirLight.color.rgb * 0.35, u_Lights.ambientColor.rgb);
    return ambient + diffuse + specular;
}

void main() {
    vec4 localPos = vec4(aPos, 1.0);
    vec3 localNormal = aNormal;
    vec3 localTangent = aTangent;
    vec3 localBitangent = aBitangent;

    float totalWeight = aBoneWeights.x + aBoneWeights.y + aBoneWeights.z + aBoneWeights.w;
    if (totalWeight > 0.001) {
        uint base = uint(max(u_PushConstants.texIndices2.y, 0)) * BONES_PER_PALETTE;
        uvec4 ids = min(aBoneIds, uvec4(BONES_PER_PALETTE - 1u)) + uvec4(base);
        mat4 skin = u_Bones[ids.x] * aBoneWeights.x
                  + u_Bones[ids.y] * aBoneWeights.y
                  + u_Bones[ids.z] * aBoneWeights.z
                  + u_Bones[ids.w] * aBoneWeights.w;
        skin *= (1.0 / totalWeight);
        localPos = skin * localPos;
        localNormal = mat3(skin) * localNormal;
        localTangent = mat3(skin) * localTangent;
        localBitangent = mat3(skin) * localBitangent;
    }

    vec4 worldPos = u_PushConstants.model * localPos;
    vFragPos = worldPos.xyz;

    mat3 model3 = mat3(u_PushConstants.model);
    mat3 normalMatrix = transpose(inverse(model3));
    vNormal = normalMatrix * localNormal;
    vTangent = model3 * localTangent;
    vBitangent = model3 * localBitangent;

    vTexCoord = aTexCoord;
    vLightmapUV = aLightmapUV;
    vColor = aColor;

    vLightSpacePos = u_Lights.lightSpaceMatrix * worldPos;

    // Lighting is only needed by the lit pass in Gouraud mode; shadow blob and shadow map depth draws skip it
    v_GouraudLightColor = (u_Lights.shadingModel > 0.5 && u_PushConstants.params.x < 0.5)
                        ? computeGouraudLight(worldPos.xyz, vNormal) : vec3(0.0);

    // Shadow map depth pass renders the same geometry from the directional light
    vec4 cameraClip = (u_PushConstants.texIndices2.z == 1) ? u_Lights.previewViewProj * worldPos
                                                           : u_Lights.proj * u_Lights.view * worldPos;
    vec4 clipPos = (u_PushConstants.params.x > 1.5) ? vLightSpacePos : cameraClip;
    vViewDist = clipPos.w;
    gl_Position = clipPos;
}
