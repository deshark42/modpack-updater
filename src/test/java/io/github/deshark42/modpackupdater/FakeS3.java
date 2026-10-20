package io.github.deshark42.modpackupdater;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A minimal object store for tests: keys are raw request paths, objects live in a map. */
public final class FakeS3 implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile String failPutPath;

    public FakeS3() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<String> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    public int objectCount() {
        return objects.size();
    }

    public byte[] object(String key) {
        return objects.get(key);
    }

    public String text(String key) {
        byte[] content = objects.get(key);
        return content == null ? null : new String(content, StandardCharsets.UTF_8);
    }

    /** Stores an object without going through the publisher, as if another writer had put it there. */
    public void put(String key, byte[] content) {
        objects.put(key, content.clone());
    }

    /** Makes the next PUT to {@code path} fail with a 500, until {@link #clearFailures()} is called. */
    public void failPut(String path) {
        failPutPath = path;
    }

    public void clearFailures() {
        failPutPath = null;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String method = exchange.getRequestMethod();
        requests.add(method + " " + path);
        byte[] content = objects.get(path);
        if ("PUT".equals(method)) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (path.equals(failPutPath)) {
                byte[] message = "InjectedFailure".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, message.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(message);
                }
            } else {
                objects.put(path, body);
                exchange.sendResponseHeaders(200, -1);
            }
        } else if (content != null) {
            if ("HEAD".equals(method)) {
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(200, content.length);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(content);
                }
            }
        } else if ("HEAD".equals(method)) {
            exchange.sendResponseHeaders(404, -1);
        } else {
            byte[] message = "NoSuchKey".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, message.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(message);
            }
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
