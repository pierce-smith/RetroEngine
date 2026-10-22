#version 450

layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aTexCoord;
layout(location = 3) in vec2 aLightmapUV;
layout(location = 4) in vec3 aTangent;
layout(location = 5) in vec3 aBitangent;
layout(location = 6) in vec2 aLightmapParams;

layout(location = 0) out vec3 vFragPos;
layout(location = 1) out vec3 vNormal;
layout(location = 2) out vec2 vTexCoord;
layout(location = 3) out vec4 vFragPosLightSpace;
layout(location = 4) out vec2 vLightmapUV;
layout(location = 5) out vec3 vTangent;
layout(location = 6) out vec3 vBitangent;

layout(push_constant) uniform MeshPushConstants {
    mat4 model;
    mat4 view;
    mat4 proj;
    mat4 lightSpaceMatrix;
} u_PushConstants;

void main() {
    vec4 worldPos = u_PushConstants.model * vec4(aPos, 1.0);
    vFragPos = worldPos.xyz;
    
    // Normal matrix transformation to prevent non-uniform scaling distortion
    mat3 normalMatrix = transpose(inverse(mat3(u_PushConstants.model)));
    vNormal = normalize(normalMatrix * aNormal);
    vTangent = normalize(normalMatrix * aTangent);
    vBitangent = normalize(normalMatrix * aBitangent);
    
    vTexCoord = aTexCoord;
    vLightmapUV = aLightmapUV;
    vFragPosLightSpace = u_PushConstants.lightSpaceMatrix * worldPos;
    
    gl_Position = u_PushConstants.proj * u_PushConstants.view * worldPos;
}
