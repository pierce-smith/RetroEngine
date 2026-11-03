package com.retro.engine.ui;

import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sound.sampled.*;

/**
 * Non-blocking fire-and-forget UI Sound Manager for editor button presses,
 * dialog transitions, entity spawning, entity duplication, deletion, and context menus.
 *
 * Clips are classpath resources under {@code /audio/ui/} (src/main/resources/audio/ui), so they ship inside the
 * editor jar and never appear as loose files in a project's Assets/ or in exported distributions. Decoded
 * STBVorbis buffers are freed from the native C heap immediately after conversion to PCM.
 */
public class UISoundPlayer {

    private static final String RESOURCE_DIR = "/audio/ui/";

    public enum UISound {
        CLICK("click.ogg"),
        OPEN_MODAL("open.ogg"),
        CLOSE_MODAL("close.ogg"),
        SPAWN("spawn.ogg"),
        DELETE("delete.ogg"),
        DUPLICATE("duplicate.ogg", "spawn.ogg"),
        TOGGLE("toggle.ogg");

        private final String fileName;
        private final String fallbackFileName;

        UISound(String fileName) {
            this(fileName, null);
        }

        UISound(String fileName, String fallbackFileName) {
            this.fileName = fileName;
            this.fallbackFileName = fallbackFileName;
        }

        /** Classpath resource path of this clip, e.g. {@code /audio/ui/click.ogg}. */
        public String getResourcePath() {
            return RESOURCE_DIR + fileName;
        }

        /** Resource used when this clip is not bundled (DUPLICATE reuses spawn.ogg), or null. */
        public String getFallbackResourcePath() {
            return fallbackFileName != null ? RESOURCE_DIR + fallbackFileName : null;
        }
    }

    private static final Map<UISound, byte[]> soundBufferCache = new ConcurrentHashMap<>();
    private static final Map<UISound, AudioFormat> soundFormatCache = new ConcurrentHashMap<>();
    private static volatile float masterUiVolume = 0.8f;
    private static volatile boolean soundEnabled = true;

    /**
     * Plays a UI sound effect asynchronously on a background virtual thread.
     */
    public static void play(UISound sound) {
        if (!soundEnabled || sound == null) return;

        Thread.ofVirtual().start(() -> {
            try {
                byte[] pcmData = soundBufferCache.get(sound);
                AudioFormat format = soundFormatCache.get(sound);

                if (pcmData == null || format == null) {
                    if (!loadAndCacheSound(sound)) {
                        return;
                    }
                    pcmData = soundBufferCache.get(sound);
                    format = soundFormatCache.get(sound);
                }

                if (pcmData != null && format != null) {
                    ByteArrayInputStream bais = new ByteArrayInputStream(pcmData);
                    AudioInputStream ais = new AudioInputStream(bais, format, pcmData.length / format.getFrameSize());

                    Clip clip = AudioSystem.getClip();
                    clip.open(ais);

                    if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                        FloatControl gainControl = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                        float targetGain = Math.max(0.0001f, Math.min(1.0f, masterUiVolume));
                        float dB = (float) (Math.log10(targetGain) * 20.0);
                        dB = Math.max(gainControl.getMinimum(), Math.min(gainControl.getMaximum(), dB));
                        gainControl.setValue(dB);
                    }

                    clip.addLineListener(event -> {
                        if (event.getType() == LineEvent.Type.STOP) {
                            clip.close();
                        }
                    });

                    clip.start();
                }
            } catch (Exception ignored) {
                // Non-blocking fire-and-forget
            }
        });
    }

    private static boolean loadAndCacheSound(UISound sound) {
        String resource = sound.getResourcePath();
        InputStream in = UISoundPlayer.class.getResourceAsStream(resource);
        if (in == null && sound.getFallbackResourcePath() != null) {
            resource = sound.getFallbackResourcePath();
            in = UISoundPlayer.class.getResourceAsStream(resource);
        }
        if (in == null) return false;

        try (InputStream stream = in) {
            byte[] encodedBytes = stream.readAllBytes();
            String lower = resource.toLowerCase();
            if (lower.endsWith(".ogg")) {
                return decodeOggVorbis(sound, encodedBytes);
            }
            if (lower.endsWith(".wav") || lower.endsWith(".aiff")) {
                try (AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(encodedBytes))) {
                    soundBufferCache.put(sound, ais.readAllBytes());
                    soundFormatCache.put(sound, ais.getFormat());
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Decodes an in-memory Ogg Vorbis clip to 16-bit little-endian PCM and caches it. */
    private static boolean decodeOggVorbis(UISound sound, byte[] encodedBytes) {
        ByteBuffer encoded = null;
        ShortBuffer rawAudio = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            encoded = MemoryUtil.memAlloc(encodedBytes.length).put(encodedBytes).flip();
            IntBuffer channels = stack.mallocInt(1);
            IntBuffer sampleRate = stack.mallocInt(1);
            rawAudio = STBVorbis.stb_vorbis_decode_memory(encoded, channels, sampleRate);
            if (rawAudio == null) return false;

            byte[] pcm = new byte[rawAudio.remaining() * 2];
            for (int i = 0; rawAudio.hasRemaining(); i += 2) {
                short sample = rawAudio.get();
                pcm[i] = (byte) (sample & 0xFF);
                pcm[i + 1] = (byte) ((sample >> 8) & 0xFF);
            }
            soundBufferCache.put(sound, pcm);
            soundFormatCache.put(sound, new AudioFormat(sampleRate.get(0), 16, channels.get(0), true, false));
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (rawAudio != null) MemoryUtil.memFree(rawAudio);
            if (encoded != null) MemoryUtil.memFree(encoded);
        }
    }

    public static void setMasterUiVolume(float volume) {
        masterUiVolume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    public static float getMasterUiVolume() {
        return masterUiVolume;
    }

    public static void setSoundEnabled(boolean enabled) {
        soundEnabled = enabled;
    }

    public static boolean isSoundEnabled() {
        return soundEnabled;
    }
}
