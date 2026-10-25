package io.github.deshark42.modpackupdater.command;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.AtomicFile;
import io.github.deshark42.modpackupdater.log.Text;
import io.github.deshark42.modpackupdater.model.Layout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
              "read_endpoint": "",
              "region": "us-east-1",
              "bucket": "",
              "prefix": "",
              "path_style": false,
              "concurrency": 8,
              "signing_private_key": ".modpack-updater/keys/release-private.pem",
              "trusted_public_key": ".modpack-updater/keys/release-public.pem",
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
        LOG.info("initializing a workspace in {}", workspace);
        Path config = Layout.configFile(workspace);
        String shownConfig = Text.relativeTo(workspace, config);
        boolean configCreated = !Files.exists(config);
        if (configCreated) {
            AtomicFile.write(config, TEMPLATE);
            LOG.info("created {}", shownConfig);
        } else {
            LOG.info("kept {}", shownConfig);
            Workspace.fillIn(workspace);
        }
        if (tag != null) {
            directory(Layout.baseDir(workspace, tag), workspace);
            directory(Layout.overlayDir(workspace, tag), workspace);
            Path remove = Layout.removeFile(workspace, tag);
            if (Files.exists(remove)) {
                LOG.info("kept {}", Text.relativeTo(workspace, remove));
            } else {
                AtomicFile.write(remove, REMOVE_TEMPLATE);
                LOG.info("created {}", Text.relativeTo(workspace, remove));
            }
        }
        if (configCreated) {
            LOG.info("fill in \"endpoint\" and \"bucket\" in {} before publishing", shownConfig);
        }
        LOG.info("workspace is ready");
    }

    private static void directory(Path directory, Path workspace) {
        if (Files.isDirectory(directory)) {
            LOG.info("kept {}", Text.relativeTo(workspace, directory));
            return;
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UserError("cannot create " + directory + ": " + e.getMessage(), e);
        }
        LOG.info("created {}", Text.relativeTo(workspace, directory));
    }
}
