package com.retro.engine.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

class GameIconTest {

    /** The packaged editor has no source tree, so exports rely on these coming out of the jar. */
    @Test
    void bundledIconsExtractForEveryPlatform() throws Exception {
        for (StandaloneExporter.Platform p : StandaloneExporter.Platform.values()) {
            File icon = GameIcon.bundledIcon(p.iconExtension());
            assertNotNull(icon, p.name());
            assertArrayEquals(Files.readAllBytes(Path.of(StandaloneExporter.defaultIconPath(p))), Files.readAllBytes(icon.toPath()));
        }
    }

    @Test
    void defaultIcoGivesItsLargestImage() throws Exception {
        byte[] png = GameIcon.pngFromIco(Files.readAllBytes(Path.of("src/main/resources/icons/icon.ico")));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(256, image.getWidth());
        assertEquals(256, image.getHeight());
    }

    @Test
    void bmpIcoEntryDecodesWithAlpha() throws Exception {
        int size = 2;
        ByteBuffer dib = ByteBuffer.allocate(40 + size * size * 4 + size * 4).order(ByteOrder.LITTLE_ENDIAN);
        dib.putInt(40).putInt(size).putInt(size * 2).putShort((short) 1).putShort((short) 32).putInt(0);
        dib.position(40);
        // Bottom-up BGRA: bottom row red then transparent, top row green then blue
        dib.put(new byte[] { 0, 0, (byte) 255, (byte) 255,  0, 0, 0, 0 });
        dib.put(new byte[] { 0, (byte) 255, 0, (byte) 255,  (byte) 255, 0, 0, (byte) 255 });
        byte[] entry = dib.array();
        ByteBuffer ico = ByteBuffer.allocate(6 + 16 + entry.length).order(ByteOrder.LITTLE_ENDIAN);
        ico.putShort((short) 0).putShort((short) 1).putShort((short) 1);
        ico.put((byte) size).put((byte) size).put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32)
            .putInt(entry.length).putInt(22);
        ico.put(entry);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(GameIcon.pngFromIco(ico.array())));
        assertEquals(0xFF00FF00, image.getRGB(0, 0)); // top-left green
        assertEquals(0xFF0000FF, image.getRGB(1, 0)); // top-right blue
        assertEquals(0xFFFF0000, image.getRGB(0, 1)); // bottom-left red
        assertEquals(0, image.getRGB(1, 1) >>> 24);   // bottom-right transparent
    }

    @Test
    void defaultWindowIconIsTheEnginePng() throws Exception {
        byte[] png = GameIcon.windowIconPng(null);
        assertNotNull(png);
        assertTrue(ImageIO.read(new ByteArrayInputStream(png)).getWidth() > 0);
    }
}
