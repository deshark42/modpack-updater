package io.github.deshark42.modpackupdater.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.github.deshark42.modpackupdater.UserError;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON as this tool needs it: documents parse into plain maps, lists and scalars, and are written
 * back either on one line or with one field per line.
 *
 * <p>The reader is strict, so a malformed manifest or config fails instead of being read as
 * something it is not. Reading and writing are Gson's; this class only maps Gson's tree to plain
 * values and back, and reports parse failures as {@link JsonException}.
 */
public final class Json {

    private static final Gson COMPACT = new GsonBuilder()
            .setStrictness(Strictness.STRICT)
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private static final Gson PRETTY = new GsonBuilder()
            .setStrictness(Strictness.STRICT)
            .disableHtmlEscaping()
            .serializeNulls()
            .setPrettyPrinting()
            .create();

    /** Gson appends this hint to every parse error; it is noise in a terminal. */
    private static final String GSON_TROUBLESHOOTING =
            "\nSee https://github.com/google/gson/blob/main/Troubleshooting.md";

    private Json() {
    }

    /** Parses one JSON document into maps, lists, strings, longs, doubles, booleans and nulls. */
    public static Object parse(String text) {
        if (text.isBlank()) {
            throw new JsonException("malformed json: the document is empty");
        }
        JsonReader reader = new JsonReader(new StringReader(text));
        reader.setStrictness(Strictness.STRICT);
        JsonElement element;
        try {
            element = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new JsonException("malformed json: unexpected trailing content");
            }
        } catch (JsonParseException e) {
            throw new JsonException("malformed json: " + concise(e.getMessage()));
        } catch (IOException e) {
            throw new JsonException("malformed json: " + concise(e.getMessage()));
        }
        return plain(element);
    }

    /** Writes the document on one line, ending with a newline. */
    public static String write(Object value) {
        return COMPACT.toJson(value) + "\n";
    }

    /** Like {@link #write}, but every object and array gets a line of its own: for the files people edit. */
    public static String writePretty(Object value) {
        return PRETTY.toJson(value) + "\n";
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

    private static Object plain(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            Map<String, Object> object = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                object.put(entry.getKey(), plain(entry.getValue()));
            }
            return object;
        }
        if (element.isJsonArray()) {
            List<Object> array = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) {
                array.add(plain(item));
            }
            return array;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isString()) {
            return primitive.getAsString();
        }
        return number(primitive.getAsString());
    }

    private static Object number(String token) {
        try {
            if (token.contains(".") || token.contains("e") || token.contains("E")) {
                return Double.parseDouble(token);
            }
            return Long.parseLong(token);
        } catch (NumberFormatException e) {
            throw new JsonException("malformed json: cannot read the number " + token);
        }
    }

    private static String concise(String message) {
        if (message == null) {
            return "the document cannot be read";
        }
        int cut = message.indexOf(GSON_TROUBLESHOOTING);
        return cut < 0 ? message : message.substring(0, cut);
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }
}
