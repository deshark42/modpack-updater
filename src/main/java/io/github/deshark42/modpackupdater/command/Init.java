package io.github.deshark42.modpackupdater.command;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.AtomicFile;
import io.github.deshark42.modpackupdater.log.Log;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Lays out a workspace with the files the tool expects to find: the connection config every
 * workspace needs, and the base directory a tag is published from. Nothing here is overwritten.
 */
public final class Init {

    private static final String TEMPLATE = """
            {
              "endpoint": "",
              "region": "us-east-1",
              "bucket": "",
              "prefix": "",
              "path_style": false,
              "access_key": "",
              "secret_key": ""
            }
            """;

    private Init() {
    }

    public static void run(Path workspace, String tag, Log log) {
        log.headline("init " + workspace);
        Path config = Layout.configFile(workspace);
        String shown = Log.relativeTo(workspace, config);
        if (Files.exists(config)) {
            log.step("kept", shown);
        } else {
            AtomicFile.write(config, TEMPLATE);
            log.step("created", shown);
            log.note("fill in \"endpoint\" and \"bucket\" in " + shown);
        }
        if (tag != null) {
            Path base = Layout.baseDir(workspace, tag);
            if (Files.isDirectory(base)) {
                log.step("kept", Log.relativeTo(workspace, base));
            } else {
                try {
                    Files.createDirectories(base);
                } catch (IOException e) {
                    throw new UserError("cannot create " + base + ": " + e.getMessage(), e);
                }
                log.step("created", Log.relativeTo(workspace, base));
            }
        }
        log.done("workspace is ready");
    }
}
