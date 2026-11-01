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
import org.junit.jupiter.params.provider.ValueSource;

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

    @Test
    void latestRemainsTheDefaultChannel() {
        assertEquals("latest", ObjectKeys.DEFAULT_CHANNEL);
        assertEquals(ObjectKeys.latest("client"), ObjectKeys.channel("client", ObjectKeys.DEFAULT_CHANNEL));
    }

    @ParameterizedTest(name = "channel {0} has its own pointer")
    @ValueSource(strings = {"a", "beta", "A0._-", "preview.json", "releases"})
    void aChannelPointerIsNamedUnderItsTag(String channel) {
        assertEquals(ObjectKeys.METADATA + "/client/" + channel + ".json", ObjectKeys.channel("client", channel));
    }

    @Test
    void theLongestTagAndChannelHaveANonCacheablePointer() {
        String longest = "a".repeat(120);

        assertEquals(ObjectKeys.NO_STORE_CACHE_CONTROL,
                ObjectKeys.cacheControl(ObjectKeys.channel(longest, longest)));
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
                Arguments.of(ObjectKeys.latest("client"), ObjectKeys.NO_STORE_CACHE_CONTROL),
                Arguments.of(ObjectKeys.channel("client", "beta"), ObjectKeys.NO_STORE_CACHE_CONTROL),
                Arguments.of(ObjectKeys.channel("client", "A0._-"), ObjectKeys.NO_STORE_CACHE_CONTROL),
                Arguments.of(ObjectKeys.channel("client", "preview.json"), ObjectKeys.NO_STORE_CACHE_CONTROL),
                Arguments.of(ObjectKeys.channel("client", "releases"), ObjectKeys.NO_STORE_CACHE_CONTROL),
                Arguments.of(ObjectKeys.channel("releases", "beta"), ObjectKeys.NO_STORE_CACHE_CONTROL),
                Arguments.of(ObjectKeys.release("client", "latest"), ObjectKeys.IMMUTABLE_CACHE_CONTROL),
                Arguments.of(ObjectKeys.signature("client", "latest"), ObjectKeys.IMMUTABLE_CACHE_CONTROL));
    }

    @ParameterizedTest(name = "unknown object [{0}] has no cache policy")
    @MethodSource("unknownKeys")
    void anUnknownObjectKindHasNoCachePolicy(String key) {
        assertNull(ObjectKeys.cacheControl(key));
    }

    private static Stream<String> unknownKeys() {
        return Stream.of(
                "state/current.json",
                "metadata.json",
                ObjectKeys.METADATA + "/beta.json",
                ObjectKeys.METADATA + "/client/nested/beta.json",
                ObjectKeys.METADATA + "/client/nested/latest.json",
                ObjectKeys.METADATA + "/client/beta.json/",
                ObjectKeys.METADATA + "/client/beta.json.sig",
                ObjectKeys.METADATA + "/client/beta.txt",
                ObjectKeys.METADATA + "/client/.json",
                ObjectKeys.METADATA + "/client/.hidden.json",
                ObjectKeys.METADATA + "/client/beta test.json",
                ObjectKeys.METADATA + "/client/版本.json",
                ObjectKeys.METADATA + "/client/" + "a".repeat(121) + ".json",
                ObjectKeys.METADATA + "/" + "a".repeat(121) + "/beta.json",
                ObjectKeys.METADATA + "//beta.json",
                ObjectKeys.METADATA + "/.hidden/beta.json",
                ObjectKeys.METADATA + "/client test/latest.json");
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
                Arguments.of("latest tag", (Supplier<String>) () -> ObjectKeys.latest("client/extra"), "invalid tag"),
                Arguments.of("channel tag", (Supplier<String>) () -> ObjectKeys.channel("client/extra", "beta"),
                        "invalid tag"),
                Arguments.of("channel name", (Supplier<String>) () -> ObjectKeys.channel("client", "../beta"),
                        "invalid channel"),
                Arguments.of("missing channel", (Supplier<String>) () -> ObjectKeys.channel("client", null),
                        "channel must not be empty"));
    }
}
