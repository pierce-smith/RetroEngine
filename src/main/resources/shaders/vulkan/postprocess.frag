#version 450
#extension GL_EXT_nonuniform_qualifier : require

// Single-pass camera post-processing: vignette, CRT scanlines, chromatic aberration, saturation / contrast.
// Push constant floats after the slot = PostProcessSettings.pack() (offset 16).
layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 outFragColor;

layout(set = 0, binding = 1) uniform sampler u_MaterialSampler;
layout(set = 0, binding = 4) uniform texture2D u_Textures[];

layout(push_constant) uniform PostFXPushConstants {
    int sceneSlot;
    int pad0, pad1, pad2;
    float time;
    float vignette;
    float scanlines;
    float scanlineSpeed;
    float chromatic;   // pixels at the screen edge
    float saturation;
    float contrast;
    float pad3;
} fx;

vec3 sampleScene(vec2 uv) {
    return texture(sampler2D(u_Textures[fx.sceneSlot], u_MaterialSampler), clamp(uv, 0.0, 1.0)).rgb;
}

void main() {
    vec2 size = vec2(textureSize(sampler2D(u_Textures[fx.sceneSlot], u_MaterialSampler), 0));
    vec2 dir = vUV - 0.5;

    vec2 off = dir * 2.0 * fx.chromatic / size;
    vec3 col = vec3(sampleScene(vUV + off).r, sampleScene(vUV).g, sampleScene(vUV - off).b);

    float luma = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(vec3(luma), col, fx.saturation);
    col = (col - 0.5) * fx.contrast + 0.5;

    // Scanlines: 2-pixel period, scrolling scanlineSpeed lines per second
    float s = 0.5 + 0.5 * sin(3.14159265 * (vUV.y * size.y + 2.0 * fx.time * fx.scanlineSpeed));
    col *= 1.0 - fx.scanlines * (1.0 - s);

    float v = smoothstep(0.25, 0.75, length(dir) * 1.41421356);
    col *= 1.0 - fx.vignette * v;

    outFragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
