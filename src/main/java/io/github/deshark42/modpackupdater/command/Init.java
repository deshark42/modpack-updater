package io.github.deshark42.modpackupdater.command;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.AtomicFile;
import io.github.deshark42.modpackupdater.log.Text;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lays out a workspace with the files the tool expects to find: the connection config every
 * workspace needs, and the base/, overlay/ and remove.txt a tag is published from. Nothing here is
 * overwritten.
 */
public final class Init {

    private static final Logger LOG = LoggerFactory.getLogger(Init.class);

    private static final String TEMPLATE = """
            {
              "endpoint": "",
              "region": "us-east-1",
              "bucket": "",
              "prefix": "",
              "path_style": false,
              "concurrency": 8,
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

    public static void run(Path workspace, String tag) {
        LOG.debug("init {}", workspace);
        Path config = Layout.configFile(workspace);
        String shown = Text.relativeTo(workspace, config);
        List<String> created = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        if (Files.exists(config)) {
            LOG.debug("kept {}", shown);
            kept.add(shown);
            Workspace.fillIn(workspace);
        } else {
            AtomicFile.write(config, TEMPLATE);
            LOG.debug("created {}", shown);
            created.add(shown);
        }
        if (tag != null) {
            directory(Layout.baseDir(workspace, tag), workspace, created, kept);
            directory(Layout.overlayDir(workspace, tag), workspace, created, kept);
            Path remove = Layout.removeFile(workspace, tag);
            String ruleFile = Text.relativeTo(workspace, remove);
            if (Files.exists(remove)) {
                LOG.debug("kept {}", ruleFile);
                kept.add(ruleFile);
            } else {
                AtomicFile.write(remove, REMOVE_TEMPLATE);
                LOG.debug("created {}", ruleFile);
                created.add(ruleFile);
            }
        }
        LOG.info("workspace is ready");
        if (!created.isEmpty()) {
            LOG.debug("created: {}", String.join(", ", created));
        }
        if (!kept.isEmpty()) {
            LOG.debug("kept: {}", String.join(", ", kept));
        }
        if (created.contains(shown)) {
            LOG.debug("next: fill in \"endpoint\" and \"bucket\" in {}", shown);
        }
    }

    private static void directory(Path directory, Path workspace, List<String> created, List<String> kept) {
        String shown = Text.relativeTo(workspace, directory);
        if (Files.isDirectory(directory)) {
            LOG.debug("kept {}", shown);
            kept.add(shown);
            return;
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UserError("cannot create " + directory + ": " + e.getMessage(), e);
        }
        LOG.debug("created {}", shown);
        created.add(shown);
    }
}
