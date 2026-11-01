package io.github.deshark42.modpackupdater.cli;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.command.Init;
import io.github.deshark42.modpackupdater.command.Keygen;
import io.github.deshark42.modpackupdater.command.Promote;
import io.github.deshark42.modpackupdater.command.Publish;
import io.github.deshark42.modpackupdater.command.Update;
import io.github.deshark42.modpackupdater.log.Logging;
import io.github.deshark42.modpackupdater.model.ChannelHistory;
import io.github.deshark42.modpackupdater.model.Names;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The command line: workspace commands and local signing key generation. */
public final class Cli {

    public static final int USAGE_ERROR = 2;
    public static final int FAILURE = 1;
    public static final int OK = 0;

    private static final Logger LOG = LoggerFactory.getLogger(Cli.class);

    private static final String USAGE = String.join(System.lineSeparator(),
            "usage: modpack-updater publish [--tag <tag>] [--id <release-id>] [options]",
            "       publish: missing tag or id starts a wizard when a terminal is available",
            "       modpack-updater promote --tag <tag> --id <release-id> [options]",
            "       modpack-updater update --tag <tag> [--tag <tag> ...] [--id <release-id>] [options]",
            "       modpack-updater init [--tag <tag>] [options]",
            "       modpack-updater keygen [--workspace <dir>]",
            "options: --workspace <dir>   work in this directory instead of the current one",
            "         --tag <tag>         update: repeat to update tags in order; stop on the first failure",
            "         --id <release-id>   update: apply this release (one distinct tag only)",
            "         --channel <name>    publish/promote/update: select a channel (default latest)",
            "         --cache-max-age-days <days>",
            "                             how long the local blob cache stays usable (default "
                    + Publish.DEFAULT_CACHE_MAX_AGE_DAYS + ")",
            "         --no-latest         publish: update no channel; cannot be combined with --channel");

    private Cli() {
    }

    public static int run(String[] args, Path cwd, PrintStream out, PrintStream err) {
        return run(args, cwd, out, err, Clock.systemDefaultZone());
    }

    public static int run(String[] args, Path cwd, PrintStream out, PrintStream err, Clock clock) {
        var console = System.console();
        return run(args, cwd, out, err, clock, console == null ? null : console.reader());
    }

