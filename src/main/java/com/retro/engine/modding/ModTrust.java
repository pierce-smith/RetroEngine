package com.retro.engine.modding;

import com.retro.engine.core.Log;
import com.retro.engine.core.UserFolders;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Asks the player before a mod's code runs. Mod scripts are ordinary Java with the same access to the computer as the
 * game itself: there is no sandbox. So a mod that carries code ({@code .java}, {@code .class} or {@code .jar} files
 * anywhere in it) only loads once the player has agreed to it. Asset-only mods (textures, models, scene patches,
 * shaders) load without asking.
 *
 * <p>Agreement is remembered per game in {@code <OS app data>/<game>/trusted_mods.properties}, keyed by mod id and a
 * SHA-256 of the mod's code, so an update that changes the code asks again. A "no" is remembered until the game
 * closes. Mod folders a developer mounted by hand, and the mod open in the mod workspace, are trusted, since the
 * developer chose them.</p>
 *
 * <p>Without a {@link Prompter} (tests, or a front end that never set one) untrusted code mods are skipped:
 * the safe default. Headless runs use {@code --trust-mods} to load them anyway.</p>
 */
public final class ModTrust {

    public static final String TRUST_FILE = "trusted_mods.properties";
    public static final String TRUST_ALL_FLAG = "--trust-mods";

    /** Shows the player the mods that want to run code and returns true to load them. */
    @FunctionalInterface
    public interface Prompter {
        boolean allow(String gameName, List<ModManifest> untrusted);
    }

    private static volatile Prompter prompter;
    private static volatile boolean trustAll;
    // "No" answers for this run (mod id + code hash), so reloading the mod set doesn't ask again
    private static final Set<String> declinedThisRun = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private ModTrust() {}

    public static void setPrompter(Prompter p) { prompter = p; }

    /** {@code --trust-mods}: load every mod's code without asking (dedicated servers, automated tests of a game). */
    public static void setTrustAll(boolean all) { trustAll = all; }

    /** Forgets this run's "no" answers (tests). */
    static void resetSession() { declinedThisRun.clear(); }

    /**
     * Returns {@code mods} without the code mods the player has not agreed to, asking about new or changed ones.
     * {@code mountedRoots} are folders mounted by hand, whose mods are trusted.
     */
    static List<ModManifest> filter(List<ModManifest> mods, String gameName, List<File> mountedRoots) {
        File store = storeFile(gameName);
        Properties trusted = read(store);
        List<ModManifest> allowed = new ArrayList<>();
        List<ModManifest> ask = new ArrayList<>();
        List<String> askHashes = new ArrayList<>();

        for (ModManifest m : mods) {
            String hash;
            try {
                hash = codeHash(m.root);
            } catch (IOException e) {
                Log.warn("ModTrust", "Skipping " + m + ": could not read its files: " + e.getMessage());
                continue;
            }
            if (hash == null || trustAll || isUnder(m.root, mountedRoots) || hash.equals(trusted.getProperty(m.id))) {
                allowed.add(m);
            } else if (declinedThisRun.contains(m.id + ":" + hash)) {
                Log.info("ModTrust", "Not loading " + m + ": its code was declined earlier in this session.");
            } else {
                ask.add(m);
                askHashes.add(hash);
            }
        }
        if (ask.isEmpty()) return allowed;

        Prompter p = prompter;
        boolean yes = false;
        if (p == null) {
            Log.warn("ModTrust", ask.size() + " mod(s) with code were not loaded because nobody could be asked to allow them. "
                    + "Start with " + TRUST_ALL_FLAG + " to load them.");
        } else {
            try {
                yes = p.allow(gameName, List.copyOf(ask));
            } catch (RuntimeException e) {
                Log.error("ModTrust", "The mod trust prompt failed; not loading untrusted mods", e);
            }
        }

        for (int i = 0; i < ask.size(); i++) {
            ModManifest m = ask.get(i);
            if (yes) {
                trusted.setProperty(m.id, askHashes.get(i));
                allowed.add(m);
            } else {
                declinedThisRun.add(m.id + ":" + askHashes.get(i));
                Log.info("ModTrust", "Not loading " + m + ": the player did not allow its code to run.");
            }
        }
        if (yes) write(store, trusted);
        // Keep discovery order; the load-order resolver sorts by priority and dependencies afterwards
        allowed.sort(java.util.Comparator.comparingInt(mods::indexOf));
        return allowed;
    }

