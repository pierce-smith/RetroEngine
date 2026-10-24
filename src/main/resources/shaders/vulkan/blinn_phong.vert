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

layout(push_constant) uniform MeshPushConstants {
    mat4 model;
    mat4 view;
    mat4 proj;
    vec4 shadowParams; // x = passFlags (1.0 = shadow blob), y = heightFade, z = reflection, w = unused
    mat4 lightSpaceMatrix;
} u_PushConstants;

void main() {
    vec4 worldPos = u_PushConstants.model * vec4(aPos, 1.0);
    vFragPos = worldPos.xyz;
    
    mat3 normalMatrix = transpose(inverse(mat3(u_PushConstants.model)));
    vNormal = normalize(normalMatrix * aNormal);
    vTangent = normalize(normalMatrix * aTangent);
    vBitangent = normalize(normalMatrix * aBitangent);
    
    vTexCoord = aTexCoord;
    vColor = aColor;
    
    vec4 clipPos = u_PushConstants.proj * u_PushConstants.view * worldPos;
    vViewDist = clipPos.w;
    gl_Position = clipPos;
}
