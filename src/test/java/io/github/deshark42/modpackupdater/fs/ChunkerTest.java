package io.github.deshark42.modpackupdater.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.Chunking;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChunkerTest {

    private static final Chunking PARAMETERS = Chunking.DEFAULT;

    private static byte[] random(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static Path write(Path root, String name, byte[] content) throws IOException {
        Path file = root.resolve(name);
        Files.write(file, content);
        return file;
    }

    private static List<Chunker.Cut> cuts(Path file) {
        List<Chunker.Cut> cuts = new ArrayList<>();
        Chunker.split(file, PARAMETERS, cuts::add);
        return cuts;
    }

    @Test
    void anEmptyFileSplitsIntoNoChunks(@TempDir Path root) throws IOException {
        Path file = write(root, "empty", new byte[0]);

        Chunker.Result result = Chunker.split(file, PARAMETERS, null);

        assertEquals(List.of(), result.table().chunks());
        assertEquals(0, result.table().size());
        assertEquals(Sha256.ofBytes(new byte[0]), result.hash());
    }

    @Test
    void aFileShorterThanTheMinimumIsOneChunk(@TempDir Path root) throws IOException {
        byte[] content = "{\"options\": {}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path file = write(root, "options.json", content);

        Chunker.Result result = Chunker.split(file, PARAMETERS, null);

        assertEquals(1, result.table().chunks().size());
        assertEquals(content.length, result.table().size());
        assertEquals(Sha256.ofBytes(content), result.hash());
        assertEquals(result.hash(), result.table().chunks().get(0));
    }

    @Test
    void theCutsCoverTheFileInOrderWithinTheBounds(@TempDir Path root) throws IOException {
        byte[] content = random(300_000, 1);
        Path file = write(root, "content.bin", content);

        Chunker.Result result = Chunker.split(file, PARAMETERS, null);
        List<Chunker.Cut> cuts = cuts(file);

        assertEquals(result.table().chunks(), cuts.stream().map(Chunker.Cut::hash).toList());
        assertEquals(content.length, result.table().size());
        long next = 0;
        for (int i = 0; i < cuts.size(); i++) {
            Chunker.Cut cut = cuts.get(i);
            assertEquals(next, cut.offset(), "the cuts are contiguous");
            assertTrue(cut.length() <= PARAMETERS.max(), "no cut is longer than max: " + cut.length());
            if (i < cuts.size() - 1) {
                assertTrue(cut.length() >= PARAMETERS.min(), "only the tail may be shorter than min: " + cut.length());
            }
            assertArrayEquals(Arrays.copyOfRange(content, (int) cut.offset(), (int) cut.offset() + cut.length()),
                    Chunker.read(file, cut), "a cut reads back as the bytes it was found in");
            assertEquals(cut.hash(), Sha256.ofBytes(Chunker.read(file, cut)));
            next += cut.length();
        }
        assertEquals(content.length, next);
        assertEquals(Sha256.ofBytes(content), result.hash());
    }

    @Test
    void theSameContentCutsTheSameWayEveryTime(@TempDir Path root) throws IOException {
        byte[] content = random(200_000, 2);
        Path file = write(root, "content.bin", content);

        Chunker.Result first = Chunker.split(file, PARAMETERS, null);
        Chunker.Result second = Chunker.split(file, PARAMETERS, null);

        assertEquals(first.table().toJson(), second.table().toJson());
        assertEquals(first.hash(), second.hash());
    }

    @Test
    void contentThatDefeatsTheRollingHashStillRespectsTheBounds(@TempDir Path root) throws IOException {
        byte[] content = new byte[500_000];
        Path file = write(root, "zeros.bin", content);

        List<Chunker.Cut> cuts = cuts(file);

        assertTrue(cuts.size() > 1, "a long file is split even when its bytes repeat");
        for (int i = 0; i < cuts.size(); i++) {
            assertTrue(cuts.get(i).length() <= PARAMETERS.max(), "no cut is longer than max");
            if (i < cuts.size() - 1) {
                assertTrue(cuts.get(i).length() >= PARAMETERS.min(), "only the tail may be shorter than min");
            }
        }
    }

    @Test
    void anInsertionOnlyMovesTheChunksAroundIt(@TempDir Path root) throws IOException {
        byte[] original = random(400_000, 3);
        int insertAt = 200_000;
        byte[] insertion = random(5_000, 4);
        byte[] modified = new byte[original.length + insertion.length];
        System.arraycopy(original, 0, modified, 0, insertAt);
        System.arraycopy(insertion, 0, modified, insertAt, insertion.length);
        System.arraycopy(original, insertAt, modified, insertAt + insertion.length, original.length - insertAt);

        List<Chunker.Cut> before = cuts(write(root, "before.bin", original));
        List<Chunker.Cut> after = cuts(write(root, "after.bin", modified));

        List<Chunker.Cut> beforePrefix = before.stream()
                .filter(cut -> cut.offset() + cut.length() <= insertAt).toList();
        List<Chunker.Cut> afterPrefix = after.stream()
                .filter(cut -> cut.offset() + cut.length() <= insertAt).toList();
        assertEquals(beforePrefix, afterPrefix, "every chunk before the insertion is unchanged");

        Set<String> shared = new HashSet<>(before.stream().map(Chunker.Cut::hash).toList());
        shared.retainAll(after.stream().map(Chunker.Cut::hash).toList());
        assertTrue(shared.size() >= before.size() / 2,
                "most chunks survive an insertion: " + shared.size() + " of " + before.size());
    }

    @Test
    void readingACutBeyondTheEndFails(@TempDir Path root) throws IOException {
        byte[] content = random(1_000, 5);
        Path file = write(root, "content.bin", content);
        Chunker.Cut cut = new Chunker.Cut("a".repeat(64), 900, 200);

        UserError error = assertThrows(UserError.class, () -> Chunker.read(file, cut));
        assertTrue(error.getMessage().contains("ends before the chunk"), error.getMessage());
    }
}
