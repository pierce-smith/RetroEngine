#version 450

layout(location = 0) out vec3 vNearPoint;
layout(location = 1) out vec3 vFarPoint;
layout(location = 2) out float vIsLine;

layout(push_constant) uniform GridPushConstants {
    mat4 view;
    mat4 proj;
} u_Grid;

vec3 UnprojectPoint(float x, float y, float z, mat4 view, mat4 proj) {
    mat4 viewInv = inverse(view);
    mat4 projInv = inverse(proj);
    vec4 unprojectedPoint = viewInv * projInv * vec4(x, y, z, 1.0);
    return unprojectedPoint.xyz / unprojectedPoint.w;
}

const vec3 gridPlane[6] = vec3[](
    vec3(-1.0, -1.0, 0.0), vec3( 1.0, -1.0, 0.0), vec3( 1.0,  1.0, 0.0),
    vec3(-1.0, -1.0, 0.0), vec3( 1.0,  1.0, 0.0), vec3(-1.0,  1.0, 0.0)
);

void main() {
    if (gl_VertexIndex >= 6) {
        vIsLine = 1.0;
        vNearPoint = vec3(0.0);
        vFarPoint = vec3(0.0);
        vec3 linePos = (gl_VertexIndex == 6) ? vec3(0.0, 0.0, 0.0) : vec3(0.0, 5.0, 0.0);
        gl_Position = u_Grid.proj * u_Grid.view * vec4(linePos, 1.0);
        return;
    }

    vIsLine = 0.0;
    vec3 p = gridPlane[gl_VertexIndex];
    vNearPoint = UnprojectPoint(p.x, p.y, 0.0, u_Grid.view, u_Grid.proj);
    vFarPoint = UnprojectPoint(p.x, p.y, 1.0, u_Grid.view, u_Grid.proj);
    
    gl_Position = vec4(p, 1.0);
}
