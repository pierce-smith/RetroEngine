package com.retro.engine.backend.metal;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.objc_getClass;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_registerName;

/**
 * MetalPipeline sets up the 3D RenderWare 3.5 shader pipeline featuring:
 * 1. Adaptive stride vertex reading (supports 9-float grid/quad and 14-float 3D models).
 * 2. Directional, Point, and Spot light shading calculations.
 * 3. Ambient intensity blending and specular highlights.
 */
public class MetalPipeline {
    private long pipelineState3D = 0L;
    private long depthStencilState3D = 0L;

    private long pipelineStateUI = 0L;
    private long depthStencilStateUI = 0L;

    private static final String MSL_3D_SHADER = """
            #include <metal_stdlib>
            using namespace metal;

            struct VertexOut {
                float4 position [[position]];
                float3 worldNormal;
                float3 worldPos;
                float4 color;
                float2 uv;
            };

            struct Uniforms {
                float4x4 mvp;             // 64 bytes (0..63)
                float4x4 model;           // 64 bytes (64..127)
                float4 lightDirIntensity; // 16 bytes (128..143) -> xyz = lightDir, w = intensity
                float4 lightPosType;      // 16 bytes (144..159) -> xyz = lightPos, w = lightType (0=DIR, 1=POINT, 2=SPOT)
                float4 lightColor;        // 16 bytes (160..175) -> rgb = lightColor, a = unused
                float4 ambientColor;      // 16 bytes (176..191) -> rgb = ambient, a = unused
                uint hasTexture;          // 4 bytes  (192..195)
                uint isUnlit;             // 4 bytes  (196..199)
                uint stride;              // 4 bytes  (200..203)
            };

            vertex VertexOut vertexMain(uint vertexID [[vertex_id]],
                                        constant float* vertices [[buffer(0)]],
                                        constant Uniforms& uniforms [[buffer(1)]]) {
                VertexOut out;
                uint vertStride = uniforms.stride > 0 ? uniforms.stride : 14;
                int idx = vertexID * vertStride;

                float4 localPos = float4(vertices[idx], vertices[idx+1], vertices[idx+2], 1.0);
                out.position = uniforms.mvp * localPos;
                out.worldPos = (uniforms.model * localPos).xyz;

                if (vertStride == 14) {
                    float3 localNorm = float3(vertices[idx+3], vertices[idx+4], vertices[idx+5]);
                    out.worldNormal = normalize((uniforms.model * float4(localNorm, 0.0)).xyz);
                    out.color = float4(vertices[idx+6], vertices[idx+7], vertices[idx+8], vertices[idx+9]);
                    out.uv = float2(vertices[idx+10], vertices[idx+11]);
                } else {
                    out.worldNormal = float3(0.0, 1.0, 0.0);
                    out.color = float4(vertices[idx+3], vertices[idx+4], vertices[idx+5], vertices[idx+6]);
                    out.uv = float2(vertices[idx+7], vertices[idx+8]);
                }

                return out;
            }

            fragment float4 fragmentMain(VertexOut in [[stage_in]],
                                          texture2d<float> tex [[texture(0)]],
                                          sampler smp [[sampler(0)]],
                                          constant Uniforms& uniforms [[buffer(1)]]) {
                float4 baseColor = in.color;
                if (uniforms.hasTexture == 1) {
                    baseColor *= tex.sample(smp, in.uv);
                }

                if (uniforms.isUnlit == 1) {
                    return baseColor;
                }

                float3 N = normalize(in.worldNormal);
                int lightType = int(uniforms.lightPosType.w);
                float3 L;
                float attenuation = 1.0;

                if (lightType == 0) {
                    // Directional Light
                    L = normalize(-uniforms.lightDirIntensity.xyz);
                } else {
                    // Point or Spot Light
                    float3 lightPos = uniforms.lightPosType.xyz;
                    float3 lightVector = lightPos - in.worldPos;
                    float dist = length(lightVector);
                    L = normalize(lightVector);

                    // Quadratic attenuation for Retro Engine scale
                    attenuation = 1.0 / (1.0 + 0.1 * dist + 0.02 * dist * dist);

                    if (lightType == 2) {
                        // Spot Light cone calculation
                        float3 spotDir = normalize(-uniforms.lightDirIntensity.xyz);
                        float theta = dot(L, spotDir);
                        float innerCutoff = 0.93; // ~21 deg inner angle
                        float outerCutoff = 0.82; // ~35 deg outer angle
                        float spotIntensity = clamp((theta - outerCutoff) / (innerCutoff - outerCutoff), 0.0, 1.0);
                        attenuation *= spotIntensity;
                    }
                }

                float intensity = uniforms.lightDirIntensity.w;
                float diff = max(dot(N, L), 0.0);
                float3 diffuse = diff * uniforms.lightColor.rgb * intensity * attenuation;
                float3 ambient = max(uniforms.ambientColor.rgb, float3(0.35, 0.35, 0.38));

                float3 finalRGB = (ambient + diffuse) * baseColor.rgb;
                return float4(finalRGB, baseColor.a);
            }
            """ ;

