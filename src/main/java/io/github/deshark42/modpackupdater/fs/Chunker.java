package io.github.deshark42.modpackupdater.fs;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.model.ChunkTable;
import io.github.deshark42.modpackupdater.model.Chunking;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a file into content-defined chunks and hashes it, in one pass.
 *
 * <p>The boundary rule is a gear rolling hash: every byte shifts the hash left and adds one table
 * value, and a chunk may end once it holds {@code min} bytes and the low bits of the hash are zero,
 * which happens once every {@code avg} bytes on average; {@code max} ends a chunk that never got
 * there. The same bytes therefore cut the same way on every machine, in every release, and an
 * insertion or deletion only moves the chunks around it.
 *
 * <p>The gear table is part of the algorithm: changing it changes every boundary, so chunk tables
 * written before the change no longer describe what this class produces.
 */
public final class Chunker {

    private static final int BUFFER = 1 << 16;
    /** The seed of the gear table; the table itself must never change once releases exist. */
    private static final long GEAR_SEED = 0x9e3779b97f4a7c15L;
    private static final long[] GEAR = gear();

    private Chunker() {
    }

    /** One chunk of a file: the hash of its bytes and where those bytes are. */
    public record Cut(String hash, long offset, int length) {
    }

    /** Told about every cut in file order, while the file is being read. */
    public interface Sink {
        void cut(Cut cut);
    }

    /** The whole file's hash and the table that splits it. */
    public record Result(String hash, ChunkTable table) {
    }

    /** Splits {@code file}, reporting every cut to {@code sink} (may be null) as it is found. */
    public static Result split(Path file, Chunking chunking, Sink sink) {
        List<String> chunks = new ArrayList<>();
        MessageDigest whole = digest();
        MessageDigest chunk = digest();
        byte[] current = new byte[chunking.max()];
        long size = 0;
        long start = 0;
        int held = 0;
        long rolling = 0;
        long mask = chunking.mask();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), BUFFER)) {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                for (int i = 0; i < read; i++) {
                    int value = buffer[i] & 0xff;
                    current[held++] = buffer[i];
                    rolling = (rolling << 1) + GEAR[value];
                    if (held >= chunking.min() && ((rolling & mask) == 0 || held >= chunking.max())) {
                        chunks.add(cut(whole, chunk, current, held, start, sink));
                        start += held;
                        held = 0;
                        rolling = 0;
                    }
                }
                size += read;
            }
            if (held > 0) {
                chunks.add(cut(whole, chunk, current, held, start, sink));
            }
        } catch (IOException e) {
            throw new UserError("cannot read " + file + ": " + e.getMessage(), e);
        }
        return new Result(Sha256.hex(whole.digest()), new ChunkTable(chunking, size, chunks));
    }

    /** Reads one cut back from the file it came from. */
    public static byte[] read(Path file, Cut cut) {
        byte[] bytes = new byte[cut.length()];
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            channel.position(cut.offset());
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw new UserError("cannot read chunk " + cut.hash() + " from " + file
                            + ": the file ends before the chunk does");
                }
            }
        } catch (IOException e) {
            throw new UserError("cannot read " + file + ": " + e.getMessage(), e);
        }
        return bytes;
    }

    private static String cut(MessageDigest whole, MessageDigest chunk, byte[] bytes, int length, long start,
            Sink sink) {
        whole.update(bytes, 0, length);
        chunk.update(bytes, 0, length);
        String hash = Sha256.hex(chunk.digest());
        chunk.reset();
        if (sink != null) {
            sink.cut(new Cut(hash, start, length));
        }
        return hash;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static long[] gear() {
        long[] table = new long[256];
        for (int i = 0; i < table.length; i++) {
            table[i] = splitmix64(GEAR_SEED + i);
        }
        return table;
    }

    private static long splitmix64(long seed) {
        long z = seed + 0x9e3779b97f4a7c15L;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
