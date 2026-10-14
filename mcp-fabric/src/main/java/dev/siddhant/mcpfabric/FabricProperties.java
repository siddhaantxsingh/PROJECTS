package dev.siddhant.mcpfabric;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fabric")
public record FabricProperties(List<UpstreamConfig> upstreams, List<ClientConfig> clients, String auditFile, Breaker breaker,
                               Duration sessionIdleTimeout, String adminKeySha256, Integer pageSize) {

    public record UpstreamConfig(String name, String type, List<String> command, Map<String, String> env, String url,
                                 Map<String, String> headers, Duration timeout) {
    }

    /** keySha256: hex SHA-256 of the client's bearer token (the token itself is never stored). */
    public record ClientConfig(String id, String keySha256, List<String> allow, List<String> deny, Integer ratePerMinute, Integer burst) {
    }

    public record Breaker(int threshold, Duration cooldown) {
    }

    public FabricProperties {
        upstreams = upstreams == null ? List.of() : upstreams;
        clients = clients == null ? List.of() : clients;
        auditFile = auditFile == null ? "fabric-audit.jsonl" : auditFile;
        breaker = breaker == null ? new Breaker(5, Duration.ofSeconds(10)) : breaker;
        sessionIdleTimeout = sessionIdleTimeout == null ? Duration.ofMinutes(30) : sessionIdleTimeout;
        pageSize = pageSize == null ? 50 : pageSize;
    }
}
