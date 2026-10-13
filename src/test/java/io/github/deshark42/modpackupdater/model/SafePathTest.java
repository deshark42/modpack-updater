package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.deshark42.modpackupdater.UserError;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafePathTest {

    @Test
    void acceptsAndNormalizesPlainRelativePaths() {
        assertEquals("mods/x.jar", SafePath.require("mods/x.jar"));
        assertEquals("mods/x.jar", SafePath.require("mods/./x.jar"));
        assertEquals("a/b", SafePath.require("a/b/"));
    }

    @Test
    void rejectsEscapes() {
        for (String bad : new String[]{"../x", "a/../../x", "/x", "C:/x", "a\\b", "", ".", ".."}) {
            assertThrows(UserError.class, () -> SafePath.require(bad), bad);
        }
    }

    @Test
    void resolvesInsideTheRoot(@TempDir Path root) {
        assertEquals(root.resolve("mods/x.jar"), SafePath.resolve(root, "mods/x.jar"));
    }
}
