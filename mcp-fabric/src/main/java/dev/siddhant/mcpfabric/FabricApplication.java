package dev.siddhant.mcpfabric;

import dev.siddhant.mcpfabric.audit.AuditLog;
import dev.siddhant.mcpfabric.gateway.Gateway;
import dev.siddhant.mcpfabric.policy.Policy;
import dev.siddhant.mcpfabric.upstream.CircuitBreaker;
import dev.siddhant.mcpfabric.upstream.HttpUpstream;
import dev.siddhant.mcpfabric.upstream.StdioUpstream;
import dev.siddhant.mcpfabric.upstream.Upstream;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(FabricProperties.class)
@EnableScheduling
public class FabricApplication {
    public static void main(String[] args) {
        SpringApplication.run(FabricApplication.class, args);
    }

    @Bean(destroyMethod = "")
    List<Upstream> upstreams(FabricProperties p) {
        return p.upstreams().stream().<Upstream>map(c -> {
            Duration t = c.timeout() == null ? Duration.ofSeconds(30) : c.timeout();
            var cb = new CircuitBreaker(p.breaker().threshold(), p.breaker().cooldown(), Clock.systemUTC());
            return switch (c.type()) {
                case "stdio" -> new StdioUpstream(c.name(), c.command(), c.env() == null ? Map.of() : c.env(), t, cb);
                case "http" -> new HttpUpstream(c.name(), URI.create(c.url()), c.headers() == null ? Map.of() : c.headers(), t, cb);
                default -> throw new IllegalArgumentException("unknown upstream type " + c.type());
            };
        }).toList();
    }

    @Bean(destroyMethod = "close")
    AuditLog auditLog(FabricProperties p) throws Exception {
        return new AuditLog(Path.of(p.auditFile()));
    }

    @Bean
    Gateway gateway(List<Upstream> ups, FabricProperties p, AuditLog audit, MeterRegistry metrics) {
        var g = new Gateway(ups, new Policy(p.clients(), Clock.systemUTC()), audit, metrics, p.pageSize());
        ups.forEach(Upstream::start);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> ups.forEach(Upstream::close)));
        return g;
    }
}
