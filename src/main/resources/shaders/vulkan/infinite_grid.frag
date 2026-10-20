#version 450

layout(location = 0) in vec3 vNearPoint;
layout(location = 1) in vec3 vFarPoint;

layout(location = 0) out vec4 outFragColor;

layout(push_constant) uniform GridPushConstants {
    mat4 view;
    mat4 proj;
} u_Grid;

vec4 ComputeGrid(vec3 fragPos3D, float scale, bool drawAxis) {
    vec2 coord = fragPos3D.xz * scale;
    vec2 derivative = fwidth(coord);
    vec2 grid = abs(fract(coord - 0.5) - 0.5) / derivative;
    float line = min(grid.x, grid.y);
    float minimumz = min(derivative.y, 1.0);
    float minimumx = min(derivative.x, 1.0);
    vec4 color = vec4(0.35, 0.40, 0.45, 1.0 - min(line, 1.0));

    if (drawAxis) {
        if (fragPos3D.x > -0.1 * minimumx && fragPos3D.x < 0.1 * minimumx)
            color = vec4(0.25, 0.45, 0.95, 1.0); // Z-Axis (Blue)
        if (fragPos3D.z > -0.1 * minimumz && fragPos3D.z < 0.1 * minimumz)
            color = vec4(0.95, 0.25, 0.25, 1.0); // X-Axis (Red)
    }
    return color;
}

float ComputeDepth(vec3 pos) {
    vec4 clip_space_pos = u_Grid.proj * u_Grid.view * vec4(pos.xyz, 1.0);
    return (clip_space_pos.z / clip_space_pos.w);
}

float ComputeLinearDepth(vec3 pos) {
    vec4 clip_space_pos = u_Grid.proj * u_Grid.view * vec4(pos.xyz, 1.0);
    float clip_space_depth = (clip_space_pos.z / clip_space_pos.w) * 2.0 - 1.0;
    float near = 0.1;
    float far = 100.0;
    float linearDepth = (2.0 * near * far) / (far + near - clip_space_depth * (far - near));
    return linearDepth / far;
}

void main() {
    float t = -vNearPoint.y / (vFarPoint.y - vNearPoint.y);
    if (t < 0.0) {
        discard;
    }
    
    vec3 fragPos3D = vNearPoint + t * (vFarPoint - vNearPoint);
    gl_FragDepth = ComputeDepth(fragPos3D);

    float linearDepth = ComputeLinearDepth(fragPos3D);
    float fading = max(0.0, (1.0 - linearDepth * 2.2));

    vec4 grid1 = ComputeGrid(fragPos3D, 1.0, true);
    vec4 grid2 = ComputeGrid(fragPos3D, 0.1, false);

    vec4 finalColor = mix(grid2, grid1, grid1.a);
    finalColor.a *= fading;

    if (finalColor.a < 0.02) discard;

    outFragColor = finalColor;
}
