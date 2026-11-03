package com.retro.engine.modding;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * {@code .rmod} distribution packages: a zip whose root is the mod folder (mod.json at the top). ModLoader extracts
 * each package once into a per-user cache and loads it like a mod folder; the cache is refreshed when the package
 * changes (size + modification time).
 */
public final class ModArchive {

    public static final String EXTENSION = ".rmod";
    private static final String STAMP = ".rmod-stamp";
    private static final long MAX_UNPACKED_BYTES = 4L * 1024 * 1024 * 1024; // zip-bomb guard

    private ModArchive() {}

    /**
     * Per-user cache folder for one Mods/ directory ({@code ~/.retroengine/cache/<hash>/}). Mods folders of exported
     * games may be read-only (inside a signed .app bundle), so extracted packages and compiled shaders live here.
     */
    public static File cacheDirFor(File modsDir) {
        String key;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(modsDir.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
            key = HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception e) {
            key = Integer.toHexString(modsDir.getAbsolutePath().hashCode());
        }
        return new File(System.getProperty("user.home"), ".retroengine/cache/" + key);
    }

    /** Extracts {@code rmod} into {@code cacheRoot/<name>/} unless an up-to-date copy is already there. */
    static File extractCached(File rmod, File cacheRoot) throws IOException {
        String base = rmod.getName().substring(0, rmod.getName().length() - EXTENSION.length());
        File target = new File(cacheRoot, base);
        String stamp = rmod.length() + ":" + rmod.lastModified();
        File stampFile = new File(target, STAMP);
        if (stampFile.isFile() && stamp.equals(Files.readString(stampFile.toPath()).trim()) && new File(target, ModManifest.FILE_NAME).isFile()) {
            return target;
        }

        deleteRecursively(target.toPath());
        Files.createDirectories(target.toPath());
        Path root = target.toPath().toRealPath();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(rmod.toPath())))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                Path out = root.resolve(e.getName()).normalize();
                if (!out.startsWith(root)) throw new IOException("entry escapes the package: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out))) {
                    byte[] buf = new byte[64 * 1024];
                    for (int n; (n = zip.read(buf)) > 0; ) {
                        total += n;
                        if (total > MAX_UNPACKED_BYTES) throw new IOException("package expands beyond 4 GB");
                        os.write(buf, 0, n);
                    }
                }
            }
        } catch (IOException e) {
            deleteRecursively(target.toPath());
            throw e;
        }
        if (!new File(target, ModManifest.FILE_NAME).isFile()) {
            deleteRecursively(target.toPath());
            throw new IOException("package has no mod.json at its root");
        }
        Files.writeString(stampFile.toPath(), stamp);
        System.out.println("[ModArchive] Extracted " + rmod.getName() + " -> " + target);
        return target;
    }

    /** Zips every regular file under {@code sourceDir} accepted by {@code include} (relative paths) into {@code target}. */
    public static void write(File sourceDir, File target, Predicate<String> include) throws IOException {
        Path src = sourceDir.toPath();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(src)) {
            walk.filter(Files::isRegularFile).sorted().forEach(files::add);
        }
        Files.createDirectories(target.getAbsoluteFile().getParentFile().toPath());
        Path temp = target.toPath().resolveSibling(target.getName() + ".tmp");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
            for (Path f : files) {
                String rel = src.relativize(f).toString().replace('\\', '/');
                if (!include.test(rel)) continue;
                zip.putNextEntry(new ZipEntry(rel));
                try (InputStream in = Files.newInputStream(f)) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
            }
        }
        Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path x : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
        }
    }
}
