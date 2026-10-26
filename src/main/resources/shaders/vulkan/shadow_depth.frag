#version 450
#extension GL_EXT_nonuniform_qualifier : require

// Directional shadow map depth pass: depth is written by fixed function, this stage only applies the
// foliage alpha cutout so leaf cards cast shaped shadows. Binding 2 (the shadow map itself) is never touched.
layout(location = 2) in vec2 vTexCoord;

layout(set = 0, binding = 1) uniform sampler u_MaterialSampler;
// Bindless material texture table (partially bound); indexed with dynamically uniform push constant slots
layout(set = 0, binding = 4) uniform texture2D u_Textures[];

// 128 bytes: must stay byte-identical to the block in blinn_phong.vert
layout(push_constant) uniform MeshPushConstants {
    mat4 model;
    vec4 params;
    vec4 surface;
    ivec4 texIndices;  // x = albedo slot (-1 = no cutout)
    ivec4 texIndices2;
} u_PushConstants;

void main() {
    int albedoIdx = u_PushConstants.texIndices.x;
    if (albedoIdx >= 0 && texture(sampler2D(u_Textures[albedoIdx], u_MaterialSampler), vTexCoord).a < 0.25) {
        discard;
    }
}
