#version 450
#extension GL_EXT_nonuniform_qualifier : require

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vUV;
layout(location = 0) out vec4 outFragColor;

// Bindless set 0 shared with the 3D passes: the linear material sampler and the texture table
layout(set = 0, binding = 1) uniform sampler u_MaterialSampler;
layout(set = 0, binding = 4) uniform texture2D u_Textures[];

// fontSlot: bindless slot of the SDF font atlas, or -1 while it is not resident
// previewSlot: bindless slot of the camera preview target, or -1 when no preview was rendered this frame
layout(push_constant) uniform UIPushConstants {
    int fontSlot;
    int previewSlot;
} pc;

void main() {
    // Sampled unconditionally so fwidth stays well-defined; solid geometry carries uv < -1 and ignores it
    float dist = texture(sampler2D(u_Textures[max(pc.fontSlot, 0)], u_MaterialSampler), vUV).r;
    float w = max(fwidth(dist) * 0.7, 1e-4);
    // Camera preview quads carry uv offset by +2; explicit LOD since this branch is per-fragment
    if (vUV.x > 1.5) {
        vec3 preview = pc.previewSlot < 0 ? vec3(0.0)
                : textureLod(sampler2D(u_Textures[pc.previewSlot], u_MaterialSampler), vUV - vec2(2.0), 0.0).rgb;
        outFragColor = vec4(preview, vColor.a);
        return;
    }
    float coverage = pc.fontSlot < 0 ? 0.0 : smoothstep(0.5 - w, 0.5 + w, dist);
    outFragColor = vec4(vColor.rgb, vUV.x < -1.0 ? vColor.a : vColor.a * coverage);
}