    /** The plain-language question the native prompt shows. */
    public static String promptText(String gameName, List<ModManifest> untrusted) {
        StringBuilder text = new StringBuilder();
        text.append(untrusted.size() == 1 ? "This mod contains" : "These mods contain")
            .append(" code, which runs with the same access to your computer as ").append(displayName(gameName)).append(":\n\n");
        for (ModManifest m : untrusted) {
            text.append("  ").append(m.name == null || m.name.isBlank() ? m.id : m.name);
            if (m.version != null && !m.version.isBlank()) text.append(' ').append(m.version);
            if (m.author != null && !m.author.isBlank()) text.append(" by ").append(m.author);
            text.append('\n');
        }
        text.append("\nOnly load mods from people you trust. Load ").append(untrusted.size() == 1 ? "it" : "them").append('?');
        return text.toString();
    }

    private static String displayName(String gameName) {
        return gameName == null || gameName.isBlank() ? "the game" : gameName;
    }

    /**
     * SHA-256 over the mod's code files (relative path and bytes, in path order), or null when it has none. Assets
     * don't count, so editing a texture doesn't ask again. Folders starting with '.' are skipped, as discovery does.
     */
    static String codeHash(File modRoot) throws IOException {
        Path root = modRoot.toPath();
        List<Path> code;
        try (Stream<Path> walk = Files.walk(root)) {
            code = walk.filter(Files::isRegularFile)
                    .filter(p -> isCode(p.getFileName().toString()))
                    .filter(p -> !hiddenBelow(root, p))
                    .sorted()
                    .toList();
        }
        if (code.isEmpty()) return null;
        MessageDigest sha = sha256();
        byte[] buffer = new byte[64 * 1024];
        for (Path file : code) {
            sha.update(root.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            try (InputStream in = Files.newInputStream(file)) {
                int n;
                while ((n = in.read(buffer)) > 0) sha.update(buffer, 0, n);
            }
            sha.update((byte) 0);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    static boolean isCode(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".class") || lower.endsWith(".jar");
    }

    private static boolean hiddenBelow(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            if (part.toString().startsWith(".")) return true;
        }
        return false;
    }

    private static boolean isUnder(File dir, List<File> roots) {
        Path p = dir.getAbsoluteFile().toPath().normalize();
        for (File r : roots) {
            if (p.startsWith(r.getAbsoluteFile().toPath().normalize())) return true;
        }
        return false;
    }

    static File storeFile(String gameName) {
        return new File(new File(UserFolders.dataRoot(), UserFolders.folderName(gameName)), TRUST_FILE);
    }

    private static Properties read(File store) {
        Properties p = new Properties();
        if (!store.isFile()) return p;
        try (InputStream in = Files.newInputStream(store.toPath())) {
            p.load(in);
        } catch (IOException e) {
            Log.warn("ModTrust", "Could not read " + store + " (every mod with code will ask again): " + e.getMessage());
        }
        return p;
    }

    private static void write(File store, Properties p) {
        try {
            Files.createDirectories(store.getParentFile().toPath());
            Path tmp = store.toPath().resolveSibling(TRUST_FILE + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                p.store(out, "Mods whose code the player allowed: mod id = SHA-256 of the mod's code");
            }
            Files.move(tmp, store.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Log.warn("ModTrust", "Could not remember the allowed mods in " + store + ": " + e.getMessage());
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every Java runtime ships SHA-256
        }
    }
}
