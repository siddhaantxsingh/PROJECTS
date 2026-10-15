package dev.siddhant.mcpfabric;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fabric")
public record FabricProperties(List<UpstreamConfig> upstreams, List<ClientConfig> clients, String auditFile, Breaker breaker,
                               Duration sessionIdleTimeout, String adminKeySha256, Integer pageSize, Auth auth,
                               Approval approval, ToolGuard toolGuard, Long maxRequestBytes) {

    /**
     * One upstream MCP server. Credentials for it live only here (headers for
     * HTTP, env for stdio) and are injected by the gateway; nothing the client
     * sends is forwarded as a credential.
     *
     * trusted:        tool metadata from a trusted upstream is only flagged, never redacted.
     * inheritEnv:     pass the gateway's whole environment to a stdio child (default false:
     *                 only a small allowlist such as PATH and HOME, plus `env`).
     * pathArguments:  names of top-level string arguments that are file paths; they are
     *                 checked for traversal and, if pathRoots is set, confined to those roots.
     */
    public record UpstreamConfig(String name, String type, List<String> command, Map<String, String> env, String url,
                                 Map<String, String> headers, Duration timeout, Boolean trusted, Boolean inheritEnv,
                                 List<String> pathArguments, List<String> pathRoots) {
    }

    /** keySha256: hex SHA-256 of the client's bearer token (the token itself is never stored). Static mode only. */
    public record ClientConfig(String id, String keySha256, List<String> allow, List<String> deny, Integer ratePerMinute, Integer burst) {
    }

    /** OAuth mode: a tenant named by the token's tenant claim, with its tool policy and rate limit. */
    public record TenantConfig(String name, List<String> allow, List<String> deny, Integer ratePerMinute, Integer burst) {
    }

    public record Breaker(int threshold, Duration cooldown) {
    }

    /**
     * mode: "oauth" (default; JWT access tokens per the MCP authorization spec) or
     * "static" (SHA-256-hashed bearer tokens from `clients`; for development only).
     */
    public record Auth(String mode, String issuer, String jwksUri, String resource, List<String> audiences,
                       List<String> algorithms, Duration clockSkew, String requiredScope, String adminScope,
                       String serverScopePrefix, String tenantClaim, List<TenantConfig> tenants,
                       List<String> authorizationServers, String resourceMetadataUrl, Duration jwksCacheTtl,
                       Duration jwksMinRefreshInterval) {
        public Auth {
            mode = mode == null ? "oauth" : mode;
            audiences = audiences == null || audiences.isEmpty() ? (resource == null ? List.of() : List.of(resource)) : audiences;
            algorithms = algorithms == null || algorithms.isEmpty() ? List.of("RS256", "ES256", "PS256") : algorithms;
            clockSkew = clockSkew == null ? Duration.ofSeconds(30) : clockSkew;
            requiredScope = requiredScope == null ? "mcp:tools" : requiredScope;
            adminScope = adminScope == null ? "mcp:admin" : adminScope;
            serverScopePrefix = serverScopePrefix == null ? "mcp:server:" : serverScopePrefix;
            tenantClaim = tenantClaim == null ? "tenant" : tenantClaim;
            tenants = tenants == null ? List.of() : tenants;
            authorizationServers = authorizationServers == null || authorizationServers.isEmpty()
                    ? (issuer == null ? List.of() : List.of(issuer)) : authorizationServers;
            jwksCacheTtl = jwksCacheTtl == null ? Duration.ofMinutes(5) : jwksCacheTtl;
            jwksMinRefreshInterval = jwksMinRefreshInterval == null ? Duration.ofSeconds(30) : jwksMinRefreshInterval;
        }

        public boolean oauth() {
            return "oauth".equals(mode);
        }
    }

    /** Tools matching `requiredFor` wait for an admin's decision, at most `timeout`. */
    public record Approval(List<String> requiredFor, Duration timeout) {
        public Approval {
            requiredFor = requiredFor == null ? List.of() : requiredFor;
            timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
        }
    }

    /**
     * Tool-metadata screening. untrustedAction: "redact" (default) replaces
     * suspicious text, "block" hides the tool, "flag" only reports it.
     * pinsFile: where first-seen tool-definition hashes are kept (in memory if unset).
     */
    public record ToolGuard(String untrustedAction, Integer maxDescriptionLength, String pinsFile) {
        public ToolGuard {
            untrustedAction = untrustedAction == null ? "redact" : untrustedAction;
            maxDescriptionLength = maxDescriptionLength == null ? 2048 : maxDescriptionLength;
        }
    }

    public FabricProperties {
        upstreams = upstreams == null ? List.of() : upstreams;
        clients = clients == null ? List.of() : clients;
        auditFile = auditFile == null ? "fabric-audit.jsonl" : auditFile;
        breaker = breaker == null ? new Breaker(5, Duration.ofSeconds(10)) : breaker;
        sessionIdleTimeout = sessionIdleTimeout == null ? Duration.ofMinutes(30) : sessionIdleTimeout;
        pageSize = pageSize == null ? 50 : pageSize;
        auth = auth == null ? new Auth(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null) : auth;
        approval = approval == null ? new Approval(null, null) : approval;
        toolGuard = toolGuard == null ? new ToolGuard(null, null, null) : toolGuard;
        maxRequestBytes = maxRequestBytes == null ? 1_048_576L : maxRequestBytes;
    }
}
