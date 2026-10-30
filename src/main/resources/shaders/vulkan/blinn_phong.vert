#version 450

layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec4 aColor;
layout(location = 3) in vec2 aTexCoord;
layout(location = 4) in vec2 aLightmapUV;
layout(location = 5) in vec3 aTangent;
layout(location = 6) in vec3 aBitangent;

layout(location = 0) out vec3 vFragPos;
layout(location = 1) out vec3 vNormal;
layout(location = 2) out vec2 vTexCoord;
layout(location = 3) out vec4 vColor;
layout(location = 4) out vec3 vTangent;
layout(location = 5) out vec3 vBitangent;
layout(location = 6) out float vViewDist;
layout(location = 7) out vec4 vLightSpacePos;
layout(location = 8) out vec2 vLightmapUV;

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
    vec4 previewViewPos;   // byte offset 2368: xyz = that camera's world position (2384 bytes total)
} u_Lights;

// 128 bytes: exactly the maxPushConstantsSize every Vulkan implementation guarantees.
// Must stay byte-identical across blinn_phong.vert/.frag, blinn_phong_skinned.vert and shadow_depth.frag.
layout(push_constant) uniform MeshPushConstants {
    mat4 model;
    vec4 params;       // x = passFlags (1.0 = shadow blob, 2.0 = shadow map depth), y = heightFade, z = reflection, w = emissiveIntensity
    vec4 surface;      // x = specularStrength, y = roughness, z = normalStrength, w = unused
    ivec4 texIndices;  // bindless slots: x = albedo, y = normal, z = specular/roughness, w = lightmap/AO (-1 = channel absent)
    ivec4 texIndices2; // x = emissive slot, y = bone palette slot, z = camera view (0 = editor, 1 = camera preview), w = unused
} u_PushConstants;

void main() {
    vec4 worldPos = u_PushConstants.model * vec4(aPos, 1.0);
    vFragPos = worldPos.xyz;

    // Normals use the inverse-transpose; tangent-frame vectors follow the surface, so they take the model matrix.
    // Left unnormalized here: meshes without tangent data supply zero vectors, which the fragment stage detects.
    mat3 model3 = mat3(u_PushConstants.model);
    mat3 normalMatrix = transpose(inverse(model3));
    vNormal = normalMatrix * aNormal;
    vTangent = model3 * aTangent;
    vBitangent = model3 * aBitangent;

    vTexCoord = aTexCoord;
    vLightmapUV = aLightmapUV;
    vColor = aColor;

    vLightSpacePos = u_Lights.lightSpaceMatrix * worldPos;

    // Shadow map depth pass renders the same geometry from the directional light
    vec4 cameraClip = (u_PushConstants.texIndices2.z == 1) ? u_Lights.previewViewProj * worldPos
                                                           : u_Lights.proj * u_Lights.view * worldPos;
    vec4 clipPos = (u_PushConstants.params.x > 1.5) ? vLightSpacePos : cameraClip;
    vViewDist = clipPos.w;
    gl_Position = clipPos;
}
