#version 450

// Premultiplied particle color: (rgb * a, a) alpha-blended, (rgb * a, 0) additive; blended ONE / ONE_MINUS_SRC_ALPHA.
// Soft round falloff (the Vulkan path has no per-batch texture binding).
layout(location = 0) in vec4 vColor;
layout(location = 1) in vec2 vUV;

layout(location = 0) out vec4 outColor;

void main() {
    vec2 c = vUV * 2.0 - 1.0;
    float soft = clamp(1.0 - dot(c, c), 0.0, 1.0);
    float coverage = soft * soft;
    if (coverage < 0.002) discard;
    outColor = vec4(vColor.rgb * coverage, vColor.a * coverage);
}
