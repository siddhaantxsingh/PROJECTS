package dev.siddhant.mcpfabric.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.siddhant.mcpfabric.auth.Authenticator;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth 2.0 Protected Resource Metadata (RFC 9728), which MCP clients use to
 * find the authorization server for this resource. Served at the root
 * well-known path and at the path-inserted form for the /mcp resource.
 * Not served in static dev mode.
 */
@RestController
public class WellKnownController {
    private final Authenticator authn;

    public WellKnownController(Authenticator authn) {
        this.authn = authn;
    }

    @GetMapping({"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp"})
    public ResponseEntity<ObjectNode> metadata() {
        if (!authn.oauth()) return ResponseEntity.notFound().build();
        var c = authn.config();
        ObjectNode o = new ObjectMapper().createObjectNode().put("resource", c.resource());
        c.authorizationServers().forEach(o.putArray("authorization_servers")::add);
        o.putArray("scopes_supported").add(c.requiredScope()).add(c.adminScope()).add(c.serverScopePrefix() + "*");
        o.putArray("bearer_methods_supported").add("header");
        o.put("resource_name", "mcp-fabric");
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(5))).body(o);
    }
}
