package dev.siddhant.mcpfabric;

import static dev.siddhant.mcpfabric.McpClient.obj;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import dev.siddhant.mcpfabric.gateway.Gateway;
import dev.siddhant.mcpfabric.guard.ToolGuard;
import dev.siddhant.mcpfabric.upstream.StdioUpstream;
import dev.siddhant.mcpfabric.upstream.Upstream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The gateway as an OAuth 2.1 resource server (MCP authorization spec),
 * with human approvals, credential isolation, tool-metadata screening,
 * path-argument checks and request-size limits. Tokens are signed with real
 * RSA and EC keys published by an in-test JWKS server; upstreams are real
 * processes ("alpha" over stdio, "beta" over HTTP) that record everything
 * they receive.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecurityIntegrationTest {
    static final String ISS = "https://idp.test/realms/fabric", RESOURCE = "https://mcp.test/mcp";
    static final ObjectMapper JSON = new ObjectMapper();
    static RSAKey rsa1, rsa2, rogue;
    static ECKey ec1;
    static final List<JWK> published = new CopyOnWriteArrayList<>();
    static final AtomicInteger jwksFetches = new AtomicInteger();
    static HttpServer jwks;
    static Process beta;
    static Path audit, alphaCapture, betaCapture;
    /** every client token handed to the gateway, to prove none reaches an upstream */
    static final Set<String> issued = ConcurrentSet();

    static Set<String> ConcurrentSet() {
        return java.util.concurrent.ConcurrentHashMap.newKeySet();
    }

    @LocalServerPort
    int port;
    @Autowired
    Gateway gateway;
    final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        rsa1 = new RSAKeyGenerator(2048).keyID("r1").generate();
        rsa2 = new RSAKeyGenerator(2048).keyID("r2").generate();
        rogue = new RSAKeyGenerator(2048).keyID("rogue").generate();
        ec1 = new ECKeyGenerator(Curve.P_256).keyID("e1").generate();
        published.add(rsa1.toPublicJWK());
        published.add(ec1.toPublicJWK());
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/jwks", ex -> {
            jwksFetches.incrementAndGet();
            byte[] b = new JWKSet(List.copyOf(published)).toString(true).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        jwks.start();

        Path dir = Files.createTempDirectory("fabric-sec");
        audit = dir.resolve("audit.jsonl");
        alphaCapture = dir.resolve("alpha-capture.txt");
        betaCapture = dir.resolve("beta-capture.txt");
        int betaPort;
        try (var s = new ServerSocket(0)) {
            betaPort = s.getLocalPort();
        }
        beta = new ProcessBuilder(FabricIntegrationTest.demo("--name", "beta", "--http", String.valueOf(betaPort), "--capture", betaCapture.toString()))
                .redirectErrorStream(true).start();
        var out = new BufferedReader(new InputStreamReader(beta.getInputStream()));
        String line;
        while ((line = out.readLine()) != null && !line.contains("listening")) { }

        r.add("fabric.auth.mode", () -> "oauth");
        r.add("fabric.auth.issuer", () -> ISS);
        r.add("fabric.auth.resource", () -> RESOURCE);
        r.add("fabric.auth.jwks-uri", () -> "http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks");
        r.add("fabric.auth.clock-skew", () -> "30s");
        r.add("fabric.auth.jwks-min-refresh-interval", () -> "1s");
        r.add("fabric.auth.tenants[0].name", () -> "acme");
        r.add("fabric.auth.tenants[0].deny[0]", () -> "*__crash");
        r.add("fabric.auth.tenants[0].rate-per-minute", () -> "100000");
        r.add("fabric.auth.tenants[0].burst", () -> "1000");
        r.add("fabric.auth.tenants[1].name", () -> "readonly");
        r.add("fabric.auth.tenants[1].allow[0]", () -> "alpha__echo");
        r.add("fabric.approval.required-for[0]", () -> "alpha__add");
        r.add("fabric.approval.timeout", () -> "3s");
        List<String> alpha = FabricIntegrationTest.demo("--name", "alpha", "--threat", "--capture", alphaCapture.toString());
        for (int i = 0; i < alpha.size(); i++) r.add("fabric.upstreams[0].command[" + i + "]", alpha.get(i)::toString);
        r.add("fabric.upstreams[0].name", () -> "alpha");
        r.add("fabric.upstreams[0].type", () -> "stdio");
        r.add("fabric.upstreams[0].timeout", () -> "5s");
        r.add("fabric.upstreams[0].env.ALPHA_TOKEN", () -> "alpha-upstream-secret");
        r.add("fabric.upstreams[0].path-arguments[0]", () -> "path");
        r.add("fabric.upstreams[0].path-roots[0]", () -> "/srv/data");
        r.add("fabric.upstreams[1].name", () -> "beta");
        r.add("fabric.upstreams[1].type", () -> "http");
        r.add("fabric.upstreams[1].url", () -> "http://127.0.0.1:" + betaPort + "/mcp");
        r.add("fabric.upstreams[1].timeout", () -> "5s");
        r.add("fabric.upstreams[1].headers.Authorization", () -> "Bearer beta-upstream-secret");
        r.add("fabric.audit-file", audit::toString);
        r.add("fabric.max-request-bytes", () -> "1048576");
    }

    @AfterAll
    static void stop() {
        if (beta != null) beta.destroyForcibly();
        if (jwks != null) jwks.stop(0);
    }

    @BeforeEach
    void waitReady() throws Exception {
        long end = System.currentTimeMillis() + 30_000;
        while (gateway.upstreams().values().stream().anyMatch(u -> u.state() != Upstream.State.READY)) {
            assertThat(System.currentTimeMillis()).as("upstreams ready").isLessThan(end);
            Thread.sleep(50);
        }
    }

    // ------------------------------------------------------------ token minting

    static JWTClaimsSet.Builder claims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().issuer(ISS).audience(RESOURCE).subject("alice").claim("client_id", "ide")
                .claim("tenant", "acme").claim("scope", "mcp:tools mcp:server:*").issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)));
    }

    static String sign(JWK key, JWSAlgorithm alg, Consumer<JWTClaimsSet.Builder> mod) throws Exception {
        var b = claims();
        mod.accept(b);
        var jwt = new SignedJWT(new JWSHeader.Builder(alg).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), b.build());
        jwt.sign(key instanceof RSAKey k ? new RSASSASigner(k) : new ECDSASigner((ECKey) key));
        String t = jwt.serialize();
        issued.add(t);
        return t;
    }

    static String token(Consumer<JWTClaimsSet.Builder> mod) throws Exception {
        return sign(rsa1, JWSAlgorithm.RS256, mod);
    }

    static String token() throws Exception {
        return token(b -> { });
    }

    record Resp(int status, String challenge, JsonNode body) {
    }

    Resp initialize(String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}"));
        if (token != null) b.header("Authorization", "Bearer " + token);
        var r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.headers().firstValue("WWW-Authenticate").orElse(null), r.body().isEmpty() ? null : JSON.readTree(r.body()));
    }

    Resp admin(String method, String path, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Authorization", "Bearer " + token);
        b = method.equals("GET") ? b.GET() : b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
        var r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.headers().firstValue("WWW-Authenticate").orElse(null), r.body().isEmpty() ? null : JSON.readTree(r.body()));
    }

    McpClient client(String token) throws Exception {
        var c = new McpClient(port, token);
        assertThat(c.initialize().status()).isEqualTo(200);
        return c;
    }

    static String adminToken(String sub) throws Exception {
        return token(b -> b.subject(sub).claim("scope", "mcp:admin"));
    }

    void assertRejected(String token, int status, String description) throws Exception {
        Resp r = initialize(token);
        assertThat(r.status()).as(description).isEqualTo(status);
        assertThat(r.challenge()).as("challenge for " + description).startsWith("Bearer ").contains("resource_metadata=\"https://mcp.test/.well-known/oauth-protected-resource/mcp\"");
        if (status == 401) assertThat(r.challenge()).contains("error=\"invalid_token\"").contains("error_description=\"" + description + "\"");
    }

    // ------------------------------------------------------------ 1. OAuth resource server

    @Test
    @Order(1)
    void protectedResourceMetadataAndChallenge() throws Exception {
        for (String p : List.of("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp")) {
            var r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + p)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(200);
            JsonNode m = JSON.readTree(r.body());
            assertThat(m.path("resource").asText()).isEqualTo(RESOURCE);
            assertThat(m.path("authorization_servers").get(0).asText()).isEqualTo(ISS);
            assertThat(m.path("scopes_supported").toString()).contains("mcp:tools", "mcp:admin");
            assertThat(m.path("bearer_methods_supported").get(0).asText()).isEqualTo("header");
        }
        Resp none = initialize(null);
        assertThat(none.status()).isEqualTo(401);
        assertThat(none.challenge()).isEqualTo("Bearer realm=\"mcp-fabric\", resource_metadata=\"https://mcp.test/.well-known/oauth-protected-resource/mcp\"");
        // a token in the query string is not accepted (MCP spec: header only)
        var q = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp?access_token=" + token()))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(q.statusCode()).isEqualTo(401);
    }

    @Test
    @Order(2)
    void tokenValidationMatrix() throws Exception {
        assertThat(initialize(token()).status()).as("valid RS256").isEqualTo(200);
        assertThat(initialize(sign(ec1, JWSAlgorithm.ES256, b -> { })).status()).as("valid ES256").isEqualTo(200);
        assertThat(initialize(token(b -> b.audience(List.of("https://other.test", RESOURCE)))).status()).as("aud list containing us").isEqualTo(200);
        assertThat(initialize(token(b -> b.expirationTime(Date.from(Instant.now().minusSeconds(10))))).status())
                .as("expired 10 s ago, inside the 30 s skew").isEqualTo(200);
        int fetchesAfterWarmup = jwksFetches.get();
        assertThat(fetchesAfterWarmup).as("JWKS fetched once and cached").isEqualTo(1);

        assertRejected(token(b -> b.expirationTime(Date.from(Instant.now().minusSeconds(300)))), 401, "expired");
        assertRejected(token(b -> b.notBeforeTime(Date.from(Instant.now().plusSeconds(300)))), 401, "not_yet_valid");
        assertRejected(token(b -> b.audience("https://other.test/mcp")), 401, "audience");
        assertRejected(token(b -> b.audience((String) null)), 401, "audience");
        assertRejected(token(b -> b.issuer("https://evil.test")), 401, "issuer");
        assertRejected(sign(rogue, JWSAlgorithm.RS256, b -> { }), 401, "unknown_key");
        // right kid, wrong key: signature check fails
        RSAKey forged = new RSAKey.Builder(rogue).keyID("r1").build();
        assertRejected(sign(forged, JWSAlgorithm.RS256, b -> { }), 401, "signature");
        // alg=none
        String none = new PlainJWT(claims().build()).serialize();
        issued.add(none);
        assertRejected(none, 401, "alg_none");
        // HS256 keyed with the RSA public key: the classic algorithm-confusion attack
        var hs = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("r1").build(), claims().build());
        hs.sign(new MACSigner(rsa1.toPublicJWK().toRSAPublicKey().getEncoded()));
        issued.add(hs.serialize());
        assertRejected(hs.serialize(), 401, "algorithm_not_allowed");
        assertRejected("not.a.jwt", 401, "malformed");
        // missing scope -> 403 insufficient_scope naming the scope
        Resp noScope = initialize(token(b -> b.claim("scope", "openid profile")));
        assertThat(noScope.status()).isEqualTo(403);
        assertThat(noScope.challenge()).contains("error=\"insufficient_scope\"").contains("scope=\"mcp:tools\"");
        assertThat(initialize(token(b -> b.claim("tenant", "unknown"))).status()).as("unknown tenant").isEqualTo(403);
        assertThat(initialize(token(b -> b.claim("scope", null).claim("scp", List.of("mcp:tools")))).status()).as("scp array form").isEqualTo(200);

        // 10 more unknown-kid tokens cannot make the gateway hammer the IdP (rate-limited refresh)
        for (int i = 0; i < 10; i++) assertThat(initialize(sign(rogue, JWSAlgorithm.RS256, b -> { })).status()).isEqualTo(401);
        assertThat(jwksFetches.get() - fetchesAfterWarmup).as("JWKS refreshes caused by 11 unknown-kid tokens").isLessThanOrEqualTo(4);
    }

    @Test
    @Order(3)
    void signingKeyRotationIsPickedUp() throws Exception {
        Thread.sleep(1200); // past the 1 s minimum refresh interval
        String withNewKey = sign(rsa2, JWSAlgorithm.RS256, b -> { });
        assertThat(initialize(withNewKey).status()).as("new kid before it is published").isEqualTo(401);
        published.add(rsa2.toPublicJWK());
        Thread.sleep(1200);
        int before = jwksFetches.get();
        assertThat(initialize(withNewKey).status()).as("new kid after it is published").isEqualTo(200);
        assertThat(jwksFetches.get()).isGreaterThan(before);
        assertThat(initialize(token()).status()).as("old key still valid while published").isEqualTo(200);
    }

    @Test
    @Order(4)
    void scopesAndTenantsNarrowTheToolPolicy() throws Exception {
        var betaOnly = client(token(b -> b.claim("scope", "mcp:tools mcp:server:beta")));
        var names = allTools(betaOnly).stream().map(t -> t.path("name").asText()).toList();
        assertThat(names).isNotEmpty().allMatch(n -> n.startsWith("beta__"));
        assertThat(betaOnly.tool("alpha__echo", obj("text", "x")).error().path("code").asInt()).isEqualTo(Gateway.FORBIDDEN);
        assertThat(betaOnly.tool("beta__echo", obj("text", "hi")).result().path("content").get(0).path("text").asText()).isEqualTo("hi");

        var noServers = client(token(b -> b.claim("scope", "mcp:tools")));
        assertThat(noServers.call("tools/list", null).result().path("tools").size()).as("no server scope, no tools").isZero();

        var ro = client(token(b -> b.claim("tenant", "readonly")));
        assertThat(allTools(ro).stream().map(t -> t.path("name").asText()).toList()).containsExactly("alpha__echo");

        // a session is bound to the principal that created it
        var alice = client(token());
        var bob = new McpClient(port, token(b -> b.subject("bob")));
        bob.session = alice.session;
        assertThat(bob.call("ping", null).status()).isEqualTo(404);
        // the same principal may present a fresh token on the same session
        var alice2 = new McpClient(port, token());
        alice2.session = alice.session;
        assertThat(alice2.call("ping", null).status()).isEqualTo(200);
    }

    @Test
    @Order(5)
    void adminApiNeedsTheAdminScope() throws Exception {
        Resp asClient = admin("GET", "/admin/approvals", token());
        assertThat(asClient.status()).isEqualTo(403);
        assertThat(asClient.challenge()).contains("insufficient_scope").contains("scope=\"mcp:admin\"");
        assertThat(admin("GET", "/admin/approvals", "garbage").status()).isEqualTo(401);
        assertThat(admin("GET", "/admin/approvals", adminToken("carol")).status()).isEqualTo(200);
        // the admin scope alone does not open the MCP endpoint
        assertThat(initialize(adminToken("carol")).status()).isEqualTo(403);
    }

    // ------------------------------------------------------------ 2. human approvals

    JsonNode pendingFor(String tool) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            for (JsonNode a : admin("GET", "/admin/approvals", adminToken("carol")).body()) {
                if (a.path("tool").asText().equals(tool) && a.path("status").asText().equals("PENDING")) return a;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no pending approval for " + tool);
    }

    @Test
    @Order(6)
    void approvalsParkCallsUntilAnAdminDecides() throws Exception {
        var c = client(token());
        String carol = adminToken("carol");

        // approve
        var call = CompletableFuture.supplyAsync(() -> unchecked(() -> c.tool("alpha__add", obj("a", 1, "b", 2))));
        JsonNode p = pendingFor("alpha__add");
        assertThat(p.path("subject").asText()).isEqualTo("alice");
        assertThat(p.path("arguments").asText()).contains("\"a\":1");
        assertThat(call).isNotDone();
        String id = p.path("id").asText();
        // the requester cannot approve their own call, even with the admin scope
        assertThat(admin("POST", "/admin/approvals/" + id + "/approve", adminToken("alice")).status()).isEqualTo(403);
        Resp ok = admin("POST", "/admin/approvals/" + id + "/approve", carol);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().path("outcome").asText()).isEqualTo("APPLIED");
        assertThat(call.get().result().path("content").get(0).path("text").asText()).isEqualTo("3.0");
        // idempotent: the same decision again is a no-op; the opposite one conflicts
        assertThat(admin("POST", "/admin/approvals/" + id + "/approve", carol).body().path("outcome").asText()).isEqualTo("ALREADY");
        assertThat(admin("POST", "/admin/approvals/" + id + "/deny", carol).status()).isEqualTo(409);
        assertThat(admin("POST", "/admin/approvals/nope/approve", carol).status()).isEqualTo(404);

        // deny
        var denied = CompletableFuture.supplyAsync(() -> unchecked(() -> c.tool("alpha__add", obj("a", 5, "b", 5))));
        String id2 = pendingFor("alpha__add").path("id").asText();
        assertThat(admin("POST", "/admin/approvals/" + id2 + "/deny", carol).status()).isEqualTo(200);
        assertThat(denied.get().error().path("code").asInt()).isEqualTo(Gateway.FORBIDDEN);

        // timeout (3 s): the call fails, and a late approval is refused
        long t0 = System.currentTimeMillis();
        var expired = c.tool("alpha__add", obj("a", 7, "b", 7));
        assertThat(System.currentTimeMillis() - t0).isBetween(2500L, 8000L);
        assertThat(expired.error().path("code").asInt()).isEqualTo(Gateway.APPROVAL_TIMEOUT);
        String id3 = expired.error().path("data").path("approval").asText();
        assertThat(admin("POST", "/admin/approvals/" + id3 + "/approve", carol).status()).isEqualTo(409);

        // tools without the approval rule are not parked
        assertThat(c.tool("alpha__echo", obj("text", "fast")).result()).isNotNull();

        String log = Files.readString(audit);
        assertThat(log).contains("\"event\":\"approval_requested\"", "\"event\":\"approval_decided\"", "\"event\":\"approval_expired\"",
                "\"approver\":\"carol\"", "\"approved_by\":\"carol\"", "\"outcome\":\"approval_denied\"", "\"outcome\":\"approval_expired\"");
        assertThat(admin("GET", "/admin/audit/verify", carol).body().path("intact").asBoolean()).isTrue();
    }

    interface Call<T> {
        T run() throws Exception;
    }

    static <T> T unchecked(Call<T> c) {
        try {
            return c.run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------ 4. threats

    @Test
    @Order(7)
    void poisonedToolMetadataIsRedacted() throws Exception {
        var c = client(token());
        var all = allTools(c);
        JsonNode notes = all.stream().filter(t -> t.path("name").asText().equals("alpha__notes")).findFirst().orElseThrow();
        assertThat(notes.path("description").asText()).isEqualTo(ToolGuard.REDACTED_TEXT);
        assertThat(notes.path("inputSchema").path("properties").path("note").path("description").asText()).isEqualTo("the note");
        assertThat(all.toString()).doesNotContain("id_rsa").doesNotContain("​").doesNotContain("IMPORTANT");
        // benign tools are untouched
        assertThat(all.stream().filter(t -> t.path("name").asText().equals("alpha__echo")).findFirst().orElseThrow()
                .path("description").asText()).isEqualTo("Return the text");

        JsonNode report = null;
        for (JsonNode v : admin("GET", "/admin/tools", adminToken("carol")).body()) if (v.path("name").asText().equals("alpha__notes")) report = v;
        assertThat(report.path("state").asText()).isEqualTo("REDACTED");
        List<String> findings = new ArrayList<>();
        report.path("findings").forEach(f -> findings.add(f.asText()));
        assertThat(findings).contains("hidden_characters", "hidden_tag", "credential_reference", "override_instructions", "conceal_from_user", "cross_tool_directive");
        assertThat(Files.readString(audit)).contains("\"event\":\"tool_metadata_flagged\"");
    }

    static List<JsonNode> allTools(McpClient c) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        String cursor = null;
        do {
            var p = cursor == null ? null : obj("cursor", cursor);
            JsonNode r = c.call("tools/list", p).result();
            r.path("tools").forEach(out::add);
            cursor = r.hasNonNull("nextCursor") ? r.get("nextCursor").asText() : null;
        } while (cursor != null);
        return out;
    }

    int alphaCalls(McpClient c) throws Exception {
        String s = c.tool("alpha__stats", obj()).result().path("content").get(0).path("text").asText();
        return JSON.readTree(s).path("calls").asInt();
    }

    @Test
    @Order(8)
    void pathTraversalIsRejectedBeforeTheUpstream() throws Exception {
        var c = client(token());
        int before = alphaCalls(c);
        for (String bad : List.of("../etc/passwd", "docs/../../etc/shadow", "%2e%2e/%2e%2e/etc/passwd", "..\\..\\windows\\win.ini",
                "/etc/passwd", "/srv/database/x", "a\u0000b")) {
            var r = c.tool("alpha__read_file", obj("path", bad));
            assertThat(r.error()).as(bad).isNotNull();
            assertThat(r.error().path("code").asInt()).as(bad).isEqualTo(-32602);
        }
        assertThat(alphaCalls(c)).as("rejected calls never reached the upstream").isEqualTo(before + 1);
        assertThat(c.tool("alpha__read_file", obj("path", "docs/readme.txt")).result().path("content").get(0).path("text").asText())
                .isEqualTo("would read docs/readme.txt");
        assertThat(c.tool("alpha__read_file", obj("path", "/srv/data/report.csv")).result()).isNotNull();
        assertThat(Files.readString(audit)).contains("\"outcome\":\"path_rejected\"");
    }

    @Test
    @Order(9)
    void oversizedAndDeeplyNestedPayloadsAreRefused() throws Exception {
        var c = client(token());
        String big = "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"alpha__echo\",\"arguments\":{\"text\":\""
                + "x".repeat(1536 * 1024) + "\"}}}";
        var sized = http.send(c.req().POST(HttpRequest.BodyPublishers.ofString(big)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(sized.statusCode()).isEqualTo(413);
        byte[] bytes = big.getBytes(StandardCharsets.UTF_8);
        var chunked = http.send(c.req().POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(chunked.statusCode()).as("chunked body without Content-Length").isEqualTo(413);
        String deep = "[".repeat(5000) + "]".repeat(5000);
        String nested = "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{\"name\":\"alpha__echo\",\"arguments\":{\"text\":" + deep + "}}}";
        var d = http.send(c.req().POST(HttpRequest.BodyPublishers.ofString(nested)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(d.statusCode()).as("nesting deeper than Jackson's limit").isEqualTo(400);
        // a normal call still works
        assertThat(c.tool("alpha__echo", obj("text", "small")).result()).isNotNull();
    }

    // ------------------------------------------------------------ 3. credential isolation

    @Test
    @Order(10)
    void clientTokensNeverReachUpstreamsAndUpstreamCredentialsAreInjected() throws Exception {
        var c = client(token());
        assertThat(c.tool("beta__echo", obj("text", "to beta")).result()).isNotNull();
        assertThat(c.tool("alpha__echo", obj("text", "to alpha")).result()).isNotNull();
        String a = Files.readString(alphaCapture), b = Files.readString(betaCapture);
        assertThat(issued).hasSizeGreaterThan(20);
        for (String t : issued) {
            String sig = t.substring(t.lastIndexOf('.') + 1);
            String body = t.split("\\.")[1];
            for (String part : sig.isEmpty() ? List.of(body) : List.of(sig, body)) {
                assertThat(a).doesNotContain(part);
                assertThat(b).doesNotContain(part);
            }
        }
        assertThat(b).contains("HEADER Authorization: Bearer beta-upstream-secret");
        assertThat(b.lines().filter(l -> l.startsWith("HEADER Authorization:")).distinct()).containsExactly("HEADER Authorization: Bearer beta-upstream-secret");
        assertThat(a).contains("ENV ALPHA_TOKEN=alpha-upstream-secret");
        Set<String> envNames = new HashSet<>();
        a.lines().filter(l -> l.startsWith("ENV ")).forEach(l -> envNames.add(l.substring(4, l.indexOf('='))));
        var allowed = new HashSet<>(StdioUpstream.ENV_ALLOWLIST);
        allowed.add("ALPHA_TOKEN");
        assertThat(allowed).as("the stdio child sees only allowlisted variables").containsAll(envNames);
    }

    // ------------------------------------------------------------ 4b. rug pull (changes alpha's catalog: last)

    @Test
    @Order(11)
    void changedToolDefinitionIsQuarantinedUntilAccepted() throws Exception {
        var c = client(token());
        assertThat(c.tool("alpha__echo", obj("text", "before")).result()).isNotNull();
        assertThat(c.tool("alpha__rugpull", obj()).result()).isNotNull();
        long end = System.currentTimeMillis() + 10_000;
        while (allTools(c).stream().anyMatch(t -> t.path("name").asText().equals("alpha__echo"))) {
            assertThat(System.currentTimeMillis()).as("echo hidden after its definition changed").isLessThan(end);
            Thread.sleep(50);
        }
        var r = c.tool("alpha__echo", obj("text", "after"));
        assertThat(r.error().path("code").asInt()).isEqualTo(Gateway.FORBIDDEN);
        assertThat(r.error().path("message").asText()).contains("quarantined");
        assertThat(Files.readString(audit)).contains("\"event\":\"tool_definition_changed\"", "\"outcome\":\"quarantined\"");

        Resp acc = admin("POST", "/admin/tools/alpha__echo/accept", adminToken("carol"));
        assertThat(acc.status()).isEqualTo(200);
        // accepted, but the new description is poisoned, so clients see it redacted
        assertThat(acc.body().path("state").asText()).isEqualTo("REDACTED");
        JsonNode echo = allTools(c).stream().filter(t -> t.path("name").asText().equals("alpha__echo")).findFirst().orElseThrow();
        assertThat(echo.path("description").asText()).isEqualTo(ToolGuard.REDACTED_TEXT);
        assertThat(c.tool("alpha__echo", obj("text", "after accept")).result().path("content").get(0).path("text").asText()).isEqualTo("after accept");
        assertThat(Files.readString(audit)).contains("\"event\":\"tool_pin_accepted\"");
    }
}
