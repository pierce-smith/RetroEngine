package com.retro.engine.editor.audio;

import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-blocking fire-and-forget UI Sound Manager for editor button presses,
 * dialog transitions, entity spawning, entity duplication, deletion, and context menus.
 */
public class UISoundPlayer {

    public enum UISound {
        CLICK("Assets/Audio/UI/click.ogg"),
        OPEN_MODAL("Assets/Audio/UI/open.ogg"),
        CLOSE_MODAL("Assets/Audio/UI/close.ogg"),
        SPAWN("Assets/Audio/UI/spawn.ogg"),
        DELETE("Assets/Audio/UI/delete.ogg"),
        DUPLICATE("Assets/Audio/UI/duplicate.ogg"),
        TOGGLE("Assets/Audio/UI/toggle.ogg");

        private final String defaultPath;

        UISound(String defaultPath) {
            this.defaultPath = defaultPath;
        }

        public String getDefaultPath() {
            return defaultPath;
        }
    }

    private static final Map<UISound, byte[]> soundBufferCache = new ConcurrentHashMap<>();
    private static final Map<UISound, AudioFormat> soundFormatCache = new ConcurrentHashMap<>();
    private static float masterUiVolume = 0.8f;
    private static boolean soundEnabled = true;

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
        String path = sound.getDefaultPath();
        File file = new File(path);

        if (!file.exists()) {
            File altFile = new File("Assets/Audio/UI/" + sound.name().toLowerCase() + ".ogg");
            if (altFile.exists()) {
                file = altFile;
            } else if (sound == UISound.DUPLICATE) {
                // Fallback for DUPLICATE if duplicate.ogg does not exist on disk
                file = new File(UISound.SPAWN.getDefaultPath());
                if (!file.exists()) {
                    file = new File("Assets/Audio/UI/spawn.ogg");
                }
            }
        }

        if (!file.exists()) {
            return false;
        }

        try {
            AudioInputStream ais = null;
            String lowerPath = file.getName().toLowerCase();

            if (lowerPath.endsWith(".ogg")) {
                ais = loadOggVorbisStream(file.getAbsolutePath());
            } else if (lowerPath.endsWith(".wav") || lowerPath.endsWith(".aiff")) {
                ais = AudioSystem.getAudioInputStream(file);
            }

            if (ais != null) {
                byte[] pcmBytes = ais.readAllBytes();
                soundBufferCache.put(sound, pcmBytes);
                soundFormatCache.put(sound, ais.getFormat());
                ais.close();
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static AudioInputStream loadOggVorbisStream(String filePath) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer channelsBuffer = stack.mallocInt(1);
            IntBuffer sampleRateBuffer = stack.mallocInt(1);

            ShortBuffer rawAudio = STBVorbis.stb_vorbis_decode_filename(filePath, channelsBuffer, sampleRateBuffer);
            if (rawAudio == null) return null;

            int channels = channelsBuffer.get(0);
            int sampleRate = sampleRateBuffer.get(0);

            byte[] pcmBytes = new byte[rawAudio.remaining() * 2];
            int idx = 0;
            while (rawAudio.hasRemaining()) {
                short sample = rawAudio.get();
                pcmBytes[idx++] = (byte) (sample & 0xFF);
                pcmBytes[idx++] = (byte) ((sample >> 8) & 0xFF);
            }

            AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
            ByteArrayInputStream bais = new ByteArrayInputStream(pcmBytes);
            return new AudioInputStream(bais, format, pcmBytes.length / format.getFrameSize());
        } catch (Exception e) {
            return null;
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
