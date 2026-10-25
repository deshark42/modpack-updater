package io.github.deshark42.modpackupdater.command;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.BaseArchive;
import io.github.deshark42.modpackupdater.fs.PathGuard;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Merges base files with archive contents and owns extracted files until publication finishes. */
final class BaseSource implements AutoCloseable {

    private final Path workspace;
    private final List<BaseArchive> archives = new ArrayList<>();
    private final Map<Path, Path> paths = new HashMap<>();
    private final Map<String, Long> sizes = new TreeMap<>();
    private final Map<Path, Path> directories = new HashMap<>();
    private List<Path> inputs;

    private BaseSource(Path workspace) {
        this.workspace = workspace;
    }

    static BaseSource open(Path workspace, String tag) {
        Path base = Layout.baseDir(workspace, tag);
        Path packs = Layout.basePackDir(workspace, tag);
        rejectLegacyArchives(workspace, Layout.tagRoot(workspace, tag), packs);
        boolean hasBase = requireDirectory(base);
        boolean hasPacks = requireDirectory(packs);
        if (!hasBase && !hasPacks) {
            throw new UserError("no base input directory: at least one of " + base + " or " + packs + " must exist");
        }
        BaseSource result = new BaseSource(workspace);
        try {
            if (hasBase) {
                result.scan(base, "", null);
            }
            Map<String, Path> packed = new TreeMap<>();
            if (hasPacks) {
                result.scan(packs, "", packed);
            }
            for (Map.Entry<String, Path> entry : packed.entrySet()) {
                BaseArchive archive = BaseArchive.open(workspace, entry.getValue());
                result.archives.add(archive);
                int slash = entry.getKey().lastIndexOf('/');
                String prefix = slash < 0 ? "" : entry.getKey().substring(0, slash);
                result.scan(archive.root(), prefix, null);
            }
            result.inputs = hasBase
                    ? packed.isEmpty() ? List.of(base) : List.of(base, packs)
                    : List.of(packs);
            return result;
        } catch (RuntimeException | Error failure) {
            try {
                result.close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    List<Path> inputs() {
        return inputs;
    }

    Map<String, Long> files() {
        return Collections.unmodifiableMap(sizes);
    }

    Path file(String path) {
        Path file = paths.get(identity(path));
        if (file == null) {
            throw new IllegalArgumentException("not a base file: " + path);
        }
        return PathGuard.require(workspace, file);
    }

    private void scan(Path root, String prefix, Map<String, Path> packed) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    PathGuard.require(workspace, directory);
                    String path = target(prefix, root.relativize(directory));
                    if (packed == null && !directory.equals(root)) {
                        register(path, directory, true);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    PathGuard.require(workspace, file);
                    if (!attributes.isRegularFile()) {
                        throw new UserError("base input is not a regular file: " + file);
                    }
                    String path = target(prefix, root.relativize(file));
                    if (packed != null) {
                        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (!lower.endsWith(".zip") && !lower.endsWith(".7z")) {
                            throw new UserError("base_pack accepts only ZIP or 7z archives: " + file);
                        }
                        packed.put(path, file);
                    } else {
                        register(path, file, false);
                        sizes.put(path, attributes.size());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UserError("cannot read base input " + root + ": " + e.getMessage(), e);
        }
    }

    private void register(String path, Path source, boolean directory) {
        Path key = identity(path);
        Path existing = paths.get(key);
        if (existing == null && !directory) {
            existing = directories.get(key);
        }
        if (existing != null) {
            throw collision(path, existing, source);
        }
        int slash = path.indexOf('/');
        while (slash >= 0) {
            String parent = path.substring(0, slash);
            Path parentKey = identity(parent);
            existing = paths.get(parentKey);
            if (existing != null) {
                throw collision(parent, existing, source);
            }
            directories.putIfAbsent(parentKey, source);
            slash = path.indexOf('/', slash + 1);
        }
        if (directory) {
            directories.putIfAbsent(key, source);
        } else {
            paths.put(key, source);
        }
    }

    private Path identity(String logical) {
        return workspace.getFileSystem().getPath(logical);
    }

    private static UserError collision(String path, Path first, Path second) {
        return new UserError("base input collision at " + path + ": " + first + " conflicts with " + second);
    }

    private static String target(String prefix, Path relative) {
        StringBuilder out = new StringBuilder(prefix);
        for (Path part : relative) {
            if (part.toString().isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(part);
        }
        return out.toString();
    }

    private static boolean requireDirectory(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()) {
                throw new UserError("base input is not a directory: " + path);
            }
            return true;
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            throw new UserError("cannot inspect base input " + path + ": " + e.getMessage(), e);
        }
    }

    private static void rejectLegacyArchives(Path workspace, Path tagRoot, Path packs) {
        try (var entries = Files.list(tagRoot)) {
            for (Path entry : entries.toList()) {
                String lower = entry.getFileName().toString().toLowerCase(Locale.ROOT);
                if (lower.equals(Layout.BASE + ".zip") || lower.equals(Layout.BASE + ".7z")) {
                    PathGuard.require(workspace, entry);
                    throw new UserError("legacy base archive " + entry
                            + " is no longer an input; move it into " + packs);
                }
            }
        } catch (NoSuchFileException e) {
            // Missing inputs are reported together after checking both directories.
        } catch (IOException e) {
            throw new UserError("cannot inspect tag directory " + tagRoot + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        Throwable failure = null;
        for (int i = archives.size() - 1; i >= 0; i--) {
            try {
                archives.get(i).close();
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (failure instanceof RuntimeException error) {
            throw error;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }
}
