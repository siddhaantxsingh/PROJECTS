package dev.siddhant.mcpfabric;

import static dev.siddhant.mcpfabric.McpClient.obj;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.siddhant.mcpfabric.audit.AuditLog;
import dev.siddhant.mcpfabric.gateway.Gateway;
import dev.siddhant.mcpfabric.policy.Policy;
import dev.siddhant.mcpfabric.upstream.StdioUpstream;
import dev.siddhant.mcpfabric.upstream.Upstream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End to end: the gateway in front of a stdio upstream ("alpha", a child
 * process) and an HTTP upstream ("beta", a separate process), driven over
 * real HTTP by the test client.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability // metrics exporters are off in tests by default
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FabricIntegrationTest {
    static final String FULL = "key-full", LIMITED = "key-limited", SLOW = "key-slow", ADMIN = "key-admin";
    static Process beta;
    static int betaPort;
    static Path audit;

    @LocalServerPort
    int port;
    @Autowired
    Gateway gateway;

    static List<String> demo(String... args) {
        var cmd = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java"), "-cp", System.getProperty("java.class.path"),
                "dev.siddhant.mcpfabric.demo.DemoServer"));
        cmd.addAll(List.of(args));
        return cmd;
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        try (var s = new ServerSocket(0)) {
            betaPort = s.getLocalPort();
        }
        beta = new ProcessBuilder(demo("--name", "beta", "--http", String.valueOf(betaPort))).redirectErrorStream(true).start();
        var out = new BufferedReader(new InputStreamReader(beta.getInputStream()));
        String line;
        while ((line = out.readLine()) != null && !line.contains("listening")) { }
        audit = Files.createTempDirectory("fabric").resolve("audit.jsonl");
        List<String> alpha = demo("--name", "alpha");
        for (int i = 0; i < alpha.size(); i++) r.add("fabric.upstreams[0].command[" + i + "]", alpha.get(i)::toString);
        r.add("fabric.upstreams[0].name", () -> "alpha");
        r.add("fabric.upstreams[0].type", () -> "stdio");
        r.add("fabric.upstreams[0].timeout", () -> "2s");
        r.add("fabric.upstreams[1].name", () -> "beta");
        r.add("fabric.upstreams[1].type", () -> "http");
        r.add("fabric.upstreams[1].url", () -> "http://127.0.0.1:" + betaPort + "/mcp");
        r.add("fabric.upstreams[1].timeout", () -> "2s");
        r.add("fabric.clients[0].id", () -> "full");
        r.add("fabric.clients[0].key-sha256", () -> Policy.sha256Hex(FULL));
        r.add("fabric.clients[0].rate-per-minute", () -> "100000");
        r.add("fabric.clients[0].burst", () -> "1000");
        r.add("fabric.clients[1].id", () -> "limited");
        r.add("fabric.clients[1].key-sha256", () -> Policy.sha256Hex(LIMITED));
        r.add("fabric.clients[1].allow[0]", () -> "alpha__*");
        r.add("fabric.clients[1].deny[0]", () -> "*__crash");
        r.add("fabric.clients[2].id", () -> "slow");
        r.add("fabric.clients[2].key-sha256", () -> Policy.sha256Hex(SLOW));
        r.add("fabric.clients[2].rate-per-minute", () -> "60");
        r.add("fabric.clients[2].burst", () -> "3");
        r.add("fabric.admin-key-sha256", () -> Policy.sha256Hex(ADMIN));
        r.add("fabric.audit-file", audit::toString);
        r.add("fabric.page-size", () -> "4");
        r.add("fabric.breaker.threshold", () -> "2");
        r.add("fabric.breaker.cooldown", () -> "1500ms");
    }

    @AfterAll
    static void stop() {
        if (beta != null) beta.destroyForcibly();
    }

    @BeforeEach
    void waitReady() throws Exception {
        long end = System.currentTimeMillis() + 20_000;
        while (gateway.upstreams().values().stream().anyMatch(u -> u.state() != Upstream.State.READY)) {
            assertThat(System.currentTimeMillis()).as("upstreams ready").isLessThan(end);
            Thread.sleep(50);
        }
    }

    McpClient client(String key) throws Exception {
        var c = new McpClient(port, key);
        assertThat(c.initialize().status()).isEqualTo(200);
        return c;
    }

    static String text(McpClient.Reply r) {
        return r.result().path("content").get(0).path("text").asText();
    }

    @Test
    @Order(1)
    void handshakeSessionsAndAuth() throws Exception {
        var anon = new McpClient(port, null);
        assertThat(anon.initialize().status()).isEqualTo(401);
        var bad = new McpClient(port, "wrong");
        assertThat(bad.initialize().status()).isEqualTo(401);

        var c = new McpClient(port, FULL);
        var init = c.initialize();
        assertThat(init.result().path("protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(init.result().path("capabilities").path("tools").path("listChanged").asBoolean()).isTrue();
        assertThat(c.session).isNotBlank();
        assertThat(c.call("ping", null).result().isObject()).isTrue();
        assertThat(c.call("nope/nope", null).error().path("code").asInt()).isEqualTo(-32601);

        // another client cannot use this session; no session at all is rejected
        var other = new McpClient(port, LIMITED);
        other.session = c.session;
        assertThat(other.call("ping", null).status()).isEqualTo(404);
        var none = new McpClient(port, FULL);
        assertThat(none.call("ping", null).status()).isEqualTo(400);
        // unsupported protocol version header
        var resp = c.http.send(c.req().header("MCP-Protocol-Version", "1999-01-01")
                .POST(HttpRequest.BodyPublishers.ofString(McpClient.msg("ping", null, 99L).toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(400);
        // an older supported version is negotiated, an unknown one falls back to the latest
        var old = new McpClient(port, FULL);
        var r = old.send(McpClient.msg("initialize", obj("protocolVersion", "2025-03-26", "capabilities", obj()), 1L));
        assertThat(r.result().path("protocolVersion").asText()).isEqualTo("2025-03-26");
        // ending a session
        var del = c.http.send(c.req().DELETE().build(), HttpResponse.BodyHandlers.discarding());
        assertThat(del.statusCode()).isEqualTo(204);
        assertThat(c.call("ping", null).status()).isEqualTo(404);
    }

    @Test
    @Order(2)
    void catalogIsFederatedAndPaginated() throws Exception {
        var c = client(FULL);
        List<String> names = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            var r = c.call("tools/list", cursor == null ? obj() : obj("cursor", cursor));
            r.result().path("tools").forEach(t -> names.add(t.path("name").asText()));
            cursor = r.result().hasNonNull("nextCursor") ? r.result().get("nextCursor").asText() : null;
            pages++;
        } while (cursor != null);
        assertThat(pages).isGreaterThan(1);
        // upstream pagination (3 per page) and gateway pagination (4 per page) both followed
        assertThat(names).contains("alpha__echo", "alpha__stats", "beta__echo", "beta__add_tool").hasSize(14);
        assertThat(c.call("tools/list", obj("cursor", "garbage")).error().path("code").asInt()).isEqualTo(-32602);

        var res = c.call("resources/list", obj()).result().path("resources");
        assertThat(res).hasSize(2);
        assertThat(text2(c.call("resources/read", obj("uri", "memo://beta/notes")))).isEqualTo("notes of beta");
        assertThat(c.call("resources/read", obj("uri", "memo://zeta/notes")).error().path("code").asInt()).isEqualTo(-32002);
        var prompts = c.call("prompts/list", obj()).result().path("prompts");
        assertThat(prompts).extracting(p -> p.path("name").asText()).containsExactlyInAnyOrder("alpha__greet", "beta__greet");
        var g = c.call("prompts/get", obj("name", "beta__greet", "arguments", obj("who", "Ada")));
        assertThat(g.result().path("messages").get(0).path("content").path("text").asText()).isEqualTo("Hello, Ada (from beta)");
    }

    static String text2(McpClient.Reply r) {
        return r.result().path("contents").get(0).path("text").asText();
    }

    @Test
    @Order(3)
    void toolCallsAreRoutedAndValidated() throws Exception {
        var c = client(FULL);
        assertThat(text(c.tool("alpha__echo", obj("text", "hi")))).isEqualTo("hi");
        assertThat(text(c.tool("beta__add", obj("a", 2, "b", 40)))).isEqualTo("42.0");
        var fail = c.tool("alpha__fail", obj());
        assertThat(fail.result().path("isError").asBoolean()).isTrue();
        // schema validation happens before the upstream sees the call
        int before = calls(c, "alpha");
        var bad = c.tool("alpha__add", obj("a", "two"));
        assertThat(bad.error().path("code").asInt()).isEqualTo(-32602);
        assertThat(bad.error().path("data").toString()).contains("b").contains("a");
        assertThat(c.tool("alpha__echo", obj("text", "x", "extra", 1)).error().path("code").asInt()).isEqualTo(-32602);
        assertThat(calls(c, "alpha")).isEqualTo(before + 1); // only the stats call itself
        assertThat(c.tool("gamma__echo", obj()).error().path("code").asInt()).isEqualTo(-32602);
        assertThat(c.tool("alpha__nothing", obj()).error().path("code").asInt()).isEqualTo(-32602);
    }

    static int calls(McpClient c, String up) throws Exception {
        return McpClient.JSON.readTree(text(c.tool(up + "__stats", obj()))).path("calls").asInt();
    }

    @Test
    @Order(4)
    void authorizationAndRateLimits() throws Exception {
        var lim = client(LIMITED);
        var tools = lim.call("tools/list", obj());
        List<String> names = new ArrayList<>();
        String cursor = null;
        do {
            var r = lim.call("tools/list", cursor == null ? obj() : obj("cursor", cursor));
            r.result().path("tools").forEach(t -> names.add(t.path("name").asText()));
            cursor = r.result().hasNonNull("nextCursor") ? r.result().get("nextCursor").asText() : null;
        } while (cursor != null);
        assertThat(tools.status()).isEqualTo(200);
        assertThat(names).allMatch(n -> n.startsWith("alpha__")).doesNotContain("alpha__crash");
        assertThat(lim.tool("beta__echo", obj("text", "x")).error().path("code").asInt()).isEqualTo(-32001);
        assertThat(lim.tool("alpha__crash", obj()).error().path("code").asInt()).isEqualTo(-32001);
        assertThat(text(lim.tool("alpha__echo", obj("text", "ok")))).isEqualTo("ok");

        var slow = client(SLOW);
        int ok = 0, limited = 0;
        for (int i = 0; i < 8; i++) {
            var r = slow.tool("alpha__echo", obj("text", "x"));
            if (r.status() == 200) ok++;
            else if (r.status() == 429) {
                limited++;
                assertThat(r.error().path("code").asInt()).isEqualTo(-32029);
            }
        }
        assertThat(ok).isBetween(3, 4); // burst of 3 (+1 if a token refilled)
        assertThat(limited).isEqualTo(8 - ok);
        Thread.sleep(1100); // 1 token/s
        assertThat(slow.tool("alpha__echo", obj("text", "x")).status()).isEqualTo(200);
        // non-tool requests are not rate limited
        for (int i = 0; i < 10; i++) assertThat(slow.call("ping", null).status()).isEqualTo(200);
    }

    @Test
    @Order(5)
    void concurrentCallsKeepTheirResponses() throws Exception {
        var c = client(FULL);
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<String>> fs = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                String up = i % 2 == 0 ? "alpha" : "beta";
                String t = "msg-" + i;
                fs.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return text(c.tool(up + "__echo", obj("text", t)));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, ex));
            }
            for (int i = 0; i < 100; i++) assertThat(fs.get(i).get(30, TimeUnit.SECONDS)).isEqualTo("msg-" + i);
        }
    }

    @Test
    @Order(6)
    void timeoutsAndCancellationReachTheUpstream() throws Exception {
        var c = client(FULL);
        int cancels = cancels(c, "alpha");
        long t0 = System.currentTimeMillis();
        var r = c.tool("alpha__sleep", obj("ms", 10_000));
        assertThat(System.currentTimeMillis() - t0).isLessThan(5_000);
        assertThat(r.error().path("code").asInt()).isEqualTo(-32002);
        assertThat(r.error().path("message").asText()).contains("timed out");
        // the gateway told the upstream to stop
        waitFor(() -> cancels(c, "alpha") == cancels + 1, "cancellation after timeout");
        // a client cancellation is forwarded too
        var slowCall = CompletableFuture.supplyAsync(() -> {
            try {
                return c.send(McpClient.msg("tools/call", obj("name", "beta__sleep", "arguments", obj("ms", 1500)), 777L));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        int bc = cancels(c, "beta");
        Thread.sleep(300);
        assertThat(c.notify("notifications/cancelled", obj("requestId", 777, "reason", "user"))).isEqualTo(202);
        waitFor(() -> cancels(c, "beta") == bc + 1, "client cancellation forwarded");
        slowCall.get(10, TimeUnit.SECONDS);
    }

    static int cancels(McpClient c, String up) {
        try {
            return McpClient.JSON.readTree(text(c.tool(up + "__stats", obj()))).path("cancels").asInt();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    interface Cond {
        boolean ok() throws Exception;
    }

    static void waitFor(Cond c, String what) throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        while (!c.ok()) {
            assertThat(System.currentTimeMillis()).as(what).isLessThan(end);
            Thread.sleep(50);
        }
    }

    @Test
    @Order(7)
    void listChangedPropagatesToClients() throws Exception {
        var c = client(FULL);
        // open the notification stream
        var events = new java.util.concurrent.LinkedBlockingQueue<String>();
        var resp = c.http.sendAsync(c.req().header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofLines());
        Thread.ofVirtual().start(() -> {
            try {
                resp.get().body().forEach(l -> {
                    if (l.startsWith("data:")) events.add(l.substring(5).trim());
                });
            } catch (Exception ignored) {
                // stream closed
            }
        });
        Thread.sleep(300);
        assertThat(text(c.tool("beta__add_tool", obj("name", "shout")))).isEqualTo("added");
        String ev = events.poll(10, TimeUnit.SECONDS);
        assertThat(ev).contains("notifications/tools/list_changed");
        waitFor(() -> c.tool("beta__shout", obj("text", "hey")).result() != null, "new tool callable through the gateway");
        assertThat(text(c.tool("beta__shout", obj("text", "hey")))).isEqualTo("shout:hey");
    }

    @Test
    @Order(8)
    void crashedStdioUpstreamRestartsAndCircuitBreakerOpens() throws Exception {
        var c = client(FULL);
        var alpha = (StdioUpstream) gateway.upstreams().get("alpha");
        long pid = alpha.pid();
        var r = c.tool("alpha__crash", obj());
        assertThat(r.error().path("code").asInt()).isEqualTo(-32002);
        waitFor(() -> alpha.state() == Upstream.State.READY && alpha.pid() != pid, "restart");
        assertThat(text(c.tool("alpha__echo", obj("text", "back")))).isEqualTo("back");

        // two timeouts open beta's circuit (threshold 2): calls then fail fast
        var beta = gateway.upstreams().get("beta");
        c.tool("beta__sleep", obj("ms", 5000));
        c.tool("beta__sleep", obj("ms", 5000));
        assertThat(beta.breaker().isOpen()).isTrue();
        long t0 = System.currentTimeMillis();
        var fast = c.tool("beta__echo", obj("text", "x"));
        assertThat(System.currentTimeMillis() - t0).isLessThan(500);
        assertThat(fast.error().path("message").asText()).contains("circuit open");
        // after the cooldown one trial call goes through and closes it
        Thread.sleep(1600);
        assertThat(text(c.tool("beta__echo", obj("text", "again")))).isEqualTo("again");
        assertThat(beta.breaker().isOpen()).isFalse();
    }

    @Test
    @Order(9)
    void auditLogRecordsEveryCallAndDetectsTampering() throws Exception {
        var c = client(FULL);
        c.tool("alpha__echo", obj("text", "audited"));
        var admin = new McpClient(port, ADMIN);
        var http = admin.http;
        var adm = java.net.URI.create("http://127.0.0.1:" + port + "/admin/audit/verify");
        var v = http.send(HttpRequest.newBuilder(adm).header("Authorization", "Bearer " + ADMIN).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(McpClient.JSON.readTree(v.body()).path("intact").asBoolean()).isTrue();
        assertThat(http.send(HttpRequest.newBuilder(adm).header("Authorization", "Bearer " + FULL).build(), HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(401);
        List<String> lines = Files.readAllLines(audit);
        assertThat(lines).hasSizeGreaterThan(20);
        JsonNode last = McpClient.JSON.readTree(lines.get(lines.size() - 1));
        assertThat(last.path("tool").asText()).isEqualTo("alpha__echo");
        assertThat(last.path("outcome").asText()).isEqualTo("ok");
        assertThat(lines.stream().map(l -> l.contains("audited"))).doesNotContain(true); // arguments are hashed, not stored
        assertThat(lines).anyMatch(l -> l.contains("\"rate_limited\"")).anyMatch(l -> l.contains("\"forbidden\"")).anyMatch(l -> l.contains("\"invalid\""));
        // tamper with a copy: change one outcome
        Path copy = Files.createTempFile("audit", ".jsonl");
        var t = new ArrayList<>(lines);
        t.set(5, t.get(5).replaceFirst("\"outcome\":\"[a-z_]+\"", "\"outcome\":\"ok_tampered\""));
        Files.write(copy, t);
        assertThat(AuditLog.verify(copy)).contains("record 6");
        // and delete a record
        t = new ArrayList<>(lines);
        t.remove(3);
        Files.write(copy, t);
        assertThat(AuditLog.verify(copy)).isNotNull();
    }

    @Test
    @Order(10)
    void upstreamStatusAndMetrics() throws Exception {
        var http = java.net.http.HttpClient.newHttpClient();
        var s = http.send(HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/admin/upstreams"))
                .header("Authorization", "Bearer " + ADMIN).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode a = McpClient.JSON.readTree(s.body());
        assertThat(a).extracting(n -> n.path("name").asText()).containsExactly("alpha", "beta");
        var m = http.send(HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertThat(m).contains("mcp_tool_calls_seconds_count").contains("mcp_upstream_ready").contains("mcp_rejected_total");
    }
}
