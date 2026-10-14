package dev.siddhant.mcpfabric.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.siddhant.mcpfabric.FabricProperties;
import dev.siddhant.mcpfabric.audit.AuditLog;
import dev.siddhant.mcpfabric.policy.Policy;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Operator endpoints, guarded by the admin token. */
@RestController
public class AdminController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Gateway gw;
    private final FabricProperties props;
    private final McpController mcp;

    public AdminController(Gateway gw, FabricProperties props, McpController mcp) {
        this.gw = gw;
        this.props = props;
        this.mcp = mcp;
    }

    private boolean admin(String h) {
        if (props.adminKeySha256() == null || h == null || !h.startsWith("Bearer ")) return false;
        return MessageDigest.isEqual(HexFormat.of().parseHex(Policy.sha256Hex(h.substring(7).trim())), HexFormat.of().parseHex(props.adminKeySha256()));
    }

    @GetMapping("/admin/upstreams")
    public ResponseEntity<?> upstreams(@RequestHeader(value = "Authorization", required = false) String h) {
        if (!admin(h)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        ArrayNode a = JSON.createArrayNode();
        gw.upstreams().values().forEach(u -> {
            ObjectNode o = a.addObject().put("name", u.name()).put("state", u.state().name()).put("tools", u.tools().size())
                    .put("resources", u.resources().size()).put("prompts", u.prompts().size()).put("circuitOpen", u.breaker().isOpen());
            o.put("sessions", mcp.sessionCount());
        });
        return ResponseEntity.ok(a);
    }

    @GetMapping("/admin/audit/verify")
    public ResponseEntity<?> verify(@RequestHeader(value = "Authorization", required = false) String h) throws Exception {
        if (!admin(h)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        String problem = AuditLog.verify(Path.of(props.auditFile()));
        return ResponseEntity.ok(JSON.createObjectNode().put("intact", problem == null).put("problem", problem));
    }
}
