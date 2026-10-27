package io.github.deshark42.modpackupdater.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.deshark42.modpackupdater.TestFiles;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.Chunking;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    @ParameterizedTest(name = "a cut at {0} reads {1} bytes")
    @CsvSource({"0, 1", "1, 31", "31, 1", "0, 32"})
    void readsExactlyTheSelectedCut(int offset, int length, @TempDir Path root) throws IOException {
        byte[] content = TestFiles.random(32, 1);
        TestFiles.write(root, "content.bin", content);
        byte[] expected = Arrays.copyOfRange(content, offset, offset + length);
        Chunker.Cut cut = new Chunker.Cut(Sha256.ofBytes(expected), offset, length);

        byte[] actual = Chunker.read(root.resolve("content.bin"), cut);

        assertArrayEquals(expected, actual);
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
    void contentThatDefeatsTheRollingHashCutsAtTheMaximum(@TempDir Path root) throws IOException {
        TestFiles.write(root, "zeros.bin", new byte[PARAMETERS.max() * 2 + 1]);

        List<Chunker.Cut> cuts = cuts(root.resolve("zeros.bin"));

        assertEquals(List.of(PARAMETERS.max(), PARAMETERS.max(), 1),
                cuts.stream().map(Chunker.Cut::length).toList());
        assertEquals(List.of(0L, (long) PARAMETERS.max(), PARAMETERS.max() * 2L),
                cuts.stream().map(Chunker.Cut::offset).toList());
    }

    @Test
    void anInsertionOnlyMovesTheChunksAroundIt(@TempDir Path root) throws IOException {
        byte[] original = TestFiles.random(200_000, 42);
        int insertAt = 20_000;
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

        long stableSuffix = insertAt + PARAMETERS.max();
        List<Chunker.Cut> beforeSuffix = before.stream().filter(cut -> cut.offset() >= stableSuffix).toList();
        List<Chunker.Cut> afterSuffix = after.stream()
                .filter(cut -> cut.offset() - insertion.length >= stableSuffix)
                .map(cut -> new Chunker.Cut(cut.hash(), cut.offset() - insertion.length, cut.length())).toList();
        assertFalse(beforeSuffix.isEmpty(), "the fixture includes chunks beyond the insertion's boundary region");
        assertEquals(beforeSuffix, afterSuffix, "the distant suffix retains exact bytes, lengths and shifted offsets");
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
        assertEquals(content.length, result.table().size());
        assertEquals(cuts.stream().map(Chunker.Cut::hash).toList(), result.table().chunks());
        assertEquals("dda435b9042083a15068a87593acabb147a259f695472827c4d48a79fd115297", result.hash());
    }

    @Test
    void readingACutBeyondTheEndFails(@TempDir Path root) throws IOException {
        byte[] content = TestFiles.random(1_000, 5);
        TestFiles.write(root, "content.bin", content);
        Chunker.Cut cut = new Chunker.Cut(HASH, 900, 200);

        UserError error = assertThrows(UserError.class, () -> Chunker.read(root.resolve("content.bin"), cut));

        assertTrue(error.getMessage().contains("ends before the chunk"), error.getMessage());
        assertArrayEquals(content, Files.readAllBytes(root.resolve("content.bin")));
    }
}
