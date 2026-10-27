package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Sha256;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ObjectKeysTest {

    private static final String HASH = Sha256.ofBytes(TestFiles.bytes("object key content"));
    private static final String SHARD = HASH.substring(0, 2);

    @Test
    void chunksAreShardedByTheFirstTwoCharactersOfTheirHash() {
        assertEquals(ObjectKeys.BLOB + "/" + SHARD + "/" + HASH, ObjectKeys.blob(HASH));
    }

    @Test
    void chunkTablesAreShardedByTheFirstTwoCharactersOfTheirId() {
        assertEquals(ObjectKeys.INDEX + "/" + SHARD + "/" + HASH, ObjectKeys.index(HASH));
    }

    @Test
    void aReleaseIsNamedUnderItsTag() {
        assertEquals(ObjectKeys.METADATA + "/client/" + Layout.RELEASES + "/2.9.0.json",
                ObjectKeys.release("client", "2.9.0"));
    }

    @Test
    void aSignatureIsStoredBesideItsRelease() {
        String release = ObjectKeys.release("client", "2.9.0");

        assertEquals(release + ".sig", ObjectKeys.signature("client", "2.9.0"));
    }

    @Test
    void theLatestPointerIsNamedUnderItsTag() {
        assertEquals(ObjectKeys.METADATA + "/client/latest.json", ObjectKeys.latest("client"));
    }

    @ParameterizedTest(name = "object {0} uses cache control {1}")
    @MethodSource("cachePolicies")
    void eachKnownObjectKindUsesItsDeclaredCachePolicy(String key, String policy) {
        assertEquals(policy, ObjectKeys.cacheControl(key));
    }

    private static Stream<Arguments> cachePolicies() {
        return Stream.of(
                Arguments.of(ObjectKeys.blob(HASH), ObjectKeys.IMMUTABLE_CACHE_CONTROL),
                Arguments.of(ObjectKeys.index(HASH), ObjectKeys.IMMUTABLE_CACHE_CONTROL),
                Arguments.of(ObjectKeys.release("client", "2.9.0"), ObjectKeys.IMMUTABLE_CACHE_CONTROL),
                Arguments.of(ObjectKeys.signature("client", "2.9.0"), ObjectKeys.IMMUTABLE_CACHE_CONTROL),
                Arguments.of(ObjectKeys.latest("client"), ObjectKeys.NO_STORE_CACHE_CONTROL));
    }

    @Test
    void anUnknownObjectKindHasNoCachePolicy() {
        assertNull(ObjectKeys.cacheControl("state/current.json"));
    }

    @Test
    void aChunkAddressMustBeASha256Hash() {
        UserError error = assertThrows(UserError.class, () -> ObjectKeys.blob("abc"));

        assertTrue(error.getMessage().contains("not a sha256 hash"), error.getMessage());
    }

    @Test
    void aChunkTableAddressMustBeASha256Hash() {
        UserError error = assertThrows(UserError.class, () -> ObjectKeys.index("zz"));

        assertTrue(error.getMessage().contains("not a sha256 hash"), error.getMessage());
    }

    @ParameterizedTest(name = "{0} rejects unsafe names")
    @MethodSource("unsafeNames")
    void unsafeNamesCannotEscapeTheirObjectNamespace(String description, Supplier<String> key, String message) {
        UserError error = assertThrows(UserError.class, key::get);

        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    private static Stream<Arguments> unsafeNames() {
        return Stream.of(
                Arguments.of("release tag", (Supplier<String>) () -> ObjectKeys.release("../client", "2.9.0"),
                        "invalid tag"),
                Arguments.of("release id", (Supplier<String>) () -> ObjectKeys.release("client", "../2.9.0"),
                        "invalid release id"),
                Arguments.of("latest tag", (Supplier<String>) () -> ObjectKeys.latest("client/extra"), "invalid tag"));
    }
}
