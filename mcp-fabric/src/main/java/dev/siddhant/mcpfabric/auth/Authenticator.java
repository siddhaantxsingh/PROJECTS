package dev.siddhant.mcpfabric.auth;

import dev.siddhant.mcpfabric.FabricProperties;
import dev.siddhant.mcpfabric.policy.Policy;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns an `Authorization` header into a caller, for the MCP endpoint and for
 * the admin API. In OAuth mode the bearer token must be a valid JWT access
 * token for this resource with the required scope (`mcp:tools`, or
 * `mcp:admin` for the admin API); failures carry the `WWW-Authenticate`
 * challenge the MCP authorization spec asks for, pointing at the Protected
 * Resource Metadata document. Static mode (hashed shared tokens) is for
 * development only.
 */
public class Authenticator {
    private static final Logger log = LoggerFactory.getLogger(Authenticator.class);

    public sealed interface Result permits Ok, Fail {
    }

    /** subject: who is calling (JWT `sub`; the static client id or "admin-key" in static mode). */
    public record Ok(Policy.Client client, String subject) implements Result {
    }

    public record Fail(int status, String challenge, String message) implements Result {
    }

    private final FabricProperties.Auth cfg;
    private final Policy policy;
    private final byte[] adminDigest;
    private final JwtVerifier jwt;
    private final MeterRegistry metrics;
    private final String metadataUrl;

    public Authenticator(FabricProperties p, Policy policy, MeterRegistry metrics) {
        this.cfg = p.auth();
        this.policy = policy;
        this.metrics = metrics;
        this.adminDigest = p.adminKeySha256() == null ? null : HexFormat.of().parseHex(p.adminKeySha256());
        if (cfg.oauth()) {
            jwt = new JwtVerifier(cfg);
            metadataUrl = cfg.resourceMetadataUrl() != null ? cfg.resourceMetadataUrl() : defaultMetadataUrl(cfg.resource());
        } else if ("static".equals(cfg.mode())) {
            jwt = null;
            metadataUrl = null;
            log.warn("fabric.auth.mode=static: shared bearer tokens, for development only; use oauth in production");
        } else {
            throw new IllegalStateException("fabric.auth.mode must be oauth or static, not " + cfg.mode());
        }
    }

    /** RFC 9728 section 3.1: the well-known path is inserted before the resource's path. */
    static String defaultMetadataUrl(String resource) {
        URI u = URI.create(resource);
        String path = u.getRawPath() == null || u.getRawPath().equals("/") ? "" : u.getRawPath();
        return u.getScheme() + "://" + u.getRawAuthority() + "/.well-known/oauth-protected-resource" + path;
    }

    public boolean oauth() {
        return jwt != null;
    }

    public FabricProperties.Auth config() {
        return cfg;
    }

    public String metadataUrl() {
        return metadataUrl;
    }

    private static String bearer(String header) {
        if (header == null || header.length() < 8 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String t = header.substring(7).trim();
        return t.isEmpty() ? null : t;
    }

    private Fail fail(int status, String error, String description, String scope) {
        metrics.counter("mcp.auth.failures", "reason", error == null ? "missing" : description == null ? error : description).increment();
        if (!oauth()) return new Fail(status, "Bearer realm=\"mcp-fabric\"", "missing or invalid bearer token");
        StringBuilder c = new StringBuilder("Bearer realm=\"mcp-fabric\"");
        if (error != null) c.append(", error=\"").append(error).append('"');
        if (description != null) c.append(", error_description=\"").append(description).append('"');
        if (scope != null) c.append(", scope=\"").append(scope).append('"');
        c.append(", resource_metadata=\"").append(metadataUrl).append('"');
        String msg = error == null ? "authorization required" : error + (description == null ? "" : ": " + description);
        return new Fail(status, c.toString(), msg);
    }

    /** Caller of the MCP endpoint. */
    public Result mcp(String header) {
        String token = bearer(header);
        if (token == null) return fail(401, null, null, null);
        if (!oauth()) {
            return policy.authenticate(token).<Result>map(c -> new Ok(c, c.id())).orElseGet(() -> fail(401, "invalid_token", null, null));
        }
        JwtVerifier.Verified v;
        try {
            v = jwt.verify(token);
        } catch (JwtVerifier.InvalidToken e) {
            log.debug("token rejected ({}): {}", e.reason, e.getMessage());
            return fail(401, "invalid_token", e.reason, null);
        }
        if (!v.scopes().contains(cfg.requiredScope())) return fail(403, "insufficient_scope", null, cfg.requiredScope());
        Set<String> servers = new LinkedHashSet<>();
        for (String s : v.scopes()) if (s.startsWith(cfg.serverScopePrefix())) servers.add(s.substring(cfg.serverScopePrefix().length()));
        Object tenant = v.claims().getClaim(cfg.tenantClaim());
        String clientId = firstString(v, "client_id", "azp");
        if (clientId == null) clientId = v.claims().getSubject();
        var client = policy.forToken(tenant instanceof String t ? t : null, clientId, v.claims().getSubject(), servers);
        if (client.isEmpty()) return fail(403, "insufficient_scope", "unknown tenant", null);
        return new Ok(client.get(), v.claims().getSubject());
    }

    /** Caller of the admin API: the admin key (static mode) or a JWT with the admin scope. */
    public Result admin(String header) {
        String token = bearer(header);
        if (token == null) return fail(401, null, null, null);
        if (!oauth()) {
            if (adminDigest == null) return fail(401, "invalid_token", null, null);
            boolean ok = MessageDigest.isEqual(HexFormat.of().parseHex(Policy.sha256Hex(token)), adminDigest);
            return ok ? new Ok(null, "admin-key") : fail(401, "invalid_token", null, null);
        }
        try {
            var v = jwt.verify(token);
            if (!v.scopes().contains(cfg.adminScope())) return fail(403, "insufficient_scope", null, cfg.adminScope());
            return new Ok(null, v.claims().getSubject());
        } catch (JwtVerifier.InvalidToken e) {
            return fail(401, "invalid_token", e.reason, null);
        }
    }

    private static String firstString(JwtVerifier.Verified v, String... names) {
        for (String n : names) if (v.claims().getClaim(n) instanceof String s && !s.isBlank()) return s;
        return null;
    }
}
