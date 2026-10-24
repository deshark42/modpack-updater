package io.github.deshark42.modpackupdater.cli;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.command.Init;
import io.github.deshark42.modpackupdater.command.Publish;
import io.github.deshark42.modpackupdater.command.Update;
import io.github.deshark42.modpackupdater.log.Logging;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** The command line: three commands, a handful of options, usage errors kept apart from real ones. */
public final class Cli {

    public static final int USAGE_ERROR = 2;
    public static final int FAILURE = 1;
    public static final int OK = 0;

    private static final String USAGE = String.join(System.lineSeparator(),
            "usage: modpack-updater publish --tag <tag> --id <release-id> [options]",
            "       modpack-updater update --tag <tag> [--id <release-id>] [options]",
            "       modpack-updater init [--tag <tag>] [options]",
            "options: --workspace <dir>   work in this directory instead of the current one",
            "         --id <release-id>   update: apply this release instead of the latest one",
            "         --cache-max-age-days <days>",
            "                             how long the local blob cache stays usable (default "
                    + Publish.DEFAULT_CACHE_MAX_AGE_DAYS + ")",
            "         --dry-run           report what would happen, write nothing",
            "         --verbose           list every file the command touches",
            "         --quiet             print only the closing line");

    private Cli() {
    }

    public static int run(String[] args, Path cwd, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            return usage(err, "no command");
        }
        String command = args[0];
        Map<String, String> options = new HashMap<>();
        boolean dryRun = false;
        boolean verbose = false;
        boolean quiet = false;
        for (int i = 1; i < args.length; i++) {
            String argument = args[i];
            switch (argument) {
                case "--dry-run" -> dryRun = true;
                case "--verbose" -> verbose = true;
                case "--quiet" -> quiet = true;
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
        if (verbose && quiet) {
            return usage(err, "--verbose and --quiet cannot be combined");
        }
        Path workspace = cwd.resolve(options.getOrDefault("workspace", ".")).toAbsolutePath().normalize();
        String tag = options.get("tag");
        Logging.Mode mode = quiet ? Logging.Mode.QUIET
                : verbose ? Logging.Mode.VERBOSE : Logging.Mode.NORMAL;
        try {
            switch (command) {
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
                    Logging.begin(workspace, dryRun ? null : "publish-" + tag, mode, out, err);
                    Publish.run(workspace, tag, releaseId, cacheMaxAgeDays, dryRun);
                    return OK;
                }
                case "update" -> {
                    if (tag == null) {
                        return usage(err, "update needs --tag <tag>");
                    }
                    if (options.containsKey("cache-max-age-days")) {
                        return usage(err, "update does not take --cache-max-age-days");
                    }
                    Logging.begin(workspace, dryRun ? null : "update-" + tag, mode, out, err);
                    Update.run(workspace, tag, options.get("id"), dryRun);
                    return OK;
                }
                case "init" -> {
                    if (options.containsKey("id")) {
                        return usage(err, "init does not take --id");
                    }
                    if (options.containsKey("cache-max-age-days")) {
                        return usage(err, "init does not take --cache-max-age-days");
                    }
                    if (dryRun) {
                        return usage(err, "init does not take --dry-run");
                    }
                    Logging.begin(workspace, null, mode, out, err);
                    Init.run(workspace, tag);
                    return OK;
                }
                default -> {
                    return usage(err, "unknown command: " + command);
                }
            }
        } catch (UserError e) {
            Logging.clearProgress();
            err.println("error: " + e.getMessage());
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
