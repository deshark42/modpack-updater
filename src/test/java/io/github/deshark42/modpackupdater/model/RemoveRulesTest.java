package io.github.deshark42.modpackupdater.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoveRulesTest {

    private static RemoveRules rules(String... lines) {
        return RemoveRules.of(List.of(lines));
    }

    private static Set<String> removed(RemoveRules rules, String... paths) {
        return rules.apply(Set.of(paths)).removed();
    }

    @Test
    void ignoresBlankLinesCommentsAndRepeats() {
        RemoveRules rules = rules("", "   ", "# a comment", "a.jar", "  a.jar  ", "a.jar");

        assertEquals(1, rules.size());
        assertEquals(Set.of("a.jar"), removed(rules, "a.jar", "b.jar"));
    }

    @Test
    void aBareNameMatchesAFileAtAnyDepth() {
        RemoveRules rules = rules("example-mod-*.jar");

        assertEquals(Set.of("example-mod-1.0.jar", "mods/example-mod-2.0.jar"),
                removed(rules, "example-mod-1.0.jar", "mods/example-mod-2.0.jar", "mods/other.jar"));
    }

    @Test
    void aLeadingSlashAnchorsARuleToTheTagDirectory() {
        RemoveRules rules = rules("/example-mod-*.jar");

        assertEquals(Set.of("example-mod-1.0.jar"),
                removed(rules, "example-mod-1.0.jar", "mods/example-mod-2.0.jar"));
    }

    @Test
    void aSeparatorInTheMiddleAnchorsARuleToo() {
        RemoveRules rules = rules(".minecraft/mods/example.jar");

        assertEquals(Set.of(".minecraft/mods/example.jar"),
                removed(rules, ".minecraft/mods/example.jar", "deep/.minecraft/mods/example.jar"));
    }

    @Test
    void twoStarsCrossDirectories() {
        RemoveRules rules = rules(".minecraft/mods/**/example.jar");

        assertEquals(Set.of(".minecraft/mods/example.jar", ".minecraft/mods/a/example.jar",
                        ".minecraft/mods/a/b/example.jar"),
                removed(rules, ".minecraft/mods/example.jar", ".minecraft/mods/a/example.jar",
                        ".minecraft/mods/a/b/example.jar", ".minecraft/example.jar"));
    }

    @Test
    void aTrailingSlashRemovesADirectoryAndEverythingBelowIt() {
        RemoveRules rules = rules(".minecraft/excludeDir/");

        assertEquals(Set.of(".minecraft/excludeDir/a.txt", ".minecraft/excludeDir/deep/b.txt"),
                removed(rules, ".minecraft/excludeDir/a.txt", ".minecraft/excludeDir/deep/b.txt",
                        ".minecraft/keep/a.txt"));
        assertEquals(Set.of(), removed(rules, ".minecraft/excludeDir"), "a file of that name is not a directory");
    }

    @Test
    void anUnanchoredDirectoryNameMatchesAtAnyDepth() {
        RemoveRules rules = rules("excludeDir/");

        assertEquals(Set.of("excludeDir/a.txt", "deep/excludeDir/b.txt"),
                removed(rules, "excludeDir/a.txt", "deep/excludeDir/b.txt", "excludeDir.jar"));
    }

    @Test
    void aQuestionMarkAndABracketMatchOneCharacter() {
        assertEquals(Set.of("mod-1.jar"), removed(rules("mod-?.jar"), "mod-1.jar", "mod-12.jar"));
        assertEquals(Set.of("a.jar", "b.jar"), removed(rules("[ab].jar"), "a.jar", "b.jar", "c.jar"));
    }

    @Test
    void aNameHasToMatchInFull() {
        assertEquals(Set.of(), removed(rules("a.jar"), "a.jar.bak", "xa.jar"));
    }

    @Test
    void reportsTheRulesThatMatchedNothing() {
        RemoveRules rules = rules("ghost.jar", "a.jar");

        assertEquals(List.of("ghost.jar"), rules.apply(Set.of("a.jar")).unmatched());
        assertEquals(List.of(), rules.apply(Set.of("a.jar", "ghost.jar")).unmatched());
    }

    @Test
    void refusesRulesThatLeaveTheTagDirectory() {
        assertThrows(UserError.class, () -> rules("../escape.jar"));
        assertThrows(UserError.class, () -> rules("mods/../../escape.jar"));
        assertThrows(UserError.class, () -> rules(".."));
    }

    @Test
    void refusesRulesThatAreNotAPlainRelativePath() {
        assertThrows(UserError.class, () -> rules("C:/escape.jar"));
        assertThrows(UserError.class, () -> rules("/"));
    }

    @Test
    void aMissingRemoveFileIsNoRules(@TempDir Path workspace) {
        assertEquals(0, RemoveRules.read(workspace.resolve("remove.txt")).size());
    }

    @Test
    void readsRulesFromAFile(@TempDir Path workspace) throws IOException {
        Path file = workspace.resolve("remove.txt");
        Files.writeString(file, "# header\n\nexample-mod-*.jar\n");

        assertEquals(Set.of("mods/example-mod-1.0.jar"),
                removed(RemoveRules.read(file), "mods/example-mod-1.0.jar", "mods/other.jar"));
    }

    @Test
    void aRuleIsMatchedAsAWholePath() {
        RemoveRules rules = rules("mods/a.jar");

        assertTrue(removed(rules, "mods/a.jar").contains("mods/a.jar"));
        assertEquals(Set.of(), removed(rules, "mods/a.jar.bak", "x/mods/a.jar"));
    }
}
