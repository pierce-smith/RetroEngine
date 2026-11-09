import com.retro.engine.animation.AnimationClip;
import com.retro.engine.animation.AnimationClipIO;
import com.retro.engine.animation.KeyframeTrack;
import com.retro.engine.ecs.Component;
import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.SceneWorld;
import com.retro.engine.ecs.components.BoxCollider;
import com.retro.engine.navigation.NavMesh;
import com.retro.engine.navigation.NavMeshBaker;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Smaller formats, for tags from beta3 on (same packages as today). Each writes a file, reads it back with the same
 * build, and records what came back.
 *
 * args: outDir tagName
 */
public class ExtrasGen {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        String tag = args[1];
        navMesh(out, tag);
        clip(out, tag);
        System.out.println(tag + ": extras written");
        System.exit(0);
    }

    static Entity box(String name, float x, float y, float z, float sx, float sy, float sz) {
        Entity e = new Entity(name, name, null);
        e.getTransform().posX = x;
        e.getTransform().posY = y;
        e.getTransform().posZ = z;
        BoxCollider c = new BoxCollider();
        c.sizeX = sx;
        c.sizeY = sy;
        c.sizeZ = sz;
        e.addComponent(c);
        return e;
    }

    /** A floor with a block on it, and on builds that have them, a gate across one side. */
    static void navMesh(Path out, String tag) throws Exception {
        List<Entity> entities = new ArrayList<>();
        entities.add(box("Floor", 0, -0.5f, 0, 20, 1, 20));
        entities.add(box("Block", 3, 1, 3, 2, 2, 2));
        try {
            Class<?> gateClass = Class.forName("com.retro.engine.ecs.components.NavMeshGate");
            Entity gate = new Entity("Gate", "Gate", null);
            gate.getTransform().posX = -5;
            gate.getTransform().posY = 1;
            gate.addComponent((Component) gateClass.getConstructor().newInstance());
            entities.add(gate);
        } catch (ClassNotFoundException noGatesYet) {}

        NavMesh mesh = NavMeshBaker.bake(entities, new SceneWorld(), new NavMeshBaker.Settings()).mesh();
        if (mesh == null) throw new IllegalStateException("bake produced nothing");
        File file = out.resolve("navmesh-" + tag + ".navmesh").toFile();
        mesh.save(file);
        NavMesh loaded = NavMesh.load(file);
        if (loaded == null) throw new IllegalStateException("navmesh reload");

        StringBuilder sb = new StringBuilder();
        sb.append("N|links=").append(loaded.getLinkCount()).append('\n');
        for (float x = -9; x <= 9; x += 1.5f) {
            for (float z = -9; z <= 9; z += 1.5f) {
                sb.append("P|").append(x).append(',').append(z).append('=').append(loaded.findPoly(x, 0.1f, z, 1.0f)).append('\n');
            }
        }
        Files.writeString(Path.of(file + ".expected"), sb.toString());
    }

    /** Two bones, keys on every channel, odd key counts and an unusual loop mode. */
    static void clip(Path out, String tag) throws Exception {
        AnimationClip clip = new AnimationClip("Fixture Walk", 1.25f);
        clip.ticksPerSecond = 30.0f;
        AnimationClip.LoopMode[] modes = AnimationClip.LoopMode.values();
        clip.loopMode = modes[modes.length - 1];
        for (String bone : new String[] { "Hips", "Spine_01" }) {
            KeyframeTrack t = new KeyframeTrack(bone);
            t.positionTimes = new float[] { 0.0f, 0.5f, 1.25f };
            t.positions = new float[] { 0, 1, 0, 0.1f, 1.05f, 0.2f, 0, 1, 0.4f };
            t.rotationTimes = new float[] { 0.0f, 1.25f };
            t.rotations = new float[] { 0, 0, 0, 1, 0, 0.3826834f, 0, 0.9238795f };
            t.scaleTimes = new float[] { 0.0f };
            t.scales = new float[] { 1, 1, 1 };
            clip.addTrack(t);
        }
        File file = out.resolve("clip-" + tag + ".ranim").toFile();
        AnimationClipIO.save(file, clip);
        Files.writeString(Path.of(file + ".expected"), describe(AnimationClipIO.load(file)));
    }

    /** Same text as TaggedFormatsTest.describe. */
    static String describe(AnimationClip c) {
        StringBuilder sb = new StringBuilder();
        sb.append("A|name=").append(c.name).append('\n');
        sb.append("A|duration=").append(c.durationSeconds).append('\n');
        sb.append("A|ticks=").append(c.ticksPerSecond).append('\n');
        sb.append("A|loop=").append(c.loopMode.name()).append('\n');
        List<KeyframeTrack> tracks = new ArrayList<>(c.getTracks());
        tracks.sort((a, b) -> a.boneName.compareTo(b.boneName));
        for (KeyframeTrack t : tracks) {
            sb.append("T|").append(t.boneName).append("|pos=").append(java.util.Arrays.toString(t.positionTimes)).append(java.util.Arrays.toString(t.positions)).append('\n');
            sb.append("T|").append(t.boneName).append("|rot=").append(java.util.Arrays.toString(t.rotationTimes)).append(java.util.Arrays.toString(t.rotations)).append('\n');
            sb.append("T|").append(t.boneName).append("|scale=").append(java.util.Arrays.toString(t.scaleTimes)).append(java.util.Arrays.toString(t.scales)).append('\n');
        }
        return sb.toString();
    }
}
