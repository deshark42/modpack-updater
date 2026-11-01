package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NamesTest {

    @ParameterizedTest(name = "{0} is a portable tag")
    @ValueSource(strings = {"a", "client-extra", "A0._-"})
    void portableTagNamesRemainUnchanged(String name) {
        assertEquals(name, Names.requireTag(name));
    }

    @ParameterizedTest(name = "{0} is a portable channel")
    @ValueSource(strings = {"a", "latest", "beta", "A0._-", "preview.json"})
    void portableChannelNamesRemainUnchanged(String name) {
        assertEquals(name, Names.requireChannel(name));
    }

    @Test
    void theLongestAllowedChannelRemainsUnchanged() {
        String longest = "a".repeat(120);

        assertEquals(longest, Names.requireChannel(longest));
    }

    @Test
    void aNullChannelIsRejected() {
        UserError error = assertThrows(UserError.class, () -> Names.requireChannel(null));

        assertTrue(error.getMessage().contains("channel must not be empty"), error.getMessage());
    }

    @Test
    void anEmptyChannelIsRejected() {
        UserError error = assertThrows(UserError.class, () -> Names.requireChannel(""));

        assertTrue(error.getMessage().contains("channel must not be empty"), error.getMessage());
    }

    @Test
    void aChannelLongerThanTheLimitIsRejected() {
        UserError error = assertThrows(UserError.class, () -> Names.requireChannel("a".repeat(121)));

        assertTrue(error.getMessage().contains("channel is longer than 120 characters"), error.getMessage());
    }

    @ParameterizedTest(name = "[{0}] is not a portable channel name")
    @ValueSource(strings = {"../x", "a/b", "a\\b", ".hidden", "-lead", "_lead", "a b", "版本", "a:b"})
    void unsafeChannelNamesAreRejected(String name) {
        UserError error = assertThrows(UserError.class, () -> Names.requireChannel(name));

        assertTrue(error.getMessage().startsWith("invalid channel"), error.getMessage());
    }

    @Test
    void aPortableReleaseIdRemainsUnchanged() {
        assertEquals("2.9.0-RC1", Names.requireReleaseId("2.9.0-RC1"));
    }

    @Test
    void acceptsTheLongestAllowedName() {
        String longest = "a".repeat(120);

        assertEquals(longest, Names.requireTag(longest));
    }

    @Test
    void aNullTagIsRejected() {
        UserError error = assertThrows(UserError.class, () -> Names.requireTag(null));
        assertTrue(error.getMessage().contains("must not be empty"), error.getMessage());
    }

    @Test
    void anEmptyTagIsRejected() {
        UserError error = assertThrows(UserError.class, () -> Names.requireTag(""));
        assertTrue(error.getMessage().contains("must not be empty"), error.getMessage());
    }

    @Test
    void rejectsANameLongerThanTheLimit() {
        UserError error = assertThrows(UserError.class, () -> Names.requireTag("a".repeat(121)));
        assertTrue(error.getMessage().contains("longer than 120 characters"), error.getMessage());
    }

    @ParameterizedTest(name = "\"{0}\" is not a portable tag name")
    @ValueSource(strings = {"../x", "a/b", "a\\b", ".hidden", "-lead", "a b", "版本", "a:b"})
    void rejectsNamesThatCouldLeaveTheirDirectory(String name) {
        UserError error = assertThrows(UserError.class, () -> Names.requireTag(name));
        assertTrue(error.getMessage().startsWith("invalid tag"), error.getMessage());
    }

    @Test
    void namesTheFieldTheReleaseIdCameFrom() {
        UserError error = assertThrows(UserError.class, () -> Names.requireReleaseId("a/b"));
        assertTrue(error.getMessage().contains("invalid release id"), error.getMessage());
    }
}
