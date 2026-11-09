package com.retro.engine.core;

import com.retro.engine.api.PublicApi;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The running engine's version and build ID. Both come from {@code engine-version.properties}, which Gradle generates
 * from the build's {@code version} and git commit ({@code generateEngineVersion}), so there is one version source.
 * Running from classes that skipped Gradle (some IDE setups) gives {@value #UNKNOWN_VERSION}.
 */
@PublicApi
public final class EngineVersion {

    static final String RESOURCE = "/com/retro/engine/engine-version.properties";
    static final String UNKNOWN_VERSION = "0.0.0-dev";
    static final String UNKNOWN_BUILD = "unknown";

    /** e.g. {@code 1.0.0} or {@code 1.0.0-SNAPSHOT}. */
    public static final String VERSION;
    /** Short commit hash, with {@code -dirty} for builds made with uncommitted changes. */
    public static final String BUILD_ID;
    /** Leading number of {@link #VERSION}. Mods targeting a different major version are refused. */
    public static final int MAJOR;

    static {
        Properties p = new Properties();
        try (InputStream in = EngineVersion.class.getResourceAsStream(RESOURCE)) {
            if (in != null) p.load(in);
        } catch (IOException ignored) {
            // Fall through to the unknown values
        }
        VERSION = p.getProperty("version", UNKNOWN_VERSION).trim();
        BUILD_ID = p.getProperty("build", UNKNOWN_BUILD).trim();
        MAJOR = major(VERSION);
    }

    private EngineVersion() {}

    /** {@code 1.0.0-SNAPSHOT (build ee78039e4f)}, used in log headers. */
    public static String describe() {
        return VERSION + " (build " + BUILD_ID + ")";
    }

    /** Leading number of a version string; {@code 0} when it has none. */
    public static int major(String version) {
        if (version == null) return 0;
        String v = version.trim();
        int end = 0;
        while (end < v.length() && Character.isDigit(v.charAt(end))) end++;
        if (end == 0) return 0;
        try { return Integer.parseInt(v.substring(0, end)); } catch (NumberFormatException e) { return 0; }
    }

    /** Numeric {@code major.minor.patch} parts, missing or non-numeric parts as 0. Suffixes like {@code -SNAPSHOT} are dropped. */
    public static int[] parts(String version) {
        int[] out = new int[3];
        if (version == null) return out;
        String v = version.trim();
        int dash = v.indexOf('-');
        if (dash >= 0) v = v.substring(0, dash);
        String[] s = v.split("\\.");
        for (int i = 0; i < out.length && i < s.length; i++) {
            try { out[i] = Integer.parseInt(s[i].trim()); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    /**
     * Whether content built for {@code target} can run on this engine: true when the target is blank (not stated) or
     * has the same major version.
     */
    public static boolean isCompatible(String target) {
        return target == null || target.isBlank() || major(target) == MAJOR;
    }
}
