package io.github.deshark42.modpackupdater.cli;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.command.Init;
import io.github.deshark42.modpackupdater.command.Keygen;
import io.github.deshark42.modpackupdater.command.Publish;
import io.github.deshark42.modpackupdater.command.Update;
import io.github.deshark42.modpackupdater.log.Logging;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The command line: workspace commands and local signing key generation. */
public final class Cli {

    public static final int USAGE_ERROR = 2;
    public static final int FAILURE = 1;
    public static final int OK = 0;

    private static final Logger LOG = LoggerFactory.getLogger(Cli.class);

    private static final String USAGE = String.join(System.lineSeparator(),
            "usage: modpack-updater publish --tag <tag> --id <release-id> [options]",
            "       modpack-updater update --tag <tag> [--id <release-id>] [options]",
            "       modpack-updater init [--tag <tag>] [options]",
            "       modpack-updater keygen [--workspace <dir>]",
            "options: --workspace <dir>   work in this directory instead of the current one",
            "         --id <release-id>   update: apply this release instead of the latest one",
            "         --cache-max-age-days <days>",
            "                             how long the local blob cache stays usable (default "
                    + Publish.DEFAULT_CACHE_MAX_AGE_DAYS + ")");

    private Cli() {
    }

    public static int run(String[] args, Path cwd, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            return usage(err, "no command");
        }
        String command = args[0];
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String argument = args[i];
            switch (argument) {
                case "--tag", "--id", "--workspace", "--cache-max-age-days" -> {
                    if (i + 1 == args.length) {
                        return usage(err, argument + " needs a value");
                    }
                    options.put(argument.substring(2), args[++i]);
                }
                default -> {
                    return usage(err, "unknown option: " + argument);
                }
            }
        }
        Path workspace = cwd.resolve(options.getOrDefault("workspace", ".")).toAbsolutePath().normalize();
        String tag = options.get("tag");
        String action = null;
        try {
            switch (command) {
                case "keygen" -> {
                    if (options.keySet().stream().anyMatch(key -> !key.equals("workspace"))) {
                        return usage(err, "keygen only takes --workspace");
                    }
                    action = "generating release signing keys";
                    Logging.begin(workspace, null, out, err);
                    Keygen.run(workspace);
                    return OK;
                }
                case "publish" -> {
                    if (tag == null) {
                        return usage(err, "publish needs --tag <tag>");
                    }
                    String releaseId = options.get("id");
                    if (releaseId == null) {
                        return usage(err, "publish needs --id <release-id>");
                    }
                    int cacheMaxAgeDays = Publish.DEFAULT_CACHE_MAX_AGE_DAYS;
                    if (options.containsKey("cache-max-age-days")) {
                        String value = options.get("cache-max-age-days");
                        try {
                            cacheMaxAgeDays = Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                            return usage(err, "--cache-max-age-days needs a positive number of days: " + value);
                        }
                        if (cacheMaxAgeDays < 1) {
                            return usage(err, "--cache-max-age-days needs a positive number of days: " + value);
                        }
                    }
                    action = "publishing release " + releaseId + " of tag " + tag;
                    Logging.begin(workspace, "publish-" + tag, out, err);
                    Publish.run(workspace, tag, releaseId, cacheMaxAgeDays);
                    return OK;
                }
                case "update" -> {
                    if (tag == null) {
                        return usage(err, "update needs --tag <tag>");
                    }
                    if (options.containsKey("cache-max-age-days")) {
                        return usage(err, "update does not take --cache-max-age-days");
                    }
                    action = "updating tag " + tag;
                    Logging.begin(workspace, "update-" + tag, out, err);
                    Update.run(workspace, tag, options.get("id"));
                    return OK;
                }
                case "init" -> {
                    if (options.containsKey("id")) {
                        return usage(err, "init does not take --id");
                    }
                    if (options.containsKey("cache-max-age-days")) {
                        return usage(err, "init does not take --cache-max-age-days");
                    }
                    action = "initializing a workspace in " + workspace;
                    Logging.begin(workspace, null, out, err);
                    Init.run(workspace, tag);
                    return OK;
                }
                default -> {
                    return usage(err, "unknown command: " + command);
                }
            }
        } catch (UserError e) {
            Logging.clearProgress();
            LOG.error("{} failed: {}", action, e.getMessage());
            return FAILURE;
        } finally {
            Logging.finish();
        }
    }

    private static int usage(PrintStream err, String message) {
        err.println(message);
        err.println(USAGE);
        return USAGE_ERROR;
    }
}
