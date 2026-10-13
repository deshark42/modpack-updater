package io.github.deshark42.modpackupdater.cli;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.command.Publish;
import io.github.deshark42.modpackupdater.command.Update;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** The command line: two commands, a handful of options, usage errors kept apart from real ones. */
public final class Cli {

    public static final int USAGE_ERROR = 2;
    public static final int FAILURE = 1;
    public static final int OK = 0;

    private static final String USAGE = String.join(System.lineSeparator(),
            "usage: modpack-updater publish --tag <tag> --id <release-id> [--workspace <dir>] [--dry-run]",
            "       modpack-updater update --tag <tag> [--workspace <dir>] [--dry-run]");

    private Cli() {
    }

    public static int run(String[] args, Path cwd, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            return usage(err, "no command");
        }
        String command = args[0];
        Map<String, String> options = new HashMap<>();
        boolean dryRun = false;
        for (int i = 1; i < args.length; i++) {
            String argument = args[i];
            switch (argument) {
                case "--dry-run" -> dryRun = true;
                case "--tag", "--id", "--workspace" -> {
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
        if (tag == null) {
            return usage(err, command + " needs --tag <tag>");
        }
        try {
            switch (command) {
                case "publish" -> {
                    String releaseId = options.get("id");
                    if (releaseId == null) {
                        return usage(err, "publish needs --id <release-id>");
                    }
                    Publish.run(workspace, tag, releaseId, dryRun, out);
                    return OK;
                }
                case "update" -> {
                    if (options.containsKey("id")) {
                        return usage(err, "update does not take --id");
                    }
                    Update.run(workspace, tag, dryRun, out);
                    return OK;
                }
                default -> {
                    return usage(err, "unknown command: " + command);
                }
            }
        } catch (UserError e) {
            err.println("error: " + e.getMessage());
            return FAILURE;
        }
    }

    private static int usage(PrintStream err, String message) {
        err.println(message);
        err.println(USAGE);
        return USAGE_ERROR;
    }
}
