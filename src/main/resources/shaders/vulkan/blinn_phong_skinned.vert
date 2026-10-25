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

layout(push_constant) uniform MeshPushConstants {
    mat4 model;
    mat4 view;
    mat4 proj;
    vec4 shadowParams; // x = passFlags (1.0 = shadow blob), y = heightFade, z = reflection, w = unused
    mat4 lightSpaceMatrix;
} u_PushConstants;

layout(std430, set = 0, binding = 3) readonly buffer BonePalette {
    mat4 u_Bones[64];
};

void main() {
    vec4 localPos = vec4(aPos, 1.0);
    vec3 localNormal = aNormal;
    vec3 localTangent = aTangent;
    vec3 localBitangent = aBitangent;

    float totalWeight = aBoneWeights.x + aBoneWeights.y + aBoneWeights.z + aBoneWeights.w;
    if (totalWeight > 0.001) {
        uvec4 ids = min(aBoneIds, uvec4(63u));
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

    mat3 normalMatrix = transpose(inverse(mat3(u_PushConstants.model)));
    vNormal = normalize(normalMatrix * localNormal);
    vTangent = normalize(normalMatrix * localTangent);
    vBitangent = normalize(normalMatrix * localBitangent);

    vTexCoord = aTexCoord;
    vColor = aColor;

    vec4 clipPos = u_PushConstants.proj * u_PushConstants.view * worldPos;
    vViewDist = clipPos.w;
    gl_Position = clipPos;
}
