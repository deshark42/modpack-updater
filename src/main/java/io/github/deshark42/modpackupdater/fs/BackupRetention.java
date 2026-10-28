package io.github.deshark42.modpackupdater.fs;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.log.Text;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Names;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps seven nonempty release backups after a successful update, never touching .initial.
 * Recency is an explicit oldest-first list outside the payload trees, so nested writes and arbitrary
 * release names cannot reorder known backups. Legacy directories use their mtime only on discovery.
 */
public final class BackupRetention {
    private static final int KEEP = 7;
    private static final Logger LOG = LoggerFactory.getLogger(BackupRetention.class);

    private record Backup(Path directory, FileTime modified, List<Path> contents) {
        String id() {
            return directory.getFileName().toString();
        }
    }

    private BackupRetention() {
    }

    /** Records recency before applying files, so even an interrupted update cannot leave a stale order. */
    public static void record(Path workspace, String tag, String releaseId) {
        if (releaseId == null) {
            return;
        }
        try {
            Names.requireReleaseId(releaseId);
            Path file = Layout.backupOrderFile(workspace, tag);
            Set<String> order = new LinkedHashSet<>(readOrder(file));
            order.remove(releaseId);
            order.add(releaseId);
            AtomicFile.write(file, String.join("\n", order) + "\n");
        } catch (IOException e) {
            throw new UserError("cannot record backup order before moving files: " + e.getMessage(), e);
        }
    }

    /** Call only after the local release state is committed; writtenRelease is null if nothing moved. */
    public static void afterUpdate(Path workspace, String tag, String writtenRelease) {
        try {
            prune(workspace, tag, writtenRelease);
        } catch (IOException | UncheckedIOException | UserError | SecurityException e) {
            LOG.warn("tag {}: could not clean backups; keeping remaining copies: {}", tag, e.getMessage());
        }
    }

    private static void prune(Path workspace, String tag, String writtenRelease) throws IOException {
        Path root = Layout.backupsDir(workspace, tag);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        PathGuard guard = PathGuard.at(workspace);
        List<Backup> backups = new ArrayList<>();
        try (var children = Files.list(root)) {
            for (Path directory : children.toList()) {
                String id = directory.getFileName().toString();
                if (id.equals(Layout.INITIAL) || !isRelease(id)) {
                    continue;
                }
                guard.require(directory);
                BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory()) {
                    continue;
                }
                List<Path> contents;
                try (var walk = Files.walk(directory)) {
                    contents = walk.peek(guard::require).toList();
                }
                boolean nonempty = false;
                for (Path path : contents) {
                    if (Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
                        nonempty = true;
                    }
                }
                if (nonempty) {
                    backups.add(new Backup(directory, attributes.lastModifiedTime(), contents));
                }
            }
        }
        if (backups.isEmpty()) {
            return;
        }
        Path orderFile = Layout.backupOrderFile(workspace, tag);
        List<String> recorded = readOrder(orderFile);
        Set<String> existing = new HashSet<>();
        backups.forEach(backup -> existing.add(backup.id()));
        Set<String> order = new LinkedHashSet<>();
        backups.stream().sorted(Comparator.comparing(Backup::modified).thenComparing(Backup::id))
                .map(Backup::id).filter(id -> !recorded.contains(id)).forEach(order::add);
        recorded.stream().filter(existing::contains).forEach(order::add);
        if (writtenRelease != null && existing.contains(writtenRelease)) {
            order.remove(writtenRelease);
            order.add(writtenRelease);
        }
        // Persist before deleting: a failed metadata write must not discard recovery copies.
        AtomicFile.write(orderFile, String.join("\n", order) + "\n");
        Set<String> expired = new HashSet<>(order.stream().limit(Math.max(0, order.size() - KEEP)).toList());
        int removed = 0;
        for (Backup backup : backups) {
            if (!expired.contains(backup.id())) {
                continue;
            }
            try {
                for (Path path : backup.contents().stream().sorted(Comparator.reverseOrder()).toList()) {
                    guard.require(path);
                    Files.delete(path);
                }
                removed++;
                LOG.debug("tag {}: removed backup {}", tag, Text.relativeTo(workspace, backup.directory()));
            } catch (IOException | UserError | SecurityException e) {
                LOG.warn("tag {}: could not remove backup {}: {}", tag,
                        Text.relativeTo(workspace, backup.directory()), e.getMessage());
            }
        }
        if (removed > 0) {
            LOG.info("tag {}: removed {}; keeping the newest {} nonempty backups (excluding .initial)",
                    tag, Text.count(removed, "backup"), KEEP);
        }
    }

    private static boolean isRelease(String id) {
        try {
            Names.requireReleaseId(id);
            return true;
        } catch (UserError e) {
            return false;
        }
    }

    private static List<String> readOrder(Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<String> order = Files.readAllLines(file);
        Set<String> unique = new HashSet<>();
        for (String id : order) {
            if (!isRelease(id) || !unique.add(id)) {
                throw new UserError("invalid backup order in " + file);
            }
        }
        return order;
    }
}
