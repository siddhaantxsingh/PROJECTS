package dev.siddhant.mcpfabric;

import static dev.siddhant.mcpfabric.McpClient.obj;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.siddhant.mcpfabric.gateway.Gateway;
import dev.siddhant.mcpfabric.policy.Policy;
import dev.siddhant.mcpfabric.upstream.CircuitBreaker;
import dev.siddhant.mcpfabric.upstream.StdioUpstream;
import dev.siddhant.mcpfabric.upstream.Upstream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Gateway overhead and throughput. Opt-in:
 *   mvn test -Dtest=BenchmarkTest -Dbench=true   (writes target/bench.json)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "bench", matches = "true")
class BenchmarkTest {
    static final String KEY = "bench-key";

    @LocalServerPort
    int port;
    @Autowired
    Gateway gateway;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        List<String> cmd = FabricIntegrationTest.demo("--name", "alpha");
        for (int i = 0; i < cmd.size(); i++) r.add("fabric.upstreams[0].command[" + i + "]", cmd.get(i)::toString);
        r.add("fabric.upstreams[0].name", () -> "alpha");
        r.add("fabric.upstreams[0].type", () -> "stdio");
        r.add("fabric.clients[0].id", () -> "bench");
        r.add("fabric.clients[0].key-sha256", () -> Policy.sha256Hex(KEY));
        r.add("fabric.clients[0].rate-per-minute", () -> "100000000");
        r.add("fabric.clients[0].burst", () -> "1000000");
        // -Dbench.auditDir=/dev/shm puts the audit log on tmpfs (fsync ~free) to isolate its cost
        String ad = System.getProperty("bench.auditDir");
        Path dir = ad == null ? Files.createTempDirectory("bench") : Files.createTempDirectory(Path.of(ad), "bench");
        r.add("fabric.audit-file", () -> dir.resolve("audit.jsonl").toString());
        r.add("fabric.auth.mode", () -> "static");
    }

    static double pct(double[] v, double p) {
        Arrays.sort(v);
        return v[(int) Math.round((v.length - 1) * p)];
    }

    static ObjectNode latency(String name, double[] us) {
        return new ObjectMapper().createObjectNode().put("mode", name).put("calls", us.length).put("p50_us", pct(us, 0.5))
                .put("p99_us", pct(us, 0.99)).put("mean_us", Arrays.stream(us).average().orElse(0));
    }

    @Test
    void bench() throws Exception {
        while (gateway.upstreams().get("alpha").state() != Upstream.State.READY) Thread.sleep(50);
        var out = new ObjectMapper().createObjectNode();
        ArrayNode lat = out.putArray("sequential_latency");
        int n = 3000;
        // 1. the upstream alone, through the same stdio transport, no gateway
        var direct = new StdioUpstream("direct", FabricIntegrationTest.demo("--name", "direct"), Map.of(), Duration.ofSeconds(10),
                new CircuitBreaker(5, Duration.ofSeconds(1), Clock.systemUTC()));
        direct.start();
        while (direct.state() != Upstream.State.READY) Thread.sleep(50);
        var args = obj("name", "echo", "arguments", obj("text", "hello"));
        for (int i = 0; i < 500; i++) direct.request("tools/call", args);
        double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            long t = System.nanoTime();
            direct.request("tools/call", args);
            d[i] = (System.nanoTime() - t) / 1e3;
        }
        lat.add(latency("upstream direct (stdio)", d));
        direct.close();
        // 2. through the gateway over HTTP (auth, policy, schema validation, audit fsync)
        var c = new McpClient(port, KEY);
        c.initialize();
        for (int i = 0; i < 500; i++) c.tool("alpha__echo", obj("text", "hello"));
        double[] g = new double[n];
        for (int i = 0; i < n; i++) {
            long t = System.nanoTime();
            var r = c.tool("alpha__echo", obj("text", "hello"));
            g[i] = (System.nanoTime() - t) / 1e3;
            if (r.status() != 200 || r.result() == null) throw new AssertionError(r.body().toString());
        }
        lat.add(latency("through mcp-fabric (HTTP)", g));
        // 3. throughput with concurrent clients for 5 s each
        ArrayNode thr = out.putArray("throughput");
        for (int clients : new int[] {1, 8, 32}) {
            List<McpClient> cs = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                var x = new McpClient(port, KEY);
                x.initialize();
                cs.add(x);
            }
            AtomicLong done = new AtomicLong();
            long end = System.nanoTime() + 5_000_000_000L;
            List<Double> all = java.util.Collections.synchronizedList(new ArrayList<>());
            try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
                for (var x : cs) {
                    ex.submit(() -> {
                        while (System.nanoTime() < end) {
                            long t = System.nanoTime();
                            var r = x.tool("alpha__echo", obj("text", "hello"));
                            all.add((System.nanoTime() - t) / 1e3);
                            if (r.status() == 200) done.incrementAndGet();
                        }
                        return null;
                    });
                }
                ex.shutdown();
                ex.awaitTermination(30, TimeUnit.SECONDS);
            }
            double[] v = all.stream().mapToDouble(Double::doubleValue).toArray();
            thr.addObject().put("clients", clients).put("calls_per_s", done.get() / 5.0).put("p50_us", pct(v, 0.5)).put("p99_us", pct(v, 0.99));
        }
        out.put("cpus", Runtime.getRuntime().availableProcessors());
        out.put("audit_dir", System.getProperty("bench.auditDir", "disk (temp dir)"));
        Files.writeString(Path.of(System.getProperty("bench.out", "target/bench.json")), out.toPrettyString());
        System.out.println(out.toPrettyString());
    }
}
