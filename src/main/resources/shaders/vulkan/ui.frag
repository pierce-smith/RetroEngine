#version 450
#extension GL_EXT_nonuniform_qualifier : require

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vUV;
layout(location = 0) out vec4 outFragColor;

// Bindless set 0 shared with the 3D passes: the linear material sampler and the texture table
layout(set = 0, binding = 1) uniform sampler u_MaterialSampler;
layout(set = 0, binding = 4) uniform texture2D u_Textures[];

// fontSlot: bindless slot of the SDF font atlas, or -1 while it is not resident
layout(push_constant) uniform UIPushConstants {
    int fontSlot;
} pc;

void main() {
    // Sampled unconditionally so fwidth stays well-defined; solid geometry carries uv < -1 and ignores it
    float dist = texture(sampler2D(u_Textures[max(pc.fontSlot, 0)], u_MaterialSampler), vUV).r;
    float w = max(fwidth(dist) * 0.7, 1e-4);
    float coverage = pc.fontSlot < 0 ? 0.0 : smoothstep(0.5 - w, 0.5 + w, dist);
    outFragColor = vec4(vColor.rgb, vUV.x < -1.0 ? vColor.a : vColor.a * coverage);
}
