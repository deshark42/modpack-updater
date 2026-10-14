package io.github.deshark42.modpackupdater.model;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.AtomicFile;
import io.github.deshark42.modpackupdater.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Connection settings, shared by publisher and subscriber and kept in .modpack-updater/config.json. */
public record Config(String endpoint, String region, String bucket, String prefix, boolean pathStyle,
                     String accessKey, String secretKey, int concurrency) {

    /** Transfers that run at the same time unless config.json says otherwise. */
    public static final int DEFAULT_CONCURRENCY = 4;
    public static final int MAX_CONCURRENCY = 64;

    public Config {
        if (endpoint == null || !(endpoint.startsWith("http://") || endpoint.startsWith("https://"))) {
            throw new UserError("\"endpoint\" must be an http(s) URL: " + endpoint);
        }
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        if (region == null || region.isBlank()) {
            throw new UserError("\"region\" must not be empty");
        }
        if (bucket == null || bucket.isBlank()) {
            throw new UserError("\"bucket\" must not be empty");
        }
        if (prefix == null) {
            prefix = "";
        }
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        if (concurrency < 1 || concurrency > MAX_CONCURRENCY) {
            throw new UserError("\"concurrency\" must be between 1 and " + MAX_CONCURRENCY + ": " + concurrency);
        }
    }

    public static Config load(Path workspaceRoot) {
        Path file = Layout.configFile(workspaceRoot);
        if (!Files.isRegularFile(file)) {
            throw new UserError("no " + file + ": a workspace needs a .modpack-updater/config.json");
        }
        return parse(AtomicFile.read(file), file.toString());
    }

    public static Config parse(String text, String what) {
        Map<String, Object> object = Json.asObject(Json.parse(text), what);
        String accessKey = Fields.optionalString(object, "access_key", null, what);
        String secretKey = Fields.optionalString(object, "secret_key", null, what);
        if ((accessKey == null) != (secretKey == null)) {
            throw new UserError(what + ": set both \"access_key\" and \"secret_key\", or neither for a public bucket");
        }
        return new Config(
                Fields.string(object, "endpoint", what),
                Fields.optionalString(object, "region", "us-east-1", what),
                Fields.string(object, "bucket", what),
                Fields.optionalString(object, "prefix", "", what),
                Fields.optionalBoolean(object, "path_style", false, what),
                accessKey,
                secretKey,
                Fields.optionalInt(object, "concurrency", DEFAULT_CONCURRENCY, what));
    }

    public boolean anonymous() {
        return accessKey == null;
    }
}
