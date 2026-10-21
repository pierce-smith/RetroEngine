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
    vec3 lightDir = normalize(-light.direction.xyz);
    if (length(light.direction.xyz) < 0.001) {
        lightDir = normalize(vec3(0.5, 1.0, 0.5));
    }
    
    float diff = max(dot(normal, lightDir), 0.0);
    vec3 halfwayDir = normalize(lightDir + viewDir);
    float spec = pow(max(dot(normal, halfwayDir), 0.0), 32.0);
    
    vec3 lightCol = (length(light.color.rgb) < 0.001) ? vec3(1.0, 0.95, 0.85) : light.color.rgb;
    float lightIntensity = (light.color.a < 0.01) ? 1.2 : light.color.a;
    
    vec3 ambient = 0.35 * lightCol * lightIntensity * albedo;
    vec3 diffuse = diff * lightCol * lightIntensity * albedo;
    vec3 specular = spec * lightCol * lightIntensity * vec3(0.3);
    
    return ambient + diffuse + specular;
}

void main() {
    vec4 texColor = texture(u_MainTexture, vTexCoord);
    vec3 albedo = texColor.rgb;
    float alpha = texColor.a;
    
    // Robust fallback if texture sampling yields zero alpha or unmapped memory
    if (alpha < 0.05 || length(albedo) < 0.001) {
        albedo = vec3(0.75, 0.80, 0.85); // Solid neutral slate diffuse
        alpha = 1.0;
    }
    
    vec3 norm = normalize(vNormal);
    if (length(norm) < 0.001) norm = vec3(0.0, 1.0, 0.0);
    
    vec3 viewDir = normalize(u_Lights.viewPos.xyz - vFragPos);
    if (length(u_Lights.viewPos.xyz) < 0.001) {
        viewDir = normalize(vec3(0.0, 2.0, 5.0) - vFragPos);
    }
    
    vec3 result = CalculateDirectionalLight(u_Lights.dirLight, norm, viewDir, albedo);
    outFragColor = vec4(result, alpha);
}
