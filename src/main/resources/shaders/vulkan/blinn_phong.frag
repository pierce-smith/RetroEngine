#version 450

layout(location = 0) in vec3 vFragPos;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in vec2 vTexCoord;
layout(location = 3) in vec4 vColor;
layout(location = 4) in vec3 vTangent;
layout(location = 5) in vec3 vBitangent;
layout(location = 6) in float vViewDist;

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
    PointLight pointLights;
    SpotLight spotLights;
    vec4 numLights;   // x = numDir, y = numPoint, z = numSpot, w = shadowMapBias
    vec4 viewPos;     // xyz = camera position, w = groundY
    mat4 view;        // byte offset 176
    mat4 proj;        // byte offset 240
} u_Lights;

layout(set = 0, binding = 1) uniform sampler2D u_MainTexture;
layout(set = 0, binding = 2) uniform sampler2D u_ShadowMap;

// 80 bytes: stays within the 128-byte maxPushConstantsSize every Vulkan implementation guarantees
layout(push_constant) uniform MeshPushConstants {
    mat4 model;
    vec4 params; // x = passFlags (1.0 = shadow blob), y = heightFade, z = reflection, w = unused
} u_PushConstants;

void main() {
    // Dynamic Ground Shadow Blob Pass (PassFlags == 1.0)
    if (u_PushConstants.params.x > 0.5) {
        vec2 uvCentered = vTexCoord - vec2(0.5, 0.5);
        float distSq = dot(uvCentered, uvCentered) * 4.0;
        if (distSq >= 1.0) {
            discard;
        }
        float heightFade = u_PushConstants.params.y;
        float alpha = max(0.0, 1.0 - distSq) * heightFade * 0.75;
        outFragColor = vec4(0.02, 0.02, 0.05, alpha);
        return;
    }

    vec4 texColor = texture(u_MainTexture, vTexCoord);
    
    // Foliage / Leaf Billboard Alpha Cutout Masking (Discard transparent empty space)
    if (texColor.a < 0.25) {
        discard;
    }

    vec4 baseColor = vec4(texColor.rgb, texColor.a);
    if (length(vColor.rgb) > 0.001) {
        baseColor.rgb *= vColor.rgb;
    }
    
    vec3 N = normalize(vNormal);
    if (length(N) < 0.001) N = vec3(0.0, 1.0, 0.0);

    vec3 L = vec3(0.0, 1.0, 0.0);
    float attenuation = 1.0;
    vec3 lightColor = vec3(1.0, 1.0, 1.0);
    float intensity = 1.0;

    if (u_Lights.numLights.z > 0.5) {
        // Spot Light
        vec3 lightVector = u_Lights.spotLights.position.xyz - vFragPos;
        float dist = length(lightVector);
        L = normalize(lightVector);
        attenuation = 1.0 / (1.0 + 0.1 * dist + 0.02 * dist * dist);

        vec3 spotDir = normalize(-u_Lights.spotLights.direction.xyz);
        float theta = dot(L, spotDir);
        float innerCutoff = u_Lights.spotLights.position.w > 0.0 ? u_Lights.spotLights.position.w : 0.93;
        float outerCutoff = u_Lights.spotLights.direction.w > 0.0 ? u_Lights.spotLights.direction.w : 0.82;
        float spotIntensity = clamp((theta - outerCutoff) / max(innerCutoff - outerCutoff, 0.0001), 0.0, 1.0);
        attenuation *= spotIntensity;

        lightColor = u_Lights.spotLights.color.rgb;
        intensity = u_Lights.spotLights.color.a;
    } else if (u_Lights.numLights.y > 0.5) {
        // Point Light
        vec3 lightVector = u_Lights.pointLights.position.xyz - vFragPos;
        float dist = length(lightVector);
        L = normalize(lightVector);
        attenuation = 1.0 / (1.0 + 0.1 * dist + 0.02 * dist * dist);

        lightColor = u_Lights.pointLights.color.rgb;
        intensity = u_Lights.pointLights.color.a;
    } else if (u_Lights.numLights.x > 0.5) {
        // Directional Light
        L = normalize(-u_Lights.dirLight.direction.xyz);
        lightColor = u_Lights.dirLight.color.rgb;
        intensity = u_Lights.dirLight.color.a;
    }

    float diff = max(dot(N, L), 0.0);
    vec3 diffuse = diff * lightColor * intensity * attenuation;
    vec3 ambient = max(u_Lights.dirLight.color.rgb * 0.35, vec3(0.35, 0.35, 0.38));

    vec3 finalRGB = (ambient + diffuse) * baseColor.rgb;

    // Hemisphere Sky/Ground Environment Reflection Pass
    float reflectStrength = u_PushConstants.params.z;
    if (reflectStrength > 0.0) {
        vec3 V = normalize(u_Lights.viewPos.xyz - vFragPos);
        vec3 R = reflect(-V, N);
        vec3 envSky = vec3(0.85, 0.90, 1.0);
        vec3 envGround = vec3(0.15, 0.20, 0.30);
        vec3 envColor = mix(envGround, envSky, clamp(R.y * 0.5 + 0.5, 0.0, 1.0));
        finalRGB = mix(finalRGB, envColor, clamp(reflectStrength, 0.0, 1.0) * 0.4);
    }

    // Distance Fog Attenuation (20m to 150m)
    float fogStart = 20.0;
    float fogEnd = 150.0;
    float fogFactor = clamp((vViewDist - fogStart) / (fogEnd - fogStart), 0.0, 1.0);
    vec3 fogColor = vec3(0.12, 0.13, 0.16);
    finalRGB = mix(finalRGB, fogColor, fogFactor);

    outFragColor = vec4(finalRGB, baseColor.a);
}
