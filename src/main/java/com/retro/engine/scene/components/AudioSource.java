package com.retro.engine.scene.components;

import com.retro.engine.scene.components.Component;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * Encapsulates spatial 3D audio playback parameters, attenuation state,
 * and hardware audio streaming driver (supporting both .wav and .ogg Vorbis clips).
 * Updated with non-destructive spatial gain calculation and STBVorbis buffer cleanup.
 */
public class AudioSource implements Component {
    public enum PlaybackState { PLAYING, PAUSED, STOPPED }

    public String audioAssetPath = "";
    public float volume = 1.0f;       // User-configured base volume (0.0f to 1.0f)
    public float pitch = 1.0f;        // 0.5f to 2.0f
    public boolean loop = false;
    public float minDistance = 1.0f;  // Distance at which sound is at full volume
    public float maxDistance = 25.0f; // Distance beyond which sound is completely silent
    public float attenuation = 1.0f;  // Rolloff factor
    public PlaybackState state = PlaybackState.STOPPED;

    // Native Audio Line Handle
    private transient Clip audioClip = null;

    public AudioSource() {}

    public AudioSource(String audioAssetPath) {
        this.audioAssetPath = (audioAssetPath != null) ? audioAssetPath : "";
    }

    public void play() {
        if (audioAssetPath == null || audioAssetPath.trim().isEmpty()) return;

        try {
            if (audioClip == null || !audioClip.isOpen()) {
                if (!loadAudioClip(audioAssetPath)) {
                    System.err.println("[AudioSource] Failed to load audio clip: " + audioAssetPath);
                    return;
                }
            }

            if (audioClip != null) {
                updateClipGain(this.volume);
                if (loop) {
                    audioClip.loop(Clip.LOOP_CONTINUOUSLY);
                } else {
                    audioClip.setFramePosition(0);
                    audioClip.start();
                }
                this.state = PlaybackState.PLAYING;
            }
        } catch (Exception e) {
            System.err.println("[AudioSource] Playback error on: " + audioAssetPath);
            e.printStackTrace();
        }
    }

    public void pause() {
        if (audioClip != null && audioClip.isRunning()) {
            audioClip.stop();
        }
        this.state = PlaybackState.PAUSED;
    }

    public void stop() {
        if (audioClip != null) {
            audioClip.stop();
            audioClip.setFramePosition(0);
        }
        this.state = PlaybackState.STOPPED;
    }

    public void dispose() {
        if (audioClip != null) {
            try {
                if (audioClip.isRunning()) {
                    audioClip.stop();
                }
                audioClip.close();
            } catch (Exception ignored) {}
            audioClip = null;
        }
        this.state = PlaybackState.STOPPED;
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
        assert (!Float.isNaN(this.volume) && !Float.isInfinite(this.volume))
            : "[FATAL] Invalid volume assigned to AudioSource: " + volume;
        updateClipGain(this.volume);
    }

    public void setPitch(float pitch) {
        this.pitch = Math.max(0.5f, Math.min(2.0f, pitch));
        assert (!Float.isNaN(this.pitch) && !Float.isInfinite(this.pitch))
            : "[FATAL] Invalid pitch assigned to AudioSource: " + pitch;
    }

    public void setDistances(float minDistance, float maxDistance) {
        this.minDistance = Math.max(0.1f, minDistance);
        this.maxDistance = Math.max(this.minDistance, maxDistance);
        assert (this.maxDistance >= this.minDistance)
            : "[FATAL] AudioSource maxDistance (" + this.maxDistance + ") cannot be less than minDistance (" + this.minDistance + ")";
    }

    /**
     * Updates hardware audio clip gain without modifying base user-configured volume.
     */
    public void updateClipGain(float effectiveVolume) {
        if (audioClip != null && audioClip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl gainControl = (FloatControl) audioClip.getControl(FloatControl.Type.MASTER_GAIN);
            float targetGain = Math.max(0.0001f, Math.min(1.0f, effectiveVolume));
            float dB = (float) (Math.log10(targetGain) * 20.0);
            dB = Math.max(gainControl.getMinimum(), Math.min(gainControl.getMaximum(), dB));
            gainControl.setValue(dB);
        }
    }

    /**
     * Evaluates spatial gain based on distance to active listener and applies it to hardware clip.
     */
    public void applySpatialGain(float listenerX, float listenerY, float listenerZ,
                                 float sourceX, float sourceY, float sourceZ) {
        float effectiveGain = calculateSpatialGain(listenerX, listenerY, listenerZ, sourceX, sourceY, sourceZ);
        updateClipGain(effectiveGain);
    }

    /**
     * Calculates spatial audio gain based on Euclidean distance to the active listener.
     */
    public float calculateSpatialGain(float listenerX, float listenerY, float listenerZ,
                                      float sourceX, float sourceY, float sourceZ) {
        assert (!Float.isNaN(listenerX) && !Float.isInfinite(listenerX) &&
                !Float.isNaN(listenerY) && !Float.isInfinite(listenerY) &&
                !Float.isNaN(listenerZ) && !Float.isInfinite(listenerZ) &&
                !Float.isNaN(sourceX) && !Float.isInfinite(sourceX) &&
                !Float.isNaN(sourceY) && !Float.isInfinite(sourceY) &&
                !Float.isNaN(sourceZ) && !Float.isInfinite(sourceZ))
            : "[FATAL] NaN or Infinity detected in spatial audio listener/source coordinates";

        float dx = sourceX - listenerX;
        float dy = sourceY - listenerY;
        float dz = sourceZ - listenerZ;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist <= minDistance) {
            return volume;
        }
        if (dist >= maxDistance) {
            return 0.0f;
        }

        float factor = 1.0f + attenuation * (dist - minDistance);
        float gain = (factor > 0.0001f) ? (volume / factor) : 0.0f;
        gain = Math.max(0.0f, Math.min(1.0f, gain));

        assert (!Float.isNaN(gain) && !Float.isInfinite(gain) && gain >= 0.0f && gain <= 1.0f)
            : "[FATAL] Degenerate spatial audio gain calculated: " + gain;

        return gain;
    }

    private boolean loadAudioClip(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) return false;

        String lowerPath = filePath.toLowerCase();
        try {
            AudioInputStream ais = null;

            if (lowerPath.endsWith(".ogg")) {
                ais = loadOggVorbisStream(filePath);
            } else if (lowerPath.endsWith(".wav") || lowerPath.endsWith(".au") || lowerPath.endsWith(".aiff")) {
                ais = AudioSystem.getAudioInputStream(file);
            }

            if (ais == null) return false;

            audioClip = AudioSystem.getClip();
            audioClip.open(ais);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private AudioInputStream loadOggVorbisStream(String filePath) {
        ShortBuffer rawAudio = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer channelsBuffer = stack.mallocInt(1);
            IntBuffer sampleRateBuffer = stack.mallocInt(1);

            rawAudio = STBVorbis.stb_vorbis_decode_filename(filePath, channelsBuffer, sampleRateBuffer);
            if (rawAudio == null) {
                System.err.println("[AudioSource] STBVorbis failed to decode .ogg file: " + filePath);
                return null;
            }

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
            e.printStackTrace();
            return null;
        } finally {
            if (rawAudio != null) {
                MemoryUtil.memFree(rawAudio);
            }
        }
    }

    @Override
    public String getComponentName() {
        return "Audio Source";
    }
}
