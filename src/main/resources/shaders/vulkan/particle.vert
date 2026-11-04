#version 450

// Camera-facing billboard particles (ParticleDrawList): world-space quads, 9-float stride (pos, premultiplied rgba, uv)
layout(location = 0) in vec3 inPos;
layout(location = 1) in vec4 inColor;
layout(location = 2) in vec2 inUV;

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec2 vUV;

// Same 128-byte layout as infinite_grid.vert (shares its pipeline layout)
layout(push_constant) uniform ParticlePushConstants {
    mat4 view;
    mat4 proj;
} u_Particles;

void main() {
    vColor = inColor;
    vUV = inUV;
    gl_Position = u_Particles.proj * u_Particles.view * vec4(inPos, 1.0);
}
