package io.github.deshark42.modpackupdater.command;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.BaseArchive;
import io.github.deshark42.modpackupdater.fs.PathGuard;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

/** Selects one base input and owns its extracted files until publication finishes. */
final class BaseSource implements AutoCloseable {

    private final Path input;
    private final BaseArchive archive;

    private BaseSource(Path input, BaseArchive archive) {
        this.input = input;
        this.archive = archive;
    }

    static BaseSource open(Path workspace, String tag) {
        Path input = select(workspace, tag);
        BaseArchive archive = input.equals(Layout.baseDir(workspace, tag))
                ? null : BaseArchive.open(workspace, input);
        return new BaseSource(input, archive);
    }

    static Path select(Path workspace, String tag) {
        Path base = Layout.baseDir(workspace, tag);
        Path tagRoot = Layout.tagRoot(workspace, tag);
        rejectMisnamedArchives(tagRoot);
        Path zip = PathGuard.require(workspace, tagRoot.resolve(Layout.BASE + ".zip"));
        Path sevenZip = PathGuard.require(workspace, tagRoot.resolve(Layout.BASE + ".7z"));
        BasicFileAttributes baseAttributes = attributes(base);
        BasicFileAttributes zipAttributes = attributes(zip);
        BasicFileAttributes sevenZipAttributes = attributes(sevenZip);
        if (baseAttributes != null && !baseAttributes.isDirectory()) {
            throw new UserError("base input is not a directory: " + base);
        }
        requireArchiveFile(zip, zipAttributes);
        requireArchiveFile(sevenZip, sevenZipAttributes);
        if (zipAttributes != null && sevenZipAttributes != null) {
            throw new UserError("ambiguous base input: both " + zip + " and " + sevenZip
                    + " exist; keep only one base archive");
        }
        Path archive = zipAttributes != null ? zip : sevenZipAttributes != null ? sevenZip : null;
        if (archive == null) {
            if (baseAttributes == null) {
                throw new UserError("no such directory: " + base);
            }
            return base;
        }
        if (baseAttributes != null && !isEmpty(base)) {
            throw new UserError("ambiguous base input: nonempty directory " + base + " and archive "
                    + archive + " coexist; empty or remove the directory, or remove the archive");
        }
        return archive;
    }

    Path input() {
        return input;
    }

    Path root() {
        return archive == null ? input : archive.root();
    }

    @Override
    public void close() {
        if (archive != null) {
            archive.close();
        }
    }

    /**
     * Rejects an archive whose name only differs in case from the documented names. Without this,
     * a Linux publisher dropping base.ZIP next to init's empty base/ would silently publish the
     * empty directory instead of the archive.
     */
    private static void rejectMisnamedArchives(Path tagRoot) {
        String zip = Layout.BASE + ".zip";
        String sevenZip = Layout.BASE + ".7z";
        try (var entries = Files.list(tagRoot)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (name.equals(zip) || name.equals(sevenZip)) {
                    continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if ((lower.equals(zip) || lower.equals(sevenZip))
                        && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new UserError("base archive names are case-sensitive: " + entry
                            + " is ignored; rename it to " + zip + " or " + sevenZip);
                }
            }
        } catch (NoSuchFileException e) {
            // no tag directory yet: select already reports the missing base input
        } catch (IOException e) {
            throw new UserError("cannot inspect tag directory " + tagRoot + ": " + e.getMessage(), e);
        }
    }

    private static BasicFileAttributes attributes(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UserError("cannot inspect base input " + path + ": " + e.getMessage(), e);
        }
    }

    private static void requireArchiveFile(Path path, BasicFileAttributes attributes) {
        if (attributes != null && !attributes.isRegularFile()) {
            throw new UserError("base archive is not a regular file: " + path);
        }
    }

    private static boolean isEmpty(Path directory) {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        } catch (IOException e) {
            throw new UserError("cannot inspect base directory " + directory + ": " + e.getMessage(), e);
        }
    }
}
