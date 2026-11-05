package com.retro.engine.ecs.serialization;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Rotating backups beside a scene file, newest first: {@code Foo.rscene.bak}, {@code Foo.rscene.1.bak} and so on.
 * Every name ends in .bak, so exports, mod packages and the Asset Browser all skip them.
 */
public final class SceneBackups {

    public static final int KEEP = 5;

    private SceneBackups() {}

    /** Newest backup for {@code sceneFile}. */
    public static File newestFor(File sceneFile) {
        return new File(sceneFile.getPath() + ".bak");
    }

    private static File olderFor(File sceneFile, int age) {
        return new File(sceneFile.getPath() + "." + age + ".bak");
    }

    /** Moves every backup one place older and drops the oldest, leaving the newest slot free. */
    public static void rotate(File sceneFile) throws IOException {
        Files.deleteIfExists(olderFor(sceneFile, KEEP - 1).toPath());
        for (int age = KEEP - 2; age >= 1; age--) {
            File from = olderFor(sceneFile, age);
            if (from.isFile()) Files.move(from.toPath(), olderFor(sceneFile, age + 1).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        File newest = newestFor(sceneFile);
        if (newest.isFile()) Files.move(newest.toPath(), olderFor(sceneFile, 1).toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Keeps the current file as the newest backup, just before a save replaces it. A file matching the newest backup is
     * skipped, so saving repeatedly without changes doesn't push real history out of the rotation.
     */
    public static void backUpBeforeSave(File sceneFile) throws IOException {
        if (!sceneFile.isFile()) return;
        File newest = newestFor(sceneFile);
        if (newest.isFile() && Files.mismatch(sceneFile.toPath(), newest.toPath()) == -1L) return;
        rotate(sceneFile);
        Files.copy(sceneFile.toPath(), newest.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
