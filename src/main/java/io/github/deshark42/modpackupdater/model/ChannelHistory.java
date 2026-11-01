package io.github.deshark42.modpackupdater.model;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.AtomicFile;
import io.github.deshark42.modpackupdater.json.Json;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Workspace-local suggestions, not an authoritative list of remote channel pointers. */
public final class ChannelHistory {

    private ChannelHistory() {
    }

    public static List<String> channels(Path workspace, String tag) {
        TreeSet<String> remembered = read(Layout.channelHistoryFile(workspace, tag));
        List<String> channels = new ArrayList<>();
        channels.add(ObjectKeys.DEFAULT_CHANNEL);
        channels.addAll(remembered);
        return List.copyOf(channels);
    }

    public static void remember(Path workspace, String tag, String channel) {
        Names.requireTag(tag);
        if (channel == null || ObjectKeys.DEFAULT_CHANNEL.equals(channel)) {
            return;
        }
        Names.requireChannel(channel);
        TreeSet<String> remembered = read(Layout.channelHistoryFile(workspace, tag));
        if (!remembered.add(channel)) {
            return;
        }
        AtomicFile.write(Layout.channelHistoryFile(workspace, tag), Json.writePretty(remembered));
    }

    private static TreeSet<String> read(Path file) {
        TreeSet<String> remembered = new TreeSet<>();
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
            return remembered;
        }
        try {
            List<Object> entries = Json.asArray(Json.parse(AtomicFile.read(file)), "channel history");
            for (Object entry : entries) {
                if (!(entry instanceof String channel)) {
                    throw new UserError("channel history entries must be strings");
                }
                remembered.add(Names.requireChannel(channel));
            }
        } catch (Json.JsonException | UserError e) {
            throw new UserError("cannot read channel history " + file + ": " + e.getMessage(), e);
        }
        remembered.remove(ObjectKeys.DEFAULT_CHANNEL);
        return remembered;
    }
}
