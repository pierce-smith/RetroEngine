#version 450

layout(location = 0) in vec3 vWorldPos;
layout(location = 1) in vec4 vColor;

layout(location = 0) out vec4 outFragColor;

void main() {
    // Vertical Green Y-Axis Pillar Line (y > 0.05)
    if (vWorldPos.y > 0.05) {
        outFragColor = vec4(0.2, 1.0, 0.2, 1.0); // Vibrant Green Y-Axis Pillar
        return;
    }

    vec2 coord = vWorldPos.xz;
    vec2 der = fwidth(coord);
    vec2 safeDer = max(der, vec2(0.00001));

    // 1-Meter Minor Grid Lines
    vec2 grid1 = abs(fract(coord - 0.5) - 0.5) / safeDer;
    float line1 = min(grid1.x, grid1.y);
    float c1 = 1.0 - min(line1, 1.0);

    // 10-Meter Major Grid Subdivisions
    vec2 grid10 = abs(fract((coord / 10.0) - 0.5) - 0.5) / (safeDer / 10.0);
    float line10 = min(grid10.x, grid10.y);
    float c10 = 1.0 - min(line10, 1.0);

    // Primary Axes (Red X-Axis at Z=0, Blue Z-Axis at X=0)
    bool isXAxis = abs(vWorldPos.z) < max(der.y * 1.5, 0.08);
    bool isZAxis = abs(vWorldPos.x) < max(der.x * 1.5, 0.08);

    vec4 gridColor = vec4(0.45, 0.48, 0.55, 0.35 * c1); // Minor 1m lines
    if (c10 > 0.01) {
        gridColor = mix(gridColor, vec4(0.75, 0.80, 0.90, 0.70), c10); // Major 10m lines
    }
    if (isXAxis) {
        gridColor = vec4(0.95, 0.25, 0.25, 0.95); // Red X-Axis
    }
    if (isZAxis) {
        gridColor = vec4(0.25, 0.55, 0.95, 0.95); // Blue Z-Axis
    }

    if (gridColor.a < 0.01) {
        discard;
    }

    // Distance Horizon Fog Attenuation (450m limit matching Metal backend)
    float dist = length(vWorldPos.xz);
    float fade = clamp(1.0 - (dist / 450.0), 0.0, 1.0);
    gridColor.a *= fade;

    if (gridColor.a < 0.01) {
        discard;
    }

    outFragColor = gridColor;
}
