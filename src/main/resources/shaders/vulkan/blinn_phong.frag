#version 450

layout(location = 0) in vec3 vFragPos;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in vec2 vTexCoord;
layout(location = 3) in vec4 vFragPosLightSpace;

layout(location = 0) out vec4 outFragColor;

// Strict Apple Silicon / MoltenVK std140 struct layout (16-byte alignment per struct)
struct DirectionalLight {
    vec4 direction; // xyz = direction, w = padding
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
    PointLight pointLights[4];
    SpotLight spotLights[4];
    vec4 numLights;   // x = numDir, y = numPoint, z = numSpot, w = shadowMapBias
    vec4 viewPos;     // xyz = camera position, w = groundY
} u_Lights;

layout(set = 0, binding = 1) uniform sampler2D u_MainTexture;
layout(set = 0, binding = 2) uniform sampler2D u_ShadowMap;

float CalculateShadow(vec4 fragPosLightSpace, vec3 normal, vec3 lightDir) {
    vec3 projCoords = fragPosLightSpace.xyz / fragPosLightSpace.w;
    projCoords.xy = projCoords.xy * 0.5 + 0.5; // Map [-1, 1] to [0, 1]
    
    if (projCoords.z > 1.0 || projCoords.x < 0.0 || projCoords.x > 1.0 || projCoords.y < 0.0 || projCoords.y > 1.0) {
        return 0.0;
    }
    
    float currentDepth = projCoords.z;
    float bias = max(0.005 * (1.0 - dot(normal, lightDir)), 0.0005);
    
    // PCF 3x3 Shadow Filtering Kernel
    float shadow = 0.0;
    vec2 texelSize = 1.0 / textureSize(u_ShadowMap, 0);
    for(int x = -1; x <= 1; ++x) {
        for(int y = -1; y <= 1; ++y) {
            float pcfDepth = texture(u_ShadowMap, projCoords.xy + vec2(x, y) * texelSize).r;
            shadow += currentDepth - bias > pcfDepth ? 1.0 : 0.0;
        }
    }
    shadow /= 9.0;
    return shadow;
}

float CalculateRaycastBlobShadow(vec3 fragPos, float groundY) {
    float distToGround = abs(fragPos.y - groundY);
    if (distToGround > 0.6) return 0.0;
    float radius = 1.0 - (distToGround / 0.6);
    return smoothstep(0.0, 1.0, radius) * 0.45;
}

vec3 CalculateDirectionalLight(DirectionalLight light, vec3 normal, vec3 viewDir, vec3 albedo, float shadow) {
    vec3 lightDir = normalize(-light.direction.xyz);
    
    float diff = max(dot(normal, lightDir), 0.0);
    
    vec3 halfwayDir = normalize(lightDir + viewDir);
    float spec = pow(max(dot(normal, halfwayDir), 0.0), 32.0);
    
    vec3 ambient = 0.18 * light.color.rgb * light.color.a * albedo;
    vec3 diffuse = diff * light.color.rgb * light.color.a * albedo;
    vec3 specular = spec * light.color.rgb * light.color.a * vec3(0.4);
    
    return ambient + (1.0 - shadow) * (diffuse + specular);
}

void main() {
    vec4 texColor = texture(u_MainTexture, vTexCoord);
    if (texColor.a < 0.1) discard;
    vec3 albedo = texColor.rgb;
    
    vec3 norm = normalize(vNormal);
    vec3 viewDir = normalize(u_Lights.viewPos.xyz - vFragPos);
    
    vec3 lightDir = normalize(-u_Lights.dirLight.direction.xyz);
    float shadow = CalculateShadow(vFragPosLightSpace, norm, lightDir);
    
    float blobShadow = CalculateRaycastBlobShadow(vFragPos, u_Lights.viewPos.w);
    shadow = clamp(shadow + blobShadow, 0.0, 0.85);
    
    vec3 result = CalculateDirectionalLight(u_Lights.dirLight, norm, viewDir, albedo, shadow);
    outFragColor = vec4(result, texColor.a);
}
