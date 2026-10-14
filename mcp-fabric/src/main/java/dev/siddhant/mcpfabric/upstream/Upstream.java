package dev.siddhant.mcpfabric.upstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One upstream MCP server. Transport-independent part: request/response
 * correlation, the initialize handshake, catalog caching (tools, resources,
 * prompts), refresh on list_changed notifications, timeouts with
 * cancellation, and a circuit breaker.
 */
public abstract class Upstream {
    public enum State { CONNECTING, READY, OPEN_CIRCUIT, DOWN }

    protected static final ObjectMapper JSON = new ObjectMapper();
    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final String name;
    protected final Duration timeout;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final List<Consumer<Upstream>> catalogListeners = new CopyOnWriteArrayList<>();
    private final CircuitBreaker breaker;

    protected volatile State state = State.CONNECTING;
    private volatile List<JsonNode> tools = List.of();
    private volatile List<JsonNode> resources = List.of();
    private volatile List<JsonNode> prompts = List.of();
    private volatile JsonNode serverInfo;

    protected Upstream(String name, Duration timeout, CircuitBreaker breaker) {
        this.name = name;
        this.timeout = timeout;
        this.breaker = breaker;
    }

    public String name() { return name; }
    public Duration timeout() { return timeout; }
    public State state() {
        if (state == State.READY && !breaker.allows()) return State.OPEN_CIRCUIT;
        return state;
    }
    public List<JsonNode> tools() { return tools; }
    public List<JsonNode> resources() { return resources; }
    public List<JsonNode> prompts() { return prompts; }
    public JsonNode serverInfo() { return serverInfo; }
    public CircuitBreaker breaker() { return breaker; }
    public void onCatalogChange(Consumer<Upstream> l) { catalogListeners.add(l); }

    /** Send one JSON-RPC message (a request or a notification). */
    protected abstract void send(ObjectNode msg) throws Exception;

    /** Start the transport; must end by calling {@link #handshake()}. */
    public abstract void start();

    public abstract void close();

    /** Called by the transport for every message received. */
    protected void receive(JsonNode msg) {
        if (msg.has("id") && (msg.has("result") || msg.has("error"))) {
            var f = pending.remove(msg.get("id").asLong());
            if (f != null) f.complete(msg);
            return;
        }
        String method = msg.path("method").asText("");
        switch (method) {
            case "notifications/tools/list_changed", "notifications/resources/list_changed", "notifications/prompts/list_changed" ->
                Thread.ofVirtual().start(this::refreshQuietly);
            default -> { } // other notifications (progress, logging) are not forwarded
        }
    }

    /** The transport lost its connection: fail every outstanding request. */
    protected void connectionLost(String why) {
        var it = pending.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            it.remove();
            e.getValue().completeExceptionally(new UpstreamException(name + ": " + why));
        }
    }

    /**
     * Send a request and wait for its result. Throws UpstreamException for
     * transport failures, timeouts and open circuits; a JSON-RPC error
     * response is returned as a {@link RpcError}.
     */
    public JsonNode request(String method, JsonNode params) throws UpstreamException, RpcError {
        return request(method, params, timeout);
    }

    public JsonNode request(String method, JsonNode params, Duration t) throws UpstreamException, RpcError {
        return request(method, params, t, id -> { });
    }

    /** As above; `onId` learns the upstream request id (to forward a cancellation). */
    public JsonNode request(String method, JsonNode params, Duration t, java.util.function.LongConsumer onId) throws UpstreamException, RpcError {
        if (state != State.READY && !method.equals("initialize")) throw new UpstreamException(name + ": not ready (" + state + ")");
        if (!breaker.allows()) throw new UpstreamException(name + ": circuit open");
        long id = nextId.getAndIncrement();
        onId.accept(id);
        ObjectNode req = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
        if (params != null) req.set("params", params);
        var f = new CompletableFuture<JsonNode>();
        pending.put(id, f);
        try {
            send(req);
            JsonNode resp = f.get(t.toMillis(), TimeUnit.MILLISECONDS);
            breaker.success();
            if (resp.has("error")) throw new RpcError(resp.get("error"));
            return resp.get("result");
        } catch (TimeoutException e) {
            pending.remove(id);
            breaker.failure();
            // tell the server to stop working on it
            notifyQuietly("notifications/cancelled", JSON.createObjectNode().put("requestId", id).put("reason", "gateway timeout"));
            throw new UpstreamException(name + ": " + method + " timed out after " + t.toMillis() + " ms");
        } catch (RpcError e) {
            throw e;
        } catch (Exception e) {
            pending.remove(id);
            breaker.failure();
            Throwable c = e.getCause() != null ? e.getCause() : e;
            throw new UpstreamException(c.getMessage() == null ? name + ": " + c : c.getMessage());
        }
    }

    /** Cancel an in-flight request we forwarded (client sent notifications/cancelled). */
    public void notifyQuietly(String method, JsonNode params) {
        try {
            ObjectNode n = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", method);
            if (params != null) n.set("params", params);
            send(n);
        } catch (Exception e) {
            log.debug("{}: notify {} failed: {}", name, method, e.toString());
        }
    }

    /** Run the MCP initialize handshake and load the catalog. */
    protected void handshake() throws UpstreamException, RpcError {
        state = State.CONNECTING;
        ObjectNode p = JSON.createObjectNode().put("protocolVersion", "2025-06-18");
        p.putObject("capabilities");
        p.putObject("clientInfo").put("name", "mcp-fabric").put("version", "0.1.0");
        JsonNode r = request("initialize", p);
        serverInfo = r;
        notifyQuietly("notifications/initialized", null);
        state = State.READY;
        refresh();
        log.info("{}: ready ({} tools, {} resources, {} prompts)", name, tools.size(), resources.size(), prompts.size());
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (Exception e) {
            log.warn("{}: catalog refresh failed: {}", name, e.toString());
        }
    }

    /** Reload tools/resources/prompts (following pagination cursors). */
    public void refresh() throws UpstreamException, RpcError {
        JsonNode caps = serverInfo == null ? JSON.createObjectNode() : serverInfo.path("capabilities");
        tools = caps.has("tools") ? listAll("tools/list", "tools") : List.of();
        resources = caps.has("resources") ? listAll("resources/list", "resources") : List.of();
        prompts = caps.has("prompts") ? listAll("prompts/list", "prompts") : List.of();
        catalogListeners.forEach(l -> l.accept(this));
    }

    private List<JsonNode> listAll(String method, String field) throws UpstreamException, RpcError {
        var out = new java.util.ArrayList<JsonNode>();
        String cursor = null;
        for (int page = 0; page < 1000; page++) {
            ObjectNode p = JSON.createObjectNode();
            if (cursor != null) p.put("cursor", cursor);
            JsonNode r = request(method, p);
            if (r.path(field) instanceof ArrayNode a) a.forEach(out::add);
            cursor = r.hasNonNull("nextCursor") ? r.get("nextCursor").asText() : null;
            if (cursor == null) break;
        }
        return List.copyOf(out);
    }

    public static class UpstreamException extends Exception {
        public UpstreamException(String m) { super(m); }
    }

    /** A JSON-RPC error returned by the upstream server. */
    public static class RpcError extends Exception {
        public final JsonNode error;
        public RpcError(JsonNode error) {
            super(error.path("message").asText());
            this.error = error;
        }
    }
}
