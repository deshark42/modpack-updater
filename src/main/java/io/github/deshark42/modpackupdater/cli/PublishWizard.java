package io.github.deshark42.modpackupdater.cli;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.command.Publish;
import io.github.deshark42.modpackupdater.model.ChannelHistory;
import io.github.deshark42.modpackupdater.model.Layout;
import io.github.deshark42.modpackupdater.model.Manifest;
import io.github.deshark42.modpackupdater.model.Names;
import io.github.deshark42.modpackupdater.model.Plan;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

final class PublishWizard {

    record Selection(Path workspace, String tag, String releaseId, String channel, int cacheMaxAgeDays) {
    }

    private record ChannelChoice(String name, boolean create) {
    }

    private final BufferedReader input;
    private final PrintStream out;

    private PublishWizard(BufferedReader input, PrintStream out) {
        this.input = input;
        this.out = out;
    }

    static Selection collect(Path cwd, Map<String, String> options, BufferedReader input, PrintStream out,
            Clock clock, BiConsumer<Selection, Publish.Prepared> publish) {
        String tag = options.containsKey("tag") ? Names.requireTag(options.get("tag")) : null;
        String releaseId = options.containsKey("id") ? Names.requireReleaseId(options.get("id")) : null;
        if (options.containsKey("channel") && options.containsKey("no-latest")) {
            throw new UserError("publish cannot combine --channel with --no-latest");
        }
        String channel = options.containsKey("channel") ? Names.requireChannel(options.get("channel")) : null;
        Integer cacheDays = options.containsKey("cache-max-age-days")
                ? positiveDays(options.get("cache-max-age-days")) : null;
        PublishWizard wizard = new PublishWizard(input, out);
        try {
            return wizard.collect(cwd.toAbsolutePath().normalize(), options, tag, releaseId, channel, cacheDays,
                    clock, publish);
        } catch (Cancelled e) {
            out.println("Publish cancelled; nothing published.");
            out.flush();
            return null;
        } catch (IOException e) {
            throw new UserError("publish wizard I/O failed: " + e.getMessage(), e);
        }
    }

    private Selection collect(Path cwd, Map<String, String> options, String tag, String releaseId,
            String channel, Integer cacheDays, Clock clock, BiConsumer<Selection, Publish.Prepared> publish)
            throws IOException {
        Path workspace = options.containsKey("workspace") ? workspace(cwd, options.get("workspace")) : null;
        out.println("Publish wizard — enter :q at any prompt to cancel. Enter a new release id after the diff to publish.");
        if (workspace == null) {
            workspace = ask("Workspace [" + cwd + "]", value -> workspace(cwd, value.isEmpty() ? "." : value));
        }
        Path selectedWorkspace = workspace;
        if (tag == null) {
            List<String> tags = discoverTags(workspace);
            if (tags.isEmpty()) {
                throw new UserError("no publisher tags found in " + workspace
                        + "; run init --tag <tag> to create base or base_pack inputs");
            }
            out.println("Available tags:");
            for (int i = 0; i < tags.size(); i++) {
                out.println("  " + (i + 1) + ") " + tags.get(i));
            }
            tag = ask("Tag number" + (tags.size() == 1 ? " [1]" : " (1-" + tags.size() + ")"),
                    value -> requireInputs(selectedWorkspace, selectTag(tags, value)));
        } else {
            requireInputs(workspace, tag);
        }
        if (!options.containsKey("channel") && !options.containsKey("no-latest")) {
            channel = chooseChannel(workspace, tag);
        }
        int selectedCacheDays = cacheDays == null ? Publish.DEFAULT_CACHE_MAX_AGE_DAYS : cacheDays;
        out.println("Workspace: " + workspace);
        out.println("Tag: " + tag);
        out.println("Channel: " + (channel == null ? "(no channel)" : channel));
        out.println("Reading the channel baseline and preparing the publish diff...");
        out.flush();
        try (Publish.Prepared prepared = Publish.prepare(workspace, tag, channel, clock)) {
            Manifest baseline = prepared.baseline();
            if (channel == null) {
                out.println("No channel selected: no remote comparison baseline; all payload files are shown as added.");
            } else if (baseline == null) {
                out.println("Latest release id on channel " + channel + ": (none; first publication on this channel)");
            } else {
                out.println("Latest release id on channel " + channel + ": " + baseline.releaseId());
            }
            Plan diff = Plan.between(baseline == null ? null : baseline.files(), prepared.files());
            out.println("Diff (channel release -> workspace publish payload): " + diff.describe());
            for (String path : diff.added()) {
                out.println("  + " + path);
            }
            for (String path : diff.replaced()) {
                out.println("  ~ " + path);
            }
            for (String path : diff.deleted()) {
                out.println("  - " + path);
            }
            if (diff.isEmpty()) {
                out.println("No file changes.");
            }
            out.println("Blob cache max age (days): " + selectedCacheDays);
            out.println("Release ids are immutable. Enter a new release id to publish immediately; blank cancels.");
            String requestedId = releaseId;
            String confirmedId = ask(requestedId == null ? "New release id to publish"
                    : "Type release id '" + requestedId + "' to publish", value -> {
                        if (value.isEmpty()) {
                            throw new Cancelled();
                        }
                        String id = Names.requireReleaseId(value);
                        if (requestedId != null && !requestedId.equals(id)) {
                            throw new UserError("enter the supplied release id " + requestedId + " to confirm, or :q to cancel");
                        }
                        if (baseline != null && baseline.releaseId().equals(id)) {
                            throw new UserError("release ids are immutable; enter a different release id from " + id);
                        }
                        return id;
                    });
            Selection selection = new Selection(workspace, tag, confirmedId, channel, selectedCacheDays);
            publish.accept(selection, prepared);
            return selection;
        }
    }

