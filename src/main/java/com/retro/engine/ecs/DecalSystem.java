package com.retro.engine.ecs;

import com.retro.engine.assets.MeshBufferData;
import com.retro.engine.ecs.SceneWorld.WorldTransform;
import com.retro.engine.ecs.components.AnimatorComponent;
import com.retro.engine.ecs.components.Decal;
import com.retro.engine.ecs.components.MeshFilter;
import com.retro.engine.graphics.DecalBuilder;
import com.retro.engine.graphics.DecalSpec;
import com.retro.engine.graphics.DecalTextures;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Owns every live decal, both hand-placed {@link Decal} components and the ones scripts spawn during play. Spawns queue
 * up and are built at the next render extraction, once this frame's pose is known and the GPU can take the geometry.
 *
 * <p>A decal is a list of {@link Piece}s, one per mesh it landed on, each stored in that mesh's local space. The render
 * pipeline draws a piece with its target's current transform and bones, so decals ride moving and animated meshes for
 * free, and a piece disappears with its target.</p>
 *
 * <p>Spawned decals are capped by {@link #setMaxDecals}: the oldest goes first. They are cleared when play stops.</p>
 */
public final class DecalSystem {

    public static final int DEFAULT_MAX_DECALS = 256;
    /** A single frame builds at most this many spawns, so a burst can't stall a frame; the rest wait for the next one. */
    private static final int MAX_BUILDS_PER_FRAME = 32;
    /** Headless runs never reach render extraction, so an unbuilt queue has to stay bounded on its own. */
    private static final int MAX_PENDING = 1024;

    /** One decal's geometry on one target mesh. */
    public static final class Piece {
        public final EntityHandle target;
        public final String meshKey;
        public final MeshBufferData mesh;
        public final boolean skinned;
        // Local-space bounds, for frustum culling a piece on its own
        public final float centerX, centerY, centerZ, radius;

        Piece(EntityHandle target, String meshKey, MeshBufferData mesh, boolean skinned, float[] center, float radius) {
            this.target = target;
            this.meshKey = meshKey;
            this.mesh = mesh;
            this.skinned = skinned;
            this.centerX = center[0];
            this.centerY = center[1];
            this.centerZ = center[2];
            this.radius = radius;
        }
    }

    /** A built decal. */
    public static final class Live {
        public DecalSpec spec;
        public final List<Piece> pieces = new ArrayList<>(2);
        float age;
        // Authored decals only
        int signature;
        int seenStamp;
        boolean built;
        boolean waitingForMeshes;

        /** Current opacity including the end-of-life fade. */
        public float opacity() {
            float opacity = spec.opacity;
            if (spec.lifetime > 0.0f && spec.fadeTime > 0.0f) {
                float remaining = spec.lifetime - age;
                if (remaining < spec.fadeTime) opacity *= Math.max(0.0f, remaining / spec.fadeTime);
            }
            return opacity;
        }
    }

    private final DecalBuilder builder = new DecalBuilder();
    private final ArrayDeque<DecalSpec> pending = new ArrayDeque<>();
    private final ArrayList<Live> spawned = new ArrayList<>();
    private final ArrayList<Live> authored = new ArrayList<>();
    private final IdentityHashMap<Decal, Live> authoredByComponent = new IdentityHashMap<>();
    private final ArrayList<String> retiredMeshes = new ArrayList<>();
    private final float[] center = new float[3];
    private final double[] axes = new double[9];
    private int maxDecals = DEFAULT_MAX_DECALS;
    private int stamp;
    private long nextMeshId;

    /** Queues a decal; the spec is copied, so the caller can reuse it. */
    public void spawn(DecalSpec spec) {
        if (spec == null) return;
        if (pending.size() >= MAX_PENDING) pending.poll();
        pending.add(spec.copy());
    }

    /** Spawned decals allowed at once. Authored decals don't count against it. */
    public void setMaxDecals(int max) {
        maxDecals = Math.max(0, max);
        trimToBudget();
    }

    public int getMaxDecals() { return maxDecals; }

    /** Live decals in draw order: authored first, then spawned oldest to newest, so newer marks layer on top. */
    public int getLiveCount() { return authored.size() + spawned.size(); }

    public Live getLive(int index) {
        return index < authored.size() ? authored.get(index) : spawned.get(index - authored.size());
    }

    /** Ages spawned decals and removes the expired ones. Runs only while simulating, so a pause holds them. */
    public void update(float deltaTime) {
        for (int i = spawned.size() - 1; i >= 0; i--) {
            Live live = spawned.get(i);
            live.age += deltaTime;
            if (live.spec.lifetime > 0.0f && live.age >= live.spec.lifetime) {
                retire(live);
                spawned.remove(i);
            }
        }
    }

    /** Removes every spawned decal, for the end of play. Authored decals stay. */
    public void clearSpawned() {
        pending.clear();
        for (int i = 0; i < spawned.size(); i++) retire(spawned.get(i));
        spawned.clear();
    }

    /**
     * Render extraction, before the pieces are read: frees retired geometry, brings authored decals up to date with
     * their components and builds queued spawns. {@code meshes} returns a mesh's CPU data, or null while it streams in.
     */
    public void sync(SceneWorld world, Function<String, MeshBufferData> meshes, Consumer<String> releaseMesh) {
        List<Entity> entities = world.getSceneEntities();
        syncAuthored(world, entities, meshes);

        for (int built = 0; built < MAX_BUILDS_PER_FRAME && !pending.isEmpty(); built++) {
            Live live = new Live();
            live.spec = pending.poll();
            buildPieces(live, world, entities, meshes);
            if (live.pieces.isEmpty()) continue;
            spawned.add(live);
        }
        trimToBudget();

        for (int i = 0; i < retiredMeshes.size(); i++) releaseMesh.accept(retiredMeshes.get(i));
        retiredMeshes.clear();
    }

    private void trimToBudget() {
        int excess = spawned.size() - maxDecals;
        if (excess <= 0) return;
        for (int i = 0; i < excess; i++) retire(spawned.get(i));
        spawned.subList(0, excess).clear();
    }

    private void retire(Live live) {
        for (int i = 0; i < live.pieces.size(); i++) retiredMeshes.add(live.pieces.get(i).meshKey);
        live.pieces.clear();
    }

    private void syncAuthored(SceneWorld world, List<Entity> entities, Function<String, MeshBufferData> meshes) {
        stamp++;
        boolean editing = world.getPlayState() == SceneWorld.PlayState.EDITING;
        for (int i = 0, n = entities.size(); i < n; i++) {
            Entity entity = entities.get(i);
            if (entity == null) continue;
            Decal decal = entity.getComponent(Decal.class);
            if (decal == null) continue;

            Live live = authoredByComponent.get(decal);
            if (live == null) {
                live = new Live();
                authoredByComponent.put(decal, live);
                authored.add(live);
            }
            live.seenStamp = stamp;

            // In play the geometry stays put and rides its targets, so a parented decal doesn't rebuild every frame
            if (live.built && !live.waitingForMeshes && !editing) {
                live.spec.opacity = decal.opacity;
                continue;
            }
            DecalSpec spec = specFor(entity, decal, world);
            int signature = signatureOf(spec, entities, world);
            if (live.built && !live.waitingForMeshes && signature == live.signature) continue;

            retire(live);
            live.spec = spec;
            live.signature = signature;
            live.built = true;
            buildPieces(live, world, entities, meshes);
        }

        for (int i = authored.size() - 1; i >= 0; i--) {
            Live live = authored.get(i);
            if (live.seenStamp == stamp) continue;
            retire(live);
            authored.remove(i);
            authoredByComponent.values().remove(live);
        }
    }

    /** The spec a component describes: the box follows the entity, with its +Y as the surface normal. */
    private DecalSpec specFor(Entity entity, Decal decal, SceneWorld world) {
        WorldTransform wt = world.getComputedWorldTransform(entity);
        HierarchySystem.rotationMatrix(wt.rx(), wt.ry(), wt.rz(), axes);

        DecalSpec spec = new DecalSpec();
        spec.texturePath = switch (decal.style) {
            case BLOOD -> DecalTextures.BLOOD;
            case BULLET_HOLE -> DecalTextures.BULLET_HOLE;
            case SCORCH -> DecalTextures.SCORCH;
            case SOFT -> null;
            case TEXTURE -> decal.texturePath;
        };
        spec.normalPath = decal.normalPath;
        spec.posX = wt.px(); spec.posY = wt.py(); spec.posZ = wt.pz();
        spec.normalX = (float) axes[1]; spec.normalY = (float) axes[4]; spec.normalZ = (float) axes[7];
        spec.tangentX = (float) axes[0]; spec.tangentY = (float) axes[3]; spec.tangentZ = (float) axes[6];
        spec.width = decal.width * Math.abs(wt.sx());
        spec.height = decal.height * Math.abs(wt.sz());
        spec.depth = decal.depth * Math.abs(wt.sy());
        spec.colorR = decal.colorR; spec.colorG = decal.colorG; spec.colorB = decal.colorB;
        spec.opacity = decal.opacity;
        spec.specularStrength = decal.specularStrength;
        spec.roughness = decal.roughness;
        spec.normalStrength = decal.normalStrength;
        spec.maxAngle = decal.maxAngle;
        spec.affectSkinned = decal.affectSkinned;
        spec.exclude = entity;
        if (decal.targetEntity != null && !decal.targetEntity.isBlank()) {
            spec.target = world.findEntityByName(decal.targetEntity);
            if (spec.target == null) spec.width = 0.0f; // nothing to land on, not everything
        }
        return spec;
    }

    /**
     * Changes when the decal needs rebuilding: anything that shapes it, or any surface in reach moving. Handles count too,
     * because Stop can re-register restored entities, which would leave pieces pointing at the old ones.
     */
    private int signatureOf(DecalSpec spec, List<Entity> entities, SceneWorld world) {
        int h = java.util.Objects.hash(spec.texturePath, spec.posX, spec.posY, spec.posZ, spec.normalX, spec.normalY, spec.normalZ,
                spec.tangentX, spec.tangentY, spec.tangentZ, spec.width, spec.height, spec.depth);
        h = 31 * h + java.util.Objects.hash(spec.colorR, spec.colorG, spec.colorB, spec.maxAngle, spec.affectSkinned, spec.target);
        double reach = Math.sqrt(spec.width * spec.width + spec.height * spec.height + spec.depth * spec.depth);
        for (int i = 0, n = entities.size(); i < n; i++) {
            Entity entity = entities.get(i);
            MeshFilter filter = entity != null ? entity.getComponent(MeshFilter.class) : null;
            if (filter == null || entity == spec.exclude) continue;
            WorldTransform wt = world.getComputedWorldTransform(entity);
            double dx = wt.px() - spec.posX, dy = wt.py() - spec.posY, dz = wt.pz() - spec.posZ;
            double maxScale = Math.max(Math.abs(wt.sx()), Math.max(Math.abs(wt.sy()), Math.abs(wt.sz())));
            // A generous test: this only has to notice nearby movement, and big meshes are matched by their scale
            if (dx * dx + dy * dy + dz * dz > (reach + maxScale * 20.0) * (reach + maxScale * 20.0)) continue;
            h = 31 * h + java.util.Objects.hash(wt.px(), wt.py(), wt.pz(), wt.rx(), wt.ry(), wt.rz(), wt.sx(), wt.sy(), wt.sz(), filter.isVisible, filter.meshAssetPath, entity.getHandle());
        }
        return h;
    }

    private void buildPieces(Live live, SceneWorld world, List<Entity> entities, Function<String, MeshBufferData> meshes) {
        DecalSpec spec = live.spec;
        live.waitingForMeshes = false;
        if (!builder.setFrame(spec)) return;
        double reach = builder.boundingRadius();

        for (int i = 0, n = entities.size(); i < n; i++) {
            Entity entity = entities.get(i);
            if (entity == null || entity == spec.exclude || (spec.target != null && entity != spec.target)) continue;
            MeshFilter filter = entity.getComponent(MeshFilter.class);
            if (filter == null || !filter.isVisible || filter.meshAssetPath == null) continue;
            MeshBufferData mesh = meshes.apply(filter.meshAssetPath);
            if (mesh == null) {
                live.waitingForMeshes = true;
                continue;
            }
            boolean skinned = mesh.stride() >= MeshBufferData.SKINNED_FLOATS_PER_VERTEX;
            if (skinned && !spec.affectSkinned) continue;

            WorldTransform wt = world.getComputedWorldTransform(entity);
            double maxScale = Math.max(Math.abs(wt.sx()), Math.max(Math.abs(wt.sy()), Math.abs(wt.sz())));
            // Animation can swing limbs past the bind-pose bounds, the same allowance the render culling makes
            double meshReach = mesh.boundsRadius() * maxScale * (skinned ? 2.0 : 1.0) + reach;
            double dx = wt.px() - spec.posX, dy = wt.py() - spec.posY, dz = wt.pz() - spec.posZ;
            if (dx * dx + dy * dy + dz * dz > meshReach * meshReach) continue;

            AnimatorComponent animator = skinned ? entity.getComponent(AnimatorComponent.class) : null;
            float[][] palette = (animator != null && animator.skeleton != null) ? animator.bonePaletteMatrices : null;
            MeshBufferData piece = builder.build(mesh, palette, wt);
            if (piece == null) continue;
            float radius = builder.lastBounds(center);
            live.pieces.add(new Piece(entity.getHandle(), "decal://" + nextMeshId++, piece, skinned, center, radius));
        }
    }
}
