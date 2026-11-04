#version 450

// Fullscreen triangle (no vertex buffer) covering the viewport set to the Scene tile / window.
// With a positive-height viewport NDC y = -1 is the top edge, matching the offscreen image's row 0.
layout(location = 0) out vec2 vUV;

void main() {
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2) * 2.0 - 1.0;
    vUV = pos * 0.5 + 0.5;
    gl_Position = vec4(pos, 0.0, 1.0);
}
