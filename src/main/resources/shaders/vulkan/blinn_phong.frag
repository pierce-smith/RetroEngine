#version 450

layout(location = 0) in vec3 vFragPos;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in vec2 vTexCoord;
layout(location = 3) in vec4 vFragPosLightSpace;

layout(location = 0) out vec4 outFragColor;

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
    PointLight pointLights;
    SpotLight spotLights;
    vec4 numLights;   // x = numDir, y = numPoint, z = numSpot, w = shadowMapBias
    vec4 viewPos;     // xyz = camera position, w = groundY
} u_Lights;

layout(set = 0, binding = 1) uniform sampler2D u_MainTexture;
layout(set = 0, binding = 2) uniform sampler2D u_ShadowMap;

vec3 CalculateDirectionalLight(DirectionalLight light, vec3 normal, vec3 viewDir, vec3 albedo) {
    if (light.color.a < 0.001) return vec3(0.0);
    
    vec3 lightDir = normalize(-light.direction.xyz);
    float diff = max(dot(normal, lightDir), 0.0);
    vec3 halfwayDir = normalize(lightDir + viewDir);
    float spec = pow(max(dot(normal, halfwayDir), 0.0), 32.0);
    
    vec3 lightCol = light.color.rgb;
    float lightIntensity = light.color.a;
    
    vec3 ambient = 0.15 * lightCol * lightIntensity * albedo;
    vec3 diffuse = diff * lightCol * lightIntensity * albedo;
    vec3 specular = spec * lightCol * lightIntensity * vec3(0.3);
    
    return ambient + diffuse + specular;
}

vec3 CalculatePointLight(PointLight light, vec3 normal, vec3 fragPos, vec3 viewDir, vec3 albedo) {
    if (light.color.a < 0.001) return vec3(0.0);

    vec3 lightVector = light.position.xyz - fragPos;
    float dist = length(lightVector);
    if (dist < 0.0001) return vec3(0.0);
    vec3 lightDir = normalize(lightVector);
    
    float attConstant = light.params.x > 0.0 ? light.params.x : 1.0;
    float attLinear = light.params.y > 0.0 ? light.params.y : 0.09;
    float attQuad = light.params.z > 0.0 ? light.params.z : 0.032;
    float attenuation = 1.0 / (attConstant + attLinear * dist + attQuad * dist * dist);
    
    float diff = max(dot(normal, lightDir), 0.0);
    vec3 halfwayDir = normalize(lightDir + viewDir);
    float spec = pow(max(dot(normal, halfwayDir), 0.0), 32.0);
    
    vec3 lightCol = light.color.rgb;
    float intensity = light.color.a;
    
    vec3 ambient = 0.15 * lightCol * intensity * albedo;
    vec3 diffuse = diff * lightCol * intensity * albedo;
    vec3 specular = spec * lightCol * intensity * vec3(0.3);
    
    return (ambient + diffuse + specular) * attenuation;
}

vec3 CalculateSpotLight(SpotLight light, vec3 normal, vec3 fragPos, vec3 viewDir, vec3 albedo) {
    if (light.color.a < 0.001) return vec3(0.0);

    vec3 lightVector = light.position.xyz - fragPos;
    float dist = length(lightVector);
    if (dist < 0.0001) return vec3(0.0);
    vec3 lightDir = normalize(lightVector);
    
    // Distance attenuation matching Metal
    float attenuation = 1.0 / (1.0 + 0.1 * dist + 0.02 * dist * dist);
    
    // Cone attenuation matching Metal
    vec3 spotDir = normalize(-light.direction.xyz);
    float theta = dot(lightDir, spotDir);
    float innerCutoff = light.position.w > 0.0 ? light.position.w : 0.93;
    float outerCutoff = light.direction.w > 0.0 ? light.direction.w : 0.82;
    float epsilon = max(innerCutoff - outerCutoff, 0.0001);
    float spotIntensity = clamp((theta - outerCutoff) / epsilon, 0.0, 1.0);
    attenuation *= spotIntensity;
    
    if (attenuation < 0.001) return vec3(0.0);

    float diff = max(dot(normal, lightDir), 0.0);
    vec3 halfwayDir = normalize(lightDir + viewDir);
    float spec = pow(max(dot(normal, halfwayDir), 0.0), 32.0);
    
    vec3 lightCol = light.color.rgb;
    float intensity = light.color.a;
    
    vec3 ambient = 0.15 * lightCol * intensity * albedo;
    vec3 diffuse = diff * lightCol * intensity * albedo;
    vec3 specular = spec * lightCol * intensity * vec3(0.3);
    
    return (ambient + diffuse + specular) * attenuation;
}

void main() {
    vec4 texColor = texture(u_MainTexture, vTexCoord);
    vec3 albedo = texColor.rgb;
    float alpha = texColor.a;
    
    // Fallback to neutral base color if texture has zero alpha or unmapped memory
    if (alpha < 0.05 || length(albedo) < 0.001) {
        albedo = vec3(0.75, 0.80, 0.85);
        alpha = 1.0;
    }
    
    vec3 norm = normalize(vNormal);
    if (length(norm) < 0.001) norm = vec3(0.0, 1.0, 0.0);
    
    vec3 viewDir = normalize(u_Lights.viewPos.xyz - vFragPos);
    if (length(u_Lights.viewPos.xyz) < 0.001) {
        viewDir = normalize(vec3(0.0, 2.0, 5.0) - vFragPos);
    }
    
    vec3 result = vec3(0.0);
    
    // Evaluate strictly based on active light count
    if (u_Lights.numLights.z > 0.5) {
        // Spotlight active
        result = CalculateSpotLight(u_Lights.spotLights, norm, vFragPos, viewDir, albedo);
    } else if (u_Lights.numLights.y > 0.5) {
        // Point light active
        result = CalculatePointLight(u_Lights.pointLights, norm, vFragPos, viewDir, albedo);
    } else if (u_Lights.numLights.x > 0.5) {
        // Directional light active
        result = CalculateDirectionalLight(u_Lights.dirLight, norm, viewDir, albedo);
    } else {
        // Dark unlit ambient baseline when no lights are active
        result = 0.05 * albedo;
    }
    
    outFragColor = vec4(result, alpha);
}
