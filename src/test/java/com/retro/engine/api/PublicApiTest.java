package com.retro.engine.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The public API is the {@link PublicApi}-marked surface. It must be self-contained (no signature mentions an engine
 * type that isn't API, and no editor type is API) and match the committed baseline, so every API change is a
 * reviewed diff of {@value ApiSurface#BASELINE}.
 */
class PublicApiTest {

    @Test
    void apiSignaturesOnlyMentionApiTypes() throws IOException {
        ApiSurface s = ApiSurface.scan();
        assertFalse(s.fullTypes.isEmpty(), "no @PublicApi types found");
        assertTrue(s.leaks.isEmpty(), () -> s.leaks.size() + " API signature(s) expose non-API types. Mark the type @PublicApi, "
                + "or mark the member @Internal:\n  " + String.join("\n  ", s.leaks));
    }

    @Test
    void apiMatchesTheBaseline() throws IOException {
        Path baseline = Path.of(ApiSurface.BASELINE);
        assertTrue(Files.isRegularFile(baseline), "Missing " + baseline.toAbsolutePath() + ". Run ./gradlew apiDump.");
        List<String> expected = Files.readAllLines(baseline, StandardCharsets.UTF_8);
        List<String> actual = ApiSurface.scan().render();
        if (expected.equals(actual)) return;

        Set<String> removed = new LinkedHashSet<>(expected);
        actual.forEach(removed::remove);
        Set<String> added = new LinkedHashSet<>(actual);
        expected.forEach(added::remove);
        List<String> msg = new ArrayList<>();
        msg.add("The public API differs from " + ApiSurface.BASELINE + ".");
        msg.add("If the change is intended, run ./gradlew apiDump and commit the updated file.");
        if (!removed.isEmpty()) {
            msg.add("Removed or changed (breaks scripts and mods written against the baseline):");
            removed.forEach(l -> msg.add("  - " + l.strip()));
        }
        if (!added.isEmpty()) {
            msg.add("Added:");
            added.forEach(l -> msg.add("  + " + l.strip()));
        }
        fail(String.join("\n", msg));
    }
}
