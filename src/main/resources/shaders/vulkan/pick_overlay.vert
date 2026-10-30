#version 450

// Pick overlay: screen-space handle triangles (transform gizmo) drawn last in the Color-ID pass, depth testing off.
// aPos = scene-tile NDC (+y up; the offscreen viewport's negative height flips it like the mesh passes),
// aId = low 16 bits of the overlay ID, OR'd onto IRenderBackend.PICK_OVERLAY_ID_BASE.
layout(location = 0) in vec2 aPos;
layout(location = 1) in float aId;

layout(location = 0) flat out uint vPickId;

void main() {
    vPickId = 0xFFFE0000u | (uint(aId) & 0xFFFFu);
    gl_Position = vec4(aPos, 0.0, 1.0);
}
