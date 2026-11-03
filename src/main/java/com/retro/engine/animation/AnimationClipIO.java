package com.retro.engine.animation;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Binary animation clip format (.ranim), also embedded in scene animator records:
 * [magic 'RANM'] [version] [name UTF] [duration] [ticksPerSecond] [loop mode UTF] [track count], then per track
 * [bone name UTF] and position / rotation / scale channels as [key count] [times] [values]. Tracks are keyed by bone
 * name, so a clip stays valid for any skeleton that uses the same bone names, whatever their index order.
 */
public final class AnimationClipIO {

    public static final String EXTENSION = ".ranim";
    private static final int MAGIC = 0x52414E4D; // 'RANM'
    private static final int VERSION = 1;

    private AnimationClipIO() {}

    public static void save(File file, AnimationClip clip) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        File temp = new File(file.getPath() + ".tmp");
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))) {
            write(dos, clip);
        }
        if (file.exists() && !file.delete()) throw new IOException("Cannot replace " + file);
        if (!temp.renameTo(file)) throw new IOException("Cannot write " + file);
    }

    public static AnimationClip load(File file) throws IOException {
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            return read(dis);
        }
    }

    public static void write(DataOutputStream dos, AnimationClip clip) throws IOException {
        dos.writeInt(MAGIC);
        dos.writeInt(VERSION);
        dos.writeUTF(clip.name != null ? clip.name : "");
        dos.writeFloat(clip.durationSeconds);
        dos.writeFloat(clip.ticksPerSecond);
        dos.writeUTF(clip.loopMode.name());
        dos.writeInt(clip.getTracks().size());
        for (KeyframeTrack track : clip.getTracks()) {
            dos.writeUTF(track.boneName);
            writeChannel(dos, track.positionTimes, track.positions, 3);
            writeChannel(dos, track.rotationTimes, track.rotations, 4);
            writeChannel(dos, track.scaleTimes, track.scales, 3);
        }
    }

    public static AnimationClip read(DataInputStream dis) throws IOException {
        if (dis.readInt() != MAGIC) throw new IOException("Not a .ranim clip");
        int version = dis.readInt();
        if (version > VERSION) throw new IOException("Unsupported .ranim version " + version);

        AnimationClip clip = new AnimationClip(dis.readUTF(), dis.readFloat());
        clip.ticksPerSecond = dis.readFloat();
        String loop = dis.readUTF();
        for (AnimationClip.LoopMode mode : AnimationClip.LoopMode.values()) {
            if (mode.name().equals(loop)) clip.loopMode = mode;
        }
        int trackCount = dis.readInt();
        for (int t = 0; t < trackCount; t++) {
            KeyframeTrack track = new KeyframeTrack(dis.readUTF());
            float[][] channel = readChannel(dis, 3);
            track.positionTimes = channel[0];
            track.positions = channel[1];
            channel = readChannel(dis, 4);
            track.rotationTimes = channel[0];
            track.rotations = channel[1];
            channel = readChannel(dis, 3);
            track.scaleTimes = channel[0];
            track.scales = channel[1];
            clip.addTrack(track);
        }
        return clip;
    }

    /** Deep copy (tracks and key arrays), renamed to {@code name}. */
    public static AnimationClip copy(AnimationClip clip, String name) {
        AnimationClip out = new AnimationClip(name, clip.durationSeconds);
        out.ticksPerSecond = clip.ticksPerSecond;
        out.loopMode = clip.loopMode;
        for (KeyframeTrack src : clip.getTracks()) {
            KeyframeTrack track = new KeyframeTrack(src.boneName);
            track.boneIndex = src.boneIndex;
            track.positionTimes = src.positionTimes.clone();
            track.positions = src.positions.clone();
            track.rotationTimes = src.rotationTimes.clone();
            track.rotations = src.rotations.clone();
            track.scaleTimes = src.scaleTimes.clone();
            track.scales = src.scales.clone();
            out.addTrack(track);
        }
        return out;
    }

    private static void writeChannel(DataOutputStream dos, float[] times, float[] values, int width) throws IOException {
        int keys = Math.min(times.length, values.length / width);
        dos.writeInt(keys);
        for (int k = 0; k < keys; k++) dos.writeFloat(times[k]);
        for (int k = 0; k < keys * width; k++) dos.writeFloat(values[k]);
    }

    private static float[][] readChannel(DataInputStream dis, int width) throws IOException {
        int keys = dis.readInt();
        if (keys < 0 || keys > (1 << 20)) throw new IOException("Corrupt .ranim key count " + keys);
        float[] times = new float[keys];
        float[] values = new float[keys * width];
        for (int k = 0; k < keys; k++) times[k] = dis.readFloat();
        for (int k = 0; k < values.length; k++) values[k] = dis.readFloat();
        return new float[][] { times, values };
    }
}
