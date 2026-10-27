package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReleaseIdentityTest {
    @ParameterizedTest(name = "requested client release 2 rejects signed identity {0}/{1}")
    @CsvSource({"server,2", "client,1", "server,1"})
    void aSignedManifestCannotSubstituteAnotherTagOrRelease(String tag, String releaseId) {
        Manifest manifest = new Manifest(tag, releaseId, Instant.EPOCH, Manifest.SHA256, Map.of());

        UserError error = assertThrows(UserError.class, () -> ReleaseIdentity.require(manifest, "client", "2"));

        assertTrue(error.getMessage().contains("signed manifest identity"), error.getMessage());
        assertTrue(error.getMessage().contains("tag client release 2"), error.getMessage());
        assertEquals(tag, manifest.tag());
        assertEquals(releaseId, manifest.releaseId());
    }
}