    private String chooseChannel(Path workspace, String tag) throws IOException {
        List<String> channels;
        try {
            channels = ChannelHistory.channels(workspace, tag);
        } catch (UserError e) {
            out.println("Warning: cannot read channel history; enter a channel name if needed: " + e.getMessage());
            channels = List.of(ObjectKeys.DEFAULT_CHANNEL);
        }
        out.println("Known channels for tag " + tag + " (local history, not a server listing):");
        for (int i = 0; i < channels.size(); i++) {
            out.println("  " + (i + 1) + ") " + channels.get(i));
        }
        out.println("  n) New channel");
        out.println("  -) No channel (pointerless release)");
        List<String> choices = channels;
        ChannelChoice selected = ask("Channel number [1], name, n=new channel, -=no channel", value -> {
            if (value.isEmpty()) {
                return new ChannelChoice(ObjectKeys.DEFAULT_CHANNEL, false);
            }
            if (value.equals("-") || value.equals("n")) {
                return new ChannelChoice(null, value.equals("n"));
            }
            if (value.matches("[0-9]+")) {
                int number;
                try {
                    number = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new UserError("enter a channel number from 1 to " + choices.size() + ", or n for a new name");
                }
                if (number < 1 || number > choices.size()) {
                    throw new UserError("enter a channel number from 1 to " + choices.size() + ", or n for a new name");
                }
                return new ChannelChoice(choices.get(number - 1), false);
            }
            return new ChannelChoice(Names.requireChannel(value), false);
        });
        return selected.create() ? ask("New channel name", Names::requireChannel) : selected.name();
    }

    private <T> T ask(String label, Function<String, T> validate) throws IOException {
        while (true) {
            String value = prompt(label);
            try {
                return validate.apply(value);
            } catch (UserError e) {
                if (e.getCause() instanceof IOException) {
                    throw e;
                }
                out.println(e.getMessage());
            }
        }
    }

    private String prompt(String label) throws IOException {
        out.print(label + ": ");
        out.flush();
        if (out.checkError()) {
            throw new UserError("cannot write publish wizard prompt");
        }
        String answer = input.readLine();
        if (answer == null || answer.equals(":q")) {
            throw new Cancelled();
        }
        return answer.strip();
    }

    private static Path workspace(Path cwd, String value) {
        Path workspace;
        try {
            workspace = cwd.resolve(value).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new UserError("invalid workspace path: " + value, e);
        }
        BasicFileAttributes root = attributes(workspace);
        if (root == null || !root.isDirectory()) {
            throw new UserError("workspace must be an existing directory: " + workspace);
        }
        Path config = Layout.configFile(workspace);
        BasicFileAttributes configAttributes = attributes(config, LinkOption.NOFOLLOW_LINKS);
        if (configAttributes == null || !configAttributes.isRegularFile()) {
            throw new UserError("workspace config is missing: " + config + "; run init first");
        }
        return workspace;
    }

    private static List<String> discoverTags(Path workspace) throws IOException {
        List<String> tags = new ArrayList<>();
        try (var children = Files.newDirectoryStream(workspace)) {
            for (Path child : children) {
                BasicFileAttributes childAttributes = attributes(child, LinkOption.NOFOLLOW_LINKS);
                if (childAttributes == null || !childAttributes.isDirectory()) {
                    continue;
                }
                try {
                    tags.add(requireInputs(workspace, child.getFileName().toString()));
                } catch (UserError e) {
                    if (e.getCause() instanceof IOException) {
                        throw e;
                    }
                }
            }
        } catch (DirectoryIteratorException e) {
            throw new UserError("cannot discover publisher tags in " + workspace + ": " + e.getMessage(), e.getCause());
        }
        tags.sort(String::compareTo);
        return tags;
    }

    private static String selectTag(List<String> tags, String value) {
        if (value.isEmpty() && tags.size() == 1) {
            return tags.get(0);
        }
        int number;
        try {
            number = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new UserError("enter a tag number from 1 to " + tags.size());
        }
        if (number < 1 || number > tags.size()) {
            throw new UserError("enter a tag number from 1 to " + tags.size());
        }
        return tags.get(number - 1);
    }

    private static String requireInputs(Path workspace, String tag) {
        Names.requireTag(tag);
        Path base = Layout.baseDir(workspace, tag);
        Path packs = Layout.basePackDir(workspace, tag);
        boolean hasBase = inputDirectory(base);
        boolean hasPacks = inputDirectory(packs);
        if (!hasBase && !hasPacks) {
            throw new UserError("tag " + tag + " has no publisher inputs: " + base + " or " + packs
                    + " must exist; run init --tag " + tag);
        }
        return tag;
    }

    private static boolean inputDirectory(Path path) {
        BasicFileAttributes attributes = attributes(path, LinkOption.NOFOLLOW_LINKS);
        if (attributes != null && !attributes.isDirectory()) {
            throw new UserError("publisher input must be a directory: " + path);
        }
        return attributes != null;
    }

    private static BasicFileAttributes attributes(Path path, LinkOption... options) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, options);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UserError("cannot inspect publish wizard path " + path + ": " + e.getMessage(), e);
        }
    }

    private static int positiveDays(String value) {
        try {
            int days = Integer.parseInt(value);
            if (days > 0) {
                return days;
            }
        } catch (NumberFormatException e) {
            throw new UserError("--cache-max-age-days needs a positive number of days: " + value, e);
        }
        throw new UserError("--cache-max-age-days needs a positive number of days: " + value);
    }

    private static final class Cancelled extends RuntimeException {
    }
}
