package io.github.deshark42.modpackupdater.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.deshark42.modpackupdater.UserError;
import io.github.deshark42.modpackupdater.fs.Sha256;
import io.github.deshark42.modpackupdater.model.Config;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class S3StoreTest {

    private HttpServer server;
    private final Map<String, byte[]> objects = new HashMap<>();
    private final Deque<String> requests = new ArrayDeque<>();
    private final Map<String, String> lastHeaders = new HashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        requests.add(exchange.getRequestMethod() + " " + path);
        lastHeaders.clear();
        exchange.getRequestHeaders().forEach((name, values) -> lastHeaders.put(name.toLowerCase(), values.get(0)));
        if ("PUT".equals(exchange.getRequestMethod())) {
            objects.put(path, exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(200, -1);
        } else {
            byte[] content = objects.get(path);
            if (content == null) {
                byte[] message = "NoSuchKey".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, message.length);
                exchange.getResponseBody().write(message);
            } else {
                exchange.sendResponseHeaders(200, content.length);
                exchange.getResponseBody().write(content);
            }
        }
        exchange.close();
    }

    private Config config(boolean pathStyle, boolean anonymous) {
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        String credentials = anonymous ? "" : ", \"access_key\": \"AK\", \"secret_key\": \"SK\"";
        return Config.parse("{\"endpoint\": \"" + endpoint + "\", \"bucket\": \"pack\", \"prefix\": \"gtnh/\","
                + " \"path_style\": " + pathStyle + credentials + "}", "config.json");
    }

    @Test
    void uploadsHashedBytesAndReadsThemBack(@TempDir Path dir) throws IOException {
        byte[] content = "content".getBytes(StandardCharsets.UTF_8);
        S3Store store = new S3Store(config(true, false));

        String relative = "blob/" + Sha256.ofBytes(content).substring(0, 2) + "/" + Sha256.ofBytes(content);
        store.putBytes(relative, content);

        assertEquals("PUT /pack/gtnh/" + relative, requests.poll());
        assertEquals(Sha256.ofBytes(content), lastHeaders.get("x-amz-content-sha256"));
        assertTrue(lastHeaders.get("authorization").startsWith("AWS4-HMAC-SHA256 Credential=AK/"));
        assertTrue(lastHeaders.get("authorization").contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"));
        assertArrayEquals("content".getBytes(StandardCharsets.UTF_8), objects.get("/pack/gtnh/" + relative));

        assertArrayEquals("content".getBytes(StandardCharsets.UTF_8), store.getBytes(relative));
        assertEquals("GET /pack/gtnh/" + relative, requests.poll());
    }

    @Test
    void anonymousStoreSendsNoCredentials(@TempDir Path dir) throws IOException {
        S3Store store = new S3Store(config(true, true));
        objects.put("/pack/gtnh/metadata/client/latest.json", "{}".getBytes(StandardCharsets.UTF_8));

        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), store.getBytes("metadata/client/latest.json"));
        assertNull(lastHeaders.get("authorization"));
        assertNull(lastHeaders.get("x-amz-date"));
    }

    @Test
    void downloadsToTheTargetPath(@TempDir Path dir) throws IOException {
        S3Store store = new S3Store(config(true, true));
        objects.put("/pack/gtnh/blobs/aa/abc", "bytes".getBytes(StandardCharsets.UTF_8));
        Path target = dir.resolve("mods/deep/a.jar");

        store.download("blobs/aa/abc", target);

        assertEquals("bytes", Files.readString(target));
        try (var entries = Files.list(dir.resolve("mods/deep"))) {
            assertEquals(java.util.List.of(target), entries.toList());
        }
    }

    @Test
    void missingObjectsAreReportedWithTheirKey() {
        S3Store store = new S3Store(config(true, true));
        UserError error = assertThrows(UserError.class, () -> store.getBytes("metadata/client/latest.json"));
        assertTrue(error.getMessage().contains("not found"), error.getMessage());
        assertTrue(error.getMessage().contains("metadata/client/latest.json"), error.getMessage());
        assertTrue(error.getMessage().contains("pack"), error.getMessage());
    }

    @Test
    void urlAvoidsADoubleSlashWhenThereIsNoPrefix() {
        Config plain = Config.parse("{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\","
                + " \"path_style\": true}", "config.json");
        assertEquals(URI.create("https://s3.example.com/pack/blobs/ab/abc"), new S3Store(plain).uri("blobs/ab/abc"));

        Config prefixed = Config.parse("{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\","
                + " \"prefix\": \"gtnh\", \"path_style\": true}", "config.json");
        assertEquals(URI.create("https://s3.example.com/pack/gtnh/blobs/ab/abc"),
                new S3Store(prefixed).uri("blobs/ab/abc"));

        Config virtualHost = Config.parse("{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\"}", "config.json");
        assertEquals(URI.create("https://pack.s3.example.com/blobs/ab/abc"), new S3Store(virtualHost).uri("blobs/ab/abc"));
    }

    @Test
    void objectKeysAreEscapedButSlashesSurvive() {
        Config plain = Config.parse("{\"endpoint\": \"https://s3.example.com\", \"bucket\": \"pack\","
                + " \"path_style\": true}", "config.json");
        assertEquals(URI.create("https://s3.example.com/pack/metadata/a%20b/latest.json"),
                new S3Store(plain).uri("metadata/a b/latest.json"));
    }
}
