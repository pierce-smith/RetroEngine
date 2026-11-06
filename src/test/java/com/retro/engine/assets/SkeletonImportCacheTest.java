package com.retro.engine.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.retro.engine.ecs.Entity;
import com.retro.engine.ecs.EntitySpawner;
import com.retro.engine.ecs.components.AnimatorComponent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

class SkeletonImportCacheTest {

    private static final String RIG = "/templates/first_person_example/Meshes/arms_rig.glb";

    @TempDir Path dir;
    private File rig;

    @BeforeEach
    void copyRig() throws IOException {
        SkeletonImporter.clearCache();
        rig = dir.resolve("arms_rig.glb").toFile();
        try (InputStream in = SkeletonImportCacheTest.class.getResourceAsStream(RIG)) {
            Files.copy(assertResource(in), rig.toPath());
        }
    }

    private static InputStream assertResource(InputStream in) {
        assertNotNull(in, "rig resource " + RIG);
        return in;
    }

    @Test
    void anUnchangedFileIsImportedOnce() {
        assertSame(SkeletonImporter.loadSkeletonAndAnimations(rig.getPath()), SkeletonImporter.loadSkeletonAndAnimations(rig.getPath()));
    }

    @Test
    void aChangedFileIsImportedAgain() {
        SkeletonImporter.SkeletonImportResult first = SkeletonImporter.loadSkeletonAndAnimations(rig.getPath());
        assertEquals(true, rig.setLastModified(rig.lastModified() + 5000L));
        assertNotSame(first, SkeletonImporter.loadSkeletonAndAnimations(rig.getPath()));
    }

    @Test
    void instancesShareTheRigButNotTheirPlaybackState() {
        Entity a = new Entity("a", "A", rig.getPath()), b = new Entity("b", "B", rig.getPath());
        EntitySpawner.attachAnimatorIfRigged(a, rig.getPath());
        EntitySpawner.attachAnimatorIfRigged(b, rig.getPath());
        AnimatorComponent animA = a.getComponent(AnimatorComponent.class), animB = b.getComponent(AnimatorComponent.class);
        assertSame(animA.skeleton, animB.skeleton);
        assertSame(animA.currentClip, animB.currentClip);
        assertNotSame(animA, animB);
        assertNotSame(animA.controller, animB.controller);
        assertNotSame(animA.bonePaletteMatrices, animB.bonePaletteMatrices);
    }

    @Test
    void objMeshesNeverReachTheImporter() {
        Entity e = new Entity("o", "O", "Assets/Meshes/DoesNotExist.obj");
        EntitySpawner.attachAnimatorIfRigged(e, "Assets/Meshes/DoesNotExist.obj");
        assertNull(e.getComponent(AnimatorComponent.class));
    }
}
