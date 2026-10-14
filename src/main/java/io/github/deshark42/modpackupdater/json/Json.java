package io.github.deshark42.modpackupdater.json;

import io.github.deshark42.modpackupdater.UserError;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict JSON reader and writer. The files this tool reads and writes are small and
 * machine-generated, so a dependency-free implementation is enough.
 */
public final class Json {

    private Json() {
    }

    public static Object parse(String text) {
        return new Parser(text).parseDocument();
    }

    public static String write(Object value) {
        return write(value, false);
    }

    /** Like {@link #write}, but every object and array gets a line of its own: for the files people edit. */
    public static String writePretty(Object value) {
        return write(value, true);
    }

    private static String write(Object value, boolean pretty) {
        StringBuilder out = new StringBuilder();
        writeValue(value, out, 0, pretty);
        return out.append('\n').toString();
    }

    public static Map<String, Object> asObject(Object value, String what) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new UserError(what + ": expected an object");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new UserError(what + ": object keys must be strings");
            }
            out.put(key, entry.getValue());
        }
        return out;
    }

    public static List<Object> asArray(Object value, String what) {
        if (!(value instanceof List<?> list)) {
            throw new UserError(what + ": expected an array");
        }
        return new ArrayList<>(list);
    }

    private static void writeValue(Object value, StringBuilder out, int depth, boolean pretty) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String string) {
            writeString(string, out);
        } else if (value instanceof Boolean bool) {
            out.append(bool.booleanValue());
        } else if (value instanceof Number number) {
            writeNumber(number, out);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(map, out, depth, pretty);
        } else if (value instanceof List<?> list) {
            writeArray(list, out, depth, pretty);
        } else {
            throw new IllegalArgumentException("unsupported json value: " + value.getClass().getName());
        }
    }

    private static void writeObject(Map<?, ?> map, StringBuilder out, int depth, boolean pretty) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        boolean inline = !pretty && allScalars(map.values());
        if (inline) {
            out.append("{ ");
            appendEntries(map, out, depth, true, pretty);
            out.append(" }");
            return;
        }
        out.append("{\n");
        appendEntries(map, out, depth, false, pretty);
        out.append('\n');
        indent(out, depth);
        out.append('}');
    }

    private static void appendEntries(Map<?, ?> map, StringBuilder out, int depth, boolean inline,
                                      boolean pretty) {
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(inline ? ", " : ",\n");
            }
            first = false;
            if (!inline) {
                indent(out, depth + 1);
            }
            writeString((String) entry.getKey(), out);
            out.append(": ");
            writeValue(entry.getValue(), out, depth + 1, pretty);
        }
    }

    private static void writeArray(List<?> list, StringBuilder out, int depth, boolean pretty) {
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        boolean inline = !pretty && allScalars(list);
        if (inline) {
            out.append("[ ");
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    out.append(", ");
                }
                first = false;
                writeValue(item, out, depth + 1, pretty);
            }
            out.append(" ]");
            return;
        }
        out.append("[\n");
        boolean first = true;
        for (Object item : list) {
            if (!first) {
                out.append(",\n");
            }
            first = false;
            indent(out, depth + 1);
            writeValue(item, out, depth + 1, pretty);
        }
        out.append('\n');
        indent(out, depth);
        out.append(']');
    }

    private static boolean allScalars(Iterable<?> values) {
        for (Object value : values) {
            if (value instanceof Map || value instanceof List) {
                return false;
            }
        }
        return true;
    }

    private static void indent(StringBuilder out, int depth) {
        for (int i = 0; i < depth; i++) {
            out.append("  ");
        }
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static void writeNumber(Number number, StringBuilder out) {
        if (number instanceof Double || number instanceof Float) {
            double value = number.doubleValue();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException("json cannot hold " + value);
            }
            out.append(String.valueOf(value));
        } else if (number instanceof java.math.BigDecimal decimal) {
            out.append(decimal.toPlainString());
        } else {
            out.append(number.longValue());
        }
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }

    private static final class Parser {
        private static final int MAX_DEPTH = 64;

        private final String text;
        private int position;

        Parser(String text) {
            this.text = text;
        }

        Object parseDocument() {
            Object value = parseValue(0);
            skipWhitespace();
            if (position < text.length()) {
                throw error("unexpected trailing content");
            }
            return value;
        }

        private Object parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nesting is too deep");
            }
            skipWhitespace();
            char c = peek();
            return switch (c) {
                case '{' -> parseObject(depth);
                case '[' -> parseArray(depth);
                case '"' -> parseString();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject(int depth) {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("expected a string key");
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                map.put(key, parseValue(depth + 1));
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> parseArray(int depth) {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                position++;
                return list;
            }
            while (true) {
                list.add(parseValue(depth + 1));
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (position >= text.length()) {
                    throw error("unterminated string");
                }
                char c = text.charAt(position++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    if (c < 0x20) {
                        throw error("unescaped control character in string");
                    }
                    out.append(c);
                    continue;
                }
                if (position >= text.length()) {
                    throw error("unterminated escape");
                }
                char escape = text.charAt(position++);
                switch (escape) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (position + 4 > text.length()) {
                            throw error("truncated unicode escape");
                        }
                        String hex = text.substring(position, position + 4);
                        try {
                            out.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw error("bad unicode escape: " + hex);
                        }
                        position += 4;
                    }
                    default -> throw error("bad escape: " + escape);
                }
            }
        }

        private Object parseNumber() {
            int start = position;
            if (peek() == '-') {
                position++;
            }
            while (position < text.length() && Character.isDigit(text.charAt(position))) {
                position++;
            }
            boolean integral = true;
            if (position < text.length() && text.charAt(position) == '.') {
                integral = false;
                position++;
                while (position < text.length() && Character.isDigit(text.charAt(position))) {
                    position++;
                }
            }
            if (position < text.length() && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
                integral = false;
                position++;
                if (position < text.length() && (text.charAt(position) == '+' || text.charAt(position) == '-')) {
                    position++;
                }
                while (position < text.length() && Character.isDigit(text.charAt(position))) {
                    position++;
                }
            }
            String token = text.substring(start, position);
            if (token.isEmpty() || token.equals("-")) {
                throw error("expected a json value");
            }
            try {
                return integral ? (Object) Long.valueOf(token) : (Object) Double.valueOf(token);
            } catch (NumberFormatException e) {
                throw error("malformed number: " + token);
            }
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, position)) {
                throw error("expected " + word);
            }
            position += word.length();
            return value;
        }

        private char peek() {
            if (position >= text.length()) {
                throw error("unexpected end of input");
            }
            return text.charAt(position);
        }

        private char next() {
            char c = peek();
            position++;
            return c;
        }

        private void expect(char expected) {
            skipWhitespace();
            if (position >= text.length() || text.charAt(position) != expected) {
                throw error("expected '" + expected + "'");
            }
            position++;
        }

        private void skipWhitespace() {
            while (position < text.length()) {
                char c = text.charAt(position);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    position++;
                } else {
                    break;
                }
            }
        }

        private JsonException error(String message) {
            int at = Math.min(position, text.length());
            String near = text.substring(Math.max(0, at - 20), Math.min(text.length(), at + 20))
                    .replace("\n", "\\n");
            return new JsonException(message + " (offset " + at + ", near \"" + near + "\")");
        }
    }
}
