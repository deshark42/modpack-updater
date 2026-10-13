package io.github.deshark42.modpackupdater.s3;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.AtomicFile;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.model.Config;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;

/** An S3 compatible object store, addressed with the credentials or the anonymity from the config. */
public final class S3Store {

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final Config config;
    private final HttpClient http;
    private final Clock clock;
    private final Duration timeout;

    public S3Store(Config config) {
        this(config, Clock.systemUTC(), Duration.ofMinutes(30));
    }

    S3Store(Config config, Clock clock, Duration timeout) {
        this.config = config;
        this.clock = clock;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** The object key of a workspace relative path, prefix included. */
    public String key(String relative) {
        return config.prefix() + relative;
    }

    /** The URL of a workspace relative object. */
    public URI uri(String relative) {
        return rawUri(key(relative));
    }

    private URI rawUri(String objectKey) {
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

    public byte[] getBytes(String relative) {
        String objectKey = key(relative);
        HttpRequest request = request("GET", objectKey, SigV4.EMPTY_PAYLOAD, HttpRequest.BodyPublishers.noBody());
        HttpResponse<byte[]> response = send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (!isSuccess(response)) {
            throw failure("read", objectKey, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        }
        return response.body();
    }

    /** Fetches an object into {@code target}, which is replaced only once the download is complete. */
    public void download(String relative, Path target) {
        String objectKey = key(relative);
        HttpRequest request = request("GET", objectKey, SigV4.EMPTY_PAYLOAD, HttpRequest.BodyPublishers.noBody());
        HttpResponse<InputStream> response = send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (!isSuccess(response)) {
            String body;
            try (InputStream in = response.body()) {
                body = new String(in.readNBytes(2048), StandardCharsets.UTF_8);
            } catch (IOException e) {
                body = "";
            }
            throw failure("read", objectKey, response.statusCode(), body);
        }
        try (InputStream in = response.body()) {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".part");
            try {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                AtomicFile.move(temp, target);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UserError("cannot write " + target + ": " + e.getMessage(), e);
        }
    }

    public void putFile(String relative, Path file) {
        String objectKey = key(relative);
        String payloadHash;
        try {
            payloadHash = Sha256.ofFile(file);
        } catch (IOException e) {
            throw new UserError("cannot read " + file + ": " + e.getMessage(), e);
        }
        HttpRequest request = request("PUT", objectKey, payloadHash, fileBody(file));
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (!isSuccess(response)) {
            throw failure("write", objectKey, response.statusCode(), response.body());
        }
    }

    public void putBytes(String relative, byte[] content) {
        String objectKey = key(relative);
        HttpRequest request = request("PUT", objectKey, Sha256.hex(digest(content)),
                HttpRequest.BodyPublishers.ofByteArray(content));
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (!isSuccess(response)) {
            throw failure("write", objectKey, response.statusCode(), response.body());
        }
    }

    private static HttpRequest.BodyPublisher fileBody(Path file) {
        try {
            return HttpRequest.BodyPublishers.ofFile(file);
        } catch (IOException e) {
            throw new UserError("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    private static boolean isSuccess(HttpResponse<?> response) {
        return response.statusCode() / 100 == 2;
    }

    private UserError failure(String operation, String objectKey, int status, String body) {
        String reason = switch (status) {
            case 403 -> "access denied";
            case 404 -> "not found";
            default -> "HTTP " + status;
        };
        String detail = body == null ? "" : body.strip();
        if (detail.length() > 200) {
            detail = detail.substring(0, 200);
        }
        return new UserError("cannot " + operation + " " + config.bucket() + "/" + objectKey + ": " + reason
                + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    private HttpRequest request(String method, String objectKey, String payloadHash, HttpRequest.BodyPublisher body) {
        URI uri = rawUri(objectKey);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout).method(method, body);
        if (config.anonymous()) {
            return builder.build();
        }
        Map<String, String> headers = new TreeMap<>();
        headers.put("host", authority(uri));
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", AMZ_DATE.format(clock.instant()));
        builder.header("x-amz-content-sha256", payloadHash);
        builder.header("x-amz-date", headers.get("x-amz-date"));
        builder.header("Authorization", SigV4.authorization(method, uri.getRawPath(), uri.getRawQuery() == null ? "" : uri.getRawQuery(),
                headers, payloadHash, config.accessKey(), config.secretKey(), config.region()));
        return builder.build();
    }

    private static String authority(URI uri) {
        return uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return http.send(request, handler);
        } catch (IOException e) {
            throw new UserError("cannot reach " + config.endpoint() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UserError("interrupted while talking to " + config.endpoint());
        }
    }

    private static byte[] digest(byte[] content) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(content);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String encodePath(String objectKey) {
        StringBuilder out = new StringBuilder(objectKey.length());
        for (String segment : objectKey.split("/", -1)) {
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(encode(segment));
        }
        return out.toString();
    }

    private static String encode(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xf, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }
}
