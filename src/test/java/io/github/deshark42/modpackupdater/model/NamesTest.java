package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class NamesTest {

    @Test
    void acceptsPortableNames() {
        assertEquals("a", Names.requireTag("a"));
        assertEquals("client-extra", Names.requireTag("client-extra"));
        assertEquals("A0._-", Names.requireTag("A0._-"));
        assertEquals("2.9.0-RC1", Names.requireReleaseId("2.9.0-RC1"));
    }

    @Test
    void acceptsTheLongestAllowedName() {
        String longest = "a".repeat(120);

        assertEquals(longest, Names.requireTag(longest));
    }

    @ParameterizedTest(name = "name \"{0}\" is rejected")
    @NullAndEmptySource
    void rejectsANameThatIsMissing(String name) {
        UserError error = assertThrows(UserError.class, () -> Names.requireTag(name));
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
