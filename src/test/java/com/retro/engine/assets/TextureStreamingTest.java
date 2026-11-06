package com.retro.engine.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retro.engine.graphics.IRenderBackend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;

class TextureStreamingTest {

    @TempDir Path dir;
    private final AssetStreamer streamer = new AssetStreamer();
    private final AtomicInteger uploads = new AtomicInteger(), running = new AtomicInteger(), peak = new AtomicInteger();
    private volatile boolean refuseUploads = false;
    private volatile long uploadMillis = 0L;

    private final IRenderBackend backend = (IRenderBackend) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { IRenderBackend.class },
        (proxy, method, args) -> {
            if (method.getName().equals("registerTexture")) {
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                Thread.sleep(uploadMillis);
                running.decrementAndGet();
                return refuseUploads ? 0L : (long) uploads.incrementAndGet();
            }
            Class<?> type = method.getReturnType();
            if (type == boolean.class) return false;
            if (type == long.class) return 0L;
            if (type == int.class) return 0;
            if (type == float.class) return 0.0f;
            return null;
        });

    @AfterEach
    void shutDown() {
        streamer.shutdown();
    }

    private String png(String name) throws IOException {
        File file = dir.resolve(name).toFile();
        ImageIO.write(new BufferedImage(8, 4, BufferedImage.TYPE_INT_ARGB), "png", file);
        return file.getPath();
    }

    private static void await(BooleanSupplier done) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(2L);
        assertTrue(done.getAsBoolean(), "timed out");
    }

    @Test
    void pixelsLeaveTheHeapOnceTheGpuHasThem() throws Exception {
        streamer.bindRenderBackend(backend);
        String path = png("a.png");
        streamer.requestTextureAsync(path);
        await(() -> streamer.isTextureLoaded(path));
        assertNotEquals(0L, streamer.getTextureHandle(path));
        assertNull(streamer.getTexture(path).pixels);
        assertEquals(8, streamer.getTexture(path).width);
    }

    @Test
    void pixelsStayWhenThereIsNoBackendToHoldThem() throws Exception {
        String path = png("a.png");
        streamer.requestTextureAsync(path);
        await(() -> streamer.isTextureLoaded(path));
        assertNotNull(streamer.getTexture(path).pixels);
    }

    @Test
    void pixelsStayWhenTheBackendRefusesTheUpload() throws Exception {
        refuseUploads = true;
        streamer.bindRenderBackend(backend);
        String path = png("a.png");
        streamer.requestTextureAsync(path);
        await(() -> streamer.isTextureLoaded(path));
        assertNotNull(streamer.getTexture(path).pixels);
    }

    @Test
    void decodedChannelsLandInRgbaOrder() throws Exception {
        File file = dir.resolve("red.png").toFile();
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 1, 0x80112233);
        ImageIO.write(image, "png", file);
        streamer.requestTextureAsync(file.getPath());
        await(() -> streamer.isTextureLoaded(file.getPath()));
        byte[] p = streamer.getTexture(file.getPath()).pixels;
        assertEquals(0x11, p[12] & 0xFF);
        assertEquals(0x22, p[13] & 0xFF);
        assertEquals(0x33, p[14] & 0xFF);
        assertEquals(0x80, p[15] & 0xFF);
    }

    @Test
    void onlyAFewTexturesDecodeAtOnce() throws Exception {
        uploadMillis = 20L;
        streamer.bindRenderBackend(backend);
        String[] paths = new String[12];
        for (int i = 0; i < paths.length; i++) {
            paths[i] = png("t" + i + ".png");
            streamer.requestTextureAsync(paths[i]);
        }
        await(() -> uploads.get() == paths.length);
        assertTrue(peak.get() <= 2, "peak " + peak.get());
    }

    private void requestForTwentyFrames(String path) throws InterruptedException {
        for (int frame = 0; frame < 20; frame++) {
            streamer.requestTextureAsync(path);
            Thread.sleep(5L);
        }
    }

    @Test
    void aBrokenTextureIsNotRetriedEveryFrame() throws Exception {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream err = System.err;
        System.setErr(new PrintStream(log, true));
        try {
            Path broken = dir.resolve("broken.png");
            Files.write(broken, new byte[] { 1, 2, 3 });
            requestForTwentyFrames(broken.toString());
            // The decode runs on its own thread, so wait for its report rather than assume twenty frames were long enough
            await(() -> log.toString().contains("Could not load texture"));
            requestForTwentyFrames(broken.toString());
        } finally {
            System.setErr(err);
        }
        assertEquals(1L, log.toString().lines().filter(line -> line.contains("Could not load texture")).count());
    }

    @Test
    void aMissingTextureLoadsOnceItsFileAppears() throws Exception {
        Path late = dir.resolve("late.png");
        String path = late.toString();
        streamer.requestTextureAsync(path);
        Thread.sleep(50L);
        assertFalse(streamer.isTextureLoaded(path));

        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", late.toFile());
        await(() -> {
            streamer.requestTextureAsync(path);
            return streamer.isTextureLoaded(path);
        });
    }

    @Test
    void aRepairedTextureLoadsWithoutAnExplicitReload() throws Exception {
        Path broken = dir.resolve("broken.png");
        Files.write(broken, new byte[] { 1, 2, 3 });
        String path = broken.toString();
        streamer.requestTextureAsync(path);
        Thread.sleep(50L);

        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", broken.toFile());
        assertTrue(broken.toFile().setLastModified(System.currentTimeMillis() + 5000L));
        await(() -> {
            streamer.requestTextureAsync(path);
            return streamer.isTextureLoaded(path);
        });
    }
}
