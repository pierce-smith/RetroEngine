package com.retro.engine.scene.components.media;

import com.retro.engine.animation.AnimationSystem;
import com.retro.engine.animation.clips.AnimationClip;
import com.retro.engine.animation.clips.AnimationClipIO;
import com.retro.engine.animation.clips.KeyframeTrack;
import com.retro.engine.animation.state.AnimatorController;
import com.retro.engine.animation.state.AnimatorState;
import com.retro.engine.animation.skeleton.Skeleton;
import com.retro.engine.editor.ui.panels.InspectorProperty;
import com.retro.engine.scene.components.Component;
import com.retro.engine.scene.entity.Entity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entity Component storing skeleton reference, active animation clip, state machine controller,
 * playback state, and pre-allocated 64-matrix bone palette buffer for zero per-frame JVM allocations.
 *
 * The skeleton and controller are rebuilt from the mesh asset after a scene load (EntitySpawner.attachAnimatorIfRigged);
 * only the authored data is persisted ({@link #poseData}): editor pose edits and recorded keys by bone name, the record
 * time, assigned .ranim clips, and the entry state.
 */
public class AnimatorComponent implements Component {

    public static final int MAX_BONES = 64;

    /** Name of {@link #recordedPose} and of the controller state that plays it. */
    public static final String RECORDED_CLIP_NAME = "Recorded Pose";
    private static final int POSE_DATA_VERSION = 1;

    public String activeClipName = "";
    public AnimationClip currentClip = null;
    public Skeleton skeleton = null;

    public AnimatorController controller = null;

    public float playbackTime = 0.0f;
    public float playbackSpeed = 1.0f;
    public boolean isPlaying = true;

    // 64 pre-allocated column-major 4x4 matrix arrays for direct GPU VBO/UBO streaming
    public final float[][] bonePaletteMatrices = new float[MAX_BONES][16];

    private boolean dirty = true;

    // Runtime: true while the palette holds the skeleton's rest pose, so idle animators skip re-evaluation
    public boolean bindPoseApplied = false;

    // Editor pose edits: per-bone local transforms (column-major 4x4) replacing the skeleton's bind pose in the rest
    // pose (AnimationSystem.resetToBindPose). Kept per animator because duplicated entities share one Skeleton.
    public final float[][] editPoseLocal = new float[MAX_BONES][16];
    public final boolean[] editPoseActive = new boolean[MAX_BONES];

    // Keyframes captured from the edit pose (one track per bone), and the clip time new keys are written at
    public AnimationClip recordedPose = null;
    public float recordTime = 0.0f;

    // .ranim clips assigned to this animator's controller (exported recordings or clips added from the asset browser)
    public final List<String> clipAssetPaths = new ArrayList<>();

    // Scene persistence: encoded on save (onBeforeSerialize), decoded on load (onDeserialized)
    @InspectorProperty(hidden = true, since = 4)
    private byte[] poseData = null;

    // Loaded state waiting for the skeleton / controller, which are rebuilt from the mesh after the scene load
    private Map<String, float[]> pendingEditPose = null;
    private String savedEntryState = null;

    public AnimatorComponent() {
        initIdentityPalette();
    }

    public AnimatorComponent(Skeleton skeleton, AnimationClip initialClip) {
        this.skeleton = skeleton;
        this.currentClip = initialClip;
        if (initialClip != null) {
            this.activeClipName = initialClip.name;
        }
        initIdentityPalette();
    }

    public AnimatorComponent(Skeleton skeleton, AnimatorController controller) {
        this.skeleton = skeleton;
        this.controller = controller;
        initIdentityPalette();
    }

    private void initIdentityPalette() {
        for (int b = 0; b < MAX_BONES; b++) {
            for (int i = 0; i < 16; i++) {
                bonePaletteMatrices[b][i] = (i % 5 == 0) ? 1.0f : 0.0f;
            }
        }
    }

    /** Local transform bone {@code boneIndex} rests at: its edit pose if one is set, else the skeleton's bind transform. */
    public float[] getLocalPose(int boneIndex) {
        if (boneIndex < 0 || boneIndex >= MAX_BONES) return null;
        if (editPoseActive[boneIndex]) return editPoseLocal[boneIndex];
        Skeleton.BoneNode bone = (skeleton != null) ? skeleton.getBone(boneIndex) : null;
        return (bone != null) ? bone.localBindTransform : null;
    }

    public void setEditPose(int boneIndex, float[] localTransform) {
        if (boneIndex < 0 || boneIndex >= MAX_BONES || localTransform == null || localTransform.length != 16) return;
        System.arraycopy(localTransform, 0, editPoseLocal[boneIndex], 0, 16);
        editPoseActive[boneIndex] = true;
    }

    public void clearEditPose(int boneIndex) {
        if (boneIndex >= 0 && boneIndex < MAX_BONES) editPoseActive[boneIndex] = false;
    }

    /**
     * Copies another animator's authored data onto this one (duplicates and play-mode clones keep the authored pose).
     * The edit pose is copied; the recorded clip is shared, like the controller whose "Recorded Pose" state plays it.
     */
    public void copyEditPoseFrom(AnimatorComponent other) {
        if (other == null) return;
        for (int b = 0; b < MAX_BONES; b++) {
            System.arraycopy(other.editPoseLocal[b], 0, editPoseLocal[b], 0, 16);
            editPoseActive[b] = other.editPoseActive[b];
        }
        recordedPose = other.recordedPose;
        recordTime = other.recordTime;
        clipAssetPaths.clear();
        clipAssetPaths.addAll(other.clipAssetPaths);
        pendingEditPose = (other.pendingEditPose != null) ? new LinkedHashMap<>(other.pendingEditPose) : null;
        savedEntryState = other.savedEntryState;
        bindPoseApplied = false;
    }

    /**
     * Binds {@code newSkeleton}, carrying the edit pose over by bone name: the pose loaded from a scene (which has no
     * skeleton yet), or the current pose when the mesh is swapped for one with a differently ordered skeleton.
     */
    public void bindSkeleton(Skeleton newSkeleton) {
        Map<String, float[]> byName = (pendingEditPose != null) ? pendingEditPose : editPoseByName();
        pendingEditPose = null;
        skeleton = newSkeleton;
        for (int b = 0; b < MAX_BONES; b++) editPoseActive[b] = false;
        if (newSkeleton != null) {
            for (Map.Entry<String, float[]> entry : byName.entrySet()) {
                setEditPose(newSkeleton.getBoneIndex(entry.getKey()), entry.getValue());
            }
            if (recordedPose != null) {
                for (KeyframeTrack track : recordedPose.getTracks()) track.boneIndex = newSkeleton.getBoneIndex(track.boneName);
            }
        }
        bindPoseApplied = false;
    }

    /** Entry state saved with the scene, applied once the rebuilt controller has its states (null when none). */
    public String takeSavedEntryState() {
        String name = savedEntryState;
        savedEntryState = null;
        return name;
    }

    private Map<String, float[]> editPoseByName() {
        Map<String, float[]> byName = new LinkedHashMap<>();
        if (skeleton == null) return (pendingEditPose != null) ? pendingEditPose : byName;
        int count = Math.min(skeleton.getBoneCount(), MAX_BONES);
        for (int b = 0; b < count; b++) {
            if (editPoseActive[b]) byName.put(skeleton.getBone(b).name, editPoseLocal[b].clone());
        }
        return byName;
    }

    /**
     * Poses every keyed bone at {@code time} from {@link #recordedPose} (the record playhead moved), so new edits start
     * from the recorded motion at that time. Bones without a track keep their edit pose.
     */
    public void applyRecordedPose(float time) {
        if (recordedPose == null || skeleton == null) return;
        float[] pos = new float[3], quat = new float[4], scale = new float[3], local = new float[16];
        for (KeyframeTrack track : recordedPose.getTracks()) {
            int bone = skeleton.getBoneIndex(track.boneName);
            if (bone < 0 || bone >= MAX_BONES) continue;
            track.samplePosition(time, pos);
            track.sampleRotation(time, quat);
            track.sampleScale(time, scale);
            AnimationSystem.composeTRS(pos, quat, scale, local);
            setEditPose(bone, local);
        }
        bindPoseApplied = false;
    }

    /**
     * Writes {@link #recordedPose} to a .ranim clip at {@code filePath}, named after the file. Returns false when there
     * is nothing recorded or the file cannot be written.
     */
    public boolean exportRecordedClip(String filePath) {
        if (recordedPose == null || recordedPose.getTracks().isEmpty() || filePath == null || filePath.isBlank()) return false;
        File file = new File(filePath);
        String name = file.getName();
        if (name.toLowerCase().endsWith(AnimationClipIO.EXTENSION)) name = name.substring(0, name.length() - AnimationClipIO.EXTENSION.length());
        try {
            AnimationClipIO.save(file, AnimationClipIO.copy(recordedPose, name));
            return true;
        } catch (IOException e) {
            System.err.println("[AnimatorComponent] Failed to export " + filePath + ": " + e.getMessage());
            return false;
        }
    }

    @Override
    public void onBeforeSerialize() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(bytes);
            dos.writeInt(POSE_DATA_VERSION);
            Map<String, float[]> pose = editPoseByName();
            dos.writeInt(pose.size());
            for (Map.Entry<String, float[]> entry : pose.entrySet()) {
                dos.writeUTF(entry.getKey());
                for (float f : entry.getValue()) dos.writeFloat(f);
            }
            dos.writeFloat(recordTime);
            boolean hasClip = recordedPose != null && !recordedPose.getTracks().isEmpty();
            dos.writeBoolean(hasClip);
            if (hasClip) AnimationClipIO.write(dos, recordedPose);
            dos.writeInt(clipAssetPaths.size());
            for (String path : clipAssetPaths) dos.writeUTF(path);
            AnimatorState entry = (controller != null) ? controller.getEntryState() : null;
            String entryName = (entry != null) ? entry.name : savedEntryState;
            dos.writeUTF(entryName != null ? entryName : "");
            dos.flush();
            poseData = bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e); // in-memory stream
        }
    }

    @Override
    public void onDeserialized(Entity owner) {
        byte[] data = poseData;
        poseData = null;
        if (data == null || data.length == 0) return;
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            if (dis.readInt() > POSE_DATA_VERSION) return;
            int poseCount = dis.readInt();
            Map<String, float[]> pose = new LinkedHashMap<>();
            for (int i = 0; i < poseCount; i++) {
                String bone = dis.readUTF();
                float[] m = new float[16];
                for (int k = 0; k < 16; k++) m[k] = dis.readFloat();
                pose.put(bone, m);
            }
            recordTime = dis.readFloat();
            recordedPose = dis.readBoolean() ? AnimationClipIO.read(dis) : null;
            int pathCount = dis.readInt();
            clipAssetPaths.clear();
            for (int i = 0; i < pathCount; i++) clipAssetPaths.add(dis.readUTF());
            String entry = dis.readUTF();
            savedEntryState = entry.isEmpty() ? null : entry;
            pendingEditPose = pose;
            if (skeleton != null) bindSkeleton(skeleton);
        } catch (IOException e) {
            System.err.println("[AnimatorComponent] Discarding unreadable pose data on '" + (owner != null ? owner.name : "?") + "': " + e.getMessage());
        }
    }

    /**
     * Writes a position / rotation (quaternion x, y, z, w) / scale key for {@code bone} at {@link #recordTime} into
     * {@link #recordedPose}, replacing a key already at that time. Returns the bone's track.
     */
    public KeyframeTrack recordKeyframe(Skeleton.BoneNode bone, float[] pos, float[] quat, float[] scale) {
        if (bone == null) return null;
        if (recordedPose == null) {
            recordedPose = new AnimationClip(RECORDED_CLIP_NAME, 0.01f);
            recordedPose.ticksPerSecond = 30.0f;
        }
        KeyframeTrack track = recordedPose.getTrack(bone.name);
        if (track == null) {
            track = new KeyframeTrack(bone.name);
            recordedPose.addTrack(track);
        }
        track.boneIndex = bone.id;
        float time = Math.max(0.0f, recordTime);

        int at = keyIndex(track.positionTimes, time);
        if (at >= 0) System.arraycopy(pos, 0, track.positions, at * 3, 3);
        else { track.positionTimes = insert(track.positionTimes, -at - 1, new float[] { time }); track.positions = insert(track.positions, (-at - 1) * 3, pos); }
        at = keyIndex(track.rotationTimes, time);
        if (at >= 0) System.arraycopy(quat, 0, track.rotations, at * 4, 4);
        else { track.rotationTimes = insert(track.rotationTimes, -at - 1, new float[] { time }); track.rotations = insert(track.rotations, (-at - 1) * 4, quat); }
        at = keyIndex(track.scaleTimes, time);
        if (at >= 0) System.arraycopy(scale, 0, track.scales, at * 3, 3);
        else { track.scaleTimes = insert(track.scaleTimes, -at - 1, new float[] { time }); track.scales = insert(track.scales, (-at - 1) * 3, scale); }

        recordedPose.durationSeconds = Math.max(recordedPose.durationSeconds, time);
        return track;
    }

    private static final float KEY_TIME_EPSILON = 1e-4f;

    /** Index of the key at {@code time} (within epsilon), else -(insertion index) - 1 keeping the times sorted. */
    private static int keyIndex(float[] times, float time) {
        for (int i = 0; i < times.length; i++) {
            if (Math.abs(times[i] - time) <= KEY_TIME_EPSILON) return i;
            if (times[i] > time) return -i - 1;
        }
        return -times.length - 1;
    }

    private static float[] insert(float[] array, int offset, float[] value) {
        float[] grown = new float[array.length + value.length];
        System.arraycopy(array, 0, grown, 0, offset);
        System.arraycopy(value, 0, grown, offset, value.length);
        System.arraycopy(array, offset, grown, offset + value.length, array.length - offset);
        return grown;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    @Override
    public String getComponentName() {
        return "Animator Component";
    }
}
