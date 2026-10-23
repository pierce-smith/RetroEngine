#version 450

layout(location = 0) in vec3 inPos;
layout(location = 1) in vec4 inColor;
layout(location = 2) in vec2 inUV;

layout(location = 0) out vec3 vWorldPos;
layout(location = 1) out vec4 vColor;

layout(push_constant) uniform GridPushConstants {
    mat4 view;
    mat4 proj;
} u_Grid;

void main() {
    vWorldPos = inPos;
    vColor = inColor;
    gl_Position = u_Grid.proj * u_Grid.view * vec4(inPos, 1.0);
}
