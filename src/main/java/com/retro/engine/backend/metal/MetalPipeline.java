package com.retro.engine.backend.metal;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.macosx.LibSystem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.objc_getClass;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_registerName;

/**
 * MetalPipeline sets up the RenderWare 3.5 multi-pass shader pipeline featuring:
 * 1. 22-float static / 30-float skinned vertex strides & multi-light type shading (Directional, Point, Spot).
 * 2. Multi-channel materials (albedo, normal, specular/roughness, lightmap/AO, emissive) read through Metal 3 argument buffers.
 * 3. Dynamic Ground Entity Shadow Blobs and the Procedural Ray-Plane Anti-Aliased Infinite Shader Grid.
 * 4. Decoupled window composition & 2D UI pipelines with zero depth attachment dependency.
 * 5. 2048x2048 Depth32Float directional shadow map depth pass with 3x3 PCF sampling in the lit fragment path.
 * <p>
 * MSL sources live in {@code /shaders/metal/*.metal} on the classpath. When the build's compileMetalShaders task
 * produced a {@code .metallib} whose recorded source hash matches the shipped source, the precompiled library is
 * loaded; otherwise (non-macOS builds, missing Metal toolchain, stale output) the source is compiled at runtime.
 */
public class MetalPipeline {

    private static final String SHADER_DIR = "/shaders/metal/";

    private static final long MTL_PIXEL_FORMAT_BGRA8_UNORM = 80L;
    /** Color-ID picking target format (MTLPixelFormatRGBA8Uint). */
    public static final long MTL_PIXEL_FORMAT_RGBA8_UINT = 73L;
    private static final long MTL_PIXEL_FORMAT_DEPTH32_FLOAT = 252L;
    private static final long MTL_COMPARE_LESS = 1L;
    private static final long MTL_COMPARE_LESS_EQUAL = 3L;
    private static final long MTL_COMPARE_ALWAYS = 7L;

    private enum Blend { NONE, ALPHA, UI }

    private long pipelineState3D = 0L;
    private long pipelineStateComposition = 0L;
    private long depthStencilState3D = 0L;
    private long depthStencilStateShadow = 0L;
    private long pipelineStateShadowMap = 0L;
    private long depthStencilStateShadowMap = 0L;
    private long pipelineStatePick = 0L;

    private long pipelineStateUI = 0L;
    private long depthStencilStateUI = 0L;

    public boolean initialize(MetalDevice device) {
        // Library compilation and NSError reporting hand back autoreleased objects; startup has no enclosing pool
        long pool = device.pushAutoreleasePool();
        try {
            return buildPipelines(device);
        } finally {
            device.drainAutoreleasePool(pool);
        }
    }

    private boolean buildPipelines(MetalDevice device) {
        long libLit = loadLibrary(device, "blinn_phong");
        long libShadow = loadLibrary(device, "shadow_depth");
        long libUI = loadLibrary(device, "ui");
        long libPick = loadLibrary(device, "entity_id");
        if (libLit == 0L || libShadow == 0L || libUI == 0L) {
            device.release(libLit);
            device.release(libShadow);
            device.release(libUI);
            device.release(libPick);
            return false;
        }

        long vertLit = newFunction(device, libLit, "vertexMain");
        long fragLit = newFunction(device, libLit, "fragmentMain");
        long vertShadow = newFunction(device, libShadow, "shadowVertexMain");
        long fragShadow = newFunction(device, libShadow, "shadowFragmentMain");
        long vertUI = newFunction(device, libUI, "uiVertexMain");
        long fragUI = newFunction(device, libUI, "uiFragmentMain");
        long vertComp = newFunction(device, libUI, "compositeVertexMain");
        long fragComp = newFunction(device, libUI, "compositeFragmentMain");
        // Picking is optional: a missing entity_id library only disables GPU picks
        long vertPick = libPick != 0L ? newFunction(device, libPick, "pickVertexMain") : 0L;
        long fragPick = libPick != 0L ? newFunction(device, libPick, "pickFragmentMain") : 0L;
        // Functions retain their library; pipeline states retain what they need from the functions
        device.release(libLit);
        device.release(libShadow);
        device.release(libUI);
        device.release(libPick);

        // 1. Offscreen 3D pipeline: alpha-blended BGRA8 color + Depth32Float
        pipelineState3D = createPipeline(device, "offscreen 3D", vertLit, fragLit, Blend.ALPHA, MTL_PIXEL_FORMAT_DEPTH32_FLOAT);
        // 2. Viewport composition quad: opaque 1:1 copy of the offscreen color, no depth attachment
        pipelineStateComposition = createPipeline(device, "viewport composition", vertComp, fragComp, Blend.NONE, 0L);
        // 3. Directional shadow map depth pipeline: no color attachments, Depth32Float
        pipelineStateShadowMap = createPipeline(device, "directional shadow map", vertShadow, fragShadow, null, MTL_PIXEL_FORMAT_DEPTH32_FLOAT);
        // 4. 2D UI overlay: alpha-blended color, no depth attachment
        pipelineStateUI = createPipeline(device, "2D UI", vertUI, fragUI, Blend.UI, 0L);
        // 5. Color-ID picking: unblended RGBA8Uint + Depth32Float
        pipelineStatePick = createPipeline(device, "color-ID picking", vertPick, fragPick, Blend.NONE, MTL_PIXEL_FORMAT_RGBA8_UINT, MTL_PIXEL_FORMAT_DEPTH32_FLOAT);

        for (long fn : new long[] { vertLit, fragLit, vertShadow, fragShadow, vertUI, fragUI, vertComp, fragComp, vertPick, fragPick }) {
            device.release(fn);
        }

        depthStencilState3D = createDepthStencilState(device, MTL_COMPARE_LESS, true);              // Opaque 3D models
        depthStencilStateShadow = createDepthStencilState(device, MTL_COMPARE_LESS_EQUAL, false);   // Grid & blobs: test, no write
        depthStencilStateShadowMap = createDepthStencilState(device, MTL_COMPARE_LESS_EQUAL, true); // Shadow casters
        depthStencilStateUI = createDepthStencilState(device, MTL_COMPARE_ALWAYS, false);

        if (pipelineStateShadowMap == 0L) {
            System.err.println("[MetalPipeline] ERROR: Failed to create directional shadow map pipeline state.");
        }
        return pipelineState3D != 0L && pipelineStateComposition != 0L && pipelineStateUI != 0L;
    }

