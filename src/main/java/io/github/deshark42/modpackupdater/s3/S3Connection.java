package io.github.deshark42.modpackupdater.s3;

import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.AtomicFile;
import io.github.deshark42.modpackupdater.model.Config;
import java.io.ByteArrayInputStream;
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
import java.time.Duration;
import java.util.zip.GZIPInputStream;

/**
 * What the two stores share: one HTTP client per connection, the same timeout, redirect policy and
 * error mapping, and the read path both of them perform. How a key becomes a URL, which headers a
 * GET carries and how a failure is named are what the write side and the read side decide apart.
 */
abstract class S3Connection implements ObjectReader {

    final Config config;
    private final HttpClient http;
    private final Duration timeout;

    S3Connection(Config config, Duration timeout) {
        this.config = config;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public final String key(String relative) {
        return config.prefix() + relative;
    }

    /** The URL of a workspace relative object. */
    public final URI uri(String relative) {
        return rawUri(key(relative));
    }

    /** The URL of an object key, prefix included: the bucket addressing on one side, the CDN's on the other. */
    abstract URI rawUri(String objectKey);

    /** How a failure names the object: the bucket path, or the read URL that was actually requested. */
    abstract String display(String objectKey);

    /** Where a request went when no response came back: the endpoint of the connection. */
    abstract String reachTarget();

    /** The GET of one object, with the headers and credentials this side sends. */
    abstract HttpRequest read(String objectKey);

    /** Runs on a successful download, before the bytes reach the workspace. */
    void checkDownload(String objectKey, HttpResponse<?> response) {
    }

    protected final HttpRequest.Builder builder(String method, String objectKey, URI uri,
            HttpRequest.BodyPublisher body) {
        try {
            return HttpRequest.newBuilder(uri).timeout(timeout).method(method, body);
        } catch (IllegalArgumentException e) {
            throw new UserError("cannot address " + objectKey + " on " + reachTarget() + ": " + e.getMessage());
        }
    }

    @Override
    public final byte[] getBytes(String relative) {
        String objectKey = key(relative);
        HttpResponse<byte[]> response = send(read(objectKey), HttpResponse.BodyHandlers.ofByteArray());
        if (!isSuccess(response)) {
            throw failure("read", objectKey, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        }
        byte[] body = response.body();
        if (!isGzip(response)) {
            return body;
        }
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UserError("cannot read " + display(objectKey) + ": " + e.getMessage(), e);
        }
    }

    @Override
    public final void download(String relative, Path target) {
        String objectKey = key(relative);
        HttpResponse<InputStream> response = send(read(objectKey), HttpResponse.BodyHandlers.ofInputStream());
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
            checkDownload(objectKey, response);
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

    protected final <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return http.send(request, handler);
        } catch (IOException e) {
            throw new UserError("cannot reach " + reachTarget() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UserError("interrupted while talking to " + reachTarget());
        }
    }

    protected final UserError failure(String operation, String objectKey, int status, String body) {
        String reason = switch (status) {
            case 403 -> "access denied";
            case 404 -> "not found";
            default -> "HTTP " + status;
        };
        String detail = body == null ? "" : body.strip();
        if (detail.length() > 200) {
            detail = detail.substring(0, 200);
        }
        return new UserError("cannot " + operation + " " + display(objectKey) + ": " + reason
                + (detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    protected static boolean isSuccess(HttpResponse<?> response) {
        return response.statusCode() / 100 == 2;
    }

    protected static boolean isGzip(HttpResponse<?> response) {
        return response.headers().firstValue("content-encoding")
                .map(value -> value.trim().equalsIgnoreCase(S3Store.GZIP))
                .orElse(false);
    }

    protected static String encodePath(String objectKey) {
        StringBuilder out = new StringBuilder(objectKey.length());
        for (String segment : objectKey.split("/", -1)) {
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(encode(segment));
        }
        return out.toString();
    }

    protected static String encode(String value) {
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
