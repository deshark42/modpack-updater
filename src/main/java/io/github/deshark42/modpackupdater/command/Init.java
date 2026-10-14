package io.github.deshark42.modpackupdater.command;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.AtomicFile;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.IOException;
import java.io.PrintStream;
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

    public static void run(Path workspace, String tag, PrintStream out) {
        Path config = Layout.configFile(workspace);
        if (Files.exists(config)) {
            out.println("kept    " + config);
        } else {
            AtomicFile.write(config, TEMPLATE);
            out.println("created " + config);
            out.println("fill in \"endpoint\" and \"bucket\" in " + config);
        }
        if (tag == null) {
            return;
        }
        Path base = Layout.baseDir(workspace, tag);
        if (Files.isDirectory(base)) {
            out.println("kept    " + base);
            return;
        }
        try {
            Files.createDirectories(base);
        } catch (IOException e) {
            throw new UserError("cannot create " + base + ": " + e.getMessage(), e);
        }
        out.println("created " + base);
    }
}