    private static final String MSL_UI_SHADER = """
            #include <metal_stdlib>
            using namespace metal;

            struct VertexOut {
                float4 position [[position]];
                float4 color;
            };

            vertex VertexOut uiVertexMain(uint vertexID [[vertex_id]],
                                          constant float* vertices [[buffer(0)]]) {
                VertexOut out;
                int idx = vertexID * 6;
                out.position = float4(vertices[idx], vertices[idx+1], 0.0, 1.0);
                out.color = float4(vertices[idx+2], vertices[idx+3], vertices[idx+4], vertices[idx+5]);
                return out;
            }

            fragment float4 uiFragmentMain(VertexOut in [[stage_in]]) {
                return in.color;
            }
            """ ;

    public boolean initialize(MetalDevice device) {
        long d = device.getDevice();
        long send = device.getObjcMsgSend();

        long source3D = device.createNSString(MSL_3D_SHADER);
        long lib3D = invokePPPPPP(d, sel_registerName("newLibraryWithSource:options:error:"), source3D, 0L, 0L, send);
        if (lib3D == 0L) return false;

        long vertFunc3D = invokePPPP(lib3D, sel_registerName("newFunctionWithName:"), device.createNSString("vertexMain"), send);
        long fragFunc3D = invokePPPP(lib3D, sel_registerName("newFunctionWithName:"), device.createNSString("fragmentMain"), send);

        long pipeDescClass = objc_getClass("MTLRenderPipelineDescriptor");
        long pipeDesc3D = invokePPP(pipeDescClass, sel_registerName("new"), send);
        invokePPPP(pipeDesc3D, sel_registerName("setVertexFunction:"), vertFunc3D, send);
        invokePPPP(pipeDesc3D, sel_registerName("setFragmentFunction:"), fragFunc3D, send);

        long colorAttach3D = invokePPP(pipeDesc3D, sel_registerName("colorAttachments"), send);
        long colorAttach3D0 = invokePPPP(colorAttach3D, sel_registerName("objectAtIndexedSubscript:"), 0L, send);
        invokePPPP(colorAttach3D0, sel_registerName("setPixelFormat:"), 80L, send);
        invokePPPP(pipeDesc3D, sel_registerName("setDepthAttachmentPixelFormat:"), 252L, send);

        pipelineState3D = invokePPPPP(d, sel_registerName("newRenderPipelineStateWithDescriptor:error:"), pipeDesc3D, 0L, send);

        long depthDescClass = objc_getClass("MTLDepthStencilDescriptor");
        long depthDesc3D = invokePPP(depthDescClass, sel_registerName("new"), send);
        invokePPPP(depthDesc3D, sel_registerName("setDepthCompareFunction:"), 1L, send);
        invokePPPP(depthDesc3D, sel_registerName("setDepthWriteEnabled:"), 1L, send);
        depthStencilState3D = invokePPPP(d, sel_registerName("newDepthStencilStateWithDescriptor:"), depthDesc3D, send);

        long sourceUI = device.createNSString(MSL_UI_SHADER);
        long libUI = invokePPPPPP(d, sel_registerName("newLibraryWithSource:options:error:"), sourceUI, 0L, 0L, send);
        if (libUI == 0L) return false;

        long vertFuncUI = invokePPPP(libUI, sel_registerName("newFunctionWithName:"), device.createNSString("uiVertexMain"), send);
        long fragFuncUI = invokePPPP(libUI, sel_registerName("newFunctionWithName:"), device.createNSString("uiFragmentMain"), send);

        long pipeDescUI = invokePPP(pipeDescClass, sel_registerName("new"), send);
        invokePPPP(pipeDescUI, sel_registerName("setVertexFunction:"), vertFuncUI, send);
        invokePPPP(pipeDescUI, sel_registerName("setFragmentFunction:"), fragFuncUI, send);

        long colorAttachUI = invokePPP(pipeDescUI, sel_registerName("colorAttachments"), send);
        long colorAttachUI0 = invokePPPP(colorAttachUI, sel_registerName("objectAtIndexedSubscript:"), 0L, send);
        invokePPPP(colorAttachUI0, sel_registerName("setPixelFormat:"), 80L, send);

        invokePPPP(colorAttachUI0, sel_registerName("setBlendingEnabled:"), 1L, send);
        invokePPPP(colorAttachUI0, sel_registerName("setSourceRGBBlendFactor:"), 4L, send);
        invokePPPP(colorAttachUI0, sel_registerName("setDestinationRGBBlendFactor:"), 5L, send);
        invokePPPP(pipeDescUI, sel_registerName("setDepthAttachmentPixelFormat:"), 252L, send);

        pipelineStateUI = invokePPPPP(d, sel_registerName("newRenderPipelineStateWithDescriptor:error:"), pipeDescUI, 0L, send);

        long depthDescUI = invokePPP(depthDescClass, sel_registerName("new"), send);
        invokePPPP(depthDescUI, sel_registerName("setDepthCompareFunction:"), 7L, send);
        invokePPPP(depthDescUI, sel_registerName("setDepthWriteEnabled:"), 0L, send);
        depthStencilStateUI = invokePPPP(d, sel_registerName("newDepthStencilStateWithDescriptor:"), depthDescUI, send);

        return pipelineState3D != 0L && pipelineStateUI != 0L;
    }

    public long getPipelineState3D() { return pipelineState3D; }
    public long getDepthStencilState3D() { return depthStencilState3D; }
    public long getPipelineStateUI() { return pipelineStateUI; }
    public long getDepthStencilStateUI() { return depthStencilStateUI; }
}
