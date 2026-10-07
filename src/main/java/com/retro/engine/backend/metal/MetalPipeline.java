package com.retro.engine.backend.metal;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.objc_getClass;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_registerName;

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
                float4 color;
                float2 uv;
            };

            struct Uniforms {
                float4x4 mvp;
                uint hasTexture;
            };

            vertex VertexOut vertexMain(uint vertexID [[vertex_id]],
                                        constant float* vertices [[buffer(0)]],
                                        constant Uniforms& uniforms [[buffer(1)]]) {
                VertexOut out;
                int idx = vertexID * 9;
                float4 pos = float4(vertices[idx], vertices[idx+1], vertices[idx+2], 1.0);
                out.position = uniforms.mvp * pos;
                out.color = float4(vertices[idx+3], vertices[idx+4], vertices[idx+5], vertices[idx+6]);
                out.uv = float2(vertices[idx+7], vertices[idx+8]);
                return out;
            }

            fragment float4 fragmentMain(VertexOut in [[stage_in]],
                                          texture2d<float> tex [[texture(0)]],
                                          sampler smp [[sampler(0)]],
                                          constant Uniforms& uniforms [[buffer(1)]]) {
                if (uniforms.hasTexture == 1) {
                    float4 texColor = tex.sample(smp, in.uv);
                    return texColor * in.color;
                }
                return in.color;
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

        // 3D Pipeline
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

        // 3D Depth State
        long depthDescClass = objc_getClass("MTLDepthStencilDescriptor");
        long depthDesc3D = invokePPP(depthDescClass, sel_registerName("new"), send);
        invokePPPP(depthDesc3D, sel_registerName("setDepthCompareFunction:"), 1L, send); // Less
        invokePPPP(depthDesc3D, sel_registerName("setDepthWriteEnabled:"), 1L, send);
        depthStencilState3D = invokePPPP(d, sel_registerName("newDepthStencilStateWithDescriptor:"), depthDesc3D, send);

        // 2D UI Pipeline
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

        // 2D Depth State
        long depthDescUI = invokePPP(depthDescClass, sel_registerName("new"), send);
        invokePPPP(depthDescUI, sel_registerName("setDepthCompareFunction:"), 7L, send); // Always
        invokePPPP(depthDescUI, sel_registerName("setDepthWriteEnabled:"), 0L, send);
        depthStencilStateUI = invokePPPP(d, sel_registerName("newDepthStencilStateWithDescriptor:"), depthDescUI, send);

        return pipelineState3D != 0L && pipelineStateUI != 0L;
    }

    public long getPipelineState3D() { return pipelineState3D; }
    public long getDepthStencilState3D() { return depthStencilState3D; }
    public long getPipelineStateUI() { return pipelineStateUI; }
    public long getDepthStencilStateUI() { return depthStencilStateUI; }
}
