package io.github.deshark42.modpackupdater.model;

import java.nio.file.Path;

/** Every path the tool knows about, in one place: the layout the notes describe. */
public final class Layout {

    public static final String STATE_DIR = ".modpack-updater";
    public static final String CONFIG = "config.json";
    public static final String CURRENT = "current.json";
    public static final String RELEASES = "releases";
    public static final String BACKUP = "backup";
    public static final String LOGS = "logs";
    public static final String BASE = "base";

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

    public static Path backupDir(Path workspaceRoot, String tag, String releaseId) {
        return tagDir(workspaceRoot, tag).resolve(BACKUP).resolve(Names.requireReleaseId(releaseId));
    }

    public static Path baseDir(Path workspaceRoot, String tag) {
        return workspaceRoot.resolve(Names.requireTag(tag)).resolve(BASE);
    }
}
