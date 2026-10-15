package dev.siddhant.mcpfabric;

import static org.assertj.core.api.Assertions.assertThat;

import dev.siddhant.mcpfabric.FabricProperties.ClientConfig;
import dev.siddhant.mcpfabric.audit.AuditLog;
import dev.siddhant.mcpfabric.policy.Policy;
import dev.siddhant.mcpfabric.upstream.CircuitBreaker;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class UnitTests {
    static class FakeClock extends Clock {
        final AtomicLong ms = new AtomicLong(1_000_000);
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(ms.get()); }
        public long millis() { return ms.get(); }
    }

    @Test
    void circuitBreakerOpensHalfOpensAndCloses() {
        var clock = new FakeClock();
        var cb = new CircuitBreaker(3, Duration.ofSeconds(5), clock);
        cb.failure();
        cb.failure();
        assertThat(cb.allows()).isTrue();
        cb.failure();
        assertThat(cb.allows()).isFalse();
        clock.ms.addAndGet(5000);
        assertThat(cb.allows()).isTrue();  // one probe
        assertThat(cb.allows()).isFalse(); // only one
        cb.failure();                      // probe failed: open again
        assertThat(cb.allows()).isFalse();
        clock.ms.addAndGet(5000);
        assertThat(cb.allows()).isTrue();
        cb.success();
        assertThat(cb.isOpen()).isFalse();
        assertThat(cb.allows()).isTrue();
    }

    @Test
    void policyGlobsDenyWinsAndTokenBucket() {
        var clock = new FakeClock();
        var p = new Policy(List.of(new ClientConfig("a", Policy.sha256Hex("k"), List.of("alpha__*", "beta__echo"), List.of("*__crash"), 60, 2)), clock);
        var c = p.authenticate("k").orElseThrow();
        assertThat(p.authenticate("x")).isEmpty();
        assertThat(p.allowed(c, "alpha__echo")).isTrue();
        assertThat(p.allowed(c, "alpha__crash")).isFalse();
        assertThat(p.allowed(c, "beta__echo")).isTrue();
        assertThat(p.allowed(c, "beta__echoes")).isFalse();
        assertThat(p.allowed(c, "alpha.x")).isFalse();
        assertThat(p.admit(c)).isTrue();
        assertThat(p.admit(c)).isTrue();
        assertThat(p.admit(c)).isFalse();
        clock.ms.addAndGet(1000);
        assertThat(p.admit(c)).isTrue();
        assertThat(p.admit(c)).isFalse();
        clock.ms.addAndGet(60_000); // refills only up to the burst
        assertThat(p.admit(c)).isTrue();
        assertThat(p.admit(c)).isTrue();
        assertThat(p.admit(c)).isFalse();
    }

    @Test
    void auditLogSurvivesATornWriteAndContinuesTheChain() throws Exception {
        var path = Files.createTempDirectory("audit").resolve("a.jsonl");
        try (var log = new AuditLog(path)) {
            for (int i = 0; i < 5; i++) log.append(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("i", i));
        }
        // simulate a crash in the middle of writing record 6
        Files.writeString(path, "{\"i\":5,\"seq\":6,\"ts\":\"x\",\"pr", java.nio.file.StandardOpenOption.APPEND);
        try (var log = new AuditLog(path)) {
            log.append(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("i", 6));
        }
        assertThat(AuditLog.verify(path)).isNull();
        assertThat(Files.readAllLines(path)).hasSize(6);
    }
}

class ExampleConfigTest {
    @org.junit.jupiter.api.Test
    void exampleConfigBindsAndItsTokensAuthenticate() throws Exception {
        var sources = new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("example", new org.springframework.core.io.FileSystemResource("config/example.yml"));
        var env = new org.springframework.core.env.StandardEnvironment();
        sources.forEach(env.getPropertySources()::addLast);
        var p = org.springframework.boot.context.properties.bind.Binder.get(env).bind("fabric", FabricProperties.class).get();
        org.assertj.core.api.Assertions.assertThat(p.upstreams()).hasSize(2);
        var policy = new Policy(p.clients(), java.time.Clock.systemUTC());
        var ide = policy.authenticate("change-me-ide").orElseThrow();
        var ci = policy.authenticate("change-me-ci").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(policy.allowed(ide, "files__echo")).isTrue();
        org.assertj.core.api.Assertions.assertThat(policy.allowed(ci, "files__echo")).isFalse();
        org.assertj.core.api.Assertions.assertThat(Policy.sha256Hex("change-me-admin")).isEqualTo(p.adminKeySha256());
        org.assertj.core.api.Assertions.assertThat(p.auth().mode()).isEqualTo("static");
    }

    @org.junit.jupiter.api.Test
    void oauthExampleConfigBindsAndBuildsAVerifier() throws Exception {
        var sources = new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("oauth", new org.springframework.core.io.FileSystemResource("config/oauth-example.yml"));
        var env = new org.springframework.core.env.StandardEnvironment();
        sources.forEach(env.getPropertySources()::addLast);
        var p = org.springframework.boot.context.properties.bind.Binder.get(env).bind("fabric", FabricProperties.class).get();
        org.assertj.core.api.Assertions.assertThat(p.auth().oauth()).isTrue();
        org.assertj.core.api.Assertions.assertThat(p.auth().tenants()).isNotEmpty();
        var policy = new Policy(p.clients(), p.auth().tenants(), java.time.Clock.systemUTC());
        var a = new dev.siddhant.mcpfabric.auth.Authenticator(p, policy, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        org.assertj.core.api.Assertions.assertThat(a.metadataUrl()).isEqualTo("https://mcp.example.com/.well-known/oauth-protected-resource/mcp");
    }
}
