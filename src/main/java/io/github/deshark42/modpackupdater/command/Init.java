package io.github.deshark42.modpackupdater.command;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.AtomicFile;
import io.github.deshark42.modpackupdater.log.Log;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lays out a workspace with the files the tool expects to find: the connection config every
 * workspace needs, and the base/, overlay/ and remove.txt a tag is published from. Nothing here is
 * overwritten.
 */
public final class Init {

    private static final String TEMPLATE = """
            {
              "endpoint": "",
              "region": "us-east-1",
              "bucket": "",
              "prefix": "",
              "path_style": false,
              "concurrency": 4,
              "access_key": "",
              "secret_key": ""
            }
            """;

    private static final String REMOVE_TEMPLATE = """
            # One .gitignore-style rule per line: paths in base/ that must not be published.
            # A rule that names a directory removes everything below it.
            # .minecraft/config/
            # example-mod-*.jar
            """;

    private Init() {
    }

    public static void run(Path workspace, String tag, Log log) {
        log.headline("init " + workspace);
        log.phase("workspace");
        Path config = Layout.configFile(workspace);
        String shown = Log.relativeTo(workspace, config);
        List<String> created = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        if (Files.exists(config)) {
            log.step("kept", shown);
            kept.add(shown);
            Workspace.fillIn(workspace, log);
        } else {
            AtomicFile.write(config, TEMPLATE);
            log.step("created", shown);
            created.add(shown);
        }
        if (tag != null) {
            directory(Layout.baseDir(workspace, tag), workspace, log, created, kept);
            directory(Layout.overlayDir(workspace, tag), workspace, log, created, kept);
            Path remove = Layout.removeFile(workspace, tag);
            String ruleFile = Log.relativeTo(workspace, remove);
            if (Files.exists(remove)) {
                log.step("kept", ruleFile);
                kept.add(ruleFile);
            } else {
                AtomicFile.write(remove, REMOVE_TEMPLATE);
                log.step("created", ruleFile);
                created.add(ruleFile);
            }
        }
        log.done("workspace is ready");
        if (!created.isEmpty()) {
            log.summary("created", String.join(", ", created));
        }
        if (!kept.isEmpty()) {
            log.summary("kept", String.join(", ", kept));
        }
        if (created.contains(shown)) {
            log.summary("next", "fill in \"endpoint\" and \"bucket\" in " + shown);
        }
    }

    private static void directory(Path directory, Path workspace, Log log, List<String> created, List<String> kept) {
        String shown = Log.relativeTo(workspace, directory);
        if (Files.isDirectory(directory)) {
            log.step("kept", shown);
            kept.add(shown);
            return;
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UserError("cannot create " + directory + ": " + e.getMessage(), e);
        }
        log.step("created", shown);
        created.add(shown);
    }
}
