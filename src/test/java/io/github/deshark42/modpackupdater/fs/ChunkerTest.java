package io.github.deshark42.modpackupdater.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.Chunking;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChunkerTest {

    private static final Chunking PARAMETERS = Chunking.DEFAULT;
    /** A well-formed hash to build a cut that no content produced. */
    private static final String HASH = Sha256.ofBytes(TestFiles.bytes("chunker test content"));

    private static List<Chunker.Cut> cuts(Path file) {
        List<Chunker.Cut> cuts = new ArrayList<>();
        Chunker.split(file, PARAMETERS, cuts::add);
        return cuts;
    }

    @Test
    void anEmptyFileSplitsIntoNoChunks(@TempDir Path root) throws IOException {
        TestFiles.write(root, "empty", new byte[0]);

        Chunker.Result result = Chunker.split(root.resolve("empty"), PARAMETERS, null);

        assertEquals(List.of(), result.table().chunks());
        assertEquals(0, result.table().size());
        assertEquals(Sha256.ofBytes(new byte[0]), result.hash());
    }

    @Test
    void aFileShorterThanTheMinimumIsOneChunk(@TempDir Path root) throws IOException {
        byte[] content = TestFiles.bytes("{\"options\": {}}");
        TestFiles.write(root, "options.json", content);

        Chunker.Result result = Chunker.split(root.resolve("options.json"), PARAMETERS, null);

        assertEquals(1, result.table().chunks().size());
        assertEquals(content.length, result.table().size());
        assertEquals(Sha256.ofBytes(content), result.hash());
        assertEquals(result.hash(), result.table().chunks().get(0));
    }

    @Test
    void aFileExactlyTheMinimumIsOneChunk(@TempDir Path root) throws IOException {
        TestFiles.write(root, "minimum.bin", TestFiles.random(PARAMETERS.min(), 21));

        Chunker.Result result = Chunker.split(root.resolve("minimum.bin"), PARAMETERS, null);

        assertEquals(1, result.table().chunks().size());
        assertEquals(PARAMETERS.min(), result.table().size());
    }

    @Test
    void theCutsCoverTheFileInOrderWithinTheBounds(@TempDir Path root) throws IOException {
        byte[] content = TestFiles.random(300_000, 1);
        TestFiles.write(root, "content.bin", content);
        Path file = root.resolve("content.bin");

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
        TestFiles.write(root, "content.bin", TestFiles.random(200_000, 2));
        Path file = root.resolve("content.bin");

        Chunker.Result first = Chunker.split(file, PARAMETERS, null);
        Chunker.Result second = Chunker.split(file, PARAMETERS, null);

        assertEquals(first.table(), second.table());
        assertEquals(first.hash(), second.hash());
    }

    @Test
    void contentThatDefeatsTheRollingHashStillRespectsTheBounds(@TempDir Path root) throws IOException {
        TestFiles.write(root, "zeros.bin", new byte[500_000]);

        List<Chunker.Cut> cuts = cuts(root.resolve("zeros.bin"));

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
        byte[] original = TestFiles.random(400_000, 3);
        int insertAt = 200_000;
        byte[] insertion = TestFiles.random(5_000, 4);
        byte[] modified = new byte[original.length + insertion.length];
        System.arraycopy(original, 0, modified, 0, insertAt);
        System.arraycopy(insertion, 0, modified, insertAt, insertion.length);
        System.arraycopy(original, insertAt, modified, insertAt + insertion.length, original.length - insertAt);
        TestFiles.write(root, "before.bin", original);
        TestFiles.write(root, "after.bin", modified);

        List<Chunker.Cut> before = cuts(root.resolve("before.bin"));
        List<Chunker.Cut> after = cuts(root.resolve("after.bin"));

        List<Chunker.Cut> beforePrefix = before.stream()
                .filter(cut -> cut.offset() + cut.length() <= insertAt).toList();
        List<Chunker.Cut> afterPrefix = after.stream()
                .filter(cut -> cut.offset() + cut.length() <= insertAt).toList();
        assertEquals(beforePrefix, afterPrefix, "every chunk before the insertion is unchanged");

        Set<String> shared = new HashSet<>(before.stream().map(Chunker.Cut::hash).toList());
        shared.retainAll(after.stream().map(Chunker.Cut::hash).toList());
        assertTrue(shared.size() >= before.size() / 2,
                "at least half the chunks survive: only the chunk the insertion lands in moves its boundary,"
                        + " so a 2x tolerance keeps this from depending on where the cut fell: "
                        + shared.size() + " of " + before.size());
    }

    @Test
    void cutsFixedContentAtTheGoldenOffsets(@TempDir Path root) throws IOException {
        // The gear table is part of the chunking algorithm: changing it changes every boundary, so
        // these constants are exactly what an intentional algorithm change has to replace. The
        // content is TestFiles.random(200_000, 42) -- a fixed seed, no committed binary fixture.
        byte[] content = TestFiles.random(200_000, 42);
        TestFiles.write(root, "golden.bin", content);
        List<Chunker.Cut> cuts = new ArrayList<>();

        Chunker.Result result = Chunker.split(root.resolve("golden.bin"), PARAMETERS, cuts::add);

        assertEquals(List.of(
                new Chunker.Cut("2ed33a880e4202ce5a70eba8e6cad355c15945d1ddc1a2e638b5726f37aab95f", 0L, 14127),
                new Chunker.Cut("a6be86905e317bed6f24f1bca2f259a949e75b1f93e5bc5bc7011fad4b1e1395", 14127L, 31639),
                new Chunker.Cut("bb50d7174912f63f4a7ec7ac5f3537f054a2facd32c7e041d208f1563d795dc9", 45766L, 38539),
                new Chunker.Cut("8ed856863d9e0806149b2f568ea3d7812258cfbd66683029bf12ef545d3435eb", 84305L, 51574),
                new Chunker.Cut("ac2c57b527982fd0adca019082d3d81d16d769dd43d340b4b2d7388a090431e8", 135879L, 27468),
                new Chunker.Cut("8c80a2f092d21ca53146b6b99b4b4f570f0154e2ada5840bc2022aa07668efe7", 163347L, 36653)),
                cuts);
        assertEquals(cuts.stream().map(Chunker.Cut::hash).toList(), result.table().chunks());
        assertEquals("dda435b9042083a15068a87593acabb147a259f695472827c4d48a79fd115297", result.hash());
    }

    @Test
    void readingACutBeyondTheEndFails(@TempDir Path root) throws IOException {
        TestFiles.write(root, "content.bin", TestFiles.random(1_000, 5));
        Chunker.Cut cut = new Chunker.Cut(HASH, 900, 200);

        UserError error = assertThrows(UserError.class, () -> Chunker.read(root.resolve("content.bin"), cut));

        assertTrue(error.getMessage().contains("ends before the chunk"), error.getMessage());
    }
}
