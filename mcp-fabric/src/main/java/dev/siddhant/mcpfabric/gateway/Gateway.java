package dev.siddhant.mcpfabric.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.siddhant.mcpfabric.FabricProperties.UpstreamConfig;
import dev.siddhant.mcpfabric.approval.Approvals;
import dev.siddhant.mcpfabric.audit.AuditLog;
import dev.siddhant.mcpfabric.guard.PathGuard;
import dev.siddhant.mcpfabric.guard.ToolGuard;
import dev.siddhant.mcpfabric.policy.Policy;
import dev.siddhant.mcpfabric.upstream.Upstream;
import dev.siddhant.mcpfabric.upstream.Upstream.RpcError;
import dev.siddhant.mcpfabric.upstream.Upstream.UpstreamException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The federation layer. Tools and prompts of upstream `u` are exposed as
 * `u__name`; resources keep their URIs and are routed by URI. Every tool
 * call is authorized, rate limited, validated against the tool's input
 * schema, forwarded with a timeout (a client cancellation is forwarded
 * upstream) and audited.
 */
public class Gateway {
    public static final String SEP = "__";
    public static final List<String> VERSIONS = List.of("2025-06-18", "2025-03-26");
    // JSON-RPC error codes used by the gateway
    public static final int INVALID_PARAMS = -32602, METHOD_NOT_FOUND = -32601, INTERNAL = -32603;
    public static final int FORBIDDEN = -32001, UNAVAILABLE = -32002, APPROVAL_TIMEOUT = -32003, RATE_LIMITED = -32029;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(Gateway.class);
    private final Map<String, Upstream> upstreams = new LinkedHashMap<>();
    private final Policy policy;
    private final AuditLog audit;
    private final MeterRegistry metrics;
    private final int pageSize;
    private final Map<String, JsonSchema> schemas = new ConcurrentHashMap<>();
    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final Approvals approvals;
    private final ToolGuard guard;
    private final Map<String, UpstreamConfig> configs;

    public Gateway(List<Upstream> ups, Policy policy, AuditLog audit, MeterRegistry metrics, int pageSize) {
        this(ups, policy, audit, metrics, pageSize, new Approvals(List.of(), java.time.Duration.ofMinutes(5), audit, java.time.Clock.systemUTC()),
                new ToolGuard(audit, "redact", 2048, null), Map.of());
    }

    public Gateway(List<Upstream> ups, Policy policy, AuditLog audit, MeterRegistry metrics, int pageSize, Approvals approvals,
                   ToolGuard guard, Map<String, UpstreamConfig> configs) {
        this.approvals = approvals;
        this.guard = guard;
        this.configs = configs;
        for (Upstream u : ups) {
            if (u.name().contains(SEP)) throw new IllegalArgumentException("upstream name may not contain " + SEP + ": " + u.name());
            upstreams.put(u.name(), u);
            u.onCatalogChange(this::catalogChanged);
            metrics.gauge("mcp.upstream.ready", List.of(io.micrometer.core.instrument.Tag.of("upstream", u.name())), u,
                    x -> x.state() == Upstream.State.READY ? 1 : 0);
        }
        this.policy = policy;
        this.audit = audit;
        this.metrics = metrics;
        this.pageSize = pageSize;
    }

    public Policy policy() { return policy; }
    public Map<String, Upstream> upstreams() { return upstreams; }
    public Approvals approvals() { return approvals; }
    public ToolGuard guard() { return guard; }

    public boolean trusted(String upstream) {
        var c = configs.get(upstream);
        return c != null && Boolean.TRUE.equals(c.trusted());
    }

    public void register(Session s) { sessions.add(s); }
    public void unregister(Session s) { sessions.remove(s); }

