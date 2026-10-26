#version 450

// Directional shadow map depth pass: depth is written by fixed function, this stage only applies the
// foliage alpha cutout so leaf cards cast shaped shadows. Binding 2 (the shadow map itself) is never touched.
layout(location = 2) in vec2 vTexCoord;

layout(set = 0, binding = 1) uniform sampler2D u_MainTexture;

void main() {
    if (texture(u_MainTexture, vTexCoord).a < 0.25) {
        discard;
    }
}
