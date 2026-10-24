package io.github.deshark42.modpackupdater.command;

import io.github.deshark42.modpackupdater.log.Text;
import io.github.deshark42.modpackupdater.model.Config;
import io.github.deshark42.modpackupdater.model.Layout;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The config file every command shares, and the one thing the commands do about it. */
final class Workspace {

    private static final Logger LOG = LoggerFactory.getLogger(Workspace.class);

    private Workspace() {
    }

    /** Reads the config, filling in any default the file is missing, and reports what it added. */
    static Config load(Path workspace) {
        Config config = Config.load(workspace);
        fillIn(workspace);
        return config;
    }

    /** Fills in the defaults without parsing the whole config, for callers that only lay it out. */
    static void fillIn(Path workspace) {
        if (Config.fillMissing(workspace)) {
            LOG.debug("config {}: filled in the defaults it was missing",
                    Text.relativeTo(workspace, Layout.configFile(workspace)));
        }
    }
}