    private void catalogChanged(Upstream u) {
        schemas.keySet().removeIf(k -> k.startsWith(u.name() + SEP));
        guard.reload(u.name(), renamed(u, u.tools()), trusted(u.name()));
        ObjectNode n = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", "notifications/tools/list_changed");
        for (Session s : sessions) {
            for (var e : s.streams) {
                try {
                    e.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().data(n.toString()));
                } catch (Exception ex) {
                    s.streams.remove(e);
                }
            }
        }
    }

    // ------------------------------------------------------------ entry points

    public ObjectNode initializeResult(String requested) {
        ObjectNode r = JSON.createObjectNode().put("protocolVersion", VERSIONS.contains(requested) ? requested : VERSIONS.get(0));
        ObjectNode caps = r.putObject("capabilities");
        caps.putObject("tools").put("listChanged", true);
        caps.putObject("resources");
        caps.putObject("prompts");
        r.putObject("serverInfo").put("name", "mcp-fabric").put("version", "0.1.0");
        r.put("instructions", "Tools and prompts are named <upstream>__<name>.");
        return r;
    }

    /** Handle a request or notification. Returns null for notifications. */
    public ObjectNode handle(Session s, JsonNode msg) {
        String method = msg.path("method").asText("");
        JsonNode params = msg.path("params");
        if (!msg.has("id")) {
            if (method.equals("notifications/initialized")) s.initialized = true;
            if (method.equals("notifications/cancelled")) cancel(s, params.path("requestId"));
            return null;
        }
        JsonNode id = msg.get("id");
        try {
            JsonNode result = switch (method) {
                case "ping" -> JSON.createObjectNode();
                case "tools/list" -> page(visibleTools(s), "tools", params);
                case "tools/call" -> callTool(s, id, params);
                case "resources/list" -> page(collect(Upstream::resources, false), "resources", params);
                case "resources/read" -> readResource(params);
                case "prompts/list" -> page(collect(Upstream::prompts, true), "prompts", params);
                case "prompts/get" -> getPrompt(params);
                default -> throw new GatewayError(METHOD_NOT_FOUND, "method not found: " + method, null);
            };
            return ok(id, result);
        } catch (GatewayError e) {
            return error(id, e.code, e.getMessage(), e.data);
        } catch (Exception e) {
            log.error("internal error handling {}", method, e);
            return error(id, INTERNAL, "internal error", null);
        }
    }

    static ObjectNode ok(JsonNode id, JsonNode result) {
        ObjectNode r = JSON.createObjectNode().put("jsonrpc", "2.0");
        r.set("id", id);
        r.set("result", result);
        return r;
    }

    public static ObjectNode error(JsonNode id, int code, String message, JsonNode data) {
        ObjectNode r = JSON.createObjectNode().put("jsonrpc", "2.0");
        r.set("id", id);
        ObjectNode e = r.putObject("error").put("code", code).put("message", message);
        if (data != null) e.set("data", data);
        return r;
    }

    public static class GatewayError extends Exception {
        public final int code;
        public final JsonNode data;

        public GatewayError(int code, String m, JsonNode data) {
            super(m);
            this.code = code;
            this.data = data;
        }
    }

    // ------------------------------------------------------------ catalog

    private List<JsonNode> collect(java.util.function.Function<Upstream, List<JsonNode>> f, boolean rename) {
        List<JsonNode> out = new ArrayList<>();
        for (Upstream u : upstreams.values()) {
            if (u.state() != Upstream.State.READY) continue;
            for (JsonNode t : f.apply(u)) {
                if (rename) {
                    ObjectNode c = t.deepCopy();
                    c.put("name", u.name() + SEP + t.path("name").asText());
                    out.add(c);
                } else {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static List<JsonNode> renamed(Upstream u, List<JsonNode> list) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode t : list) {
            ObjectNode c = t.deepCopy();
            c.put("name", u.name() + SEP + t.path("name").asText());
            out.add(c);
        }
        return out;
    }

    /** Tools this client may call, as screened by the tool guard (quarantined and blocked tools are hidden). */
    private List<JsonNode> visibleTools(Session s) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode t : collect(Upstream::tools, true)) {
            String name = t.path("name").asText();
            if (!policy.allowed(s.client, name)) continue;
            var v = guard.view(name);
            if (v.isEmpty()) continue; // not screened yet
            if (v.get().callable() && v.get().hash().equals(ToolGuard.hash(t))) out.add(v.get().shown());
        }
        return out;
    }

    private JsonNode page(List<JsonNode> all, String field, JsonNode params) throws GatewayError {
        int start = 0;
        if (params.hasNonNull("cursor")) {
            try {
                start = Integer.parseInt(params.get("cursor").asText());
            } catch (NumberFormatException e) {
                throw new GatewayError(INVALID_PARAMS, "invalid cursor", null);
            }
            if (start < 0 || start > all.size()) throw new GatewayError(INVALID_PARAMS, "invalid cursor", null);
        }
        ObjectNode r = JSON.createObjectNode();
        ArrayNode a = r.putArray(field);
        all.subList(start, Math.min(all.size(), start + pageSize)).forEach(a::add);
        if (start + pageSize < all.size()) r.put("nextCursor", String.valueOf(start + pageSize));
        return r;
    }

    private record Target(Upstream upstream, String name) {
    }

    private Target route(String full) throws GatewayError {
        int i = full.indexOf(SEP);
        Upstream u = i > 0 ? upstreams.get(full.substring(0, i)) : null;
        if (u == null) throw new GatewayError(INVALID_PARAMS, "unknown tool or prompt: " + full, null);
        return new Target(u, full.substring(i + SEP.length()));
    }

    // ------------------------------------------------------------ tools

    private JsonNode callTool(Session s, JsonNode id, JsonNode params) throws GatewayError {
        String full = params.path("name").asText("");
        JsonNode args = params.has("arguments") ? params.get("arguments") : JSON.createObjectNode();
        long t0 = System.nanoTime();
        String outcome = "error";
        Approvals.Request approval = null;
        try {
            if (!policy.allowed(s.client, full)) {
                outcome = "forbidden";
                throw new GatewayError(FORBIDDEN, "client " + s.client.id() + " may not call " + full, null);
            }
            Target t = route(full);
            JsonNode tool = t.upstream().tools().stream().filter(x -> x.path("name").asText().equals(t.name())).findFirst()
                    .orElseThrow(() -> new GatewayError(INVALID_PARAMS, "unknown tool: " + full, null));
            var view = guard.view(full);
            if (view.isEmpty() || !view.get().callable() || !view.get().hash().equals(ToolGuard.hash(renamed(t.upstream(), List.of(tool)).get(0)))) {
                outcome = "quarantined";
                throw new GatewayError(FORBIDDEN, "tool " + full + " is quarantined: its definition is unscreened, blocked or changed since it was pinned", null);
            }
            if (!args.isObject()) throw new GatewayError(INVALID_PARAMS, "arguments must be an object", null);
            Set<ValidationMessage> errs = schemas.computeIfAbsent(full, k -> schemaFactory.getSchema(tool.path("inputSchema"))).validate(args);
            if (!errs.isEmpty()) {
                outcome = "invalid";
                ArrayNode d = JSON.createArrayNode();
                errs.stream().map(ValidationMessage::getMessage).sorted().forEach(d::add);
                throw new GatewayError(INVALID_PARAMS, "arguments do not match the tool's input schema", d);
            }
            String bad = PathGuard.check(configs.get(t.upstream().name()), args);
            if (bad != null) {
                outcome = "path_rejected";
                throw new GatewayError(INVALID_PARAMS, "argument rejected: " + bad, null);
            }
            if (approvals.required(full)) {
                approval = approvals.open(s.client.id(), s.client.subject(), s.id, full, Policy.sha256Hex(canonical(args)), args);
                Approvals.Status st = approvals.await(approval);
                if (st == Approvals.Status.DENIED) {
                    outcome = "approval_denied";
                    throw new GatewayError(FORBIDDEN, "call to " + full + " was denied by an approver", JSON.createObjectNode().put("approval", approval.id));
                }
                if (st != Approvals.Status.APPROVED) {
                    outcome = "approval_expired";
                    throw new GatewayError(APPROVAL_TIMEOUT, "no approval for " + full + " within the approval timeout", JSON.createObjectNode().put("approval", approval.id));
                }
            }
            ObjectNode fwd = JSON.createObjectNode().put("name", t.name());
            fwd.set("arguments", args);
            String key = id.toString();
            try {
                JsonNode r = t.upstream().request("tools/call", fwd, t.upstream().timeout(),
                        uid -> s.inflight.put(key, new Session.Inflight(t.upstream().name(), uid)));
                outcome = r.path("isError").asBoolean(false) ? "tool_error" : "ok";
                return r;
            } catch (RpcError e) {
                outcome = "upstream_error";
                throw new GatewayError(e.error.path("code").asInt(INTERNAL), e.getMessage(), e.error.get("data"));
            } catch (UpstreamException e) {
                outcome = "unavailable";
                throw new GatewayError(UNAVAILABLE, "upstream unavailable: " + e.getMessage(), null);
            } finally {
                s.inflight.remove(key);
            }
        } finally {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            ObjectNode rec = JSON.createObjectNode().put("event", "tools/call").put("client", s.client.id()).put("session", s.id)
                    .put("subject", s.client.subject()).put("tool", full).put("args_sha256", Policy.sha256Hex(canonical(args))).put("outcome", outcome).put("ms", ms);
            if (approval != null) {
                rec.put("approval", approval.id);
                if (approval.decidedBy() != null) rec.put("approved_by", approval.decidedBy());
            }
            audit.append(rec);
            Timer.builder("mcp.tool.calls").tag("tool", full).tag("outcome", outcome).register(metrics).record(java.time.Duration.ofNanos(System.nanoTime() - t0));
        }
    }

    static String canonical(JsonNode n) {
        try {
            return JSON.copy().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .writeValueAsString(JSON.treeToValue(n, Object.class));
        } catch (Exception e) {
            return n.toString();
        }
    }

    /** A rate-limit rejection is audited too. */
    public void auditRejected(Session s, String tool, String outcome) {
        audit.append(JSON.createObjectNode().put("event", "tools/call").put("client", s.client.id()).put("session", s.id).put("tool", tool)
                .put("outcome", outcome).put("ms", 0));
        metrics.counter("mcp.rejected", "reason", outcome).increment();
    }

    private void cancel(Session s, JsonNode requestId) {
        Session.Inflight f = s.inflight.remove(requestId.toString());
        if (f == null) return;
        Upstream u = upstreams.get(f.upstream());
        if (u != null) u.notifyQuietly("notifications/cancelled", JSON.createObjectNode().put("requestId", f.upstreamId()).put("reason", "client cancelled"));
    }

    // ------------------------------------------------------------ resources and prompts

    private JsonNode readResource(JsonNode params) throws GatewayError {
        String uri = params.path("uri").asText("");
        for (Upstream u : upstreams.values()) {
            if (u.state() == Upstream.State.READY && u.resources().stream().anyMatch(r -> r.path("uri").asText().equals(uri))) {
                return forward(u, "resources/read", params);
            }
        }
        throw new GatewayError(UNAVAILABLE, "resource not found: " + uri, null);
    }

    private JsonNode getPrompt(JsonNode params) throws GatewayError {
        Target t = route(params.path("name").asText(""));
        ObjectNode p = params.deepCopy();
        p.put("name", t.name());
        return forward(t.upstream(), "prompts/get", p);
    }

    private JsonNode forward(Upstream u, String method, JsonNode params) throws GatewayError {
        try {
            return u.request(method, params);
        } catch (RpcError e) {
            throw new GatewayError(e.error.path("code").asInt(INTERNAL), e.getMessage(), e.error.get("data"));
        } catch (UpstreamException e) {
            throw new GatewayError(UNAVAILABLE, "upstream unavailable: " + e.getMessage(), null);
        }
    }
}
