package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanTest {

    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);

    private static Manifest manifest(String... pathsAndHashes) {
        Map<String, FileEntry> files = new LinkedHashMap<>();
        for (int i = 0; i < pathsAndHashes.length; i += 2) {
            files.put(pathsAndHashes[i], new FileEntry(pathsAndHashes[i + 1], 10));
        }
        return new Manifest("client", "2.9.0-RC1", Instant.parse("2026-10-14T01:30:00Z"), Manifest.SHA256, files);
    }

    @Test
    void firstUpdateAddsEveryPath() {
        Plan plan = Plan.between(null, manifest("mods/a.jar", A, "config/x.cfg", B));
        assertEquals(List.of("config/x.cfg", "mods/a.jar"), plan.added());
        assertEquals(20, plan.downloadSize());
        assertEquals(2, plan.size());
    }

    @Test
    void unchangedFilesAreLeftAlone() {
        Manifest local = manifest("mods/a.jar", A, "config/x.cfg", B);
        assertEquals(List.of("config/x.cfg"), Plan.between(local, manifest("mods/a.jar", A, "config/x.cfg", A)).replaced());
        assertTrue(Plan.between(local, local).isEmpty());
        assertEquals(0, Plan.between(local, local).downloadSize());
    }

    @Test
    void pathsMissingFromTheTargetAreDeleted() {
        Plan plan = Plan.between(manifest("mods/a.jar", A, "mods/gone.jar", B), manifest("mods/a.jar", A));
        assertEquals(List.of("mods/gone.jar"), plan.deleted());
        assertFalse(plan.isEmpty());
    }

    @Test
    void downloadsCoverAddedAndReplacedPaths() {
        Plan plan = Plan.between(manifest("mods/old.jar", B), manifest("mods/old.jar", A, "mods/new.jar", B));
        assertEquals(List.of("mods/new.jar", "mods/old.jar"), plan.downloads());
        assertEquals("1 added, 1 replaced, 0 deleted", plan.describe());
    }

    @Test
    void equalSizeButDifferentHashCountsAsReplaced() {
        Plan plan = Plan.between(manifest("mods/a.jar", A), manifest("mods/a.jar", B));
        assertEquals(List.of("mods/a.jar"), plan.replaced());
    }
}
