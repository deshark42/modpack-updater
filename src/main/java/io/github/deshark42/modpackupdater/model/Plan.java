package io.github.deshark42.modpackupdater.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The work needed to turn the files recorded in one manifest into the files recorded in another.
 *
 * <p>The plan is derived from the two manifests alone; it never inspects the files on disk.
 */
public record Plan(List<String> added, List<String> replaced, List<String> deleted, long downloadSize) {

    public Plan {
        added = List.copyOf(added);
        replaced = List.copyOf(replaced);
        deleted = List.copyOf(deleted);
    }

    public static Plan between(Manifest local, Manifest target) {
        List<String> added = new ArrayList<>();
        List<String> replaced = new ArrayList<>();
        long downloadSize = 0;
        for (Map.Entry<String, FileEntry> entry : target.files().entrySet()) {
            FileEntry mine = local == null ? null : local.files().get(entry.getKey());
            if (mine == null) {
                added.add(entry.getKey());
                downloadSize += entry.getValue().size();
            } else if (!mine.hash().equals(entry.getValue().hash())) {
                replaced.add(entry.getKey());
                downloadSize += entry.getValue().size();
            }
        }
        List<String> deleted = new ArrayList<>();
        if (local != null) {
            for (String path : local.files().keySet()) {
                if (!target.files().containsKey(path)) {
                    deleted.add(path);
                }
            }
        }
        return new Plan(added, replaced, deleted, downloadSize);
    }

    /** Paths whose content has to be fetched, sorted so the order of a run is stable. */
    public List<String> downloads() {
        Set<String> paths = new TreeSet<>(added);
        paths.addAll(replaced);
        return List.copyOf(paths);
    }

    public boolean isEmpty() {
        return added.isEmpty() && replaced.isEmpty() && deleted.isEmpty();
    }

    public int size() {
        return added.size() + replaced.size() + deleted.size();
    }

    public String describe() {
        return added.size() + " added, " + replaced.size() + " replaced, " + deleted.size() + " deleted";
    }

    @Override
    public String toString() {
        return describe() + " (" + Collections.unmodifiableList(downloads()).size() + " files to download)";
    }
}
