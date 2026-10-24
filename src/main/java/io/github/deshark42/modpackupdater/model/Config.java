package io.github.deshark42.modpackupdater.model;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.AtomicFile;
import io.github.deshark42.modpackupdater.json.Json;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Connection settings, shared by publisher and subscriber and kept in .modpack-updater/config.json. */
public record Config(String endpoint, String region, String bucket, String prefix, boolean pathStyle,
                     String accessKey, String secretKey, int concurrency) {

    /** Transfers that run at the same time unless config.json says otherwise. */
    public static final int DEFAULT_CONCURRENCY = 8;
    public static final int MAX_CONCURRENCY = 64;

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    /** The fields a config may leave out, and the values the tool uses and writes back for them. */
    private static final Map<String, Object> DEFAULTS = defaults();

    private static Map<String, Object> defaults() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("region", "us-east-1");
        out.put("prefix", "");
        out.put("path_style", false);
        out.put("concurrency", DEFAULT_CONCURRENCY);
        return out;
    }

    public Config {
        if (endpoint == null || !(endpoint.startsWith("http://") || endpoint.startsWith("https://"))) {
            throw new UserError("\"endpoint\" must be an http(s) URL: " + endpoint);
        }
        String given = endpoint;
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw new UserError("\"endpoint\" must be an http(s) URL with a host: " + given);
        }
        if (uri.getHost() == null || !uri.getRawPath().isEmpty() || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new UserError("\"endpoint\" must be an http(s) URL with a host and no path, query or"
                    + " credentials: " + given);
        }
        if (!pathStyle && isIpLiteral(uri.getHost())) {
            throw new UserError("\"path_style\" must be true: " + given
                    + " is an IP address, so the bucket cannot be part of the host name");
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

    /**
     * Writes back the fields the file does not carry yet, so a config written before a field existed
     * gains its default without being asked. Every value and unknown key already in the file is kept.
     *
     * @return whether the file was written
     */
    public static boolean fillMissing(Path workspaceRoot) {
        Path file = Layout.configFile(workspaceRoot);
        Map<String, Object> object = Json.asObject(Json.parse(AtomicFile.read(file)), file.toString());
        boolean wrote = false;
        for (Map.Entry<String, Object> entry : DEFAULTS.entrySet()) {
            if (!object.containsKey(entry.getKey())) {
                object.put(entry.getKey(), entry.getValue());
                wrote = true;
            }
        }
        if (wrote) {
            AtomicFile.write(file, Json.writePretty(object));
        }
        return wrote;
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

    private static boolean isIpLiteral(String host) {
        return host.startsWith("[") || IPV4.matcher(host).matches();
    }
}
