package com.enterprise.oms.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/** Minimal programmable upstream built on the JDK HttpServer (records every request, configurable reply and delay). */
public final class StubServer implements AutoCloseable {

    public record Recorded(String method, String rawPath, String query, Map<String, List<String>> headers, byte[] body) {
        public String header(String name) {
            List<String> v = headers.get(name.toLowerCase(Locale.ROOT));
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        public List<String> headerValues(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    public static final class Reply {
        int status = 200;
        String body = "{\"ok\":true}";
        String contentType = "application/json";
        long delayMs = 0;
        final Map<String, String> headers = new LinkedHashMap<>();

        public Reply status(int s) { this.status = s; return this; }
        public Reply body(String b) { this.body = b; return this; }
        public Reply delay(long ms) { this.delayMs = ms; return this; }
        public Reply header(String k, String v) { headers.put(k, v); return this; }
    }

    private final HttpServer server;
    private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile Reply reply = new Reply();
    private volatile Reply readinessReply = new Reply().body("{\"status\":\"UP\"}");

    public StubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    private void handle(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        String path = ex.getRequestURI().getRawPath();
        Map<String, List<String>> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), List.copyOf(v)));
        boolean readiness = path.equals("/actuator/health/readiness");
        if (!readiness) {
            requests.add(new Recorded(ex.getRequestMethod(), path, ex.getRequestURI().getRawQuery(), headers, body));
        }
        Reply r = readiness ? readinessReply : reply;
        if (r.delayMs > 0) {
            try {
                Thread.sleep(r.delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] out = r.body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", r.contentType);
        r.headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        try {
            ex.sendResponseHeaders(r.status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(out);
                }
            }
        } catch (IOException ignored) {
            // client gave up (timeout tests)
        } finally {
            ex.close();
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String url() {
        return "http://127.0.0.1:" + port();
    }

    public Reply reply() {
        Reply r = new Reply();
        this.reply = r;
        return r;
    }

    public Reply readiness() {
        Reply r = new Reply().body("{\"status\":\"UP\"}");
        this.readinessReply = r;
        return r;
    }

    public List<Recorded> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    public Recorded last() {
        List<Recorded> all = requests();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    public void reset() {
        requests.clear();
        reply = new Reply();
        readinessReply = new Reply().body("{\"status\":\"UP\"}");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
