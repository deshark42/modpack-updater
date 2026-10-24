package io.github.deshark42.modpackupdater.model;

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
    public static final String OVERLAY = "overlay";
    public static final String REMOVE = "remove.txt";
    public static final String TMP = "tmp";
    /** Where an update stages the chunks it downloads. */
    public static final String BLOB = "blob";
    /** Where an update assembles the files it will put in place. */
    public static final String FILE = "file";
    /** The backup directory of a first update, which has no old release id to name it after. */
    public static final String INITIAL = ".initial";

    private Layout() {
    }

    public static Path stateDir(Path workspaceRoot) {
        return workspaceRoot.resolve(STATE_DIR);
    }

    public static Path configFile(Path workspaceRoot) {
        return stateDir(workspaceRoot).resolve(CONFIG);
    }

    public static Path logsDir(Path workspaceRoot) {
        return stateDir(workspaceRoot).resolve(LOGS);
    }

    /** Where an update stages what it downloads before putting it in place. */
    public static Path tempDir(Path workspaceRoot) {
        return stateDir(workspaceRoot).resolve(TMP);
    }

    /** The downloaded chunks of one run; content already on disk is referenced, never copied here. */
    public static Path blobTempDir(Path workspaceRoot) {
        return tempDir(workspaceRoot).resolve(BLOB);
    }

    /** The files one run assembled and is about to put in place. */
    public static Path fileTempDir(Path workspaceRoot) {
        return tempDir(workspaceRoot).resolve(FILE);
    }

    /** The append-only record of blobs this workspace has confirmed in the bucket. */
    public static Path knownBlobsFile(Path workspaceRoot) {
        return stateDir(workspaceRoot).resolve(KNOWN_BLOBS);
    }

    public static Path tagDir(Path workspaceRoot, String tag) {
        return stateDir(workspaceRoot).resolve(Names.requireTag(tag));
    }

    public static Path currentFile(Path workspaceRoot, String tag) {
        return tagDir(workspaceRoot, tag).resolve(CURRENT);
    }

    public static Path releasesDir(Path workspaceRoot, String tag) {
        return tagDir(workspaceRoot, tag).resolve(RELEASES);
    }

    public static Path releaseFile(Path workspaceRoot, String tag, String releaseId) {
        return releasesDir(workspaceRoot, tag).resolve(Names.requireReleaseId(releaseId) + ".json");
    }

    /** The directory every release's moved-away files are kept under. */
    public static Path backupsDir(Path workspaceRoot, String tag) {
        return tagDir(workspaceRoot, tag).resolve(BACKUP);
    }

    public static Path backupDir(Path workspaceRoot, String tag, String releaseId) {
        return backupsDir(workspaceRoot, tag).resolve(Names.requireReleaseId(releaseId));
    }

    /**
     * The backup directory of a first update: there is no old release id to name it after, and no
     * release id may start with a dot, so this cannot collide with one.
     */
    public static Path initialBackupDir(Path workspaceRoot, String tag) {
        return backupsDir(workspaceRoot, tag).resolve(INITIAL);
    }

    /** The directory a tag is published from: base/, overlay/ and remove.txt live side by side. */
    public static Path tagRoot(Path workspaceRoot, String tag) {
        return workspaceRoot.resolve(Names.requireTag(tag));
    }

    public static Path baseDir(Path workspaceRoot, String tag) {
        return tagRoot(workspaceRoot, tag).resolve(BASE);
    }

    public static Path overlayDir(Path workspaceRoot, String tag) {
        return tagRoot(workspaceRoot, tag).resolve(OVERLAY);
    }

    public static Path removeFile(Path workspaceRoot, String tag) {
        return tagRoot(workspaceRoot, tag).resolve(REMOVE);
    }
}
