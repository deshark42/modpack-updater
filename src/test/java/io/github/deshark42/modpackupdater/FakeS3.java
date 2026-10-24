package io.github.deshark42.modpackupdater;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.deshark42.modpackupdater.s3.SigV4Verifier;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An in-process S3 endpoint reached over real HTTP: the one storage double the suite uses.
 *
 * <p>It serves GET, HEAD and PUT, and it checks the SigV4 signature of every request it does not
 * fail on purpose: the signature must recompute from the request's own bytes, path, query and
 * signed headers, and x-amz-content-sha256 must match the body, so a wrong signature or a corrupted
 * upload cannot quietly pass. Errors come back as S3's XML bodies, and an unsupported method fails
 * loudly instead of looking like a missing object. A bucket can be opened to anonymous access, in
 * which case nothing is checked.
 *
 * <p>The store records every request with the headers it carried, counts the bytes it moved and the
 * requests it had in flight at once, and can be told to fail selected requests, persistently or
 * once, so a client's failure handling is exercised against a real transport.
 */
public final class FakeS3 implements AutoCloseable {

    private static final int THREADS = 4;

    private final HttpServer server;
    private final ExecutorService executor;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong uploaded = new AtomicLong();
    private final AtomicLong downloaded = new AtomicLong();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peakInFlight = new AtomicInteger();
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private final Map<String, Integer> nextFailures = new ConcurrentHashMap<>();
    private volatile boolean anonymous;

    /** One request the store received: its method, raw path and headers, names lower case. */
    public record Request(String method, String path, Map<String, String> headers) {

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    public FakeS3() throws IOException {
        server = bind();
        server.createContext("/", this::handle);
        executor = Executors.newFixedThreadPool(THREADS, runnable -> {
            Thread thread = new Thread(runnable, "fake-s3");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.start();
    }

    /**
     * Binds the loopback port the OS picks. A port the OS picks can still be one a connection of an
     * earlier test left in {@code TIME_WAIT}, which is not bindable without {@code SO_REUSEADDR}, so a
     * refused bind is retried; nothing else about the server depends on the port number.
     */
    private static HttpServer bind() throws IOException {
        BindException last = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (BindException e) {
                last = e;
            }
        }
        throw last;
    }

    /** The endpoint a workspace points at, e.g. "http://127.0.0.1:34567". */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * Whether requests without credentials are served: a public bucket. When off, a request without
     * an Authorization header is refused. When on, the signature and payload checks are skipped,
     * since there is no identity to check a request against.
     */
    public void allowAnonymous(boolean allow) {
        anonymous = allow;
    }

    /** Every request received, as "METHOD path". */
    public List<String> requests() {
        List<String> out = new ArrayList<>();
        synchronized (requests) {
            for (Request request : requests) {
                out.add(request.method() + " " + request.path());
            }
        }
        return out;
    }

    /** Every request received with its headers, so a test can assert what the client sent. */
    public List<Request> requestsDetailed() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    /** The most requests the store had in flight at once, for proving a run really overlapped them. */
    public int maxInFlight() {
        return peakInFlight.get();
    }

    public int objectCount() {
        return objects.size();
    }

    public long uploadedBytes() {
        return uploaded.get();
    }

    public long downloadedBytes() {
        return downloaded.get();
    }

    /** The bytes stored under {@code path}, or null when the path holds no object. */
    public byte[] object(String path) {
        byte[] content = objects.get(path);
        return content == null ? null : content.clone();
    }

    /** The text stored under {@code path}, or null when the path holds no object. */
    public String text(String path) {
        byte[] content = object(path);
        return content == null ? null : new String(content, StandardCharsets.UTF_8);
    }

    /** Seeds an object without a request, the way another writer would have left it. */
    public void put(String path, byte[] content) {
        objects.put(path, content.clone());
    }

    /** Fails every PUT to {@code path} with a 500 until {@link #clearFailures()} is called. */
    public void failPut(String path) {
        fail("PUT", path, 500);
    }

    /** Fails every request with this method and path with {@code status} until cleared. */
    public void fail(String method, String path, int status) {
        failures.put(method + " " + path, status);
    }

    /** Fails only the next request with this method and path with {@code status}. */
    public void failNext(String method, String path, int status) {
        nextFailures.put(method + " " + path, status);
    }

    /** Stops failing injected requests. */
    public void clearFailures() {
        failures.clear();
        nextFailures.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        int now = inFlight.incrementAndGet();
        peakInFlight.accumulateAndGet(now, Math::max);
        try {
            serve(exchange);
        } finally {
            inFlight.decrementAndGet();
            exchange.close();
        }
    }

    private void serve(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery();
        Map<String, String> headers = headers(exchange);
        byte[] body = exchange.getRequestBody().readAllBytes();
        requests.add(new Request(method, path, headers));
        String key = method + " " + path;
        int injected = injectedStatus(key);
        if (injected != 0) {
            sendError(exchange, injected, errorCodeFor(injected), "injected " + injected + " for " + key);
            return;
        }
        if (!"GET".equals(method) && !"HEAD".equals(method) && !"PUT".equals(method)) {
            sendError(exchange, 400, "UnknownMethod", "the store does not serve " + method);
            return;
        }
        if (!anonymous) {
            SigV4Verifier.Verdict verdict = SigV4Verifier.verify(method, path, query, headers, body,
                    TestWorkspace.ACCESS_KEY, TestWorkspace.SECRET_KEY, TestWorkspace.REGION);
            if (!verdict.isValid()) {
                sendError(exchange, 403, verdict.errorCode(), verdict.message());
                return;
            }
        }
        if ("PUT".equals(method)) {
            objects.put(path, body);
            uploaded.addAndGet(body.length);
            sendStatus(exchange, 200);
        } else if ("HEAD".equals(method)) {
            sendStatus(exchange, objects.containsKey(path) ? 200 : 404);
        } else {
            byte[] content = objects.get(path);
            if (content == null) {
                sendError(exchange, 404, "NoSuchKey", "the bucket has no object " + path);
            } else {
                downloaded.addAndGet(content.length);
                exchange.sendResponseHeaders(200, content.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(content);
                }
            }
        }
    }

    private int injectedStatus(String key) {
        Integer once = nextFailures.remove(key);
        if (once != null) {
            return once;
        }
        Integer persistent = failures.get(key);
        return persistent == null ? 0 : persistent;
    }

    private static String errorCodeFor(int status) {
        if (status == 403) {
            return "AccessDenied";
        }
        if (status == 404) {
            return "NoSuchKey";
        }
        return status >= 500 ? "InternalError" : "InjectedFailure";
    }

    private static Map<String, String> headers(HttpExchange exchange) {
        Map<String, String> out = new TreeMap<>();
        exchange.getRequestHeaders().forEach((name, values) ->
                out.put(name.toLowerCase(Locale.ROOT), values.isEmpty() ? "" : values.get(0)));
        return Collections.unmodifiableMap(out);
    }

    private static void sendError(HttpExchange exchange, int status, String code, String message)
            throws IOException {
        if ("HEAD".equals(exchange.getRequestMethod())) {
            sendStatus(exchange, status);
            return;
        }
        byte[] body = ("<Error><Code>" + escape(code) + "</Code><Message>" + escape(message)
                + "</Message></Error>").getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void sendStatus(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
