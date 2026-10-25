package io.github.deshark42.modpackupdater.model;

import io.github.deshark42.modpackupdater.fs.PathGuard;
import java.nio.file.Path;

/** Every path the tool knows about, in one place: the layout the notes describe. */
public final class Layout {

    public static final String STATE_DIR = ".modpack-updater";
    public static final String CONFIG = "config.json";
    public static final String KNOWN_BLOBS = "known-blobs.jsonl";
    public static final String CURRENT = "current.json";
    public static final String RELEASES = "releases";
    public static final String BACKUP = "backup";
    public static final String LOGS = "logs";
    public static final String BASE = "base";
    public static final String BASE_PACK = "base_pack";
    public static final String OVERLAY = "overlay";
    public static final String REMOVE = "remove.txt";
    public static final String TMP = "tmp";
    public static final String KEYS = "keys";
    public static final String PRIVATE_KEY = "release-private.pem";
    public static final String PUBLIC_KEY = "release-public.pem";
    public static final String DEFAULT_PRIVATE_KEY = STATE_DIR + "/" + KEYS + "/" + PRIVATE_KEY;
    public static final String DEFAULT_PUBLIC_KEY = STATE_DIR + "/" + KEYS + "/" + PUBLIC_KEY;
    /** Where an update stages the chunks it downloads. */
    public static final String BLOB = "blob";
    /** Where an update assembles the files it will put in place. */
    public static final String FILE = "file";
    /** The backup directory of a first update, which has no old release id to name it after. */
    public static final String INITIAL = ".initial";

    private Layout() {
    }

    private static Path checked(Path workspaceRoot, String... parts) {
        Path path = workspaceRoot;
        for (String part : parts) {
            path = path.resolve(part);
        }
        return PathGuard.require(workspaceRoot, path);
    }

    public static Path stateDir(Path workspaceRoot) {
        return checked(workspaceRoot, STATE_DIR);
    }

    public static Path configFile(Path workspaceRoot) {
        return checked(workspaceRoot, STATE_DIR, CONFIG);
    }

    public static Path logsDir(Path workspaceRoot) {
        return checked(workspaceRoot, STATE_DIR, LOGS);
    }

    /** Where an update stages what it downloads before putting it in place. */
    public static Path tempDir(Path workspaceRoot) {
        return checked(workspaceRoot, STATE_DIR, TMP);
    }

    /** The downloaded chunks of one run; content already on disk is referenced, never copied here. */
    public static Path blobTempDir(Path workspaceRoot) {
        return checked(workspaceRoot, STATE_DIR, TMP, BLOB);
    }

    /** The files one run assembled and is about to put in place. */
    public static Path fileTempDir(Path workspaceRoot) {
        return checked(workspaceRoot, STATE_DIR, TMP, FILE);
    }

    /** The append-only record of blobs this workspace has confirmed in the bucket. */
    public static Path knownBlobsFile(Path workspaceRoot) {
        return checked(workspaceRoot, STATE_DIR, KNOWN_BLOBS);
    }

    public static Path tagDir(Path workspaceRoot, String tag) {
        return checked(workspaceRoot, STATE_DIR, Names.requireTag(tag));
    }

    public static Path currentFile(Path workspaceRoot, String tag) {
        return checked(workspaceRoot, STATE_DIR, Names.requireTag(tag), CURRENT);
    }

    public static Path releasesDir(Path workspaceRoot, String tag) {
        return checked(workspaceRoot, STATE_DIR, Names.requireTag(tag), RELEASES);
    }

    public static Path releaseFile(Path workspaceRoot, String tag, String releaseId) {
        return checked(workspaceRoot, STATE_DIR, Names.requireTag(tag), RELEASES,
                Names.requireReleaseId(releaseId) + ".json");
    }

    /** The directory every release's moved-away files are kept under. */
    public static Path backupsDir(Path workspaceRoot, String tag) {
        return checked(workspaceRoot, STATE_DIR, Names.requireTag(tag), BACKUP);
    }

    public static Path backupDir(Path workspaceRoot, String tag, String releaseId) {
        return checked(workspaceRoot, STATE_DIR, Names.requireTag(tag), BACKUP, Names.requireReleaseId(releaseId));
    }

    /**
     * The backup directory of a first update: there is no old release id to name it after, and no
     * release id may start with a dot, so this cannot collide with one.
     */
    public static Path initialBackupDir(Path workspaceRoot, String tag) {
        return checked(workspaceRoot, STATE_DIR, Names.requireTag(tag), BACKUP, INITIAL);
    }

    /** The directory a tag is published from: base/, base_pack/, overlay/ and remove.txt. */
    public static Path tagRoot(Path workspaceRoot, String tag) {
        return checked(workspaceRoot, Names.requireTag(tag));
    }

    public static Path baseDir(Path workspaceRoot, String tag) {
        return checked(workspaceRoot, Names.requireTag(tag), BASE);
    }

    public static Path basePackDir(Path workspaceRoot, String tag) {
        return checked(workspaceRoot, Names.requireTag(tag), BASE_PACK);
    }

    public static Path overlayDir(Path workspaceRoot, String tag) {
        return checked(workspaceRoot, Names.requireTag(tag), OVERLAY);
    }

    public static Path removeFile(Path workspaceRoot, String tag) {
        return checked(workspaceRoot, Names.requireTag(tag), REMOVE);
    }
}
