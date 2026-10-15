package dev.siddhant.mcpfabric.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.siddhant.mcpfabric.FabricProperties;

import dev.siddhant.mcpfabric.audit.AuditLog;
import dev.siddhant.mcpfabric.auth.Authenticator;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator endpoints. They need the admin credential: a JWT with the admin
 * scope (OAuth mode) or the admin key (static dev mode). MCP client tokens
 * without the admin scope are refused.
 */
@RestController
public class AdminController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Gateway gw;
    private final FabricProperties props;
    private final McpController mcp;
    private final Authenticator authn;

    public AdminController(Gateway gw, FabricProperties props, McpController mcp, Authenticator authn) {
        this.gw = gw;
        this.props = props;
        this.mcp = mcp;
        this.authn = authn;
    }

    /** Run `body` with the admin's identity, or answer 401/403. */
    private ResponseEntity<?> asAdmin(String h, Function<String, ResponseEntity<?>> body) {
        var r = authn.admin(h);
        if (r instanceof Authenticator.Fail f) return McpController.challenge(f, null);
        return body.apply(((Authenticator.Ok) r).subject());
    }

    @GetMapping("/admin/upstreams")
    public ResponseEntity<?> upstreams(@RequestHeader(value = "Authorization", required = false) String h) {
        return asAdmin(h, who -> {
            ArrayNode a = JSON.createArrayNode();
            gw.upstreams().values().forEach(u -> {
                ObjectNode o = a.addObject().put("name", u.name()).put("state", u.state().name()).put("tools", u.tools().size())
                        .put("resources", u.resources().size()).put("prompts", u.prompts().size()).put("circuitOpen", u.breaker().isOpen());
                o.put("sessions", mcp.sessionCount());
            });
            return ResponseEntity.ok(a);
        });
    }

    @GetMapping("/admin/audit/verify")
    public ResponseEntity<?> verify(@RequestHeader(value = "Authorization", required = false) String h) {
        return asAdmin(h, who -> {
            try {
                String problem = AuditLog.verify(Path.of(props.auditFile()));
                return ResponseEntity.ok(JSON.createObjectNode().put("intact", problem == null).put("problem", problem));
            } catch (Exception e) {
                return ResponseEntity.internalServerError().build();
            }
        });
    }

    // ------------------------------------------------------------ approvals

    @GetMapping("/admin/approvals")
    public ResponseEntity<?> approvals(@RequestHeader(value = "Authorization", required = false) String h) {
        return asAdmin(h, who -> {
            ArrayNode a = JSON.createArrayNode();
            gw.approvals().all().stream().sorted(Comparator.comparing(r -> r.created)).forEach(r -> a.add(r.toJson()));
            return ResponseEntity.ok(a);
        });
    }

    @PostMapping("/admin/approvals/{id}/approve")
    public ResponseEntity<?> approve(@RequestHeader(value = "Authorization", required = false) String h, @PathVariable String id,
                                     @RequestBody(required = false) JsonNode body) {
        return asAdmin(h, who -> decide(id, true, who, body));
    }

    @PostMapping("/admin/approvals/{id}/deny")
    public ResponseEntity<?> deny(@RequestHeader(value = "Authorization", required = false) String h, @PathVariable String id,
                                  @RequestBody(required = false) JsonNode body) {
        return asAdmin(h, who -> decide(id, false, who, body));
    }

    private ResponseEntity<?> decide(String id, boolean approve, String who, JsonNode body) {
        String reason = body == null ? null : body.path("reason").asText(null);
        var d = gw.approvals().decide(id, approve, who, reason);
        return switch (d.outcome()) {
            case APPLIED, ALREADY -> ResponseEntity.ok(d.request().toJson().put("outcome", d.outcome().name()));
            case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).body(d.request().toJson().put("outcome", "CONFLICT"));
            case SELF_APPROVAL -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(JSON.createObjectNode().put("error", "requester cannot approve their own call"));
            case NOT_FOUND -> ResponseEntity.notFound().build();
        };
    }

    // ------------------------------------------------------------ tool guard

    @GetMapping("/admin/tools")
    public ResponseEntity<?> tools(@RequestHeader(value = "Authorization", required = false) String h) {
        return asAdmin(h, who -> {
            ArrayNode a = JSON.createArrayNode();
            gw.guard().views().forEach(v -> a.add(v.toJson()));
            return ResponseEntity.ok(a);
        });
    }

    /** Accept a tool's current definition after a reviewed change (re-pin). */
    @PostMapping("/admin/tools/{name}/accept")
    public ResponseEntity<?> accept(@RequestHeader(value = "Authorization", required = false) String h, @PathVariable String name) {
        return asAdmin(h, who -> gw.guard().view(name)
                .flatMap(v -> gw.guard().accept(name, who, gw.trusted(v.upstream())))
                .<ResponseEntity<?>>map(v -> ResponseEntity.ok(v.toJson()))
                .orElse(ResponseEntity.notFound().build()));
    }

}