    static int run(String[] args, Path cwd, PrintStream out, PrintStream err, Clock clock, Reader input) {
        if (args.length == 0) {
            return usage(err, "no command");
        }
        String command = args[0];
        Map<String, String> options = new HashMap<>();
        Set<String> updateTags = new LinkedHashSet<>();
        for (int i = 1; i < args.length; i++) {
            String argument = args[i];
            switch (argument) {
                case "--tag", "--id", "--workspace", "--cache-max-age-days", "--channel" -> {
                    if (i + 1 == args.length) {
                        return usage(err, argument + " needs a value");
                    }
                    String value = args[++i];
                    options.put(argument.substring(2), value);
                    if (command.equals("update") && argument.equals("--tag")) {
                        updateTags.add(value);
                    }
                }
                case "--no-latest" -> {
                    if (!command.equals("publish")) {
                        return usage(err, command + " does not take --no-latest");
                    }
                    options.put("no-latest", "true");
                }
                default -> {
                    return usage(err, "unknown option: " + argument);
                }
            }
        }
        if (command.equals("publish") && options.containsKey("channel") && options.containsKey("no-latest")) {
            return usage(err, "publish cannot combine --channel with --no-latest");
        }
        if (command.equals("publish") && input != null
                && (!options.containsKey("tag") || !options.containsKey("id"))) {
            try {
                PublishWizard.collect(cwd, options, new BufferedReader(input), out, clock, (selection, prepared) -> {
                    Logging.begin(selection.workspace(), "publish-" + selection.tag(), out, err, clock);
                    prepared.publish(selection.releaseId(), selection.cacheMaxAgeDays());
                    rememberChannel(selection.workspace(), selection.tag(), selection.channel());
                });
                return OK;
            } catch (UserError e) {
                Logging.clearProgress();
                err.println("publish failed: " + e.getMessage());
                return FAILURE;
            } finally {
                Logging.finish();
            }
        }
        Path workspace = cwd.resolve(options.getOrDefault("workspace", ".")).toAbsolutePath().normalize();
        String tag = options.get("tag");
        String channel = options.getOrDefault("channel", ObjectKeys.DEFAULT_CHANNEL);
        String action = null;
        try {
            switch (command) {
                case "keygen" -> {
                    if (options.keySet().stream().anyMatch(key -> !key.equals("workspace"))) {
                        return usage(err, "keygen only takes --workspace");
                    }
                    action = "generating release signing keys";
                    Logging.begin(workspace, null, out, err, clock);
                    Keygen.run(workspace);
                    return OK;
                }
                case "publish" -> {
                    if (tag == null) {
                        return usage(err, "publish needs --tag <tag> without a terminal; run publish in a terminal for the wizard");
                    }
                    String releaseId = options.get("id");
                    if (releaseId == null) {
                        return usage(err, "publish needs --id <release-id> without a terminal; run publish in a terminal for the wizard");
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
                    Logging.begin(workspace, "publish-" + tag, out, err, clock);
                    Publish.run(workspace, tag, releaseId, cacheMaxAgeDays,
                            options.containsKey("no-latest") ? null : channel, clock);
                    rememberChannel(workspace, tag, options.containsKey("no-latest") ? null : channel);
                    return OK;
                }
                case "promote" -> {
                    if (tag == null) {
                        return usage(err, "promote needs --tag <tag>");
                    }
                    String releaseId = options.get("id");
                    if (releaseId == null) {
                        return usage(err, "promote needs --id <release-id>");
                    }
                    if (options.containsKey("cache-max-age-days")) {
                        return usage(err, "promote does not take --cache-max-age-days");
                    }
                    action = "promoting release " + releaseId + " of tag " + tag + " to channel " + channel;
                    Logging.begin(workspace, "promote-" + tag, out, err, clock);
                    Promote.run(workspace, tag, releaseId, channel, clock);
                    rememberChannel(workspace, tag, channel);
                    return OK;
                }
                case "update" -> {
                    if (updateTags.isEmpty()) {
                        return usage(err, "update needs --tag <tag>");
                    }
                    if (options.containsKey("cache-max-age-days")) {
                        return usage(err, "update does not take --cache-max-age-days");
                    }
                    String releaseId = options.get("id");
                    if (updateTags.size() > 1 && releaseId != null) {
                        return usage(err, "update with multiple tags does not take --id");
                    }
                    action = "updating tag " + updateTags.iterator().next();
                    Logging.begin(workspace, updateTags.size() == 1 ? "update-" + tag : "update", out, err, clock);
                    Names.requireChannel(channel);
                    for (String updateTag : updateTags) {
                        action = "updating tag " + updateTag;
                        Names.requireTag(updateTag);
                    }
                    if (releaseId != null) {
                        Names.requireReleaseId(releaseId);
                    }
                    for (String updateTag : updateTags) {
                        action = "updating tag " + updateTag;
                        Logging.resetProgress();
                        Update.run(workspace, updateTag, releaseId, channel, clock);
                        if (releaseId == null) {
                            rememberChannel(workspace, updateTag, channel);
                        }
                    }
                    return OK;
                }
                case "init" -> {
                    if (options.containsKey("channel")) {
                        return usage(err, "init does not take --channel");
                    }
                    if (options.containsKey("id")) {
                        return usage(err, "init does not take --id");
                    }
                    if (options.containsKey("cache-max-age-days")) {
                        return usage(err, "init does not take --cache-max-age-days");
                    }
                    action = "initializing a workspace in " + workspace;
                    Logging.begin(workspace, null, out, err, clock);
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

    private static void rememberChannel(Path workspace, String tag, String channel) {
        try {
            ChannelHistory.remember(workspace, tag, channel);
        } catch (UserError e) {
            LOG.warn("Command succeeded, but could not remember channel {} for tag {}: {}", channel, tag, e.getMessage());
        }
    }

    private static int usage(PrintStream err, String message) {
        err.println(message);
        err.println(USAGE);
        return USAGE_ERROR;
    }
}
