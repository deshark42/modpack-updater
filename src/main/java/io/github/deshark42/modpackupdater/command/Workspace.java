package io.github.deshark42.modpackupdater.command;

import io.github.deshark42.modpackupdater.log.Log;
import io.github.deshark42.modpackupdater.model.Config;
import io.github.deshark42.modpackupdater.model.Layout;
import java.nio.file.Path;

/** The config file every command shares, and the one thing the commands do about it. */
final class Workspace {

    private Workspace() {
    }

    /** Reads the config, filling in any default the file is missing, and reports what it added. */
    static Config load(Path workspace, Log log) {
        Config config = Config.load(workspace);
        fillIn(workspace, log);
        return config;
    }

    /** Fills in the defaults without parsing the whole config, for callers that only lay it out. */
    static void fillIn(Path workspace, Log log) {
        if (Config.fillMissing(workspace)) {
            log.note("filled in the defaults missing from "
                    + Log.relativeTo(workspace, Layout.configFile(workspace)));
        }
    }
}