    private long createPipeline(MetalDevice device, String label, long vertexFn, long fragmentFn, Blend blend, long depthFormat) {
        return createPipeline(device, label, vertexFn, fragmentFn, blend, MTL_PIXEL_FORMAT_BGRA8_UNORM, depthFormat);
    }

    private long createPipeline(MetalDevice device, String label, long vertexFn, long fragmentFn, Blend blend, long colorFormat, long depthFormat) {
        if (vertexFn == 0L || fragmentFn == 0L) return 0L;
        long send = device.getObjcMsgSend();

        long desc = invokePPP(objc_getClass("MTLRenderPipelineDescriptor"), sel_registerName("new"), send);
        invokePPPP(desc, sel_registerName("setVertexFunction:"), vertexFn, send);
        invokePPPP(desc, sel_registerName("setFragmentFunction:"), fragmentFn, send);

        if (blend != null) {
            long colorAttachments = invokePPP(desc, sel_registerName("colorAttachments"), send);
            long color0 = invokePPPP(colorAttachments, sel_registerName("objectAtIndexedSubscript:"), 0L, send);
            invokePPPP(color0, sel_registerName("setPixelFormat:"), colorFormat, send);
            if (blend != Blend.NONE) {
                invokePPPP(color0, sel_registerName("setBlendingEnabled:"), 1L, send);
                invokePPPP(color0, sel_registerName("setSourceRGBBlendFactor:"), 4L, send);       // SourceAlpha
                invokePPPP(color0, sel_registerName("setDestinationRGBBlendFactor:"), 5L, send);  // OneMinusSourceAlpha
                // UI accumulates coverage into the drawable's alpha (transparent glyph padding never punches holes); 3D blends alpha like color
                invokePPPP(color0, sel_registerName("setSourceAlphaBlendFactor:"), blend == Blend.UI ? 1L : 4L, send);
                invokePPPP(color0, sel_registerName("setDestinationAlphaBlendFactor:"), 5L, send);
            }
        }
        invokePPPP(desc, sel_registerName("setDepthAttachmentPixelFormat:"), depthFormat, send);

        long state;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long pError = stack.ncalloc(8, 1, 8);
            state = invokePPPPP(device.getDevice(), sel_registerName("newRenderPipelineStateWithDescriptor:error:"), desc, pError, send);
            if (state == 0L) {
                System.err.println("[MetalPipeline] ERROR: " + label + " pipeline creation failed: " + describeError(device, MemoryUtil.memGetAddress(pError)));
            }
        }
        device.release(desc);
        return state;
    }

    private static long createDepthStencilState(MetalDevice device, long compareFunction, boolean depthWrite) {
        long send = device.getObjcMsgSend();
        long desc = invokePPP(objc_getClass("MTLDepthStencilDescriptor"), sel_registerName("new"), send);
        invokePPPP(desc, sel_registerName("setDepthCompareFunction:"), compareFunction, send);
        invokePPPP(desc, sel_registerName("setDepthWriteEnabled:"), depthWrite ? 1L : 0L, send);
        long state = invokePPPP(device.getDevice(), sel_registerName("newDepthStencilStateWithDescriptor:"), desc, send);
        device.release(desc);
        return state;
    }

    private static long newFunction(MetalDevice device, long library, String name) {
        long nsName = device.createNSString(name);
        long fn = invokePPPP(library, sel_registerName("newFunctionWithName:"), nsName, device.getObjcMsgSend());
        device.release(nsName);
        if (fn == 0L) System.err.println("[MetalPipeline] ERROR: Shader function not found: " + name);
        return fn;
    }

    /** Loads {@code name.metal} from the classpath, preferring a matching precompiled {@code name.metallib}. */
    private long loadLibrary(MetalDevice device, String name) {
        byte[] source = readResource(SHADER_DIR + name + ".metal");
        if (source == null) {
            System.err.println("[MetalPipeline] ERROR: Missing shader resource " + SHADER_DIR + name + ".metal");
            return 0L;
        }

        long lib = loadPrecompiledLibrary(device, name, source);
        if (lib != 0L) {
            System.out.println("[MetalPipeline] Loaded precompiled Metal library: " + name + ".metallib");
            return lib;
        }

        long send = device.getObjcMsgSend();
        long sourceNS = device.createNSString(new String(source, StandardCharsets.UTF_8));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long pError = stack.ncalloc(8, 1, 8);
            lib = invokePPPPPP(device.getDevice(), sel_registerName("newLibraryWithSource:options:error:"), sourceNS, 0L, pError, send);
            if (lib == 0L) {
                System.err.println("[MetalPipeline] ERROR: Failed to compile " + name + ".metal: " + describeError(device, MemoryUtil.memGetAddress(pError)));
            } else {
                System.out.println("[MetalPipeline] Compiled Metal library from source: " + name + ".metal");
            }
        } finally {
            device.release(sourceNS);
        }
        return lib;
    }

    /**
     * Returns 0L unless the build shipped {@code name.metallib} together with a {@code name.metallib.sha256} that matches
     * the SHA-256 of the shipped source, so an out-of-date library is never paired with newer Java-side layouts.
     */
    private static long loadPrecompiledLibrary(MetalDevice device, String name, byte[] source) {
        byte[] metallib = readResource(SHADER_DIR + name + ".metallib");
        byte[] recordedHash = readResource(SHADER_DIR + name + ".metallib.sha256");
        if (metallib == null || recordedHash == null || metallib.length == 0) return 0L;
        if (!new String(recordedHash, StandardCharsets.US_ASCII).trim().equalsIgnoreCase(sha256Hex(source))) {
            System.out.println("[MetalPipeline] Precompiled " + name + ".metallib is stale; compiling from source.");
            return 0L;
        }

        long dispatchDataCreate = LibSystem.getLibrary().getFunctionAddress("dispatch_data_create");
        if (dispatchDataCreate == 0L) return 0L;

        long send = device.getObjcMsgSend();
        ByteBuffer bytes = MemoryUtil.memAlloc(metallib.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            bytes.put(metallib).flip();
            // A null destructor selects DISPATCH_DATA_DESTRUCTOR_DEFAULT, which copies the bytes
            long data = invokePPPPP(MemoryUtil.memAddress(bytes), (long) metallib.length, 0L, 0L, dispatchDataCreate);
            if (data == 0L) return 0L;
            long pError = stack.ncalloc(8, 1, 8);
            long lib = invokePPPPP(device.getDevice(), sel_registerName("newLibraryWithData:error:"), data, pError, send);
            if (lib == 0L) {
                System.err.println("[MetalPipeline] Precompiled " + name + ".metallib rejected (" + describeError(device, MemoryUtil.memGetAddress(pError)) + "); compiling from source.");
            }
            device.release(data);
            return lib;
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    private static String describeError(MetalDevice device, long nsError) {
        if (nsError == 0L) return "unknown error";
        long send = device.getObjcMsgSend();
        long description = invokePPP(nsError, sel_registerName("localizedDescription"), send);
        long utf8 = description != 0L ? invokePPP(description, sel_registerName("UTF8String"), send) : 0L;
        return utf8 != 0L ? MemoryUtil.memUTF8(utf8) : "unknown error";
    }

    private static byte[] readResource(String path) {
        try (InputStream in = MetalPipeline.class.getResourceAsStream(path)) {
            return in != null ? in.readAllBytes() : null;
        } catch (IOException e) {
            System.err.println("[MetalPipeline] Failed to read " + path + ": " + e.getMessage());
            return null;
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public long getPipelineState3D() { return pipelineState3D; }
    public long getPipelineStateComposition() { return pipelineStateComposition; }
    public long getDepthStencilState3D() { return depthStencilState3D; }
    public long getDepthStencilStateShadow() { return depthStencilStateShadow; }
    public long getPipelineStateShadowMap() { return pipelineStateShadowMap; }
    public long getDepthStencilStateShadowMap() { return depthStencilStateShadowMap; }
    public long getPipelineStatePick() { return pipelineStatePick; }
    public long getPipelineStateUI() { return pipelineStateUI; }
    public long getDepthStencilStateUI() { return depthStencilStateUI; }
}
