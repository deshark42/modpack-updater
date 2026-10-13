package io.github.deshark42.modpackupdater.model;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.json.Json;
import java.util.Map;

/** Typed lookups over a parsed JSON object, with error messages that name the file. */
final class Fields {

    private Fields() {
    }

    static String string(Map<String, Object> object, String key, String what) {
        Object value = object.get(key);
        if (!(value instanceof String string)) {
            throw new UserError(what + " is missing a string \"" + key + "\"");
        }
        return string;
    }

    static String optionalString(Map<String, Object> object, String key, String fallback, String what) {
        Object value = object.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String string)) {
            throw new UserError(what + ": \"" + key + "\" must be a string");
        }
        return string.isBlank() ? fallback : string;
    }

    static boolean optionalBoolean(Map<String, Object> object, String key, boolean fallback, String what) {
        Object value = object.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Boolean bool)) {
            throw new UserError(what + ": \"" + key + "\" must be true or false");
        }
        return bool.booleanValue();
    }

    static long longValue(Map<String, Object> object, String key, String what) {
        Object value = object.get(key);
        if (!(value instanceof Long number)) {
            throw new UserError(what + ": \"" + key + "\" must be an integer");
        }
        return number.longValue();
    }

    static Map<String, Object> object(Map<String, Object> object, String key, String what) {
        return Json.asObject(object.get(key), what + ": \"" + key + "\"");
    }
}
