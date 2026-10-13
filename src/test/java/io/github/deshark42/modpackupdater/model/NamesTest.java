package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.deshark42.modpackupdater.UserError;
import org.junit.jupiter.api.Test;

class NamesTest {

    @Test
    void acceptsPortableNames() {
        assertEquals("client-extra", Names.requireTag("client-extra"));
        assertEquals("2.9.0-RC1", Names.requireReleaseId("2.9.0-RC1"));
    }

    @Test
    void rejectsNamesThatCouldLeaveTheirDirectory() {
        for (String bad : new String[]{"../x", "a/b", "a\\b", ".hidden", "-lead", "a b", "", "版本"}) {
            assertThrows(UserError.class, () -> Names.requireTag(bad), bad);
        }
    }
}
