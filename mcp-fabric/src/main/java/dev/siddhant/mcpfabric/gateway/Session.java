package dev.siddhant.mcpfabric.gateway;

import dev.siddhant.mcpfabric.policy.Policy.Client;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** One client session (Mcp-Session-Id). */
public class Session {
    /** A tool call we forwarded and can still cancel. */
    public record Inflight(String upstream, long upstreamId) {
    }

    public final String id;
    public final Client client;
    public final String protocolVersion;
    public volatile boolean initialized;
    public volatile long lastSeen = System.currentTimeMillis();
    public final List<SseEmitter> streams = new CopyOnWriteArrayList<>();
    /** client request id (as JSON text) -> forwarded call */
    public final Map<String, Inflight> inflight = new ConcurrentHashMap<>();

    public Session(String id, Client client, String protocolVersion) {
        this.id = id;
        this.client = client;
        this.protocolVersion = protocolVersion;
    }
}
