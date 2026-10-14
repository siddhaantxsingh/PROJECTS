package dev.siddhant.mcpfabric.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import dev.siddhant.mcpfabric.FabricProperties;
import dev.siddhant.mcpfabric.policy.Policy;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * MCP Streamable HTTP endpoint.
 * POST /mcp   one JSON-RPC message; requests get a JSON reply, notifications 202
 * GET  /mcp   server-sent events for this session (list_changed notifications)
 * DELETE /mcp end the session
 * Every call needs `Authorization: Bearer <client key>`; after initialize,
 * the `Mcp-Session-Id` it returned, which must belong to the same client.
 */
@RestController
public class McpController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Gateway gw;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Duration idle;

    public McpController(Gateway gw, FabricProperties props) {
        this.gw = gw;
        this.idle = props.sessionIdleTimeout();
    }

    private Optional<Policy.Client> auth(String header) {
        if (header == null || !header.startsWith("Bearer ")) return Optional.empty();
        return gw.policy().authenticate(header.substring(7).trim());
    }

    private static ResponseEntity<JsonNode> rpcError(HttpStatus st, JsonNode id, int code, String msg) {
        return ResponseEntity.status(st).contentType(MediaType.APPLICATION_JSON).body(Gateway.error(id == null ? NullNode.instance : id, code, msg, null));
    }

    @PostMapping(value = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> post(@RequestHeader(value = "Authorization", required = false) String authz,
                                         @RequestHeader(value = "Mcp-Session-Id", required = false) String sid,
                                         @RequestHeader(value = "MCP-Protocol-Version", required = false) String version,
                                         @RequestBody JsonNode msg) {
        var client = auth(authz);
        JsonNode id = msg.get("id");
        if (client.isEmpty()) return rpcError(HttpStatus.UNAUTHORIZED, id, Gateway.FORBIDDEN, "missing or invalid bearer token");
        if (!msg.isObject() || !"2.0".equals(msg.path("jsonrpc").asText()) || !msg.hasNonNull("method") && !msg.has("result") && !msg.has("error")) {
            return rpcError(HttpStatus.BAD_REQUEST, id, -32600, "invalid JSON-RPC message (batches are not supported)");
        }
        if (version != null && !Gateway.VERSIONS.contains(version)) {
            return rpcError(HttpStatus.BAD_REQUEST, id, -32600, "unsupported MCP-Protocol-Version " + version);
        }
        String method = msg.path("method").asText("");
        if (method.equals("initialize")) {
            if (id == null) return rpcError(HttpStatus.BAD_REQUEST, null, -32600, "initialize must be a request");
            String requested = msg.path("params").path("protocolVersion").asText("");
            var result = gw.initializeResult(requested);
            Session s = new Session(UUID.randomUUID().toString(), client.get(), result.path("protocolVersion").asText());
            sessions.put(s.id, s);
            gw.register(s);
            return ResponseEntity.ok().header("Mcp-Session-Id", s.id).contentType(MediaType.APPLICATION_JSON)
                    .body(JSON.createObjectNode().put("jsonrpc", "2.0").<com.fasterxml.jackson.databind.node.ObjectNode>set("id", id).set("result", result));
        }
        if (sid == null) return rpcError(HttpStatus.BAD_REQUEST, id, -32600, "missing Mcp-Session-Id");
        Session s = sessions.get(sid);
        if (s == null || !s.client.id().equals(client.get().id())) return rpcError(HttpStatus.NOT_FOUND, id, -32600, "unknown session");
        s.lastSeen = System.currentTimeMillis();
        if (method.equals("tools/call") && id != null && !gw.policy().admit(s.client)) {
            gw.auditRejected(s, msg.path("params").path("name").asText(""), "rate_limited");
            return rpcError(HttpStatus.TOO_MANY_REQUESTS, id, Gateway.RATE_LIMITED, "rate limit exceeded for client " + s.client.id());
        }
        JsonNode resp = gw.handle(s, msg);
        if (resp == null) return ResponseEntity.accepted().build();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(resp);
    }

    @GetMapping(value = "/mcp", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object stream(@RequestHeader(value = "Authorization", required = false) String authz,
                         @RequestHeader(value = "Mcp-Session-Id", required = false) String sid) {
        var client = auth(authz);
        if (client.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        Session s = sid == null ? null : sessions.get(sid);
        if (s == null || !s.client.id().equals(client.get().id())) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        SseEmitter e = new SseEmitter(0L);
        s.streams.add(e);
        e.onCompletion(() -> s.streams.remove(e));
        e.onTimeout(() -> s.streams.remove(e));
        e.onError(x -> s.streams.remove(e));
        try {
            e.send(SseEmitter.event().comment("open"));
        } catch (Exception ignored) {
            s.streams.remove(e);
        }
        return e;
    }

    @DeleteMapping("/mcp")
    public ResponseEntity<Void> delete(@RequestHeader(value = "Authorization", required = false) String authz,
                                       @RequestHeader(value = "Mcp-Session-Id", required = false) String sid) {
        var client = auth(authz);
        if (client.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        Session s = sid == null ? null : sessions.get(sid);
        if (s == null || !s.client.id().equals(client.get().id())) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        end(s);
        return ResponseEntity.noContent().build();
    }

    private void end(Session s) {
        sessions.remove(s.id);
        gw.unregister(s);
        s.streams.forEach(SseEmitter::complete);
    }

    @Scheduled(fixedDelay = 60_000)
    void expireIdle() {
        long cutoff = System.currentTimeMillis() - idle.toMillis();
        sessions.values().stream().filter(s -> s.lastSeen < cutoff && s.streams.isEmpty()).toList().forEach(this::end);
    }

    public int sessionCount() {
        return sessions.size();
    }
}
