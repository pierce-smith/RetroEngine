package com.retro.engine.backend.metal;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.macosx.ObjCRuntime.*;

/**
 * SRP Domain Manager: Manages Metal GPU texture handle caching, LRU eviction,
 * default white texture fallback, and sampler state lifecycle.
 */
public class MetalTextureRegistry {

    private static final int DEFAULT_MAX_CACHE_ENTRIES = 128;

    private final int maxTextureCacheSize;
    private final Map<String, Long> gpuTextureCache;

    private long defaultTextureHandle = 0L;
    private long samplerStateHandle = 0L;

    public MetalTextureRegistry() {
        this(DEFAULT_MAX_CACHE_ENTRIES);
    }

    public MetalTextureRegistry(int maxTextureCacheSize) {
        this.maxTextureCacheSize = Math.max(8, maxTextureCacheSize);
        this.gpuTextureCache = Collections.synchronizedMap(
                new LinkedHashMap<String, Long>(16, 0.75f, true)
        );
    }

    public void initDefaultTextureAndSampler(MetalDevice metalDevice) {
        if (metalDevice == null || metalDevice.getDevice() == 0L) return;

        long device = metalDevice.getDevice();
        long objc_msgSend = metalDevice.getObjcMsgSend();

        byte[] whitePixel = new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255};
        long descClass = objc_getClass("MTLTextureDescriptor");
        long selTex2D = sel_registerName("texture2DDescriptorWithPixelFormat:width:height:mipmapped:");
        long desc = invokePPPPPPP(descClass, selTex2D, 70L, 1L, 1L, 0L, objc_msgSend);

        defaultTextureHandle = invokePPPP(device, sel_registerName("newTextureWithDescriptor:"), desc, objc_msgSend);

        if (defaultTextureHandle != 0L) {
            ByteBuffer buf = MemoryUtil.memAlloc(4);
            buf.put(whitePixel).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer region = stack.malloc(8 * 6);
                region.putLong(0).putLong(0).putLong(0);
                region.putLong(1).putLong(1).putLong(1);
                region.flip();

                long sel_replaceRegion = sel_registerName("replaceRegion:mipmapLevel:withBytes:bytesPerRow:");
                invokePPPPPPP(defaultTextureHandle, sel_replaceRegion, MemoryUtil.memAddress(region), 0L, MemoryUtil.memAddress(buf), 4L, objc_msgSend);
            }
            MemoryUtil.memFree(buf);
        }

        long samplerDescClass = objc_getClass("MTLSamplerDescriptor");
        long samplerDesc = invokePPP(samplerDescClass, sel_registerName("new"), objc_msgSend);
        invokePPPP(samplerDesc, sel_registerName("setMinFilter:"), 1L, objc_msgSend);
        invokePPPP(samplerDesc, sel_registerName("setMagFilter:"), 1L, objc_msgSend);
        samplerStateHandle = invokePPPP(device, sel_registerName("newSamplerStateWithDescriptor:"), samplerDesc, objc_msgSend);
    }

    public long getDefaultTextureHandle(MetalDevice metalDevice) {
        if (defaultTextureHandle == 0L) {
            initDefaultTextureAndSampler(metalDevice);
        }
        return defaultTextureHandle;
    }

    public long getSamplerStateHandle(MetalDevice metalDevice) {
        if (samplerStateHandle == 0L) {
            initDefaultTextureAndSampler(metalDevice);
        }
        return samplerStateHandle;
    }

    public long getOrCreateTexture(MetalDevice metalDevice, String texturePath, byte[] pixels, int texW, int texH,
                                    Queue<MetalGpuCache.DeferredHandle> disposalQueue, long currentFrame) {
        if (texturePath == null || pixels == null || pixels.length == 0 || metalDevice == null) return 0L;

        Long existing = gpuTextureCache.get(texturePath);
        if (existing != null) return existing;

        evictLruTexturesIfNeeded(disposalQueue, currentFrame);

        MetalTexture tex = new MetalTexture();
        tex.uploadTexture(metalDevice.getDevice(), metalDevice.getObjcMsgSend(), pixels, texW, texH);
        long handle = tex.getBindableTexture();
        if (handle != 0L) {
            gpuTextureCache.put(texturePath, handle);
        }
        return handle;
    }

    private void evictLruTexturesIfNeeded(Queue<MetalGpuCache.DeferredHandle> disposalQueue, long currentFrame) {
        synchronized (gpuTextureCache) {
            while (gpuTextureCache.size() >= maxTextureCacheSize) {
                Iterator<Map.Entry<String, Long>> it = gpuTextureCache.entrySet().iterator();
                if (!it.hasNext()) break;

                Map.Entry<String, Long> lru = it.next();
                Long handle = lru.getValue();
                it.remove();

                if (handle != null && handle != 0L && disposalQueue != null) {
                    disposalQueue.add(new MetalGpuCache.DeferredHandle(handle, currentFrame + 3));
                }
            }
        }
    }

    public void cleanup(MetalDevice metalDevice) {
        if (metalDevice != null && metalDevice.getObjcMsgSend() != 0L) {
            long send = metalDevice.getObjcMsgSend();
            long selRelease = sel_registerName("release");

            synchronized (gpuTextureCache) {
                for (Long handle : gpuTextureCache.values()) {
                    if (handle != null && handle != 0L) {
                        invokePPP(handle, selRelease, send);
                    }
                }
                gpuTextureCache.clear();
            }

            if (defaultTextureHandle != 0L) {
                invokePPP(defaultTextureHandle, selRelease, send);
                defaultTextureHandle = 0L;
            }
            if (samplerStateHandle != 0L) {
                invokePPP(samplerStateHandle, selRelease, send);
                samplerStateHandle = 0L;
            }
        } else {
            gpuTextureCache.clear();
        }
    }
}
