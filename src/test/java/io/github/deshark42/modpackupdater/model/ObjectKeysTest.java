package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Sha256;
import org.junit.jupiter.api.Test;

class ObjectKeysTest {

    private static final String HASH = Sha256.ofBytes(TestFiles.bytes("object key content"));
    private static final String SHARD = HASH.substring(0, 2);

    @Test
    void shardsChunksByTheFirstTwoCharactersOfTheirHash() {
        assertEquals("blob/" + SHARD + "/" + HASH, ObjectKeys.blob(HASH));
    }

    @Test
    void shardsChunkTablesByTheFirstTwoCharactersOfTheirId() {
        assertEquals("index/" + SHARD + "/" + HASH, ObjectKeys.index(HASH));
    }

    @Test
    void namesAReleaseUnderItsTag() {
        assertEquals("metadata/client/releases/2.9.0.json", ObjectKeys.release("client", "2.9.0"));
    }

    @Test
    void pointsAtTheLatestReleaseOfATag() {
        assertEquals("metadata/client/latest.json", ObjectKeys.latest("client"));
    }

    @Test
    void rejectsContentAddressesThatAreNotSha256Hashes() {
        UserError hashError = assertThrows(UserError.class, () -> ObjectKeys.blob("abc"));
        assertTrue(hashError.getMessage().contains("not a sha256 hash"), hashError.getMessage());
        UserError indexError = assertThrows(UserError.class, () -> ObjectKeys.index("zz"));
        assertTrue(indexError.getMessage().contains("not a sha256 hash"), indexError.getMessage());
    }

    @Test
    void rejectsUnsafeTagsAndReleaseIds() {
        UserError tag = assertThrows(UserError.class, () -> ObjectKeys.release("../client", "2.9.0"));
        assertTrue(tag.getMessage().contains("invalid tag"), tag.getMessage());
        UserError releaseId = assertThrows(UserError.class, () -> ObjectKeys.release("client", "../2.9.0"));
        assertTrue(releaseId.getMessage().contains("invalid release id"), releaseId.getMessage());
        UserError nested = assertThrows(UserError.class, () -> ObjectKeys.latest("client/extra"));
        assertTrue(nested.getMessage().contains("invalid tag"), nested.getMessage());
    }
}
