package com.retro.engine.backend.metal;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.objc_getClass;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_registerName;

/**
 * MetalPipeline sets up the RenderWare 3.5 multi-pass shader pipeline featuring:
 * 1. 22-float vertex stride & multi-light type shading (Directional, Point, Spot).
 * 2. Dynamic Ground Entity Shadow Blobs with soft radial distance falloff.
 * 3. Procedural Ray-Plane Anti-Aliased Infinite Shader Grid.
 */
public class MetalPipeline {
    private long pipelineState3D = 0L;
    private long depthStencilState3D = 0L;
    private long depthStencilStateShadow = 0L;

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
                float2 uv2;
                float3 tangent;
                float3 bitangent;
                float viewDist;
            };

            struct Uniforms {
                float4x4 mvp;             // 64 bytes (0..63)
                float4x4 model;           // 64 bytes (64..127)
                float4 lightDirIntensity; // 16 bytes (128..143) -> xyz = lightDir, w = intensity
                float4 lightPosType;      // 16 bytes (144..159) -> xyz = lightPos, w = lightType (0=DIR, 1=POINT, 2=SPOT)
                float4 lightColor;        // 16 bytes (160..175) -> rgb = lightColor, a = unused
                float4 ambientColor;      // 16 bytes (176..191) -> rgb = ambient, a = unused
                float4 fogColorDensity;   // 16 bytes (192..207) -> rgb = fogColor, w = fogDensity
                float4 matParams;         // 16 bytes (208..223) -> x = reflectStrength, y = shadowOpacity, z = unused, w = unused
                uint hasTexture;          // 4 bytes  (224..227)
                uint isUnlit;             // 4 bytes  (228..231) -> 0 = Lit Model, 1 = Procedural Grid, 2 = Viewport Pass-through
                uint stride;              // 4 bytes  (232..235)
                uint passFlags;           // 4 bytes  (236..239) -> bit 0: shadowBlob, bit 1: reflection, bit 2: lightmap, bit 3: fog
            };

            vertex VertexOut vertexMain(uint vertexID [[vertex_id]],
                                        constant float* vertices [[buffer(0)]],
                                        constant Uniforms& uniforms [[buffer(1)]]) {
                VertexOut out;
                uint vertStride = uniforms.stride > 0 ? uniforms.stride : 22;
                int idx = vertexID * vertStride;

                float4 localPos = float4(vertices[idx], vertices[idx+1], vertices[idx+2], 1.0);
                out.position = uniforms.mvp * localPos;
                out.worldPos = (uniforms.model * localPos).xyz;
                out.viewDist = out.position.w;

                if (vertStride == 22) {
                    float3 localNorm = float3(vertices[idx+3], vertices[idx+4], vertices[idx+5]);
                    out.worldNormal = normalize((uniforms.model * float4(localNorm, 0.0)).xyz);
                    out.color = float4(vertices[idx+6], vertices[idx+7], vertices[idx+8], vertices[idx+9]);
                    out.uv = float2(vertices[idx+10], vertices[idx+11]);
                    out.uv2 = float2(vertices[idx+12], vertices[idx+13]);
                    float3 localTan = float3(vertices[idx+14], vertices[idx+15], vertices[idx+16]);
                    out.tangent = normalize((uniforms.model * float4(localTan, 0.0)).xyz);
                    float3 localBitan = float3(vertices[idx+17], vertices[idx+18], vertices[idx+19]);
                    out.bitangent = normalize((uniforms.model * float4(localBitan, 0.0)).xyz);
                } else if (vertStride == 14) {
                    float3 localNorm = float3(vertices[idx+3], vertices[idx+4], vertices[idx+5]);
                    out.worldNormal = normalize((uniforms.model * float4(localNorm, 0.0)).xyz);
                    out.color = float4(vertices[idx+6], vertices[idx+7], vertices[idx+8], vertices[idx+9]);
                    out.uv = float2(vertices[idx+10], vertices[idx+11]);
                    out.uv2 = float2(vertices[idx+12], vertices[idx+13]);
                    out.tangent = float3(1.0, 0.0, 0.0);
                    out.bitangent = float3(0.0, 1.0, 0.0);
                } else {
                    out.worldNormal = float3(0.0, 1.0, 0.0);
                    out.color = float4(vertices[idx+3], vertices[idx+4], vertices[idx+5], vertices[idx+6]);
                    out.uv = float2(vertices[idx+7], vertices[idx+8]);
                    out.uv2 = out.uv;
                    out.tangent = float3(1.0, 0.0, 0.0);
                    out.bitangent = float3(0.0, 1.0, 0.0);
                }

                return out;
            }

            fragment float4 fragmentMain(VertexOut in [[stage_in]],
                                          texture2d<float> tex [[texture(0)]],
                                          sampler smp [[sampler(0)]],
                                          constant Uniforms& uniforms [[buffer(1)]]) {

                // Pass Flag Bit 0: Ground Dynamic Shadow Blob Pass
                if ((uniforms.passFlags & 1u) != 0u) {
                    float2 uvCentered = in.uv - float2(0.5, 0.5);
                    float distSq = dot(uvCentered, uvCentered) * 4.0;
                    if (distSq >= 1.0) {
                        discard_fragment();
                    }
                    float alpha = max(0.0, 1.0 - distSq) * uniforms.matParams.y;
                    return float4(0.02, 0.02, 0.05, alpha * 0.75);
                }

                // Mode 2: Viewport Composition Pass-Through (1:1 Framebuffer Output)
                if (uniforms.isUnlit == 2u) {
                    if (uniforms.hasTexture == 1) {
                        return tex.sample(smp, in.uv);
                    }
                    return in.color;
                }

                // Mode 1: Procedural Ray-Plane Anti-Aliased Infinite Shader Grid
                if (uniforms.isUnlit == 1u) {
                    if (in.worldPos.y > 0.05) {
                        return float4(0.2, 1.0, 0.2, 1.0); // Vibrant Green Y-Axis Line
                    }

                    float2 coord = in.worldPos.xz;
                    float2 der = fwidth(coord);
                    float2 safeDer = max(der, float2(0.00001, 0.00001));

                    // 1-Meter Minor Grid Lines
                    float2 grid1 = abs(fract(coord - 0.5) - 0.5) / safeDer;
                    float line1 = min(grid1.x, grid1.y);
                    float c1 = 1.0 - min(line1, 1.0);

                    // 10-Meter Major Grid Subdivisions
                    float2 grid10 = abs(fract((coord / 10.0) - 0.5) - 0.5) / (safeDer / 10.0);
                    float line10 = min(grid10.x, grid10.y);
                    float c10 = 1.0 - min(line10, 1.0);

                    // Primary Axes (Red X-Axis at Z=0, Blue Z-Axis at X=0)
                    bool isXAxis = abs(in.worldPos.z) < max(der.y * 1.5, 0.08);
                    bool isZAxis = abs(in.worldPos.x) < max(der.x * 1.5, 0.08);

                    float4 gridColor = float4(0.45, 0.48, 0.55, 0.35 * c1); // Minor 1m lines
                    if (c10 > 0.01) {
                        gridColor = mix(gridColor, float4(0.75, 0.80, 0.90, 0.70), c10); // Major 10m lines
                    }
                    if (isXAxis) {
                        gridColor = float4(0.95, 0.25, 0.25, 0.95); // Red X-Axis
                    }
                    if (isZAxis) {
                        gridColor = float4(0.25, 0.55, 0.95, 0.95); // Blue Z-Axis
                    }

                    if (gridColor.a < 0.01) {
                        discard_fragment();
                    }

                    // Distance Horizon Fog Attenuation
                    float dist = length(in.worldPos.xz);
                    float fade = clamp(1.0 - (dist / 450.0), 0.0, 1.0);
                    gridColor.a *= fade;

                    return gridColor;
                }

                // Mode 0: Standard Opaque 3D Mesh Lighting
                float4 baseColor = in.color;
                if (uniforms.hasTexture == 1) {
                    float4 texColor = tex.sample(smp, in.uv);
                    if (texColor.a < 0.25) {
                        discard_fragment();
                    }
                    baseColor *= texColor;
                }

                float3 N = normalize(in.worldNormal);
                int lightType = int(uniforms.lightPosType.w);
                float3 L;
                float attenuation = 1.0;

                if (lightType == 0) {
                    L = normalize(-uniforms.lightDirIntensity.xyz);
                } else {
                    float3 lightPos = uniforms.lightPosType.xyz;
                    float3 lightVector = lightPos - in.worldPos;
                    float dist = length(lightVector);
                    L = normalize(lightVector);
                    attenuation = 1.0 / (1.0 + 0.1 * dist + 0.02 * dist * dist);

                    if (lightType == 2) {
                        float3 spotDir = normalize(-uniforms.lightDirIntensity.xyz);
                        float theta = dot(L, spotDir);
                        float innerCutoff = 0.93;
                        float outerCutoff = 0.82;
                        float spotIntensity = clamp((theta - outerCutoff) / (innerCutoff - outerCutoff), 0.0, 1.0);
                        attenuation *= spotIntensity;
                    }
                }

                float intensity = uniforms.lightDirIntensity.w;
                float diff = max(dot(N, L), 0.0);
                float3 diffuse = diff * uniforms.lightColor.rgb * intensity * attenuation;
                float3 ambient = max(uniforms.ambientColor.rgb, float3(0.35, 0.35, 0.38));

                float3 finalRGB = (ambient + diffuse) * baseColor.rgb;

                if ((uniforms.passFlags & 2u) != 0u || uniforms.matParams.x > 0.0) {
                    float3 V = normalize(-in.worldPos);
                    float3 R = reflect(V, N);
                    float3 envSky = float3(0.85, 0.90, 1.0);
                    float3 envGround = float3(0.15, 0.20, 0.30);
                    float3 envColor = mix(envGround, envSky, clamp(R.y * 0.5 + 0.5, 0.0, 1.0));
                    float reflectStrength = clamp(uniforms.matParams.x, 0.0, 1.0);
                    finalRGB = mix(finalRGB, envColor, reflectStrength * 0.4);
                }

                float fogStart = 20.0;
                float fogEnd = 150.0;
                float fogFactor = clamp((in.viewDist - fogStart) / (fogEnd - fogStart), 0.0, 1.0);
                float3 fogColor = float3(0.12, 0.13, 0.16);
                finalRGB = mix(finalRGB, fogColor, fogFactor);

                return float4(finalRGB, baseColor.a);
            }
            """;

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
            """;

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

        invokePPPP(colorAttach3D0, sel_registerName("setBlendingEnabled:"), 1L, send);
        invokePPPP(colorAttach3D0, sel_registerName("setSourceRGBBlendFactor:"), 4L, send);       // SourceAlpha
        invokePPPP(colorAttach3D0, sel_registerName("setDestinationRGBBlendFactor:"), 5L, send);  // OneMinusSourceAlpha
        invokePPPP(colorAttach3D0, sel_registerName("setSourceAlphaBlendFactor:"), 4L, send);
        invokePPPP(colorAttach3D0, sel_registerName("setDestinationAlphaBlendFactor:"), 5L, send);

        invokePPPP(pipeDesc3D, sel_registerName("setDepthAttachmentPixelFormat:"), 252L, send);

        pipelineState3D = invokePPPPP(d, sel_registerName("newRenderPipelineStateWithDescriptor:error:"), pipeDesc3D, 0L, send);

        long depthDescClass = objc_getClass("MTLDepthStencilDescriptor");
        long depthDesc3D = invokePPP(depthDescClass, sel_registerName("new"), send);
        invokePPPP(depthDesc3D, sel_registerName("setDepthCompareFunction:"), 1L, send); // Less
        invokePPPP(depthDesc3D, sel_registerName("setDepthWriteEnabled:"), 1L, send);    // Depth write ON for 3D models
        depthStencilState3D = invokePPPP(d, sel_registerName("newDepthStencilStateWithDescriptor:"), depthDesc3D, send);

        long depthDescShadow = invokePPP(depthDescClass, sel_registerName("new"), send);
        invokePPPP(depthDescShadow, sel_registerName("setDepthCompareFunction:"), 3L, send); // LessEqual
        invokePPPP(depthDescShadow, sel_registerName("setDepthWriteEnabled:"), 0L, send);   // Depth write OFF!
        depthStencilStateShadow = invokePPPP(d, sel_registerName("newDepthStencilStateWithDescriptor:"), depthDescShadow, send);

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

        long depthDescUIInst = invokePPP(depthDescClass, sel_registerName("new"), send);
        invokePPPP(depthDescUIInst, sel_registerName("setDepthCompareFunction:"), 7L, send);
        invokePPPP(depthDescUIInst, sel_registerName("setDepthWriteEnabled:"), 0L, send);
        depthStencilStateUI = invokePPPP(d, sel_registerName("newDepthStencilStateWithDescriptor:"), depthDescUIInst, send);

        return pipelineState3D != 0L && pipelineStateUI != 0L;
    }

    public long getPipelineState3D() { return pipelineState3D; }
    public long getDepthStencilState3D() { return depthStencilState3D; }
    public long getDepthStencilStateShadow() { return depthStencilStateShadow; }
    public long getPipelineStateUI() { return pipelineStateUI; }
    public long getDepthStencilStateUI() { return depthStencilStateUI; }
}
