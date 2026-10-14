package io.github.deshark42.modpackupdater.model;

import io.github.deshark42.modpackupdater.UserError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules in a tag's remove.txt: .gitignore patterns without negation or backslash escapes. A
 * rule is anchored at the tag directory when it holds a separator anywhere but the very end, and a
 * rule that matches a directory removes everything below it. The tree the rules judge is a flat set
 * of file paths, so matching walks a file's ancestor directories as well.
 */
public final class RemoveRules {

    private final List<Rule> rules;

    private RemoveRules(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /** A tag without a remove.txt simply has no rules. */
    public static RemoveRules read(Path file) {
        if (!Files.isRegularFile(file)) {
            return new RemoveRules(List.of());
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UserError("cannot read " + file + ": " + e.getMessage(), e);
        }
        return of(lines);
    }

    /** Blank lines and comments are ignored, repeats collapse, and the rest must be a valid rule. */
    public static RemoveRules of(List<String> lines) {
        Set<String> seen = new LinkedHashSet<>();
        for (String line : lines) {
            String text = line.strip();
            if (!text.isEmpty() && !text.startsWith("#")) {
                seen.add(text);
            }
        }
        List<Rule> parsed = new ArrayList<>();
        for (String text : seen) {
            parsed.add(Rule.parse(text));
        }
        return new RemoveRules(parsed);
    }

    public int size() {
        return rules.size();
    }

    /** What the rules took out of a tree, and which rules found nothing to take. */
    public Outcome apply(Set<String> paths) {
        Set<String> removed = new LinkedHashSet<>();
        List<String> unmatched = new ArrayList<>();
        for (Rule rule : rules) {
            boolean matched = false;
            for (String path : paths) {
                if (rule.removes(path)) {
                    matched = true;
                    removed.add(path);
                }
            }
            if (!matched) {
                unmatched.add(rule.text());
            }
        }
        return new Outcome(Set.copyOf(removed), List.copyOf(unmatched));
    }

    /** The paths a set of rules removes, and the rules that removed nothing. */
    public record Outcome(Set<String> removed, List<String> unmatched) {
    }

    private record Rule(String text, Pattern pattern, boolean anchored, boolean directoryOnly) {

        static Rule parse(String text) {
            if (text.indexOf('\\') >= 0 || text.indexOf(':') >= 0) {
                throw new UserError("removal rule is not a plain relative path: " + text);
            }
            boolean directoryOnly = text.endsWith("/");
            String body = directoryOnly ? text.substring(0, text.length() - 1) : text;
            boolean anchored = body.indexOf('/') >= 0;
            String pattern = anchored && body.startsWith("/") ? body.substring(1) : body;
            List<String> segments = new ArrayList<>();
            for (String segment : pattern.split("/", -1)) {
                if (segment.isEmpty()) {
                    continue;
                }
                if (segment.equals(".") || segment.equals("..")) {
                    throw new UserError("removal rule leaves the tag directory: " + text);
                }
                segments.add(segment);
            }
            if (segments.isEmpty()) {
                throw new UserError("removal rule names no path: " + text);
            }
            return new Rule(text, Pattern.compile(regex(segments)), anchored, directoryOnly);
        }

        boolean removes(String file) {
            if (matches(file, false)) {
                return true;
            }
            for (int slash = file.indexOf('/'); slash >= 0; slash = file.indexOf('/', slash + 1)) {
                if (matches(file.substring(0, slash), true)) {
                    return true;
                }
            }
            return false;
        }

        private boolean matches(String path, boolean directory) {
            if (directoryOnly && !directory) {
                return false;
            }
            String subject = anchored ? path : path.substring(path.lastIndexOf('/') + 1);
            return pattern.matcher(subject).matches();
        }
    }

    /** An unanchored rule only ever sees one path segment, an anchored one sees the whole path. */
    private static String regex(List<String> segments) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).equals("**")) {
                out.append(i == segments.size() - 1 ? ".*" : "(?:[^/]+/)*");
            } else {
                out.append(segment(segments.get(i)));
                if (i < segments.size() - 1) {
                    out.append('/');
                }
            }
        }
        return out.toString();
    }

    private static String segment(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '*' -> out.append("[^/]*");
                case '?' -> out.append("[^/]");
                case '[' -> {
                    int end = closing(text, i);
                    if (end < 0) {
                        out.append("\\[");
                    } else {
                        out.append(charClass(text.substring(i + 1, end)));
                        i = end;
                    }
                }
                case '\\', '^', '$', '.', '|', '+', '(', ')', '{', '}', ']' -> out.append('\\').append(c);
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Closes a bracket expression, allowing the leading negation and literal bracket git allows. */
    private static int closing(String text, int open) {
        int i = open + 1;
        if (i < text.length() && (text.charAt(i) == '!' || text.charAt(i) == '^')) {
            i++;
        }
        if (i < text.length() && text.charAt(i) == ']') {
            i++;
        }
        return text.indexOf(']', i);
    }

    private static String charClass(String body) {
        StringBuilder out = new StringBuilder("[");
        String rest = body;
        if (rest.startsWith("!") || rest.startsWith("^")) {
            out.append('^');
            rest = rest.substring(1);
        }
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '\\' || c == ']' || c == '^' || c == '[') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.append(']').toString();
    }
}
