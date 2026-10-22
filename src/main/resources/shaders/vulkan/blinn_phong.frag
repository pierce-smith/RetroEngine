#version 450

layout(location = 0) in vec3 vFragPos;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in vec2 vTexCoord;
layout(location = 3) in vec4 vColor;
layout(location = 4) in vec3 vTangent;
layout(location = 5) in vec3 vBitangent;

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

layout(push_constant) uniform MeshPushConstants {
    mat4 model;
    mat4 view;
    mat4 proj;
    vec4 shadowParams; // x = passFlags (1.0 = shadow blob), y = heightFade, z = reflection, w = unused
    mat4 lightSpaceMatrix;
} u_PushConstants;

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
    
    float attenuation = 1.0 / (1.0 + 0.1 * dist + 0.02 * dist * dist);
    
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
    // Dynamic Ground Shadow Blob Pass (PassFlags == 1.0)
    if (u_PushConstants.shadowParams.x > 0.5) {
        vec2 uvCentered = vTexCoord - vec2(0.5, 0.5);
        float distSq = dot(uvCentered, uvCentered) * 4.0;
        if (distSq >= 1.0) {
            discard;
        }
        float heightFade = u_PushConstants.shadowParams.y;
        float alpha = max(0.0, 1.0 - distSq) * heightFade * 0.75;
        outFragColor = vec4(0.02, 0.02, 0.05, alpha);
        return;
    }

    vec4 texColor = texture(u_MainTexture, vTexCoord);
    
    // Foliage / Leaf Billboard Alpha Cutout Masking (Discard transparent empty space)
    if (texColor.a < 0.25) {
        discard;
    }

    vec3 albedo = texColor.rgb;
    
    // Fallback to neutral base color if texture color is black/unmapped
    if (length(albedo) < 0.001) {
        albedo = vec3(0.75, 0.80, 0.85);
    }
    
    vec3 norm = normalize(vNormal);
    if (length(norm) < 0.001) norm = vec3(0.0, 1.0, 0.0);
    
    vec3 viewDir = normalize(u_Lights.viewPos.xyz - vFragPos);
    if (length(u_Lights.viewPos.xyz) < 0.001) {
        viewDir = normalize(vec3(0.0, 2.0, 5.0) - vFragPos);
    }
    
    vec3 result = vec3(0.0);
    
    if (u_Lights.numLights.z > 0.5) {
        result = CalculateSpotLight(u_Lights.spotLights, norm, vFragPos, viewDir, albedo);
    } else if (u_Lights.numLights.y > 0.5) {
        result = CalculatePointLight(u_Lights.pointLights, norm, vFragPos, viewDir, albedo);
    } else if (u_Lights.numLights.x > 0.5) {
        result = CalculateDirectionalLight(u_Lights.dirLight, norm, viewDir, albedo);
    } else {
        result = 0.05 * albedo;
    }
    
    // Output SOLID OPAQUE 3D geometry (Alpha = 1.0) to prevent pipeline blend transparency
    outFragColor = vec4(result, 1.0);
}
