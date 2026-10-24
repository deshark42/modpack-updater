package io.github.deshark42.modpackupdater.s3;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.model.Config;
import io.github.deshark42.modpackupdater.model.ObjectKeys;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

/** An S3 compatible object store, addressed with the credentials or the anonymity from the config. */
public final class S3Store extends S3Connection {

    /** The one content encoding this tool writes: a metadata object too large to leave plain. */
    static final String GZIP = "gzip";

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final Clock clock;

    public S3Store(Config config) {
        this(config, Clock.systemUTC(), Duration.ofMinutes(30));
    }

    S3Store(Config config, Clock clock, Duration timeout) {
        super(config, timeout);
        this.clock = clock;
    }

    @Override
    URI rawUri(String objectKey) {
        URI endpoint = URI.create(config.endpoint());
        int port = endpoint.getPort();
        String portPart = port == -1 ? "" : ":" + port;
        String authority;
        String path;
        if (config.pathStyle()) {
            authority = endpoint.getHost() + portPart;
            path = "/" + encode(config.bucket()) + "/" + encodePath(objectKey);
        } else {
            authority = encode(config.bucket()) + "." + endpoint.getHost() + portPart;
            path = "/" + encodePath(objectKey);
        }
        try {
            return URI.create(endpoint.getScheme() + "://" + authority + path);
        } catch (IllegalArgumentException e) {
            throw new UserError("cannot address " + objectKey + " on " + config.endpoint() + ": " + e.getMessage());
        }
    }

    @Override
    String display(String objectKey) {
        return config.bucket() + "/" + objectKey;
    }

    @Override
    String reachTarget() {
        return config.endpoint();
    }

    @Override
    HttpRequest read(String objectKey) {
        return request("GET", objectKey, SigV4.EMPTY_PAYLOAD, HttpRequest.BodyPublishers.noBody());
    }

    /** Whether an object is there, asked with a HEAD request so no content moves. */
    public boolean exists(String relative) {
        String objectKey = key(relative);
        HttpRequest request = request("HEAD", objectKey, SigV4.EMPTY_PAYLOAD, HttpRequest.BodyPublishers.noBody());
        HttpResponse<Void> response = send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() == 404) {
            return false;
        }
        if (!isSuccess(response)) {
            throw failure("read", objectKey, response.statusCode(), "");
        }
        return true;
    }

    public void putBytes(String relative, byte[] content) {
        put(relative, content, Map.of());
    }

    /**
     * Stores an object gzipped, telling readers so with the content encoding header, and answers the stored size.
     */
    public int putGzip(String relative, byte[] content) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(content);
        } catch (IOException e) {
            throw new UserError("cannot compress " + key(relative) + ": " + e.getMessage(), e);
        }
        byte[] compressed = buffer.toByteArray();
        put(relative, compressed, Map.of("content-encoding", GZIP));
        return compressed.length;
    }

    private void put(String relative, byte[] content, Map<String, String> extra) {
        String objectKey = key(relative);
        Map<String, String> headers = new TreeMap<>(extra);
        String cacheControl = ObjectKeys.cacheControl(relative);
        if (cacheControl != null) {
            headers.put("cache-control", cacheControl);
        }
        HttpRequest request = request("PUT", objectKey, Sha256.hex(digest(content)),
                HttpRequest.BodyPublishers.ofByteArray(content), headers);
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (!isSuccess(response)) {
            throw failure("write", objectKey, response.statusCode(), response.body());
        }
    }

    private HttpRequest request(String method, String objectKey, String payloadHash, HttpRequest.BodyPublisher body) {
        return request(method, objectKey, payloadHash, body, Map.of());
    }

    private HttpRequest request(String method, String objectKey, String payloadHash, HttpRequest.BodyPublisher body,
            Map<String, String> extra) {
        URI uri = rawUri(objectKey);
        HttpRequest.Builder builder = builder(method, objectKey, uri, body);
        for (Map.Entry<String, String> header : extra.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        if (config.anonymous()) {
            return builder.build();
        }
        Map<String, String> headers = new TreeMap<>(extra);
        headers.put("host", authority(uri));
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", AMZ_DATE.format(clock.instant()));
        builder.header("x-amz-content-sha256", payloadHash);
        builder.header("x-amz-date", headers.get("x-amz-date"));
        builder.header("Authorization", SigV4.authorization(method, uri.getRawPath(),
                uri.getRawQuery() == null ? "" : uri.getRawQuery(), headers, payloadHash,
                config.accessKey(), config.secretKey(), config.region()));
        return builder.build();
    }

    private static String authority(URI uri) {
        return uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
    }

    private static byte[] digest(byte[] content) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(content);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
