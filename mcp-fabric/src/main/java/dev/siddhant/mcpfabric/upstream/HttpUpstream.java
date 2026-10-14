package dev.siddhant.mcpfabric.upstream;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * MCP Streamable HTTP transport (client side). Every message is a POST; the
 * reply is either one JSON message or a server-sent-event stream of them.
 * The session id from the initialize response is sent on later requests.
 * A GET stream, if the server offers one, carries server notifications.
 * Connection failures mark the upstream down and a supervisor retries the
 * handshake with backoff.
 */
public class HttpUpstream extends Upstream {
    private final URI url;
    private final Map<String, String> headers;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile String sessionId;
    private volatile boolean closed;

    public HttpUpstream(String name, URI url, Map<String, String> headers, Duration timeout, CircuitBreaker breaker) {
        super(name, timeout, breaker);
        this.url = url;
        this.headers = headers;
    }

    @Override
    public void start() {
        Thread.ofVirtual().name("http-" + name).start(() -> {
            long backoff = 1000;
            while (!closed) {
                if (state != State.READY) {
                    try {
                        sessionId = null;
                        handshake();
                        backoff = 1000;
                        Thread.ofVirtual().start(this::notificationStream);
                    } catch (Exception e) {
                        state = State.DOWN;
                        log.warn("{}: {}; retrying in {} ms", name, e.getMessage(), backoff);
                        backoff = Math.min(backoff * 2, 30_000);
                    }
                }
                try {
                    Thread.sleep(state == State.READY ? 1000 : backoff);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    private HttpRequest.Builder base() {
        var b = HttpRequest.newBuilder(url).timeout(timeout.plusSeconds(5));
        headers.forEach(b::header);
        if (sessionId != null) b.header("Mcp-Session-Id", sessionId);
        return b;
    }

    @Override
    protected void send(ObjectNode msg) throws Exception {
        boolean isInit = "initialize".equals(msg.path("method").asText());
        var req = base().header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(msg))).build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream()).whenComplete((resp, err) -> {
            if (err != null) {
                state = State.DOWN;
                connectionLost(err.toString());
                return;
            }
            try (var body = resp.body()) {
                if (isInit) resp.headers().firstValue("Mcp-Session-Id").ifPresent(s -> sessionId = s);
                if (resp.statusCode() == 404 && sessionId != null) {
                    // the server dropped our session: reconnect
                    state = State.DOWN;
                    connectionLost("session expired");
                    return;
                }
                if (resp.statusCode() == 202) return; // accepted notification
                String ct = resp.headers().firstValue("Content-Type").orElse("");
                if (ct.startsWith("text/event-stream")) {
                    readEvents(new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8)));
                } else if (resp.statusCode() / 100 == 2) {
                    receive(JSON.readTree(body));
                } else {
                    // an HTTP error carrying a JSON-RPC error body, or not
                    var text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                    var node = text.isBlank() ? null : JSON.readTree(text);
                    if (node != null && node.has("error") && node.has("id")) receive(node);
                    else connectionLost("HTTP " + resp.statusCode());
                }
            } catch (Exception e) {
                connectionLost(e.toString());
            }
        });
    }

    private void readEvents(BufferedReader r) throws Exception {
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            if (line.startsWith("data:")) {
                data.append(line.substring(5).trim());
            } else if (line.isEmpty() && !data.isEmpty()) {
                receive(JSON.readTree(data.toString()));
                data.setLength(0);
            }
        }
        if (!data.isEmpty()) receive(JSON.readTree(data.toString()));
    }

    private void notificationStream() {
        try {
            var req = base().header("Accept", "text/event-stream").timeout(Duration.ofDays(1)).GET().build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) return; // server offers no stream (405)
            readEvents(new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.debug("{}: notification stream ended: {}", name, e.toString());
        }
    }

    @Override
    public void close() {
        closed = true;
        state = State.DOWN;
        if (sessionId != null) {
            try {
                http.send(base().DELETE().build(), HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
                // best effort
            }
        }
    }
}
