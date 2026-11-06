#version 450
#extension GL_EXT_nonuniform_qualifier : require

// Color-ID picking pass: writes the draw's 32-bit pick ID into an R8G8B8A8_UINT target, packed little-endian so a
// host read of the texel's four bytes yields the ID directly. Shares the blinn_phong vertex stages; the albedo alpha
// cutout matches shadow_depth.frag so cut-out foliage is transparent to picks exactly where it renders transparent.
// Every output of the shared blinn_phong vertex stages is declared, so the stages' interfaces match. Only vTexCoord is read
layout(location = 0) in vec3 vFragPos;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in vec2 vTexCoord;
layout(location = 3) in vec4 vColor;
layout(location = 4) in vec3 vTangent;
layout(location = 5) in vec3 vBitangent;
layout(location = 6) in float vViewDist;
layout(location = 7) in vec4 vLightSpacePos;
layout(location = 8) in vec2 vLightmapUV;
layout(location = 9) in vec3 v_GouraudLightColor;

layout(set = 0, binding = 1) uniform sampler u_MaterialSampler;
layout(set = 0, binding = 4) uniform texture2D u_Textures[];

// 128 bytes: must stay byte-identical to the block in blinn_phong.vert
layout(push_constant) uniform MeshPushConstants {
    mat4 model;
    vec4 params;
    vec4 surface;
    ivec4 texIndices;  // x = albedo slot (-1 = no cutout)
    ivec4 texIndices2; // w = pick ID (bit pattern)
} u_PushConstants;

layout(location = 0) out uvec4 outPickId;

void main() {
    int albedoIdx = u_PushConstants.texIndices.x;
    if (albedoIdx >= 0 && texture(sampler2D(u_Textures[albedoIdx], u_MaterialSampler), vTexCoord).a < 0.25) {
        discard;
    }
    uint id = uint(u_PushConstants.texIndices2.w);
    outPickId = uvec4(id & 0xFFu, (id >> 8) & 0xFFu, (id >> 16) & 0xFFu, id >> 24);
}
