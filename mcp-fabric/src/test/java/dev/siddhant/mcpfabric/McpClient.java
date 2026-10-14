package dev.siddhant.mcpfabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicLong;

/** Minimal MCP Streamable HTTP client for the tests. */
public class McpClient {
    static final ObjectMapper JSON = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();
    final URI url;
    final String key;
    String session;
    final AtomicLong ids = new AtomicLong(1);

    public record Reply(int status, JsonNode body) {
        JsonNode result() { return body.get("result"); }
        JsonNode error() { return body.get("error"); }
    }

    McpClient(int port, String key) {
        this.url = URI.create("http://127.0.0.1:" + port + "/mcp");
        this.key = key;
    }

    HttpRequest.Builder req() {
        var b = HttpRequest.newBuilder(url).header("Content-Type", "application/json");
        if (key != null) b.header("Authorization", "Bearer " + key);
        if (session != null) b.header("Mcp-Session-Id", session);
        return b;
    }

    Reply initialize() throws Exception {
        ObjectNode p = JSON.createObjectNode().put("protocolVersion", "2025-06-18");
        p.putObject("capabilities");
        p.putObject("clientInfo").put("name", "test").put("version", "1");
        var resp = http.send(req().POST(HttpRequest.BodyPublishers.ofString(msg("initialize", p, ids.getAndIncrement()).toString())).build(),
                HttpResponse.BodyHandlers.ofString());
        resp.headers().firstValue("Mcp-Session-Id").ifPresent(s -> session = s);
        Reply r = new Reply(resp.statusCode(), JSON.readTree(resp.body()));
        if (resp.statusCode() == 200) notify("notifications/initialized", null);
        return r;
    }

    static ObjectNode msg(String method, JsonNode params, Long id) {
        ObjectNode m = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", method);
        if (id != null) m.put("id", id);
        if (params != null) m.set("params", params);
        return m;
    }

    Reply call(String method, JsonNode params) throws Exception {
        return send(msg(method, params, ids.getAndIncrement()));
    }

    Reply send(JsonNode m) throws Exception {
        var resp = http.send(req().POST(HttpRequest.BodyPublishers.ofString(m.toString())).build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(resp.statusCode(), resp.body().isEmpty() ? null : JSON.readTree(resp.body()));
    }

    int notify(String method, JsonNode params) throws Exception {
        var resp = http.send(req().POST(HttpRequest.BodyPublishers.ofString(msg(method, params, null).toString())).build(),
                HttpResponse.BodyHandlers.discarding());
        return resp.statusCode();
    }

    Reply tool(String name, JsonNode args) throws Exception {
        ObjectNode p = JSON.createObjectNode().put("name", name);
        p.set("arguments", args);
        return call("tools/call", p);
    }

    static ObjectNode obj(Object... kv) {
        ObjectNode o = JSON.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) o.set((String) kv[i], JSON.valueToTree(kv[i + 1]));
        return o;
    }
}
