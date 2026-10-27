package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.fs.Sha256;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanTest {

    private static final String A = Sha256.ofBytes(TestFiles.bytes("plan a"));
    private static final String B = Sha256.ofBytes(TestFiles.bytes("plan b"));
    private static final String CHUNKS = Sha256.ofBytes(TestFiles.bytes("plan chunks"));

    private static Manifest manifest(String... pathsAndHashes) {
        Map<String, FileEntry> files = new LinkedHashMap<>();
        for (int i = 0; i < pathsAndHashes.length; i += 2) {
            files.put(pathsAndHashes[i], new FileEntry(pathsAndHashes[i + 1], 10, CHUNKS));
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
    void aChangedHashIsReplacedEvenWhenTheSizeIsTheSame() {
        Plan plan = Plan.between(manifest("mods/a.jar", A), manifest("mods/a.jar", B));

        assertEquals(List.of("mods/a.jar"), plan.replaced());
        assertEquals(10, plan.downloadSize());
    }

    @Test
    void leavesUnchangedFilesAlone() {
        Manifest local = manifest("mods/a.jar", A, "config/x.cfg", B);

        Plan plan = Plan.between(local, local);

        assertTrue(plan.isEmpty());
        assertEquals(List.of(), plan.added());
        assertEquals(List.of(), plan.replaced());
        assertEquals(List.of(), plan.deleted());
        assertEquals(List.of(), plan.downloads());
        assertEquals(0, plan.downloadSize());
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
    }

    @Test
    void describesEachKindOfChangeWithItsOwnCount() {
        Plan plan = new Plan(List.of("added"), List.of("replaced-a", "replaced-b"),
                List.of("deleted-a", "deleted-b", "deleted-c"), 0);

        String description = plan.describe();

        assertEquals("1 added, 2 replaced, 3 deleted", description);
    }
}
